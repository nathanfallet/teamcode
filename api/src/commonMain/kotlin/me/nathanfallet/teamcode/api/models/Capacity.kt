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
 * [accountLabel]) and [id], [name] and [email] are set; for anyone else they are null and [label] is a neutral "Account N". [unavailableReason] is
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
