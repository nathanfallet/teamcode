package me.nathanfallet.teamcode.cli.workflows

import com.github.ajalt.clikt.core.UsageError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.serialization.json.*
import me.nathanfallet.teamcode.api.Serialization
import me.nathanfallet.teamcode.api.models.*
import me.nathanfallet.teamcode.cli.CliUserException
import me.nathanfallet.teamcode.client.TeamCodeClient
import me.nathanfallet.teamcode.client.TeamCodeException

/** Stops with a clear message when the server does not serve workflows (it is older than this CLI). */
internal suspend fun requireWorkflows(client: TeamCodeClient) {
    if (Workflows.CAPABILITY !in client.capabilities().features)
        throw CliUserException("This TeamCode server is too old for workflows: it does not advertise ${Workflows.CAPABILITY}. Ask an administrator to update it.")
}

private val WORKFLOW_ID = Regex("[a-zA-Z0-9-]{1,36}")

/** The identifier arguments the client would reject with an exception: a usage message instead. */
internal fun requireWorkflowId(value: String, what: String = "workflow id"): String {
    if (!WORKFLOW_ID.matches(value)) throw UsageError("\"$value\" is not a valid $what: use the id that teamcode workflows ${if (what == "run id") "runs" else "list"} prints.")
    return value
}

/** Every definition, in id order; bounded because a team has at most 200. */
internal suspend fun listAllWorkflows(client: TeamCodeClient): List<WorkflowView> {
    val all = mutableListOf<WorkflowView>()
    var after: String? = null
    repeat(10) {
        val page = client.workflows(after, 100)
        all += page.workflows
        after = page.nextAfter
        if (after == null) return all
    }
    return all
}

/** Pages of runs, newest first, until [limit] rows; the second value is the cursor of the next page when more exist. */
internal suspend fun listRuns(client: TeamCodeClient, workflow: String?, status: String?, before: String?, limit: Int, all: Boolean): Pair<List<WorkflowRunView>, String?> {
    val runs = mutableListOf<WorkflowRunView>()
    var cursor = before
    do {
        val page = client.workflowRuns(workflow, status, cursor, minOf(100, limit - runs.size), all)
        runs += page.runs
        cursor = page.nextBefore
    } while (cursor != null && runs.size < limit)
    return runs.take(limit) to cursor
}

/**
 * The inputs to send, checked against [workflow]'s declared inputs: an unknown, repeated, too long or missing required
 * input is refused here and named, so nothing reaches the server. An input with a default is left to the server.
 */
internal fun resolveInputs(workflow: WorkflowView, given: List<Pair<String, String>>): Map<String, String> {
    val declared = workflow.inputs.associateBy { it.id }
    val values = linkedMapOf<String, String>()
    given.forEach { (key, value) ->
        val input = declared[key] ?: throw CliUserException(
            "\"${workflow.name}\" has no input \"$key\"." + if (declared.isEmpty()) " It takes no inputs." else " Its inputs: ${declared.keys.joinToString(", ")}.")
        if (key in values) throw CliUserException("Input $key is given twice: pass --input $key=… once.")
        if (value.length > input.maxLength) throw CliUserException("Input $key is ${value.length} characters long; the most it takes is ${input.maxLength}.")
        values[key] = value
    }
    val missing = workflow.inputs.filter { it.required && it.default == null && it.id !in values }
    if (missing.isNotEmpty()) throw CliUserException(
        "\"${workflow.name}\" needs " + missing.joinToString(", ") { "${it.id} (${it.label})" } + ": pass " + missing.joinToString(" ") { "--input ${it.id}=…" } + ".")
    return values
}

private val FORBIDDEN_KEYS = setOf("id", "version", "createdAt", "updatedAt", "updatedById")

/**
 * A definition as the API takes it, from [text]. The output of `workflows show --json` is accepted too (its id,
 * version and timestamps are ignored), and so is a `{"workflow": {...}}` wrapper.
 */
