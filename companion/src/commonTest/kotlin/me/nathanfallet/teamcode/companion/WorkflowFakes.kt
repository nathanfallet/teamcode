package me.nathanfallet.teamcode.companion

import me.nathanfallet.teamcode.companion.domain.models.*
import me.nathanfallet.teamcode.companion.domain.repositories.WorkflowDraftRepository
import me.nathanfallet.teamcode.companion.domain.repositories.WorkflowRepository

fun step(id: String, kind: StepKind = StepKind.AGENT, title: String = "Step $id", profileId: String? = null, vararg transitions: StepTransition) =
    WorkflowStep(id, kind, kind.wire, title, null, null, profileId, null, null, emptyList(), transitions.toList(), 3)

fun workflow(id: String = "wf1", inputs: List<WorkflowInput> = listOf(WorkflowInput("ticket", "Ticket", true, null, 10), WorkflowInput("notes", "Notes", false, "small", 20)), steps: List<WorkflowStep> = listOf(step("work", transitions = arrayOf(StepTransition("delivered", "review"))), step("review", StepKind.HUMAN, "Review"))) =
    Workflow(id, "Fix a ticket", "An agent fixes it.", 2, "work", "same", inputs, steps, 100, 1L)

fun visit(seq: Int, node: String = "work", status: VisitStatus = VisitStatus.ACTIVE, kind: StepKind = StepKind.AGENT, wait: String? = null, child: String? = null, session: String? = null) =
    RunVisit(seq, node, "Title $node", kind, status, null, null, session, wait, child, 1_000L * seq, null)

fun workflowRun(id: String, status: RunStatus = RunStatus.RUNNING, createdAt: Long = 100, starter: String = "m1", visits: List<RunVisit> = emptyList(), session: String? = null, failure: String? = null, current: String? = null) =
    WorkflowRun(id, "wf1", "Fix a ticket", 2, status, status.wire, starter, mapOf("ticket" to "T-1"), session, current, current?.let { StepKind.AGENT }, null, failure, null, visits, createdAt, createdAt, null)

/** Workflows answered from memory; every call is recorded in [calls]. */
class FakeWorkflowRepository : WorkflowRepository {
    val calls = mutableListOf<String>()
    var definitions: List<Workflow> = listOf(workflow())
    /** The runs by id, newest first in a list. */
    val runs = linkedMapOf<String, WorkflowRun>()
    var failure: Exception? = null
    val started = mutableListOf<Triple<String, RunStart, String>>()

    private fun record(call: String) { calls += call; failure?.let { throw it } }

    override suspend fun workflows(accountId: String, after: String?): WorkflowPage { record("workflows:$after"); return WorkflowPage(definitions, null) }
    override suspend fun workflow(accountId: String, id: String): Workflow { record("workflow:$id"); return definitions.firstOrNull { it.id == id } ?: throw CompanionException.NotFound() }
    override suspend fun runs(accountId: String, scope: RunScope, filter: RunFilter, before: String?): WorkflowRunPage {
        record("runs:${scope.name}:${filter.name}:$before")
        val all = runs.values.filter { filter.status == null || it.status.wire == filter.status }.sortedByDescending { it.createdAt }.map { it.copy(visits = emptyList()) }
        return WorkflowRunPage(all, null)
    }
    override suspend fun run(accountId: String, id: String): WorkflowRun { record("run:$id"); return runs[id] ?: throw CompanionException.NotFound() }
    override suspend fun start(accountId: String, workflowId: String, start: RunStart, idempotencyKey: String): WorkflowRun {
        record("start:$workflowId"); started += Triple(workflowId, start, idempotencyKey)
        return workflowRun("new", createdAt = 999).also { runs["new"] = it }
    }
    override suspend fun cancel(accountId: String, id: String): WorkflowRun {
        record("cancel:$id")
        return runs.getValue(id).copy(status = RunStatus.CANCELLED, rawStatus = "cancelled").also { runs[id] = it }
    }

