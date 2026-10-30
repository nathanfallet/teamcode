package me.nathanfallet.teamcode.presentation

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import me.nathanfallet.teamcode.api.models.*
import me.nathanfallet.teamcode.domain.team.*
import me.nathanfallet.teamcode.domain.workflows.*

private val workflowId = Regex("[a-zA-Z0-9-]{1,36}")
private fun ApplicationCall.workflowId() = parameters["id"]!!.also { if (!workflowId.matches(it)) throw TeamFailure("not_found", 404, "Workflow not found.") }

private fun invalid(message: String) = TeamFailure("invalid_workflow", 400, message)
private fun <T> wire(value: String, parse: (String) -> T?, field: String, allowed: List<String>): T =
    parse(value) ?: throw invalid("Use $field ${allowed.joinToString(", ")}.")

internal fun WorkflowDraft.domain() = WorkflowDefinition(
    name, description, start, wire(sessionMode, SessionMode::parse, "sessionMode", Workflows.sessionModes),
    inputs.map { WorkflowInput(it.id, it.label, it.required, it.default, it.maxLength) },
    nodes.map { n ->
        WorkflowNode(
            n.id, wire(n.kind, NodeKind::parse, "kind", Workflows.nodeKinds), n.title, n.instruction, n.profileId, n.agent, n.model,
            n.deliverable?.let { Deliverable(wire(it.type, DeliverableType::parse, "a deliverable type", Workflows.deliverableTypes), it.label, it.domain, it.schema) },
            n.fields.map { f ->
                me.nathanfallet.teamcode.domain.waits.WaitField(
                    f.id, wire(f.type, me.nathanfallet.teamcode.domain.waits.FieldType::parse, "type", Waits.fieldTypes), f.label, f.help, f.required, f.default,
                    f.options.map { me.nathanfallet.teamcode.domain.waits.WaitOption(it.value, it.label, it.description) }, f.multiple, f.allowOther, f.maxLength,
                )
            },
            n.actions.map { me.nathanfallet.teamcode.domain.waits.WaitAction(it.id, it.label, it.requires, it.style) },
            n.assignee?.let { a ->
                when (a.kind) {
                    "owner" -> NodeAssignee(true, null)
                    "member" -> NodeAssignee(false, a.memberId)
                    else -> throw invalid("Use an assignee kind ${Workflows.assigneeKinds.joinToString(", ")}.")
                }
            },
            n.workflowId, n.arguments, n.transitions.map { Transition(it.on, it.to) }, n.maxVisits,
        )
    },
    WorkflowLimits(limits.maxNodes, limits.maxVisits, limits.maxDepth),
)

internal fun Workflow.view() = WorkflowView(
    id, definition.name, definition.description, version, definition.start, definition.sessionMode.wire,
    definition.inputs.map { WorkflowInputView(it.id, it.label, it.required, it.default, it.maxLength) },
    definition.nodes.map { n ->
        WorkflowNodeView(
            n.id, n.kind.wire, n.title, n.instruction, n.profileId, n.agent, n.model,
            n.deliverable?.let { WorkflowDeliverableView(it.type.wire, it.label, it.domain, it.schema) },
            n.fields.map { f -> WaitFieldView(f.id, f.type.wire, f.label, f.help, f.required, f.default, f.options.map { WaitOptionView(it.value, it.label, it.description) }, f.multiple, f.allowOther, f.maxLength) },
            n.actions.map { WaitActionView(it.id, it.label, it.requires, it.style) },
            n.assignee?.let { WorkflowAssigneeView(if (it.ownerOfRun) "owner" else "member", it.memberId) },
            n.workflowId, n.arguments, n.transitions.map { WorkflowTransitionView(it.on, it.to) }, n.maxVisits,
        )
    },
    WorkflowLimitsView(definition.limits.maxNodes, definition.limits.maxVisits, definition.limits.maxDepth),
    createdAt, updatedAt, updatedById,
)

private fun WorkflowSettings.view() = WorkflowSettingsView(WorkflowEditorsView(editors.mode.wire, editors.memberIds.sorted()), canEdit)

/** A workflow with all its nodes, encoded. */
private const val WORKFLOW_BYTES = 512 * 1024

