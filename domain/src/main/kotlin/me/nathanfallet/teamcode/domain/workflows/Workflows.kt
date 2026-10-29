package me.nathanfallet.teamcode.domain.workflows

import kotlinx.serialization.json.JsonElement
import me.nathanfallet.teamcode.domain.waits.WaitAction
import me.nathanfallet.teamcode.domain.waits.WaitField

enum class NodeKind(val wire: String) {
    AGENT("agent"), HUMAN("human"), WORKFLOW("workflow");
    companion object { fun parse(value: String) = entries.singleOrNull { it.wire == value } }
}
enum class DeliverableType(val wire: String) {
    ARTIFACT("artifact"), URL("url"), TEXT("text"), JSON("json");
    companion object { fun parse(value: String) = entries.singleOrNull { it.wire == value } }
}
enum class SessionMode(val wire: String) {
    SAME("same"), HANDOFF("handoff");
    companion object { fun parse(value: String) = entries.singleOrNull { it.wire == value } }
}
enum class EditorMode(val wire: String) {
    ALL("all"), ADMINS("admins"), MEMBERS("members");
    companion object { fun parse(value: String) = entries.singleOrNull { it.wire == value } }
}

data class WorkflowInput(val id: String, val label: String, val required: Boolean = true, val default: String? = null, val maxLength: Int = 4_096)
data class Deliverable(val type: DeliverableType, val label: String? = null, val domain: String? = null, val schema: JsonElement? = null)
/** [to] null ends the workflow. */
data class Transition(val on: String, val to: String? = null)
/** `owner` is the member who started the run. */
data class NodeAssignee(val ownerOfRun: Boolean = true, val memberId: String? = null)

data class WorkflowNode(
    val id: String, val kind: NodeKind, val title: String, val instruction: String? = null,
    val profileId: String? = null, val agent: String? = null, val model: String? = null, val deliverable: Deliverable? = null,
    val fields: List<WaitField> = emptyList(), val actions: List<WaitAction> = emptyList(), val assignee: NodeAssignee? = null,
    val workflowId: String? = null, val arguments: Map<String, String> = emptyMap(),
    val transitions: List<Transition> = emptyList(), val maxVisits: Int = 3,
)

data class WorkflowLimits(val maxNodes: Int = 50, val maxVisits: Int = 100, val maxDepth: Int = 3)

/** What a member saves; the store assigns the identity, the version and the times. */
data class WorkflowDefinition(
    val name: String, val description: String?, val start: String, val sessionMode: SessionMode,
    val inputs: List<WorkflowInput>, val nodes: List<WorkflowNode>, val limits: WorkflowLimits = WorkflowLimits(),
)

data class Workflow(
    val id: String, val teamId: String, val version: Int, val definition: WorkflowDefinition,
    val createdAt: Long, val updatedAt: Long, val updatedById: String?,
)

data class WorkflowEditors(val mode: EditorMode = EditorMode.ADMINS, val memberIds: Set<String> = emptySet())
