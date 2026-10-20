package me.nathanfallet.teamcode.api.models

import kotlinx.serialization.Serializable

@Serializable
data class CapacityView(val memberId: String, val dailyUnits: Long, val maxConcurrent: Int, val poolId: String?, val chargedAndReservedUnits: Long, val activeRequests: Int, val resetsAtMillis: Long, val hardCapUnits: Long = 0, val weight: Int = 1, val poolTotalWeight: Long = 1, val poolDailyUnits: Long = 1_000_000, val queuedRequests: Int = 0, val groups: List<GroupAllowanceView> = emptyList(), val budgetOverride: BudgetOverrideSummaryView? = null, val baseDailyUnits: Long = dailyUnits, val allowSharedOverflow: Boolean = false)
@Serializable
data class PolicyRequest(val dailyUnits: Long, val maxConcurrent: Int, val poolId: String? = null, val weight: Int = 1, val allowSharedOverflow: Boolean = false)
@Serializable
data class QuotaView(val accountId: String, val window: String, val utilization: Double, val observedAtMillis: Long, val resetsAtMillis: Long? = null)

@Serializable
data class PoolBudgetView(val poolId: String, val dailyUnits: Long, val sharedOverflow: Boolean = false)
