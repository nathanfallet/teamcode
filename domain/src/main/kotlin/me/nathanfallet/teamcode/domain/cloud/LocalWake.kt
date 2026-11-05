package me.nathanfallet.teamcode.domain.cloud

import kotlinx.coroutines.delay

/**
 * What the Stop hook of a local session ([CloudSessionKind.LOCAL]) hears from the server: [open] says a wait of the session is still
 * open, so the hook keeps holding the agent; [messages] are the answers (and wake-ups) that arrived, in the order they did.
 */
data class LocalWakeResult(val open: Boolean, val messages: List<String>)

/** The wake of a local session by a wait, for the Stop hook of its `teamcode run` (docs/waits.md, "A local run"). */
fun interface LocalWake {
    /** Waits up to [waitMillis] for something to say; [LocalWakeResult] with `open = false` and no message when the session has no open wait. */
    suspend fun poll(teamId: String, sessionId: String, waitMillis: Long): LocalWakeResult
}

/**
 * A wait's answer reaches a session as a message queued on it ([CloudConversationService.queueWaitAnswer]). A pod or a remote host
 * reads its queue on the runner channel; a local session has no runner: its `teamcode run` hangs on its Stop hook while a wait is
 * open, and this reads the queue for it. A message is handed over once (delivered and acknowledged here, the way a runner does
 * for the agent). What is not read stays queued with the wait's own answer, so a terminal closed meanwhile loses nothing.
 */
class LocalWakeService(
    private val sessions: CloudSessionStore,
    private val conversations: CloudConversationStore,
    private val hasOpenWait: suspend (teamId: String, sessionId: String) -> Boolean,
    private val sleep: suspend (Long) -> Unit = { delay(it) },
) : LocalWake {
    override suspend fun poll(teamId: String, sessionId: String, waitMillis: Long): LocalWakeResult {
        val deadline = sessions.now() + waitMillis.coerceIn(0, MAX_POLL_MILLIS)
        var settled = false
        while (true) {
            val session = sessions.find(teamId, sessionId)
            if (session == null || session.kind != CloudSessionKind.LOCAL || session.state.terminal || session.desiredState == DesiredState.TERMINATED || session.currentEpoch < 1)
                return LocalWakeResult(false, emptyList())
            val inputs = conversations.deliver(sessionId, session.currentEpoch, BATCH).filter { it.kind == CloudInputKind.MESSAGE && it.text != null }
            if (inputs.isNotEmpty()) {
                conversations.acknowledge(sessionId, session.currentEpoch, inputs.map { RunnerInputAck(it.id, true, null) })
                return LocalWakeResult(hasOpenWait(teamId, sessionId), inputs.map { it.text!! })
            }
            if (!hasOpenWait(teamId, sessionId)) {
                // A wait is resolved just before its answer is queued: look once more before saying nothing is expected.
                if (settled) return LocalWakeResult(false, emptyList())
                settled = true
                sleep(POLL_MILLIS)
                continue
            }
            settled = false
            if (sessions.now() >= deadline) return LocalWakeResult(true, emptyList())
            sleep(POLL_MILLIS)
        }
    }

    companion object {
        const val POLL_MILLIS = 1_000L
        const val MAX_POLL_MILLIS = 30_000L
        const val BATCH = 25
    }
}
