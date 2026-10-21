package me.nathanfallet.teamcode.domain.capacity

import me.nathanfallet.teamcode.domain.accounts.AccountRecord

/**
 * What is left of one current provider window before the team's quota pressure threshold, until [resetsAtMillis]
 * (null when the provider did not say). [remainingShare] is a share of the whole window (0.0 to 1.0): the threshold
 * minus [utilization], never negative.
 */
data class WindowHeadroom(val window: String, val utilization: Double, val remainingShare: Double, val observedAtMillis: Long, val resetsAtMillis: Long?)

/**
 * One account that can take a request now, with the remaining share of each current window that applies. A window
 * absent from [windows] is unknown (never observed, or its observation is stale), not full.
 */
data class AccountHeadroom(val account: AccountRecord, val windows: List<WindowHeadroom>) {
    fun window(name: String): WindowHeadroom? = windows.firstOrNull { it.window == name }
}

/**
 * The one computation of the remaining capacity of the usable accounts, per window until its reset, for pacing
 * projections and capacity sizing alike. Usable means what admission and `teamcode status` mean
 * ([AccountUnavailability.of]): enabled, in a serving state (not pending handover, not `RECONNECT_REQUIRED`), not
 * cooling down, and no current window that applies at or above the threshold. Excluded accounts are absent from the
 * result, so an exhausted or disconnected account never counts as headroom.
 *
 * Without [model], only shared windows apply (as in [DescribePoolAccountsUseCase]); with it, the account's model
 * windows that apply to it count too, using [meteredLimit] for the account's observed model limit, if any.
 */
class AccountHeadroomUseCase {
    fun usable(
        accounts: List<PoolAccountObservation>, now: Long, pressureThreshold: Double = DEFAULT_QUOTA_PRESSURE_THRESHOLD,
        model: String? = null, meteredLimit: (AccountRecord) -> String? = { null },
    ): List<AccountHeadroom> {
        require(pressureThreshold in 0.0..1.0)
        return accounts.mapNotNull { observed ->
            val account = observed.account
            val applicable = observed.windows.filter { quota ->
                quota.accountId == account.id && quota.isCurrent(now) &&
                    (!quota.window.startsWith("model:") || (model != null && quota.appliesTo(model, meteredLimit(account))))
            }
            val exhausted = applicable.any { it.utilization >= pressureThreshold }
            if (AccountUnavailability.of(account, observed.cooldownUntilMillis, exhausted, now) != null) return@mapNotNull null
            AccountHeadroom(account, applicable.sortedBy { it.window }.map {
                WindowHeadroom(it.window, it.utilization, (pressureThreshold - it.utilization).coerceIn(0.0, 1.0), it.observedAtMillis, it.resetsAtMillis)
            })
        }
    }
}
