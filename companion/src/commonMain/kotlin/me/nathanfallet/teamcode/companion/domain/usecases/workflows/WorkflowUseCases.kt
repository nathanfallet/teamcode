package me.nathanfallet.teamcode.companion.domain.usecases.workflows

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import me.nathanfallet.teamcode.companion.domain.models.CompanionException
import me.nathanfallet.teamcode.companion.domain.models.InvalidInput
import me.nathanfallet.teamcode.companion.domain.models.LiveUpdate
import me.nathanfallet.teamcode.companion.domain.models.RunFilter
import me.nathanfallet.teamcode.companion.domain.models.RunScope
import me.nathanfallet.teamcode.companion.domain.models.RunStart
import me.nathanfallet.teamcode.companion.domain.models.RunStatus
import me.nathanfallet.teamcode.companion.domain.models.RunUpdate
import me.nathanfallet.teamcode.companion.domain.models.SessionChange
import me.nathanfallet.teamcode.companion.domain.models.Workflow
import me.nathanfallet.teamcode.companion.domain.models.WorkflowPage
import me.nathanfallet.teamcode.companion.domain.models.WorkflowRun
import me.nathanfallet.teamcode.companion.domain.models.WorkflowRunPage
import me.nathanfallet.teamcode.companion.domain.models.requireInput
import me.nathanfallet.teamcode.companion.domain.repositories.AccountRepository
import me.nathanfallet.teamcode.companion.domain.repositories.Features
import me.nathanfallet.teamcode.companion.domain.repositories.FeaturesRepository
import me.nathanfallet.teamcode.companion.domain.repositories.WaitRepository
import me.nathanfallet.teamcode.companion.domain.repositories.WorkflowRepository
import me.nathanfallet.teamcode.companion.domain.models.WaitStatus
import me.nathanfallet.teamcode.companion.domain.usecases.auth.AccountGuard
import me.nathanfallet.teamcode.companion.domain.usecases.sessions.SessionFeed

/** Whether the Workflows destination is offered: a signed-in account on a server that announces `workflows.v1`. */
class WorkflowsAvailabilityUseCase(private val accounts: AccountRepository, private val features: FeaturesRepository) {
    suspend fun available(accountId: String): Boolean {
        val account = accounts.accounts.value.firstOrNull { it.id == accountId } ?: return false
        if (!account.signedIn) return false
        return try {
            Features.WORKFLOWS in features.features(accountId)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            // Unknown for now (offline): hidden rather than offered and refused.
            false
        }
    }
}

/** What the use cases share: the capability check, the account guard and the repository. */
class WorkflowAccess(val repository: WorkflowRepository, val features: FeaturesRepository, val accounts: AccountRepository, val guard: AccountGuard) {
    suspend fun <T> call(accountId: String, call: suspend () -> T): T = guard(accountId) {
        if (Features.WORKFLOWS !in features.features(accountId)) throw CompanionException.Unsupported(Features.WORKFLOWS)
        call()
    }

    fun memberId(accountId: String): String = accounts.accounts.value.firstOrNull { it.id == accountId }?.memberId ?: throw CompanionException.Unauthorized()
    fun isAdministrator(accountId: String): Boolean = accounts.accounts.value.firstOrNull { it.id == accountId }?.isAdministrator == true

    /** Only who started a run, or an administrator, cancels it, and only while it moves. */
    fun canCancel(accountId: String, run: WorkflowRun): Boolean = run.status.active && (run.starterId == memberId(accountId) || isAdministrator(accountId))
}

/** A page of runs, newest first; the team's only for an administrator. */
class LoadWorkflowRunsUseCase(private val access: WorkflowAccess) {
    suspend operator fun invoke(accountId: String, scope: RunScope, filter: RunFilter, before: String? = null): WorkflowRunPage {
        if (scope == RunScope.TEAM && !access.isAdministrator(accountId)) throw CompanionException.Unsupported("team")
        return access.call(accountId) { access.repository.runs(accountId, scope, filter, before) }
    }
}

class GetWorkflowRunUseCase(private val access: WorkflowAccess) {
    suspend operator fun invoke(accountId: String, id: String): WorkflowRun = access.call(accountId) { access.repository.run(accountId, id) }
}

/** Cancels a run: the starter's or an administrator's; the engine ends it on its next pass and withdraws its open wait. */
class CancelWorkflowRunUseCase(private val access: WorkflowAccess) {
    suspend operator fun invoke(accountId: String, run: WorkflowRun): WorkflowRun {
        requireInput(access.canCancel(accountId, run), InvalidInput.Problem.WORKFLOW_NOT_STARTER, message = "Only who started a run, or an administrator, cancels it.")
        return access.call(accountId) { access.repository.cancel(accountId, run.id) }
    }
}

/** A page of the saved workflows, by id. */
class LoadWorkflowsUseCase(private val access: WorkflowAccess) {
    suspend operator fun invoke(accountId: String, after: String? = null): WorkflowPage = access.call(accountId) { access.repository.workflows(accountId, after) }
}

