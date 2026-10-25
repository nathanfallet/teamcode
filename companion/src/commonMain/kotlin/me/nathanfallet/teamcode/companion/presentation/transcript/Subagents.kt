package me.nathanfallet.teamcode.companion.presentation.transcript

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import me.nathanfallet.teamcode.companion.domain.models.BackgroundTask
import me.nathanfallet.teamcode.companion.domain.models.TranscriptEvent

/** The tools that start a subagent: `Agent`, and `Task`, its name before Claude Code renamed it. */
val AGENT_TOOLS = setOf("Agent", "Task")

enum class SubagentState {
    RUNNING, DONE, FAILED,
    /** No result and its turn ended: interrupted, or its agent stopped. */
    STOPPED,
    /** Launched in the background, and the runner reports no tasks: whether it still runs is unknown. */
    BACKGROUND,
}

/**
 * One subagent, read from the transcript: [id] is its `Agent` call's id, which every event it produced carries as
 * `parentToolUseId`; [type] its subagent type (`general-purpose` when none was named); [depth] 0 for one the main agent
 * started, 1 for a subagent's own, and so on; [steps] its own tool uses; [taskId] its background task, to stop it.
 */
data class Subagent(
    val id: String,
    val type: String,
    val description: String,
    val state: SubagentState,
    val startedAt: Long,
    val durationMs: Long,
    val depth: Int,
    val parentId: String?,
    val steps: Int,
    val taskId: String?,
    /** Running, with every tool use still open a wait ([watches]): parked on a wait rather than working. */
    val waiting: Boolean = false,
) {
    val active: Boolean get() = state == SubagentState.RUNNING || state == SubagentState.BACKGROUND
}

/** The runner's latest report for a running session: its live [tasks], whether it can stop one, its scheduled wake-ups. */
data class LiveTasks(val tasks: List<BackgroundTask>, val stoppable: Boolean, val scheduled: Int)

/**
 * A background command, or a monitor ([monitor]: started by the Monitor tool, or a command that only waits, [watches]),
 * with the [command] its call ran when loaded.
 */
data class BackgroundCommand(val task: BackgroundTask, val command: String?, val monitor: Boolean)

private val lenient = Json { ignoreUnknownKeys = true }

/**
 * A command that only waits: `sleep N` alone, an `until`/`while` loop whose body starts with `sleep`, `watch`,
 * `gh run watch`, `gh pr checks --watch`. `kubectl wait`, `sleep 2 && ./gradlew build` or `while read f; do …` are work.
 * The classification of the `cr-orchestre` panel (its `WATCHER`), kept identical.
 */
private val WATCHER = Regex("""^\s*(sleep\s+[\d.]+[smh]?\s*$|(until|while)\b[^;]*;\s*do\s+sleep\b|watch\b|gh run watch\b|gh pr checks\b.*--watch)""")

/** A tool use that only waits: the Monitor tool, or a Bash command matching [WATCHER]. */
fun watches(call: TranscriptEvent.ToolCall): Boolean =
    call.name == "Monitor" || (call.name == "Bash" && call.inputObject()?.text("command")?.let { WATCHER.containsMatchIn(it) } == true)

private fun TranscriptEvent.ToolCall.inputObject(): JsonObject? = try { lenient.parseToJsonElement(input) as? JsonObject } catch (_: IllegalArgumentException) { null }
private fun JsonObject.text(key: String) = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

/**
 * Every subagent the loaded events show, in the order they started. [running]: the session's agent runs (ready or
 * active); [tasks]: the runner's live tasks, null when it reports none (an older runner or server); [now] the clock.
 */
