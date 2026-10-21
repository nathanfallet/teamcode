package me.nathanfallet.teamcode.domain.capacity

import me.nathanfallet.teamcode.domain.accounts.AccountRecord
import me.nathanfallet.teamcode.domain.accounts.AccountState

/** Admission skips an account once any window that applies to the request reaches this utilization. */
const val QUOTA_EXHAUSTED_UTILIZATION = .95

/** Why an account cannot take a new request right now; the first one that applies, in this order. */
enum class AccountUnavailability { DISABLED, PENDING_HANDOVER, RECONNECT_REQUIRED, COOLING_DOWN, QUOTA_EXHAUSTED }

/** What the store reads for one account; [cooldownUntilMillis] is 0 when no cool-down was ever set. */
data class PoolAccountObservation(val account: AccountRecord, val cooldownUntilMillis: Long, val windows: List<AccountQuota>)

/**
 * One subscription account as the caller may see it. [accountId], [name] and [email] are null unless identities are
 * revealed; [label] then is a neutral, stable "Account N". [windows] holds only current observations.
 */
data class PoolAccountStatus(
    val accountId: String?, val name: String?, val label: String, val provider: String, val poolId: String?,
    val state: String, val disabled: Boolean, val unavailable: AccountUnavailability?, val cooldownUntilMillis: Long?,
    val windows: List<AccountQuota>, val email: String? = null,
) { val usable: Boolean get() = unavailable == null }

/**
 * Reads the same eligibility as admission: enabled, serving state, no cool-down and no current shared window at
 * [QUOTA_EXHAUSTED_UTILIZATION]. A model-scoped window (`model:…`) only bars that model, so it is shown without making
 * the account unusable. Identities are revealed to interactive administrators only; members get neutral labels.
 */
class DescribePoolAccountsUseCase {
    fun describe(accounts: List<PoolAccountObservation>, now: Long, revealIdentity: Boolean): List<PoolAccountStatus> =
        accounts.sortedWith(compareByDescending<PoolAccountObservation> { it.account.priority }.thenBy { it.account.id })
            .mapIndexed { index, observed ->
                val account = observed.account
                val windows = observed.windows.filter { it.accountId == account.id && it.isCurrent(now) }.sortedBy { it.window }
                val cooling = observed.cooldownUntilMillis.takeIf { it > now }
                val unavailable = when {
                    account.disabled -> AccountUnavailability.DISABLED
                    account.state == AccountState.PENDING_HANDOVER -> AccountUnavailability.PENDING_HANDOVER
                    !account.state.canServe -> AccountUnavailability.RECONNECT_REQUIRED
                    cooling != null -> AccountUnavailability.COOLING_DOWN
                    windows.any { !it.window.startsWith("model:") && it.utilization >= QUOTA_EXHAUSTED_UTILIZATION } -> AccountUnavailability.QUOTA_EXHAUSTED
                    else -> null
                }
                PoolAccountStatus(
                    accountId = account.id.takeIf { revealIdentity }, name = account.displayName.takeIf { revealIdentity },
                    label = if (revealIdentity) account.displayName else "Account ${index + 1}",
                    provider = account.identity.provider, poolId = account.poolId, state = account.state.name,
                    disabled = account.disabled, unavailable = unavailable, cooldownUntilMillis = cooling, windows = windows,
                    email = account.email.takeIf { revealIdentity },
                )
            }
}