class GetWorkflowUseCase(private val access: WorkflowAccess) {
    suspend operator fun invoke(accountId: String, id: String): Workflow = access.call(accountId) { access.repository.workflow(accountId, id) }
}

/**
 * Checks what is entered before anything is sent (the server checks again and words its refusals by code): a value for
 * each required input (its default counts), none longer than its maximum, a profile when an agent step names none.
 * Returns the inputs to send: trimmed, without the empty optional ones.
 */
fun checkRunStart(workflow: Workflow, entered: Map<String, String>, profileId: String?): RunStart {
    val sent = linkedMapOf<String, String>()
    for (input in workflow.inputs) {
        val value = (entered[input.id] ?: input.default.orEmpty()).trim()
        requireInput(value.isNotEmpty() || !input.required, InvalidInput.Problem.WORKFLOW_INPUT_REQUIRED, message = "Fill in ${input.label}.")
        requireInput(value.length <= input.maxLength, InvalidInput.Problem.WORKFLOW_INPUT_LENGTH, input.maxLength, "${input.label} is up to ${input.maxLength} characters.")
        if (value.isNotEmpty()) sent[input.id] = value
    }
    requireInput(!workflow.needsProfile || !profileId.isNullOrBlank(), InvalidInput.Problem.CHOOSE_PROFILE, message = "Choose a profile.")
    return RunStart(sent, profileId?.takeIf { it.isNotBlank() && workflow.needsProfile })
}

/** Starts a run as the member. The caller keeps [idempotencyKey] while the form is unchanged, so a retry returns the first run. */
class StartWorkflowRunUseCase(private val access: WorkflowAccess) {
    suspend operator fun invoke(accountId: String, workflow: Workflow, entered: Map<String, String>, profileId: String?, idempotencyKey: String): WorkflowRun {
        val start = checkRunStart(workflow, entered, profileId)
        return access.call(accountId) { access.repository.start(accountId, workflow.id, start, idempotencyKey) }
    }
}

/** The member's open waits, to tell which runs wait for them: by wait id (a run's visit names it) and by session. */
data class OpenWaits(val waitIds: Set<String>, val sessionIds: Set<String>) {
    companion object { val NONE = OpenWaits(emptySet(), emptySet()) }
}

/** Reads the first page of the member's open waits; none on a server without waits. */
class LoadOpenWaitsUseCase(private val waits: WaitRepository, private val features: FeaturesRepository, private val guard: AccountGuard) {
    suspend operator fun invoke(accountId: String): OpenWaits = guard(accountId) {
        if (Features.WAITS !in features.features(accountId)) return@guard OpenWaits.NONE
        val open = waits.list(accountId, WaitStatus.OPEN).waits
        OpenWaits(open.mapTo(HashSet()) { it.id }, open.mapNotNullTo(HashSet()) { it.sessionId })
    }
}

/** What a screen of runs does next: load again, apply one run's change, show the connection's state. */
sealed interface RunsSignal {
    data object Reload : RunsSignal
    data class Connection(val reconnecting: Boolean) : RunsSignal
    data class Updated(val update: RunUpdate) : RunsSignal
    /** A wait was asked, answered or withdrawn: the runs blocked on one may now wait for the member, or no longer. */
    data object WaitsChanged : RunsSignal
}

/**
 * Keeps the runs current from the app's one feed ([SessionFeed]): a load when the feed opens (so nothing is missed
 * between loading and following), after a gap, and at each `cloud.workflow.run.updated` event. Without the feed or
 * without `workflows.live.v1` nothing more is followed (never polled): the runs load once and on return to the screen.
 */
class FollowWorkflowRunsUseCase(private val feed: SessionFeed, private val features: FeaturesRepository, private val guard: AccountGuard) {
    operator fun invoke(accountId: String, memberId: String): Flow<RunsSignal> = guard.flow(accountId, flow {
        val announced = features.features(accountId)
        if (Features.EVENTS !in announced) { emit(RunsSignal.Reload); return@flow }
        feed.follow(accountId).collect { update ->
            when (update) {
                is LiveUpdate.Opened -> { emit(RunsSignal.Connection(reconnecting = false)); if (!update.resumed) emit(RunsSignal.Reload) }
                LiveUpdate.Gap -> emit(RunsSignal.Reload)
                is LiveUpdate.Item -> update.value.runUpdate()?.let { emit(RunsSignal.Updated(it)) }
                    ?: if (update.value.type in SessionChange.WAIT_TYPES && update.value.memberId == memberId) emit(RunsSignal.WaitsChanged) else Unit
                is LiveUpdate.Reconnecting -> emit(RunsSignal.Connection(reconnecting = true))
                LiveUpdate.Ended -> Unit
            }
        }
    })

    private fun SessionChange.runUpdate(): RunUpdate? = runId?.takeIf { type == SessionChange.WORKFLOW_RUN }?.let { RunUpdate(it, runWorkflowId, runStatus ?: RunStatus.UNKNOWN, memberId, runParentId) }
}
