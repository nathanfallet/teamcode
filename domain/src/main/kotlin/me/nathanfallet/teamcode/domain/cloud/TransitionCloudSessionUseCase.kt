package me.nathanfallet.teamcode.domain.cloud

import me.nathanfallet.teamcode.domain.team.TeamFailure

/** A decided change: the next row and what must commit with it. */
data class CloudTransition(val next: CloudSession, val write: CloudWrite = CloudWrite())

/**
 * Every lifecycle change is a compare-and-set on `generation`. A lost race re-reads the row and evaluates the
 * decision again on the fresh state; nothing is written from a stale read.
 */
class TransitionCloudSessionUseCase(private val store: CloudSessionStore) {
    /** Commits [transition] exactly once against [expected]; null when another writer won. */
    suspend fun commit(expected: CloudSession, transition: CloudTransition): CloudSession? {
        val next = transition.next
        require(next.id == expected.id && next.teamId == expected.teamId && next.generation == expected.generation) { "A transition must derive from the row it replaces." }
        CloudStateMachine.require(expected.state, next.state)
        require(next.currentEpoch == expected.currentEpoch) { "The epoch advances only through start()." }
        require(next.ownerId == expected.ownerId) { "The owner changes only through a completed transfer." }
        return store.compareAndSet(expected, next, transition.write)
    }

    /** Advances the epoch by exactly one together with its new execution row, subject to running capacity. */
    suspend fun start(expected: CloudSession, next: CloudSession, execution: CloudExecution, leaseOwner: String, memberRunningLimit: Int): StartOutcome {
        require(next.id == expected.id && next.generation == expected.generation && next.state == expected.state) { "A start keeps the session state." }
        require(expected.state == CloudSessionState.CREATING || expected.state == CloudSessionState.RESUMING) { "Only a creating or resuming session starts an execution." }
        require(next.currentEpoch == expected.currentEpoch + 1 && execution.epoch == next.currentEpoch && execution.sessionId == expected.id) { "The epoch advances by one with its execution." }
        require(next.ownerId == expected.ownerId && execution.ownerId == expected.ownerId) { "An execution runs for the session's current owner." }
        return store.startExecution(expected, next, execution, leaseOwner, memberRunningLimit)
    }

    /** Advances the epoch of a remote session by exactly one on its host, ending the execution it replaces. */
    suspend fun startHost(expected: CloudSession, next: CloudSession, execution: CloudExecution, ended: CloudExecution?, actor: CloudActor): CloudSession? {
        require(expected.kind == CloudSessionKind.REMOTE && next.id == expected.id && next.generation == expected.generation) { "Only a remote session starts on its host." }
        CloudStateMachine.require(expected.state, next.state)
        require(next.currentEpoch == expected.currentEpoch + 1 && execution.epoch == next.currentEpoch && execution.sessionId == expected.id) { "The epoch advances by one with its execution." }
        require(next.ownerId == expected.ownerId && execution.ownerId == expected.ownerId) { "An execution runs for the session's current owner." }
        require(ended == null || (ended.epoch == expected.currentEpoch && ended.state == ExecutionState.ENDED)) { "Only the current execution is replaced." }
        return store.startHostExecution(expected, next, execution, ended, actor)
    }

    /**
     * Reads the session, lets [decide] return a transition (or null for "already in the requested state"),
     * and retries on a lost race. Returns the committed or current row, or null when the session does not exist.
     */
    suspend operator fun invoke(teamId: String, id: String, attempts: Int = 5, decide: suspend (CloudSession) -> CloudTransition?): CloudSession? {
        repeat(attempts) {
            val current = store.find(teamId, id) ?: return null
            val transition = decide(current) ?: return current
            commit(current, transition)?.let { return it }
        }
        throw TeamFailure("session_busy", 409, "The cloud session changed concurrently. Retry the request.")
    }
}
