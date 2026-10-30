package me.nathanfallet.teamcode.cli.workflows

import kotlin.time.Instant
import me.nathanfallet.teamcode.api.models.*
import me.nathanfallet.teamcode.cli.waits.age

private fun time(millis: Long) = Instant.fromEpochMilliseconds(millis).toString()
private fun oneLine(text: String, max: Int = 90) = text.replace(Regex("\\s+"), " ").trim().let { if (it.length > max) it.take(max - 1) + "…" else it }

/** One row of `workflows list`: id, name, version, nodes, inputs, age. */
internal fun WorkflowView.line(now: Long) = listOf(id, name, "v$version", "${nodes.size} nodes", "${inputs.size} inputs", age(updatedAt, now)).joinToString("\t")

/** `workflows show`: the whole definition, readable. */
internal fun WorkflowView.details(): String = buildList {
    add("$name  [$id]  v$version")
    description?.takeIf { it.isNotBlank() }?.let { add(it) }
    add("Start: $start; session mode: $sessionMode; limits: ${limits.maxNodes} nodes, ${limits.maxVisits} visits, depth ${limits.maxDepth}")
    add("Updated ${time(updatedAt)}" + (updatedById?.let { " by $it" } ?: ""))
    add(""); add("Inputs:")
    if (inputs.isEmpty()) add("  none") else inputs.forEach {
        add("  ${it.id}\t${it.label}" + listOfNotNull(if (it.required) "required" else "optional", it.default?.let { d -> "default \"$d\"" }, "max ${it.maxLength}").joinToString(", ", " (", ")"))
    }
    add(""); add("Nodes:")
    nodes.forEach { n ->
        add("  ${n.id}\t${n.kind}\t${n.title}" + if (n.id == start) "  (start)" else "")
        n.instruction?.takeIf { it.isNotBlank() }?.let { add("    instruction: ${oneLine(it)}") }
        n.deliverable?.let { d -> add("    delivers: ${d.type}" + listOfNotNull(d.label, d.domain?.let { x -> "on $x" }, if (d.schema != null) "matching a schema" else null).joinToString("", transform = { " ($it)" })) }
        if (n.kind == "agent") listOfNotNull(n.profileId?.let { "profile $it" }, n.agent?.let { "agent $it" }, n.model?.let { "model $it" }).takeIf { it.isNotEmpty() }?.let { add("    runs with: ${it.joinToString(", ")}") }
        if (n.kind == "human") {
            add("    asks: " + (n.assignee?.let { a -> if (a.kind == "member") "member ${a.memberId}" else "the starter" } ?: "the starter"))
            n.fields.forEach { f -> add("    field ${f.id}\t${f.type}\t${f.label}" + if (f.required) " (required)" else "") }
            n.actions.forEach { a -> add("    action ${a.id}\t${a.label}") }
        }
        if (n.kind == "workflow") add("    runs workflow ${n.workflowId}" + if (n.arguments.isEmpty()) "" else " with " + n.arguments.entries.joinToString(", ") { "${it.key}=${oneLine(it.value, 40)}" })
        n.transitions.forEach { add("    on ${it.on} -> ${it.to ?: "end"}") }
        if (n.maxVisits != 3) add("    at most ${n.maxVisits} visits")
    }
}.joinToString("\n")

internal fun WorkflowSettingsView.render(): String =
    "Who may create, edit and delete workflows: " + when (editors.mode) {
        "members" -> "these members (${editors.memberIds.joinToString(", ").ifEmpty { "none" }}) and administrators"
        "all" -> "every member"
        else -> "administrators only"
    } + ". You " + (if (canEdit) "can" else "cannot") + " edit."

/** One row of `workflows runs`: id, status, workflow and version, current node, age, failure. */
internal fun WorkflowRunView.line(now: Long) = listOf(id, status, "$workflowName v$workflowVersion", currentNodeId ?: "-", age(createdAt, now),
    failure?.let { "failed: ${failureWords(it)}" } ?: "").joinToString("\t").trimEnd()

/** A failure code in words, with what to do when there is something to do. */
internal fun failureWords(code: String): String = when {
    code == "session_failed" -> "the agent's session failed"
    code == "session_deleted" -> "the agent's session was deleted"
    code == "session_missing" -> "the agent's session could not be found"
    code == "ended_without_delivery" -> "the agent ended its turn without delivering (and without asking a person)"
    code == "profile_missing" -> "no cloud profile was available for an agent step; start it again with --profile"
    code == "starter_unavailable" -> "the member who started the run is no longer active"
    code == "wait_cancelled" -> "the question to a person was withdrawn"
    code == "wait_missing" -> "the question to a person disappeared"
    code.startsWith("max_visits:") -> "the loop came back to step \"${code.substringAfter(':')}\" more often than its limit allows"
    code == "max_total_visits" -> "the run reached the workflow's limit of visits in all"
    code == "max_depth" -> "sub-workflows are nested deeper than the limit"
    code == "workflow_missing" -> "a sub-workflow no longer exists"
    code == "invalid_arguments" -> "the arguments given to a sub-workflow were refused"
    code == "sub_workflow_failed" -> "a sub-workflow failed"
    code == "sub_workflow_cancelled" -> "a sub-workflow was cancelled"
    code == "expired" -> "the run was not finished within two weeks"
    code == "no_transition" -> "a step ended with an outcome that has no transition"
    else -> "($code)"
}

/** `workflows run-show`: status, the step it is on, what it waits for, the failure, then the timeline of visits. */
internal fun WorkflowRunView.details(now: Long): String = buildList {
    add("$workflowName v$workflowVersion  [$id]  $status")
    add("Started ${time(createdAt)} by $startedById" + (finishedAt?.let { "; ended ${time(it)}" } ?: "; ${age(createdAt, now)} ago"))
    sessionId?.let { add("Session: $it") }
    parentRunId?.let { add("Sub-run of: $it") }
    if (inputs.isNotEmpty()) { add("Inputs:"); inputs.forEach { (k, v) -> add("  $k = ${oneLine(v)}") } }
    val active = visits.lastOrNull { it.status == "active" }
    if (status !in TERMINAL) {
        add("Current step: ${currentNodeId ?: active?.nodeId ?: "-"}")
        active?.waitId?.let { add("Blocked on a person's answer: teamcode waits show $it") }
        active?.childRunId?.let { add("Blocked on a sub-run: teamcode workflows run-show $it") }
        if (active != null && active.waitId == null && active.childRunId == null && active.kind == "agent") add("An agent works on it" + (active.sessionId?.let { " (session $it)" } ?: "") + " and ends by delivering.")
    }
    failure?.let { add("Failure: ${failureWords(it)}") }
    output?.let { add(""); add("Output:"); add(it) }
    add(""); add("Visits:")
    if (visits.isEmpty()) add("  none yet") else visits.sortedBy { it.seq }.forEach { v ->
        add("  ${v.seq}\t${v.nodeId}\t${v.kind}\t${v.status}" + listOfNotNull(v.outcome, v.sessionId?.let { "session $it" }, v.waitId?.let { "wait $it" }, v.childRunId?.let { "sub-run $it" },
            v.finishedAt?.let { "${(it - v.startedAt) / 1000}s" }).joinToString(", ", " (", ")").takeIf { v.outcome != null || v.sessionId != null || v.waitId != null || v.childRunId != null || v.finishedAt != null }.orEmpty())
        v.output?.let { add("      ${oneLine(it, 160)}") }
    }
}.joinToString("\n")
