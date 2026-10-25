package me.nathanfallet.teamcode.companion.presentation.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import me.nathanfallet.teamcode.companion.domain.models.PendingMessage
import me.nathanfallet.teamcode.companion.domain.models.PermissionMode
import me.nathanfallet.teamcode.companion.domain.repositories.PreferencesRepository
import me.nathanfallet.teamcode.companion.domain.services.LocalAvailability
import me.nathanfallet.teamcode.companion.domain.services.LocalHost
import me.nathanfallet.teamcode.companion.domain.services.LocalSessionRequest
import me.nathanfallet.teamcode.companion.domain.services.LocalResumables
import me.nathanfallet.teamcode.companion.domain.services.LocalResumeBlock
import me.nathanfallet.teamcode.companion.domain.models.ResumableSession
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
    val error: Failure? = null,
    val started: LocalSessionStart? = null,
    /** "Install" of a missing CLI is running; [installOutput] holds its last lines. */
    val installing: Boolean = false,
    val installOutput: List<String> = emptyList(),
    /** The folder's conversations on this device, read when the picker first opens; null until then. */
    val resumable: List<ResumableSession>? = null,
    /** The cursor of the next, older page; null on the last one. */
    val resumableNext: Long? = null,
    val loadingResumable: Boolean = false,
    /** Why the conversations could not be read, shown in the picker; the form can still start a new conversation. */
    val resumableError: Failure? = null,
    /** Why this device cannot continue a conversation at all (its daemon off, too old…): the picker is disabled and says so. */
    val resumeBlock: LocalResumeBlock? = null,
    val resumeOpen: Boolean = false,
    /** The conversation the session continues; null, the default, starts a new one. */
    val resumeId: String? = null,
) {
    /** The picker shows once a folder is chosen, for Claude Code only. */
    val showsResume: Boolean get() = availability == LocalAvailability.Ready && directory.isNotBlank() && agent == "claude"

    val canStart: Boolean get() = availability == LocalAvailability.Ready && !starting && started == null && directory.isNotBlank() && name.isNotBlank()

    /** The session started but its first message did not reach it: the session screen still opens, and says so. */
    val messageFailed: Boolean get() = started?.firstMessage?.let { it.state == PendingMessage.State.FAILED || it.state == PendingMessage.State.REFUSED } == true
}

