package eclipse.orbit

import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertIs
import java.nio.file.Files
import java.util.Comparator

class StoreTest {
    @Test
    fun `passphrase verifier is write once and survives concurrent initializers and restart`() {
        val directory = createTempDirectory("orbit-verifier-test")
        try {
            val store = Store(directory)
            val (device, _) = store.addDevice("TV", "Model")
            val candidates = (1..16).map { PassphraseVerifier("salt-$it", "nonce-$it", "ciphertext-$it") }
            val workers = java.util.concurrent.Executors.newFixedThreadPool(4)
            try {
                val results = candidates.map { candidate ->
                    workers.submit<SavePassphraseVerifierResult> { store.savePassphraseVerifier(device.id, candidate) }
                }.map { it.get(10, java.util.concurrent.TimeUnit.SECONDS) }
                val saved = results.filterIsInstance<SavePassphraseVerifierResult.Saved>().single().verifier
                assertEquals(15, results.filterIsInstance<SavePassphraseVerifierResult.Existing>().size)
                results.filterIsInstance<SavePassphraseVerifierResult.Existing>().forEach { assertEquals(saved, it.verifier) }
                assertEquals(true to saved, Store(directory).passphraseVerifier(device.id))
                assertEquals(SavePassphraseVerifierResult.UnknownDevice,
                    store.savePassphraseVerifier("missing", candidates.first()))
            } finally { workers.shutdownNow() }
        } finally {
            Files.walk(directory).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
            }
        }
    }

    @Test
    fun `existing profile vaults cannot silently initialize a new passphrase verifier`() {
        val directory = createTempDirectory("orbit-verifier-test")
        try {
            val store = Store(directory)
            val profile = store.addProfile("One")
            val (device, _) = store.addDevice("TV", "Model")
            store.setDeviceProfiles(device.id, listOf(profile.id))
            assertIs<SaveProfileVaultResult.Saved>(store.saveProfileVault(device.id, profile.id, 0, "s", "n", "c"))
            assertEquals(SavePassphraseVerifierResult.ExistingVaults,
                store.savePassphraseVerifier(device.id, PassphraseVerifier("s", "n", "c")))
            assertEquals(true to null, Store(directory).passphraseVerifier(device.id))
        } finally {
            Files.walk(directory).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
            }
        }
    }

    @Test
    fun `encrypted profile writes require assignment and reject stale revisions`() {
        val directory = createTempDirectory("orbit-store-test")
        try {
            val store = Store(directory)
            val profile = store.addProfile("One")
            val (first, _) = store.addDevice("TV", "Model")
            val (second, _) = store.addDevice("Other TV", "Model")
            store.setDeviceProfiles(first.id, listOf(profile.id))
            assertEquals(listOf(AssignedProfile(profile, null)), store.assignedProfiles(first.id))

            assertEquals(SaveProfileVaultResult.NotAssigned,
                store.saveProfileVault(second.id, profile.id, 0, "salt", "nonce", "ciphertext"))
            val saved = assertIs<SaveProfileVaultResult.Saved>(
                store.saveProfileVault(first.id, profile.id, 0, "salt", "nonce", "ciphertext"))
            assertEquals(1L, saved.vault.revision)
            assertEquals(SaveProfileVaultResult.Conflict,
                store.saveProfileVault(first.id, profile.id, 0, "salt", "nonce", "other"))
            assertEquals(emptyList(), store.assignedProfileVaults(second.id))
            assertEquals(listOf(saved.vault), Store(directory).assignedProfileVaults(first.id))
            assertEquals(listOf(AssignedProfile(profile, saved.vault)), store.assignedProfiles(first.id))
            assertEquals(emptyList(), store.assignedProfiles(second.id))

            store.setDeviceProfiles(first.id, emptyList())
            assertEquals(SaveProfileVaultResult.NotAssigned,
                store.saveProfileVault(first.id, profile.id, 1, "salt", "nonce", "other"))
            store.setDeviceProfiles(second.id, listOf(profile.id))
            assertEquals(listOf(saved.vault), store.assignedProfileVaults(second.id))
            store.removeProfile(profile.id)
            assertEquals(emptyList(), Store(directory).assignedProfileVaults(second.id))
            assertEquals(emptyList(), store.assignedProfiles(second.id))
        } finally {
            Files.walk(directory).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
            }
        }
    }

    @Test
    fun `assignments persist and deleting a profile unassigns it`() {
        val directory = createTempDirectory("orbit-store-test")
        try {
            val store = Store(directory)
            val first = store.addProfile("One")
            val second = store.addProfile("Two")
            val (device, _) = store.addDevice("TV", "Model")
            store.setDeviceProfiles(device.id, listOf(first.id, second.id))

            val restored = Store(directory)
            assertEquals(listOf(first.id, second.id), restored.devices().single().profileIds)
            restored.removeProfile(first.id)
            assertEquals(listOf(second.id), Store(directory).devices().single().profileIds)
        } finally {
            Files.walk(directory).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
            }
        }
    }

    @Test
    fun `invalid assignments do not change a device`() {
        val directory = createTempDirectory("orbit-store-test")
        try {
            val store = Store(directory)
            val profile = store.addProfile("One")
            val (device, _) = store.addDevice("TV", "Model")
            store.setDeviceProfiles(device.id, listOf(profile.id))

            assertFailsWith<IllegalArgumentException> { store.setDeviceProfiles(device.id, listOf("missing")) }
            assertFailsWith<IllegalArgumentException> { store.setDeviceProfiles(device.id, listOf(profile.id, profile.id)) }
            assertNull(store.setDeviceProfiles("missing", listOf(profile.id)))
            assertEquals(listOf(profile.id), Store(directory).devices().single().profileIds)
        } finally {
            Files.walk(directory).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
            }
        }
    }
}
