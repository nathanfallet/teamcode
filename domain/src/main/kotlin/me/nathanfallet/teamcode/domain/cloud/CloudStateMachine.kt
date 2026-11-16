package me.nathanfallet.teamcode.domain.cloud

import me.nathanfallet.teamcode.domain.cloud.CloudSessionState.*
import me.nathanfallet.teamcode.domain.team.TeamFailure

/** The documented transition table. A same-state write only changes phase or bookkeeping. */
object CloudStateMachine {
    private val allowed: Map<CloudSessionState, Set<CloudSessionState>> = mapOf(
        CREATING to setOf(PREPARING, FAILED, TERMINATED),
        PREPARING to setOf(READY, FAILED, TERMINATED),
        // To resuming: a remote session's host started again, and its new execution replaces the live one.
        READY to setOf(ACTIVE, PAUSING, PAUSED, RESUMING, TERMINATED),
        ACTIVE to setOf(READY, PAUSING, PAUSED, RESUMING, TERMINATED),
        // Back to ready: a resume cancelled the pause before the runner checkpointed.
        PAUSING to setOf(READY, PAUSED, TERMINATED),
        // Ownership changes only at a paused boundary: no pod, every execution ended.
        PAUSED to setOf(RESUMING, TERMINATED, TRANSFERRING),
        RESUMING to setOf(READY, PAUSED, FAILED, TERMINATED),
        TRANSFERRING to setOf(PAUSED, RESUMING, TERMINATED),
        FAILED to setOf(TERMINATED),
        TERMINATED to emptySet(),
    )
    fun permits(from: CloudSessionState, to: CloudSessionState) = from == to || to in allowed.getValue(from)

    /**
     * The one way back out of `terminated`: a remote session the device refused ([CloudSessionService.HOST_REFUSED]) never ran,
     * and its conversation is still on the device, so its owner may reopen it (`paused`, then a resume like any other). Nothing
     * else leaves `terminated`: a session the member deleted, or that expired, stays ended.
     */
    fun reopenable(session: CloudSession) = session.state == TERMINATED && session.kind == CloudSessionKind.REMOTE && session.terminatedReason == CloudSessionService.HOST_REFUSED
    fun require(from: CloudSessionState, to: CloudSessionState) {
        if (!permits(from, to)) throw TeamFailure("invalid_transition", 409, "A cloud session cannot move from ${from.wire} to ${to.wire}.")
    }
}
