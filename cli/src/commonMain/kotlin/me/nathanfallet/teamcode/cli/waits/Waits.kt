package me.nathanfallet.teamcode.cli.waits

import com.github.ajalt.clikt.command.SuspendingCliktCommand
import com.github.ajalt.clikt.command.SuspendingNoOpCliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.*
import com.github.ajalt.clikt.parameters.types.choice
import com.github.ajalt.clikt.parameters.types.int
import com.github.ajalt.clikt.parameters.types.long
import kotlinx.serialization.encodeToString
import me.nathanfallet.teamcode.api.Serialization
import me.nathanfallet.teamcode.api.models.*
import me.nathanfallet.teamcode.cli.CliUserException
import me.nathanfallet.teamcode.cli.loadConfig
import me.nathanfallet.teamcode.cli.sdk
import kotlin.time.Clock

/** `teamcode waits`: what sessions need from a person before they can go on. */
class WaitsCommand : SuspendingNoOpCliktCommand(name = "waits") {
    override fun help(context: Context) = "What waits for you: the questions and requests of your sessions. List them, read one, answer it, cancel it, or ask a member for something."
    init { subcommands(WaitsList(), WaitsShow(), WaitsAnswer(), WaitsCancel(), WaitsAsk()) }
}

private class WaitsList : SuspendingCliktCommand(name = "list") {
    override fun help(context: Context) = "List the waits asked of you (open ones by default), newest first: id, status, kind, title, session, age, source. " +
        "A session's own permission request or question (source session) is answered in the session, not here."
    private val all by option("--all", help = "Every status: same as --status all.").flag()
    private val status by option("--status", help = "open (the default), resolved, cancelled or all.").choice("open", "resolved", "cancelled", "all")
    private val assignee by option("--assignee", help = "all, or a member id: the team's or that member's waits; administrators only.")
    private val resolver by option("--resolver", help = "human (the default: what waits for you), time, event (what the server resolves by itself: a delay, a CI) or all.").choice("human", "time", "event", "all")
    private val before by option("--before", help = "The id the previous page ended with (printed when more exist).")
    private val limit by option("--limit", help = "At most this many rows: 1 to $MAX_LISTED_WAITS (50).").int().default(50)
    private val json by option("--json").flag()
    override suspend fun run() {
        if (all && status != null && status != "all") throw UsageError("Use --all or --status, not both.")
        if (limit !in 1..MAX_LISTED_WAITS) throw UsageError("--limit is 1 to $MAX_LISTED_WAITS.")
        assignee?.let { if (it != "all") requireWaitId(it, "member id") }
        before?.let { requireWaitId(it, "cursor") }
        sdk(loadConfig()).use { client ->
            requireWaits(client)
            val (waits, more) = listWaits(client, assignee, if (all) "all" else status ?: "open", before, limit, resolver)
            if (json) echo(Serialization.json.encodeToString(waits))
            else { val now = Clock.System.now().toEpochMilliseconds(); waits.forEach { echo(it.line(now)) } }
            if (waits.isEmpty() && !json) echo("Nothing waits for you.", err = true)
            if (more != null) echo("More exist: teamcode waits list --before $more", err = true)
        }
    }
}

private class WaitsShow : SuspendingCliktCommand(name = "show") {
    override fun help(context: Context) = "Show a wait: its text, references, fields, actions and the answer if any."
    private val id by argument()
    private val json by option("--json").flag()
    override suspend fun run() {
        requireWaitId(id)
        sdk(loadConfig()).use { client ->
            requireWaits(client)
            val wait = client.wait(id)
            echo(if (json) Serialization.json.encodeToString(wait) else wait.details())
        }
    }
}

private fun pair(text: String): Pair<String, String> = text.indexOf('=').let { i ->
    if (i <= 0) throw UsageError("\"${text.substringBefore('=')}\" needs the form NAME=VALUE.") else text.substring(0, i) to text.substring(i + 1)
}

