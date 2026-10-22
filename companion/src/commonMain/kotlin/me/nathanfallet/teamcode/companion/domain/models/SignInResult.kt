package me.nathanfallet.teamcode.companion.domain.models

sealed interface SignInResult {
    data class SignedIn(val account: Account) : SignInResult

    /**
     * The server requires a new password before anything else, as on the CLI. [temporaryToken] only allows the
     * password change; it is never stored.
     */
    class PasswordChangeRequired(val server: ServerInfo, val username: String, val deviceName: String, internal val temporaryToken: String) : SignInResult {
        override fun toString() = "PasswordChangeRequired(${server.origin}, $username)"
    }
}
