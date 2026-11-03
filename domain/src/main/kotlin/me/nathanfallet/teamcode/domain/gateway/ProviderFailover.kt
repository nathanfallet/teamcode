package me.nathanfallet.teamcode.domain.gateway

import me.nathanfallet.teamcode.domain.capacity.RetryWindows
import me.nathanfallet.teamcode.domain.capacity.providerLabel
import me.nathanfallet.teamcode.domain.team.TeamFailure

/**
 * What a provider's definite refusal, answered before any output reached the client, says about trying another account
 * for the same request. Anything else (5xx, another 4xx, a transport failure, a failure after output) is never tried
 * elsewhere: the provider may have done the work, and a committed stream is never replayed.
 */
enum class ProviderRefusal {
    /**
     * The account itself cannot serve: credentials or entitlement refused (401 after one refresh, 403), or a 429 whose
     * quota headers (or the stored observations) show a spent subscription window. The account is recorded as paused or
     * at its limit in shared SQL, so no other request of any replica selects it again: trying the next account is a
     * one-time discovery, not a storm.
     */
    ACCOUNT,

    /**
     * A 429 that does not show a spent subscription: a rate limit that may apply to every account at once. The account
     * is paused as before, but a request switches account only [ProviderFailoverBudget.RATE_LIMIT_SWITCHES] time(s) on
     * it: walking the whole pool on a provider-wide limit pauses every account and refuses the whole team.
     */
    RATE_LIMIT;

    companion object {
        /** `null` when [status] is not a refusal another account can take instead. */
        fun of(status: Int, subscriptionSpent: Boolean): ProviderRefusal? = when (status) {
            401, 403 -> ACCOUNT
            429 -> if (subscriptionSpent) ACCOUNT else RATE_LIMIT
            else -> null
        }
    }
}

/**
 * One request's allowance of account switches after provider refusals, per provider. Account refusals keep the
 * existing bounds (each account once per target model, 64 accounts in all); rate limits allow
 * [RATE_LIMIT_SWITCHES] switch(es) per provider, after which the request stops trying that provider and the
 * caller gets [failure]. Mirrors TeamClaude's single hop on a plain rate limit (`ctx.rateLimitHopped`).
 * Not thread-safe: a request's attempts run one after the other.
 */
class ProviderFailoverBudget(private val rateLimitSwitches: Int = RATE_LIMIT_SWITCHES) {
    private val rateLimits = mutableMapOf<String, MutableList<Long>>()

    /** Records a refusal of [provider]; true while another account of [provider] may still be tried. */
    fun record(provider: String, refusal: ProviderRefusal, retryAfterSeconds: Long): Boolean {
        if (refusal == ProviderRefusal.RATE_LIMIT) rateLimits.getOrPut(provider) { mutableListOf() } += retryAfterSeconds.coerceAtLeast(1)
        return allows(provider)
    }

    fun allows(provider: String): Boolean = (rateLimits[provider]?.size ?: 0) <= rateLimitSwitches

    /**
     * The refusal once [provider]'s switches are used up, `null` before. Retry-After is the shortest wait the provider
     * announced: the accounts it named are paused until then, and accounts not tried are usable before. Counts only,
     * never an account name.
     */
    fun failure(provider: String): TeamFailure? {
        val waits = rateLimits[provider]?.takeUnless { allows(provider) } ?: return null
        val retryAfter = waits.min()
        val label = providerLabel(provider)
        return TeamFailure(CODE, 429,
            "The provider rate-limited ${waits.size} $label accounts of your TeamCode pool one after the other for this request. " +
                "They are paused; TeamCode does not try the rest of the pool on a rate limit that may apply to every account. " +
                "Retry in ${RetryWindows.duration(retryAfter)}.",
            retryAfterSeconds = retryAfter, retryable = retryAfter <= RetryWindows.MAX_AUTOMATIC_RETRY_SECONDS)
    }

    companion object {
        const val RATE_LIMIT_SWITCHES = 1
        const val CODE = "provider_rate_limited"
    }
}