internal fun parseDefinition(text: String): WorkflowDraft {
    val root = try { Json.parseToJsonElement(text) } catch (_: Exception) { throw CliUserException("The definition is not valid JSON.") }
    var obj = root as? JsonObject ?: throw CliUserException("The definition must be a JSON object with name, start and nodes.")
    (obj["workflow"] as? JsonObject)?.let { obj = it }
    val clean = JsonObject(obj.filterKeys { it !in FORBIDDEN_KEYS })
    try { return Serialization.json.decodeFromJsonElement(WorkflowDraft.serializer(), clean) }
    catch (error: CancellationException) { throw error }
    catch (error: Exception) {
        throw CliUserException("The definition is not a workflow: ${error.message?.lineSequence()?.firstOrNull().orEmpty().take(200)}. Required: name, start, nodes (each with id, kind, title).")
    }
}

private val TEMPLATE = Regex("\\{\\{\\s*([^}]*?)\\s*}}")

/**
 * What can be checked without the server, each problem naming its node. The server stays the authority (sub-workflows,
 * limits, wait rules, name uniqueness); this only saves a round trip, and says which node when the server's answer
 * does not.
 */
internal fun checkDefinition(draft: WorkflowDraft): List<String> {
    val problems = mutableListOf<String>()
    if (draft.nodes.isEmpty()) return listOf("The workflow has no nodes.")
    val ids = draft.nodes.map { it.id }
    ids.groupBy { it }.filter { it.value.size > 1 }.keys.forEach { problems += "Node id \"$it\" is used more than once." }
    val byId = draft.nodes.associateBy { it.id }
    val inputs = draft.inputs.map { it.id }.toSet()
    if (draft.start !in byId) problems += "The start node \"${draft.start}\" does not exist."
    else if (byId.getValue(draft.start).kind == "human") problems += "The start node \"${draft.start}\" is a human step: a person is asked about a session, so an agent works first."
    if (draft.sessionMode !in Workflows.sessionModes) problems += "sessionMode is ${Workflows.sessionModes.joinToString(" or ")}, not \"${draft.sessionMode}\"."
    draft.nodes.forEach { node ->
        val at = "Node \"${node.id}\""
        if (node.kind !in Workflows.nodeKinds) { problems += "$at has the kind \"${node.kind}\": use ${Workflows.nodeKinds.joinToString(", ")}."; return@forEach }
        when (node.kind) {
            "agent" -> {
                if (node.instruction.isNullOrBlank()) problems += "$at is an agent step and needs an instruction."
                val deliverable = node.deliverable
                if (deliverable == null) problems += "$at is an agent step and needs a deliverable."
                else {
                    if (deliverable.type !in Workflows.deliverableTypes) problems += "$at: the deliverable type is ${Workflows.deliverableTypes.joinToString(", ")}, not \"${deliverable.type}\"."
                    if (deliverable.domain != null && deliverable.type != "url") problems += "$at: a deliverable domain goes with the type url."
                    if (deliverable.schema != null && deliverable.type != "json") problems += "$at: a deliverable schema goes with the type json."
                }
            }
            "human" -> if (node.instruction.isNullOrBlank() && node.actions.isEmpty() && node.fields.isEmpty()) problems += "$at is a human step and needs an instruction or actions."
            "workflow" -> if (node.workflowId.isNullOrBlank()) problems += "$at runs another workflow and needs workflowId."
        }
        val outcomes = when (node.kind) { "agent", "workflow" -> listOf(Workflows.DELIVERED); else -> node.actions.map { it.id }.ifEmpty { listOf("done") } }
        val taken = node.transitions.map { it.on }
        taken.groupBy { it }.filter { it.value.size > 1 }.keys.forEach { problems += "$at has two transitions for \"$it\"." }
        taken.filter { it !in outcomes }.forEach { problems += "$at has a transition for \"$it\", which is not one of its outcomes (${outcomes.joinToString(", ")})." }
        outcomes.filter { it !in taken }.forEach { problems += "$at has no transition for \"$it\": every outcome needs one (use \"to\": null to end the workflow)." }
        node.transitions.forEach { t -> if (t.to != null && t.to !in byId) problems += "$at goes on \"${t.on}\" to \"${t.to}\", which does not exist." }
        listOfNotNull(node.instruction, node.title.takeIf { node.kind == "human" }).plus(node.arguments.values).forEach { text ->
            TEMPLATE.findAll(text).map { it.groupValues[1] }.forEach { ref ->
                val parts = ref.split('.')
                when {
                    parts.size == 2 && parts[0] == "inputs" -> if (parts[1] !in inputs) problems += "$at uses {{$ref}}, but the workflow declares no input \"${parts[1]}\"."
                    parts.size == 3 && parts[0] == "nodes" && parts[2] == "output" ->
                        if (parts[1] !in byId) problems += "$at uses {{$ref}}, but there is no node \"${parts[1]}\"." else if (parts[1] == node.id) problems += "$at reads its own output ({{$ref}})."
                    else -> problems += "$at uses {{$ref}}: only {{inputs.ID}} and {{nodes.ID.output}} are accepted."
                }
            }
        }
    }
    if (draft.start in byId) {
        val reached = mutableSetOf<String>(); val queue = ArrayDeque(listOf(draft.start))
        while (queue.isNotEmpty()) { val id = queue.removeFirst(); if (reached.add(id)) byId[id]?.transitions?.mapNotNull { it.to }?.let(queue::addAll) }
        draft.nodes.filter { it.id !in reached }.forEach { problems += "Node \"${it.id}\" cannot be reached from the start node." }
    }
    if (draft.nodes.none { n -> n.transitions.any { it.to == null } }) problems += "No node ends the workflow: give one transition \"to\": null."
    return problems
}

