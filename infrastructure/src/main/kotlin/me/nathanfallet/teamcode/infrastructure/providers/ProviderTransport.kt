package me.nathanfallet.teamcode.infrastructure.providers

import io.ktor.client.statement.HttpResponse
import io.ktor.http.Headers
import kotlinx.serialization.json.JsonObject
import me.nathanfallet.teamcode.domain.accounts.*
import me.nathanfallet.teamcode.domain.capacity.AccountQuota
import me.nathanfallet.teamcode.domain.capacity.QuotaUpdate
import me.nathanfallet.teamcode.domain.capacity.ProviderRequestIds
import me.nathanfallet.teamcode.domain.gateway.WireProtocol

/** Fixed provider destinations; no end-user URL can receive a subscription credential. */
interface ProviderTransport {
    val provider: String
    val protocol: WireProtocol
    suspend fun refresh(previous: ProviderCredentials): ProviderCredentials
    suspend fun usage(account: AccountRecord, credentials: ProviderCredentials): List<AccountQuota>
    suspend fun usageUpdate(account: AccountRecord, credentials: ProviderCredentials): QuotaUpdate =
        QuotaUpdate(usage(account, credentials), System.currentTimeMillis())
    suspend fun send(account: AccountRecord, credentials: ProviderCredentials, body: JsonObject, headers: Map<String, String>, receive: suspend (HttpResponse) -> Unit)
    fun quotas(accountId: String, headers: Headers, now: Long): List<AccountQuota>
    fun quotaUpdate(accountId: String, model: String, headers: Headers, now: Long): QuotaUpdate =
        QuotaUpdate(quotas(accountId, headers, now), now)
    /** Override for a provider with a different correlation contract; conflicting IDs remain unknown. */
    fun requestId(headers: Headers): String? = listOf("request-id", "x-request-id")
        .mapNotNull { headers.getAll(it)?.singleOrNull()?.takeIf(ProviderRequestIds::valid) }.distinct().singleOrNull()
}
