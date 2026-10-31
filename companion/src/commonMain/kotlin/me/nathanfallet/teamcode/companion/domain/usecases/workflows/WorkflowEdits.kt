package me.nathanfallet.teamcode.companion.domain.usecases.workflows

import me.nathanfallet.teamcode.companion.domain.models.DefinitionAction
import me.nathanfallet.teamcode.companion.domain.models.DefinitionDeliverable
import me.nathanfallet.teamcode.companion.domain.models.DefinitionField
import me.nathanfallet.teamcode.companion.domain.models.DefinitionNode
import me.nathanfallet.teamcode.companion.domain.models.DefinitionOption
import me.nathanfallet.teamcode.companion.domain.models.StepKind
import me.nathanfallet.teamcode.companion.domain.models.StepTransition
import me.nathanfallet.teamcode.companion.domain.models.WorkflowDefinition

/** How the editor changes a definition: pure functions, so each rule that keeps the graph consistent is tested without a screen. */
object WorkflowEdits {
    /** A new workflow: one agent step that delivers some text and ends the workflow, so the first save needs only a name and an instruction. */
    fun blank(): WorkflowDefinition = WorkflowDefinition(
        name = "", start = "work", nodes = listOf(
            DefinitionNode("work", StepKind.AGENT, "", deliverable = DefinitionDeliverable("text"), transitions = listOf(StepTransition(DefinitionNode.DELIVERED, null))),
        ),
    )

    /** A copy to save under a new workflow: the same steps, a name that says so. */
    fun copyOf(definition: WorkflowDefinition, suffix: String): WorkflowDefinition =
        definition.copy(name = (definition.name.trimEnd() + " " + suffix.trim()).trim().take(WorkflowChecks.MAX_NAME))

    /** A node id nobody uses yet: `agent1`, `human2`... */
    fun freshId(definition: WorkflowDefinition, kind: StepKind): String {
        val base = when (kind) { StepKind.AGENT -> "agent"; StepKind.HUMAN -> "human"; StepKind.WORKFLOW -> "workflow"; StepKind.UNKNOWN -> "step" }
        var n = 1
        while (definition.nodes.any { it.id == "$base$n" }) n++
        return "$base$n"
    }

    fun freshInputId(definition: WorkflowDefinition): String {
        var n = 1
        while (definition.inputs.any { it.id == "input$n" }) n++
        return "input$n"
    }

    /** A new, empty step of [kind] at the end of the list; where its outcomes go is for the member to say. */
    fun addNode(definition: WorkflowDefinition, kind: StepKind): WorkflowDefinition {
        val node = reset(DefinitionNode(freshId(definition, kind), kind, ""), kind)
        return definition.copy(nodes = definition.nodes + node, start = definition.start.ifBlank { node.id })
    }

    /**
     * [node] as a step of [kind]: what belongs to another kind is dropped (the server refuses a node that carries it), what the kind
     * needs gets a first value, and the transitions follow the outcomes (those that no longer exist are dropped).
     */
    fun reset(node: DefinitionNode, kind: StepKind): DefinitionNode {
        val base = node.copy(
            kind = kind, rawKind = kind.wire, profileId = null, agent = null, model = null, deliverable = null, fields = emptyList(), actions = emptyList(),
            assigneeMemberId = null, workflowId = null, arguments = emptyMap(),
        )
        val shaped = when (kind) {
            StepKind.AGENT -> base.copy(deliverable = DefinitionDeliverable("text"))
            else -> base
        }
        return follow(shaped)
    }

    fun setKind(definition: WorkflowDefinition, id: String, kind: StepKind): WorkflowDefinition =
        definition.changed(id) { if (it.kind == kind) it else reset(it, kind) }

    /** Keeps only the transitions whose outcome exists, once each, in the order of the outcomes. */
    fun follow(node: DefinitionNode): DefinitionNode {
        val kept = node.outcomes.distinct().mapNotNull { outcome -> node.transitions.firstOrNull { it.on == outcome } }
        return if (kept == node.transitions) node else node.copy(transitions = kept)
    }

    /** Applies [change] to the node [id] and keeps its transitions in step with its outcomes. */
    fun update(definition: WorkflowDefinition, id: String, change: (DefinitionNode) -> DefinitionNode): WorkflowDefinition = definition.changed(id, change)

    private fun WorkflowDefinition.changed(id: String, change: (DefinitionNode) -> DefinitionNode): WorkflowDefinition =
        copy(nodes = nodes.map { if (it.id == id) follow(change(it)) else it })