/** Everyone reads; who writes is the team's rule, which the service enforces (docs/workflows.md). */
internal fun Route.workflowRoutes(workflows: WorkflowService, runs: WorkflowRunService? = null) {
    route("/api/v1/workflows") {
        if (runs != null) workflowRunRoutes(runs)
        get {
            val query = call.request.queryParameters
            val page = workflows.list(call.principal, query["after"]?.also { if (!workflowId.matches(it)) throw TeamFailure("invalid_cursor", 400, "Unknown workflow cursor.") }, query["limit"]?.toIntOrNull() ?: 50)
            call.respond(WorkflowPageView(page.workflows.map { it.view() }, page.nextAfter))
        }
        post { call.respond(io.ktor.http.HttpStatusCode.Created, workflows.create(call.principal, call.jsonBody<SaveWorkflowRequest>(WORKFLOW_BYTES).workflow.domain()).view()) }
        get("/settings") { call.respond(workflows.settings(call.principal).view()) }
        put("/settings") {
            val input = call.jsonBody<WorkflowEditorsView>(64 * 1024)
            val mode = EditorMode.parse(input.mode) ?: throw TeamFailure("invalid_editors", 400, "Use a mode ${Workflows.editorModes.joinToString(", ")}.")
            call.respond(workflows.saveEditors(call.principal, WorkflowEditors(mode, input.memberIds.toSet())).view())
        }
        get("/{id}") { call.respond(workflows.get(call.principal, call.workflowId()).view()) }
        put("/{id}") {
            val input = call.jsonBody<SaveWorkflowRequest>(WORKFLOW_BYTES)
            call.respond(workflows.update(call.principal, call.workflowId(), input.workflow.domain(), input.expectedVersion).view())
        }
        delete("/{id}") { call.respond(workflows.delete(call.principal, call.workflowId()).view()) }
    }
}

internal fun WorkflowRun.view(visits: List<NodeVisit> = emptyList()) = WorkflowRunView(
    id, workflowId, workflowName, workflowVersion, status.wire, starterId, inputs, sessionId, visits.lastOrNull { it.status == VisitStatus.ACTIVE }?.nodeId, parentRunId,
    failure, output, visits.map { it.view() }, createdAt, updatedAt, finishedAt,
)

private fun NodeVisit.view() = WorkflowVisitView(seq, nodeId, kind.wire, status.wire, outcome, output, sessionId, waitId, childRunId, startedAt, finishedAt)

private const val RUN_BYTES = 128 * 1024

/** Runs of workflows: started and cancelled signed in, delivered into by their starter (docs/workflows.md). */
private fun Route.workflowRunRoutes(runs: WorkflowRunService) {
    get("/runs") {
        val query = call.request.queryParameters
        val page = runs.list(call.principal, query["all"] == "true", query["workflow"]?.also { if (!workflowId.matches(it)) throw TeamFailure("not_found", 404, "Workflow not found.") },
            query["status"], query["before"]?.also { if (!workflowId.matches(it)) throw TeamFailure("invalid_cursor", 400, "Unknown run cursor.") }, query["limit"]?.toIntOrNull() ?: 50)
        call.respond(WorkflowRunPageView(page.runs.map { it.view() }, page.nextBefore))
    }
    get("/runs/{id}") {
        val (run, visits) = runs.get(call.principal, call.workflowId())
        call.respond(run.view(visits))
    }
    post("/runs/{id}/cancel") { call.respond(runs.cancel(call.principal, call.workflowId()).view()) }
    post("/runs/{id}/deliver") {
        val input = call.jsonBody<DeliverRequest>(RUN_BYTES)
        call.respond(runs.deliver(call.principal, call.workflowId(), input.output).view())
    }
    post("/{id}/runs") {
        val input = call.jsonBody<StartWorkflowRunRequest>(RUN_BYTES)
        val change = runs.start(call.principal, call.workflowId(), input.inputs, input.profileId, call.idempotencyKey(required = false))
        if (!change.created) call.response.header(CloudHeaders.IDEMPOTENCY_REPLAYED, "true")
        call.respond(if (change.created) io.ktor.http.HttpStatusCode.Created else io.ktor.http.HttpStatusCode.OK, change.run.view())
    }
}