/** "New session on this device": a folder, an agent, a name, a first message, the permission mode. */
class LocalSessionViewModel(
    private val route: NewSessionRoute,
    private val start: StartLocalSessionUseCase,
    private val host: LocalHost,
    /** Keeps the permission mode of the last launch, here and on another device alike. */
    private val preferences: PreferencesRepository,
) : ViewModel() {
    private val state = MutableStateFlow(
        (route.directory?.let { LocalSessionUiState(directory = it, name = StartLocalSessionUseCase.defaultName(it)) } ?: LocalSessionUiState())
            .copy(bypassPermissions = preferences.permissionMode == PermissionMode.BYPASS),
    )
    val uiState: StateFlow<LocalSessionUiState> = state.asStateFlow()
    private var resumableJob: Job? = null

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

    /** Installs the missing CLI (the app's own version), showing its output, then checks again. */
    fun installCli() {
        if (state.value.installing) return
        state.update { it.copy(installing = true, installOutput = emptyList(), error = null) }
        viewModelScope.launch {
            try {
                host.installCli { line -> state.update { it.copy(installOutput = (it.installOutput + line).takeLast(MAX_OUTPUT_LINES)) } }
                state.update { it.copy(installing = false) }
                check()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                state.update { it.copy(installing = false, error = error.userMessage()) }
            }
        }
    }

    fun chooseDirectory() {
        viewModelScope.launch {
            val directory = try { host.pickDirectory() } catch (error: CancellationException) { throw error } catch (error: Exception) {
                state.update { it.copy(error = error.userMessage()) }
                null
            } ?: return@launch
            edit {
                val moved = directory != it.directory
                it.copy(directory = directory, name = if (it.nameEdited) it.name else StartLocalSessionUseCase.defaultName(directory))
                    .let { next -> if (moved) next.withoutResume(list = true) else next }
            }
        }
    }

    // Codex continues no Claude Code conversation: the choice goes with the agent.
    fun onAgent(value: String) = edit { if (value == it.agent) it else it.copy(agent = value).withoutResume() }

    /** Opens or closes the picker; the first opening reads the folder's conversations on this device. */
    fun onResumeOpen() {
        val current = state.value
        if (current.starting || !current.showsResume || current.resumeBlock != null) return
        state.update { it.copy(resumeOpen = !it.resumeOpen) }
        if (state.value.resumeOpen && current.resumable == null && !current.loadingResumable) loadResumable(null)
    }

    /** Reads the conversations again: after a failure, or once the reason this device could not continue one is fixed. */
    fun onResumeRetry() { if (state.value.showsResume && !state.value.loadingResumable) loadResumable(null) }

    /** The next, older page, added under the ones shown. */
    fun onResumeMore() { state.value.resumableNext?.let { if (!state.value.loadingResumable) loadResumable(it) } }

    /** [id] null: none, a new conversation. Closes the picker. */
    fun onResume(id: String?) {
        if (id != null && state.value.resumable?.none { it.id == id } != false) return
        edit { it.copy(resumeId = id, resumeOpen = false) }
    }

    /** Only the answer for the folder still chosen counts. */
    private fun loadResumable(before: Long?) {
        val directory = state.value.directory.takeIf { it.isNotBlank() } ?: return
        resumableJob?.cancel()
        state.update { it.copy(loadingResumable = true, resumableError = null) }
        resumableJob = viewModelScope.launch {
            try {
                val found = start.resumable(route.accountId, directory, before)
                if (state.value.directory != directory) return@launch
                when (found) {
                    is LocalResumables.Unavailable -> state.update {
                        it.copy(loadingResumable = false, resumeBlock = found.block, resumeOpen = false, resumeId = null, resumable = null, resumableNext = null)
                    }
                    is LocalResumables.Page -> state.update {
                        val shown = if (before == null) found.page.sessions
                            else it.resumable.orEmpty() + found.page.sessions.filter { s -> it.resumable.orEmpty().none { k -> k.id == s.id } }
                        it.copy(resumable = shown, resumableNext = found.page.nextBefore, loadingResumable = false, resumeBlock = null,
                            resumeOpen = it.resumeOpen || it.resumeBlock != null)
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (state.value.directory != directory) return@launch
                val message = error.userMessage()
                state.update { it.copy(loadingResumable = false, resumableError = message, resumeOpen = true) }
            }
        }
    }

    // A conversation belongs to its folder: another folder clears the choice and the list, and drops a list still loading.
    private fun LocalSessionUiState.withoutResume(list: Boolean = false): LocalSessionUiState {
        if (list) resumableJob?.cancel()
        return copy(resumeId = null, resumeOpen = false,
            resumable = if (list) null else resumable, resumableNext = if (list) null else resumableNext,
            loadingResumable = if (list) false else loadingResumable, resumableError = if (list) null else resumableError,
            resumeBlock = if (list) null else resumeBlock)
    }
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
                val request = LocalSessionRequest(current.directory, current.agent, current.name, current.model, current.effort, current.bypassPermissions,
                    resumeSessionId = current.resumeId.takeIf { current.showsResume })
                val started = start(route.accountId, request, current.message)
                // Kept once the session started with it, as for a session on another device.
                preferences.setPermissionMode(if (current.bypassPermissions) PermissionMode.BYPASS else PermissionMode.ASK)
                state.update { it.copy(starting = false, started = started) }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                state.update { it.copy(starting = false, error = error.userMessage()) }
            }
        }
    }

    private companion object { const val MAX_OUTPUT_LINES = 200 }
}