fun subagentsOf(events: List<TranscriptEvent>, running: Boolean, tasks: List<BackgroundTask>?, now: Long): List<Subagent> {
    val calls = events.filterIsInstance<TranscriptEvent.ToolCall>().filter { it.name in AGENT_TOOLS && it.id.isNotEmpty() }
    if (calls.isEmpty()) return emptyList()
    val ids = calls.map { it.id }.toSet()
    val results = events.filterIsInstance<TranscriptEvent.ToolResult>().filter { it.id in ids }.associateBy { it.id }
    val parents = calls.associate { it.id to it.parentToolUseId }
    // Where each subagent's own events end, and how many tool uses it made.
    val last = mutableMapOf<String, Long>()
    val steps = mutableMapOf<String, Int>()
    for (event in events) {
        val parent = when (event) {
            is TranscriptEvent.ToolCall -> event.parentToolUseId?.also { steps[it] = (steps[it] ?: 0) + 1 }
            is TranscriptEvent.ToolResult -> event.parentToolUseId
            is TranscriptEvent.AgentMessage -> event.parentToolUseId
            else -> null
        } ?: continue
        last[parent] = event.at
    }
    val answered = events.filterIsInstance<TranscriptEvent.ToolResult>().map { it.id }.toSet()
    val open = events.filterIsInstance<TranscriptEvent.ToolCall>().filter { it.parentToolUseId != null && it.id !in answered }.groupBy { it.parentToolUseId }
    val turnEnds = events.filterIsInstance<TranscriptEvent.Turn>().filter { it.phase.finished }.map { it.seq }
    return calls.map { call ->
        val input = call.inputObject()
        val background = (input?.get("run_in_background") as? JsonPrimitive)?.booleanOrNull == true
        val result = results[call.id]
        val task = tasks?.firstOrNull { it.subagent && it.toolUseId == call.id }
        val state = when {
            task != null -> SubagentState.RUNNING
            result != null && result.isError -> SubagentState.FAILED
            result != null && background -> if (tasks != null || !running) SubagentState.DONE else SubagentState.BACKGROUND
            result != null -> SubagentState.DONE
            turnEnds.any { it > call.seq } || !running -> SubagentState.STOPPED
            else -> SubagentState.RUNNING
        }
        val start = task?.startedAt?.coerceAtMost(call.at) ?: call.at
        val end = when (state) {
            SubagentState.RUNNING, SubagentState.BACKGROUND -> now
            // A background subagent's result only said it was launched: it ended with its last own event.
            else -> if (background) maxOf(result?.at ?: call.at, last[call.id] ?: call.at) else result?.at ?: last[call.id] ?: call.at
        }
        var depth = 0
        var parent = call.parentToolUseId
        while (parent != null && parent in ids && depth < MAX_DEPTH) { depth++; parent = parents[parent] }
        Subagent(
            id = call.id, type = input?.text("subagent_type")?.takeIf { it.isNotBlank() } ?: DEFAULT_TYPE,
            description = input?.text("description")?.takeIf { it.isNotBlank() } ?: call.summary.orEmpty(),
            state = state, startedAt = start, durationMs = (end - start).coerceAtLeast(0), depth = depth, parentId = call.parentToolUseId,
            steps = steps[call.id] ?: 0, taskId = task?.id,
            waiting = state == SubagentState.RUNNING && open[call.id].orEmpty().let { calls -> calls.isNotEmpty() && calls.all(::watches) },
        )
    }
}

/**
 * The events of one thread: the main agent's ([thread] null: no `parentToolUseId`), or the subagent [thread]'s own,
 * shown at the top level of its thread. A subagent's own subagents show there as cards, their steps in their own thread.
 */
fun threadEvents(events: List<TranscriptEvent>, thread: String?): List<TranscriptEvent> = if (thread == null) events.filter { it.parent() == null }
    else events.mapNotNull { event ->
        if (event.parent() != thread) return@mapNotNull null
        when (event) {
            is TranscriptEvent.ToolCall -> event.copy(parentToolUseId = null)
            is TranscriptEvent.ToolResult -> event.copy(parentToolUseId = null)
            is TranscriptEvent.AgentMessage -> event.copy(parentToolUseId = null)
            else -> event
        }
    }

private fun TranscriptEvent.parent(): String? = when (this) {
    is TranscriptEvent.ToolCall -> parentToolUseId
    is TranscriptEvent.ToolResult -> parentToolUseId
    is TranscriptEvent.AgentMessage -> parentToolUseId
    else -> null
}

/** The runner's latest task report, null when the loaded events hold none; nothing runs in a session that is not [running]. */
fun liveTasks(events: List<TranscriptEvent>, running: Boolean): LiveTasks? {
    val report = events.lastOrNull { it is TranscriptEvent.Tasks } as TranscriptEvent.Tasks? ?: return null
    return if (running) LiveTasks(report.tasks, report.stoppable, report.scheduled) else LiveTasks(emptyList(), report.stoppable, 0)
}

/** The background commands and monitors among [tasks] (subagents are listed as subagents), with the command each call ran. */
fun backgroundCommands(tasks: List<BackgroundTask>, events: List<TranscriptEvent>): List<BackgroundCommand> {
    val wanted = tasks.mapNotNull { it.toolUseId }.toSet()
    val calls = if (wanted.isEmpty()) emptyMap() else events.filterIsInstance<TranscriptEvent.ToolCall>().filter { it.id in wanted }.associateBy { it.id }
    return tasks.filter { !it.subagent }.map { task ->
        val call = task.toolUseId?.let(calls::get)
        BackgroundCommand(task, call?.summary, call != null && watches(call))
    }
}

private const val DEFAULT_TYPE = "general-purpose"
private const val MAX_DEPTH = 16
