package me.nathanfallet.teamcode.companion.presentation.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import me.nathanfallet.teamcode.companion.domain.models.PendingMessage
import me.nathanfallet.teamcode.companion.domain.services.LocalAvailability
import me.nathanfallet.teamcode.companion.domain.services.LocalHost
import me.nathanfallet.teamcode.companion.domain.services.LocalSessionRequest
import me.nathanfallet.teamcode.companion.domain.usecases.sessions.LocalSessionStart
import me.nathanfallet.teamcode.companion.domain.usecases.sessions.StartLocalSessionUseCase
import me.nathanfallet.teamcode.companion.presentation.navigation.NewSessionRoute

data class LocalSessionUiState(
    /** Null while it is checked. */
    val availability: LocalAvailability? = null,
    val directory: String = "",
    val agent: String = "claude",
    val name: String = "",
    /** False until the member types a name: the folder's name follows the chosen folder. */
    val nameEdited: Boolean = false,
    val model: String = "",
    val effort: String = "",
    val message: String = "",
    val bypassPermissions: Boolean = false,
    val starting: Boolean = false,
    val error: String? = null,
    val started: LocalSessionStart? = null,
) {
    val canStart: Boolean get() = availability == LocalAvailability.Ready && !starting && started == null && directory.isNotBlank() && name.isNotBlank()

    /** The session started but its first message did not reach it: the session screen still opens, and says so. */
    val messageFailed: Boolean get() = started?.firstMessage?.let { it.state == PendingMessage.State.FAILED || it.state == PendingMessage.State.REFUSED } == true
}

/** "New session on this device": a folder, an agent, a name, a first message, the permission mode. */
class LocalSessionViewModel(
    private val route: NewSessionRoute,
    private val start: StartLocalSessionUseCase,
    private val host: LocalHost,
) : ViewModel() {
    private val state = MutableStateFlow(LocalSessionUiState())
    val uiState: StateFlow<LocalSessionUiState> = state.asStateFlow()

    init { check() }

    /** Checks the CLI and the server again (after installing or updating the CLI, or signing it in). */
    fun check() {
        state.update { it.copy(availability = null, error = null) }
        viewModelScope.launch {
            try {
                val availability = start.availability(route.accountId)
                state.update { it.copy(availability = availability) }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                state.update { it.copy(error = error.userMessage()) }
            }
        }
    }

    fun chooseDirectory() {
        viewModelScope.launch {
            val directory = try { host.pickDirectory() } catch (error: CancellationException) { throw error } catch (error: Exception) {
                state.update { it.copy(error = error.userMessage()) }
                null
            } ?: return@launch
            edit { it.copy(directory = directory, name = if (it.nameEdited) it.name else StartLocalSessionUseCase.defaultName(directory)) }
        }
    }

    fun onAgent(value: String) = edit { it.copy(agent = value) }
    fun onName(value: String) = edit { it.copy(name = value, nameEdited = true) }
    fun onModel(value: String) = edit { it.copy(model = value) }
    fun onEffort(value: String) = edit { it.copy(effort = value) }
    fun onMessage(value: String) = edit { it.copy(message = value) }
    fun onBypass(value: Boolean) = edit { it.copy(bypassPermissions = value) }

    private fun edit(change: (LocalSessionUiState) -> LocalSessionUiState) {
        if (state.value.starting) return
        state.update { change(it).copy(error = null) }
    }

    fun create() {
        val current = state.value
        if (!current.canStart) return
        state.update { it.copy(starting = true, error = null) }
        viewModelScope.launch {
            try {
                val request = LocalSessionRequest(current.directory, current.agent, current.name, current.model, current.effort, current.bypassPermissions)
                val started = start(route.accountId, request, current.message)
                state.update { it.copy(starting = false, started = started) }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                state.update { it.copy(starting = false, error = error.userMessage()) }
            }
        }
    }
}
