package me.nathanfallet.teamcode.companion

import kotlinx.coroutines.test.runTest
import me.nathanfallet.teamcode.companion.infrastructure.credentials.KeyringBackend
import me.nathanfallet.teamcode.companion.infrastructure.credentials.KeyringCredentialStore
import kotlin.test.*

class KeyringCredentialStoreTest {
    private class FakeKeyring(var broken: Boolean = false) : KeyringBackend {
        val entries = mutableMapOf<Pair<String, String>, String>()
        override fun get(service: String, account: String) = check().let { entries[service to account] }
        override fun set(service: String, account: String, secret: String) { check(); entries[service to account] = secret }
        override fun delete(service: String, account: String) { check(); entries.remove(service to account) }
        private fun check() { if (broken) throw IllegalStateException("locked") }
    }

    @Test fun tokensGoToTheKeyringUnderTheTeamCodeService() = runTest {
        val keyring = FakeKeyring()
        val store = KeyringCredentialStore(keyring)
        store.write("a1", "token")
        assertEquals(mapOf(("me.nathanfallet.teamcode" to "a1") to "token"), keyring.entries)
        assertEquals("token", KeyringCredentialStore(keyring).read("a1"))
        store.delete("a1")
        assertNull(store.read("a1"))
        assertTrue(store.persistent)
    }

    @Test fun withoutAKeyringTokensLiveOnlyInMemoryAndTheStoreSaysSo() = runTest {
        val store = KeyringCredentialStore(null)
        assertFalse(store.persistent)
        store.write("a1", "token")
        assertEquals("token", store.read("a1"))
        assertNull(KeyringCredentialStore(null).read("a1"))
    }

    @Test fun aBrokenKeyringFallsBackToMemory() = runTest {
        val keyring = FakeKeyring(broken = true)
        val store = KeyringCredentialStore(keyring)
        store.write("a1", "token")
        assertFalse(store.persistent)
        assertEquals("token", store.read("a1"))
        assertTrue(keyring.entries.isEmpty())
        store.delete("a1")
        keyring.broken = false
        assertNull(store.read("a1"))
    }

    @Test fun aMomentaryFailureNeitherLosesTokensNorKeepsRevokedOnes() = runTest {
        val keyring = FakeKeyring()
        val store = KeyringCredentialStore(keyring)
        store.write("a1", "token")
        keyring.broken = true
        // A locked keyring is an error, not a missing token: the account must not be signed out for it.
        assertFailsWith<IllegalStateException> { store.read("a1") }
        keyring.broken = false
        // The keyring is asked again once it answers.
        assertEquals("token", store.read("a1"))
        keyring.broken = true
        store.write("a1", "newer")
        keyring.broken = false
        assertEquals("newer", store.read("a1"))
        // Sign-out still reaches the keyring after a failure.
        store.delete("a1")
        assertTrue(keyring.entries.isEmpty())
        assertNull(store.read("a1"))
    }
}
