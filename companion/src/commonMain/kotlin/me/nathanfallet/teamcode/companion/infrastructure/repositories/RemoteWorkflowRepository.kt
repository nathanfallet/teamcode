package me.nathanfallet.teamcode.companion.infrastructure.repositories

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import me.nathanfallet.teamcode.api.models.StartWorkflowRunRequest
import me.nathanfallet.teamcode.api.models.WaitActionView
import me.nathanfallet.teamcode.api.models.WaitFieldView
import me.nathanfallet.teamcode.api.models.WaitOptionView
import me.nathanfallet.teamcode.api.models.WorkflowAssigneeView
import me.nathanfallet.teamcode.api.models.WorkflowDeliverableView
import me.nathanfallet.teamcode.api.models.WorkflowDraft
import me.nathanfallet.teamcode.api.models.WorkflowEditorsView
import me.nathanfallet.teamcode.api.models.WorkflowInputView
import me.nathanfallet.teamcode.api.models.WorkflowLimitsView
import me.nathanfallet.teamcode.api.models.WorkflowSettingsView
import me.nathanfallet.teamcode.api.models.WorkflowTransitionView
import me.nathanfallet.teamcode.api.models.WorkflowNodeView
import me.nathanfallet.teamcode.api.models.WorkflowRunView
import me.nathanfallet.teamcode.api.models.WorkflowView
import me.nathanfallet.teamcode.api.models.WorkflowVisitView
import me.nathanfallet.teamcode.companion.domain.models.DefinitionAction
import me.nathanfallet.teamcode.companion.domain.models.DefinitionDeliverable
import me.nathanfallet.teamcode.companion.domain.models.DefinitionField
import me.nathanfallet.teamcode.companion.domain.models.DefinitionLimits
import me.nathanfallet.teamcode.companion.domain.models.DefinitionNode
import me.nathanfallet.teamcode.companion.domain.models.DefinitionOption
import me.nathanfallet.teamcode.companion.domain.models.EditableWorkflow
import me.nathanfallet.teamcode.companion.domain.models.EditorMode
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
import me.nathanfallet.teamcode.companion.domain.models.WorkflowDefinition
import me.nathanfallet.teamcode.companion.domain.models.WorkflowEditors
import me.nathanfallet.teamcode.companion.domain.models.WorkflowInput
import me.nathanfallet.teamcode.companion.domain.models.WorkflowSettings
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

    override suspend fun definition(accountId: String, id: String): EditableWorkflow = calls.withAccount(accountId, resource = true) { it.workflow(id).editable() }

    override suspend fun create(accountId: String, definition: WorkflowDefinition): EditableWorkflow =
        calls.withAccount(accountId) { it.createWorkflow(definition.draft()).editable() }

    override suspend fun update(accountId: String, id: String, definition: WorkflowDefinition, expectedVersion: Int): EditableWorkflow =
        calls.withAccount(accountId, resource = true) { it.updateWorkflow(id, definition.draft(), expectedVersion).editable() }

    override suspend fun delete(accountId: String, id: String) {
        calls.withAccount(accountId, resource = true) { it.deleteWorkflow(id) }
    }

    override suspend fun settings(accountId: String): WorkflowSettings = calls.withAccount(accountId) { it.workflowSettings().domain() }

    override suspend fun updateEditors(accountId: String, editors: WorkflowEditors): WorkflowSettings =
        calls.withAccount(accountId) { it.updateWorkflowEditors(WorkflowEditorsView(editors.mode.wire, if (editors.mode == EditorMode.MEMBERS) editors.memberIds else emptyList())).domain() }

    private companion object { const val PAGE = 50 }
}

private fun WorkflowSettingsView.domain() = WorkflowSettings(WorkflowEditors(EditorMode.of(editors.mode), editors.memberIds), canEdit)

private val pretty = Json { prettyPrint = true }

internal fun WorkflowView.editable() = EditableWorkflow(id, version, definition(), updatedAt)

internal fun WorkflowView.definition() = WorkflowDefinition(
    name = name, description = description.orEmpty(), start = start, sessionMode = sessionMode,
    inputs = inputs.map { WorkflowInput(it.id, it.label, it.required, it.default, it.maxLength) },
    nodes = nodes.map { it.definition() }, limits = DefinitionLimits(limits.maxNodes, limits.maxVisits, limits.maxDepth),
)

