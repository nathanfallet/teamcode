package me.nathanfallet.teamcode.cli.automations

import com.github.ajalt.clikt.command.SuspendingCliktCommand
import com.github.ajalt.clikt.command.SuspendingNoOpCliktCommand
import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.groups.OptionGroup
import com.github.ajalt.clikt.parameters.groups.provideDelegate
import com.github.ajalt.clikt.parameters.options.*
import com.github.ajalt.clikt.parameters.types.choice
import com.github.ajalt.clikt.parameters.types.int
import kotlinx.serialization.encodeToString
import me.nathanfallet.teamcode.api.Serialization
import me.nathanfallet.teamcode.api.models.*
import me.nathanfallet.teamcode.cli.loadConfig
import me.nathanfallet.teamcode.cli.sdk

/** `teamcode automations`: cloud sessions started by a schedule or a webhook. */
class AutomationsCommand : SuspendingNoOpCliktCommand(name = "automations") {
    override fun help(context: Context) = "Start cloud sessions on a schedule (cron) or from a webhook, with a prompt that can use the request's fields. " +
        "Requires an interactive login to create, change or enable one."
    init { subcommands(AutomationsList(), AutomationsShow(), AutomationsCreate(), AutomationsEdit(), AutomationsEnable(), AutomationsDisable(), AutomationsDelete(), AutomationsRotateSecret(), AutomationsRuns(), AutomationsRun()) }
}

/** `--url-mode` and `--url-version`: how the webhook address is written. */
private class WebhookUrlOptions : OptionGroup("Webhook address") {
    val mode by option("--url-mode", help = "signed (the sender signs each request; the default) or secret (the secret is part of the URL, for a sender that cannot sign).")
        .choice("signed", "secret").default("signed")
    val version by option("--url-version", help = "latest (the default: follows the automation), current, or vN: a URL that answers only while the automation is at that version.")
        .default("latest")
    fun mode() = if (mode == "secret") WebhookUrlMode.SECRET else WebhookUrlMode.SIGNED
}

/** The definition's fields, for `create` (name, trigger, profile and prompt are required) and `edit` (only what is given changes). */
private class DefinitionOptions : OptionGroup("Definition") {
    val name by option("--name", help = "1 to 120 characters, unique among your automations.")
    val cron by option("--cron", help = "Five fields (minute hour day-of-month month day-of-week), at least 5 minutes between two runs; needs --timezone.")
    val webhook by option("--webhook", help = "Start on an HTTP request to the automation's own URL.").flag()
    val timezone by option("--timezone", help = "IANA name for --cron, such as Europe/Paris.")
    val catchup by option("--catchup", help = "After an outage: once (run the latest missed slot once, the default) or skip.").choice("once", "skip")
    val catchupGrace by option("--catchup-grace-minutes", help = "Oldest missed slot still run by --catchup once: 0 to 1440 (360).").int()
    val action by option("--action", help = "cloud (the default). remote is refused by the server for now.").choice("cloud", "remote")
    val profile by option("--profile", help = "Cloud profile id or name (see teamcode cloud profiles); each run uses its latest version.")
    val model by option("--model")
    val effort by option("--effort")
    val maxRuntime by option("--max-runtime-minutes", help = "A run still working after that long ends timed_out and its session is paused: 1 to 1440 (60).").int()
    val prompt by option("--prompt", help = "The prompt; - reads standard input. {{name}} is a --var or a run variable (run.id, run.date, run.time, run.timezone, run.source, automation.id, automation.name).")
    val promptFile by option("--prompt-file", help = "Read the prompt from this file.")
    val variables by option("--var", help = "Webhook payload variable: 'NAME=\$.json.path[;type=int|number|boolean|string][;values=a,b][;max=N][;optional][;sensitive]'; repeatable. " +
        "On edit, the ones given replace all the declared ones.").multiple()
    val variablesFile by option("--variables-file", help = "Variables as a JSON array of the API's variable objects, instead of --var.")
    val clearVariables by option("--clear-variables", help = "Edit: declare no variable.").flag()
    val samplePayload by option("--sample-payload", help = "Webhook: a JSON object file that validates against the variables; it pre-fills tests and no real run uses it.")
    val overlap by option("--overlap", help = "When it fires while a run is waiting or working: skip (the default), queue (one waits) or allow.").choice("skip", "queue", "allow")
    val maxRunsPerDay by option("--max-runs-per-day", help = "Cron and webhook runs over any 24 hours: 1 to 1000 (24). Tests are not counted.").int()