/** Stops with every local problem listed; nothing was sent. */
internal fun requireValidDefinition(draft: WorkflowDraft) {
    val problems = checkDefinition(draft)
    if (problems.isNotEmpty()) throw CliUserException("The definition cannot be saved (checked locally, nothing was sent):\n" + problems.joinToString("\n") { "  - $it" })
}

/** What `--wait` follows: the lines to print for what changed since [seen], and the new state of [seen]. */
internal fun changes(seen: MutableMap<Int, String>, previousStatus: String?, run: WorkflowRunView): List<String> = buildList {
    run.visits.sortedBy { it.seq }.forEach { v ->
        val key = v.status + ":" + (v.waitId ?: "") + ":" + (v.outcome ?: "")
        if (seen[v.seq] == key) return@forEach
        if (v.seq !in seen) add("step ${v.seq}: ${v.nodeId} (${v.kind}) started" + (v.sessionId?.let { " in session $it" } ?: "") + (v.childRunId?.let { ", sub-run $it" } ?: ""))
        when (v.status) {
            "active" -> if (v.waitId != null) add("step ${v.seq}: ${v.nodeId} waits for a person: teamcode waits show ${v.waitId}")
            "done" -> add("step ${v.seq}: ${v.nodeId} done" + (v.outcome?.let { " ($it)" } ?: ""))
            else -> add("step ${v.seq}: ${v.nodeId} ${v.status}")
        }
        seen[v.seq] = key
    }
    if (previousStatus != run.status && run.visits.isEmpty()) add("run ${run.status}")
}

internal val TERMINAL = setOf("succeeded", "failed", "cancelled")

internal fun exitCode(status: String): Int = when (status) { "succeeded" -> 0; "failed" -> 1; "cancelled" -> 2; else -> 3 }

internal class Followed(val run: WorkflowRunView, val timedOut: Boolean)

/**
 * Polls run [id] every [intervalMillis] (at least 2 s, enforced by the command) until it ends or [timeoutMillis] of
 * waiting have passed, calling [emit] for each change. A poll that fails for a reason that may pass (network, 5xx) is
 * tried again up to three times in a row; cancellation (Ctrl-C) propagates after [onCancel].
 */
internal suspend fun follow(
    client: TeamCodeClient, id: String, intervalMillis: Long, timeoutMillis: Long, first: WorkflowRunView? = null,
    emit: (String) -> Unit, onCancel: () -> Unit = {}, pause: suspend (Long) -> Unit = { delay(it) },
): Followed {
    val seen = mutableMapOf<Int, String>()
    var status: String? = null
    var waited = 0L
    var failures = 0
    var run = first
    try {
        while (true) {
            if (run == null) {
                run = try { client.workflowRun(id).also { failures = 0 } }
                catch (error: TeamCodeException) {
                    if (error.statusCode in 400..499 && error.statusCode != 408 && error.statusCode != 429 || ++failures > 3) throw error
                    null
                }
            }
            run?.let {
                changes(seen, status, it).forEach(emit)
                status = it.status
                if (it.status in TERMINAL) return Followed(it, false)
            }
            if (waited >= timeoutMillis) return Followed(run ?: client.workflowRun(id), true)
            pause(intervalMillis); waited += intervalMillis
            run = null
        }
    } catch (error: CancellationException) { onCancel(); throw error }
}
