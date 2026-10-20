package me.nathanfallet.teamcode.domain.routing

import me.nathanfallet.teamcode.domain.accounts.AccountRecord

/** Keep an eligible conversation pin; otherwise prefer home capacity before shared overflow. */
class SelectPoolAccountUseCase {
    fun select(eligible: List<AccountPlacement>, pinnedAccountIds: List<String>, homePool: String?): AccountRecord? {
        val selector = SelectAccountUseCase()
        pinnedAccountIds.forEach { id ->
            val pin = eligible.firstOrNull { it.account.id == id }
            if (pin != null && pin.account.priority == eligible.filter { it.account.poolId == pin.account.poolId }.maxOf { it.account.priority })
                return pin.account
        }
        return selector.select(eligible.filter { it.account.poolId == homePool }, emptyList())
            ?: selector.select(eligible.filter { it.account.poolId != homePool }, emptyList())
    }
}
