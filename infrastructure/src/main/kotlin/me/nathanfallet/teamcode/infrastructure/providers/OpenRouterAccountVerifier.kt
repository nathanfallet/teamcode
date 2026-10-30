package me.nathanfallet.teamcode.infrastructure.providers

import io.ktor.client.HttpClient
import kotlinx.serialization.json.*
import me.nathanfallet.teamcode.domain.accounts.*
import me.nathanfallet.teamcode.domain.team.TeamFailure
import java.security.MessageDigest

/**
 * An OpenRouter account is an API key. The authenticated `GET /key` call proves the key works and names it; the account
 * identity is a digest of the key itself, so adding the same key again updates the account and a new key is a new one.
 */
class OpenRouterAccountVerifier(http: HttpClient, baseUrl: String = OpenRouterTransport.BASE_URL) : AccountVerifier {
    private val transport = OpenRouterTransport(http, baseUrl)
    override suspend fun verify(teamId: String, credentials: ProviderCredentials): VerifiedProviderAccount {
        val key = transport.keyDocument(credentials)
        // A management key manages keys and credits but cannot run inference: refuse it here rather than at the first request.
        if ((key["is_management_key"] as? JsonPrimitive)?.booleanOrNull == true)
            throw TeamFailure("provider_verification_failed", 422, "This is an OpenRouter management key. Create a regular API key and add that one.")
        val digest = MessageDigest.getInstance("SHA-256").digest(credentials.accessToken.encodeToByteArray()).joinToString("") { "%02x".format(it) }
        val organization = key.text("organization_id")?.takeIf { Regex("[a-zA-Z0-9_-]{1,128}").matches(it) } ?: "openrouter"
        // OpenRouter's label is already masked (`sk-or-v1-abc...xyz`) or a name the owner chose.
        val label = key.text("label")?.filterNot { it.isISOControl() }?.trim()?.take(100)?.takeIf { it.isNotEmpty() }
        return VerifiedProviderAccount(AccountIdentity(teamId, "openrouter", digest.take(32), organization), "OpenRouter" + (label?.let { " ($it)" } ?: ""))
    }
}
