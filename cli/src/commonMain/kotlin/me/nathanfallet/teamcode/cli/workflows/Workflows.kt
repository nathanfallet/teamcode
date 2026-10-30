package me.nathanfallet.teamcode.cli.workflows

import com.github.ajalt.clikt.command.SuspendingCliktCommand
import com.github.ajalt.clikt.command.SuspendingNoOpCliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.*
import com.github.ajalt.clikt.parameters.types.choice
import com.github.ajalt.clikt.parameters.types.int
import kotlinx.serialization.encodeToString
import me.nathanfallet.teamcode.api.Serialization
import me.nathanfallet.teamcode.api.models.*
import me.nathanfallet.teamcode.cli.CliUserException
import me.nathanfallet.teamcode.cli.automations.readStandardInput
import me.nathanfallet.teamcode.cli.automations.readTextFile
import me.nathanfallet.teamcode.cli.loadConfig
import me.nathanfallet.teamcode.cli.sdk
import kotlin.time.Clock

/** The most `workflows runs` fetches in one run; older entries are reached with `--before`. */
internal const val MAX_LISTED_RUNS = 500
internal const val MIN_INTERVAL_SECONDS = 2

/** `teamcode workflows`: saved sequences of agent, human and sub-workflow steps, and their runs. */
class WorkflowsCommand : SuspendingNoOpCliktCommand(name = "workflows") {
    override fun help(context: Context) = "Workflows: saved sequences of agent steps, questions to people and sub-workflows. List and read them, save or delete a definition (JSON), " +
        "start a run and follow it, list runs, see where a run is, cancel it."
    init { subcommands(WorkflowsList(), WorkflowsShow(), WorkflowsCreate(), WorkflowsUpdate(), WorkflowsDelete(), WorkflowsSettings(), WorkflowsRun(), WorkflowsRuns(), WorkflowsRunShow(), WorkflowsCancel()) }
}

private fun readDefinitionFile(file: String): WorkflowDraft {
    val draft = parseDefinition(if (file == "-") readStandardInput() else readTextFile(file))
    requireValidDefinition(draft)
    return draft
}

private class WorkflowsList : SuspendingCliktCommand(name = "list") {
    override fun help(context: Context) = "List the team's workflows: id, name, version, nodes, inputs, age."
    private val json by option("--json").flag()
    override suspend fun run() {
        sdk(loadConfig()).use { client ->
            requireWorkflows(client)
            val workflows = listAllWorkflows(client)
            if (json) echo(Serialization.json.encodeToString(workflows))
            else { val now = Clock.System.now().toEpochMilliseconds(); workflows.forEach { echo(it.line(now)) } }
            if (workflows.isEmpty() && !json) echo("No workflow is saved yet. Save one with: teamcode workflows create --file FILE.json", err = true)
        }
    }
}

private class WorkflowsShow : SuspendingCliktCommand(name = "show") {
    override fun help(context: Context) = "Show a workflow: inputs, nodes (with instructions, deliverables, questions) and where each outcome leads."
    private val id by argument()
    private val json by option("--json", help = "The definition as JSON, which create and update accept back.").flag()
    override suspend fun run() {
        requireWorkflowId(id)
        sdk(loadConfig()).use { client ->
            requireWorkflows(client)
            val workflow = client.workflow(id)
            echo(if (json) Serialization.json.encodeToString(workflow) else workflow.details())
        }
    }
}

private class WorkflowsCreate : SuspendingCliktCommand(name = "create") {
    override fun help(context: Context) = "Save a new workflow from a JSON definition (name, start, nodes, and optionally description, sessionMode, inputs, limits). " +
        "The obvious mistakes are listed with the node they concern before anything is sent; the server checks the rest. Needs the right to edit (teamcode workflows settings) and an interactive login."
    private val file by option("--file", help = "The definition: a file, or - for standard input.").required()
    private val json by option("--json").flag()
    override suspend fun run() {
        val draft = readDefinitionFile(file)
        sdk(loadConfig()).use { client ->
            requireWorkflows(client)
            val created = client.createWorkflow(draft)
            echo(if (json) Serialization.json.encodeToString(created) else "Saved ${created.name} [${created.id}] as v${created.version}.")
        }
    }
}

