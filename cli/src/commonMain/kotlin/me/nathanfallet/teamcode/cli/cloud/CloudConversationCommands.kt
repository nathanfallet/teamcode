package me.nathanfallet.teamcode.cli.cloud

import com.github.ajalt.clikt.command.SuspendingCliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.*
import com.github.ajalt.clikt.parameters.types.choice
import com.github.ajalt.clikt.parameters.types.int
import com.github.ajalt.clikt.parameters.types.long
import kotlin.time.Instant
import kotlin.uuid.Uuid
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.serialization.json.*
import me.nathanfallet.teamcode.api.Serialization
import me.nathanfallet.teamcode.api.models.*
import me.nathanfallet.teamcode.cli.SystemExit
import me.nathanfallet.teamcode.cli.loadConfig
import me.nathanfallet.teamcode.cli.terminal.Key
import me.nathanfallet.teamcode.cli.terminal.toKey
import com.github.ajalt.mordant.input.enterRawModeOrNull
import com.github.ajalt.mordant.terminal.Terminal
import me.nathanfallet.teamcode.cli.sdk
import me.nathanfallet.teamcode.cli.terminal.printable
import me.nathanfallet.teamcode.cli.terminal.quoted
import me.nathanfallet.teamcode.client.CloudStreamEnd
import me.nathanfallet.teamcode.client.TeamCodeClient
import me.nathanfallet.teamcode.client.TeamCodeException

