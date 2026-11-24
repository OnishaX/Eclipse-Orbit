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
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.seconds

class HttpError(val status: HttpStatusCode, message: String) : RuntimeException(message)

@Serializable data class Credentials(val username: String, val password: String)
@Serializable data class NameBody(val name: String)
@Serializable data class PairStartBody(val name: String, val model: String = "Android TV")
@Serializable data class PairPollBody(val secret: String)
@Serializable data class ApproveBody(val name: String? = null)
@Serializable data class ProfileIdsBody(val profileIds: List<String>)
@Serializable data class ProfileVaultBody(val baseRevision: Long, val salt: String, val nonce: String, val ciphertext: String)
@Serializable data class ProfileVaultList(val vaults: List<ProfileVault>)
@Serializable data class AssignedProfilesBody(val profiles: List<AssignedProfile>)
@Serializable data class PassphraseVerifierBody(val verifier: PassphraseVerifier?)

private fun validVault(body: ProfileVaultBody): Boolean {
    if (body.baseRevision < 0 || body.salt.length > 24 || body.nonce.length > 16 ||
        body.ciphertext.length > 174764) return false
    return try {
        val decoder = Base64.getDecoder()
        decoder.decode(body.salt).size == 16 &&
            decoder.decode(body.nonce).size == 12 &&
            decoder.decode(body.ciphertext).size in 16..131072
    } catch (_: IllegalArgumentException) {
        false
    }
}

private fun validName(name: String): String = name.trim().also {
    if (it.length !in 1..32) throw HttpError(HttpStatusCode.BadRequest, "Use a name of 1 to 32 characters")
}

private const val SESSION_COOKIE = "orbit_session"
private const val SESSION_TTL_MS = 7L * 24 * 60 * 60 * 1000
private const val PASSPHRASE_VERIFIER_CIPHERTEXT_BYTES = 27 + 16

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
        put("profileIds", JsonArray(d.profileIds.map(::JsonPrimitive)))
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
        put("/devices/{id}/profiles") {
            call.admin()
            val ids = call.receive<ProfileIdsBody>().profileIds
            val device = try { store.setDeviceProfiles(call.parameters["id"]!!, ids) }
            catch (_: IllegalArgumentException) { throw HttpError(HttpStatusCode.BadRequest, "Invalid user assignment") }
            if (device == null) throw HttpError(HttpStatusCode.NotFound, "Device not found")
            call.respond(deviceJson(device))
        }
        get("/profiles") {
            call.admin()
            call.respond(store.profiles())
        }
        post("/profiles") {
            call.admin()
            call.respond(HttpStatusCode.Created, store.addProfile(validName(call.receive<NameBody>().name)))
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
        get("/profile-vaults") {
            val device = call.device()
            val vaults = store.assignedProfileVaults(device.id)
                ?: throw HttpError(HttpStatusCode.Unauthorized, "Unknown device")
            call.respond(ProfileVaultList(vaults))
        }
        get("/assigned-profiles") {
            val device = call.device()
            val profiles = store.assignedProfiles(device.id)
                ?: throw HttpError(HttpStatusCode.Unauthorized, "Unknown device")
            call.respond(AssignedProfilesBody(profiles))
        }
        put("/profiles/{id}") {
            val device = call.device()
            val id = call.parameters["id"]!!
            val validId = try { java.util.UUID.fromString(id).toString() == id } catch (_: IllegalArgumentException) { false }
            if (!validId) throw HttpError(HttpStatusCode.BadRequest, "Invalid user ID")
            val name = validName(call.receive<NameBody>().name)
            val profile = try { store.createDeviceProfile(device.id, id, name) }
            catch (_: IllegalArgumentException) { throw HttpError(HttpStatusCode.Conflict, "User ID already exists") }
            if (profile == null) throw HttpError(HttpStatusCode.Unauthorized, "Unknown device")
            call.respond(profile)
        }
        get("/passphrase-verifier") {
            val device = call.device()
            val (found, verifier) = store.passphraseVerifier(device.id)
            if (!found) throw HttpError(HttpStatusCode.Unauthorized, "Unknown device")
            call.respond(PassphraseVerifierBody(verifier))
        }
        put("/passphrase-verifier") {
            val device = call.device()
            val verifier = call.receive<PassphraseVerifier>()
            if (!validVault(ProfileVaultBody(0, verifier.salt, verifier.nonce, verifier.ciphertext)) ||
                Base64.getDecoder().decode(verifier.ciphertext).size != PASSPHRASE_VERIFIER_CIPHERTEXT_BYTES) {
                throw HttpError(HttpStatusCode.BadRequest, "Invalid passphrase verifier")
            }
            when (val result = store.savePassphraseVerifier(device.id, verifier)) {
                is SavePassphraseVerifierResult.Saved -> call.respond(HttpStatusCode.Created, result.verifier)
                is SavePassphraseVerifierResult.Existing -> call.respond(result.verifier)
                SavePassphraseVerifierResult.ExistingVaults ->
                    throw HttpError(HttpStatusCode.Conflict, "Existing profiles require passphrase migration")
                SavePassphraseVerifierResult.UnknownDevice ->
                    throw HttpError(HttpStatusCode.Unauthorized, "Unknown device")
            }
        }
        put("/profile-vaults/{id}") {
            val device = call.device()
            val body = call.receive<ProfileVaultBody>()
            if (!validVault(body)) throw HttpError(HttpStatusCode.BadRequest, "Invalid encrypted profile")
            when (val result = store.saveProfileVault(device.id, call.parameters["id"]!!,
                body.baseRevision, body.salt, body.nonce, body.ciphertext)) {
                is SaveProfileVaultResult.Saved -> call.respond(result.vault)
                SaveProfileVaultResult.NotAssigned -> throw HttpError(HttpStatusCode.Forbidden, "User not assigned to this device")
                SaveProfileVaultResult.Conflict -> throw HttpError(HttpStatusCode.Conflict, "Profile changed; fetch the latest version")
            }
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
