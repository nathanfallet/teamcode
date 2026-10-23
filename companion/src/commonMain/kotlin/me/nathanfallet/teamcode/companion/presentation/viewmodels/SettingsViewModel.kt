package me.nathanfallet.teamcode.companion.presentation.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import me.nathanfallet.teamcode.companion.domain.models.Account
import me.nathanfallet.teamcode.companion.domain.models.ThemePreference
import me.nathanfallet.teamcode.companion.domain.repositories.AccountRepository
import me.nathanfallet.teamcode.companion.domain.repositories.PreferencesRepository
import me.nathanfallet.teamcode.companion.domain.services.CredentialStore
import me.nathanfallet.teamcode.companion.domain.services.PlatformInfo
import me.nathanfallet.teamcode.companion.domain.usecases.auth.RenameDeviceUseCase
import me.nathanfallet.teamcode.companion.domain.usecases.auth.SignOutUseCase

data class SettingsUiState(
    val accounts: List<Account> = emptyList(),
    val activeAccountId: String? = null,
    val theme: ThemePreference = ThemePreference.SYSTEM,
    val appVersion: String = "",
    /** False on a desktop without a keyring: tokens are not kept after the app quits. */
    val persistentCredentials: Boolean = true,
    val working: Boolean = false,
    val message: String? = null,
    /** On the desktop, where the CLI's `config.json` is (shown with the CLI's account). */
    val cliConfigPath: String? = null,
) {
    val activeAccount: Account? get() = accounts.firstOrNull { it.id == activeAccountId }
}

class SettingsViewModel(
    private val accounts: AccountRepository,
    private val preferences: PreferencesRepository,
    private val signOut: SignOutUseCase,
    private val renameDevice: RenameDeviceUseCase,
    private val credentials: CredentialStore,
    platform: PlatformInfo,
    cliConfigPath: String? = null,
) : ViewModel() {
    private val status = MutableStateFlow(SettingsUiState(appVersion = platform.appVersion, persistentCredentials = credentials.persistent, cliConfigPath = cliConfigPath))
    // Read again whenever the accounts change: a sign-in that fell back to memory (locked keyring) shows at once.
    val uiState: StateFlow<SettingsUiState> = combine(accounts.accounts, accounts.activeAccountId, preferences.theme, status) { list, active, theme, status ->
        status.copy(accounts = list, activeAccountId = active, theme = theme, persistentCredentials = credentials.persistent)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, status.value)

    fun switchTo(account: Account) = accounts.setActive(account.id)
    fun setTheme(theme: ThemePreference) = preferences.setTheme(theme)
    fun dismissMessage() = status.update { it.copy(message = null) }

    fun signOut(account: Account) = work {
        when (signOut(account.id)) {
            SignOutUseCase.Result.REVOKED -> "Signed out of ${account.username} on ${account.origin}."
            SignOutUseCase.Result.SERVER_UNREACHABLE -> "Signed out on this device. The server could not be reached; its session expires on its own."
            SignOutUseCase.Result.STORAGE_REFUSED ->
                "Signed out on this device, but its secure storage refused access: the session may stay valid until it expires. Revoke it from the server's Devices page."
        }
    }

    fun renameDevice(name: String) = work {
        val account = accounts.activeAccountId.value?.let { id -> accounts.accounts.value.firstOrNull { it.id == id } } ?: return@work null
        val renamed = renameDevice(account.id, name)
        "This device is now \"${renamed.deviceName}\" on ${renamed.origin}."
    }

    private fun work(action: suspend () -> String?) {
        if (status.value.working) return
        status.update { it.copy(working = true, message = null) }
        viewModelScope.launch {
            val message = try {
                action()
            } catch (error: CancellationException) {
                // The screen is closing, or the account's client was closed under the call: never a stuck spinner.
                status.update { it.copy(working = false) }
                throw error
            } catch (error: Exception) {
                error.userMessage()
            }
            status.update { it.copy(working = false, message = message) }
        }
    }
}
