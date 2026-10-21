package me.nathanfallet.teamcode.infrastructure.providers

import io.ktor.client.HttpClient
import kotlinx.serialization.json.*
import me.nathanfallet.teamcode.domain.accounts.*
import me.nathanfallet.teamcode.domain.team.TeamFailure

/** JWT claims only select an account header. The authenticated provider response establishes identity. */
class CodexAccountVerifier(http: HttpClient) : AccountVerifier {
    private val transport = CodexTransport(http)
    override suspend fun verify(teamId: String, credentials: ProviderCredentials): VerifiedProviderAccount {
        val claims = CodexTokenClaims.parse(credentials.accessToken)
        val hint = CodexTokenClaims.workspace(claims)
        if (hint.isNullOrBlank() || !Regex("[a-zA-Z0-9_-]{1,128}").matches(hint)) throw invalid()
        val profile = transport.usageDocument(hint, credentials)
        val account = (profile["account_id"] as? JsonPrimitive)?.contentOrNull
        val user = (profile["user_id"] as? JsonPrimitive)?.contentOrNull
        if (account != hint || user.isNullOrBlank() || !Regex("[a-zA-Z0-9_-]{1,128}").matches(user)) throw invalid()
        val plan = (profile["plan_type"] as? JsonPrimitive)?.contentOrNull?.filter { it.isLetterOrDigit() }?.take(32).orEmpty()
        // The authenticated usage document names the address; the token's profile claim is only a fallback for display.
        val email = providerEmail((profile["email"] as? JsonPrimitive)?.contentOrNull) ?: CodexTokenClaims.email(claims)
        return VerifiedProviderAccount(AccountIdentity(teamId, "codex", user, account), "Codex ${plan.ifEmpty { "subscription" }}", email)
    }
    private fun invalid() = TeamFailure("provider_verification_failed", 422, "Codex could not verify this account identity. Reconnect the account.")
}
