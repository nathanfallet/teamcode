package me.nathanfallet.teamcode.domain.capacity

import me.nathanfallet.teamcode.domain.accounts.AccountRecord
import me.nathanfallet.teamcode.domain.routing.RouteTarget

data class AccountPacing(val account: AccountRecord, val windows: List<WindowPacing>, val modelLimits: Map<String, String> = emptyMap())

/** Capacity is specific to the target actually serving work, never a sum of unrelated plans. */
class RoutePacingUseCase(private val pacing: PacingUseCase = PacingUseCase()) {
    /** [maximum] zero means no member/group/run limit; accounts have no parallelism limit of their own, only quota windows. */
    fun advise(targets: Set<RouteTarget>, accounts: List<AccountPacing>, maximum: Int, now: Long, allowanceAvailable: Boolean = true, homePool: String? = null, preferHome: Boolean = false, pinnedAccountIds: Map<RouteTarget, Set<String>> = emptyMap(), observedConcurrency: Int = 0): PacingAdvice {
        if (!allowanceAvailable) return PacingAdvice(Pace.PAUSE, 1, 30_000,
            "An applicable member, group, run or pool allowance is unavailable, or admission is paused. Pause new work until capacity returns or access is reviewed.")
        if (targets.isEmpty()) return pacing.advise(emptyList(), maximum, now)
        val advice = targets.map { target ->
            val candidates = accounts.filter { it.account.identity.provider == target.provider && !it.account.disabled && it.account.state.canServe }
            // Match placement priority. Idle low-priority capacity does not promise headroom
            // while requests still go to a pressured higher-priority account.
            val available = candidates.filter { account -> account.windows.none {
                target.model != null && it.quota.appliesTo(target.model, account.modelLimits[target.model.lowercase()]) && it.quota.utilization >= .95 && it.quota.isCurrent(now)
            } }
            val authorized = available.ifEmpty { candidates }
            // A pin holds while its account is eligible, whatever the priority of the others (as placement does).
            val pinned = authorized.filter { candidate -> candidate.account.id in pinnedAccountIds[target].orEmpty() }
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
                pacing.advise(windows, maximum, now, observedConcurrency)
            }, maximum)
        }
        // One scope may use several targets. Never advise acceleration merely because
        // one target is idle while another active target in the same scope is pressured.
        return combine(advice, maximum)
    }
    /** The strictest advice, with its own level and interval, under every applicable concurrency ceiling. */
    private fun combine(advice: List<PacingAdvice>, maximum: Int): PacingAdvice =
        pacing.withCeiling(advice.maxWith(PACING_STRICTNESS), concurrencyCeiling(advice.map { it.maxConcurrent } + maximum))
}
