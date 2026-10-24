package me.nathanfallet.teamcode.infrastructure.secrets

import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Signs the dashboard's trusted-browser cookie: proof that a member signed in from this browser before. It never
 * authenticates anything; it only lets the browser's own failure backoff apply instead of the username's (audit M3).
 * Stateless, so it needs no table: `v1.<member>.<issued at>.<HMAC-SHA256>`, with a key derived from the server's
 * encryption key and valid for [MAX_AGE_MILLIS].
 */
class TrustedBrowsers(encryptionKey: ByteArray) {
    private val key = Mac.getInstance(ALGORITHM).run { init(SecretKeySpec(encryptionKey, ALGORITHM)); doFinal("teamcode trusted browser v1".toByteArray()) }

    fun mint(memberId: String, now: Long): String = "v1.$memberId.$now." + sign("$memberId.$now")
    /** True when [token] was minted for [memberId] less than [MAX_AGE_MILLIS] ago. */
    fun verify(token: String, memberId: String, now: Long): Boolean {
        if (token.length > 200) return false
        val parts = token.split('.')
        if (parts.size != 4 || parts[0] != "v1" || parts[1] != memberId) return false
        val issued = parts[2].toLongOrNull() ?: return false
        if (issued > now + 60_000 || now - issued > MAX_AGE_MILLIS) return false
        return MessageDigest.isEqual(sign("$memberId.$issued").toByteArray(), parts[3].toByteArray())
    }
    private fun sign(value: String): String = Base64.getUrlEncoder().withoutPadding()
        .encodeToString(Mac.getInstance(ALGORITHM).run { init(SecretKeySpec(key, ALGORITHM)); doFinal(value.toByteArray()) })

    companion object {
        private const val ALGORITHM = "HmacSHA256"
        const val MAX_AGE_MILLIS = 400 * 86_400_000L
    }
}
