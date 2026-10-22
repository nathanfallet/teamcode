package me.nathanfallet.teamcode.companion.presentation.viewmodels

import kotlinx.coroutines.flow.collectLatest
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import me.nathanfallet.teamcode.companion.domain.models.Account
import me.nathanfallet.teamcode.companion.domain.repositories.AccountRepository
import me.nathanfallet.teamcode.companion.domain.usecases.auth.RefreshAccountUseCase

data class HomeUiState(val account: Account? = null, val refreshing: Boolean = false, val error: String? = null)

/** The active account, re-read from the server when it changes (a revoked device shows as signed out). */
class HomeViewModel(
    private val accounts: AccountRepository,
    private val refreshAccount: RefreshAccountUseCase,
) : ViewModel() {
    private val status = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = combine(accounts.accounts, accounts.activeAccountId, status) { list, active, status ->
        status.copy(account = list.firstOrNull { it.id == active })
    }.stateIn(viewModelScope, SharingStarted.Eagerly, HomeUiState())

    init {
        viewModelScope.launch {
            accounts.activeAccountId.collectLatest { id -> if (id != null) refresh(id) else status.value = HomeUiState() }
        }
    }


    private suspend fun refresh(id: String) {
        status.value = HomeUiState(refreshing = true)
        status.value = try {
            refreshAccount(id)
            HomeUiState()
        } catch (error: CancellationException) {
            // A newer account replaced this refresh, or its client was closed by a sign-out: no spinner left behind.
            status.value = HomeUiState()
            throw error
        } catch (error: Exception) {
            HomeUiState(error = error.userMessage())
        }
    }
}
