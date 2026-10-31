package me.nathanfallet.teamcode.companion.domain.usecases.workflows

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import me.nathanfallet.teamcode.companion.domain.models.CompanionException
import me.nathanfallet.teamcode.companion.domain.models.EditableWorkflow
import me.nathanfallet.teamcode.companion.domain.models.StepKind
import me.nathanfallet.teamcode.companion.domain.models.Workflow
import me.nathanfallet.teamcode.companion.domain.models.WorkflowDefinition
import me.nathanfallet.teamcode.companion.domain.models.WorkflowEditors
import me.nathanfallet.teamcode.companion.domain.models.WorkflowSettings

/** The draft breaks rules the server would refuse: nothing was sent. [problems] are what to fix. */
class InvalidWorkflow(val problems: List<WorkflowProblem>) : IllegalArgumentException("The workflow breaks ${problems.size} rule(s); fix them to save.")

/** A saved definition changed (created, replaced, deleted): the lists that show definitions read again. There is no feed event for definitions, so this is how they follow the member's own changes. */
class WorkflowChanges {
    private val flow = MutableSharedFlow<Unit>(extraBufferCapacity = 8)
    val changes: SharedFlow<Unit> = flow.asSharedFlow()
    fun changed() { flow.tryEmit(Unit) }
}

/** A saved definition, whole, to change it. A kind a newer server added is not edited here (it would be saved as another kind). */
class LoadWorkflowDefinitionUseCase(private val access: WorkflowAccess) {
    suspend operator fun invoke(accountId: String, id: String): EditableWorkflow {
        val loaded = access.call(accountId) { access.repository.definition(accountId, id) }
        if (loaded.definition.nodes.any { it.kind == StepKind.UNKNOWN }) throw CompanionException.Unsupported("workflow.kind")
        return loaded
    }
}

/**
 * Every saved workflow by id, for the callee picker and the sub-workflow checks: a team has at most 200, read [PAGE] at a
 * time (keyset pages), and at most [MAX_PAGES] pages are read.
 */
class LoadWorkflowCatalogUseCase(private val access: WorkflowAccess) {
    suspend operator fun invoke(accountId: String): List<Workflow> = access.call(accountId) {
        val all = mutableListOf<Workflow>()
        var after: String? = null
        var pages = 0
        do {
            val page = access.repository.workflows(accountId, after)
            all += page.workflows
            after = page.nextAfter
        } while (after != null && ++pages < MAX_PAGES)
        all
    }

    companion object { const val MAX_PAGES = 8 }
}

class LoadWorkflowSettingsUseCase(private val access: WorkflowAccess) {
    suspend operator fun invoke(accountId: String): WorkflowSettings = access.call(accountId) { access.repository.settings(accountId) }
}

/** Changes who may edit workflows (an administrator, signed in; the server checks). */
class SaveWorkflowEditorsUseCase(private val access: WorkflowAccess) {
    suspend operator fun invoke(accountId: String, editors: WorkflowEditors): WorkflowSettings = access.call(accountId) { access.repository.updateEditors(accountId, editors) }
}

/**
 * Saves a definition: the checks of the server are made here first ([WorkflowChecks], with the other saved workflows for the
 * sub-workflow rules), so a draft that would be refused is refused with the node it concerns and nothing is sent. A new
 * workflow is created; a changed one replaces [expectedVersion], so a save over someone else's is refused (`409 workflow_conflict`).
 */
class SaveWorkflowUseCase(private val access: WorkflowAccess, private val changes: WorkflowChanges) {
    suspend fun create(accountId: String, definition: WorkflowDefinition, saved: Map<String, Workflow>?): EditableWorkflow {
        check(definition, saved, null)
        return access.call(accountId) { access.repository.create(accountId, definition) }.also { changes.changed() }
    }

    suspend fun update(accountId: String, id: String, definition: WorkflowDefinition, expectedVersion: Int, saved: Map<String, Workflow>?): EditableWorkflow {
        check(definition, saved, id)
        return access.call(accountId) { access.repository.update(accountId, id, definition, expectedVersion) }.also { changes.changed() }
    }

    private fun check(definition: WorkflowDefinition, saved: Map<String, Workflow>?, selfId: String?) {
        val problems = WorkflowChecks.check(definition, saved, selfId)
        if (problems.isNotEmpty()) throw InvalidWorkflow(problems)
    }
}

/** Deletes a definition (runs in flight keep the copy they started with); `409 workflow_in_use` while another workflow calls it. */
class DeleteWorkflowUseCase(private val access: WorkflowAccess, private val changes: WorkflowChanges) {
    suspend operator fun invoke(accountId: String, id: String) {
        access.call(accountId) { access.repository.delete(accountId, id) }
        changes.changed()
    }
}

