package me.nathanfallet.teamcode.cli.agents

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.*
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource
import me.nathanfallet.teamcode.api.models.RunIdentifiers
import me.nathanfallet.teamcode.client.TeamCodeClient
import me.nathanfallet.teamcode.client.TeamCodeException

/**
 * Whether an agent waits for a person, from its own `Stop`, `Notification`, `UserPromptSubmit` and `PostToolUse` hooks.
 * Only the event name, for a notification its type, and for a `Stop` whether background work remains are read: the
 * prompt, the message, the tool, the commands and the paths in the payload are dropped here and never reach TeamCode.
 *
 * A Claude Code `Stop` lists the background tasks still in flight (`background_tasks`: background subagents and commands,
 * monitors) and the session's scheduled wake-ups (`session_crons`); while either is non-empty the turn ended but the
 * agent is not waiting for a person (https://code.claude.com/docs/en/hooks#stop-input), so it reports [WORKING].
 */
internal object HookActivity {
    const val WORKING = RunIdentifiers.ACTIVITY_WORKING
    const val WAITING = RunIdentifiers.ACTIVITY_WAITING
    /** Claude Code's notification types that ask for a person; `auth_success`, an unknown or a missing type do not. */
    private val ASKING = setOf("permission_prompt", "idle_prompt", "elicitation_dialog")

