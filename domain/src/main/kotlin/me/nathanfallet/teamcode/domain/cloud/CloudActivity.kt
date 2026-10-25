package me.nathanfallet.teamcode.domain.cloud

/**
 * Activity that does not go through the session row: attachment leases and messages waiting for the agent. The
 * reconciler reads it only when a session looks idle, so a busy session costs nothing.
 */
interface CloudActivitySource {
    /**
     * The latest activity time of [sessionId] outside the session row, capped at [now]: the end of the latest
     * attachment lease ([now] while one is live), and the time an input still waiting for the agent was queued or
     * last delivered. A waiting input therefore counts once, not forever, so an input stuck on a broken runner
     * cannot keep a session from pausing. Null when there is none.
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

/**
 * The idle-pause rule: a `ready` session that wants to run, with no activity for its idle timeout, is paused. Activity is
 * the session row's (turns, readiness), the [CloudActivitySource]'s (attachments, waiting inputs) and the agent's
 * background work reported by the runner's heartbeats ([backgroundActivity]).
 */
object CloudIdleRules {
    /** The longest idle timeout a profile may set; ended attachment leases are kept at least this long. */
    const val MAX_TIMEOUT_MINUTES = 1440
    /** How long an unbroken stretch of background work keeps a session running by default ([CloudSettings.backgroundWorkMaxMinutes]). */
    const val DEFAULT_BACKGROUND_WORK_MINUTES = 240

    fun idleSince(session: CloudSession, external: Long?, background: Long? = null): Long = maxOf(session.lastActivityAt, external ?: 0L, background ?: 0L)
    fun timeoutMillis(session: CloudSession): Long = session.idleTimeoutMinutes * 60_000L
    /** Only a prepared session with nothing running is a candidate; a turn, a preparation or a transfer is never idle. */
    fun candidate(session: CloudSession): Boolean =
        session.state == CloudSessionState.READY && session.desiredState == DesiredState.RUNNING && session.transferId == null && session.idleTimeoutMinutes > 0

    /**
     * The activity time the agent's background work on [execution] gives: the last heartbeat that reported it, so work
     * still running counts as activity now and work that ended counts from when it was last seen, but never later than
     * [maxMinutes] after the start of its unbroken stretch, so work that never ends (a stuck subagent, a `tail -f`, a
     * week-long `/loop`) keeps the session running for [maxMinutes] plus its idle timeout at most. Null when no heartbeat
     * reported background work or when [maxMinutes] is 0.
     */
    fun backgroundActivity(execution: CloudExecution?, maxMinutes: Int): Long? {
        val seen = execution?.backgroundSeenAt ?: return null
        val since = execution.backgroundSince ?: return null
        if (maxMinutes <= 0) return null
        return minOf(seen, since + maxMinutes * 60_000L)
    }

    /**
     * [execution] as a heartbeat at [now] writes it, given whether the runner reports [background] work. A report right
     * after a heartbeat that also reported it continues the stretch (its start is kept); any other report starts a new
     * one. A heartbeat without background work leaves both fields, so the end of the work is known from the last report.
     */
    fun heartbeat(execution: CloudExecution, background: Boolean, now: Long): CloudExecution {
        if (!background) return execution
        val continued = execution.backgroundSince != null && execution.backgroundSeenAt != null && execution.backgroundSeenAt == execution.lastHeartbeatAt
        return execution.copy(backgroundSince = if (continued) execution.backgroundSince else now, backgroundSeenAt = now)
    }
}
