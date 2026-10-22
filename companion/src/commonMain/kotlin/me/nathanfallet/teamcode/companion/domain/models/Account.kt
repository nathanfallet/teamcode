package me.nathanfallet.teamcode.companion.domain.models

import kotlinx.serialization.Serializable

/**
 * A member signed in on one TeamCode server. Only non-secret metadata: the access token is in the platform's
 * credential store, keyed by [id]. The same server with two usernames gives two accounts.
 */
@Serializable
data class Account(
    val id: String,
    val origin: String,
    val username: String,
    val memberId: String,
    val role: String,
    val deviceName: String,
    /** When the token expires (epoch milliseconds); there is no refresh token, the password is asked again. */
    val expiresAt: Long,
    val serverVersion: String,
    /** False once the server refused the token or it expired: the account stays listed and asks for the password. */
    val signedIn: Boolean = true,
) {
    val isAdministrator: Boolean get() = role.uppercase() in setOf("ADMIN", "OWNER")
}
