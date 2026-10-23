package me.nathanfallet.teamcode.client

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import me.nathanfallet.teamcode.api.models.EnrollAccountRequest
import okio.ByteString.Companion.encodeUtf8
import okio.ByteString.Companion.toByteString

/**
 * One provider sign-in, from the link to open to the credentials to enroll. The PKCE verifier, the state and the
 * enrollment operation id are drawn once by [start]; only [authorizationUrl] (which carries the state and the PKCE
 * challenge, never the verifier) is meant to be shown. Nothing here is logged, and [toString] hides every value.
 */
class OAuthSignIn private constructor(
    val provider: OAuthEnrollmentProvider,
    private val state: String,
    private val verifier: String,
    private val operationId: String,
) {
    /** The provider's page; the browser shows a code (Claude) or lands on a callback URL (Codex) to paste back. */
    val authorizationUrl: String = provider.authorizationUrl(state, pkceChallenge(verifier))

    /** The authorization code in [paste], checked against this attempt's state; throws [OAuthEnrollmentException]. */
    fun code(paste: String): String = provider.parseCode(paste, state)

    /** Exchanges the pasted code at the provider for the credentials to send with [TeamCodeClient.enrollAccount]. */
    suspend fun exchange(paste: String, createHttpClient: (HttpClientConfig<*>.() -> Unit) -> HttpClient): EnrollAccountRequest {
        val code = code(paste)
        return OAuthEnrollmentClient(provider, createHttpClient).use { it.exchange(code, state, verifier, operationId) }
    }

    override fun toString() = "OAuthSignIn(${provider.provider}, [REDACTED])"

    companion object {
        /** [randomBytes] must be a cryptographically secure source (the platform's). */
        fun start(provider: OAuthEnrollmentProvider, randomBytes: (Int) -> ByteArray): OAuthSignIn =
            OAuthSignIn(provider, state = randomOAuthCode(randomBytes), verifier = randomOAuthCode(randomBytes), operationId = randomOAuthCode(randomBytes))
    }
}

/** 32 secure random bytes as unpadded base64url (43 characters): a PKCE verifier, an OAuth state or an operation id. */
fun randomOAuthCode(randomBytes: (Int) -> ByteArray): String {
    val bytes = randomBytes(32)
    require(bytes.size == 32) { "Expected 32 random bytes." }
    return bytes.toByteString().base64Url().trimEnd('=')
}

/** The S256 PKCE challenge of [verifier] (RFC 7636): unpadded base64url of its SHA-256. */
fun pkceChallenge(verifier: String): String = verifier.encodeUtf8().sha256().base64Url().trimEnd('=')
