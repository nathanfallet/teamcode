package me.nathanfallet.teamcode.companion

import kotlinx.coroutines.test.runTest
import me.nathanfallet.teamcode.companion.infrastructure.credentials.KeychainCredentialStore
import kotlin.test.*

/**
 * The real Keychain of the simulator, under a test service so the app's entries are never touched. A bare test binary
 * has no application identifier, so the simulator may answer `errSecNotAvailable` (-25291): the test then says so and
 * stops, and the Keychain is exercised by the app itself (sign in, quit, relaunch).
 */
class KeychainCredentialStoreTest {
    private val store = KeychainCredentialStore(service = "me.nathanfallet.teamcode.test")

    @Test fun aTokenIsWrittenReplacedReadAndDeleted() = runTest {
        try {
            store.delete("a1")
        } catch (error: IllegalStateException) {
            if (error.message?.endsWith("(-25291).") == true) { println("No Keychain for this test binary; skipped."); return@runTest }
            throw error
        }
        assertNull(store.read("a1"))
        store.write("a1", "first")
        store.write("a1", "second")
        assertEquals("second", store.read("a1"))
        store.write("a2", "other")
        assertEquals("second", store.read("a1"))
        store.delete("a1")
        store.delete("a1")
        assertNull(store.read("a1"))
        assertEquals("other", store.read("a2"))
        store.delete("a2")
    }
}
