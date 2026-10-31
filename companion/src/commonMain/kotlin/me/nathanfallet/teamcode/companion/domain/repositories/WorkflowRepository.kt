package me.nathanfallet.teamcode.companion.domain.repositories

import me.nathanfallet.teamcode.companion.domain.models.EditableWorkflow
import me.nathanfallet.teamcode.companion.domain.models.RunFilter
import me.nathanfallet.teamcode.companion.domain.models.RunScope
import me.nathanfallet.teamcode.companion.domain.models.RunStart
import me.nathanfallet.teamcode.companion.domain.models.Workflow
import me.nathanfallet.teamcode.companion.domain.models.WorkflowDefinition
import me.nathanfallet.teamcode.companion.domain.models.WorkflowEditors
import me.nathanfallet.teamcode.companion.domain.models.WorkflowSettings
import me.nathanfallet.teamcode.companion.domain.models.WorkflowPage
import me.nathanfallet.teamcode.companion.domain.models.WorkflowRun
import me.nathanfallet.teamcode.companion.domain.models.WorkflowRunPage

/** The workflows and their runs on an account's server (`workflows.v1`). Failures are [me.nathanfallet.teamcode.companion.domain.models.CompanionException]s. */
interface WorkflowRepository {
    /** The saved definitions by id; [after] is the previous page's cursor. */
    suspend fun workflows(accountId: String, after: String?): WorkflowPage
    suspend fun workflow(accountId: String, id: String): Workflow
    /** Runs newest first, in [filter]'s status; [before] is the previous page's cursor. A list carries no visits. */
    suspend fun runs(accountId: String, scope: RunScope, filter: RunFilter, before: String?): WorkflowRunPage
    /** A run with its visits (the starter's, or an administrator's). */
    suspend fun run(accountId: String, id: String): WorkflowRun
    /** [idempotencyKey]: a retry with the same key returns the run the first attempt started. */
    suspend fun start(accountId: String, workflowId: String, start: RunStart, idempotencyKey: String): WorkflowRun
    suspend fun cancel(accountId: String, id: String): WorkflowRun

    /** The whole definition, to change it. */
    suspend fun definition(accountId: String, id: String): EditableWorkflow
    suspend fun create(accountId: String, definition: WorkflowDefinition): EditableWorkflow
    /** [expectedVersion] is the version the editor started from: a definition saved elsewhere meanwhile is refused (`409 workflow_conflict`). */
    suspend fun update(accountId: String, id: String, definition: WorkflowDefinition, expectedVersion: Int): EditableWorkflow
    /** `409 workflow_in_use` while another workflow calls it. */
    suspend fun delete(accountId: String, id: String)
    /** Who may edit, and whether the caller may. */
    suspend fun settings(accountId: String): WorkflowSettings
    suspend fun updateEditors(accountId: String, editors: WorkflowEditors): WorkflowSettings
}
