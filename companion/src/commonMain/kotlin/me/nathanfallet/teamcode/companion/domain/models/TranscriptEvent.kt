package me.nathanfallet.teamcode.companion.domain.models

/**
 * One transcript event, already bounded and masked by the server. [seq] is the session's own sequence, gap-free from 1;
 * events are told apart and ordered by it alone. Unknown types become [Unknown] instead of failing.
 */
sealed interface TranscriptEvent {
    val seq: Long
    val at: Long

    data class UserMessage(override val seq: Long, override val at: Long, val text: String, val inputId: String?, val authorId: String?) : TranscriptEvent
    data class AgentMessage(override val seq: Long, override val at: Long, val text: String, val parentToolUseId: String?) : TranscriptEvent
    /** [input] is the tool input as indented JSON; [summary] its most telling field (a command, a path). */
    data class ToolCall(
        override val seq: Long, override val at: Long, val id: String, val name: String, val input: String, val summary: String?, val parentToolUseId: String?,
    ) : TranscriptEvent
    data class ToolResult(
        override val seq: Long, override val at: Long, val id: String, val output: String, val isError: Boolean, val exitCode: Int?, val parentToolUseId: String?,
    ) : TranscriptEvent
    data class Turn(override val seq: Long, override val at: Long, val phase: TurnPhase, val durationMs: Long?, val steps: Int?) : TranscriptEvent
    data class Status(override val seq: Long, override val at: Long, val agent: String?, val model: String?, val exitCode: Int?) : TranscriptEvent
    data class Error(override val seq: Long, override val at: Long, val code: String?, val message: String?) : TranscriptEvent
    /**
     * An input that changed status: rejected or cancelled (with [reason]), or a consumed permission answer. A permission
     * answer's event also names the request it answered ([requestId]).
     */
    data class InputChanged(
        override val seq: Long, override val at: Long, val inputId: String?, val status: String?, val reason: String?, val requestId: String? = null,
    ) : TranscriptEvent
    /**
     * The agent waits for a person: a tool use to allow, or a question ([question], `AskUserQuestion`, with [questions]).
     * [input] is the tool input as indented JSON; a request whose input was too large for the event has [inputLeftOut]
     * and only its first characters in [inputPreview], and can only be denied.
     */
    data class PermissionRequest(
        override val seq: Long,
        override val at: Long,
        val requestId: String?,
        val toolName: String?,
        val question: Boolean,
        val questions: List<AgentQuestion> = emptyList(),
        val input: String? = null,
        val inputLeftOut: Boolean = false,
        val inputPreview: String? = null,
        val description: String? = null,
    ) : TranscriptEvent
    data class PermissionExpired(override val seq: Long, override val at: Long, val requestId: String?) : TranscriptEvent
    data class Transferred(override val seq: Long, override val at: Long, val fromMemberId: String?, val toMemberId: String?) : TranscriptEvent
    /**
     * The agent's live background tasks as its runner reported them, the full set each time (`cloud.sessions.background-tasks.v1`);
     * [stoppable]: the runner can stop one; [scheduled]: how many scheduled wake-ups the agent holds (Claude Code reports
     * only their number). An empty set once the last one ended or the agent's process stopped.
     */
    data class Tasks(override val seq: Long, override val at: Long, val tasks: List<BackgroundTask>, val stoppable: Boolean, val scheduled: Int = 0) : TranscriptEvent
    /** An event of more than 60 000 bytes, replaced by the server. */
    data class Truncated(override val seq: Long, override val at: Long, val type: String, val originalBytes: Long?) : TranscriptEvent
    data class Unknown(override val seq: Long, override val at: Long, val type: String) : TranscriptEvent
}

/**
 * One background task of the agent: a subagent (`local_agent`), a background command or monitor (`local_bash`), a remote
 * agent. [toolUseId] is the tool call that started it, when the runner knew; [startedAt] the runner's clock.
 */
data class BackgroundTask(val id: String, val type: String, val description: String, val subagentType: String?, val toolUseId: String?, val startedAt: Long?) {
    val subagent: Boolean get() = type == "local_agent"
}

enum class TurnPhase(val wire: String) {
    STARTED("started"), COMPLETED("completed"), INTERRUPTED("interrupted"), FAILED("failed"), UNKNOWN("unknown");

    /** The turn is over. A phase this version does not know says nothing either way. */
    val finished: Boolean get() = this == COMPLETED || this == INTERRUPTED || this == FAILED

    companion object {
        fun of(wire: String?): TurnPhase = entries.firstOrNull { it.wire == wire && it != UNKNOWN } ?: UNKNOWN
    }
}

/** A page of history, oldest first. [nextBefore] continues upward; null when nothing older is kept. */
data class TranscriptPage(val events: List<TranscriptEvent>, val nextBefore: Long?, val lastSeq: Long)
