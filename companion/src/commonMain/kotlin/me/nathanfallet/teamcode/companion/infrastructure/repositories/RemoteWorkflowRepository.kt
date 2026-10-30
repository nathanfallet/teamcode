package me.nathanfallet.teamcode.companion.infrastructure.repositories

import me.nathanfallet.teamcode.api.models.StartWorkflowRunRequest
import me.nathanfallet.teamcode.api.models.WorkflowNodeView
import me.nathanfallet.teamcode.api.models.WorkflowRunView
import me.nathanfallet.teamcode.api.models.WorkflowView
import me.nathanfallet.teamcode.api.models.WorkflowVisitView
import me.nathanfallet.teamcode.companion.domain.models.RunFilter
import me.nathanfallet.teamcode.companion.domain.models.RunScope
import me.nathanfallet.teamcode.companion.domain.models.RunStart
import me.nathanfallet.teamcode.companion.domain.models.RunStatus
import me.nathanfallet.teamcode.companion.domain.models.RunVisit
import me.nathanfallet.teamcode.companion.domain.models.StepAction
import me.nathanfallet.teamcode.companion.domain.models.StepDeliverable
import me.nathanfallet.teamcode.companion.domain.models.StepKind
import me.nathanfallet.teamcode.companion.domain.models.StepTransition
import me.nathanfallet.teamcode.companion.domain.models.VisitStatus
import me.nathanfallet.teamcode.companion.domain.models.Workflow
import me.nathanfallet.teamcode.companion.domain.models.WorkflowInput
import me.nathanfallet.teamcode.companion.domain.models.WorkflowPage
import me.nathanfallet.teamcode.companion.domain.models.WorkflowRun
import me.nathanfallet.teamcode.companion.domain.models.WorkflowRunPage
import me.nathanfallet.teamcode.companion.domain.models.WorkflowStep
import me.nathanfallet.teamcode.companion.domain.repositories.WorkflowRepository

/** The workflows over the typed client (`/api/v1/workflows`), one request per call. */
class RemoteWorkflowRepository(private val calls: AccountCalls) : WorkflowRepository {
    override suspend fun workflows(accountId: String, after: String?): WorkflowPage = calls.withAccount(accountId) { client ->
        val page = client.workflows(after = after, limit = PAGE)
        WorkflowPage(page.workflows.map { it.domain() }, page.nextAfter)
    }

    override suspend fun workflow(accountId: String, id: String): Workflow = calls.withAccount(accountId, resource = true) { it.workflow(id).domain() }

    override suspend fun runs(accountId: String, scope: RunScope, filter: RunFilter, before: String?): WorkflowRunPage = calls.withAccount(accountId) { client ->
        val page = client.workflowRuns(status = filter.status, before = before, limit = PAGE, all = scope == RunScope.TEAM)
        WorkflowRunPage(page.runs.map { it.domain() }, page.nextBefore)
    }

    override suspend fun run(accountId: String, id: String): WorkflowRun = calls.withAccount(accountId, resource = true) { it.workflowRun(id).domain() }

    override suspend fun start(accountId: String, workflowId: String, start: RunStart, idempotencyKey: String): WorkflowRun =
        calls.withAccount(accountId, resource = true) { it.startWorkflowRun(workflowId, StartWorkflowRunRequest(start.inputs, start.profileId?.takeIf { it.isNotBlank() }), idempotencyKey).domain() }

    override suspend fun cancel(accountId: String, id: String): WorkflowRun = calls.withAccount(accountId, resource = true) { it.cancelWorkflowRun(id).domain() }

    private companion object { const val PAGE = 50 }
}

internal fun WorkflowRunView.domain() = WorkflowRun(
    id = id, workflowId = workflowId, workflowName = workflowName, workflowVersion = workflowVersion, status = RunStatus.of(status), rawStatus = status,
    starterId = startedById, inputs = inputs, sessionId = sessionId, currentNodeId = currentNodeId, currentKind = currentNodeKind?.let(StepKind::of),
    parentRunId = parentRunId, failure = failure, output = output, visits = visits.map { it.domain() }, createdAt = createdAt, updatedAt = updatedAt, finishedAt = finishedAt,
)

internal fun WorkflowVisitView.domain() = RunVisit(
    seq = seq, nodeId = nodeId, title = nodeTitle?.takeIf { it.isNotBlank() } ?: nodeId, kind = StepKind.of(kind), status = VisitStatus.of(status), outcome = outcome,
    output = output, sessionId = sessionId, waitId = waitId, childRunId = childRunId, startedAt = startedAt, finishedAt = finishedAt,
)

internal fun WorkflowView.domain() = Workflow(
    id = id, name = name, description = description?.takeIf { it.isNotBlank() }, version = version, start = start, sessionMode = sessionMode,
    inputs = inputs.map { WorkflowInput(it.id, it.label, it.required, it.default, it.maxLength) }, steps = nodes.map { it.domain() },
    maxVisits = limits.maxVisits, updatedAt = updatedAt,
)

private fun WorkflowNodeView.domain() = WorkflowStep(
    id = id, kind = StepKind.of(kind), rawKind = kind, title = title.ifBlank { id }, instruction = instruction,
    deliverable = deliverable?.let { StepDeliverable(it.type, it.domain) }, profileId = profileId,
    assignee = assignee?.let { if (it.kind == "member") it.memberId ?: "member" else it.kind },
    calls = workflowId, actions = actions.map { StepAction(it.id, it.label) }, transitions = transitions.map { StepTransition(it.on, it.to) }, maxVisits = maxVisits,
)
