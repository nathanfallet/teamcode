package me.nathanfallet.teamcode.companion.infrastructure.system

import me.nathanfallet.teamcode.companion.domain.services.SecureRandom

/** `java.security.SecureRandom`, the platform's default strong source. */
object JavaSecureRandom : SecureRandom {
    private val source = java.security.SecureRandom()
    override fun nextBytes(count: Int): ByteArray = ByteArray(count).also(source::nextBytes)
}
