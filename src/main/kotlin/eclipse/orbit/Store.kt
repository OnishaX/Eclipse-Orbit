package eclipse.orbit

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

@Serializable
data class Admin(val username: String, val passwordHash: String, val salt: String)

@Serializable
data class Profile(val id: String, val name: String, val createdAt: Long)

@Serializable
data class Device(
    val id: String,
    val name: String,
    val model: String,
    val tokenHash: String,
    val pairedAt: Long,
    val lastSeen: Long,
)

@Serializable
data class State(
    val serverId: String = UUID.randomUUID().toString(),
    val admins: List<Admin> = emptyList(),
    val profiles: List<Profile> = emptyList(),
    val devices: List<Device> = emptyList(),
)

/** JSON-file persistence under the data volume. All mutations are serialised through [lock]. */
class Store(private val dataDir: Path) {
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true; encodeDefaults = true }
    private val stateFile = dataDir.resolve("state.json")
    private val backupsDir = dataDir.resolve("backups")
    private val lock = Any()
    private var state: State

    init {
        Files.createDirectories(backupsDir)
        state = if (Files.exists(stateFile)) json.decodeFromString(Files.readString(stateFile)) else State()
        save()
    }

    val serverId: String get() = state.serverId
    val hasAdmin: Boolean get() = state.admins.isNotEmpty()

    fun devices(): List<Device> = state.devices
    fun profiles(): List<Profile> = state.profiles

    fun bootstrapAdmin(username: String, password: String?) = synchronized(lock) {
        if (state.admins.none { it.username == username } && password != null) {
            update { it.copy(admins = it.admins + newAdmin(username, password)) }
        }
    }

    fun createFirstAdmin(username: String, password: String): Boolean = synchronized(lock) {
        if (hasAdmin) return false
        update { it.copy(admins = listOf(newAdmin(username, password))) }
        true
    }

    fun checkAdmin(username: String, password: String): Boolean {
        val admin = state.admins.firstOrNull { it.username == username } ?: return false
        return MessageDigest.isEqual(
            hashPassword(password, Base64.getDecoder().decode(admin.salt)).toByteArray(),
            admin.passwordHash.toByteArray(),
        )
    }

    fun addProfile(name: String): Profile = synchronized(lock) {
        val profile = Profile(UUID.randomUUID().toString(), name, System.currentTimeMillis())
        update { it.copy(profiles = it.profiles + profile) }
        profile
    }

    fun removeProfile(id: String) = synchronized(lock) {
        update { s -> s.copy(profiles = s.profiles.filterNot { it.id == id }) }
    }

    /** Registers a device and returns it with its plaintext bearer token (only ever returned once). */
    fun addDevice(name: String, model: String): Pair<Device, String> = synchronized(lock) {
        val token = randomToken()
        val now = System.currentTimeMillis()
        val device = Device(UUID.randomUUID().toString(), name, model, sha256(token), now, now)
        update { it.copy(devices = it.devices + device) }
        device to token
    }

    fun removeDevice(id: String) = synchronized(lock) {
        update { s -> s.copy(devices = s.devices.filterNot { it.id == id }) }
        Files.deleteIfExists(backupsDir.resolve("$id.json"))
    }

    fun deviceForToken(token: String): Device? {
        val hash = sha256(token)
        return state.devices.firstOrNull { MessageDigest.isEqual(it.tokenHash.toByteArray(), hash.toByteArray()) }
    }

    fun touchDevice(id: String) = synchronized(lock) {
        update { s -> s.copy(devices = s.devices.map { if (it.id == id) it.copy(lastSeen = System.currentTimeMillis()) else it }) }
    }

    fun saveBackup(deviceId: String, backup: JsonElement) {
        writeAtomically(backupsDir.resolve("$deviceId.json"), json.encodeToString(JsonElement.serializer(), backup))
    }

    fun loadBackup(deviceId: String): JsonElement? {
        val file = backupsDir.resolve("$deviceId.json")
        return if (Files.exists(file)) json.parseToJsonElement(Files.readString(file)) else null
    }

    fun hasBackup(deviceId: String): Boolean = Files.exists(backupsDir.resolve("$deviceId.json"))

    private fun update(transform: (State) -> State) {
        state = transform(state)
        save()
    }

    private fun save() = writeAtomically(stateFile, json.encodeToString(State.serializer(), state))

    private fun writeAtomically(target: Path, content: String) {
        val tmp = target.resolveSibling("${target.fileName}.tmp")
        Files.writeString(tmp, content)
        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    private fun newAdmin(username: String, password: String): Admin {
        val salt = ByteArray(16).also { random.nextBytes(it) }
        return Admin(username, hashPassword(password, salt), Base64.getEncoder().encodeToString(salt))
    }

    companion object {
        private val random = SecureRandom()

        fun randomToken(bytes: Int = 32): String =
            Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(bytes).also { random.nextBytes(it) })

        fun sha256(value: String): String =
            Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(value.toByteArray()))

        private fun hashPassword(password: String, salt: ByteArray): String {
            val spec = PBEKeySpec(password.toCharArray(), salt, 210_000, 256)
            val hash = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
            return Base64.getEncoder().encodeToString(hash)
        }
    }
}
