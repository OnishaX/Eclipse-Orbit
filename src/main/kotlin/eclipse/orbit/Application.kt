package eclipse.orbit

import io.ktor.http.ContentType
import io.ktor.http.Cookie
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.http.content.staticResources
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.origin
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.pingPeriod
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.close
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.seconds

class HttpError(val status: HttpStatusCode, message: String) : RuntimeException(message)

@Serializable data class Credentials(val username: String, val password: String)
@Serializable data class NameBody(val name: String)
@Serializable data class PairStartBody(val name: String, val model: String = "Android TV")
@Serializable data class PairPollBody(val secret: String)
@Serializable data class ApproveBody(val name: String? = null)

private const val SESSION_COOKIE = "orbit_session"
private const val SESSION_TTL_MS = 7L * 24 * 60 * 60 * 1000

fun main() {
    val config = OrbitConfig.fromEnv()
    val store = Store(config.dataDir)
    store.bootstrapAdmin(config.adminUser, config.adminPassword)
    Discovery(config, store).start()
    embeddedServer(Netty, port = config.httpPort, host = "0.0.0.0") {
        orbit(config, store)
    }.start(wait = true)
}

fun Application.orbit(config: OrbitConfig, store: Store) {
    val pairing = Pairing(store, config.pairingTtlSeconds * 1000)
    val signals = SignalHub()
    val adminSessions = ConcurrentHashMap<String, Pair<String, Long>>()
    val dashboardHtml = Application::class.java.getResource("/dashboard/index.html")!!.readText()

    install(CallLogging)
    install(ContentNegotiation) { json() }
    install(WebSockets) { pingPeriod = 15.seconds }
    install(StatusPages) {
        exception<HttpError> { call, e -> call.respond(e.status, mapOf("error" to e.message)) }
        exception<io.ktor.server.plugins.BadRequestException> { call, _ ->
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid request"))
        }
    }

    fun ApplicationCall.admin(): String {
        val id = request.cookies[SESSION_COOKIE] ?: throw HttpError(HttpStatusCode.Unauthorized, "Sign in required")
        val (user, expires) = adminSessions[id] ?: throw HttpError(HttpStatusCode.Unauthorized, "Sign in required")
        if (expires < System.currentTimeMillis()) {
            adminSessions.remove(id)
            throw HttpError(HttpStatusCode.Unauthorized, "Session expired")
        }
        return user
    }

    fun ApplicationCall.startSession(username: String) {
        val id = Store.randomToken()
        adminSessions[id] = username to System.currentTimeMillis() + SESSION_TTL_MS
        response.cookies.append(
            Cookie(SESSION_COOKIE, id, maxAge = (SESSION_TTL_MS / 1000).toInt(), path = "/", httpOnly = true, extensions = mapOf("SameSite" to "Strict")),
        )
    }

    fun ApplicationCall.device(): Device {
        val token = request.headers[HttpHeaders.Authorization]?.removePrefix("Bearer ")?.trim()
            ?: request.queryParameters["token"]
            ?: throw HttpError(HttpStatusCode.Unauthorized, "Device token required")
        val device = store.deviceForToken(token) ?: throw HttpError(HttpStatusCode.Unauthorized, "Unknown device")
        store.touchDevice(device.id)
        return device
    }

    fun ApplicationCall.baseUrl(): String =
        config.publicUrl ?: "${request.origin.scheme}://${request.headers[HttpHeaders.Host] ?: "${request.origin.serverHost}:${config.httpPort}"}"

    fun deviceJson(d: Device) = buildJsonObject {
        put("id", d.id)
        put("name", d.name)
        put("model", d.model)
        put("pairedAt", d.pairedAt)
        put("lastSeen", d.lastSeen)
        put("online", signals.isOnline(d.id))
        put("hasBackup", store.hasBackup(d.id))
    }

    fun Route.adminApi() = route("/api/admin") {
        get("/status") {
            val signedIn = runCatching { call.admin() }.isSuccess
            call.respond(buildJsonObject {
                put("serverName", config.serverName)
                put("needsSetup", !store.hasAdmin)
                put("signedIn", signedIn)
            })
        }
        post("/setup") {
            val body = call.receive<Credentials>()
            if (body.username.isBlank() || body.password.length < 8) throw HttpError(HttpStatusCode.BadRequest, "Password must be at least 8 characters")
            if (!store.createFirstAdmin(body.username.trim(), body.password)) throw HttpError(HttpStatusCode.Conflict, "Already set up")
            call.startSession(body.username.trim())
            call.respond(HttpStatusCode.Created, mapOf("ok" to true))
        }
        post("/login") {
            val body = call.receive<Credentials>()
            if (!store.checkAdmin(body.username, body.password)) throw HttpError(HttpStatusCode.Unauthorized, "Incorrect username or password")
            call.startSession(body.username)
            call.respond(mapOf("ok" to true))
        }
        post("/logout") {
            call.request.cookies[SESSION_COOKIE]?.let { adminSessions.remove(it) }
            call.response.cookies.append(Cookie(SESSION_COOKIE, "", maxAge = 0, path = "/"))
            call.respond(mapOf("ok" to true))
        }
        get("/devices") {
            call.admin()
            call.respond(store.devices().map(::deviceJson))
        }
        delete("/devices/{id}") {
            call.admin()
            val id = call.parameters["id"]!!
            signals.disconnect(id)
            store.removeDevice(id)
            call.respond(mapOf("ok" to true))
        }
        get("/profiles") {
            call.admin()
            call.respond(store.profiles())
        }
        post("/profiles") {
            call.admin()
            val name = call.receive<NameBody>().name.trim()
            if (name.isEmpty()) throw HttpError(HttpStatusCode.BadRequest, "Name required")
            call.respond(HttpStatusCode.Created, store.addProfile(name))
        }
        delete("/profiles/{id}") {
            call.admin()
            store.removeProfile(call.parameters["id"]!!)
            call.respond(mapOf("ok" to true))
        }
        get("/pair/{code}") {
            call.admin()
            val request = pairing.get(call.parameters["code"]!!) ?: throw HttpError(HttpStatusCode.NotFound, "Pairing code expired or not found")
            call.respond(buildJsonObject {
                put("code", request.code)
                put("name", request.name)
                put("model", request.model)
                put("status", request.status)
                put("expiresAt", request.expiresAt)
            })
        }
        post("/pair/{code}/approve") {
            call.admin()
            val body = runCatching { call.receive<ApproveBody>() }.getOrDefault(ApproveBody())
            if (!pairing.approve(call.parameters["code"]!!, body.name)) throw HttpError(HttpStatusCode.NotFound, "Pairing code expired or already used")
            call.respond(mapOf("ok" to true))
        }
        post("/pair/{code}/deny") {
            call.admin()
            if (!pairing.deny(call.parameters["code"]!!)) throw HttpError(HttpStatusCode.NotFound, "Pairing code expired or already used")
            call.respond(mapOf("ok" to true))
        }
    }

    fun Route.deviceApi() = route("/api/v1") {
        get("/info") {
            call.respond(buildJsonObject {
                put("id", store.serverId)
                put("name", config.serverName)
                put("version", "0.1.0")
            })
        }
        post("/pair") {
            val body = call.receive<PairStartBody>()
            val (request, secret) = pairing.start(body.name.take(64), body.model.take(64))
            call.respond(HttpStatusCode.Created, buildJsonObject {
                put("code", request.code)
                put("secret", secret)
                put("pairUrl", "${call.baseUrl()}/pair/${request.code}")
                put("expiresAt", request.expiresAt)
            })
        }
        post("/pair/{code}/poll") {
            val secret = call.receive<PairPollBody>().secret
            val request = pairing.poll(call.parameters["code"]!!, secret)
                ?: throw HttpError(HttpStatusCode.NotFound, "Pairing code expired or not found")
            call.respond(buildJsonObject {
                put("status", request.status)
                request.deviceId?.let { put("deviceId", it) }
                request.token?.let { put("token", it) }
            })
        }
        get("/me") {
            call.respond(deviceJson(call.device()))
        }
        delete("/me") {
            val device = call.device()
            signals.disconnect(device.id)
            store.removeDevice(device.id)
            call.respond(mapOf("ok" to true))
        }
        get("/profiles") {
            call.device()
            call.respond(store.profiles())
        }
        get("/devices") {
            val self = call.device()
            call.respond(store.devices().filter { it.id != self.id }.map(::deviceJson))
        }
        put("/backup") {
            val device = call.device()
            store.saveBackup(device.id, call.receive<JsonElement>())
            call.respond(mapOf("ok" to true))
        }
        get("/backup") {
            val device = call.device()
            val backup = store.loadBackup(device.id) ?: throw HttpError(HttpStatusCode.NotFound, "No backup")
            call.respond(backup)
        }
        webSocket("/signal") {
            val device = runCatching { call.device() }.getOrNull()
            if (device == null) {
                close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Unknown device"))
                return@webSocket
            }
            signals.handle(device.id, this)
        }
    }

    routing {
        get("/health") { call.respondText("ok") }
        get("/") { call.respondText(dashboardHtml, ContentType.Text.Html) }
        get("/pair/{code}") { call.respondText(dashboardHtml, ContentType.Text.Html) }
        staticResources("/assets", "dashboard")
        adminApi()
        deviceApi()
    }
}
