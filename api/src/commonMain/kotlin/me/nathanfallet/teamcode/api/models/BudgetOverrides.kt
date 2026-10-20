package me.nathanfallet.teamcode.api.models

import kotlinx.serialization.Serializable

/** Own capacity exposes only the active allocation and deadline, without administrative reasons. */
@Serializable
data class BudgetOverrideSummaryView(val id: String, val dailyUnits: Long, val expiresAtMillis: Long)
@Serializable
data class BudgetOverrideRequest(val dailyUnits: Long, val durationMinutes: Int, val reason: String, val replacesId: String? = null)
@Serializable
data class RevokeBudgetOverrideRequest(val reason: String)
@Serializable
data class BudgetOverrideView(
    val id: String, val memberId: String, val poolId: String?, val dailyUnits: Long,
    val createdBy: String, val reason: String, val createdAtMillis: Long, val expiresAtMillis: Long,
    val replacesId: String? = null, val revokedAtMillis: Long? = null, val revokedBy: String? = null, val revocationReason: String? = null,
)
@Serializable
data class BudgetOverridePageView(val overrides: List<BudgetOverrideView>, val activeOverrideId: String?, val nextBeforeId: String? = null)
