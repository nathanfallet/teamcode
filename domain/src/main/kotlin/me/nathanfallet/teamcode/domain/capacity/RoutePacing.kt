package me.nathanfallet.teamcode.domain.capacity

import me.nathanfallet.teamcode.domain.accounts.AccountRecord
import me.nathanfallet.teamcode.domain.routing.RouteTarget

data class AccountPacing(val account: AccountRecord, val windows: List<WindowPacing>, val modelLimits: Map<String, String> = emptyMap())

/** Capacity is specific to the target actually serving work: the accounts of its provider, for its model. */
class RoutePacingUseCase(private val pacing: PacingUseCase = PacingUseCase(), private val pressureThreshold: Double = DEFAULT_QUOTA_PRESSURE_THRESHOLD) {
    /**
     * [accounts] are every account the scope may be routed to (its pool and the overflow it may use); each target is
     * paced on all of its provider's accounts together, whatever their priority or pins: placement moves work to
     * another account before one runs out, so only their total capacity says when work would stop.
     * [maximum] zero means no member/group/run limit; accounts have no parallelism limit of their own, only quota windows.
     */
    fun advise(targets: Set<RouteTarget>, accounts: List<AccountPacing>, maximum: Int, now: Long, allowanceAvailable: Boolean = true): PacingAdvice {
        if (!allowanceAvailable) return PacingAdvice(Pace.PAUSE, maximum, PacingUseCase.PAUSE_INTERVAL_MILLIS,
            "An applicable member, group, run or pool allowance is unavailable, or admission is paused. Pause new work until capacity returns or access is reviewed.")
        if (targets.isEmpty()) return pacing.advise(emptyList(), maximum, now, pressureThreshold)
        // One scope may use several targets. Never advise acceleration merely because
        // one target is idle while another active target in the same scope is pressured.
        val advice = targets.map { target ->
            pacing.advise(accounts.filter { it.account.identity.provider == target.provider }, maximum, now, pressureThreshold, target.model)
        }
        return pacing.withCeiling(advice.maxWith(PACING_STRICTNESS), concurrencyCeiling(advice.map { it.maxConcurrent } + maximum))
    }
}
