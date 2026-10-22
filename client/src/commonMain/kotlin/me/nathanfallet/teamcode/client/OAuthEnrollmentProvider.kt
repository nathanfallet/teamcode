package me.nathanfallet.teamcode.client

import io.ktor.http.*
import kotlinx.serialization.json.*

/** Public-client OAuth details. No team credential is sent to these endpoints. */
interface OAuthEnrollmentProvider {
    val provider: String
    val displayName: String
    val clientId: String
    val redirectUri: String
    val tokenEndpoint: String
    val pasteInstructions: String
    fun authorizationUrl(state: String, challenge: String): String
    fun parseCode(paste: String, state: String): String
    fun exchangeBody(code: String, state: String, verifier: String): JsonObject
}

/**
 * A provider sign-in that cannot complete. The message is written by TeamCode, never copied from the paste or the
 * provider's answer; [status] is the token endpoint's HTTP status for [Reason.EXCHANGE_REJECTED].
 */
class OAuthEnrollmentException(val reason: Reason, val provider: String, message: String, val status: Int? = null) : IllegalArgumentException(message) {
    enum class Reason {
        /** The paste is not the provider's complete code or callback URL. */
        INVALID_PASTE,
        /** The paste belongs to another sign-in attempt. */
        STATE_MISMATCH,
        /** The provider refused sign-in in the browser. */
        PROVIDER_REFUSED,
        /** The token endpoint rejected the code: expired, already used or not from this attempt. */
        EXCHANGE_REJECTED,
        /** The token endpoint could not be reached. */
        EXCHANGE_UNREACHABLE,
        /** The token endpoint answered something that is not a usable token response. */
        INVALID_TOKEN_RESPONSE,
    }
}

private fun OAuthEnrollmentProvider.refuse(reason: OAuthEnrollmentException.Reason, message: String): Nothing =
    throw OAuthEnrollmentException(reason, displayName, message)

object ClaudeOAuthEnrollment : OAuthEnrollmentProvider {
    override val provider = "anthropic"
    override val displayName = "Claude"
    override val clientId = "9d1c250a-e61b-44d9-88ed-5944d1962f5e"
    override val redirectUri = "https://console.anthropic.com/oauth/code/callback"
    override val tokenEndpoint = "https://platform.claude.com/v1/oauth/token"
    override val pasteInstructions = "Paste the complete code#state shown by Claude."
    override fun authorizationUrl(state: String, challenge: String) = authorization(
        "https://claude.ai/oauth/authorize", state, challenge,
        "org:create_api_key user:profile user:inference user:sessions:claude_code user:mcp_servers user:file_upload",
        mapOf("code" to "true"),
    )
    override fun parseCode(paste: String, state: String): String {
        if (paste.length !in 1..8192) refuse(OAuthEnrollmentException.Reason.INVALID_PASTE, "Invalid authorization code.")
        val parts = paste.trim().split('#', limit = 2)
        if (parts.size != 2 || parts[0].isBlank() || parts[1].isBlank()) refuse(OAuthEnrollmentException.Reason.INVALID_PASTE, "Paste the complete code#state shown by Claude.")
        if (parts[1] != state) refuse(OAuthEnrollmentException.Reason.STATE_MISMATCH, "OAuth state mismatch. Start a new sign-in attempt.")
        return parts[0]
    }
    override fun exchangeBody(code: String, state: String, verifier: String) = JsonObject(exchangeParameters(code, verifier) + ("state" to JsonPrimitive(state)))
}

object CodexOAuthEnrollment : OAuthEnrollmentProvider {
    override val provider = "codex"
    override val displayName = "Codex"
    override val clientId = "app_EMoamEEZ73f0CkXaXp7hrann"
    override val redirectUri = "http://localhost:1455/auth/callback"
    override val tokenEndpoint = "https://auth.openai.com/oauth/token"
    override val pasteInstructions = "Paste the complete localhost:1455/auth/callback URL from the browser address bar, even if the page could not load."
    override fun authorizationUrl(state: String, challenge: String) = authorization(
        "https://auth.openai.com/oauth/authorize", state, challenge, "openid profile email offline_access",
        mapOf("id_token_add_organizations" to "true", "codex_cli_simplified_flow" to "true", "originator" to "codex_cli_rs"),
    )
    override fun parseCode(paste: String, state: String): String {
        val invalid = OAuthEnrollmentException.Reason.INVALID_PASTE
        if (paste.length !in 1..8192) refuse(invalid, "Invalid callback URL.")
        val text = paste.trim()
        val url = try { Url(if (text.startsWith("localhost:")) "http://$text" else text) }
            catch (_: Exception) { refuse(invalid, "Paste the complete Codex callback URL.") }
        if (url.protocol != URLProtocol.HTTP || url.host != "localhost" || url.port != 1455 || url.encodedPath != "/auth/callback" ||
            !url.user.isNullOrEmpty() || !url.password.isNullOrEmpty() || url.fragment.isNotEmpty()) refuse(invalid, "Paste the complete Codex callback URL from this sign-in attempt.")
        if (url.parameters.getAll("state") != listOf(state)) refuse(OAuthEnrollmentException.Reason.STATE_MISMATCH, "OAuth state mismatch. Start a new sign-in attempt.")
        if (url.parameters["error"] != null) refuse(OAuthEnrollmentException.Reason.PROVIDER_REFUSED, "Codex refused sign-in. Start a new sign-in attempt.")
        val codes = url.parameters.getAll("code")
        if (codes?.size != 1 || codes.single().isBlank()) refuse(invalid, "The Codex callback has no authorization code.")
        return codes.single()
    }
    override fun exchangeBody(code: String, state: String, verifier: String) = exchangeParameters(code, verifier)
}

private fun OAuthEnrollmentProvider.authorization(endpoint: String, state: String, challenge: String, scope: String, extra: Map<String, String>): String = URLBuilder(endpoint).apply {
    parameters.apply {
        append("client_id", clientId); append("redirect_uri", redirectUri); append("response_type", "code")
        append("scope", scope); append("state", state); append("code_challenge", challenge); append("code_challenge_method", "S256")
        extra.forEach { (key, value) -> append(key, value) }
    }
}.buildString()

private fun OAuthEnrollmentProvider.exchangeParameters(code: String, verifier: String) = buildJsonObject {
    put("grant_type", "authorization_code"); put("client_id", clientId); put("redirect_uri", redirectUri)
    put("code", code); put("code_verifier", verifier)
}
