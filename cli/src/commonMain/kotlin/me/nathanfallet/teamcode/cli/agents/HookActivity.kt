package me.nathanfallet.teamcode.cli.agents

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.*
import me.nathanfallet.teamcode.api.models.RunIdentifiers
import me.nathanfallet.teamcode.client.TeamCodeClient
import me.nathanfallet.teamcode.client.TeamCodeException

/**
 * Whether an agent waits for a person, from its own `Stop`, `Notification`, `UserPromptSubmit` and `PostToolUse` hooks.
 * Only the event name and, for a notification, its type are read: the prompt, the message, the tool and the paths in
 * the payload are dropped here and never reach TeamCode.
 */
internal object HookActivity {
    const val WORKING = "working"
    const val WAITING = "waiting_for_human"
    /** Claude Code's notification types that ask for a person; `auth_success` and others do not. A missing type does too. */
    private val ASKING = setOf("permission_prompt", "idle_prompt", "elicitation_dialog")

    /** The state this payload reports, or null for an event that says nothing about it (or a payload that is not a hook event). */
    fun state(input: ByteArray): String? = try {
        if (input.size > 65_536) null else {
            val body = Json.parseToJsonElement(input.decodeToString(throwOnInvalidSequence = true)).jsonObject
            fun text(key: String) = (body[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
            when (text("hook_event_name")) {
                "Stop" -> WAITING
                "Notification" -> WAITING.takeIf { text("notification_type")?.let { it in ASKING } ?: true }
                "UserPromptSubmit", "PostToolUse" -> WORKING
                else -> null
            }
        }
    } catch (error: CancellationException) { throw error }
    catch (_: Exception) { null }

    /** The run identifier a hook may report to: the canonical UUID of the launch, or null. */
    fun runId(value: String?): String? = value?.takeIf { RunIdentifiers.isValid(it) }

    /** One report, never a diagnostic. A refusal the server will repeat ([TeamCodeException] 4xx) returns true, like a success. */
    suspend fun report(client: TeamCodeClient, runId: String, state: String): Boolean = try {
        withTimeoutOrNull(1_500) { client.reportRunActivity(runId, state); true } ?: false
    } catch (error: CancellationException) { throw error }
    catch (error: TeamCodeException) { error.statusCode in 400..499 }
    catch (_: Exception) { false }
}

/**
 * The local proxy sees every hook of a launch, so a state already reported is not sent again: a tool-heavy turn costs one
 * request, not one per tool. Bounded: one entry per launch, cleared when many launches have passed through.
 */
internal class ActivityReporter(private val client: TeamCodeClient) {
    private val lock = Mutex()
    private val last = mutableMapOf<String, String>()
    suspend fun report(runId: String, state: String) {
        if (lock.withLock { last[runId] } == state) return
        if (HookActivity.report(client, runId, state)) lock.withLock {
            if (last.size >= MAX_RUNS && runId !in last) last.clear()
            last[runId] = state
        }
    }
    private companion object { const val MAX_RUNS = 256 }
}
