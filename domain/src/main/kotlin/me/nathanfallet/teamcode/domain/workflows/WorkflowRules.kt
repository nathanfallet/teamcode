package me.nathanfallet.teamcode.domain.workflows

import me.nathanfallet.teamcode.domain.team.TeamFailure
import me.nathanfallet.teamcode.domain.waits.WaitDefinition
import me.nathanfallet.teamcode.domain.waits.WaitRules

/** Bounds and pure checks of workflow definitions, run when one is saved (docs/workflows.md). */
object WorkflowRules {
    const val MAX_NAME = 120
    const val MAX_DESCRIPTION = 2_000
    const val MAX_TITLE = 200
    const val MAX_INSTRUCTION = 32_000
    const val MAX_INPUTS = 16
    const val MAX_NODES = 100
    const val MAX_VISITS_PER_NODE = 20
    const val MAX_TOTAL_VISITS = 500
    const val MAX_DEPTH = 5
    const val DELIVERED = "delivered"
    private val identifier = Regex("[A-Za-z][A-Za-z0-9_]{0,63}")
    private val placeholder = Regex("\\{\\{\\s*([^{}]*?)\\s*\\}\\}")
    private val domainName = Regex("([a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?\\.)+[a-z]{2,63}")

    fun invalid(message: String) = TeamFailure("invalid_workflow", 400, message)

    /** `{{inputs.x}}` and `{{nodes.id.output}}` in [text]; anything else between braces is refused at save. */
    fun placeholders(text: String): List<String> = placeholder.findAll(text).map { it.groupValues[1] }.toList()

    /** Everything that needs no store: the sub-workflows' existence and cycles are checked by [checkSubWorkflows]. */
    fun validate(definition: WorkflowDefinition): WorkflowDefinition {
        with(definition) {
            if (name.isBlank() || name.length > MAX_NAME || name.any { it.isISOControl() }) throw invalid("Use a name of 1 to $MAX_NAME characters without control characters.")
            description?.let { if (it.length > MAX_DESCRIPTION) throw invalid("The description is at most $MAX_DESCRIPTION characters.") }
            if (limits.maxNodes !in 1..MAX_NODES || limits.maxVisits !in 1..MAX_TOTAL_VISITS || limits.maxDepth !in 0..MAX_DEPTH)
                throw invalid("Limits: 1 to $MAX_NODES nodes, 1 to $MAX_TOTAL_VISITS visits, a sub-workflow depth of 0 to $MAX_DEPTH.")
            if (nodes.isEmpty() || nodes.size > limits.maxNodes) throw invalid("A workflow has 1 to ${limits.maxNodes} nodes.")
            if (inputs.size > MAX_INPUTS) throw invalid("At most $MAX_INPUTS inputs.")
            inputs.forEach {
                if (!identifier.matches(it.id)) throw invalid("An input id starts with a letter and has at most 64 letters, digits or underscores.")
                if (it.label.isBlank() || it.label.length > MAX_TITLE) throw invalid("Each input has a label of 1 to $MAX_TITLE characters.")
                if (it.maxLength !in 1..32_000) throw invalid("An input holds 1 to 32,000 characters.")
                it.default?.let { value -> if (value.length > it.maxLength) throw invalid("The default of ${it.id} is longer than its maximum.") }
            }
            if (inputs.map { it.id }.toSet().size != inputs.size) throw invalid("Input ids must be unique.")
            if (nodes.map { it.id }.toSet().size != nodes.size) throw invalid("Node ids must be unique.")
            if (start !in nodes.map { it.id }) throw invalid("The start node $start does not exist.")
            // A person is asked about a session (docs/waits.md): there must be one before the first question.
            if (nodes.first { it.id == start }.kind == NodeKind.HUMAN) throw invalid("The start node cannot ask a person: an agent works first, in the session the question is about.")
            val ids = nodes.map { it.id }.toSet(); val inputIds = inputs.map { it.id }.toSet()
            nodes.forEach { node(it, ids, inputIds) }
            val reachable = reachable(start, nodes)
            nodes.firstOrNull { it.id !in reachable }?.let { throw invalid("Node ${it.id} cannot be reached from the start.") }
            if (nodes.none { node -> node.transitions.any { it.to == null } }) throw invalid("No node ends the workflow: give one transition no target.")
        }
        return definition
    }

