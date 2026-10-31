package me.nathanfallet.teamcode.companion.domain.usecases.workflows

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import me.nathanfallet.teamcode.companion.domain.models.DefinitionAction
import me.nathanfallet.teamcode.companion.domain.models.DefinitionField
import me.nathanfallet.teamcode.companion.domain.models.DefinitionNode
import me.nathanfallet.teamcode.companion.domain.models.StepKind
import me.nathanfallet.teamcode.companion.domain.models.Workflow
import me.nathanfallet.teamcode.companion.domain.models.WorkflowDefinition

/** What is wrong, in the server's own terms (docs/workflows.md, "Checks at save"). The screens word each rule in the member's language. */
enum class ProblemRule {
    NAME, DESCRIPTION, LIMITS, NO_NODES, TOO_MANY_NODES, TOO_MANY_INPUTS,
    INPUT_ID, INPUT_LABEL, INPUT_MAX_LENGTH, INPUT_DEFAULT_LONG, INPUT_DUPLICATE,
    NODE_ID, NODE_DUPLICATE, START_MISSING, START_HUMAN, NOT_REACHABLE, NO_ENDING,
    TITLE, INSTRUCTION_LONG, MAX_VISITS, TEMPLATE_UNKNOWN,
    AGENT_INSTRUCTION, DELIVERABLE_MISSING, DELIVERABLE_LABEL, DELIVERABLE_DOMAIN, DELIVERABLE_SCHEMA_ONLY_JSON, DELIVERABLE_SCHEMA_MISSING, DELIVERABLE_SCHEMA_INVALID,
    ASSIGNEE_MEMBER,
    FIELDS_TOO_MANY, FIELD_ID, FIELD_DUPLICATE, FIELD_LABEL, FIELD_HELP, FIELD_SECRET, FIELD_MAX_LENGTH, FIELD_OPTIONS, FIELD_OPTION_VALUE, FIELD_OPTION_DUPLICATE, FIELD_INFO_VALUE, FIELD_DEFAULT,
    ACTIONS_TOO_MANY, ACTION_ID, ACTION_DUPLICATE, ACTION_LABEL, ACTION_REQUIRES,
    CALLEE_MISSING, CALLEE_UNKNOWN, CALLEE_SELF, CALLEE_LOOP, CALLEE_DEPTH, ARGUMENT_NAME, ARGUMENT_MISSING, ARGUMENT_UNKNOWN,
    TRANSITION_DUPLICATE, TRANSITION_OUTCOME, TRANSITION_TARGET, TRANSITION_MISSING,
}

/** One refusal. [nodeId] is the node it concerns (null for the workflow as a whole); [args] fill the sentence (a name, an id, a number). */
data class WorkflowProblem(val rule: ProblemRule, val nodeId: String? = null, val args: List<String> = emptyList())

/**
 * The checks the server makes at save, run here on the draft: while the member edits, to show each refusal against its node
 * and keep Save off, and again before anything is sent. [saved] are the other saved workflows by id for the sub-workflow
 * checks (existence, call loops, depth, arguments); null when they could not be read, which skips those checks (the server
 * still makes them). [selfId] is the id of the workflow being changed (null while creating).
 */
object WorkflowChecks {
    const val MAX_NAME = 120
    const val MAX_DESCRIPTION = 2_000
    const val MAX_TITLE = 200
    const val MAX_INSTRUCTION = 32_000
    const val MAX_INPUTS = 16
    const val MAX_NODES = 100
    const val MAX_VISITS_PER_NODE = 20
    const val MAX_TOTAL_VISITS = 500
    const val MAX_DEPTH = 5
    const val MAX_INPUT_LENGTH = 32_000
    const val MAX_FIELDS = 32
    const val MAX_ACTIONS = 8
    const val MAX_OPTIONS = 50
    const val MAX_LABEL = 200
    const val MAX_HELP = 2_000
    const val MAX_OPTION_VALUE = 500
    const val MAX_TEXT = 32_000
    val identifier = Regex("[A-Za-z][A-Za-z0-9_]{0,63}")
    private val placeholder = Regex("\\{\\{\\s*([^{}]*?)\\s*\\}\\}")
    private val domainName = Regex("([a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?\\.)+[a-z]{2,63}")
    private val date = Regex("\\d{4}-\\d{2}-\\d{2}")
    private val styles = setOf("primary", "danger", "neutral")
    private val json = Json

    /** `{{inputs.x}}` and `{{nodes.id.output}}` names in [text]; anything else between braces is refused. */
    fun placeholders(text: String): List<String> = placeholder.findAll(text).map { it.groupValues[1] }.toList()

    /** Whether [value] parses as a JSON object, for a deliverable's schema. */
    fun isJsonObject(value: String): Boolean = try { json.parseToJsonElement(value) is JsonObject } catch (_: Exception) { false }

