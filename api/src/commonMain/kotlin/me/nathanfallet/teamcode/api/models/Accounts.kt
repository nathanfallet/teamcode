package me.nathanfallet.teamcode.api.models

import kotlinx.serialization.Serializable

@Serializable
/**
 * [freshSignIn] marks credentials from an OAuth sign-in the CLI just ran (`accounts add`): the account is active at once.
 * Imports leave it false, and a new imported account waits for an explicit refresh-ownership handover.
 */
class EnrollAccountRequest(val accessToken: String, val refreshToken: String, val expiresAtMillis: Long, val operationId: String, val provider: String = "anthropic", val freshSignIn: Boolean = false) {
    override fun toString() = "EnrollAccountRequest([REDACTED])"
}
@Serializable
data class AccountView(val id: String, val provider: String, val displayName: String, val credentialVersion: Long, val disabled: Boolean, val priority: Int, val state: String, val poolId: String? = null, val email: String? = null) {
    /** How every surface names the account: "Name (email)", or the name alone while the provider email is unknown. */
    val label: String get() = accountLabel(displayName, email)
}
@Serializable
data class EnrollAccountResponse(val created: Boolean, val account: AccountView)
@Serializable
data class UpdateAccountRequest(val displayName: String, val disabled: Boolean, val priority: Int, val poolId: String? = null)

/**
 * "Name (email)" for a pool account. The name alone when the email is unknown (accounts enrolled before the email was
 * recorded, until they are added or imported again) or when the name already is that email, as TeamClaude names accounts.
 */
fun accountLabel(displayName: String, email: String?): String =
    if (email.isNullOrBlank() || email.equals(displayName, ignoreCase = true)) displayName else "$displayName ($email)"
