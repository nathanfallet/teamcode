package me.nathanfallet.teamcode.domain.capacity

import me.nathanfallet.teamcode.domain.accounts.AccountRecord
import me.nathanfallet.teamcode.domain.routing.RouteTarget

data class AccountPacing(val account: AccountRecord, val windows: List<WindowPacing>, val modelLimits: Map<String, String> = emptyMap())

/** Capacity is specific to the target actually serving work, never a sum of unrelated plans. */
class RoutePacingUseCase(private val pacing: PacingUseCase = PacingUseCase()) {
    fun advise(targets: Set<RouteTarget>, accounts: List<AccountPacing>, maximum: Int, now: Long, allowanceAvailable: Boolean = true, homePool: String? = null, preferHome: Boolean = false, pinnedAccountIds: Map<RouteTarget, Set<String>> = emptyMap()): PacingAdvice {
        if (!allowanceAvailable) return PacingAdvice(Pace.PAUSE, 1, 30_000,
            "An applicable member, group, run or pool allowance is unavailable, or admission is paused. Pause new work until capacity returns or access is reviewed.")
        if (targets.isEmpty()) return pacing.advise(emptyList(), maximum)
        val advice = targets.map { target ->
            val candidates = accounts.filter { it.account.identity.provider == target.provider && !it.account.disabled && it.account.state.canServe }
            // Match placement priority. Idle low-priority capacity does not promise headroom
            // while requests still go to a pressured higher-priority account.
            val available = candidates.filter { account -> account.windows.none {
                target.model != null && it.quota.appliesTo(target.model, account.modelLimits[target.model.lowercase()]) && it.quota.utilization >= .95 && it.quota.isCurrent(now)
            } }
            val authorized = available.ifEmpty { candidates }
            val pinned = authorized.filter { candidate -> candidate.account.id in pinnedAccountIds[target].orEmpty() &&
                candidate.account.priority == authorized.filter { it.account.poolId == candidate.account.poolId }.maxOf { it.account.priority } }
            val home = authorized.filter { it.account.poolId == homePool }
            val selectable = if (!preferHome) authorized else pinned.ifEmpty { home.ifEmpty { authorized } }
            // Active pins represent separate conversations, even on the same target.
            // Each may remain attached to a different account; idle work must not hide burn elsewhere.
            val groups = if (preferHome && pinned.isNotEmpty()) pinned.map { listOf(it) } else listOf(selectable)
            combine(groups.map { group ->
                val priority = group.maxOfOrNull { it.account.priority }
                val windows = group.filter { it.account.priority == priority }.map { account ->
                    account.windows.filter { target.model != null && it.quota.appliesTo(target.model, account.modelLimits[target.model.lowercase()]) }
                }
                pacing.advise(windows, minOf(maximum, maxOf(1, windows.size * 2)))
            }, maximum)
        }
        // One scope may use several targets. Never advise acceleration merely because
        // one target is idle while another active target in the same scope is pressured.
        return combine(advice, maximum)
    }
    private fun combine(advice: List<PacingAdvice>, maximum: Int): PacingAdvice {
        val selected = advice.maxBy { when (it.pace) {
            Pace.CAN_INCREASE -> 0; Pace.NORMAL -> 1; Pace.UNKNOWN -> 2; Pace.SLOW_DOWN -> 3; Pace.PAUSE -> 4
        } }
        return pacing.advice(selected.pace, minOf(maximum, advice.minOf { it.maxConcurrent }))
    }
}
