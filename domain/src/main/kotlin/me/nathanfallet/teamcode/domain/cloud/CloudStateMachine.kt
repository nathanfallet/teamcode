package me.nathanfallet.teamcode.domain.cloud

import me.nathanfallet.teamcode.domain.cloud.CloudSessionState.*
import me.nathanfallet.teamcode.domain.team.TeamFailure

/** The documented transition table. A same-state write only changes phase or bookkeeping. */
object CloudStateMachine {
    private val allowed: Map<CloudSessionState, Set<CloudSessionState>> = mapOf(
        CREATING to setOf(PREPARING, FAILED, TERMINATED),
        PREPARING to setOf(READY, FAILED, TERMINATED),
        READY to setOf(ACTIVE, PAUSING, PAUSED, TERMINATED),
        ACTIVE to setOf(READY, PAUSING, PAUSED, TERMINATED),
        PAUSING to setOf(PAUSED, TERMINATED),
        // Ownership changes only at a paused boundary: no pod, every execution ended.
        PAUSED to setOf(RESUMING, TERMINATED, TRANSFERRING),
        RESUMING to setOf(READY, PAUSED, FAILED, TERMINATED),
        TRANSFERRING to setOf(PAUSED, RESUMING, TERMINATED),
        FAILED to setOf(TERMINATED),
        TERMINATED to emptySet(),
    )
    fun permits(from: CloudSessionState, to: CloudSessionState) = from == to || to in allowed.getValue(from)
    fun require(from: CloudSessionState, to: CloudSessionState) {
        if (!permits(from, to)) throw TeamFailure("invalid_transition", 409, "A cloud session cannot move from ${from.wire} to ${to.wire}.")
    }
}
