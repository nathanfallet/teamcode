package me.nathanfallet.teamcode.cli.cloud

import com.github.ajalt.clikt.command.SuspendingCliktCommand
import com.github.ajalt.clikt.command.SuspendingNoOpCliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.*
import com.github.ajalt.clikt.parameters.types.int
import kotlin.time.Instant
import kotlin.uuid.Uuid
import me.nathanfallet.teamcode.api.Serialization
import me.nathanfallet.teamcode.api.models.*
import me.nathanfallet.teamcode.cli.loadConfig
import me.nathanfallet.teamcode.cli.sdk

/** `teamcode cloud`: sessions that run on the team's Kubernetes cluster. */
class Cloud : SuspendingNoOpCliktCommand(name = "cloud") {
    override fun help(context: Context) = "Create and manage cloud sessions on your team's cluster. Requires an interactive login."
    init { subcommands(CloudCreate(), CloudList(), CloudShow(), CloudPause(), CloudResume(), CloudDelete(), CloudRunner()) }
}

private fun CloudSessionView.line() = listOf(id, state + (phase?.let { " ($it)" } ?: ""), name, "epoch $currentEpoch", "updated ${Instant.fromEpochMilliseconds(updatedAt)}").joinToString("\t")
private fun CloudSessionView.details() = buildString {
    appendLine("Session:      $id")
    appendLine("Name:         $name")
    appendLine("State:        $state" + (phase?.let { " ($it)" } ?: "") + ", desired $desiredState")
    appendLine("Owner:        $ownerId" + if (createdBy.initiatorId != ownerId) " (created by ${createdBy.initiatorId})" else "")
    appendLine("Profile:      ${profile.id} v${profile.version}")
    appendLine("Image:        ${image.reference}" + (image.digest?.let { " @ $it" } ?: ""))
    appendLine("Agent:        ${agent.kind}" + listOfNotNull(agent.model, agent.effort).joinToString(" ", prefix = " ").trimEnd())
    appendLine("Epoch:        $currentEpoch")
    appendLine("Resume:       workspace ${resume.workspace}, conversation ${resume.conversation}")
    failure?.let { appendLine("Failure:      ${it.code}: ${it.message}") }
    resumeBlocked?.let { appendLine("Resume block: ${it.code}: ${it.message}") }
    if (workspaceDeletion.state != "none") appendLine("Workspace:    deletion ${workspaceDeletion.state}" + (workspaceDeletion.problem?.let { " (${it.code})" } ?: ""))
    environment.repositories.forEach { appendLine("Repository:   ${it.path} <- ${it.url}" + (it.ref?.let { ref -> " @ $ref" } ?: "") + (it.branch?.let { branch -> " (branch $branch)" } ?: "")) }
    environment.workingDirectory?.let { appendLine("Working dir:  $it") }
    labels.entries.sortedBy { it.key }.forEach { (key, value) -> appendLine("Label:        $key=$value") }
    pausedAt?.let { appendLine("Paused:       ${Instant.fromEpochMilliseconds(it)}") }
    expiresAt?.let { appendLine("Expires:      ${Instant.fromEpochMilliseconds(it)}") }
}.trimEnd()

private fun parseLabels(labels: List<String>) = labels.associate { label ->
    val key = label.substringBefore('=', "")
    require(key.isNotEmpty()) { "Use --label key=value." }
    key to label.substringAfter('=')
}

/** `[PATH=]URL[#REF]`: the path defaults to the repository name. */
internal fun parseRepository(value: String): CloudRepositoryView {
    val path = value.substringBefore('=', "").takeIf { it.isNotEmpty() && !it.contains("://") }
    val rest = if (path != null) value.substringAfter('=') else value
    val url = rest.substringBefore('#')
    val ref = rest.substringAfter('#', "").takeIf { it.isNotEmpty() }
    require(url.startsWith("https://")) { "Use --repo [PATH=]https://host/owner/name[#REF]." }
    val name = url.trimEnd('/').substringAfterLast('/').removeSuffix(".git")
    require(path != null || name.isNotEmpty()) { "Name the directory: --repo PATH=$url." }
    return CloudRepositoryView(url, path ?: name, ref)
}

