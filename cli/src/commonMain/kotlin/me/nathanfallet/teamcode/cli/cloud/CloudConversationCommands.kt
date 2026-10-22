package me.nathanfallet.teamcode.cli.cloud

import com.github.ajalt.clikt.command.SuspendingCliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.*
import com.github.ajalt.clikt.parameters.types.int
import com.github.ajalt.clikt.parameters.types.long
import kotlin.time.Instant
import kotlin.uuid.Uuid
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import me.nathanfallet.teamcode.api.Serialization
import me.nathanfallet.teamcode.api.models.*
import me.nathanfallet.teamcode.cli.SystemExit
import me.nathanfallet.teamcode.cli.loadConfig
import me.nathanfallet.teamcode.cli.sdk
import me.nathanfallet.teamcode.client.CloudStreamEnd
import me.nathanfallet.teamcode.client.TeamCodeClient
import me.nathanfallet.teamcode.client.TeamCodeException

private fun JsonObject.text(name: String) = (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.content
private fun String.preview(limit: Int) = replace('\n', ' ').let { if (it.length > limit) it.take(limit - 1) + "…" else it }

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
        CloudEventTypes.INPUT -> "[${data.text("kind") ?: "message"} ${data.text("inputId")?.take(8) ?: ""} ${data.text("status") ?: ""}${data.text("reason")?.let { ": $it" } ?: ""}]"
        else -> "[$type] $data"
    }
    return "$time #$seq ${if (data.text("parentToolUseId") != null) "  ↳ " else ""}$body"
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
        require(tail in 0..10_000) { "Use --tail between 0 and 10000." }
        sdk(loadConfig()).use { client ->
            val print: (CloudEventView) -> Unit = { echo(if (json) Serialization.json.encodeToString(it) else it.render()) }
            var cursor = after ?: (client.cloudEvents(id, after = 0, limit = 1).lastSeq - tail).coerceAtLeast(0)
            while (true) {
                val page = client.cloudEvents(id, after = cursor, limit = 200)
                page.events.forEach(print)
                cursor = page.events.lastOrNull()?.seq ?: cursor
                if (page.nextAfter == null) break
            }
            if (follow && !followEvents(client, id, cursor, print) { echo(it, err = true) }) SystemExit.exit(1)
        }
    }
}

/**
 * Follows the event stream from [after], reconnecting with backoff and resuming from the last printed sequence, so
 * no event is printed twice or skipped. Returns `true` when the session ended and `false` when an event cannot be read
 * (reconnecting would replay it), so `attach` still detaches; exits when access was revoked.
 */
