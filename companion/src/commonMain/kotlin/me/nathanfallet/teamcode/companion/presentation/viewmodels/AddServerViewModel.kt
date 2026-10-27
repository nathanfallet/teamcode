package me.nathanfallet.teamcode.companion.presentation.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import me.nathanfallet.teamcode.companion.demo.StartDemoUseCase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import me.nathanfallet.teamcode.companion.domain.models.ServerInfo
import me.nathanfallet.teamcode.companion.domain.repositories.AccountRepository
import me.nathanfallet.teamcode.companion.domain.usecases.auth.InspectServerUseCase
import me.nathanfallet.teamcode.companion.domain.usecases.auth.trimTyped

data class AddServerUiState(
    val origin: String = "",
    val checking: Boolean = false,
    /** The server that answered, shown in full before any password is asked. */
    val server: ServerInfo? = null,
    /** True when no account (signed in or not) uses this origin yet: the screen says it is a new server. */
    val newServer: Boolean = true,
    val error: Failure? = null,
    /** "Try the demo" is offered: there is no account on this device yet. */
    val demoOffered: Boolean = false,
)

class AddServerViewModel(
    private val inspect: InspectServerUseCase,
    private val accounts: AccountRepository,
    private val startDemo: StartDemoUseCase,
) : ViewModel() {
    private val state = MutableStateFlow(AddServerUiState(demoOffered = startDemo.offered))
    val uiState: StateFlow<AddServerUiState> = state.asStateFlow()
    private var check: Job? = null

    fun onOriginChange(value: String) {
        check?.cancel()
        state.value = AddServerUiState(origin = value, demoOffered = state.value.demoOffered)
    }

    /** Opens the built-in demo: its account appears, and the app leaves the sign-in screens for it. */
    fun tryDemo() {
        if (!startDemo.offered) return
        check?.cancel()
        startDemo()
    }

    fun check() {
        // What a keyboard added around the address goes before it is checked, and from the field too.
        val origin = state.value.origin.trimTyped()
        if (state.value.checking || origin.isEmpty()) return
        state.update { it.copy(origin = origin, checking = true, error = null, server = null) }
        check = viewModelScope.launch {
            try {
                val server = inspect(origin)
                state.update { it.copy(checking = false, server = server, newServer = accounts.accounts.value.none { account -> account.origin == server.origin }) }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                state.update { it.copy(checking = false, error = error.userMessage()) }
            }
        }
    }
}
