package me.nathanfallet.teamcode.infrastructure

import kotlin.test.*
import me.nathanfallet.teamcode.infrastructure.secrets.CipherSecretSealer
import me.nathanfallet.teamcode.infrastructure.secrets.CredentialCipher

class CipherSecretSealerTest {
    private val sealer = CipherSecretSealer(CredentialCipher(ByteArray(32) { it.toByte() }))

    @Test fun opensOnlyWithTheContextItWasSealedFor() {
        val envelope = sealer.seal("secret".encodeToByteArray(), "session-a")
        assertFalse("secret" in envelope)
        assertEquals("secret", sealer.open(envelope, "session-a")?.decodeToString())
        assertNull(sealer.open(envelope, "session-b"))
        assertNull(sealer.open(envelope.dropLast(3) + "xyz", "session-a"))
        assertNull(sealer.open("not an envelope", "session-a"))
    }
}