    /** The whole definitions by id (what the editor reads); the list above is what the lists read. */
    val saved = linkedMapOf<String, EditableWorkflow>()
    var settings = WorkflowSettings(WorkflowEditors(EditorMode.ADMINS), canEdit = false)
    /** A failure for one operation (`create`, `update`, `delete`, `settings`, `editors`), thrown after it is recorded. */
    val failures = mutableMapOf<String, Exception>()
    val sentEditors = mutableListOf<WorkflowEditors>()

    private fun record(call: String, operation: String) { record(call); failures[operation]?.let { throw it } }

    override suspend fun definition(accountId: String, id: String): EditableWorkflow { record("definition:$id"); return saved[id] ?: throw CompanionException.NotFound() }
    override suspend fun create(accountId: String, definition: WorkflowDefinition): EditableWorkflow {
        record("create", "create")
        return EditableWorkflow("new-wf", 1, definition, 1L).also { saved[it.id] = it; definitions = definitions + it.toWorkflow() }
    }
    override suspend fun update(accountId: String, id: String, definition: WorkflowDefinition, expectedVersion: Int): EditableWorkflow {
        record("update:$id:$expectedVersion", "update")
        val current = saved[id] ?: throw CompanionException.NotFound()
        if (current.version != expectedVersion) throw CompanionException.Server(409, "workflow_conflict")
        return EditableWorkflow(id, current.version + 1, definition, 2L).also { saved[id] = it; definitions = definitions.map { w -> if (w.id == id) it.toWorkflow() else w } }
    }
    override suspend fun delete(accountId: String, id: String) {
        record("delete:$id", "delete")
        saved.remove(id); definitions = definitions.filterNot { it.id == id }
    }
    override suspend fun settings(accountId: String): WorkflowSettings { record("settings", "settings"); return settings }
    override suspend fun updateEditors(accountId: String, editors: WorkflowEditors): WorkflowSettings {
        record("editors", "editors"); sentEditors += editors
        return WorkflowSettings(editors, settings.canEdit).also { settings = it }
    }
}

/** A step that is valid as an agent step: an instruction, a deliverable and an outcome that ends the workflow. */
fun agentNode(id: String, to: String? = null, title: String = "Step $id", instruction: String = "Do $id.") =
    DefinitionNode(id, StepKind.AGENT, title, instruction, deliverable = DefinitionDeliverable("text"), transitions = listOf(StepTransition("delivered", to)))

/** A valid definition: an agent step, then a person who approves or sends it back. */
fun validDefinition(name: String = "Fix a ticket") = WorkflowDefinition(
    name, "An agent fixes it.", "work", "same", listOf(WorkflowInput("ticket", "Ticket", true, null, 64)),
    listOf(
        agentNode("work", "review", "Work", "Fix {{inputs.ticket}}."),
        DefinitionNode(
            "review", StepKind.HUMAN, "Review", "Look at {{nodes.work.output}}", fields = listOf(DefinitionField("feedback", "text", "Feedback")),
            actions = listOf(DefinitionAction("approve", "Approve"), DefinitionAction("request_changes", "Request changes", listOf("feedback"))),
            transitions = listOf(StepTransition("approve", null), StepTransition("request_changes", "work")),
        ),
    ),
)

fun editable(id: String = "wf1", version: Int = 2, definition: WorkflowDefinition = validDefinition()) = EditableWorkflow(id, version, definition, 1L)

/** The drafts of the editor kept in memory; [saved] counts the writes. */
class InMemoryWorkflowDrafts : WorkflowDraftRepository {
    val stored = linkedMapOf<String, StoredWorkflowDraft>()
    var writes = 0
    override fun load(accountId: String, key: String) = stored["$accountId/$key"]
    override fun save(accountId: String, key: String, draft: StoredWorkflowDraft) { writes++; stored["$accountId/$key"] = draft }
    override fun clear(accountId: String, key: String) { stored.remove("$accountId/$key") }
}