private class WorkflowsUpdate : SuspendingCliktCommand(name = "update") {
    override fun help(context: Context) = "Save a new version of a workflow from a JSON definition (the output of show --json works). Runs in flight keep the version they started with. " +
        "With --expected-version the save is refused if someone saved in between."
    private val id by argument()
    private val file by option("--file", help = "The definition: a file, or - for standard input.").required()
    private val expected by option("--expected-version", help = "The version you based the change on.").int()
    private val json by option("--json").flag()
    override suspend fun run() {
        requireWorkflowId(id)
        if (expected != null && expected!! < 1) throw UsageError("--expected-version is a version number, 1 or more.")
        val draft = readDefinitionFile(file)
        sdk(loadConfig()).use { client ->
            requireWorkflows(client)
            val updated = client.updateWorkflow(id, draft, expected)
            echo(if (json) Serialization.json.encodeToString(updated) else "Saved ${updated.name} [${updated.id}] as v${updated.version}.")
        }
    }
}

private class WorkflowsDelete : SuspendingCliktCommand(name = "delete") {
    override fun help(context: Context) = "Delete a workflow. Refused while another workflow calls it; runs in flight are not affected."
    private val id by argument()
    private val json by option("--json").flag()
    override suspend fun run() {
        requireWorkflowId(id)
        sdk(loadConfig()).use { client ->
            requireWorkflows(client)
            val deleted = client.deleteWorkflow(id)
            echo(if (json) Serialization.json.encodeToString(deleted) else "Deleted ${deleted.name} [${deleted.id}].")
        }
    }
}

private class WorkflowsSettings : SuspendingCliktCommand(name = "settings") {
    override fun help(context: Context) = "Show who may create, edit and delete workflows (everyone may read). With --mode, change it (administrators, signed in)."
    private val mode by option("--mode", help = "all (every member), admins, or members (with --member).").choice(*Workflows.editorModes.toTypedArray())
    private val members by option("--member", help = "A member id allowed to edit; repeat it. Only with --mode members.").multiple()
    private val json by option("--json").flag()
    override suspend fun run() {
        if (mode == null && members.isNotEmpty()) throw UsageError("--member goes with --mode members.")
        if (mode != null && mode != "members" && members.isNotEmpty()) throw UsageError("--member is for --mode members, not --mode $mode.")
        if (mode == "members" && members.isEmpty()) throw UsageError("--mode members needs at least one --member ID (use --mode admins for administrators only).")
        members.forEach { requireWorkflowId(it, "member id") }
        sdk(loadConfig()).use { client ->
            requireWorkflows(client)
            val settings = mode?.let { client.updateWorkflowEditors(WorkflowEditorsView(it, members.distinct())) } ?: client.workflowSettings()
            echo(if (json) Serialization.json.encodeToString(settings) else settings.render())
        }
    }
}

private class WorkflowsRun : SuspendingCliktCommand(name = "run") {
    override fun help(context: Context) = "Start a run of a workflow as yourself. Inputs are checked against the definition first (an unknown or missing one is named and nothing is sent). " +
        "With --wait, follow the run and print each step until it ends: exit code 0 when it succeeded, 1 failed, 2 cancelled, 3 still unfinished at --timeout. Ctrl-C stops following, never the run."
    private val id by argument()
    private val inputs by option("--input", help = "KEY=VALUE for a declared input; repeat it.").convert { text ->
        text.indexOf('=').let { i -> if (i <= 0) throw UsageError("\"${text.substringBefore('=')}\" needs the form KEY=VALUE.") else text.substring(0, i) to text.substring(i + 1) }
    }.multiple()
    private val profile by option("--profile", help = "The cloud profile for agent steps that name none (teamcode cloud profiles).")
    private val wait by option("--wait", help = "Follow the run until it ends.").flag()
    private val interval by option("--interval", help = "With --wait, seconds between reads: $MIN_INTERVAL_SECONDS or more (3 by default).").int().default(3)
    private val timeout by option("--timeout", help = "With --wait, stop following after this many minutes (60 by default, 1 to 1440). The run keeps going.").int().default(60)
    private val json by option("--json", help = "The run as JSON (with --wait, the final state; the steps go to standard error).").flag()
    override suspend fun run() {
        requireWorkflowId(id)
        profile?.let { requireWorkflowId(it, "profile id") }
        if (interval < MIN_INTERVAL_SECONDS) throw UsageError("--interval is $MIN_INTERVAL_SECONDS seconds or more.")
        if (timeout !in 1..1440) throw UsageError("--timeout is 1 to 1440 minutes.")
        sdk(loadConfig()).use { client ->
            requireWorkflows(client)
            val workflow = client.workflow(id)
            val values = resolveInputs(workflow, inputs)
            val started = client.startWorkflowRun(id, StartWorkflowRunRequest(values, profile))
            if (!wait) {
                echo(if (json) Serialization.json.encodeToString(started)
                else "Started run ${started.id} of ${workflow.name} (v${started.workflowVersion}).\nFollow it: teamcode workflows run-show ${started.id}   Stop it: teamcode workflows cancel ${started.id}")
                return
            }
            echo("Started run ${started.id} of ${workflow.name} (v${started.workflowVersion}). Following it; Ctrl-C stops following and leaves the run running (teamcode workflows run-show ${started.id}).", err = true)
            val followed = follow(client, started.id, interval * 1000L, timeout * 60_000L, first = null,
                emit = { echo(it, err = json) },
                onCancel = { echo("Stopped following. Run ${started.id} is still running: teamcode workflows run-show ${started.id}", err = true) })
            if (followed.timedOut) {
                echo("Still ${followed.run.status} after $timeout minutes; stopped following. The run keeps going: teamcode workflows run-show ${started.id}", err = true)
            } else {
                echo("Run ${started.id} ${followed.run.status}" + (followed.run.failure?.let { ": ${failureWords(it)}" } ?: "") + ".", err = json)
                if (!json) followed.run.output?.let { echo("Output:\n$it") }
            }
            if (json) echo(Serialization.json.encodeToString(followed.run))
            val code = exitCode(followed.run.status)
            if (code != 0) throw ProgramResult(code)
        }
    }
}

