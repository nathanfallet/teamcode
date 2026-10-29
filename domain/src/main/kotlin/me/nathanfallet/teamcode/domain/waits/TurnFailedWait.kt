package me.nathanfallet.teamcode.domain.waits

/**
 * The wait the server opens for a session's owner when the agent's turn fails (`kind` `turn_failed`, docs/waits.md): it
 * belongs to nobody who asked (`createdById` null) and is cancelled by the server when the session starts another turn.
 * Retrying wakes the session with the answer message; dismissing only closes the wait.
 */
object TurnFailedWait {
    const val RETRY = "retry"
    const val DISMISS = "dismiss"
    const val NOTE = "note"
    const val TITLE = "The agent's turn failed"
    const val BODY = "The turn ended with an error. Open the session to see what happened, then try again or dismiss this."

    val fields = listOf(WaitField(NOTE, FieldType.TEXT, "Anything to change before trying again?", options = emptyList(), maxLength = 2_000))
    val actions = listOf(WaitAction(RETRY, "Try again", style = "primary"), WaitAction(DISMISS, "Dismiss"))
    fun refs(sessionId: String) = listOf(WaitRef("session", id = sessionId))

    /** Whether this answer is a message for the agent: dismissing a failed turn tells it nothing. */
    fun wakes(wait: Wait, action: String) = wait.kind != WaitKind.TURN_FAILED || action == RETRY
}
