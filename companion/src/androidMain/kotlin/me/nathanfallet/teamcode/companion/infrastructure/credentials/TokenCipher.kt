package me.nathanfallet.teamcode.companion.infrastructure.credentials

import java.util.Base64
import java.security.GeneralSecurityException
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * AES-256-GCM with a random 96-bit IV per write, stored as `v1:<base64(iv | ciphertext | tag)>`. The account id is the
 * associated data, so a ciphertext copied to another account's entry does not decrypt.
 */
internal object TokenCipher {
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128
    private const val PREFIX = "v1:"

    fun seal(key: SecretKey, accountId: String, token: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key) }
        cipher.updateAAD(accountId.encodeToByteArray())
        val iv = cipher.iv
        check(iv.size == IV_BYTES)
        return PREFIX + Base64.getEncoder().encodeToString(iv + cipher.doFinal(token.encodeToByteArray()))
    }

    /** Null when [stored] is not a ciphertext of this key for this account. */
    fun open(key: SecretKey, accountId: String, stored: String): String? {
        if (!stored.startsWith(PREFIX)) return null
        val bytes = try { Base64.getDecoder().decode(stored.removePrefix(PREFIX)) } catch (_: IllegalArgumentException) { return null }
        if (bytes.size <= IV_BYTES + TAG_BITS / 8) return null
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, bytes, 0, IV_BYTES))
            cipher.updateAAD(accountId.encodeToByteArray())
            cipher.doFinal(bytes, IV_BYTES, bytes.size - IV_BYTES).decodeToString()
        } catch (_: GeneralSecurityException) {
            null
        }
    }
}
