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
class RunnerChannelUseCase(
    private val store: CloudSessionStore,
    private val variables: CloudVariableSource = CloudVariableSource.NONE,
    private val github: GitHubAccessSource = GitHubAccessSource.NONE,
    private val transition: TransitionCloudSessionUseCase = TransitionCloudSessionUseCase(store),
) {
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
        val session = if (ready && current.desiredState == DesiredState.RUNNING && (current.state == PREPARING || current.state == RESUMING) && !current.preparationFailed) {
            transition(teamId, sessionId) { fresh ->
                live(fresh, epoch)
                // A failed preparation is never reported ready, even by a runner that claims it is.
                if ((fresh.state != PREPARING && fresh.state != RESUMING) || fresh.preparationFailed) return@transition null
                val now = store.now()
                CloudTransition(fresh.copy(state = READY, phase = null, failure = null, lastActivityAt = now, updatedAt = now),
                    CloudWrite(auditAction = if (fresh.state == RESUMING) "cloud.session.ready_resumed" else "cloud.session.ready", reconcileAt = now))
            } ?: throw fenced()
        } else current
        return instruction(session)
    }

    /**
     * Accepted once a pause is requested, including before the reconciler has moved the session to `pausing`
     * (the runner learns of the pause from its heartbeat and may finish first); the reconciler then deletes the
     * pod and advertises the checkpoint quality.
     */
    suspend fun checkpoint(teamId: String, sessionId: String, epoch: Int, completed: Boolean): RunnerInstruction {
        val session = transition(teamId, sessionId) { current ->
            val execution = live(current, epoch)
            val pauseRequested = current.desiredState == DesiredState.PAUSED && (current.state == READY || current.state == ACTIVE)
            if (current.state != PAUSING && !pauseRequested) throw TeamFailure("not_pausing", 409, "A checkpoint is accepted only once a pause is requested.")
            if (execution.checkpoint != null) return@transition null
            val now = store.now()
            CloudTransition(current.copy(state = PAUSING, pauseRequestedAt = current.pauseRequestedAt ?: now, phase = "checkpointing", updatedAt = now),
                CloudWrite(updateExecution = execution.copy(checkpoint = if (completed) "completed" else "failed"), reconcileAt = now))
        } ?: throw fenced()
        return instruction(session)
    }

    /**
     * The preparation recipe and the owner's credentials for this execution, resolved again on every call so a
     * resumed or transferred session gets the current owner's variables, GitHub access and Git identity. Only the
     * registered runner of the current epoch may read it, and only while the session is preparing or resuming.
     * Missing required variables or GitHub access fail the preparation here (`409 preparation_failed`).
     */
    suspend fun environment(teamId: String, sessionId: String, epoch: Int): PreparedEnvironment {
        val session = preparing(store.find(teamId, sessionId) ?: throw fenced(), epoch)
        val owner = store.member(teamId, session.ownerId)
        if (owner == null || owner.disabled || owner.passwordChangeRequired) {
            fail(teamId, sessionId, epoch, "The session owner is disabled or has not completed password setup.")
        }
        val spec = session.environment
        val resolved = variables.resolve(teamId, owner.id)
        val missing = spec.requiredVariables.filter { it !in resolved.values }
        // An assigned value that could not be read now is a retryable outage, not a missing assignment.
        val unreadable = missing.filter { it in resolved.unavailable }
        if (unreadable.isNotEmpty()) fail(teamId, sessionId, epoch, "Required environment variables of the owner are unavailable right now: ${unreadable.joinToString(", ")}.", retryable = true)
        if (missing.isNotEmpty()) fail(teamId, sessionId, epoch, "Required environment variables are not assigned to the owner: ${missing.joinToString(", ")}.")
        if (session.githubAccess == CloudGitHubAccess.NONE && spec.requireGitHub)
            fail(teamId, sessionId, epoch, "GitHub access is off for this session, which was created on its owner's behalf; the owner turns it on with " +
                "teamcode cloud github ${session.id} --access owner.", retryable = true)
        val access = if (session.githubAccess == CloudGitHubAccess.NONE) GitHubAccess.NotLinked else github.userAccess(teamId, owner.id)
        if (spec.requireGitHub && access !is GitHubAccess.Linked)
            fail(teamId, sessionId, epoch, if (access is GitHubAccess.NotLinked) "The owner has not linked GitHub, which this profile requires." else "The owner's GitHub access is unavailable right now.", retryable = true)
        // Content another member left on the workspace (a transfer) must not run with this owner's credentials unchecked.
        val executions = store.executions(teamId, sessionId)
        val untrusted = CloudEnvironmentRules.untrustedWorkspace(owner.id, session.createdBy.effectiveMemberId, epoch, executions,
            truncated = executions.size >= EXECUTION_HISTORY_LIMIT)
        return PreparedEnvironment(spec, CloudEnvironmentRules.gitIdentity(owner.username, access, spec), resolved, access, untrusted)
    }

    /**
     * Preparation progress from the runner: [phase] becomes the session phase; a [failure] keeps the session unready
     * and the reconciler fails it (first execution) or blocks the resume, then stops the pod.
     */
    suspend fun progress(teamId: String, sessionId: String, epoch: Int, phase: String, failure: PreparationFailure?): RunnerInstruction {
        if (failure == null && phase !in CloudEnvironmentRules.PHASES) throw TeamFailure("invalid_phase", 400, "Unknown preparation phase.")
        val session = transition(teamId, sessionId) { current ->
            preparing(current, epoch)
            val now = store.now()
            if (failure != null) {
                val spec = current.environment
                val count = when (failure.step) {
                    PreparationStep.CLONE, PreparationStep.CHECKOUT, PreparationStep.FETCH, PreparationStep.WORKSPACE -> spec.repositories.size
                    PreparationStep.SETUP_CREATE -> spec.setup.create.size
                    PreparationStep.SETUP_START -> spec.setup.start.size
                    else -> 0
                }
                if ((failure.index != null && failure.index !in 0 until count) || (failure.exitCode != null && failure.exitCode !in 0..255))
                    throw TeamFailure("invalid_failure", 400, "Unknown preparation step index or exit code.")
                failed(current, CloudEnvironmentRules.message(failure, spec, epoch), failure.step in RETRYABLE_STEPS, now)
            } else if (current.phase == phase) null
            else CloudTransition(current.copy(phase = phase, updatedAt = now), CloudWrite())
        } ?: throw fenced()
        return instruction(session)
    }

    /** The registered, current execution of a session that is still preparing, with no reported failure. */
    private suspend fun preparing(session: CloudSession, epoch: Int): CloudSession {
        val execution = live(session, epoch)
        if (execution.state != ExecutionState.RUNNING || session.desiredState != DesiredState.RUNNING || (session.state != PREPARING && session.state != RESUMING) || session.preparationFailed)
            throw TeamFailure("not_preparing", 409, "The session is not preparing an environment.")
        return session
    }

    private suspend fun fail(teamId: String, sessionId: String, epoch: Int, message: String, retryable: Boolean = false): Nothing {
        transition(teamId, sessionId) { current -> failed(preparing(current, epoch), message, retryable, store.now()) }
        throw TeamFailure(CloudEnvironmentRules.PREPARATION_FAILED, 409, "The environment cannot be prepared.")
    }

    /** Records a failed preparation; the reconciler then stops the pod and fails the session or blocks the resume. */
    private fun failed(current: CloudSession, message: String, retryable: Boolean, now: Long) = CloudTransition(
        current.copy(phase = CloudEnvironmentRules.PREPARATION_FAILED, updatedAt = now, failure = CloudProblem(CloudEnvironmentRules.PREPARATION_FAILED, message, retryable)),
        CloudWrite(reconcileAt = now))

    private suspend fun live(session: CloudSession, epoch: Int): CloudExecution {
        if (session.currentEpoch != epoch || session.state.terminal || session.desiredState == DesiredState.TERMINATED) throw fenced()
        val execution = store.execution(session.id, epoch) ?: throw fenced()
        if (execution.state == ExecutionState.ENDED) throw fenced()
        return execution
    }
    private fun instruction(session: CloudSession) = RunnerInstruction(session.desiredState,
        session.pauseRequestedAt?.let { if (session.pauseForce) it else it + DRAIN_MILLIS }, session.currentEpoch)
    private fun fenced() = TeamFailure("execution_fenced", 409, "This execution is no longer current. Stop the agent and exit without checkpointing.")

    companion object {
        const val DRAIN_MILLIS = 10 * 60_000L
        /** Steps that may succeed on another attempt (network, GitHub). Setup commands and paths fail the same way again. */
        private val RETRYABLE_STEPS = setOf(PreparationStep.CLONE, PreparationStep.FETCH, PreparationStep.RUNNER)
        /** [CloudSessionStore.executions] returns at most this many executions, newest first. */
        const val EXECUTION_HISTORY_LIMIT = 100
    }
}
