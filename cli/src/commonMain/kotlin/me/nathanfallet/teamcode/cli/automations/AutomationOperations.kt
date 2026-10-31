package me.nathanfallet.teamcode.cli.automations

import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.UsageError
import kotlinx.serialization.json.JsonObject
import me.nathanfallet.teamcode.api.models.*
import me.nathanfallet.teamcode.cli.CliUserException
import me.nathanfallet.teamcode.cli.cloud.PROFILE_ID
import me.nathanfallet.teamcode.cli.cloud.resolveProfile
import me.nathanfallet.teamcode.cli.workflows.listAllWorkflows
import me.nathanfallet.teamcode.client.TeamCodeClient
import me.nathanfallet.teamcode.client.TeamCodeException
import okio.FileSystem
import okio.IOException
import okio.Path.Companion.toPath

/** The most a list or a history shows; older entries are reached with a filter. */
internal const val MAX_LISTED_AUTOMATIONS = 500
internal const val MAX_LISTED_RUNS = 500

/** Stops with a clear message when the server does not serve automations (it is older than this CLI). */
internal suspend fun requireAutomations(client: TeamCodeClient) {
    if (Automations.CAPABILITY !in client.capabilities().features)
        throw CliUserException("This TeamCode server is too old for automations: it does not advertise ${Automations.CAPABILITY}. Ask an administrator to update it.")
}

/** The caller's automations (or [owner]'s: `all` or a member id, administrators), newest first, at most [MAX_LISTED_AUTOMATIONS]; the flag says more exist. */
internal suspend fun listAutomations(client: TeamCodeClient, owner: String?): Pair<List<AutomationSummaryView>, Boolean> {
    val all = mutableListOf<AutomationSummaryView>()
    var before: String? = null
    do {
        val page = client.automations(owner, before, 50)
        all += page.automations
        before = page.nextBefore
    } while (before != null && all.size < MAX_LISTED_AUTOMATIONS)
    return all to (before != null)
}

/** The history of an automation, newest first, at most [MAX_LISTED_RUNS]; the flag says more exist. */
internal suspend fun listRuns(client: TeamCodeClient, id: String, sources: List<String>, statuses: List<String>, limit: Int): Pair<List<AutomationRunView>, Boolean> {
    val runs = mutableListOf<AutomationRunView>()
    var before: String? = null
    do {
        val page = client.automationRuns(id, before, minOf(50, limit - runs.size), sources, statuses)
        runs += page.runs
        before = page.nextBefore
    } while (before != null && runs.size < limit)
    return runs.take(limit) to (before != null)
}

/** What `automations run` did: a preview (`--dry-run`, nothing started) or the run it queued. */
internal sealed interface TestOutcome {
    class Preview(val preview: AutomationPreviewView) : TestOutcome
    class Started(val run: AutomationRunView) : TestOutcome
}

/**
 * A test of the saved definition. A dry run validates [payload], renders the prompt and starts nothing; otherwise a
 * real run starts, with the owner's quota and accounts, once per [idempotencyKey] (random when null).
 */
internal suspend fun testAutomation(client: TeamCodeClient, id: String, payload: JsonObject?, dryRun: Boolean, idempotencyKey: String?): TestOutcome {
    requireAutomations(client)
    if (dryRun && idempotencyKey != null) throw CliUserException("--idempotency-key goes with a real run, not --dry-run: a dry run starts nothing.")
    return if (dryRun) TestOutcome.Preview(client.previewAutomationRun(id, payload))
    else TestOutcome.Started(if (idempotencyKey == null) client.runAutomation(id, payload) else client.runAutomation(id, payload, idempotencyKey))
}

/** `--profile`: an id as is, a name looked up in the caller's profiles (a list that cannot be read sends it as an id: the server answers). */
internal suspend fun resolveProfileId(client: TeamCodeClient, profile: String): String {
    if (PROFILE_ID.matches(profile)) return profile
    val profiles = try { client.cloudProfiles() } catch (_: TeamCodeException) { emptyList() }
    return resolveProfile(profile, profiles)
}

/** `--workflow`: an id as is, a name looked up in the team's workflows (a list that cannot be read sends it as given: the server answers). */
internal suspend fun resolveWorkflowId(client: TeamCodeClient, workflow: String): String {
    val workflows = try { listAllWorkflows(client) } catch (_: TeamCodeException) { return workflow }
    return workflows.firstOrNull { it.id == workflow }?.id
        ?: workflows.filter { it.name.equals(workflow, ignoreCase = true) }.singleOrNull()?.id
        ?: workflow
}

private const val MAX_TEXT_FILE_BYTES = 1024 * 1024L

/** A text file given on the command line; the message names the path, never the content. */
internal fun readTextFile(path: String, fileSystem: FileSystem = FileSystem.SYSTEM): String {
    val file = path.toPath()
    try {
        val metadata = fileSystem.metadataOrNull(file) ?: throw CliUserException("$path does not exist.")
        if (!metadata.isRegularFile) throw CliUserException("$path is not a regular file.")
        if ((metadata.size ?: 0) > MAX_TEXT_FILE_BYTES) throw CliUserException("$path is larger than 1 MiB.")
        return fileSystem.read(file) { readUtf8() }
    } catch (_: IOException) { throw CliUserException("Cannot read $path.") }
}

/** Standard input, read line by line and refused past [limit] bytes instead of buffered whole. */
internal fun readStandardInput(limit: Int = MAX_TEXT_FILE_BYTES.toInt(), readLine: () -> String? = ::readlnOrNull): String {
    val text = StringBuilder()
    var first = true
    while (true) {
        val line = readLine() ?: break
        if (!first) text.append('\n')
        first = false
        text.append(line)
        if (text.length > limit) throw CliUserException("Standard input is larger than ${limit / 1024} KiB.")
    }
    return text.toString()
}

private val AUTOMATION_ID = Regex("[a-zA-Z0-9-]{1,36}")

/** The identifier arguments the client would reject with an exception: a usage message instead. */
internal fun requireIdentifier(value: String, what: String = "automation id"): String {
    if (!AUTOMATION_ID.matches(value)) throw UsageError("\"$value\" is not a valid $what: use the id that teamcode automations list prints.")
    return value
}

internal fun requireIdempotencyKey(value: String?): String? {
    if (value != null && !CloudHeaders.idempotencyKey.matches(value)) throw UsageError("--idempotency-key is 1 to 128 characters of letters, digits and . _ : -")
    return value
}

/** Exactly one of `--payload FILE` and `--payload-stdin`, or none. */
internal fun readPayload(file: String?, stdin: Boolean, read: () -> String = { readStandardInput(MAX_PAYLOAD_BYTES + 1) }): JsonObject? {
    if (file != null && stdin) throw CliktError("Use --payload FILE or --payload-stdin, not both.")
    return when {
        file != null -> parsePayload(readTextFile(file))
        stdin -> parsePayload(read())
        else -> null
    }
}
