package me.nathanfallet.teamcode.infrastructure.providers

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import me.nathanfallet.teamcode.domain.accounts.AccountIdentity
import me.nathanfallet.teamcode.domain.accounts.ProviderCredentials

/** What a token response says about whose credentials it carries. Display data only; never logged. */
class ReportedAccount(val accountId: String?, val organizationId: String?, val email: String?) {
    /** True only when the response names exactly the stored identity. */
    fun names(identity: AccountIdentity) = accountId == identity.accountId && organizationId == identity.organizationId
    /** Without both identifiers the response cannot vouch for an address. */
    val conclusive get() = !accountId.isNullOrBlank() && !organizationId.isNullOrBlank()
    override fun toString(): String = "ReportedAccount([REDACTED])"
}

class RefreshedCredentials(val credentials: ProviderCredentials, val reported: ReportedAccount? = null)

internal fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