    /** Where [outcome] of [id] goes: a node id, or null to end the workflow. */
    fun route(definition: WorkflowDefinition, id: String, outcome: String, to: String?): WorkflowDefinition = definition.changed(id) { node ->
        node.copy(transitions = node.transitions.filterNot { it.on == outcome } + StepTransition(outcome, to))
    }

    /** Removes the step and the transitions that led to it (those outcomes are to be routed again); the first remaining step starts if it did. */
    fun removeNode(definition: WorkflowDefinition, id: String): WorkflowDefinition {
        val rest = definition.nodes.filter { it.id != id }.map { node -> node.copy(transitions = node.transitions.filterNot { it.to == id }) }
        return definition.copy(nodes = rest, start = if (definition.start == id) rest.firstOrNull()?.id.orEmpty() else definition.start)
    }

    /** Moves the step [by] places in the list (the order of the list is only how it is shown). */
    fun moveNode(definition: WorkflowDefinition, id: String, by: Int): WorkflowDefinition {
        val from = definition.nodes.indexOfFirst { it.id == id }
        val to = (from + by).coerceIn(0, (definition.nodes.size - 1).coerceAtLeast(0))
        if (from < 0 || from == to) return definition
        val list = definition.nodes.toMutableList()
        list.add(to, list.removeAt(from))
        return definition.copy(nodes = list)
    }

    /**
     * Renames a step id and follows it: the start, every transition into it and the `{{nodes.id.output}}` of the other steps.
     * Nothing is followed when the new id is blank, already used or not an id, so the node shows its problem instead of merging into another.
     */
    fun renameNode(definition: WorkflowDefinition, old: String, new: String): WorkflowDefinition {
        if (old == new) return definition
        val follows = new.isNotBlank() && WorkflowChecks.identifier.matches(new) && definition.nodes.none { it.id == new }
        return definition.copy(
            start = if (follows && definition.start == old) new else definition.start,
            nodes = definition.nodes.map { node ->
                val renamed = if (node.id == old) node.copy(id = new) else node
                if (!follows) renamed else renamed.copy(
                    instruction = rename(renamed.instruction, "nodes.$old.output", "nodes.$new.output"),
                    arguments = renamed.arguments.mapValues { rename(it.value, "nodes.$old.output", "nodes.$new.output") },
                    deliverable = renamed.deliverable?.copy(label = renamed.deliverable.label?.let { rename(it, "nodes.$old.output", "nodes.$new.output") }),
                    transitions = renamed.transitions.map { if (it.to == old) it.copy(to = new) else it },
                )
            },
        )
    }

    /** Renames a run input and follows it in the `{{inputs.id}}` of every step. */
    fun renameInput(definition: WorkflowDefinition, old: String, new: String): WorkflowDefinition {
        val follows = new.isNotBlank() && WorkflowChecks.identifier.matches(new) && definition.inputs.none { it.id == new }
        return definition.copy(
            inputs = definition.inputs.map { if (it.id == old) it.copy(id = new) else it },
            nodes = if (!follows) definition.nodes else definition.nodes.map { node ->
                node.copy(
                    instruction = rename(node.instruction, "inputs.$old", "inputs.$new"),
                    arguments = node.arguments.mapValues { rename(it.value, "inputs.$old", "inputs.$new") },
                    deliverable = node.deliverable?.copy(label = node.deliverable.label?.let { rename(it, "inputs.$old", "inputs.$new") }),
                )
            },
        )
    }

    private fun rename(text: String, old: String, new: String): String =
        Regex("\\{\\{\\s*" + Regex.escape(old) + "\\s*\\}\\}").replace(text) { "{{$new}}" }

    /** Renames a field of a human step and the `requires` of its actions that name it. */
    fun renameField(node: DefinitionNode, old: String, new: String): DefinitionNode = node.copy(
        fields = node.fields.map { if (it.id == old) it.copy(id = new) else it },
        actions = node.actions.map { action -> action.copy(requires = action.requires.map { if (it == old) new else it }) },
    )

    /** Removes a field and the `requires` that named it. */
    fun removeField(node: DefinitionNode, id: String): DefinitionNode = node.copy(
        fields = node.fields.filterNot { it.id == id },
        actions = node.actions.map { action -> action.copy(requires = action.requires.filterNot { it == id }) },
    )

