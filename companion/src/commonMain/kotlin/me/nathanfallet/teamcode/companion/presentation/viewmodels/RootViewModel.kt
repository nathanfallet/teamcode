package me.nathanfallet.teamcode.companion.presentation.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import me.nathanfallet.teamcode.companion.domain.models.ThemePreference
import me.nathanfallet.teamcode.companion.domain.repositories.AccountRepository
import me.nathanfallet.teamcode.companion.domain.repositories.PreferencesRepository

/** The start destination (adding a server when there is no account yet) and the theme. */
class RootViewModel(accounts: AccountRepository, preferences: PreferencesRepository) : ViewModel() {
    val hasAccounts: StateFlow<Boolean> = accounts.accounts
        .map { it.isNotEmpty() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, accounts.accounts.value.isNotEmpty())
    val theme: StateFlow<ThemePreference> = preferences.theme
}
