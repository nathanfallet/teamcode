package me.nathanfallet.teamcode.api.models

import kotlinx.serialization.Serializable

/** [chargedAndReservedUnits] includes [reservedUnits], the upper estimates of requests in flight that settle to their real cost. */
@Serializable
data class CapacityView(val memberId: String, val dailyUnits: Long, val maxConcurrent: Int, val poolId: String?, val chargedAndReservedUnits: Long, val activeRequests: Int, val resetsAtMillis: Long, val hardCapUnits: Long = 0, val weight: Int = 1, val poolTotalWeight: Long = 1, val poolDailyUnits: Long = 1_000_000, val queuedRequests: Int = 0, val groups: List<GroupAllowanceView> = emptyList(), val budgetOverride: BudgetOverrideSummaryView? = null, val baseDailyUnits: Long = dailyUnits, val allowSharedOverflow: Boolean = false, val reservedUnits: Long = 0)
@Serializable
data class PolicyRequest(val dailyUnits: Long, val maxConcurrent: Int, val poolId: String? = null, val weight: Int = 1, val allowSharedOverflow: Boolean = false)
@Serializable
data class QuotaView(val accountId: String, val window: String, val utilization: Double, val observedAtMillis: Long, val resetsAtMillis: Long? = null)

@Serializable
data class PoolBudgetView(val poolId: String, val dailyUnits: Long, val sharedOverflow: Boolean = false)

/**
 * A subscription account as the caller may see it. For an interactive administrator [label] is "Name (email)" (see
 * [accountLabel]) and [id], [name], [email] and [poolId] are set; for anyone else they are null and [label] is a neutral
 * "Account N" numbered in this listing's priority order, which names a row rather than a lasting account identity. [unavailableReason] is
 * `disabled`, `pending_handover`, `reconnect_required`, `cooling_down` or `quota_exhausted` when [usable] is false.
 */
@Serializable
data class PoolAccountStatusView(
    val label: String, val provider: String, val state: String, val disabled: Boolean, val usable: Boolean,
    val unavailableReason: String? = null, val cooldownUntilMillis: Long? = null, val windows: List<AccountWindowView> = emptyList(),
    val poolId: String? = null, val id: String? = null, val name: String? = null, val email: String? = null,
)
/** One current provider window: `five_hour`, `seven_day`, or a model-scoped `model:<family>` that only bars that model. */
@Serializable
data class AccountWindowView(val window: String, val utilization: Double, val observedAtMillis: Long, val resetsAtMillis: Long? = null)

/**
 * Raw provider tokens of one member, run, provider and model on one UTC day (`YYYY-MM-DD`). `inputTokens` excludes
 * cached input; `cacheCreationTokens` and `cacheReadTokens` are the cache write and read input; `outputTokens`
 * includes reasoning. `unmeteredRequests` ended without provider usage and are not in the token sums.
 */
@Serializable
data class TokenUsageRowView(
    val day: String, val memberId: String, val username: String, val runId: String? = null, val provider: String, val model: String,
    val requests: Long, val unmeteredRequests: Long, val inputTokens: Long, val cacheCreationTokens: Long, val cacheReadTokens: Long, val outputTokens: Long,
    /** The four sums priced with the team's API price table for that day, in micro-dollars; null when no price covers the model. */
    val apiEquivalentMicros: Long? = null,
)

@Serializable
data class TokenUsagePageView(val rows: List<TokenUsageRowView>, val nextAfter: String? = null)

/**
 * One row of the team's API price table (ADR 0002). Prices are **micro-dollars per million tokens**: `3000000` is $3 per
 * million tokens. [model] is an exact lowercase model identifier or a family prefix; [effectiveFrom] is a UTC date
 * (`YYYY-MM-DD`) from which the row applies, until a later row for the same model.
 */
@Serializable
data class ApiPriceView(
    val provider: String, val model: String, val effectiveFrom: String,
    val inputMicros: Long, val cacheWriteMicros: Long, val cacheReadMicros: Long, val outputMicros: Long,
)

/** The whole table: `PUT /api/v1/policy/api-prices` replaces it. */
@Serializable
data class ApiPriceTableView(val prices: List<ApiPriceView>)

