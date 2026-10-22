package me.nathanfallet.teamcode.companion.domain.repositories

import kotlinx.coroutines.flow.StateFlow
import me.nathanfallet.teamcode.companion.domain.models.Account

/** The accounts known on this device (metadata only) and the one the app shows. */
interface AccountRepository {
    val accounts: StateFlow<List<Account>>
    val activeAccountId: StateFlow<String?>

    /** Adds [account] or replaces the one with the same id, and makes it active. */
    fun save(account: Account)
    fun update(account: Account)
    /** Removes the account; the active one moves to another account, or to none. */
    fun remove(id: String)
    fun setActive(id: String)
}
