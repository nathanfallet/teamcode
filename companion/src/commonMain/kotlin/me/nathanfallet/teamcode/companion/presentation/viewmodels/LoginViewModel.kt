package me.nathanfallet.teamcode.companion.presentation.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import me.nathanfallet.teamcode.companion.domain.models.CompanionException
import me.nathanfallet.teamcode.companion.domain.models.ServerInfo
import me.nathanfallet.teamcode.companion.domain.models.SignInResult
import me.nathanfallet.teamcode.companion.domain.services.PlatformInfo
import me.nathanfallet.teamcode.companion.domain.usecases.auth.ChangePasswordUseCase
import me.nathanfallet.teamcode.companion.domain.usecases.auth.InspectServerUseCase
import me.nathanfallet.teamcode.companion.domain.usecases.auth.SignInUseCase
import me.nathanfallet.teamcode.companion.presentation.navigation.LoginRoute

data class LoginUiState(
    val origin: String,
    val server: ServerInfo? = null,
    val username: String = "",
    val password: String = "",
    val deviceName: String = "",
    /** Set when the server requires a new password before anything else. */
    val passwordChange: SignInResult.PasswordChangeRequired? = null,
    val newPassword: String = "",
    val confirmation: String = "",
    val working: Boolean = false,
    val error: String? = null,
    val signedIn: Boolean = false,
) {
    val canSubmit: Boolean get() = server != null && !working && when (passwordChange) {
        null -> username.isNotBlank() && password.isNotEmpty() && deviceName.isNotBlank()
        else -> newPassword.isNotEmpty() && confirmation.isNotEmpty()
    }

    // Passwords stay out of logs and crash reports.
    override fun toString() = "LoginUiState($origin, server=${server?.serverVersion}, working=$working, signedIn=$signedIn)"
}

/** Password sign-in, then the password change a temporary password requires, as `teamcode login`. */
class LoginViewModel(
    private val route: LoginRoute,
    private val inspect: InspectServerUseCase,
    private val signIn: SignInUseCase,
    private val changePassword: ChangePasswordUseCase,
    platform: PlatformInfo,
) : ViewModel() {
    private val state = MutableStateFlow(LoginUiState(route.origin, username = route.username.orEmpty(), deviceName = platform.defaultDeviceName))
    val uiState: StateFlow<LoginUiState> = state.asStateFlow()

    init {
        loadServer()
    }

    fun loadServer() {
        state.update { it.copy(working = true, error = null) }
        viewModelScope.launch {
            try {
                val server = inspect(route.origin)
                state.update { it.copy(server = server, working = false) }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                state.update { it.copy(working = false, error = error.userMessage()) }
            }
        }
    }

    fun onUsernameChange(value: String) = state.update { it.copy(username = value, error = null) }
    fun onPasswordChange(value: String) = state.update { it.copy(password = value, error = null) }
    fun onDeviceNameChange(value: String) = state.update { it.copy(deviceName = value, error = null) }
    fun onNewPasswordChange(value: String) = state.update { it.copy(newPassword = value, error = null) }
    fun onConfirmationChange(value: String) = state.update { it.copy(confirmation = value, error = null) }

    fun submit() {
        val current = state.value
        val server = current.server ?: return
        if (!current.canSubmit) return
        state.update { it.copy(working = true, error = null) }
        viewModelScope.launch {
            try {
                val pending = current.passwordChange
                val result = if (pending == null) signIn(server, current.username, current.password, current.deviceName)
                else changePassword(pending, current.password, current.newPassword, current.confirmation)
                when (result) {
                    is SignInResult.SignedIn -> state.update { it.copy(working = false, signedIn = true, password = "", newPassword = "", confirmation = "") }
                    is SignInResult.PasswordChangeRequired -> state.update { it.copy(working = false, passwordChange = result) }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: CompanionException.InvalidCredentials) {
                // During a password change, the temporary session expired or was revoked: back to signing in.
                state.update {
                    if (it.passwordChange == null) it.copy(working = false, error = error.userMessage())
                    else it.copy(working = false, passwordChange = null, password = "", newPassword = "", confirmation = "", error = "The temporary session ended. Sign in again with the temporary password.")
                }
            } catch (error: CompanionException.PasswordChanged) {
                // Back to the sign-in form: the temporary password and its session no longer exist.
                state.update { it.copy(working = false, passwordChange = null, password = "", newPassword = "", confirmation = "", error = error.userMessage()) }
            } catch (error: Exception) {
                state.update { it.copy(working = false, error = error.userMessage()) }
            }
        }
    }
}
