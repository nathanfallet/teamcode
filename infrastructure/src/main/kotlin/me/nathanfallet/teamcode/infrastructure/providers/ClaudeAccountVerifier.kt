package me.nathanfallet.teamcode.infrastructure.providers

import io.ktor.client.HttpClient
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.client.plugins.timeout
import io.ktor.utils.io.*
import kotlinx.serialization.json.*
import kotlinx.io.readByteArray
import me.nathanfallet.teamcode.domain.accounts.*
import me.nathanfallet.teamcode.domain.team.TeamFailure

/** Caller configures an engine with redirects disabled. No client-supplied upstream URL is accepted. */
class ClaudeAccountVerifier(private val http: HttpClient) : AccountVerifier {
    override suspend fun verify(teamId: String, credentials: ProviderCredentials): VerifiedProviderAccount = http.prepareGet("https://api.anthropic.com/api/oauth/profile") {
        bearerAuth(credentials.accessToken)
        timeout { requestTimeoutMillis = 15_000 }
    }.execute { response ->
        if (response.status.value != 200) throw TeamFailure("provider_verification_failed", 422, "Claude could not verify these credentials. Reconnect the account.")
        val input = response.bodyAsChannel().readRemaining(65_537).readByteArray()
        if (input.size > 65_536) throw TeamFailure("provider_verification_failed", 502, "Claude returned an invalid profile.")
        val profile = try { Json.parseToJsonElement(input.decodeToString()).jsonObject } catch (_: IllegalArgumentException) { throw TeamFailure("provider_verification_failed", 502, "Claude returned an invalid profile.") }
        val account = profile["account"] as? JsonObject
        val organization = profile["organization"] as? JsonObject
        val accountId = (account?.get("uuid") as? JsonPrimitive)?.contentOrNull
        val organizationId = (organization?.get("uuid") as? JsonPrimitive)?.contentOrNull
        if (accountId.isNullOrBlank() || organizationId.isNullOrBlank() || accountId.length > 128 || organizationId.length > 128) throw TeamFailure("provider_verification_failed", 422, "Claude did not return an account and organization identity.")
        if ((account["has_claude_max"] as? JsonPrimitive)?.booleanOrNull != true) throw TeamFailure("max_subscription_required", 422, "This account does not report a Claude Max subscription.")
        val label = (account["display_name"] as? JsonPrimitive)?.contentOrNull ?: "Claude Max"
        val email = providerEmail((account["email"] as? JsonPrimitive)?.contentOrNull)
        VerifiedProviderAccount(AccountIdentity(teamId, "anthropic", accountId, organizationId), label.filter { !it.isISOControl() }.take(120), email)
    }
}

/** A provider-reported address kept for display only; anything that does not look like one is dropped, never an error. */
internal fun providerEmail(value: String?): String? = value?.trim()?.takeIf {
    it.length in 3..MAX_ACCOUNT_EMAIL && it.count { c -> c == '@' } == 1 && !it.startsWith('@') && !it.endsWith('@') && it.none { c -> c.isWhitespace() || c.isISOControl() }
}