    fun check(definition: WorkflowDefinition, saved: Map<String, Workflow>? = null, selfId: String? = null): List<WorkflowProblem> {
        val problems = mutableListOf<WorkflowProblem>()
        fun add(rule: ProblemRule, node: String? = null, vararg args: String) { problems += WorkflowProblem(rule, node, args.toList()) }
        with(definition) {
            if (name.isBlank() || name.length > MAX_NAME || name.any { it.isISOControl() }) add(ProblemRule.NAME, null, MAX_NAME.toString())
            if (description.length > MAX_DESCRIPTION) add(ProblemRule.DESCRIPTION, null, MAX_DESCRIPTION.toString())
            val limitsOk = limits.maxNodes in 1..MAX_NODES && limits.maxVisits in 1..MAX_TOTAL_VISITS && limits.maxDepth in 0..MAX_DEPTH
            if (!limitsOk) add(ProblemRule.LIMITS, null, MAX_NODES.toString(), MAX_TOTAL_VISITS.toString(), MAX_DEPTH.toString())
            if (nodes.isEmpty()) add(ProblemRule.NO_NODES) else if (limitsOk && nodes.size > limits.maxNodes) add(ProblemRule.TOO_MANY_NODES, null, limits.maxNodes.toString())
            if (inputs.size > MAX_INPUTS) add(ProblemRule.TOO_MANY_INPUTS, null, MAX_INPUTS.toString())
            inputs.forEach { input ->
                if (!identifier.matches(input.id)) add(ProblemRule.INPUT_ID, null, input.id)
                if (input.label.isBlank() || input.label.length > MAX_TITLE) add(ProblemRule.INPUT_LABEL, null, input.id.ifBlank { "?" }, MAX_TITLE.toString())
                if (input.maxLength !in 1..MAX_INPUT_LENGTH) add(ProblemRule.INPUT_MAX_LENGTH, null, input.id.ifBlank { "?" }, MAX_INPUT_LENGTH.toString())
                else if ((input.default?.length ?: 0) > input.maxLength) add(ProblemRule.INPUT_DEFAULT_LONG, null, input.id)
            }
            inputs.groupBy { it.id }.filter { it.value.size > 1 }.keys.forEach { add(ProblemRule.INPUT_DUPLICATE, null, it) }
            nodes.groupBy { it.id }.filter { it.value.size > 1 }.keys.forEach { add(ProblemRule.NODE_DUPLICATE, it, it) }
            val ids = nodes.map { it.id }.toSet(); val inputIds = inputs.map { it.id }.toSet()
            val first = nodes.firstOrNull { it.id == start }
            if (nodes.isNotEmpty()) {
                if (first == null) add(ProblemRule.START_MISSING, null, start)
                else if (first.kind == StepKind.HUMAN) add(ProblemRule.START_HUMAN, first.id)
            }
            nodes.forEach { problems += checkNode(it, ids, inputIds, definition, saved, selfId) }
            if (first != null) {
                val reachable = reachable(start, nodes)
                nodes.filter { it.id !in reachable }.forEach { add(ProblemRule.NOT_REACHABLE, it.id) }
            }
            if (nodes.isNotEmpty() && nodes.none { node -> node.transitions.any { it.to == null } }) add(ProblemRule.NO_ENDING)
        }
        return problems
    }

    /** The graph from [start]: every node id that can be reached. */
    fun reachable(start: String, nodes: List<DefinitionNode>): Set<String> {
        val by = nodes.associateBy { it.id }; val seen = mutableSetOf<String>(); val stack = ArrayDeque(listOf(start))
        while (stack.isNotEmpty()) { val id = stack.removeLast(); if (seen.add(id)) by[id]?.transitions?.mapNotNull { it.to }?.let(stack::addAll) }
        return seen
    }