    suspend fun changes(resolveProfile: suspend (String) -> String, readStdin: () -> String = ::readStandardInput): AutomationChanges {
        if (prompt != null && promptFile != null) throw UsageError("Use --prompt or --prompt-file, not both.")
        if ((variables.isNotEmpty() || variablesFile != null) && clearVariables) throw UsageError("Use --var or --clear-variables, not both.")
        if (variables.isNotEmpty() && variablesFile != null) throw UsageError("Use --var or --variables-file, not both.")
        val text = promptFile?.let { readTextFile(it) } ?: prompt?.let { if (it == "-") readStdin() else it }
        val declared = when {
            clearVariables -> emptyList()
            variablesFile != null -> parseVariablesFile(readTextFile(variablesFile!!))
            variables.isNotEmpty() -> variables.map(::parseVariable)
            else -> null
        }
        return AutomationChanges(name, cron, webhook, timezone, catchup, catchupGrace, action, profile?.let { resolveProfile(it) }, model, effort, maxRuntime,
            text, declared, samplePayload?.let { parsePayload(readTextFile(it)) }, overlap, maxRunsPerDay)
    }
}

private class AutomationsList : SuspendingCliktCommand(name = "list") {
    override fun help(context: Context) = "List your automations (at most $MAX_LISTED_AUTOMATIONS): id, name, state, trigger, version, next slot, latest run."
    private val owner by option("--owner", help = "all, or a member id: that member's automations; administrators only.")
    private val json by option("--json").flag()
    override suspend fun run() {
        sdk(loadConfig()).use { client ->
            requireAutomations(client)
            val (automations, more) = listAutomations(client, owner)
            if (json) echo(Serialization.json.encodeToString(automations)) else automations.forEach { echo(it.line()) }
            if (automations.isEmpty() && !json) echo("No automation yet: create one with teamcode automations create.", err = true)
            if (more) echo("Showing the newest $MAX_LISTED_AUTOMATIONS automations.", err = true)
        }
    }
}

private class AutomationsShow : SuspendingCliktCommand(name = "show") {
    override fun help(context: Context) = "Show an automation: its definition, next slots, latest run and, for a webhook, the URL to call."
    private val id by argument()
    private val url by WebhookUrlOptions()
    private val json by option("--json").flag()
    override suspend fun run() {
        val config = loadConfig()
        sdk(config).use { client ->
            requireAutomations(client)
            val automation = client.automation(id)
            if (json) echo(Serialization.json.encodeToString(automation))
            else echo(automation.details(config.serverUrl, parseUrlVersion(url.version, automation.version), url.mode()))
        }
    }
}

private fun secretNotice(secret: String) = listOf("Webhook secret (shown once, store it now): $secret")

private class AutomationsCreate : SuspendingCliktCommand(name = "create") {
    override fun help(context: Context) = "Create an automation. It is created paused: test it with teamcode automations run ID --dry-run, then teamcode automations enable ID. " +
        "A webhook automation prints its secret once, with the URL to call."
    private val definition by DefinitionOptions()
    private val url by WebhookUrlOptions()
    private val json by option("--json").flag()
    override suspend fun run() {
        val config = loadConfig()
        sdk(config).use { client ->
            requireAutomations(client)
            val input = applyChanges(null, definition.changes({ resolveProfileId(client, it) }))
            val created = client.createAutomation(input)
            if (json) { echo(Serialization.json.encodeToString(created)); return }
            val automation = created.automation
            echo(automation.details(config.serverUrl, parseUrlVersion(url.version, automation.version), url.mode(), created.secret))
            created.secret?.let { secretNotice(it).forEach(::echo) }
            echo("It is paused. Test it: teamcode automations run ${automation.id} --dry-run" + (if (automation.trigger.kind == "webhook") " --payload FILE" else "") +
                "; arm it: teamcode automations enable ${automation.id}.", err = true)
        }
    }
}

private class AutomationsEdit : SuspendingCliktCommand(name = "edit") {
    override fun help(context: Context) = "Change an automation: only the options you give change. Saving bumps its version, so a webhook URL pinned to the old version stops answering."
    private val id by argument()
    private val definition by DefinitionOptions()
    private val json by option("--json").flag()
    override suspend fun run() {
        sdk(loadConfig()).use { client ->
            requireAutomations(client)
            val current = client.automation(id)
            val input = applyChanges(current.toInput(), definition.changes({ resolveProfileId(client, it) }))
            val saved = client.updateAutomation(id, current.version, input)
            echo(if (json) Serialization.json.encodeToString(saved) else "Saved $id as version ${saved.version}.")
            if (!json && current.webhook != null && saved.version != current.version)
                echo("Webhook URLs pinned to v${current.version} now answer 409 automation_version_changed; teamcode automations show $id --url-version current prints the new one.", err = true)
        }
    }
}

private class AutomationsEnable : SuspendingCliktCommand(name = "enable") {
    override fun help(context: Context) = "Arm an automation (its owner only). It runs as you, with your quota, accounts and GitHub link; a cron one waits for its next slot, nothing missed while paused is caught up."
    private val id by argument()
    private val json by option("--json").flag()
    override suspend fun run() {
        sdk(loadConfig()).use { client ->
            requireAutomations(client)
            val automation = client.enableAutomation(id)
            echo(if (json) Serialization.json.encodeToString(automation) else AutomationSummaryView(automation.id, automation.ownerId, automation.ownerName, automation.name, automation.enabled,
                automation.disabledReason, automation.version, automation.trigger, automation.action.kind, automation.nextRunAt, automation.lastRun, automation.consecutiveFailures, automation.updatedAt).line())
        }
    }
}