/** A window's learned full capacity in API-equivalent micro-dollars; null below three samples. */
@Serializable
data class WindowCapacityView(val window: String, val fullWindowApiMicros: Long? = null, val samples: Int)

/**
 * One current window of an account, split between TeamCode and outside use since it opened ([sinceMillis]).
 * [observedUtilization] is the provider's reading (0.0 to 1.0) and [teamCodeApiMicros] the API-equivalent TeamCode
 * settled on the account since then. With a learned capacity: [teamCodeUtilization] is TeamCode's part of the reading,
 * [outsideUtilization] the rest (use of the subscription outside TeamCode, never negative) worth [outsideApiMicros], and
 * [remainingApiMicros] what is left of the window. Null means unknown (no capacity yet, or an unpriced model).
 */
@Serializable
data class WindowUsageView(
    val window: String, val sinceMillis: Long, val observedAtMillis: Long, val resetsAtMillis: Long, val observedUtilization: Double,
    val teamCodeApiMicros: Long? = null, val teamCodeUtilization: Double? = null, val outsideUtilization: Double? = null,
    val outsideApiMicros: Long? = null, val remainingApiMicros: Long? = null,
)

/**
 * One home pool account in the estimate. [status]: `counted`, `unavailable` (disabled, pending, reconnect required or
 * cooling down), `no_fresh_observation` or `no_estimate`. [binding] is the window that gives the smaller daily figure.
 * [dailyApiMicros] is what is left for today; [capacityDailyApiMicros] the account's full daily capacity whatever it has
 * used (min of five-hour capacity × 24 / 5 and weekly capacity / 7), null when unknown.
 */
@Serializable
data class PoolSizeAccountView(
    val accountId: String, val status: String, val dailyApiMicros: Long, val binding: String? = null,
    val windows: List<WindowCapacityView> = emptyList(),
    val capacityDailyApiMicros: Long? = null, val usage: List<WindowUsageView> = emptyList(),
)

/**
 * Seven days of one model's tokens and their API-equivalent by token type (micro-dollars, cache writes and reads
 * apart); the costs are null when a day of that model has no price.
 */
@Serializable
data class ModelUsageView(
    val provider: String, val model: String, val inputTokens: Long, val cacheCreationTokens: Long, val cacheReadTokens: Long, val outputTokens: Long,
    val inputApiMicros: Long? = null, val cacheWriteApiMicros: Long? = null, val cacheReadApiMicros: Long? = null, val outputApiMicros: Long? = null,
    val apiEquivalentMicros: Long? = null,
)

/**
 * The automatic home pool size. [effectiveDailyUnits] is what admission uses now ([source] `automatic` or `manual`).
 * [estimatedDailyUnits] is the current estimate before smoothing, null with [reason] (`no_accounts`, `no_estimate`,
 * `no_priced_usage`); [automaticDailyUnits] the stored, smoothed value written every ten minutes. [estimatedDailyApiMicros]
 * is what the accounts have **left** for today.
 *
 * Capacity, independent of current use: [capacityDailyApiMicros] sums the full daily capacity of the accounts that can
 * serve and whose capacity is known; [capacityUnknownAccounts] counts those left out, so with a non-zero count it is a
 * floor. [capacityDailyTokens] converts it at the team's seven-day mix ([tokensPerApiMicro], cache reads included).
 * [outsideWeeklyApiMicros] is what the accounts' current weekly windows used outside TeamCode. [models] is the
 * seven-day mix by model, priced by token type.
 */
@Serializable
data class PoolSizeView(
    val enabled: Boolean, val manualDailyUnits: Long, val automaticDailyUnits: Long? = null, val automaticComputedAtMillis: Long? = null,
    val effectiveDailyUnits: Long, val source: String, val estimatedDailyApiMicros: Long, val estimatedDailyUnits: Long? = null,
    val reason: String? = null, val creditsPerApiMicro: Double? = null, val unpricedModels: List<String> = emptyList(),
    val accounts: List<PoolSizeAccountView> = emptyList(),
    val capacityDailyApiMicros: Long = 0, val capacityUnknownAccounts: Int = 0, val capacityDailyTokens: Long? = null,
    val tokensPerApiMicro: Double? = null, val outsideWeeklyApiMicros: Long? = null, val models: List<ModelUsageView> = emptyList(),
)
