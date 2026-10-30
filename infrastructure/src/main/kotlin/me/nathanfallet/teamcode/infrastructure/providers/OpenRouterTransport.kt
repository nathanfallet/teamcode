package me.nathanfallet.teamcode.infrastructure.providers

import io.ktor.client.HttpClient
import io.ktor.client.plugins.timeout
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.json.*
import me.nathanfallet.teamcode.domain.accounts.*
import me.nathanfallet.teamcode.domain.capacity.AccountQuota
import me.nathanfallet.teamcode.domain.gateway.WireProtocol
import me.nathanfallet.teamcode.domain.team.TeamFailure

/**
 * Pay-as-you-go models through OpenRouter's Anthropic-compatible Messages endpoint, with an API key instead of a
 * subscription sign-in. The key travels as [ProviderCredentials.accessToken]; there is nothing to refresh. Messages in,
 * Messages out: a Claude Code request is forwarded unchanged apart from the model and the stripped client metadata.
 */
class OpenRouterTransport(private val http: HttpClient, private val baseUrl: String = BASE_URL) : ProviderTransport {
    override val provider = "openrouter"
    override val protocol = WireProtocol.MESSAGES
    /** An API key does not expire, so the stored credentials are already current. */
    override suspend fun refresh(previous: ProviderCredentials): ProviderCredentials = previous
    override fun quotas(accountId: String, headers: Headers, now: Long) = emptyList<AccountQuota>()

    internal suspend fun keyDocument(credentials: ProviderCredentials): JsonObject = http.prepareGet("$baseUrl/key") {
        bearerAuth(credentials.accessToken)
        header(HttpHeaders.Accept, "application/json")
        timeout { requestTimeoutMillis = 15_000 }
    }.execute { response ->
        when (response.status.value) {
            200 -> (response.boundedJson()["data"] as? JsonObject) ?: throw TeamFailure("provider_response_invalid", 502, "OpenRouter returned an invalid key document.")
            401, 403 -> throw TeamFailure("provider_verification_failed", 422, "OpenRouter rejected this API key. Check it and add the account again.")
            else -> throw TeamFailure("usage_unavailable", 503, "OpenRouter key information is unavailable.")
        }
    }
    /** The key's own spending cap, as the share already used. A key without a cap reports no window: nothing to enforce. */
    override suspend fun usage(account: AccountRecord, credentials: ProviderCredentials): List<AccountQuota> =
        parseKeyLimit(account.id, keyDocument(credentials), System.currentTimeMillis())

    override suspend fun send(account: AccountRecord, credentials: ProviderCredentials, body: JsonObject, headers: Map<String, String>, receive: suspend (HttpResponse) -> Unit) {
        // Client metadata (Claude Code puts a device and session identity there) is not forwarded to a third party.
        val outgoing = JsonObject(body - "metadata")
        http.preparePost("$baseUrl/messages") {
            bearerAuth(credentials.accessToken)
            contentType(ContentType.Application.Json)
            header("anthropic-version", headers["anthropic-version"] ?: "2023-06-01")
            headers["anthropic-beta"]?.takeIf { it.isNotBlank() }?.let { header("anthropic-beta", it) }
            header(HttpHeaders.UserAgent, headers["user-agent"] ?: "TeamCode/0.1.0")
            timeout { requestTimeoutMillis = 10 * 60_000; socketTimeoutMillis = 90_000 }
            setBody(outgoing.toString())
        }.execute { receive(it) }
    }
    companion object {
        const val BASE_URL = "https://openrouter.ai/api/v1"
        const val CREDITS_WINDOW = "credits"
        fun parseKeyLimit(accountId: String, key: JsonObject, now: Long): List<AccountQuota> {
            val limit = (key["limit"] as? JsonPrimitive)?.doubleOrNull ?: return emptyList()
            if (!limit.isFinite() || limit <= 0) return emptyList()
            val remaining = (key["limit_remaining"] as? JsonPrimitive)?.doubleOrNull
                ?: (key["usage"] as? JsonPrimitive)?.doubleOrNull?.let { limit - it } ?: return emptyList()
            if (!remaining.isFinite()) return emptyList()
            return listOf(AccountQuota(accountId, CREDITS_WINDOW, ((limit - remaining) / limit).coerceIn(0.0, 1.0), now, null))
        }
    }
}