private class AutomationsDisable : SuspendingCliktCommand(name = "disable") {
    override fun help(context: Context) = "Pause an automation (its owner, or an administrator). A run already started goes on."
    private val id by argument()
    private val json by option("--json").flag()
    override suspend fun run() {
        sdk(loadConfig()).use { client ->
            requireAutomations(client)
            val automation = client.disableAutomation(id)
            echo(if (json) Serialization.json.encodeToString(automation) else "Paused ${automation.id} (${automation.name}).")
        }
    }
}

private class AutomationsDelete : SuspendingCliktCommand(name = "delete") {
    override fun help(context: Context) = "Delete an automation and its run history. The sessions it created stay yours."
    private val id by argument()
    private val yes by option("--yes", help = "Confirm the deletion.").flag()
    override suspend fun run() {
        if (!yes) throw CliktError("Deleting $id also deletes its run history and cannot be undone. Add --yes to confirm.")
        sdk(loadConfig()).use { client ->
            requireAutomations(client)
            client.deleteAutomation(id)
            echo("Automation $id is deleted.")
        }
    }
}

private class AutomationsRotateSecret : SuspendingCliktCommand(name = "rotate-secret") {
    override fun help(context: Context) = "Issue a new webhook secret, shown once. The former one is accepted 10 more minutes, the time to update the sender."
    private val id by argument()
    private val url by WebhookUrlOptions()
    private val json by option("--json").flag()
    override suspend fun run() {
        val config = loadConfig()
        sdk(config).use { client ->
            requireAutomations(client)
            val rotated = client.rotateAutomationSecret(id)
            if (json) { echo(Serialization.json.encodeToString(rotated)); return }
            val automation = rotated.automation
            webhookInstructions(config.serverUrl, automation, parseUrlVersion(url.version, automation.version), url.mode(), rotated.secret).forEach(::echo)
            rotated.secret?.let { secretNotice(it).forEach(::echo) }
        }
    }
}

private class AutomationsRuns : SuspendingCliktCommand(name = "runs") {
    override fun help(context: Context) = "The history of an automation, newest first (at most $MAX_LISTED_RUNS, kept 14 days): run id, status, trigger, version, time and the session it created " +
        "(teamcode cloud show SESSION)."
    private val id by argument()
    private val sources by option("--source", help = "${Automations.runSources.joinToString(", ")}; repeatable.").choice(*Automations.runSources.toTypedArray()).multiple()
    private val statuses by option("--status", help = "${Automations.runStatuses.joinToString(", ")}; repeatable.").choice(*Automations.runStatuses.toTypedArray()).multiple()
    private val limit by option("--limit", help = "At most this many runs (default 50).").int().default(50)
    private val json by option("--json").flag()
    override suspend fun run() {
        if (limit !in 1..MAX_LISTED_RUNS) throw UsageError("--limit is between 1 and $MAX_LISTED_RUNS.")
        sdk(loadConfig()).use { client ->
            requireAutomations(client)
            val (runs, more) = listRuns(client, id, sources, statuses, limit)
            if (json) echo(Serialization.json.encodeToString(runs)) else runs.forEach { echo(it.line()) }
            if (runs.isEmpty() && !json) echo("No run yet.", err = true)
            if (more) echo("Showing the newest $limit runs; raise --limit or filter with --status and --source.", err = true)
        }
    }
}

private class AutomationsRun : SuspendingCliktCommand(name = "run") {
    override fun help(context: Context) = "Test the saved definition now, paused or not. A webhook automation takes a payload (--payload FILE or --payload-stdin); a cron one takes none. " +
        "--dry-run validates the payload and shows the prompt the agent would get, and starts nothing. Without it, the test is a real run on your quota and accounts " +
        "(not counted in the daily budget); the same --idempotency-key never starts a second one."
    private val id by argument()
    private val payload by option("--payload", help = "A JSON object file, as the webhook would receive (at most 64 KiB).")
    private val payloadStdin by option("--payload-stdin", help = "Read the payload from standard input.").flag()
    private val dryRun by option("--dry-run", help = "Validate and render only: no run, no session, no quota.").flag()
    private val idempotencyKey by option("--idempotency-key", help = "Defaults to a random key; real runs only.")
    private val json by option("--json").flag()
    override suspend fun run() {
        val body = readPayload(payload, payloadStdin)
        sdk(loadConfig()).use { client ->
            when (val outcome = testAutomation(client, id, body, dryRun, idempotencyKey)) {
                is TestOutcome.Preview -> {
                    echo(if (json) Serialization.json.encodeToString(outcome.preview) else outcome.preview.text())
                    if (outcome.preview.problems.isNotEmpty()) throw ProgramResult(1)
                }
                is TestOutcome.Started -> {
                    echo(if (json) Serialization.json.encodeToString(outcome.run) else outcome.run.line())
                    if (!json) echo("Follow it: teamcode automations runs $id (the session appears once it starts).", err = true)
                }
            }
        }
    }
}
