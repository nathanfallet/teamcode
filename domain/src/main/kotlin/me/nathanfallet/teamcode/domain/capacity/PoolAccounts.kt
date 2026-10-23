package me.nathanfallet.teamcode.domain.capacity

import me.nathanfallet.teamcode.domain.accounts.AccountRecord
import me.nathanfallet.teamcode.domain.accounts.AccountState

/** Why an account cannot take a new request right now; the first one that applies, in this order. */
enum class AccountUnavailability { DISABLED, PENDING_HANDOVER, RECONNECT_REQUIRED, COOLING_DOWN, QUOTA_EXHAUSTED;
    companion object {
        /**
         * The one classification of an account that cannot serve: placement counts it in a [PoolStanding] refusal and
         * `teamcode status` shows it. [quotaExhausted] says whether a current window that applies is at the team's quota pressure threshold ([PacingSettings.quotaPressureThreshold]).
         */
        fun of(account: AccountRecord, cooldownUntilMillis: Long, quotaExhausted: Boolean, now: Long): AccountUnavailability? = when {
            account.disabled -> DISABLED
            account.state == AccountState.PENDING_HANDOVER -> PENDING_HANDOVER
            !account.state.canServe -> RECONNECT_REQUIRED
            cooldownUntilMillis > now -> COOLING_DOWN
            quotaExhausted -> QUOTA_EXHAUSTED
            else -> null
        }
    }
}

/** What the store reads for one account; [cooldownUntilMillis] is 0 when no cool-down was ever set. */
data class PoolAccountObservation(val account: AccountRecord, val cooldownUntilMillis: Long, val windows: List<AccountQuota>)

/**
 * One subscription account as the caller may see it. [accountId], [name], [email] and [poolId] are null unless identities
 * are revealed; [label] then is a neutral "Account N", numbered in priority order (it can change when
 * accounts or priorities change, so it names a row, not an account); the listing itself keeps the order it is given. [windows] holds only current observations.
 */
data class PoolAccountStatus(
    val accountId: String?, val name: String?, val label: String, val provider: String, val poolId: String?,
    val state: String, val disabled: Boolean, val unavailable: AccountUnavailability?, val cooldownUntilMillis: Long?,
    val windows: List<AccountQuota>, val email: String? = null,
) { val usable: Boolean get() = unavailable == null }

/**
 * Reads the same eligibility as admission: enabled, serving state, no cool-down and no current shared window at
 * the team's quota pressure threshold ([PacingSettings.quotaPressureThreshold]). A model-scoped window (`model:…`) only bars that model, so it is shown without making
 * the account unusable. Identities are revealed to interactive administrators only; members get neutral labels.
 */
class DescribePoolAccountsUseCase {
    fun describe(accounts: List<PoolAccountObservation>, now: Long, revealIdentity: Boolean, pressureThreshold: Double = DEFAULT_QUOTA_PRESSURE_THRESHOLD): List<PoolAccountStatus> =
        // The caller's order is the listing's order (the store sorts by next reset); only the neutral numbering follows priority.
        accounts.sortedWith(compareByDescending<PoolAccountObservation> { it.account.priority }.thenBy { it.account.id }).map { it.account.id }.let { byPriority ->
            accounts.map { observed ->
                val index = byPriority.indexOf(observed.account.id)
                val account = observed.account
                val windows = observed.windows.filter { it.accountId == account.id && it.isCurrent(now) }.sortedBy { it.window }
                val cooling = observed.cooldownUntilMillis.takeIf { it > now }
                // Without a request there is no model: only shared windows bar every model.
                val unavailable = AccountUnavailability.of(account, observed.cooldownUntilMillis,
                    windows.any { !it.window.startsWith("model:") && it.utilization >= pressureThreshold }, now)
                PoolAccountStatus(
                    accountId = account.id.takeIf { revealIdentity }, name = account.displayName.takeIf { revealIdentity },
                    label = if (revealIdentity) account.displayName else "Account ${index + 1}",
                    provider = account.identity.provider, poolId = account.poolId.takeIf { revealIdentity }, state = account.state.name,
                    disabled = account.disabled, unavailable = unavailable, cooldownUntilMillis = cooling, windows = windows,
                    email = account.email.takeIf { revealIdentity },
                )
            }
        }
}
