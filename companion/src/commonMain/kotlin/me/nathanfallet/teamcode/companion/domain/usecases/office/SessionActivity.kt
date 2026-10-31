package me.nathanfallet.teamcode.companion.domain.usecases.office

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.nathanfallet.teamcode.companion.domain.models.TranscriptEvent
import me.nathanfallet.teamcode.companion.domain.models.TranscriptPage

/** The agent's own checklist (Claude Code's `TodoWrite`): [done] of [total] items, and the one in progress. */
data class Steps(val done: Int, val total: Int, val current: String?)

/**
 * What an agent is doing, read from the newest events of its transcript: the last tool it used, its checklist, how many
 * subagents run, the last thing it said. Nothing here is a guess: a field is null when the events do not say.
 */
data class SessionActivity(
    val tool: String? = null,
    /** The tool's most telling field (a command, a path), as the server summarised it. */
    val summary: String? = null,
    val steps: Steps? = null,
    val subagents: Int = 0,
    val lastMessage: String? = null,
) {
    companion object {
        const val TODO_TOOL = "TodoWrite"
        private const val MESSAGE_LIMIT = 160

        /** The activity [events] show; they come oldest first, as a transcript page gives them. */
        fun of(events: List<TranscriptEvent>): SessionActivity {
            val call = events.lastOrNull { it is TranscriptEvent.ToolCall && it.name != TODO_TOOL && it.parentToolUseId == null } as? TranscriptEvent.ToolCall
            val todo = events.lastOrNull { it is TranscriptEvent.ToolCall && it.name == TODO_TOOL } as? TranscriptEvent.ToolCall
            val tasks = events.lastOrNull { it is TranscriptEvent.Tasks } as? TranscriptEvent.Tasks
            val message = events.lastOrNull { it is TranscriptEvent.AgentMessage && it.parentToolUseId == null } as? TranscriptEvent.AgentMessage
            return SessionActivity(
                tool = call?.name,
                summary = call?.summary?.trim()?.takeIf { it.isNotEmpty() },
                steps = todo?.let { steps(it.input) },
                subagents = tasks?.tasks?.count { it.subagent } ?: 0,
                lastMessage = message?.text?.trim()?.takeIf { it.isNotEmpty() }?.let { if (it.length > MESSAGE_LIMIT) it.take(MESSAGE_LIMIT).trimEnd() + "…" else it },
            )
        }

        /** The checklist of a `TodoWrite` input (`{"todos":[{"content","status"}]}`); null when it is not one. */
        internal fun steps(input: String): Steps? {
            val items = try {
                (Json.parseToJsonElement(input).jsonObject["todos"] as? JsonArray)?.map { it.jsonObject }
            } catch (_: IllegalArgumentException) {
                // Not JSON, or not the expected shape: the agent's tool changed or the input was cut. No checklist.
                return null
            } ?: return null
            if (items.isEmpty()) return null
            val done = items.count { it.text("status") == "completed" }
            val current = items.firstOrNull { it.text("status") == "in_progress" }?.let { it.text("activeForm") ?: it.text("content") }
            return Steps(done, items.size, current)
        }

        private fun JsonObject.text(key: String): String? = try { this[key]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() } } catch (_: IllegalArgumentException) { null }
    }
}

/**
 * The activity of the sessions the member looks at, read one session at a time when asked ([peek]) and kept a little:
 * hovering an agent costs one request, never one per agent. A session read less than [freshMillis] ago is not read again,
 * and a failed read is kept as "nothing known" for as long, so a server without transcripts is asked once, not on every hover.
 */
class ActivityPeeks(
    private val load: suspend (accountId: String, sessionId: String) -> TranscriptPage,
    private val scope: CoroutineScope,
    private val now: () -> Long,
    private val freshMillis: Long = 20_000,
    private val capacity: Int = 64,
) {
    private class Entry(val at: Long, val activity: SessionActivity?)

    private val entries = MutableStateFlow<Map<String, Entry>>(emptyMap())
    private val reading = HashSet<String>()

    private val activities = MutableStateFlow<Map<String, SessionActivity>>(emptyMap())

    /** The activity known of each session read, by session id. */
    val known: StateFlow<Map<String, SessionActivity>> = activities.asStateFlow()

    /** Reads [sessionId]'s newest events unless they were read lately or are being read. */
    fun peek(accountId: String, sessionId: String) {
        val key = "$accountId/$sessionId"
        val at = now()
        val current = entries.value[key]
        if (current != null && at - current.at < freshMillis) return
        if (!reading.add(key)) return
        scope.launch {
            val activity = try {
                SessionActivity.of(load(accountId, sessionId).events)
            } catch (cancelled: CancellationException) {
                reading.remove(key)
                throw cancelled
            } catch (_: Exception) {
                // Unsupported, gone, offline: nothing is known and nothing is shown; the next look after the delay tries again.
                null
            }
            reading.remove(key)
            entries.update { map ->
                val next = map + (key to Entry(now(), activity))
                if (next.size <= capacity) next else next.entries.sortedBy { it.value.at }.drop(next.size - capacity).associate { it.key to it.value }
            }
            activities.value = entries.value.mapNotNull { (k, entry) -> entry.activity?.let { k.substringAfter('/') to it } }.toMap()
        }
    }

    /** Forgets everything read: another account is shown. */
    fun clear() {
        entries.value = emptyMap()
        activities.value = emptyMap()
    }
}