    private fun checkNode(node: DefinitionNode, ids: Set<String>, inputs: Set<String>, definition: WorkflowDefinition, saved: Map<String, Workflow>?, selfId: String?): List<WorkflowProblem> {
        val problems = mutableListOf<WorkflowProblem>()
        val id = node.id
        val sink = Sink(id, problems)
        fun add(rule: ProblemRule, vararg args: String) = sink.add(rule, *args)
        if (!identifier.matches(id)) add(ProblemRule.NODE_ID, id)
        if (node.title.isBlank() || node.title.length > MAX_TITLE || node.title.any { it.isISOControl() }) add(ProblemRule.TITLE, MAX_TITLE.toString())
        if (node.instruction.length > MAX_INSTRUCTION) add(ProblemRule.INSTRUCTION_LONG, MAX_INSTRUCTION.toString())
        if (node.maxVisits !in 1..MAX_VISITS_PER_NODE) add(ProblemRule.MAX_VISITS, MAX_VISITS_PER_NODE.toString())
        (listOf(node.instruction) + node.arguments.values + listOfNotNull(node.deliverable?.label)).flatMap(::placeholders).distinct().forEach { name ->
            val parts = name.split('.')
            val known = when {
                parts.size == 2 && parts[0] == "inputs" -> parts[1] in inputs
                parts.size == 3 && parts[0] == "nodes" && parts[2] == "output" -> parts[1] in ids && parts[1] != id
                else -> false
            }
            if (!known) add(ProblemRule.TEMPLATE_UNKNOWN, name)
        }
        when (node.kind) {
            StepKind.AGENT -> {
                if (node.instruction.isBlank()) add(ProblemRule.AGENT_INSTRUCTION)
                val deliverable = node.deliverable
                if (deliverable == null) add(ProblemRule.DELIVERABLE_MISSING) else {
                    deliverable.label?.let { if (it.isBlank() || it.length > MAX_TITLE) add(ProblemRule.DELIVERABLE_LABEL, MAX_TITLE.toString()) }
                    if (!deliverable.domain.isNullOrEmpty() && (deliverable.type != "url" || !domainName.matches(deliverable.domain))) add(ProblemRule.DELIVERABLE_DOMAIN)
                    if (!deliverable.schema.isNullOrBlank() && deliverable.type != "json") add(ProblemRule.DELIVERABLE_SCHEMA_ONLY_JSON)
                    if (deliverable.type == "json") {
                        if (deliverable.schema.isNullOrBlank()) add(ProblemRule.DELIVERABLE_SCHEMA_MISSING)
                        else if (!isJsonObject(deliverable.schema)) add(ProblemRule.DELIVERABLE_SCHEMA_INVALID)
                    }
                }
            }
            StepKind.HUMAN -> {
                node.assigneeMemberId?.let { if (it.isBlank()) add(ProblemRule.ASSIGNEE_MEMBER) }
                human(node, sink)
            }
            StepKind.WORKFLOW -> workflow(node, definition, saved, selfId, sink)
            StepKind.UNKNOWN -> Unit
        }
        val outcomes = node.outcomes.toSet()
        node.transitions.groupBy { it.on }.filter { it.value.size > 1 }.keys.forEach { add(ProblemRule.TRANSITION_DUPLICATE, it) }
        node.transitions.forEach {
            if (it.on !in outcomes) add(ProblemRule.TRANSITION_OUTCOME, it.on, outcomes.sorted().joinToString(", "))
            if (it.to != null && it.to !in ids) add(ProblemRule.TRANSITION_TARGET, it.on, it.to)
        }
        (node.outcomes - node.transitions.map { it.on }.toSet()).distinct().forEach { add(ProblemRule.TRANSITION_MISSING, it) }
        return problems
    }

    /** The wait rules of a human step (docs/waits.md): fields, actions, `requires`; secret fields are not available yet. */
    private fun human(node: DefinitionNode, sink: Sink) {
        fun add(rule: ProblemRule, vararg args: String) = sink.add(rule, *args)
        if (node.fields.size > MAX_FIELDS) add(ProblemRule.FIELDS_TOO_MANY, MAX_FIELDS.toString())
        node.fields.forEach { field(it, sink) }
        node.fields.groupBy { it.id }.filter { it.value.size > 1 }.keys.forEach { add(ProblemRule.FIELD_DUPLICATE, it) }
        if (node.actions.size > MAX_ACTIONS) add(ProblemRule.ACTIONS_TOO_MANY, MAX_ACTIONS.toString())
        val fieldIds = node.fields.filter { it.type != "info" }.map { it.id }.toSet()
        node.actions.forEach { action(it, fieldIds, sink) }
        node.actions.groupBy { it.id }.filter { it.value.size > 1 }.keys.forEach { add(ProblemRule.ACTION_DUPLICATE, it) }
    }

    private class Sink(val nodeId: String?, val out: MutableList<WorkflowProblem>) {
        fun add(rule: ProblemRule, vararg args: String) { out += WorkflowProblem(rule, nodeId, args.toList()) }
    }

    private fun plain(value: String, max: Int) = value.isNotBlank() && value.length <= max && value.none { it.isISOControl() }

