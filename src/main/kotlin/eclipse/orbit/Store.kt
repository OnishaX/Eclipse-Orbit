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
data class ProfileVault(
    val profileId: String,
    val revision: Long,
    val salt: String,
    val nonce: String,
    val ciphertext: String,
)

@Serializable
data class PassphraseVerifier(val salt: String, val nonce: String, val ciphertext: String)

sealed interface SavePassphraseVerifierResult {
    data class Saved(val verifier: PassphraseVerifier) : SavePassphraseVerifierResult
    data class Existing(val verifier: PassphraseVerifier) : SavePassphraseVerifierResult
    data object ExistingVaults : SavePassphraseVerifierResult
    data object UnknownDevice : SavePassphraseVerifierResult
}

@Serializable
data class AssignedProfile(val profile: Profile, val vault: ProfileVault?)

sealed interface SaveProfileVaultResult {
    data class Saved(val vault: ProfileVault) : SaveProfileVaultResult
    data object NotAssigned : SaveProfileVaultResult
    data object Conflict : SaveProfileVaultResult
}

@Serializable
data class Device(
    val id: String,
    val name: String,
    val model: String,
    val tokenHash: String,
    val pairedAt: Long,
    val lastSeen: Long,
    val profileIds: List<String> = emptyList(),
)

@Serializable
data class State(
    val serverId: String = UUID.randomUUID().toString(),
    val admins: List<Admin> = emptyList(),
    val profiles: List<Profile> = emptyList(),
    val devices: List<Device> = emptyList(),
    val profileVaults: List<ProfileVault> = emptyList(),
    val passphraseVerifier: PassphraseVerifier? = null,
)

/** JSON-file persistence under the data volume. All mutations are serialised through [lock]. */
class Store(private val dataDir: Path) {
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true; encodeDefaults = true }
    private val stateFile = dataDir.resolve("state.json")
    private val backupsDir = dataDir.resolve("backups")
    private val lock = Any()
    @Volatile private var state: State

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

    fun createDeviceProfile(deviceId: String, id: String, name: String): Profile? = synchronized(lock) {
        val device = state.devices.firstOrNull { it.id == deviceId } ?: return null
        val existing = state.profiles.firstOrNull { it.id == id }
        if (existing != null) {
            require(id in device.profileIds) { "User ID already exists" }
            return existing
        }
        val profile = Profile(id, name, System.currentTimeMillis())
        update { s -> s.copy(profiles = s.profiles + profile,
            devices = s.devices.map { if (it.id == deviceId) it.copy(profileIds = it.profileIds + id) else it }) }
        profile
    }

    fun removeProfile(id: String) = synchronized(lock) {
        update { s ->
            s.copy(profiles = s.profiles.filterNot { it.id == id },
                devices = s.devices.map { d -> d.copy(profileIds = d.profileIds - id) },
                profileVaults = s.profileVaults.filterNot { it.profileId == id })
        }
    }

    fun assignedProfileVaults(deviceId: String): List<ProfileVault>? = synchronized(lock) {
        val device = state.devices.firstOrNull { it.id == deviceId } ?: return null
        state.profileVaults.filter { it.profileId in device.profileIds }
    }

    fun assignedProfiles(deviceId: String): List<AssignedProfile>? = synchronized(lock) {
        val device = state.devices.firstOrNull { it.id == deviceId } ?: return null
        state.profiles.filter { it.id in device.profileIds }.map { profile ->
            AssignedProfile(profile, state.profileVaults.firstOrNull { it.profileId == profile.id })
        }
    }

    fun passphraseVerifier(deviceId: String): Pair<Boolean, PassphraseVerifier?> = synchronized(lock) {
        if (state.devices.none { it.id == deviceId }) return false to null
        true to state.passphraseVerifier
    }

    fun savePassphraseVerifier(deviceId: String, verifier: PassphraseVerifier): SavePassphraseVerifierResult =
        synchronized(lock) {
            if (state.devices.none { it.id == deviceId }) return SavePassphraseVerifierResult.UnknownDevice
            state.passphraseVerifier?.let { return SavePassphraseVerifierResult.Existing(it) }
            if (state.profileVaults.isNotEmpty()) return SavePassphraseVerifierResult.ExistingVaults
            update { it.copy(passphraseVerifier = verifier) }
            SavePassphraseVerifierResult.Saved(verifier)
        }

    fun saveProfileVault(deviceId: String, profileId: String, baseRevision: Long,
                         salt: String, nonce: String, ciphertext: String): SaveProfileVaultResult = synchronized(lock) {
        val device = state.devices.firstOrNull { it.id == deviceId }
        if (device == null || profileId !in device.profileIds ||
            state.profiles.none { it.id == profileId }) return SaveProfileVaultResult.NotAssigned
        val current = state.profileVaults.firstOrNull { it.profileId == profileId }
        val revision = current?.revision ?: 0L
        if (baseRevision != revision || revision == Long.MAX_VALUE) return SaveProfileVaultResult.Conflict
        val saved = ProfileVault(profileId, revision + 1, salt, nonce, ciphertext)
        update { s -> s.copy(profileVaults = s.profileVaults.filterNot { it.profileId == profileId } + saved) }
        SaveProfileVaultResult.Saved(saved)
    }

    fun setDeviceProfiles(deviceId: String, profileIds: List<String>): Device? = synchronized(lock) {
        val device = state.devices.firstOrNull { it.id == deviceId } ?: return null
        val known = state.profiles.map { it.id }.toSet()
        require(profileIds.distinct().size == profileIds.size && profileIds.all { it in known }) {
            "Invalid user assignment"
        }
        val updated = device.copy(profileIds = profileIds)
        update { s -> s.copy(devices = s.devices.map { if (it.id == deviceId) updated else it }) }
        updated
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
        val next = transform(state)
        save(next)
        state = next
    }

    private fun save(value: State = state) = writeAtomically(stateFile, json.encodeToString(State.serializer(), value))

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
