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
import me.nathanfallet.teamcode.companion.domain.models.RunFilter
import me.nathanfallet.teamcode.companion.domain.models.RunScope
import me.nathanfallet.teamcode.companion.domain.models.RunStatus
import me.nathanfallet.teamcode.companion.domain.models.RunUpdate
import me.nathanfallet.teamcode.companion.domain.models.StepKind
import me.nathanfallet.teamcode.companion.domain.models.Workflow
import me.nathanfallet.teamcode.companion.domain.models.WorkflowRun
import me.nathanfallet.teamcode.companion.domain.repositories.AccountRepository
import me.nathanfallet.teamcode.companion.domain.usecases.workflows.FollowWorkflowRunsUseCase
import me.nathanfallet.teamcode.companion.domain.usecases.workflows.GetWorkflowRunUseCase
import me.nathanfallet.teamcode.companion.domain.usecases.workflows.LoadOpenWaitsUseCase
import me.nathanfallet.teamcode.companion.domain.usecases.workflows.LoadWorkflowRunsUseCase
import me.nathanfallet.teamcode.companion.domain.usecases.workflows.LoadWorkflowsUseCase
import me.nathanfallet.teamcode.companion.domain.usecases.workflows.OpenWaits
import me.nathanfallet.teamcode.companion.domain.usecases.workflows.RunsSignal
import me.nathanfallet.teamcode.companion.presentation.navigation.WorkflowsRoute

/** The step a run is at, in words the row shows: its title and kind. */
data class RunStep(val title: String, val kind: StepKind)

data class WorkflowRunsUiState(
    val scope: RunScope = RunScope.MINE,
    val filter: RunFilter = RunFilter.ALL,
    /** Null until the server answered. */
    val runs: List<WorkflowRun>? = null,
    val nextBefore: String? = null,
    val loading: Boolean = true,
    val loadingMore: Boolean = false,
    val error: Failure? = null,
    val reconnecting: Boolean = false,
    /** The team's runs are an administrator's filter. */
    val administrator: Boolean = false,
    val memberId: String = "",
    /** The definitions read for the titles of the steps a list does not carry (by workflow id). */
    val definitions: Map<String, Workflow> = emptyMap(),
    val openWaits: OpenWaits = OpenWaits.NONE,
) {
    val empty: Boolean get() = runs?.isEmpty() == true && !loading

    /**
     * The step a listed run is at: from the single read when the row has one, else the step's title in the saved
     * definition (its id when that version no longer has it), with the kind the server gave. Null once the run ended.
     */
    fun stepOf(run: WorkflowRun): RunStep? {
        if (!run.status.active) return null
        run.activeVisit?.let { return RunStep(it.title, it.kind) }
        val id = run.currentNodeId ?: return null
        val step = definitions[run.workflowId]?.step(id)
        return RunStep(step?.title ?: id, run.currentKind?.takeIf { it != StepKind.UNKNOWN } ?: step?.kind ?: StepKind.UNKNOWN)
    }

    /** Whether the run is blocked on a wait that is the member's: its visit names it, or (in a list) its session has one open. */
    fun waitsForMe(run: WorkflowRun): Boolean {
        if (run.status != RunStatus.WAITING) return false
        run.activeVisit?.waitId?.let { return it in openWaits.waitIds }
        return run.sessionId != null && run.sessionId in openWaits.sessionIds
    }

    private fun WorkflowRun.matches() = filter.status == null || status.wire == filter.status
    internal fun accepts(run: WorkflowRun) = run.matches()
}

/**
 * The member's runs (or the team's, for an administrator), newest first, a page at a time, in the filter's status. Kept
 * current by the app's one feed ([FollowWorkflowRunsUseCase]): [start] and [stop] follow the screen's lifecycle. An
 * event reads the one run it names (a primary-key read), never the page again.
 */