    /** A field of [type] with the shape of that type (a choice starts with one option; an info field takes no value). */
    fun newField(node: DefinitionNode, type: String): DefinitionField {
        var n = 1
        while (node.fields.any { it.id == "field$n" }) n++
        return DefinitionField(
            id = "field$n", type = type, label = "",
            options = if (type == "choice") listOf(DefinitionOption("option1", "")) else emptyList(),
        )
    }

    /** [field] as another type: the options and flags and default that belong to the old one are dropped. */
    fun retype(field: DefinitionField, type: String): DefinitionField {
        if (field.type == type) return field
        return field.copy(
            type = type, default = null, maxLength = null, multiple = false, allowOther = false,
            required = if (type == "info") false else field.required,
            options = if (type == "choice") field.options.ifEmpty { listOf(DefinitionOption("option1", "")) } else emptyList(),
        )
    }

    fun newAction(node: DefinitionNode): DefinitionAction {
        var n = 1
        while (node.actions.any { it.id == "action$n" }) n++
        return DefinitionAction("action$n", "")
    }

    /** Renames an action id (an outcome of the step): its transition follows it. */
    fun renameAction(node: DefinitionNode, old: String, new: String): DefinitionNode = node.copy(
        actions = node.actions.map { if (it.id == old) it.copy(id = new) else it },
        transitions = node.transitions.map { if (it.on == old && node.actions.none { a -> a.id == new }) it.copy(on = new) else it },
    )

    /**
     * The templates a step may use: the run inputs, and the output of each step that can run before it (the steps from which this one can be reached).
     * The step's own output is never offered (the server refuses a step that reads itself).
     */
    fun templates(definition: WorkflowDefinition, nodeId: String): List<String> {
        val before = definition.nodes.filter { it.id != nodeId && nodeId in WorkflowChecks.reachable(it.id, definition.nodes) }
        return definition.inputs.filter { it.id.isNotBlank() }.map { "{{inputs.${it.id}}}" } + before.map { "{{nodes.${it.id}.output}}" }
    }
}

/** How an outcome leaves a step in the map of the flow. */
enum class EdgeKind { NEXT, JOIN, LOOP, END, MISSING, UNSET }

data class FlowEdge(val outcome: String, val kind: EdgeKind, val to: String? = null)
data class FlowRow(val nodeId: String, val depth: Int, val edges: List<FlowEdge>)
data class FlowMap(val rows: List<FlowRow>, val unreachable: List<String>)

/**
 * The flow as a vertical list, from the start: each step once, indented by how deep it is, with what each outcome does: goes on to a
 * step shown below ([EdgeKind.NEXT]), joins one shown elsewhere ([EdgeKind.JOIN]), comes back to a step that led here ([EdgeKind.LOOP]),
 * ends the workflow, points nowhere, or is not routed yet. Steps nothing leads to come last. No canvas: a list reads on a phone.
 */
object FlowMaps {
    fun of(definition: WorkflowDefinition): FlowMap {
        val by = definition.nodes.associateBy { it.id }
        val seen = linkedSetOf<String>(); val onPath = mutableListOf<String>(); val rows = mutableListOf<FlowRow>()
        fun visit(id: String, depth: Int) {
            val node = by[id] ?: return
            seen += id; onPath += id
            val edges = node.outcomes.map { outcome ->
                val transition = node.transition(outcome)
                when {
                    transition == null -> FlowEdge(outcome, EdgeKind.UNSET)
                    transition.to == null -> FlowEdge(outcome, EdgeKind.END)
                    transition.to !in by -> FlowEdge(outcome, EdgeKind.MISSING, transition.to)
                    transition.to in onPath -> FlowEdge(outcome, EdgeKind.LOOP, transition.to)
                    transition.to in seen -> FlowEdge(outcome, EdgeKind.JOIN, transition.to)
                    else -> FlowEdge(outcome, EdgeKind.NEXT, transition.to)
                }
            }
            rows += FlowRow(id, depth, edges)
            // The rows of the steps that follow come right after this one, indented once more.
            node.outcomes.mapNotNull { node.transition(it)?.to }.distinct().forEach { next -> if (next !in seen && next in by) visit(next, depth + 1) }
            onPath.removeAt(onPath.lastIndex)
        }
        by[definition.start]?.let { visit(it.id, 0) }
        return FlowMap(rows, definition.nodes.map { it.id }.filter { it !in seen })
    }
}
