package me.nathanfallet.teamcode.domain.cloud

import me.nathanfallet.teamcode.domain.cloud.CloudSessionState.*
import me.nathanfallet.teamcode.domain.team.TeamFailure

/** What the server tells the runner on each heartbeat. */
data class RunnerInstruction(val desiredState: DesiredState, val pauseDeadline: Long?, val epoch: Int)

/**
 * The runner side of the lifecycle. Every call is bound to `(session, epoch)`; a call for anything other than
 * the current, live execution is fenced with `409 execution_fenced`, and the runner must then stop its agent
 * and exit without checkpointing.
 */
class RunnerChannelUseCase(private val store: CloudSessionStore, private val transition: TransitionCloudSessionUseCase = TransitionCloudSessionUseCase(store)) {
    suspend fun register(teamId: String, sessionId: String, epoch: Int, podName: String): RunnerInstruction {
        val session = transition(teamId, sessionId) { current ->
            val execution = live(current, epoch)
            if (execution.podName != podName) throw fenced()
            if (execution.state == ExecutionState.RUNNING) return@transition null
            val now = store.now()
            val state = if (current.state == CREATING) PREPARING else current.state
            if (state != PREPARING && state != RESUMING) throw fenced()
            CloudTransition(current.copy(state = state, phase = if (state == PREPARING) "starting_runner" else "restoring", updatedAt = now),
                CloudWrite(updateExecution = execution.copy(state = ExecutionState.RUNNING, registeredAt = now, lastHeartbeatAt = now), reconcileAt = now))
        } ?: throw fenced()
        return instruction(session)
    }

    /** Liveness plus readiness. `ready = true` ends preparation or restoration: the session becomes `ready`. */
    suspend fun heartbeat(teamId: String, sessionId: String, epoch: Int, ready: Boolean): RunnerInstruction {
        val current = store.find(teamId, sessionId) ?: throw fenced()
        val execution = live(current, epoch)
        if (execution.state != ExecutionState.RUNNING || !store.heartbeat(sessionId, epoch, ready)) throw fenced()
        val session = if (ready && current.desiredState == DesiredState.RUNNING && (current.state == PREPARING || current.state == RESUMING)) {
            transition(teamId, sessionId) { fresh ->
                live(fresh, epoch)
                if (fresh.state != PREPARING && fresh.state != RESUMING) return@transition null
                val now = store.now()
                CloudTransition(fresh.copy(state = READY, phase = null, failure = null, lastActivityAt = now, updatedAt = now),
                    CloudWrite(auditAction = if (fresh.state == RESUMING) "cloud.session.ready_resumed" else "cloud.session.ready", reconcileAt = now))
            } ?: throw fenced()
        } else current
        return instruction(session)
    }

    /** Accepted only while pausing; the reconciler then deletes the pod and advertises the checkpoint quality. */
    suspend fun checkpoint(teamId: String, sessionId: String, epoch: Int, completed: Boolean) {
        transition(teamId, sessionId) { current ->
            val execution = live(current, epoch)
            if (current.state != PAUSING) throw TeamFailure("not_pausing", 409, "A checkpoint is accepted only while the session is pausing.")
            if (execution.checkpoint != null) return@transition null
            val now = store.now()
            CloudTransition(current.copy(phase = "checkpointing", updatedAt = now),
                CloudWrite(updateExecution = execution.copy(checkpoint = if (completed) "completed" else "failed"), reconcileAt = now))
        } ?: throw fenced()
    }

    private suspend fun live(session: CloudSession, epoch: Int): CloudExecution {
        if (session.currentEpoch != epoch || session.state.terminal || session.desiredState == DesiredState.TERMINATED) throw fenced()
        val execution = store.execution(session.id, epoch) ?: throw fenced()
        if (execution.state == ExecutionState.ENDED || execution.state == ExecutionState.STOPPING) throw fenced()
        return execution
    }
    private fun instruction(session: CloudSession) = RunnerInstruction(session.desiredState,
        session.pauseRequestedAt?.let { if (session.pauseForce) it else it + DRAIN_MILLIS }, session.currentEpoch)
    private fun fenced() = TeamFailure("execution_fenced", 409, "This execution is no longer current. Stop the agent and exit without checkpointing.")

    companion object { const val DRAIN_MILLIS = 10 * 60_000L }
}