private class WorkflowsRuns : SuspendingCliktCommand(name = "runs") {
    override fun help(context: Context) = "List runs, newest first: id, status, workflow, current step, age, failure. Yours by default; --all is every member's (administrators)."
    private val all by option("--all", help = "The whole team's runs; administrators only.").flag()
    private val workflow by option("--workflow", help = "Only runs of this workflow id.")
    private val status by option("--status", help = "running, waiting, done (same as succeeded), failed or cancelled.").choice("running", "waiting", "done", "succeeded", "failed", "cancelled")
    private val before by option("--before", help = "The id the previous page ended with (printed when more exist).")
    private val limit by option("--limit", help = "At most this many rows: 1 to $MAX_LISTED_RUNS (50).").int().default(50)
    private val json by option("--json").flag()
    override suspend fun run() {
        if (limit !in 1..MAX_LISTED_RUNS) throw UsageError("--limit is 1 to $MAX_LISTED_RUNS.")
        workflow?.let { requireWorkflowId(it) }
        before?.let { requireWorkflowId(it, "cursor") }
        sdk(loadConfig()).use { client ->
            requireWorkflows(client)
            val (runs, more) = listRuns(client, workflow, status?.let { if (it == "done") "succeeded" else it }, before, limit, all)
            if (json) echo(Serialization.json.encodeToString(runs))
            else { val now = Clock.System.now().toEpochMilliseconds(); runs.forEach { echo(it.line(now)) } }
            if (runs.isEmpty() && !json) echo("No run matches.", err = true)
            if (more != null) echo("More exist: teamcode workflows runs --before $more", err = true)
        }
    }
}

private class WorkflowsRunShow : SuspendingCliktCommand(name = "run-show") {
    override fun help(context: Context) = "Show a run: its status, the step it is on, the wait it is blocked on, the failure in words, and the timeline of visits."
    private val id by argument()
    private val json by option("--json").flag()
    override suspend fun run() {
        requireWorkflowId(id, "run id")
        sdk(loadConfig()).use { client ->
            requireWorkflows(client)
            val run = client.workflowRun(id)
            echo(if (json) Serialization.json.encodeToString(run) else run.details(Clock.System.now().toEpochMilliseconds()))
        }
    }
}

private class WorkflowsCancel : SuspendingCliktCommand(name = "cancel") {
    override fun help(context: Context) = "Cancel a run (its starter or an administrator, signed in). The open question is withdrawn; the session is left as it is."
    private val id by argument()
    private val json by option("--json").flag()
    override suspend fun run() {
        requireWorkflowId(id, "run id")
        sdk(loadConfig()).use { client ->
            requireWorkflows(client)
            val run = client.cancelWorkflowRun(id)
            echo(if (json) Serialization.json.encodeToString(run) else "Run ${run.id} of ${run.workflowName} is ${run.status}.")
        }
    }
}
