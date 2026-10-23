package me.nathanfallet.teamcode.companion.domain.models

/** A failure the screens can explain, without any server message or response body. */
sealed class CompanionException(message: String) : Exception(message) {
    /** The origin is not an `https` origin (or `http` on loopback) without path, query or credentials. */
    class InvalidOrigin : CompanionException("Enter a server origin such as https://teamcode.example.org.")
    class Unreachable : CompanionException("The server could not be reached.")
    class NotTeamCode : CompanionException("This address does not answer like a TeamCode server.")
    class UnsupportedApi(val apiVersion: Int) : CompanionException("This server speaks API version $apiVersion; the app needs version 1.")
    class InvalidCredentials : CompanionException("Wrong username or password.")
    class Unauthorized : CompanionException("The server signed this device out. Sign in again.")
    class RateLimited(val retryAfterSeconds: Long?) : CompanionException("Too many attempts. Try again later.")
    class PasswordRejected(val code: String?) : CompanionException("The server refused this password.")
    /** The password was changed but the sign-in that follows failed: the temporary session is gone, sign in again. */
    class PasswordChanged : CompanionException("Your password was changed. Sign in with the new one.")
    /** The session (or artifact) does not exist or is not visible to this member any more. */
    class NotFound : CompanionException("This no longer exists, or you can no longer see it.")
    /** A followed stream was ended by the server: the credential or the access to what it follows was withdrawn. */
    class AccessRevoked : CompanionException("Your access to this was withdrawn.")
    /** The server sent an event this version of the app cannot read; reconnecting would replay it. */
    class UnreadableEvent : CompanionException("The server sent an event this version of the app cannot read. Update the app.")
    /** The server does not offer what the screen needs ([feature] is the missing capability). */
    class Unsupported(val feature: String) : CompanionException("This server does not offer this yet.")
    /** A view ticket for another origin than the account's: the server's public origin is not the address signed in to. */
    class ForeignContent : CompanionException("The server shows artifacts on another address than the one you signed in with. Sign in with its public address.")
    class CredentialStorage : CompanionException("This device's secure storage refused to read or keep the sign-in. Unlock the device and try again.")
    /**
     * The server refused a transfer or a decision on one ([code]: `transfer_pending`, `recipient_ineligible`, ...);
     * [problems] are the recipient's failed checks for `recipient_ineligible`. The screens word each code.
     */
    class TransferRefused(val code: String, val problems: List<String> = emptyList()) :
        CompanionException("The server refused the transfer" + (code.takeIf { it.matches(CODE) }?.let { " ($it)" } ?: "") + ".")
    class Server(val statusCode: Int, val code: String?) :
        CompanionException("The server returned HTTP $statusCode" + (code?.takeIf { it.matches(CODE) }?.let { " ($it)" } ?: "") + ".")
}

/** Error codes are short snake_case identifiers; anything else from the server is not shown. */
private val CODE = Regex("[a-z][a-z0-9_]{0,63}")