private fun JsonObject.text(name: String) = (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.content
private fun JsonElement?.isTrue() = (this as? JsonPrimitive)?.booleanOrNull == true
private fun String.preview(limit: Int) = replace('\n', ' ').let { if (it.length > limit) it.take(limit - 1) + "…" else it }

/**
 * A question request's questions and options in full, each with the index `teamcode cloud answer --answer` takes:
 * `? 0: "Red or blue?" (0: "Red" / 1: "Blue")`, marked `several` when more than one option may be chosen.
 */
private fun questions(input: JsonElement?): String = ((input as? JsonObject)?.get("questions") as? JsonArray).orEmpty().withIndex().joinToString("") { (index, element) ->
    val question = element as? JsonObject ?: JsonObject(emptyMap())
    val options = (question["options"] as? JsonArray).orEmpty().mapIndexed { option, it -> "$option: ${(((it as? JsonObject)?.get("label") as? JsonPrimitive)?.contentOrNull ?: "").quoted()}" }
    val several = (question["multiSelect"] as? JsonPrimitive)?.booleanOrNull == true
    "\n    ? $index: " + (question.text("question") ?: "").quoted().printable() + (if (options.isEmpty()) "" else " (${options.joinToString(" / ").printable()})") + if (several) " several" else ""
}

/** Micro-dollars as `$0.003253`; null for a negative amount, which no runner reports. */
internal fun dollars(micros: Long): String? = if (micros < 0) null else "$" + "${micros / 1_000_000}.${(micros % 1_000_000).toString().padStart(6, '0')}"

/**
 * One readable line (or block) per event; unknown types are shown by name so newer servers stay readable. A subagent's
 * events (`parentToolUseId`) are indented under the tool call that started it.
 */
internal fun CloudEventView.render(): String {
    val time = Instant.fromEpochMilliseconds(at).toString().substring(11, 19)
    val body = when (type) {
        CloudEventTypes.USER_MESSAGE -> "> " + (data.text("text") ?: "")
        CloudEventTypes.AGENT_MESSAGE -> data.text("text") ?: ""
        CloudEventTypes.TOOL_CALL -> "[tool] ${data.text("name") ?: "tool"} ${data["input"]?.toString()?.preview(160) ?: ""}".trimEnd()
        CloudEventTypes.TOOL_RESULT -> "[result${if ((data["isError"] as? JsonPrimitive)?.booleanOrNull == true) ", error" else ""}] ${(data.text("output") ?: "").preview(300)}"
        CloudEventTypes.TURN -> "-- turn ${data.text("phase") ?: "update"}${(data["costMicros"] as? JsonPrimitive)?.longOrNull?.let(::dollars)?.let { ", $it" } ?: ""} --"
        CloudEventTypes.STATUS -> "[status] " + data.entries.joinToString(", ") { (key, value) -> "$key=${(value as? JsonPrimitive)?.contentOrNull ?: value}" }
        CloudEventTypes.ERROR -> "[error] ${data.text("code") ?: "error"}: ${data.text("message") ?: ""}".trimEnd(' ', ':')
        CloudEventTypes.INPUT -> "[${data.text("kind") ?: "message"} ${data.text("inputId")?.take(8) ?: ""} ${data.text("status") ?: ""}${data.text("reason")?.let { ": $it" } ?: ""}]" +
            // A permission answer says which request it answered, how, and who decided.
            (data.text("requestId")?.let { request ->
                val by = data.text("authorId")?.printable() ?: "?"
                if (data.text("status") == "consumed") " ${data.text("behavior") ?: "answer"} ${request.printable()} by $by" else " (an answer to ${request.printable()} by $by, not applied)"
            } ?: "")
        CloudEventTypes.PERMISSION_REQUEST -> "[${if (data["question"].isTrue()) "question" else "permission"} ${data.text("requestId")?.printable() ?: ""}] " +
            // The whole input (bounded by the runner): whoever decides must see everything the tool would do.
            // A question is shown by the indexed lines that follow, not as raw JSON.
            // An input left out of the event shows its preview instead, and can only be denied (the server refuses allow).
            "${data.text("toolName") ?: "tool"} ${if (data["question"].isTrue() && data["input"] != null) "" else data["input"]?.toString() ?: data.text("inputPreview")?.let { "$it… (cut; too large to approve, deny only)" } ?: ""}".printable().trimEnd() + if (data["question"].isTrue()) questions(data["input"]) else ""
        CloudEventTypes.PERMISSION_EXPIRED -> "[expired ${data.text("requestId") ?: ""}] ${data.text("message") ?: "No answer in time: the request was denied."}".printable()
        else -> "[$type] $data"
    }
    // Every line is agent text: escape sequences never reach the terminal, so none can rewrite a request shown above.
    return "$time #$seq ${if (data.text("parentToolUseId") != null) "  ↳ " else ""}${body.lines().joinToString("\n") { it.printable() }}"
}

private fun CloudInputView.line() = listOfNotNull(id, kind, status, reason, if (woke) "woke the session" else null).joinToString("\t")

internal class CloudLogs : SuspendingCliktCommand(name = "logs") {
    override fun help(context: Context) = "Print a session transcript. With --follow, keep printing new events and reconnect after network failures."
    private val id by argument()
    private val after by option("--after", help = "Start after this event sequence (default: the last --tail events).").long()
    private val tail by option("--tail", help = "Events to show before following (default 50).").int().default(50)
    private val follow by option("--follow", "-f").flag()
    private val json by option("--json", help = "Print events as JSON lines.").flag()
    override suspend fun run() {
        if (tail !in 0..10_000) throw UsageError("Use --tail between 0 and 10000.")
        sdk(loadConfig()).use { client ->
            val print: (CloudEventView) -> Unit = { echo(if (json) Serialization.json.encodeToString(it) else it.render()) }
            var cursor = after ?: (client.cloudEvents(id, after = 0, limit = 1).lastSeq - tail).coerceAtLeast(0)
            while (true) {
                val page = client.cloudEvents(id, after = cursor, limit = 200)
                page.events.forEach(print)
                cursor = page.events.lastOrNull()?.seq ?: cursor
                if (page.nextAfter == null) break
            }
            if (follow && !followEvents(client, id, cursor, print, notice = { echo(it, err = true) })) SystemExit.exit(1)
        }
    }
}

/**
 * Follows the event stream from [after], reconnecting with backoff and resuming from the last printed sequence, so
 * no event is printed twice or skipped. Returns `true` when the session ended and `false` when an event cannot be read
 * (reconnecting would replay it), so `attach` still detaches; exits when access was revoked.
 */
internal suspend fun followEvents(client: TeamCodeClient, id: String, after: Long, print: (CloudEventView) -> Unit, notice: (String) -> Unit, exit: (Int) -> Nothing = SystemExit::exit): Boolean {
    var cursor = after
    var wait = 1_000L
    while (true) {
        try {
            val end = client.followCloudEvents(id, cursor) { event -> if (event.seq > cursor) { print(event); cursor = event.seq; wait = 1_000L } }
            when (end) {
                CloudStreamEnd.ENDED -> { notice("The session has ended."); return true }
                CloudStreamEnd.DENIED -> { notice("Access to this session was revoked."); exit(1) }
            }
        } catch (error: CancellationException) { throw error }
        catch (error: TeamCodeException) {
            if (error.statusCode in setOf(401, 403, 404)) { notice("The server refused the transcript (HTTP ${error.statusCode}${error.code?.let { ", $it" } ?: ""})."); exit(1) }
            // Reconnecting would replay the same event from the same cursor.
            if (error.statusCode == 0 && error.code == TeamCodeClient.MALFORMED_EVENT) {
                notice("The event after $cursor cannot be read (${error.message}). Update teamcode, or follow from a later event with `teamcode cloud logs $id --follow --after <sequence>`.")
                return false
            }
            notice("Disconnected; reconnecting from event $cursor.")
        } catch (_: Exception) { notice("Disconnected; reconnecting from event $cursor.") }
        delay(wait)
        wait = (wait * 2).coerceAtMost(15_000)
    }
}

/** Sends with one idempotency key and retries network failures, so a retry never delivers the message twice. */
internal suspend fun sendWithRetry(client: TeamCodeClient, id: String, request: SendCloudMessageRequest, key: String, notice: (String) -> Unit): CloudInputView {
    var wait = 1_000L
    repeat(5) {
        try { return client.sendCloudMessage(id, request, key) }
        catch (error: CancellationException) { throw error }
        catch (error: TeamCodeException) { if (error.statusCode in 400..499 && error.statusCode != 408 && error.statusCode != 429) throw error }
        catch (_: Exception) { }
        notice("Sending failed; retrying.")
        delay(wait); wait *= 2
    }
    return client.sendCloudMessage(id, request, key)
}

internal class CloudSend : SuspendingCliktCommand(name = "send") {
    override fun help(context: Context) = "Send a message to a session. A paused session wakes up unless --no-wake is given; the message waits in its queue."
    private val id by argument()
    private val text by argument(help = "The message; use - to read it from stdin.")
    private val noWake by option("--no-wake", help = "Leave a paused session paused; the message is delivered on the next resume.").flag()
    private val idempotencyKey by option("--idempotency-key", help = "Retrying with the same key never sends twice. Defaults to a random key.")
    private val wait by option("--wait", help = "Wait until the agent consumed the message (or it was rejected).").flag()
    private val json by option("--json").flag()
    override suspend fun run() {
        val message = if (text == "-") generateSequence(::readlnOrNull).joinToString("\n") else text
        sdk(loadConfig()).use { client ->
            var input = sendWithRetry(client, id, SendCloudMessageRequest(message, wake = !noWake), idempotencyKey ?: Uuid.random().toString()) { echo(it, err = true) }
            if (wait) while (input.status == CloudInputStatuses.QUEUED || input.status == CloudInputStatuses.DELIVERED) {
                delay(1_000)
                input = client.cloudInput(id, input.id)
            }
            echo(if (json) Serialization.json.encodeToString(input) else input.line())
        }
    }
}

internal class CloudInterrupt : SuspendingCliktCommand(name = "interrupt") {
    override fun help(context: Context) = "Stop the agent's current turn. Queued messages stay queued."
    private val id by argument()
    override suspend fun run() { echo(sdk(loadConfig()).use { it.interruptCloudSession(id, Uuid.random().toString()) }.line()) }
}

internal class CloudAnswer : SuspendingCliktCommand(name = "answer") {
    override fun help(context: Context) = "Answer a permission request of the agent (a [permission] or [question] line of the transcript): allow or deny it, " +
        "or answer its questions by the indexes printed under it. A tool use is answered by the session owner or an administrator only."
    private val id by argument()
    private val request by argument("REQUEST_ID")
    private val behavior by argument("allow|deny").choice("allow", "deny")
    private val message by option("--message", help = "Why it was denied; the agent reads it.")
    private val answers by option("--answer", help = "QUESTION=OPTION[,OPTION...] by index, from 0, for a question request (repeatable); needs allow.").multiple()
    private val texts by option("--answer-text", help = "QUESTION=TEXT: a free answer to the question at that index (repeatable); needs allow.").multiple()
    override suspend fun run() {
        val parsed = answers.map { value ->
            val (question, options) = indexed(value, "--answer QUESTION=OPTION[,OPTION...]")
            CloudQuestionAnswer(question, options.split(',').map { it.trim().toIntOrNull()?.takeIf { index -> index >= 0 } ?: throw UsageError("Use --answer QUESTION=OPTION[,OPTION...] with option indexes from 0.") })
        } + texts.map { value -> indexed(value, "--answer-text QUESTION=TEXT").let { (question, text) -> CloudQuestionAnswer(question, text = text) } }
        val answer = AnswerCloudPermissionRequest(request, behavior, message, parsed.sortedBy { it.question }.takeIf { it.isNotEmpty() })
        echo(sdk(loadConfig()).use { it.answerCloudPermission(id, answer, Uuid.random().toString()) }.line())
    }
    private fun indexed(value: String, usage: String): Pair<Int, String> {
        val split = value.indexOf('=')
        val question = value.take(split.coerceAtLeast(0)).trim().toIntOrNull()?.takeIf { split > 0 && it >= 0 } ?: throw UsageError("Use $usage with a question index from 0.")
        return question to value.drop(split + 1)
    }
}

/**
 * `teamcode cloud attach`: a structured attach over the event stream and the message routes, not a terminal. It
 * holds an attachment lease (renewed every 20 s, taken again if it expired), prints the recent transcript and new
 * events, and sends each stdin line as a message, or as the answer to an open question ([AttachRequests]; a picker,
 * [KeyAttach], when input and output are a terminal). `/interrupt` stops the current turn, `/detach` or end of input
 * detaches; the session keeps running. Network failures reconnect from the last printed event.
 */
internal class CloudAttach : SuspendingCliktCommand(name = "attach") {
    override fun help(context: Context) = "Attach to a session: follow its transcript and send each input line as a message. A question of the agent opens " +
        "as a picker on a terminal, one question at a time (arrows, space, enter; left goes back); otherwise a line answers it: an option index " +
        "(several separated by commas for a multi-select question) or your own answer. /allow or /deny [reason] decides a permission request, " +
        "/message TEXT sends a message whatever waits, /interrupt stops the current turn; /detach, Ctrl-C or end of input detaches."
    private val id by argument()
    private val tail by option("--tail", help = "Recent events to show first (default 20).").int().default(20)
    private val readOnly by option("--read-only", help = "Only follow the transcript.").flag()
    private val noWake by option("--no-wake", help = "Do not wake a paused session with messages.").flag()
    override suspend fun run() {
        if (tail !in 0..1_000) throw UsageError("Use --tail between 0 and 1000.")
        sdk(loadConfig()).use { client ->
            var lease = client.attachCloudSession(id)
            val canSend = !readOnly && CloudPermissions.MESSAGE in lease.permissions
            echo("Attached to $id." + if (canSend) " Type a message and press Enter; /interrupt, /detach." else " Read-only.", err = true)
            // Requests and questions still open, so a typed line or a picker can answer them. Only touched from this thread.
            val requests = AttachRequests()
            val last = client.cloudEvents(id, after = 0, limit = 1).lastSeq
            var cursor = (last - tail).coerceAtLeast(0)
            while (cursor < last) {
                val page = client.cloudEvents(id, after = cursor, limit = 200)
                page.events.forEach { echo(it.render()); requests.observe(it) }
                cursor = page.events.lastOrNull()?.seq ?: break
            }
            // A terminal on both ends gets pickers in raw mode (Mordant, every platform); anything else reads lines
            // and answers questions by index.
            val terminal = Terminal()
            val raw = if (canSend && terminal.terminalInfo.interactive) terminal.enterRawModeOrNull() else null
            val keys = raw?.let { KeyAttach(requests, { terminal.updateSize().width }) { text -> terminal.rawPrint(text) } }
            // Raw mode is left before any exit, so the shell gets its terminal back.
            val leave: (Int) -> Nothing = { code -> keys?.close(); raw?.close(); SystemExit.exit(code) }
            val notice: (String) -> Unit = keys?.let { it::print } ?: { echo(it, err = true) }
            if (keys != null) keys.refresh() else if (canSend) requests.prompt()?.let(notice)
            // False when the transcript stopped on an event this version cannot read: detach, then exit 1.
            var readable = true
            coroutineScope {
                val renew = launch {
                    while (isActive) {
                        delay(20_000)
                        lease = try { client.renewCloudAttachment(id, lease.id) }
                            catch (error: CancellationException) { throw error }
                            catch (error: TeamCodeException) {
                                when {
                                    error.statusCode == 403 || error.statusCode == 404 -> revoked(notice, leave)
                                    error.code == "attachment_expired" -> reattach(client, id, notice, leave) ?: lease
                                    else -> lease
                                }
                            }
                            catch (_: Exception) { lease }
                    }
                }
                val follow = launch {
                    readable = followEvents(client, id, cursor, { event ->
                        if (keys != null) { requests.observe(event); keys.print(event.render()) }
                        else { echo(event.render()); requests.observe(event)?.takeIf { canSend }?.let(notice) }
                    }, notice, leave)
                }
                /** Handles one typed line; false detaches. */
                suspend fun line(line: String): Boolean {
                    when {
                        line.isBlank() -> Unit
                        line.trim() == "/detach" -> return false
                        !canSend -> notice("This attachment is read-only.")
                        line.trim() == "/interrupt" -> try { notice(client.interruptCloudSession(id, Uuid.random().toString()).line()) }
                            catch (error: TeamCodeException) { notice("Interrupt refused (${error.code ?: "HTTP ${error.statusCode}"}).") }
                        else -> when (val action = requests.handle(line)) {
                            is AttachAction.Notice -> notice(action.text)
                            is AttachAction.Answer -> answer(client, id, requests, action, notice, prompt = keys == null)
                            is AttachAction.Message -> try {
                                val sent = sendWithRetry(client, id, SendCloudMessageRequest(action.text, wake = !noWake), Uuid.random().toString(), notice)
                                if (sent.woke) notice("The session was paused and is resuming.")
                            } catch (error: TeamCodeException) { notice("Message refused (${error.code ?: "HTTP ${error.statusCode}"}).") }
                        }
                    }
                    return true
                }
                // Not a child of this scope: a blocked read cannot be cancelled and must not hold the detach. It only
                // reads; the input is handled here, on the thread that follows the transcript.
                val pressed = Channel<Key>(Channel.UNLIMITED)
                val lines = Channel<String>()
                val reader = CoroutineScope(Dispatchers.Default).launch {
                    if (raw != null) withContext(Dispatchers.IO) {
                        // Input that cannot be read any more ends the attachment like the end of input.
                        while (isActive) { val event = try { raw.readKey() } catch (_: Exception) { break }; event.toKey()?.let { pressed.send(it) } }
                    } else while (isActive) lines.send(withContext(Dispatchers.IO) { readlnOrNull() } ?: break)
                    pressed.close(); lines.close()
                }
                val input = launch {
                    if (keys == null) { for (typed in lines) if (!line(typed)) break; return@launch }
                    for (key in pressed) {
                        when (val step = keys.feed(key)) {
                            KeyAttach.Step.Detach -> break
                            is KeyAttach.Step.Line -> if (!line(step.text)) break
                            is KeyAttach.Step.Send -> answer(client, id, requests, step.answer, notice, prompt = false)
                            KeyAttach.Step.None -> Unit
                        }
                        keys.refresh()
                    }
                }
                // Whichever ends first (the session ended, or the member detached) ends the attachment.
                select(follow, input)
                renew.cancel(); follow.cancel(); input.cancel(); reader.cancel()
            }
            keys?.close(); raw?.close()
            try { client.detachCloudSession(id, lease.id) } catch (error: CancellationException) { throw error } catch (_: Exception) { }
            echo("Detached; the session keeps running.", err = true)
            if (!readable) SystemExit.exit(1)
        }
        // A blocked stdin read cannot be cancelled; leave without waiting for it.
        SystemExit.exit(0)
    }

    /**
     * Sends a typed answer with one idempotency key, retrying network failures like a message. A refusal that leaves the
     * request open (an index it does not have) lets the member type it again; one that closes it says why.
     */
    private suspend fun answer(client: TeamCodeClient, id: String, requests: AttachRequests, action: AttachAction.Answer, notice: (String) -> Unit, prompt: Boolean) {
        val request = action.request.requestId
        val key = Uuid.random().toString()
        var wait = 1_000L
        repeat(5) { attempt ->
            try {
                val input = client.answerCloudPermission(id, action.request, key)
                requests.sent(request, input.id)
                notice(action.summary)
                return
            } catch (error: CancellationException) { throw error }
            catch (error: TeamCodeException) {
                if (error.statusCode in 400..499 && error.statusCode != 408 && error.statusCode != 429) {
                    if (error.statusCode == 409 || error.statusCode == 404) requests.close(request) else requests.reopen(request)
                    notice("Answer refused (${error.code ?: "HTTP ${error.statusCode}"}).")
                    if (prompt) requests.prompt()?.let(notice)
                    return
                }
            } catch (_: Exception) { }
            if (attempt < 4) { notice("Answering failed; retrying."); delay(wait); wait *= 2 }
        }
        requests.reopen(request)
        notice("The answer could not be sent.")
        if (prompt) requests.prompt()?.let(notice)
    }

    /** A lease removed by a change of owner cannot be taken again: the caller lost access, like a denied stream. Null on a transient error. */
    private suspend fun reattach(client: TeamCodeClient, id: String, notice: (String) -> Unit, leave: (Int) -> Nothing): CloudAttachmentView? =
        try { client.attachCloudSession(id) }
        catch (error: CancellationException) { throw error }
        catch (error: TeamCodeException) { if (error.statusCode == 403 || error.statusCode == 404) revoked(notice, leave) else null }
        catch (_: Exception) { null }

    private fun revoked(notice: (String) -> Unit, leave: (Int) -> Nothing): Nothing {
        notice("Access to this session was revoked.")
        leave(1)
    }

    private suspend fun select(a: Job, b: Job) {
        val done = CompletableDeferred<Unit>()
        a.invokeOnCompletion { done.complete(Unit) }
        b.invokeOnCompletion { done.complete(Unit) }
        done.await()
    }
}
