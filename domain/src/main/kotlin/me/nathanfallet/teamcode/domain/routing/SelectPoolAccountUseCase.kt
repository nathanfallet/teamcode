package me.nathanfallet.teamcode.domain.routing

import me.nathanfallet.teamcode.domain.accounts.AccountRecord

/**
 * Keep a conversation on its pinned account while that account stays eligible, to reuse its prompt cache: neither its
 * load nor a higher-priority account moves it. Only leaving [eligible] (a relevant quota window at the pressure threshold, disablement,
 * cooldown, reconnect, exclusion or a pool change) does. New conversations prefer home capacity before shared overflow,
 * then priority and load.
 */
class SelectPoolAccountUseCase {
    fun select(eligible: List<AccountPlacement>, pinnedAccountIds: List<String>, homePool: String?): AccountRecord? {
        val selector = SelectAccountUseCase()
        pinnedAccountIds.forEach { id -> eligible.firstOrNull { it.account.id == id }?.let { return it.account } }
        return selector.select(eligible.filter { it.account.poolId == homePool }, emptyList())
            ?: selector.select(eligible.filter { it.account.poolId != homePool }, emptyList())
    }
}
