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
    class CredentialStorage : CompanionException("This device's secure storage refused to read or keep the sign-in. Unlock the device and try again.")
    class Server(val statusCode: Int, val code: String?) :
        CompanionException("The server returned HTTP $statusCode" + (code?.takeIf { it.matches(CODE) }?.let { " ($it)" } ?: "") + ".")
}

/** Error codes are short snake_case identifiers; anything else from the server is not shown. */
private val CODE = Regex("[a-z][a-z0-9_]{0,63}")
