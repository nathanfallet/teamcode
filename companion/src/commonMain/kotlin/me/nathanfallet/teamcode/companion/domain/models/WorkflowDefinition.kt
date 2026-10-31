package me.nathanfallet.teamcode.companion.domain.models

import kotlinx.serialization.Serializable

/**
 * The whole definition of a workflow, as the editor changes it (the read model [Workflow] keeps only what the list and the
 * page show). Everything a node can carry is here, so saving a workflow another tool wrote loses nothing the app knows.
 * Texts are plain strings (an empty one is "not set"); a number that was not typed is 0, which the checks refuse in words.
 */
@Serializable
data class DefinitionOption(val value: String, val label: String, val description: String? = null)

/** A field a human step asks for: the same shape as the fields of a wait. [default] is the JSON of the value as text (`true`, `3`, `["a"]`, or the plain text). */
@Serializable
data class DefinitionField(
    val id: String,
    val type: String,
    val label: String,
    val help: String? = null,
    val required: Boolean = false,
    val default: String? = null,
    val options: List<DefinitionOption> = emptyList(),
    val multiple: Boolean = false,
    val allowOther: Boolean = false,
    val maxLength: Int? = null,
)

/** A button of a human step; [requires] names the fields that must be filled to use it. Each action is an outcome of the step. */
@Serializable
data class DefinitionAction(val id: String, val label: String, val requires: List<String> = emptyList(), val style: String? = null)

/** What an agent step delivers; [schema] is the JSON schema as text (only for `json`), [domain] only for `url`. */
@Serializable
data class DefinitionDeliverable(val type: String, val label: String? = null, val domain: String? = null, val schema: String? = null)

/**
 * One node. [assigneeMemberId] is null for the run's starter (the default), a member id, or "" while a member is still to be chosen.
 * [rawKind] is the wire name when [kind] is [StepKind.UNKNOWN] (a kind a newer server added: such a workflow is not edited here).
 */
@Serializable
data class DefinitionNode(
    val id: String,
    val kind: StepKind,
    val title: String,
    val instruction: String = "",
    val profileId: String? = null,
    val agent: String? = null,
    val model: String? = null,
    val deliverable: DefinitionDeliverable? = null,
    val fields: List<DefinitionField> = emptyList(),
    val actions: List<DefinitionAction> = emptyList(),
    val assigneeMemberId: String? = null,
    val workflowId: String? = null,
    val arguments: Map<String, String> = emptyMap(),
    val transitions: List<StepTransition> = emptyList(),
    val maxVisits: Int = 3,
    val rawKind: String = kind.wire,
) {
    /** What this step can end with: `delivered`, or the id of each action (`done` when a human step declares none). */
    val outcomes: List<String>
        get() = when (kind) {
            StepKind.AGENT, StepKind.WORKFLOW -> listOf(DELIVERED)
            StepKind.HUMAN -> actions.map { it.id }.ifEmpty { listOf(DEFAULT_ACTION) }
            StepKind.UNKNOWN -> transitions.map { it.on }
        }
    fun transition(outcome: String): StepTransition? = transitions.firstOrNull { it.on == outcome }

    companion object {
        const val DELIVERED = "delivered"
        const val DEFAULT_ACTION = "done"
    }
}

@Serializable
data class DefinitionLimits(val maxNodes: Int = 50, val maxVisits: Int = 100, val maxDepth: Int = 3)

@Serializable
data class WorkflowDefinition(
    val name: String,
    val description: String = "",
    val start: String,
    val sessionMode: String = "same",
    val inputs: List<WorkflowInput> = emptyList(),
    val nodes: List<DefinitionNode>,
    val limits: DefinitionLimits = DefinitionLimits(),
) {
    fun node(id: String): DefinitionNode? = nodes.firstOrNull { it.id == id }
}

/** A saved definition to change: [version] is the one the editor started from and sends back as `expectedVersion`. */
data class EditableWorkflow(val id: String, val version: Int, val definition: WorkflowDefinition, val updatedAt: Long)

/** Who may create, change and delete workflows (everyone reads): `all` members, `admins`, or the listed `members`. */
enum class EditorMode(val wire: String) {
    ALL("all"), ADMINS("admins"), MEMBERS("members");
    companion object { fun of(wire: String) = entries.firstOrNull { it.wire == wire } ?: ADMINS }
}

data class WorkflowEditors(val mode: EditorMode, val memberIds: List<String> = emptyList())

/** The rule and whether the caller may edit under it. */
data class WorkflowSettings(val editors: WorkflowEditors, val canEdit: Boolean)

/** The read model of a definition as the list and the page show it. */
fun EditableWorkflow.toWorkflow(): Workflow = Workflow(
    id = id, name = definition.name, description = definition.description.ifBlank { null }, version = version, start = definition.start, sessionMode = definition.sessionMode,
    inputs = definition.inputs,
    steps = definition.nodes.map { node ->
        WorkflowStep(
            id = node.id, kind = node.kind, rawKind = node.rawKind, title = node.title.ifBlank { node.id }, instruction = node.instruction.ifBlank { null },
            deliverable = node.deliverable?.let { StepDeliverable(it.type, it.domain) }, profileId = node.profileId,
            assignee = if (node.kind == StepKind.HUMAN) (node.assigneeMemberId?.ifBlank { null } ?: "owner") else null,
            calls = node.workflowId, actions = node.actions.map { StepAction(it.id, it.label) }, transitions = node.transitions, maxVisits = node.maxVisits,
        )
    },
    maxVisits = definition.limits.maxVisits, updatedAt = updatedAt,
)

/**
 * What the editor keeps on the device while a definition is being changed, so a closed screen, a killed app or a lost
 * connection costs nothing: the draft and the [baseVersion] it started from (null for a new workflow).
 */
@Serializable
data class StoredWorkflowDraft(val baseVersion: Int?, val definition: WorkflowDefinition)