    private fun node(node: WorkflowNode, ids: Set<String>, inputs: Set<String>) {
        val where = "Node ${node.id}"
        if (!identifier.matches(node.id)) throw invalid("A node id starts with a letter and has at most 64 letters, digits or underscores.")
        if (node.title.isBlank() || node.title.length > MAX_TITLE || node.title.any { it.isISOControl() }) throw invalid("$where needs a title of 1 to $MAX_TITLE characters.")
        node.instruction?.let { if (it.length > MAX_INSTRUCTION) throw invalid("$where: the instruction is at most $MAX_INSTRUCTION characters.") }
        if (node.maxVisits !in 1..MAX_VISITS_PER_NODE) throw invalid("$where: maxVisits is 1 to $MAX_VISITS_PER_NODE.")
        listOfNotNull(node.instruction) .plus(node.arguments.values).plus(listOfNotNull(node.deliverable?.label)).forEach { text ->
            placeholders(text).forEach { name -> checkPlaceholder(where, name, ids, inputs, node.id) }
        }
        val outcomes: Set<String> = when (node.kind) {
            NodeKind.AGENT -> {
                if (node.instruction.isNullOrBlank()) throw invalid("$where: an agent node needs an instruction.")
                val deliverable = node.deliverable ?: throw invalid("$where: an agent node delivers something; set its deliverable.")
                deliverable(where, deliverable)
                if (node.fields.isNotEmpty() || node.actions.isNotEmpty() || node.assignee != null || node.workflowId != null || node.arguments.isNotEmpty())
                    throw invalid("$where: an agent node has no fields, actions, assignee or sub-workflow.")
                setOf(DELIVERED)
            }
            NodeKind.HUMAN -> {
                if (node.deliverable != null || node.profileId != null || node.agent != null || node.model != null || node.workflowId != null || node.arguments.isNotEmpty())
                    throw invalid("$where: a human node only asks a person: title, instruction, fields, actions, assignee.")
                node.assignee?.let { if (!it.ownerOfRun && it.memberId.isNullOrBlank()) throw invalid("$where: name the member to ask.") }
                if (node.assignee?.ownerOfRun == true && node.assignee.memberId != null) throw invalid("$where: an owner assignee has no member id.")
                val asked = try {
                    WaitRules.validate(WaitDefinition("workflow", node.title, node.instruction, null, emptyList(), node.fields, node.actions))
                } catch (failure: TeamFailure) { throw invalid("$where: ${failure.message}") }
                asked.actions.map { it.id }.toSet()
            }
            NodeKind.WORKFLOW -> {
                if (node.workflowId.isNullOrBlank()) throw invalid("$where: a workflow node names the workflow to run.")
                if (node.deliverable != null || node.fields.isNotEmpty() || node.actions.isNotEmpty() || node.assignee != null || node.profileId != null || node.agent != null || node.model != null)
                    throw invalid("$where: a workflow node has a workflow and arguments only.")
                node.arguments.keys.forEach { if (!identifier.matches(it)) throw invalid("$where: argument names are input ids.") }
                setOf(DELIVERED)
            }
        }
        if (node.transitions.map { it.on }.toSet().size != node.transitions.size) throw invalid("$where: two transitions on the same outcome.")
        node.transitions.forEach {
            if (it.on !in outcomes) throw invalid("$where: no outcome ${it.on}; use ${outcomes.sorted().joinToString(", ")}.")
            if (it.to != null && it.to !in ids) throw invalid("$where: the transition on ${it.on} goes to ${it.to}, which does not exist.")
        }
        (outcomes - node.transitions.map { it.on }.toSet()).takeIf { it.isNotEmpty() }?.let {
            throw invalid("$where: say where ${it.sorted().joinToString(", ")} goes (a node, or no target to end the workflow).")
        }
    }