internal suspend fun followEvents(client: TeamCodeClient, id: String, after: Long, print: (CloudEventView) -> Unit, notice: (String) -> Unit): Boolean {
    var cursor = after
    var wait = 1_000L
    while (true) {
        try {
            val end = client.followCloudEvents(id, cursor) { event -> if (event.seq > cursor) { print(event); cursor = event.seq; wait = 1_000L } }
            when (end) {
                CloudStreamEnd.ENDED -> { notice("The session has ended."); return true }
                CloudStreamEnd.DENIED -> { notice("Access to this session was revoked."); SystemExit.exit(1) }
            }
        } catch (error: CancellationException) { throw error }
        catch (error: TeamCodeException) {
            if (error.statusCode in setOf(401, 403, 404)) { notice("The server refused the transcript (HTTP ${error.statusCode}${error.code?.let { ", $it" } ?: ""})."); SystemExit.exit(1) }
            // Reconnecting would replay the same event from the same cursor.
            if (error.code == TeamCodeClient.MALFORMED_EVENT) {
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

/**
 * `teamcode cloud attach`: a structured attach over the event stream and the message routes, not a terminal. It
 * holds an attachment lease (renewed every 20 s, taken again if it expired), prints the recent transcript and new
 * events, and sends each stdin line as a message. `/interrupt` stops the current turn, `/detach` or end of input
 * detaches; the session keeps running. Network failures reconnect from the last printed event.
 */
internal class CloudAttach : SuspendingCliktCommand(name = "attach") {
    override fun help(context: Context) = "Attach to a session: follow its transcript and send each input line as a message. /interrupt stops the current turn; /detach or end of input detaches."
    private val id by argument()
    private val tail by option("--tail", help = "Recent events to show first (default 20).").int().default(20)
    private val readOnly by option("--read-only", help = "Only follow the transcript.").flag()
    private val noWake by option("--no-wake", help = "Do not wake a paused session with messages.").flag()
    override suspend fun run() {
        require(tail in 0..1_000) { "Use --tail between 0 and 1000." }
        sdk(loadConfig()).use { client ->
            var lease = client.attachCloudSession(id)
            val canSend = !readOnly && CloudPermissions.MESSAGE in lease.permissions
            echo("Attached to $id." + if (canSend) " Type a message and press Enter; /interrupt, /detach." else " Read-only.", err = true)
            val notice: (String) -> Unit = { echo(it, err = true) }
            val last = client.cloudEvents(id, after = 0, limit = 1).lastSeq
            var cursor = (last - tail).coerceAtLeast(0)
            while (cursor < last) {
                val page = client.cloudEvents(id, after = cursor, limit = 200)
                page.events.forEach { echo(it.render()) }
                cursor = page.events.lastOrNull()?.seq ?: break
            }
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
                                    error.statusCode == 403 || error.statusCode == 404 -> revoked(notice)
                                    error.code == "attachment_expired" -> reattach(client, id, notice) ?: lease
                                    else -> lease
                                }
                            }
                            catch (_: Exception) { lease }
                    }
                }
                val follow = launch { readable = followEvents(client, id, cursor, { echo(it.render()) }, notice) }
                // Not a child of this scope: a blocked stdin read cannot be cancelled and must not hold the detach.
                val input = CoroutineScope(Dispatchers.Default).launch {
                    while (isActive) {
                        val line = withContext(Dispatchers.IO) { readlnOrNull() } ?: break
                        when {
                            line.isBlank() -> Unit
                            line.trim() == "/detach" -> break
                            !canSend -> notice("This attachment is read-only.")
                            line.trim() == "/interrupt" -> try { notice(client.interruptCloudSession(id, Uuid.random().toString()).line()) }
                                catch (error: TeamCodeException) { notice("Interrupt refused (${error.code ?: "HTTP ${error.statusCode}"}).") }
                            else -> try {
                                val sent = sendWithRetry(client, id, SendCloudMessageRequest(line, wake = !noWake), Uuid.random().toString(), notice)
                                if (sent.woke) notice("The session was paused and is resuming.")
                            } catch (error: TeamCodeException) { notice("Message refused (${error.code ?: "HTTP ${error.statusCode}"}).") }
                        }
                    }
                }
                // Whichever ends first (the session ended, or the member detached) ends the attachment.
                select(follow, input)
                renew.cancel(); follow.cancel(); input.cancel()
            }
            try { client.detachCloudSession(id, lease.id) } catch (error: CancellationException) { throw error } catch (_: Exception) { }
            echo("Detached; the session keeps running.", err = true)
            if (!readable) SystemExit.exit(1)
        }
        // A blocked stdin read cannot be cancelled; leave without waiting for it.
        SystemExit.exit(0)
    }

    /** A lease removed by a change of owner cannot be taken again: the caller lost access, like a denied stream. Null on a transient error. */
    private suspend fun reattach(client: TeamCodeClient, id: String, notice: (String) -> Unit): CloudAttachmentView? =
        try { client.attachCloudSession(id) }
        catch (error: CancellationException) { throw error }
        catch (error: TeamCodeException) { if (error.statusCode == 403 || error.statusCode == 404) revoked(notice) else null }
        catch (_: Exception) { null }

    private fun revoked(notice: (String) -> Unit): Nothing {
        notice("Access to this session was revoked.")
        SystemExit.exit(1)
    }

    private suspend fun select(a: Job, b: Job) {
        val done = CompletableDeferred<Unit>()
        a.invokeOnCompletion { done.complete(Unit) }
        b.invokeOnCompletion { done.complete(Unit) }
        done.await()
    }
}
