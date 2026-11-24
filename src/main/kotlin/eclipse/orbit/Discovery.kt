package eclipse.orbit

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress

/**
 * LAN discovery, modelled on Jellyfin's: Eclipse broadcasts `who is Orbit?` to UDP
 * [OrbitConfig.discoveryPort] and every server replies with its id, name and HTTP port.
 * Broadcasts only reach the container with host networking (see compose.yaml).
 */
class Discovery(private val config: OrbitConfig, private val store: Store) {
    private val log = LoggerFactory.getLogger(Discovery::class.java)

    fun start() {
        Thread({ run() }, "orbit-discovery").apply { isDaemon = true }.start()
    }

    private fun run() {
        try {
            DatagramSocket(null).use { socket ->
                socket.reuseAddress = true
                socket.broadcast = true
                socket.bind(InetSocketAddress(config.discoveryPort))
                log.info("Discovery listening on UDP {}", config.discoveryPort)
                val buffer = ByteArray(512)
                while (true) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    socket.receive(packet)
                    val message = String(packet.data, 0, packet.length).trim()
                    if (!message.equals(QUERY, ignoreCase = true)) continue
                    val reply = buildJsonObject {
                        put("id", store.serverId)
                        put("name", config.serverName)
                        put("port", config.httpPort)
                        config.publicUrl?.let { put("url", it) }
                    }.toString().toByteArray()
                    socket.send(DatagramPacket(reply, reply.size, packet.socketAddress))
                }
            }
        } catch (e: Exception) {
            log.warn("Discovery disabled: {}", e.message)
        }
    }

    companion object {
        const val QUERY = "who is Orbit?"
    }
}