private fun WorkflowNodeView.definition(): DefinitionNode {
    val stepKind = StepKind.of(kind)
    return DefinitionNode(
        id = id, kind = stepKind, title = title, instruction = instruction.orEmpty(), profileId = profileId, agent = agent, model = model,
        deliverable = deliverable?.let { DefinitionDeliverable(it.type, it.label, it.domain, it.schema?.let { schema -> pretty.encodeToString(JsonElement.serializer(), schema) }) },
        fields = fields.map { f ->
            DefinitionField(f.id, f.type, f.label, f.help, f.required, f.default?.takeIf { it !is JsonNull }?.let { value -> if (value is JsonPrimitive) value.content else value.toString() },
                f.options.map { DefinitionOption(it.value, it.label, it.description) }, f.multiple, f.allowOther, f.maxLength)
        },
        actions = actions.map { DefinitionAction(it.id, it.label, it.requires, it.style) },
        assigneeMemberId = assignee?.takeIf { it.kind == "member" }?.let { it.memberId.orEmpty() },
        workflowId = workflowId, arguments = arguments, transitions = transitions.map { StepTransition(it.on, it.to) }, maxVisits = maxVisits, rawKind = kind,
    )
}

/** What the server is sent: texts that were left empty are absent, a number typed as text is a number, a schema is the JSON it was written as. */
internal fun WorkflowDefinition.draft() = WorkflowDraft(
    name = name.trim(), description = description.trim().ifEmpty { null }, start = start, sessionMode = sessionMode,
    inputs = inputs.map { WorkflowInputView(it.id, it.label, it.required, it.default?.takeIf { d -> d.isNotEmpty() }, it.maxLength) },
    nodes = nodes.map { it.view() }, limits = WorkflowLimitsView(limits.maxNodes, limits.maxVisits, limits.maxDepth),
)

private fun DefinitionNode.view() = WorkflowNodeView(
    id = id, kind = kind.wire.ifEmpty { rawKind }, title = title.trim(), instruction = instruction.takeIf { it.isNotBlank() },
    profileId = profileId?.takeIf { it.isNotBlank() }, agent = agent?.takeIf { it.isNotBlank() }, model = model?.takeIf { it.isNotBlank() },
    deliverable = deliverable?.let { d ->
        WorkflowDeliverableView(d.type, d.label?.takeIf { it.isNotBlank() }, d.domain?.takeIf { it.isNotBlank() && d.type == "url" }, d.schema?.takeIf { it.isNotBlank() && d.type == "json" }?.let { Json.parseToJsonElement(it) })
    },
    fields = fields.map { f ->
        WaitFieldView(f.id, f.type, f.label, f.help?.takeIf { it.isNotBlank() }, f.required, f.default?.let { f.defaultJson(it) }, f.options.map { WaitOptionView(it.value, it.label, it.description?.takeIf { d -> d.isNotBlank() }) }, f.multiple, f.allowOther, f.maxLength)
    },
    actions = actions.map { WaitActionView(it.id, it.label, it.requires, it.style) },
    assignee = if (kind == StepKind.HUMAN) assigneeMemberId?.let { WorkflowAssigneeView("member", it) } else null,
    workflowId = workflowId, arguments = arguments, transitions = transitions.map { WorkflowTransitionView(it.on, it.to) }, maxVisits = maxVisits,
)

/** The JSON of a field's default: the text typed for a number, a yes/no or a list of choices is converted back to what the server stores. */
private fun DefinitionField.defaultJson(text: String): JsonElement = when {
    type == "confirm" -> JsonPrimitive(text == "true")
    type == "number" -> text.toDoubleOrNull()?.let { d -> if (d == d.toLong().toDouble() && !text.contains('.')) JsonPrimitive(d.toLong()) else JsonPrimitive(d) } ?: JsonPrimitive(text)
    type == "choice" && multiple -> try { Json.parseToJsonElement(text).takeIf { it is JsonArray } ?: JsonArray(listOf(JsonPrimitive(text))) } catch (_: Exception) { JsonArray(listOf(JsonPrimitive(text))) }
    else -> JsonPrimitive(text)
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
