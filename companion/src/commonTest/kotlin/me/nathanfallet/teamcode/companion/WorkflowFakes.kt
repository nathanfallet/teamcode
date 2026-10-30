package me.nathanfallet.teamcode.companion

import me.nathanfallet.teamcode.companion.domain.models.*
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
}