class WorkflowRunsViewModel(
    private val route: WorkflowsRoute,
    private val load: LoadWorkflowRunsUseCase,
    private val getRun: GetWorkflowRunUseCase,
    private val loadWorkflows: LoadWorkflowsUseCase,
    private val loadOpenWaits: LoadOpenWaitsUseCase,
    private val follow: FollowWorkflowRunsUseCase,
    accounts: AccountRepository,
) : ViewModel() {
    private val account = accounts.accounts.value.firstOrNull { it.id == route.accountId }
    private val state = MutableStateFlow(WorkflowRunsUiState(administrator = account?.isAdministrator == true, memberId = account?.memberId.orEmpty()))
    val uiState: StateFlow<WorkflowRunsUiState> = state.asStateFlow()
    private var reading: Job? = null
    private var following: Job? = null
    private val refreshing = mutableMapOf<String, Job>()

    fun start() {
        if (following?.isActive == true) return
        following = viewModelScope.launch {
            try {
                follow(route.accountId, state.value.memberId).collect { signal ->
                    when (signal) {
                        RunsSignal.Reload -> reload()
                        is RunsSignal.Connection -> state.update { it.copy(reconnecting = signal.reconnecting) }
                        is RunsSignal.Updated -> apply(signal.update)
                        RunsSignal.WaitsChanged -> refreshWaits()
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                // The feed ended (a refusal, a signed-out account): what is shown stays; the sessions list says why.
                state.update { it.copy(reconnecting = false) }
            }
        }
    }

    fun stop() {
        following?.cancel(); following = null
        refreshing.values.forEach { it.cancel() }; refreshing.clear()
        state.update { it.copy(reconnecting = false) }
    }

    fun setScope(scope: RunScope) {
        if (scope == state.value.scope || (scope == RunScope.TEAM && !state.value.administrator)) return
        state.update { it.copy(scope = scope, runs = null, nextBefore = null, error = null) }
        reload()
    }

    fun setFilter(filter: RunFilter) {
        if (filter == state.value.filter) return
        state.update { it.copy(filter = filter, runs = null, nextBefore = null, error = null) }
        reload()
    }

    /** Reads the first page again; what was shown stays while it loads and after a failure. */
    fun reload() {
        reading?.cancel()
        val (scope, filter) = state.value.scope to state.value.filter
        state.update { it.copy(loading = true, loadingMore = false, error = null) }
        reading = viewModelScope.launch {
            page(scope, filter, before = null)
            refreshWaits()
            loadDefinitions()
        }
    }

    fun loadMore() {
        val before = state.value.nextBefore ?: return
        if (reading?.isActive == true) return
        val (scope, filter) = state.value.scope to state.value.filter
        state.update { it.copy(loadingMore = true, error = null) }
        reading = viewModelScope.launch { page(scope, filter, before) }
    }

    private suspend fun page(scope: RunScope, filter: RunFilter, before: String?) {
        try {
            val page = load(route.accountId, scope, filter, before)
            state.update { current ->
                if (current.scope != scope || current.filter != filter) return@update current
                val known = if (before == null) emptySet() else current.runs.orEmpty().map { it.id }.toSet()
                val rows = if (before == null) page.runs else current.runs.orEmpty() + page.runs.filter { it.id !in known }
                current.copy(runs = rows, nextBefore = page.nextBefore, loading = false, loadingMore = false)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            state.update { it.copy(loading = false, loadingMore = false, error = error.userMessage()) }
        }
    }

    /** The titles of the steps: the saved definitions, one page (a list carries the step's id and kind, not its title). */
    private suspend fun loadDefinitions() {
        try {
            val page = loadWorkflows(route.accountId)
            state.update { it.copy(definitions = page.workflows.associateBy { workflow -> workflow.id }) }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            // The rows then show the step's id.
        }
    }

    private suspend fun refreshWaits() {
        try {
            val open = loadOpenWaits(route.accountId)
            state.update { it.copy(openWaits = open) }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            // Without it nothing says "waiting for you"; the status still says waiting.
        }
    }

    /** One run changed: read it (the newest read of a run wins) and put it in place, or take it out when it left the filter. */
    private fun apply(update: RunUpdate) {
        val current = state.value
        if (current.scope == RunScope.MINE && update.ownerId != current.memberId) return
        if (current.runs == null) return
        refreshing.remove(update.runId)?.cancel()
        refreshing[update.runId] = viewModelScope.launch {
            try {
                val run = getRun(route.accountId, update.runId)
                state.update { now -> now.copy(runs = now.runs?.withRun(run, now), nextBefore = now.nextBefore) }
                if (run.status == RunStatus.WAITING || update.status == RunStatus.WAITING) refreshWaits()
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                // It will come with the next load; a failed read of one run is no reason to show an error over the list.
            }
        }
    }

    private fun List<WorkflowRun>.withRun(run: WorkflowRun, state: WorkflowRunsUiState): List<WorkflowRun> {
        val present = any { it.id == run.id }
        if (!state.accepts(run)) return filterNot { it.id == run.id }
        if (present) return map { if (it.id == run.id) run else it }
        // A new run belongs at its place by creation time, but only inside the pages read: an older one comes with its page.
        val last = lastOrNull()
        if (state.nextBefore != null && last != null && run.createdAt < last.createdAt) return this
        return (this + run).sortedWith(compareByDescending<WorkflowRun> { it.createdAt }.thenByDescending { it.id })
    }
}
