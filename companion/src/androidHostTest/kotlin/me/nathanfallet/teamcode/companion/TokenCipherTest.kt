package me.nathanfallet.teamcode.companion

import javax.crypto.KeyGenerator
import me.nathanfallet.teamcode.companion.infrastructure.credentials.TokenCipher
import kotlin.test.*

/** The encryption of the Android store, with a software AES key (the Keystore key needs a device). */
class TokenCipherTest {
    private fun key() = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    @Test fun aSealedTokenOpensOnlyWithItsKeyAndAccount() {
        val key = key()
        val sealed = TokenCipher.seal(key, "a1", "tc_token")
        assertTrue(sealed.startsWith("v1:"))
        assertFalse(sealed.contains("tc_token"))
        assertEquals("tc_token", TokenCipher.open(key, "a1", sealed))
        assertNull(TokenCipher.open(key, "a2", sealed)) // Copied to another account's entry.
        assertNull(TokenCipher.open(key(), "a1", sealed)) // The key was lost (restore on another device).
    }

    @Test fun eachWriteUsesAFreshIvAndDamagedEntriesAreRefused() {
        val key = key()
        assertNotEquals(TokenCipher.seal(key, "a1", "t"), TokenCipher.seal(key, "a1", "t"))
        val sealed = TokenCipher.seal(key, "a1", "t")
        for (damaged in listOf("t", "v1:", "v1:not base64!", "v1:AAAA", sealed.dropLast(4) + "AAAA"))
            assertNull(TokenCipher.open(key, "a1", damaged), damaged)
    }
}