private class WaitsAnswer : SuspendingCliktCommand(name = "answer") {
    override fun help(context: Context) = "Answer a wait with one of its actions and the values of its fields. Values are converted by the field's declared type " +
        "(repeat --set for a multiple choice); use --set-json for a JSON value. Only a stored wait is answered here."
    private val id by argument()
    private val action by option("--action", help = "The action to take; optional when the wait has only one.")
    private val sets by option("--set", help = "FIELD=VALUE, as text; converted by the field's type.").convert { pair(it) }.multiple()
    private val jsonSets by option("--set-json", help = "FIELD=JSON, used as given: a number, true, \"text\", [\"a\",\"b\"].").convert { pair(it) }.multiple()
    private val json by option("--json").flag()
    override suspend fun run() {
        requireWaitId(id)
        sdk(loadConfig()).use { client ->
            requireWaits(client)
            val wait = client.wait(id)
            if (wait.source == "session") throw CliUserException("This is a request of the cloud session ${wait.sessionName ?: wait.sessionId}: $SESSION_NOTE (teamcode cloud attach), not with teamcode waits answer.")
            if (wait.status != "open") throw CliUserException("This wait is already ${wait.status}.")
            if (wait.resolver != "human") throw CliUserException("This wait resolves by itself (${wait.resolver}): nobody answers it. Withdraw it with teamcode waits cancel ${wait.id}.")
            val values = answerValues(wait, sets, jsonSets)
            val chosen = chooseAction(wait, action)
            val answered = client.answerWait(id, chosen, values)
            echo(if (json) Serialization.json.encodeToString(answered) else "Answered ${answered.id} with ${answered.answer?.action ?: chosen}.")
        }
    }
}

private class WaitsCancel : SuspendingCliktCommand(name = "cancel") {
    override fun help(context: Context) = "Withdraw a wait you asked, or one asked of you."
    private val id by argument()
    private val json by option("--json").flag()
    override suspend fun run() {
        requireWaitId(id)
        sdk(loadConfig()).use { client ->
            requireWaits(client)
            val wait = client.cancelWait(id)
            echo(if (json) Serialization.json.encodeToString(wait) else "Cancelled ${wait.id} (${wait.title}).")
        }
    }
}

private class WaitsAsk : SuspendingCliktCommand(name = "ask") {
    override fun help(context: Context) = "Ask a member for something in one of your sessions. Without --action there is one \"done\" action."
    private val session by option("--session", help = "The cloud session the wait belongs to (you own it).").required()
    private val title by option("--title", help = "1 to 200 characters.").required()
    private val body by option("--body")
    private val assignee by option("--assignee", help = "A member id; the session's owner by default.")
    private val after by option("--after", help = "Wait for a delay instead of a person: wake the session after this many seconds (30 to 2592000).").long()
    private val at by option("--at", help = "Wake the session at this instant: ISO-8601 in UTC, such as 2026-11-02T09:00:00Z (within 30 days).")
    private val webhook by option("--webhook", help = "Wait for an event instead of a person: prints an address a CI or a script calls once (the secret is shown only now).").flag()
    private val timeout by option("--timeout", help = "With --webhook: give up after this many seconds (86400 by default, 604800 at most).").long()
    private val onTimeout by option("--on-timeout", help = "With --webhook: wake the session with \"timed out\" (wake, the default) or ask its owner to retry or dismiss (turn_failed).").choice("wake", "turn_failed")
    private val resumeMessage by option("--resume-message", help = "With --after, --at or --webhook: a note added to the message that wakes the session (2000 characters).")
    private val refs by option("--ref", help = "artifact:ID, session:ID or url:https://…; repeatable.").multiple()
    private val fields by option("--field", help = "ID:TYPE:LABEL[:required]; repeatable. TYPE is text, choice, confirm, number, date, file or info.").multiple()
    private val choices by option("--option", help = "FIELD=VALUE: a choice of a choice field; repeatable.").convert { pair(it) }.multiple()
    private val actions by option("--action", help = "ID:LABEL; repeatable.").multiple()
    private val json by option("--json").flag()
    override suspend fun run() {
        requireWaitId(session, "session id")
        assignee?.let { requireWaitId(it, "member id") }
        val options = choices.groupBy({ it.first }, { it.second })
        val declared = fields.map { parseField(it, options) }
        (options.keys - declared.map { it.id }.toSet()).firstOrNull()?.let { throw UsageError("--option $it=… names a field that no --field declares.") }
        val timed = timedSettings(after, at, webhook, timeout, onTimeout, resumeMessage)
        if (timed != null && (declared.isNotEmpty() || actions.isNotEmpty() || assignee != null)) throw UsageError("A wait for a time or an event asks nobody: leave out --field, --action and --assignee.")
        val input = timed?.copy(sessionId = session, title = title, body = body, refs = refs.map(::parseRef))
            ?: WaitInput(session, title, body, assignee, refs.map(::parseRef), declared, actions.map(::parseAction))
        sdk(loadConfig()).use { client ->
            requireWaits(client)
            val wait = client.createWait(input)
            echo(if (json) Serialization.json.encodeToString(wait)
                else if (wait.resolver == "human") "Asked ${wait.assigneeName ?: wait.assigneeId}: ${wait.id} (${wait.title})."
                else wait.announcement())
        }
    }
}
