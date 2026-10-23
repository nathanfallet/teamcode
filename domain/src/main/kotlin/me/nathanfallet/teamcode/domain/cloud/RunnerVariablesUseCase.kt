package me.nathanfallet.teamcode.domain.cloud

import me.nathanfallet.teamcode.domain.team.TeamFailure

/**
 * The environment variables the runner serves to the agent while the session runs (`POST /cloud/runner/variables`).
 * Preparation receives them once; this lets a running agent see a rotated, added or removed variable without a new
 * pod. They are the variables of the member the current execution runs for (`CloudExecution.ownerId`), so after a
 * transfer only the recipient's are served. A disabled owner gets none. Same audience as the GitHub route: the
 * registered runner of the current epoch only.
 */
class RunnerVariablesUseCase(private val sessions: CloudSessionStore, private val variables: CloudVariableSource) {
    suspend fun variables(teamId: String, sessionId: String, epoch: Int): CloudVariables {
        val session = sessions.find(teamId, sessionId) ?: throw fenced()
        if (session.currentEpoch != epoch || session.state.terminal || session.desiredState == DesiredState.TERMINATED) throw fenced()
        val execution = sessions.execution(sessionId, epoch) ?: throw fenced()
        if (execution.state != ExecutionState.RUNNING) throw fenced()
        if (session.state !in RunnerGitHubUseCase.STATES) throw TeamFailure("variables_unavailable", 409, "The session cannot read variables in its current state.")
        val owner = sessions.member(teamId, execution.ownerId ?: session.ownerId)
        if (owner == null || owner.disabled || owner.passwordChangeRequired) return CloudVariables(emptyMap())
        return variables.resolve(teamId, owner.id)
    }

    private fun fenced() = TeamFailure("execution_fenced", 409, "This execution is no longer current. Stop the agent and exit without checkpointing.")
}