    private fun field(field: DefinitionField, sink: Sink) {
        fun add(rule: ProblemRule, vararg args: String) = sink.add(rule, *args)
        val name = field.id.ifBlank { "?" }
        if (!identifier.matches(field.id)) add(ProblemRule.FIELD_ID, field.id)
        if (!plain(field.label, MAX_LABEL)) add(ProblemRule.FIELD_LABEL, name, MAX_LABEL.toString())
        if ((field.help?.length ?: 0) > MAX_HELP) add(ProblemRule.FIELD_HELP, name, MAX_HELP.toString())
        if (field.type == "secret") add(ProblemRule.FIELD_SECRET, name)
        field.maxLength?.let { if (field.type != "text" || it !in 1..MAX_TEXT) add(ProblemRule.FIELD_MAX_LENGTH, name, MAX_TEXT.toString()) }
        if (field.type == "choice") {
            if (field.options.isEmpty() || field.options.size > MAX_OPTIONS) add(ProblemRule.FIELD_OPTIONS, name, MAX_OPTIONS.toString())
            if (field.options.any { !plain(it.value, MAX_OPTION_VALUE) || !plain(it.label, MAX_LABEL) }) add(ProblemRule.FIELD_OPTION_VALUE, name)
            if (field.options.map { it.value }.toSet().size != field.options.size) add(ProblemRule.FIELD_OPTION_DUPLICATE, name)
        } else if (field.options.isNotEmpty() || field.multiple || field.allowOther) add(ProblemRule.FIELD_OPTIONS, name, MAX_OPTIONS.toString())
        if (field.type == "info" && (field.required || field.default != null)) add(ProblemRule.FIELD_INFO_VALUE, name)
        field.default?.let { if (!defaultFits(field, it)) add(ProblemRule.FIELD_DEFAULT, name) }
    }

    private fun action(action: DefinitionAction, fieldIds: Set<String>, sink: Sink) {
        fun add(rule: ProblemRule, vararg args: String) = sink.add(rule, *args)
        if (!identifier.matches(action.id)) add(ProblemRule.ACTION_ID, action.id)
        if (!plain(action.label, MAX_LABEL)) add(ProblemRule.ACTION_LABEL, action.id.ifBlank { "?" }, MAX_LABEL.toString())
        action.requires.firstOrNull { it !in fieldIds }?.let { add(ProblemRule.ACTION_REQUIRES, action.id, it) }
        action.style?.let { if (it !in styles) add(ProblemRule.ACTION_LABEL, action.id, MAX_LABEL.toString()) }
    }

    /** Whether the default (the JSON of a value, as text) fits the field, as the server checks it. */
    fun defaultFits(field: DefinitionField, value: String): Boolean = when (field.type) {
        "text" -> value.length <= (field.maxLength ?: 8_000)
        "confirm" -> value == "true" || value == "false"
        "number" -> value.toDoubleOrNull()?.let { it.isFinite() } == true
        "date" -> date.matches(value)
        "choice" -> {
            val chosen = if (field.multiple) try { (json.parseToJsonElement(value) as? JsonArray)?.map { (it as kotlinx.serialization.json.JsonPrimitive).content } } catch (_: Exception) { null } else listOf(value)
            chosen != null && chosen.toSet().size == chosen.size && chosen.all { item -> item in field.options.map { it.value } || (field.allowOther && item.isNotBlank() && item.length <= MAX_OPTION_VALUE) }
        }
        else -> false
    }

    private fun workflow(node: DefinitionNode, definition: WorkflowDefinition, saved: Map<String, Workflow>?, selfId: String?, sink: Sink) {
        fun add(rule: ProblemRule, vararg args: String) = sink.add(rule, *args)
        val callee = node.workflowId
        if (callee.isNullOrBlank()) { add(ProblemRule.CALLEE_MISSING); return }
        node.arguments.keys.forEach { if (!identifier.matches(it)) add(ProblemRule.ARGUMENT_NAME, it) }
        if (saved == null) return
        if (selfId != null && callee == selfId) { add(ProblemRule.CALLEE_SELF); return }
        val sub = saved[callee]
        if (sub == null) { add(ProblemRule.CALLEE_UNKNOWN, callee); return }
        // The chain of calls below the callee: a loop back to this workflow, or on itself, is refused with its path; a chain deeper than the limit too.
        fun depth(id: String, path: List<String>): Int? {
            if ((selfId != null && id == selfId) || id in path) { add(ProblemRule.CALLEE_LOOP, (path + id).joinToString(" -> ")); return null }
            val next = saved[id]?.steps?.mapNotNull { it.calls }?.toSet().orEmpty()
            var deepest = 0
            for (n in next) deepest = maxOf(deepest, depth(n, path + id) ?: return null)
            return 1 + deepest
        }
        val deep = depth(callee, emptyList()) ?: return
        if (deep > definition.limits.maxDepth) add(ProblemRule.CALLEE_DEPTH, deep.toString(), definition.limits.maxDepth.toString())
        val required = sub.inputs.filter { it.required && it.default == null }.map { it.id }
        (required - node.arguments.keys).forEach { add(ProblemRule.ARGUMENT_MISSING, it, sub.name) }
        node.arguments.keys.filter { key -> sub.inputs.none { it.id == key } }.forEach { add(ProblemRule.ARGUMENT_UNKNOWN, it, sub.name) }
    }
}
