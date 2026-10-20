package me.nathanfallet.teamcode.domain.cloud

import me.nathanfallet.teamcode.domain.cloud.CloudSessionState.*
import me.nathanfallet.teamcode.domain.team.TeamFailure

/** The documented transition table. A same-state write only changes phase or bookkeeping. */
object CloudStateMachine {
    private val allowed: Map<CloudSessionState, Set<CloudSessionState>> = mapOf(
        CREATING to setOf(PREPARING, FAILED, TERMINATED, TRANSFERRING),
        PREPARING to setOf(READY, FAILED, TERMINATED, TRANSFERRING),
        READY to setOf(ACTIVE, PAUSING, PAUSED, TERMINATED, TRANSFERRING),
        ACTIVE to setOf(READY, PAUSING, PAUSED, TERMINATED, TRANSFERRING),
        PAUSING to setOf(PAUSED, TERMINATED, TRANSFERRING),
        PAUSED to setOf(RESUMING, TERMINATED, TRANSFERRING),
        RESUMING to setOf(READY, PAUSED, FAILED, TERMINATED, TRANSFERRING),
        TRANSFERRING to setOf(PAUSED, RESUMING, TERMINATED),
        FAILED to setOf(TERMINATED),
        TERMINATED to emptySet(),
    )
    fun permits(from: CloudSessionState, to: CloudSessionState) = from == to || to in allowed.getValue(from)
    fun require(from: CloudSessionState, to: CloudSessionState) {
        if (!permits(from, to)) throw TeamFailure("invalid_transition", 409, "A cloud session cannot move from ${from.wire} to ${to.wire}.")
    }
}
