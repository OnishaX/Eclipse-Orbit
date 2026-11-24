package eclipse.orbit

import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap

class PairingRequest(
    val code: String,
    val secretHash: String,
    val name: String,
    val model: String,
    val expiresAt: Long,
) {
    @Volatile var status: String = "pending"
    @Volatile var deviceId: String? = null
    @Volatile var token: String? = null
}

/**
 * QR onboarding: the TV starts a request and shows `pairUrl` as a QR code; a phone opens it,
 * signs in to the dashboard and approves; the TV polls with its secret and receives a device token.
 */
class Pairing(private val store: Store, private val ttlMillis: Long) {
    private val requests = ConcurrentHashMap<String, PairingRequest>()
    private val random = SecureRandom()

    fun start(name: String, model: String): Pair<PairingRequest, String> {
        prune()
        val secret = Store.randomToken()
        var code: String
        do code = (1..6).map { ALPHABET[random.nextInt(ALPHABET.length)] }.joinToString("")
        while (requests.containsKey(code))
        val request = PairingRequest(code, Store.sha256(secret), name, model, System.currentTimeMillis() + ttlMillis)
        requests[code] = request
        return request to secret
    }

    fun get(code: String): PairingRequest? {
        prune()
        return requests[code.uppercase()]
    }

    fun approve(code: String, name: String?): Boolean {
        val request = get(code)?.takeIf { it.status == "pending" } ?: return false
        synchronized(request) {
            if (request.status != "pending") return false
            val (device, token) = store.addDevice(name?.ifBlank { null } ?: request.name, request.model)
            request.deviceId = device.id
            request.token = token
            request.status = "approved"
        }
        return true
    }

    fun deny(code: String): Boolean {
        val request = get(code)?.takeIf { it.status == "pending" } ?: return false
        request.status = "denied"
        return true
    }

    /** Returns the request for its owner; an approved token is handed out once, then the request is discarded. */
    fun poll(code: String, secret: String): PairingRequest? {
        val request = get(code) ?: return null
        if (request.secretHash != Store.sha256(secret)) return null
        if (request.status != "pending") requests.remove(request.code)
        return request
    }

    private fun prune() {
        val now = System.currentTimeMillis()
        requests.values.removeIf { it.expiresAt < now }
    }

    companion object {
        private const val ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
    }
}
