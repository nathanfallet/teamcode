package me.nathanfallet.teamcode.infrastructure.secrets

import me.nathanfallet.teamcode.domain.cloud.SecretSealer

/** The server's credential key as a [SecretSealer]: AES-256-GCM, bound to the context given at both ends. */
class CipherSecretSealer(private val cipher: CredentialCipher) : SecretSealer {
    override fun seal(plaintext: ByteArray, context: String) = cipher.encrypt(plaintext, context)
    override fun open(envelope: String, context: String) = try { cipher.decrypt(envelope, context) } catch (_: CredentialDecryptionException) { null }
}
