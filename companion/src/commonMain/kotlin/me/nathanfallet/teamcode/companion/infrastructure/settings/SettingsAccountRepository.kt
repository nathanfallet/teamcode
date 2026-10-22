package me.nathanfallet.teamcode.companion.infrastructure.settings

import com.russhwolf.settings.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import me.nathanfallet.teamcode.companion.domain.models.Account
import me.nathanfallet.teamcode.companion.domain.repositories.AccountRepository

/** Accounts in the app's preferences, as JSON. Metadata only: no token or password is ever written here. */
class SettingsAccountRepository(private val settings: Settings) : AccountRepository {
    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(Account.serializer())
    private val state = MutableStateFlow(load())
    private val active = MutableStateFlow(settings.getStringOrNull(ACTIVE_KEY)?.takeIf { id -> state.value.any { it.id == id } } ?: state.value.firstOrNull()?.id)

    override val accounts: StateFlow<List<Account>> = state.asStateFlow()
    override val activeAccountId: StateFlow<String?> = active.asStateFlow()

    override fun save(account: Account) {
        state.update { list -> if (list.any { it.id == account.id }) list.map { if (it.id == account.id) account else it } else list + account }
        persist()
        setActive(account.id)
    }

    override fun update(account: Account) {
        state.update { list -> list.map { if (it.id == account.id) account else it } }
        persist()
    }

    override fun remove(id: String) {
        state.update { list -> list.filterNot { it.id == id } }
        persist()
        if (active.value == id) setActiveOrClear(state.value.firstOrNull()?.id)
    }

    override fun setActive(id: String) {
        if (state.value.any { it.id == id }) setActiveOrClear(id)
    }

    private fun setActiveOrClear(id: String?) {
        active.value = id
        if (id == null) settings.remove(ACTIVE_KEY) else settings.putString(ACTIVE_KEY, id)
    }

    private fun load(): List<Account> = settings.getStringOrNull(ACCOUNTS_KEY)?.let {
        // A preference written by a future version, or damaged, must not stop the app from starting.
        runCatching { json.decodeFromString(serializer, it) }.getOrNull()
    }.orEmpty()

    private fun persist() = settings.putString(ACCOUNTS_KEY, json.encodeToString(serializer, state.value))

    private companion object {
        const val ACCOUNTS_KEY = "accounts.v1"
        const val ACTIVE_KEY = "accounts.active"
    }
}
