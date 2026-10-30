package me.nathanfallet.teamcode.api.models

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/** Wire names of the closed enums of workflows (`workflows.v1`, docs/workflows.md). Clients tolerate an unknown value from a newer server. */
object Workflows {
    const val CAPABILITY = "workflows.v1"
    /** `agent` runs an agent on a prompt until it delivers; `human` asks a person (a [Waits] wait); `workflow` runs another workflow. */
    val nodeKinds = listOf("agent", "human", "workflow")
    /** What an agent node delivers. `artifact` is an artifact id, `url` an https address (optionally within a domain), `text` a string, `json` a value matching a schema. */
    val deliverableTypes = listOf("artifact", "url", "text", "json")
    /** `same`: the next agent node arrives as a message of the same session. `handoff`: a new session starts with the previous deliverables in context. */
    val sessionModes = listOf("same", "handoff")
    /** Who may create, edit and delete workflows: `all` members, `admins`, or the listed `members`. Everyone reads. */
    val editorModes = listOf("all", "admins", "members")
    val assigneeKinds = listOf("owner", "member")
    /** The only transition of an agent node: taken when it delivers. A human node has one per action. */
    const val DELIVERED = "delivered"
    val runStatuses = listOf("running", "waiting", "succeeded", "failed", "cancelled")
}

/** A run input: [id] is used as `{{inputs.id}}`. */
@Serializable
data class WorkflowInputView(val id: String, val label: String, val required: Boolean = true, val default: String? = null, val maxLength: Int = 4_096)

/** What the node must deliver: [type], a [label] for the person reading it, a [domain] for `url`, a [schema] (JSON Schema subset) for `json`. */
@Serializable
data class WorkflowDeliverableView(val type: String, val label: String? = null, val domain: String? = null, val schema: JsonElement? = null)

/** [on] is the outcome (`delivered`, or a human action id); [to] the next node, or null to end the workflow. */
@Serializable
data class WorkflowTransitionView(val on: String, val to: String? = null)

/** Who a human node asks: the run's starter (`owner`) or a given member. */
@Serializable
data class WorkflowAssigneeView(val kind: String = "owner", val memberId: String? = null)

/**
 * One step. `agent`: [instruction] (templates `{{inputs.x}}`, `{{nodes.id.output}}`), optional [profileId], [agent],
 * [model] and one [deliverable]. `human`: [instruction] is the wait's body; [title], [fields], [actions], [assignee].
 * `workflow`: [workflowId] and [arguments] (input id to template). [maxVisits] bounds a loop back to this node.
 */
@Serializable
data class WorkflowNodeView(
    val id: String,
    val kind: String,
    val title: String,
    val instruction: String? = null,
    val profileId: String? = null,
    val agent: String? = null,
    val model: String? = null,
    val deliverable: WorkflowDeliverableView? = null,
    val fields: List<WaitFieldView> = emptyList(),
    val actions: List<WaitActionView> = emptyList(),
    val assignee: WorkflowAssigneeView? = null,
    val workflowId: String? = null,
    val arguments: Map<String, String> = emptyMap(),
    val transitions: List<WorkflowTransitionView> = emptyList(),
    val maxVisits: Int = 3,
)

/** Bounds of a run: nodes per definition, visits in all, sub-workflow depth. */
@Serializable
data class WorkflowLimitsView(val maxNodes: Int = 50, val maxVisits: Int = 100, val maxDepth: Int = 3)

/** A workflow definition; [version] grows with every save, runs keep the version they started with. */
@Serializable
data class WorkflowView(
    val id: String,
    val name: String,
    val description: String? = null,
    val version: Int,
    val start: String,
    val sessionMode: String = "same",
    val inputs: List<WorkflowInputView> = emptyList(),
    val nodes: List<WorkflowNodeView>,
    val limits: WorkflowLimitsView = WorkflowLimitsView(),
    val createdAt: Long,
    val updatedAt: Long,
    val updatedById: String? = null,
)

@Serializable
data class WorkflowDraft(
    val name: String,
    val description: String? = null,
    val start: String,
    val sessionMode: String = "same",
    val inputs: List<WorkflowInputView> = emptyList(),
    val nodes: List<WorkflowNodeView>,
    val limits: WorkflowLimitsView = WorkflowLimitsView(),
)

@Serializable
data class SaveWorkflowRequest(val workflow: WorkflowDraft, val expectedVersion: Int? = null)

@Serializable
data class WorkflowPageView(val workflows: List<WorkflowView>, val nextAfter: String? = null)

/** Who may edit; [memberIds] only for `members`. */
@Serializable
data class WorkflowEditorsView(val mode: String = "admins", val memberIds: List<String> = emptyList())

@Serializable
data class WorkflowSettingsView(val editors: WorkflowEditorsView, val canEdit: Boolean = false)

/**
 * One pass through a node of a run. [status] `active`, `done`, `failed` or `cancelled`; [outcome] is what the node ended
 * with (`delivered`, or the human action id); [output] is what it delivered or what the person answered. [sessionId],
 * [waitId] and [childRunId] point at the session an agent works in, the wait a person answers, and the run of a sub-workflow.
 */
@Serializable
data class WorkflowVisitView(
    val seq: Int,
    val nodeId: String,
    val kind: String,
    val status: String,
    val outcome: String? = null,
    val output: String? = null,
    val sessionId: String? = null,
    val waitId: String? = null,
    val childRunId: String? = null,
    val startedAt: Long,
    val finishedAt: Long? = null,
)

/**
 * A run of a workflow, on the version it started with. [status] is one of [Workflows.runStatuses]; [failure] is a short
 * code when it failed. [output] is what the last node delivered. [visits] is filled on a single read, not in lists.
 */
@Serializable
data class WorkflowRunView(
    val id: String,
    val workflowId: String,
    val workflowName: String,
    val workflowVersion: Int,
    val status: String,
    val startedById: String,
    val inputs: Map<String, String> = emptyMap(),
    val sessionId: String? = null,
    val currentNodeId: String? = null,
    val parentRunId: String? = null,
    val failure: String? = null,
    val output: String? = null,
    val visits: List<WorkflowVisitView> = emptyList(),
    val createdAt: Long,
    val updatedAt: Long,
    val finishedAt: Long? = null,
)

/** [inputs] by input id; [profileId] is the cloud profile of agent nodes that name none. */
@Serializable
data class StartWorkflowRunRequest(val inputs: Map<String, String> = emptyMap(), val profileId: String? = null)

/** What an agent node delivers: a string for `artifact`, `url` and `text`, a JSON value for `json`. */
@Serializable
data class DeliverRequest(val output: JsonElement)

/** The same, by the cloud session the step runs in: [sessionId] is named by the run's starter and ignored for an execution credential, which is confined to its own session. */
@Serializable
data class DeliverSessionRequest(val sessionId: String? = null, val output: JsonElement)

/** What `GET /workflows/session/pending` answers: [needsAction] is true while the session's agent step has been neither delivered nor asked about. The rest names the step for the Stop hook's reminder. */
@Serializable
data class WorkflowSessionPendingView(val needsAction: Boolean, val runId: String? = null, val nodeTitle: String? = null, val deliverable: WorkflowPendingDeliverableView? = null)

/** The kind of deliverable the step expects, without the schema. */
@Serializable
data class WorkflowPendingDeliverableView(val type: String, val domain: String? = null)

@Serializable
data class WorkflowRunPageView(val runs: List<WorkflowRunView>, val nextBefore: String? = null)
