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
import me.nathanfallet.teamcode.companion.domain.models.RunStatus
import me.nathanfallet.teamcode.companion.domain.models.RunUpdate
import me.nathanfallet.teamcode.companion.domain.models.WorkflowRun
import me.nathanfallet.teamcode.companion.domain.repositories.AccountRepository
import me.nathanfallet.teamcode.companion.domain.usecases.workflows.CancelWorkflowRunUseCase
import me.nathanfallet.teamcode.companion.domain.usecases.workflows.FollowWorkflowRunsUseCase
import me.nathanfallet.teamcode.companion.domain.usecases.workflows.GetWorkflowRunUseCase
import me.nathanfallet.teamcode.companion.domain.usecases.workflows.LoadOpenWaitsUseCase
import me.nathanfallet.teamcode.companion.domain.usecases.workflows.OpenWaits
import me.nathanfallet.teamcode.companion.domain.usecases.workflows.RunsSignal
import me.nathanfallet.teamcode.companion.presentation.navigation.WorkflowRunRoute

data class WorkflowRunUiState(
    val run: WorkflowRun? = null,
    val loading: Boolean = true,
    /** The run is gone or not the member's to see (`404`). */
    val missing: Boolean = false,
    val error: Failure? = null,
    val reconnecting: Boolean = false,
    val openWaits: OpenWaits = OpenWaits.NONE,
    val cancelling: Boolean = false,
    /** The failure of the cancellation, apart from the page's. */
    val actionError: Failure? = null,
    val memberId: String = "",
    val administrator: Boolean = false,
) {
    /** Only who started the run, or an administrator, cancels it, while it moves. */
    val canCancel: Boolean get() = run?.let { it.status.active && !cancelling && (it.starterId == memberId || administrator) } == true

    /** Whether the wait the visit is blocked on is the member's to answer. */
    fun waitsForMe(waitId: String?): Boolean = waitId != null && waitId in openWaits.waitIds
}

/**
 * One run with its visits: the timeline, the session and the wait it is blocked on, the failure, and Cancel. Kept current
 * by the app's one feed: a change of this run (or of a sub-workflow run it called) reads it again.
 */
class WorkflowRunViewModel(
    private val route: WorkflowRunRoute,
    private val getRun: GetWorkflowRunUseCase,
    private val cancelRun: CancelWorkflowRunUseCase,
    private val loadOpenWaits: LoadOpenWaitsUseCase,
    private val follow: FollowWorkflowRunsUseCase,
    accounts: AccountRepository,
) : ViewModel() {
    private val account = accounts.accounts.value.firstOrNull { it.id == route.accountId }
    private val state = MutableStateFlow(WorkflowRunUiState(memberId = account?.memberId.orEmpty(), administrator = account?.isAdministrator == true))
    val uiState: StateFlow<WorkflowRunUiState> = state.asStateFlow()
    private var following: Job? = null
    private var reading: Job? = null

    init { reload() }

    fun start() {
        if (following?.isActive == true) return
        following = viewModelScope.launch {
            try {
                follow(route.accountId, state.value.memberId).collect { signal ->
                    when (signal) {
                        // The first load is the init's; the feed's own opening loads again only after a gap or a connection that could not resume.
                        RunsSignal.Reload -> if (state.value.run != null) reload()
                        is RunsSignal.Connection -> state.update { it.copy(reconnecting = signal.reconnecting) }
                        is RunsSignal.Updated -> if (concerns(signal.update)) reload()
                        RunsSignal.WaitsChanged -> refreshWaits()
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                state.update { it.copy(reconnecting = false) }
            }
        }
    }

    fun stop() {
        following?.cancel(); following = null
        state.update { it.copy(reconnecting = false) }
    }

    private fun concerns(update: RunUpdate): Boolean {
        val run = state.value.run ?: return false
        return update.runId == route.runId || update.parentRunId == route.runId || run.visits.any { it.childRunId == update.runId }
    }

    /** Reads the run again; what was shown stays while it loads and after a failure. */
    fun reload() {
        reading?.cancel()
        state.update { it.copy(loading = true, error = null) }
        reading = viewModelScope.launch {
            try {
                val run = getRun(route.accountId, route.runId)
                state.update { it.copy(run = run, loading = false, missing = false, error = null) }
                if (run.status == RunStatus.WAITING) refreshWaits()
            } catch (error: CancellationException) {
                throw error
            } catch (error: CompanionException.NotFound) {
                state.update { it.copy(loading = false, missing = true) }
            } catch (error: Exception) {
                state.update { it.copy(loading = false, error = error.userMessage()) }
            }
        }
    }

    private suspend fun refreshWaits() {
        try {
            val open = loadOpenWaits(route.accountId)
            state.update { it.copy(openWaits = open) }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            // The status still says waiting.
        }
    }

    /** Cancels the run (the screen confirms first); the row takes the server's answer, the engine ends the run on its next pass. */
    fun cancel() {
        val run = state.value.run ?: return
        if (!state.value.canCancel) return
        state.update { it.copy(cancelling = true, actionError = null) }
        viewModelScope.launch {
            try {
                val cancelled = cancelRun(route.accountId, run)
                // The answer carries no visits: keep the timeline, take the status.
                state.update { it.copy(cancelling = false, run = cancelled.copy(visits = run.visits)) }
                reload()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                state.update { it.copy(cancelling = false, actionError = error.userMessage()) }
                // A run that ended meanwhile (`409 run_finished`): show how.
                reload()
            }
        }
    }
}