    private fun checkPlaceholder(where: String, name: String, ids: Set<String>, inputs: Set<String>, self: String) {
        val parts = name.split('.')
        val ok = when {
            parts.size == 2 && parts[0] == "inputs" -> parts[1] in inputs
            parts.size == 3 && parts[0] == "nodes" && parts[2] == "output" -> parts[1] in ids && parts[1] != self
            else -> false
        }
        if (!ok) throw invalid("$where: {{$name}} is not known; use {{inputs.<id>}} or {{nodes.<id>.output}} of another node.")
    }

    private fun deliverable(where: String, deliverable: Deliverable) {
        deliverable.label?.let { if (it.isBlank() || it.length > MAX_TITLE) throw invalid("$where: the deliverable label is 1 to $MAX_TITLE characters.") }
        if (deliverable.domain != null && (deliverable.type != DeliverableType.URL || !domainName.matches(deliverable.domain)))
            throw invalid("$where: only a url deliverable has a domain, such as github.com.")
        if (deliverable.schema != null && deliverable.type != DeliverableType.JSON) throw invalid("$where: only a json deliverable has a schema.")
        if (deliverable.type == DeliverableType.JSON && deliverable.schema == null) throw invalid("$where: a json deliverable needs a schema.")
    }

    private fun reachable(start: String, nodes: List<WorkflowNode>): Set<String> {
        val by = nodes.associateBy { it.id }; val seen = mutableSetOf<String>(); val stack = ArrayDeque(listOf(start))
        while (stack.isNotEmpty()) { val id = stack.removeLast(); if (seen.add(id)) by[id]?.transitions?.mapNotNull { it.to }?.let(stack::addAll) }
        return seen
    }

    /**
     * The sub-workflows of [definition] saved as [selfId] (null while creating): each exists in [others], none leads
     * back to [selfId], and the chain is no deeper than the limit. [others] maps a workflow id to its definition.
     */
    fun checkSubWorkflows(selfId: String?, definition: WorkflowDefinition, others: Map<String, WorkflowDefinition>) {
        val called = definition.nodes.mapNotNull { it.workflowId }.toSet()
        called.firstOrNull { it !in others }?.let { throw invalid("The workflow $it does not exist.") }
        if (selfId != null && selfId in called) throw invalid("A workflow cannot call itself.")
        fun depth(id: String, path: List<String>): Int {
            if (selfId != null && id == selfId) throw invalid("Workflow calls loop back to this one (${(path + id).joinToString(" -> ")}).")
            if (id in path) throw invalid("Workflow calls loop (${(path + id).joinToString(" -> ")}).")
            val next = others[id]?.nodes?.mapNotNull { it.workflowId }?.toSet().orEmpty()
            return 1 + (next.maxOfOrNull { depth(it, path + id) } ?: 0)
        }
        val deepest = called.maxOfOrNull { depth(it, emptyList()) } ?: 0
        if (deepest > definition.limits.maxDepth) throw invalid("Sub-workflows are nested $deepest deep; this workflow allows ${definition.limits.maxDepth}.")
        called.forEach { id ->
            val sub = others.getValue(id)
            val required = sub.inputs.filter { it.required && it.default == null }.map { it.id }
            val given = definition.nodes.filter { it.workflowId == id }
            given.forEach { node ->
                (required - node.arguments.keys).firstOrNull()?.let { throw invalid("Node ${node.id}: workflow $id needs the input $it.") }
                node.arguments.keys.firstOrNull { it !in sub.inputs.map { i -> i.id } }?.let { throw invalid("Node ${node.id}: workflow $id has no input $it.") }
            }
        }
    }
}