    /** The state this payload reports, or null for an event that says nothing about it (or a payload that is not a hook event). */
    fun state(input: ByteArray): String? = try {
        if (input.size > MAXIMUM_PARSED) prefixState(input) else {
            val body = Json.parseToJsonElement(input.decodeToString(throwOnInvalidSequence = true)).jsonObject
            fun text(key: String) = (body[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
            fun listed(key: String) = (body[key] as? JsonArray)?.isNotEmpty() == true
            when (text("hook_event_name")) {
                "Stop" -> if (listed("background_tasks") || listed("session_crons")) WORKING else WAITING
                "Notification" -> WAITING.takeIf { text("notification_type") in ASKING }
                "UserPromptSubmit", "PostToolUse", "PostToolUseFailure" -> WORKING
                else -> null
            }
        }
    } catch (error: CancellationException) { throw error }
    catch (_: Exception) { null }

    private const val MAXIMUM_PARSED = 65_536
    private val EVENT = Regex(""""hook_event_name"\s*:\s*"([A-Za-z]{1,40})"""")
    private val NOTIFICATION = Regex(""""notification_type"\s*:\s*"([a-z_]{1,40})"""")
    /**
     * A non-empty background list as a key of the payload: the quote before the key follows `{`, `,` or a space, which an
     * escaped quote inside a string (a message that quotes the key) never does.
     */
    private val BACKGROUND = Regex("""[{,\s]"(?:background_tasks|session_crons)"\s*:\s*\[\s*\{""")

    /**
     * A payload too large to parse (a big tool result) still changes the state: the event name is read from the head, where
     * Claude Code writes it before the tool input and response. The body itself is never parsed or kept; for a `Stop`, it is
     * only searched for a non-empty background list ([BACKGROUND]), which may follow a long final message.
     */
    private fun prefixState(input: ByteArray): String? {
        val head = input.decodeToString(0, minOf(input.size, 4_096))
        return when (EVENT.find(head)?.groupValues?.get(1)) {
            "Stop" -> if (BACKGROUND.containsMatchIn(input.decodeToString())) WORKING else WAITING
            "Notification" -> WAITING.takeIf { NOTIFICATION.find(head)?.groupValues?.get(1) in ASKING }
            "UserPromptSubmit", "PostToolUse", "PostToolUseFailure" -> WORKING
            else -> null
        }
    }

    /** The run identifier a hook may report to: the canonical UUID of the launch, or null. */
    fun runId(value: String?): String? = value?.takeIf { RunIdentifiers.isValid(it) }

    /** Refusals that will repeat for this launch: not yours (403), unknown (404), ended or changed (409). 401, 408 and 429 are retried. */
    private val PERMANENT = setOf(403, 404, 409)

    /** When a hook fired, by this computer's clock: the server orders reports by it, not by their arrival. */
    fun observedNow(): Long = Clock.System.now().toEpochMilliseconds()

    /** One report, never a diagnostic. [observedAtMillis] is when the hook fired, kept across retries. */
    suspend fun report(client: TeamCodeClient, runId: String, state: String, observedAtMillis: Long = observedNow()): Outcome = try {
        when (withTimeoutOrNull(1_500) { client.reportRunActivity(runId, state, observedAtMillis) }?.superseded) {
            false -> Outcome.REPORTED
            true -> Outcome.SUPERSEDED
            null -> Outcome.RETRY
        }
    } catch (error: CancellationException) { throw error }
    catch (error: TeamCodeException) { if (error.statusCode == 403) Outcome.FOREIGN else if (error.statusCode in PERMANENT) Outcome.REFUSED else Outcome.RETRY }
    catch (_: Exception) { Outcome.RETRY }
    /** [SUPERSEDED]: the server already holds a report observed later, so this one changed nothing. */
    enum class Outcome { REPORTED, SUPERSEDED, REFUSED, FOREIGN, RETRY }
}

/**
 * The local proxy sees every hook of a launch, so a state already reported is not sent again: a tool-heavy turn costs one
 * request, not one per tool. The memory expires after [REMEMBER], so a state the server dropped (a silence) is sent again;
 * a launch the server refused for good (ended, foreign, unknown) is left alone for as long. Bounded: oldest launch first.
 * A temporary failure (timeout, 5xx, 401) is sent again after each of [retryDelays], unless a newer state of the launch
 * arrived meanwhile; then it is dropped and the next event reports again.
 */
internal class ActivityReporter(
    private val client: TeamCodeClient, private val notice: ((String) -> Unit)? = null,
    private val retryDelays: List<Duration> = listOf(2.seconds, 5.seconds),
) {
    private var warned = false
    private val lock = Mutex()
    private class Seen(val state: String?, val at: TimeMark)
    private val last = LinkedHashMap<String, Seen>()
    /** One state per launch, the latest; a retry carries the time its hook fired and waits until [notBefore]. */
    private class Pending(val state: String, val observedAtMillis: Long, val attempt: Int = 0, val notBefore: TimeMark? = null)
    private val pendingLock = Mutex()
    private val pending = LinkedHashMap<String, Pending>()
    /** The latest hook time seen per launch, sent or not. */
    private val newest = LinkedHashMap<String, Long>()
    /** Wakes a sender waiting for a retry when a new state arrives, so it is not held behind the delay. */
    private val arrived = Channel<Unit>(Channel.CONFLATED)
    /**
     * One report at a time, so two hooks of a launch reach the server in the order they came. A hook that finds a report in
     * flight leaves its state for the one in flight to send after it (the latest per launch wins), so a slow server never
     * piles up one waiting handler per hook.
     */
    suspend fun report(runId: String, state: String, observed: Long = HookActivity.observedNow()) {
        // [observed] is when the hook reached the service, before its guidance work: a slow hook that gets here after a later
        // one is older and is dropped, even when the later one was not sent again because its state had already been reported.
        val fresh = pendingLock.withLock {
            if ((newest[runId] ?: Long.MIN_VALUE) > observed) return@withLock false
            newest.remove(runId); newest[runId] = observed
            if (newest.size > MAX_RUNS) newest.remove(newest.keys.first())
            enqueue(runId, Pending(state, observed)); true
        }
        if (!fresh) return
        arrived.trySend(Unit)
        // Enqueue first, then try to be the sender: a state queued while another report finishes is picked up by the loop.
        while (lock.tryLock()) {
            try {
                while (true) {
                    val (next, wait) = pendingLock.withLock { next() } ?: break
                    if (next != null) send(next.first, next.second)
                    else if (wait != null) withTimeoutOrNull(wait) { arrived.receive() }
                }
            } finally { lock.unlock() }
            if (pendingLock.withLock { pending.isEmpty() }) return
        }
    }
    /** The first launch ready to send (removed from the queue), or how long until a retry is due; null once nothing is queued. */
    private fun next(): Pair<Pair<String, Pending>?, Duration?>? {
        if (pending.isEmpty()) return null
        val ready = pending.entries.firstOrNull { it.value.notBefore?.hasPassedNow() != false }
        if (ready != null) { pending.remove(ready.key); return (ready.key to ready.value) to null }
        return null to pending.values.minOf { -it.notBefore!!.elapsedNow() }.coerceAtLeast(Duration.ZERO)
    }
    private fun enqueue(runId: String, entry: Pending) {
        pending.remove(runId); pending[runId] = entry
        if (pending.size > MAX_RUNS) pending.remove(pending.keys.first())
    }
    private suspend fun send(runId: String, entry: Pending) {
        val state = entry.state
        val seen = last[runId]
        if (seen != null && seen.at.elapsedNow() < REMEMBER && (seen.state == null || seen.state == state)) return
        when (HookActivity.report(client, runId, state, entry.observedAtMillis)) {
            HookActivity.Outcome.REPORTED -> remember(runId, Seen(state, TimeSource.Monotonic.markNow()))
            // The server holds a later report of ours, already remembered when it was sent.
            HookActivity.Outcome.SUPERSEDED -> Unit
            HookActivity.Outcome.REFUSED -> remember(runId, Seen(null, TimeSource.Monotonic.markNow()))
            HookActivity.Outcome.FOREIGN -> {
                remember(runId, Seen(null, TimeSource.Monotonic.markNow()))
                if (!warned) { warned = true; notice?.invoke(FOREIGN_NOTICE) }
            }
            HookActivity.Outcome.RETRY -> pendingLock.withLock {
                val delay = retryDelays.getOrNull(entry.attempt)
                // A newer state of the launch replaces this one; it keeps its own hook time, so the server orders them right.
                if (delay != null && runId !in pending)
                    enqueue(runId, Pending(state, entry.observedAtMillis, entry.attempt + 1, TimeSource.Monotonic.markNow() + delay))
            }
        }
    }
    private fun remember(runId: String, seen: Seen) {
        last.remove(runId); last[runId] = seen
        if (last.size > MAX_RUNS) last.remove(last.keys.first())
    }
    private companion object {
        const val FOREIGN_NOTICE = "TeamCode refused this launch's activity reports (HTTP 403): the shared service uses another credential than this launch. " +
            "The team view will not show whether the agent works or waits. Sign in again with the credential the service runs with, or restart the service from this one."
        const val MAX_RUNS = 256; val REMEMBER = 30.seconds }
}
