package me.nathanfallet.teamcode.domain.capacity

import me.nathanfallet.teamcode.domain.team.Principal
import me.nathanfallet.teamcode.domain.team.TeamFailure

/** An explicit daily allocation, never additional unmetered usage or access to another pool. */
data class BudgetOverrideSummary(val id: String, val dailyUnits: Long, val expiresAtMillis: Long)
data class BudgetOverrideChange(val dailyUnits: Long, val durationMinutes: Int, val reason: String, val replacesId: String? = null)

/** Immutable grant metadata; revocation adds its actor, time and reason without erasing the grant. */
data class BudgetOverride(
    val id: String, val memberId: String, val poolId: String?, val dailyUnits: Long,
    val createdBy: String, val reason: String, val createdAtMillis: Long, val expiresAtMillis: Long,
    val replacesId: String? = null, val revokedAtMillis: Long? = null, val revokedBy: String? = null, val revocationReason: String? = null,
) {
    fun isActive(now: Long, pool: String?) = revokedAtMillis == null && createdAtMillis <= now && expiresAtMillis > now && poolId == pool
    fun summary() = BudgetOverrideSummary(id, dailyUnits, expiresAtMillis)
}
data class BudgetOverridePage(val overrides: List<BudgetOverride>, val activeOverrideId: String?, val nextBeforeId: String?)

interface BudgetOverrideService {
    suspend fun history(actor: Principal, memberId: String, beforeId: String? = null): BudgetOverridePage
    suspend fun grant(actor: Principal, memberId: String, change: BudgetOverrideChange): BudgetOverride
    suspend fun revoke(actor: Principal, memberId: String, id: String, reason: String): BudgetOverride
}

/** Exceptions expire at the next UTC reset even when the requested duration would cross it. */
class CheckBudgetOverrideUseCase {
    fun expiresAt(change: BudgetOverrideChange, now: Long): Long {
        if (change.dailyUnits !in 0..100_000_000_000L || change.durationMinutes !in 1..1440)
            throw TeamFailure("invalid_budget_override", 400, "Use 0–100000000000 daily credits and a duration of 1–1440 minutes.")
        reason(change.reason)
        return minOf(now + change.durationMinutes * 60_000L, (now / DAY + 1) * DAY)
    }
    fun reason(value: String): String = value.trim().also {
        if (it.length !in 1..240 || it.any { character -> character < ' ' || character == '\u007f' })
            throw TeamFailure("invalid_budget_override", 400, "Provide a single-line reason of 1–240 characters, without secrets.")
    }
    companion object { private const val DAY = 86_400_000L }
}
