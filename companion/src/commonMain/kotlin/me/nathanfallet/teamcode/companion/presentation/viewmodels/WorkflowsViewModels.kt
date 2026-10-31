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
import me.nathanfallet.teamcode.companion.domain.models.CompanionException
import me.nathanfallet.teamcode.companion.domain.models.Profile
import me.nathanfallet.teamcode.companion.domain.models.Workflow
import me.nathanfallet.teamcode.companion.domain.services.IdGenerator
import me.nathanfallet.teamcode.companion.domain.models.WorkflowRun
import me.nathanfallet.teamcode.companion.domain.usecases.sessions.LoadProfilesUseCase
import me.nathanfallet.teamcode.companion.domain.usecases.workflows.GetWorkflowUseCase
import me.nathanfallet.teamcode.companion.domain.usecases.workflows.LoadWorkflowsUseCase
import me.nathanfallet.teamcode.companion.domain.usecases.workflows.StartWorkflowRunUseCase
import me.nathanfallet.teamcode.companion.presentation.navigation.WorkflowRoute
import me.nathanfallet.teamcode.companion.presentation.navigation.WorkflowStartRoute
import me.nathanfallet.teamcode.companion.presentation.navigation.WorkflowsRoute

data class WorkflowsUiState(
    /** Null until the server answered. */
    val workflows: List<Workflow>? = null,
    val nextAfter: String? = null,
    val loading: Boolean = true,
    val loadingMore: Boolean = false,
    val error: Failure? = null,
) {
    val empty: Boolean get() = workflows?.isEmpty() == true && !loading
}

/** The saved workflows, by id, a page at a time (read-only: editing definitions is still done over the API). */
class WorkflowsViewModel(private val route: WorkflowsRoute, private val load: LoadWorkflowsUseCase) : ViewModel() {
    private val state = MutableStateFlow(WorkflowsUiState())
    val uiState: StateFlow<WorkflowsUiState> = state.asStateFlow()
    private var reading: Job? = null

    init { reload() }

    fun reload() {
        reading?.cancel()
        state.update { it.copy(loading = true, loadingMore = false, error = null) }
        reading = viewModelScope.launch { page(after = null) }
    }

    fun loadMore() {
        val after = state.value.nextAfter ?: return
        if (reading?.isActive == true) return
        state.update { it.copy(loadingMore = true, error = null) }
        reading = viewModelScope.launch { page(after) }
    }

    private suspend fun page(after: String?) {
        try {
            val page = load(route.accountId, after)
            state.update { current ->
                val known = if (after == null) emptySet() else current.workflows.orEmpty().map { it.id }.toSet()
                val rows = if (after == null) page.workflows else current.workflows.orEmpty() + page.workflows.filter { it.id !in known }
                current.copy(workflows = rows, nextAfter = page.nextAfter, loading = false, loadingMore = false)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            state.update { it.copy(loading = false, loadingMore = false, error = error.userMessage()) }
        }
    }
}

data class WorkflowUiState(
    val workflow: Workflow? = null,
    val loading: Boolean = true,
    val missing: Boolean = false,
    val error: Failure? = null,
)

/** One saved workflow: its inputs, and its steps with their transitions as a readable list. */
class WorkflowViewModel(private val route: WorkflowRoute, private val get: GetWorkflowUseCase) : ViewModel() {
    private val state = MutableStateFlow(WorkflowUiState())
    val uiState: StateFlow<WorkflowUiState> = state.asStateFlow()

    init { reload() }

    fun reload() {
        state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            try {
                val workflow = get(route.accountId, route.workflowId)
                state.update { it.copy(workflow = workflow, loading = false, missing = false) }
            } catch (error: CancellationException) {
                throw error
            } catch (error: CompanionException.NotFound) {
                state.update { it.copy(loading = false, missing = true) }
            } catch (error: Exception) {
                state.update { it.copy(loading = false, error = error.userMessage()) }
            }
        }
    }
}

data class WorkflowStartUiState(
    val workflow: Workflow? = null,
    val loading: Boolean = true,
    val missing: Boolean = false,
    val error: Failure? = null,
    /** What was typed, by input id; an input not typed shows its default. */
    val inputs: Map<String, String> = emptyMap(),
    val profiles: List<Profile> = emptyList(),
    val loadingProfiles: Boolean = true,
    val profileId: String? = null,
    val starting: Boolean = false,
    /** The failure of the start, apart from the form's load. */
    val startError: Failure? = null,
    /** The run that was started: the screen moves to it. */
    val started: WorkflowRun? = null,
) {
    fun valueOf(inputId: String): String = inputs[inputId] ?: workflow?.inputs?.firstOrNull { it.id == inputId }?.default.orEmpty()
    val needsProfile: Boolean get() = workflow?.needsProfile == true
    val canStart: Boolean get() = workflow != null && !starting && started == null && (!needsProfile || (!loadingProfiles && profileId != null))
}

/**
 * The form that starts a run: a field for each input of the workflow (label, required, default, maximum length) and, when
 * an agent step names no profile, the profile picker of the new-session form. The idempotency key lives as long as the
 * form is unchanged, so a retry after a network failure returns the run the first attempt started.
 */
class WorkflowStartViewModel(
    private val route: WorkflowStartRoute,
    private val get: GetWorkflowUseCase,
    private val profiles: LoadProfilesUseCase,
    private val startRun: StartWorkflowRunUseCase,
    private val ids: IdGenerator,
) : ViewModel() {
    private val state = MutableStateFlow(WorkflowStartUiState())
    val uiState: StateFlow<WorkflowStartUiState> = state.asStateFlow()
    private var key = ids.newId()

    init { reload() }

    fun reload() {
        state.update { it.copy(loading = true, error = null, loadingProfiles = true) }
        viewModelScope.launch {
            try {
                val workflow = get(route.accountId, route.workflowId)
                state.update { it.copy(workflow = workflow, loading = false, missing = false) }
                if (workflow.needsProfile) loadProfiles() else state.update { it.copy(loadingProfiles = false) }
            } catch (error: CancellationException) {
                throw error
            } catch (error: CompanionException.NotFound) {
                state.update { it.copy(loading = false, missing = true, loadingProfiles = false) }
            } catch (error: Exception) {
                state.update { it.copy(loading = false, loadingProfiles = false, error = error.userMessage()) }
            }
        }
    }

    private suspend fun loadProfiles() {
        try {
            val options = profiles(route.accountId)
            state.update { it.copy(profiles = options.profiles, profileId = it.profileId ?: options.profiles.singleOrNull()?.id, loadingProfiles = false) }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            state.update { it.copy(loadingProfiles = false, error = error.userMessage()) }
        }
    }

    fun onInput(id: String, value: String) { key = ids.newId(); state.update { it.copy(inputs = it.inputs + (id to value), startError = null) } }
    fun onProfile(id: String) { key = ids.newId(); state.update { it.copy(profileId = id, startError = null) } }

    fun start() {
        val current = state.value
        val workflow = current.workflow ?: return
        if (!current.canStart) return
        state.update { it.copy(starting = true, startError = null) }
        viewModelScope.launch {
            try {
                val run = startRun(route.accountId, workflow, current.inputs, current.profileId, key)
                state.update { it.copy(starting = false, started = run) }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                state.update { it.copy(starting = false, startError = error.userMessage()) }
            }
        }
    }
}
