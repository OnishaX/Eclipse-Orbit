package eclipse.orbit

import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.put
import java.util.concurrent.ConcurrentHashMap

/**
 * Relays JSON envelopes `{ "to": deviceId, "type": "...", "payload": {...} }` between paired
 * devices, stamping `from`. Used for messaging, file-share offers and WebRTC call signalling
 * (offer/answer/ice); media itself flows peer-to-peer on the LAN.
 */
class SignalHub {
    private val sessions = ConcurrentHashMap<String, DefaultWebSocketServerSession>()

    fun isOnline(deviceId: String) = sessions.containsKey(deviceId)

    suspend fun handle(deviceId: String, session: DefaultWebSocketServerSession) {
        sessions.put(deviceId, session)?.close()
        broadcastPresence(deviceId, true)
        try {
            for (frame in session.incoming) {
                if (frame !is Frame.Text) continue
                val envelope = runCatching { Json.parseToJsonElement(frame.readText()).jsonObject }.getOrNull() ?: continue
                val to = envelope["to"]?.jsonPrimitive?.content ?: continue
                val relayed = JsonObject(envelope + ("from" to JsonPrimitive(deviceId)))
                sessions[to]?.send(Frame.Text(relayed.toString()))
            }
        } finally {
            sessions.remove(deviceId, session)
            broadcastPresence(deviceId, false)
        }
    }

    suspend fun disconnect(deviceId: String) {
        sessions.remove(deviceId)?.close()
    }

    private suspend fun broadcastPresence(deviceId: String, online: Boolean) {
        val message = buildJsonObject {
            put("type", "presence")
            put("deviceId", deviceId)
            put("online", online)
        }.toString()
        sessions.forEach { (id, s) -> if (id != deviceId) runCatching { s.send(Frame.Text(message)) } }
    }
}
