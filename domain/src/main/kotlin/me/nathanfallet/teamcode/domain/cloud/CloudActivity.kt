package me.nathanfallet.teamcode.domain.cloud

/**
 * Activity that does not go through the session row: attachment leases and messages waiting for the agent. The
 * reconciler reads it only when a session looks idle, so a busy session costs nothing.
 */
interface CloudActivitySource {
    /**
     * The latest activity time of [sessionId] outside the session row, capped at [now]: [now] while an attachment
     * lease is live or an input waits for the agent, otherwise the end of the latest attachment lease; null when
     * there is none.
     */
    suspend fun lastActivity(sessionId: String, now: Long): Long?
}

/** Queues the message given at creation; implemented by the conversation service. */
fun interface CloudInitialMessages {
    /**
     * Queues [text] on [session] as a message written by [principal]. Idempotent per session and author: a creation
     * replayed with its `Idempotency-Key` queues nothing more.
     */
    suspend fun queue(principal: me.nathanfallet.teamcode.domain.team.Principal, session: CloudSession, text: String)
}

/** The idle-pause rule: a `ready` session that wants to run, with no activity for its idle timeout, is paused. */
object CloudIdleRules {
    fun idleSince(session: CloudSession, external: Long?): Long = maxOf(session.lastActivityAt, external ?: 0L)
    fun timeoutMillis(session: CloudSession): Long = session.idleTimeoutMinutes * 60_000L
    /** Only a prepared session with nothing running is a candidate; a turn, a preparation or a transfer is never idle. */
    fun candidate(session: CloudSession): Boolean =
        session.state == CloudSessionState.READY && session.desiredState == DesiredState.RUNNING && session.transferId == null && session.idleTimeoutMinutes > 0
}
