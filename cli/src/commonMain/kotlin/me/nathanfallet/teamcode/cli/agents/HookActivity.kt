package me.nathanfallet.teamcode.cli.agents

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.*
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource
import me.nathanfallet.teamcode.api.models.RunIdentifiers
import me.nathanfallet.teamcode.client.TeamCodeClient
import me.nathanfallet.teamcode.client.TeamCodeException

/**
 * Whether an agent waits for a person, from its own `Stop`, `Notification`, `UserPromptSubmit` and `PostToolUse` hooks.
 * Only the event name and, for a notification, its type are read: the prompt, the message, the tool and the paths in
 * the payload are dropped here and never reach TeamCode.
 */
internal object HookActivity {
    const val WORKING = RunIdentifiers.ACTIVITY_WORKING
    const val WAITING = RunIdentifiers.ACTIVITY_WAITING
    /** Claude Code's notification types that ask for a person; `auth_success`, an unknown or a missing type do not. */
    private val ASKING = setOf("permission_prompt", "idle_prompt", "elicitation_dialog")

    /** The state this payload reports, or null for an event that says nothing about it (or a payload that is not a hook event). */
    fun state(input: ByteArray): String? = try {
        if (input.size > 65_536) null else {
            val body = Json.parseToJsonElement(input.decodeToString(throwOnInvalidSequence = true)).jsonObject
            fun text(key: String) = (body[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
            when (text("hook_event_name")) {
                "Stop" -> WAITING
                "Notification" -> WAITING.takeIf { text("notification_type") in ASKING }
                "UserPromptSubmit", "PostToolUse", "PostToolUseFailure" -> WORKING
                else -> null
            }
        }
    } catch (error: CancellationException) { throw error }
    catch (_: Exception) { null }

    /** The run identifier a hook may report to: the canonical UUID of the launch, or null. */
    fun runId(value: String?): String? = value?.takeIf { RunIdentifiers.isValid(it) }

    /** Refusals that will repeat for this launch: not yours (403), unknown (404), ended or changed (409). 401, 408 and 429 are retried. */
    private val PERMANENT = setOf(403, 404, 409)

    /** One report, never a diagnostic.  */
    suspend fun report(client: TeamCodeClient, runId: String, state: String): Outcome = try {
        if (withTimeoutOrNull(1_500) { client.reportRunActivity(runId, state); true } == true) Outcome.REPORTED else Outcome.RETRY
    } catch (error: CancellationException) { throw error }
    catch (error: TeamCodeException) { if (error.statusCode in PERMANENT) Outcome.REFUSED else Outcome.RETRY }
    catch (_: Exception) { Outcome.RETRY }
    enum class Outcome { REPORTED, REFUSED, RETRY }
}

/**
 * The local proxy sees every hook of a launch, so a state already reported is not sent again: a tool-heavy turn costs one
 * request, not one per tool. The memory expires after [REMEMBER], so a state the server dropped (a silence) is sent again;
 * a launch the server refused for good (ended, foreign, unknown) is left alone for as long. Bounded: oldest launch first.
 */
internal class ActivityReporter(private val client: TeamCodeClient) {
    private val lock = Mutex()
    private class Seen(val state: String?, val at: TimeMark)
    private val last = LinkedHashMap<String, Seen>()
    private val pendingLock = Mutex()
    private val pending = LinkedHashMap<String, String>()
    /**
     * One report at a time, so two hooks of a launch reach the server in the order they came. A hook that finds a report in
     * flight leaves its state for the one in flight to send after it (the latest per launch wins), so a slow server never
     * piles up one waiting handler per hook.
     */
    suspend fun report(runId: String, state: String) {
        pendingLock.withLock { pending[runId] = state }
        // Enqueue first, then try to be the sender: a state queued while another report finishes is picked up by the loop.
        while (lock.tryLock()) {
            try {
                while (true) {
                    val next = pendingLock.withLock { pending.entries.firstOrNull()?.let { it.key to it.value }?.also { pending.remove(it.first) } } ?: break
                    send(next.first, next.second)
                }
            } finally { lock.unlock() }
            if (pendingLock.withLock { pending.isEmpty() }) return
        }
    }
    private suspend fun send(runId: String, state: String) {
        val seen = last[runId]
        if (seen != null && seen.at.elapsedNow() < REMEMBER && (seen.state == null || seen.state == state)) return
        when (HookActivity.report(client, runId, state)) {
            HookActivity.Outcome.REPORTED -> remember(runId, Seen(state, TimeSource.Monotonic.markNow()))
            HookActivity.Outcome.REFUSED -> remember(runId, Seen(null, TimeSource.Monotonic.markNow()))
            HookActivity.Outcome.RETRY -> Unit
        }
    }
    private fun remember(runId: String, seen: Seen) {
        last.remove(runId); last[runId] = seen
        if (last.size > MAX_RUNS) last.remove(last.keys.first())
    }
    private companion object { const val MAX_RUNS = 256; val REMEMBER = 30.seconds }
}
