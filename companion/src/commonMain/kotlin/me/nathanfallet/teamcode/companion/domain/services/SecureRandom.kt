package me.nathanfallet.teamcode.companion.domain.services

/** The platform's cryptographically secure random source, for the PKCE verifier and state of a provider sign-in. */
fun interface SecureRandom {
    fun nextBytes(count: Int): ByteArray
}
