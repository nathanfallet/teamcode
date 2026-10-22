package me.nathanfallet.teamcode.companion.presentation.transcript

import me.nathanfallet.teamcode.companion.domain.models.TranscriptEvent
import me.nathanfallet.teamcode.companion.domain.models.TurnPhase

/** What the transcript list shows, built from the events; [key] is stable across merges (a sequence number). */
sealed interface TranscriptItem {
    val key: Long
    /** Nested under a subagent's tool call. */
    val nested: Boolean

    data class User(val event: TranscriptEvent.UserMessage) : TranscriptItem {
        override val key get() = event.seq
        override val nested get() = false
    }
    data class Agent(val event: TranscriptEvent.AgentMessage) : TranscriptItem {
        override val key get() = event.seq
        override val nested get() = event.parentToolUseId != null
    }
    /** A tool use and its result, paired by id; [call] is null when the call is in a page not loaded yet. */
    data class Tool(val call: TranscriptEvent.ToolCall?, val result: TranscriptEvent.ToolResult?) : TranscriptItem {
        override val key get() = call?.seq ?: result!!.seq
        override val nested get() = (call?.parentToolUseId ?: result?.parentToolUseId) != null
    }
    /** Consecutive tool uses with no text between them, shown folded as "N actions". */
    data class Tools(val tools: List<Tool>) : TranscriptItem {
        override val key get() = tools.first().key
        override val nested get() = tools.first().nested
    }
    /** The end of a turn: its duration and steps, and whether it failed or was interrupted. */
    data class TurnEnd(val event: TranscriptEvent.Turn) : TranscriptItem {
        override val key get() = event.seq
        override val nested get() = false
    }
    /** A centered system line: agent started, error, rejected message, permission request, transfer. */
    data class System(val event: TranscriptEvent) : TranscriptItem {
        override val key get() = event.seq
        override val nested get() = false
    }
}

/**
 * Builds the list: tool results join their call, runs of tool uses at the same level fold together, turn starts and
 * unknown types are left out (the running indicator shows a turn in progress), and plain status changes of inputs
 * that were consumed are skipped (the message itself shows).
 */
fun transcriptItems(events: List<TranscriptEvent>): List<TranscriptItem> {
    val results = events.filterIsInstance<TranscriptEvent.ToolResult>().associateBy { it.id }
    val calls = events.filterIsInstance<TranscriptEvent.ToolCall>().map { it.id }.toSet()
    val items = mutableListOf<TranscriptItem>()
    var run = mutableListOf<TranscriptItem.Tool>()
    fun flush() {
        when (run.size) {
            0 -> Unit
            1 -> items += run.single()
            else -> items += TranscriptItem.Tools(run)
        }
        run = mutableListOf()
    }
    for (event in events) {
        val item: TranscriptItem? = when (event) {
            is TranscriptEvent.ToolCall -> TranscriptItem.Tool(event, results[event.id].takeIf { event.id.isNotEmpty() })
            // Shown with its call; alone only when the call is not loaded.
            is TranscriptEvent.ToolResult -> if (event.id.isNotEmpty() && event.id in calls) null else TranscriptItem.Tool(null, event)
            is TranscriptEvent.UserMessage -> TranscriptItem.User(event)
            is TranscriptEvent.AgentMessage -> if (event.text.isBlank()) null else TranscriptItem.Agent(event)
            is TranscriptEvent.Turn -> if (event.phase == TurnPhase.STARTED) null else TranscriptItem.TurnEnd(event)
            is TranscriptEvent.InputChanged -> if (event.status == "rejected" || event.status == "cancelled") TranscriptItem.System(event) else null
            is TranscriptEvent.Unknown -> null
            else -> TranscriptItem.System(event)
        }
        when {
            item == null -> Unit
            item is TranscriptItem.Tool && (run.isEmpty() || run.last().nested == item.nested) -> run += item
            item is TranscriptItem.Tool -> { flush(); run += item }
            else -> { flush(); items += item }
        }
    }
    flush()
    return items
}