private class CloudCreate : SuspendingCliktCommand(name = "create") {
    override fun help(context: Context) = "Create a cloud session from a profile. Retrying with the same --idempotency-key never creates a second session."
    private val name by option("--name").required()
    private val profile by option("--profile", help = "Profile ID (see the dashboard).").required()
    private val image by option("--image", help = "Image override allowed by the profile.")
    private val model by option("--model")
    private val effort by option("--effort")
    private val labels by option("--label", help = "key=value; repeatable.").multiple()
    private val idle by option("--idle-minutes", help = "Idle timeout, clamped to the profile bounds.").int()
    private val onBehalfOf by option("--member", help = "Create for this member (TeamCode-On-Behalf-Of); administrators only.")
    private val idempotencyKey by option("--idempotency-key", help = "Defaults to a random key.")
    private val repositories by option("--repo", help = "[PATH=]URL[#REF]; repeatable. Replaces the profile's repositories.").multiple()
    private val branch by option("--branch", help = "Create this local branch in every --repo after checking out its ref.")
    private val workdir by option("--workdir", help = "Working directory relative to /workspace.")
    private val json by option("--json").flag()
    override suspend fun run() {
        val parsed = parseLabels(labels)
        val agent = if (model != null || effort != null) CloudAgentRequest(model, effort) else null
        require(branch == null || repositories.isNotEmpty()) { "Use --branch with --repo." }
        val environment = if (repositories.isNotEmpty() || workdir != null)
            CloudEnvironmentRequest(repositories.map { parseRepository(it).copy(branch = branch) }.takeIf { it.isNotEmpty() }, workdir) else null
        val session = sdk(loadConfig()).use {
            it.createCloudSession(CreateCloudSessionRequest(name, profile, image, agent, parsed, idle, environment), idempotencyKey ?: Uuid.random().toString(), onBehalfOf)
        }
        echo(if (json) Serialization.json.encodeToString(session) else session.line())
    }
}

private class CloudList : SuspendingCliktCommand(name = "list") {
    override fun help(context: Context) = "List your sessions, newest first (at most $MAX_LISTED)."
    private val all by option("--all", help = "Every member's sessions; administrators only.").flag()
    private val member by option("--member", help = "Another member's sessions; administrators only.")
    private val states by option("--state", help = "Repeatable: ${CloudSessionStates.all.joinToString(", ")}.").multiple()
    private val labels by option("--label", help = "key=value; repeatable.").multiple()
    private val json by option("--json").flag()
    override suspend fun run() {
        require(!(all && member != null)) { "Use --all or --member, not both." }
        val parsed = parseLabels(labels)
        sdk(loadConfig()).use { client ->
            var before: String? = null
            val sessions = mutableListOf<CloudSessionView>()
            do {
                val page = client.cloudSessions(if (all) "*" else member, states, parsed, before, 50)
                sessions += page.sessions
                before = page.nextBefore
            } while (before != null && sessions.size < MAX_LISTED)
            if (json) echo(Serialization.json.encodeToString(sessions)) else sessions.forEach { echo(it.line()) }
            if (before != null) echo("Showing the newest $MAX_LISTED sessions; filter with --state or --label.", err = true)
        }
    }
    private companion object { const val MAX_LISTED = 500 }
}

private class CloudShow : SuspendingCliktCommand(name = "show") {
    private val id by argument()
    private val executions by option("--executions", help = "Also list pod executions.").flag()
    private val json by option("--json").flag()
    override suspend fun run() {
        sdk(loadConfig()).use { client ->
            val session = client.cloudSession(id)
            if (json) echo(Serialization.json.encodeToString(session)) else echo(session.details())
            if (executions) for (execution in client.cloudExecutions(id))
                echo("e${execution.epoch}\t${execution.state}\t${execution.podName}\t${execution.nodeName ?: "-"}\t${execution.exitReason ?: "-"}\t${execution.checkpoint ?: "-"}")
        }
    }
}

private class CloudPause : SuspendingCliktCommand(name = "pause") {
    private val id by argument()
    private val force by option("--force", help = "Do not wait for the agent to drain; the workspace becomes crash-consistent.").flag()
    private val onBehalfOf by option("--member", help = "Act for this member (TeamCode-On-Behalf-Of); administrators only.")
    override suspend fun run() { echo(sdk(loadConfig()).use { it.pauseCloudSession(id, force, onBehalfOf) }.line()) }
}

private class CloudResume : SuspendingCliktCommand(name = "resume") {
    private val id by argument()
    private val onBehalfOf by option("--member", help = "Act for this member (TeamCode-On-Behalf-Of); administrators only.")
    override suspend fun run() { echo(sdk(loadConfig()).use { it.resumeCloudSession(id, onBehalfOf) }.line()) }
}

private class CloudDelete : SuspendingCliktCommand(name = "delete") {
    override fun help(context: Context) = "Delete a session and its workspace. The workspace volume is deleted and the deletion proven; this cannot be undone."
    private val id by argument()
    private val yes by option("--yes", help = "Confirm the deletion.").flag()
    private val onBehalfOf by option("--member", help = "Act for this member (TeamCode-On-Behalf-Of); administrators only.")
    override suspend fun run() {
        require(yes) { "Deleting $id also deletes its workspace and cannot be undone. Add --yes to confirm." }
        sdk(loadConfig()).use { it.deleteCloudSession(id, onBehalfOf) }
        echo("Session $id is being deleted.")
    }
}
