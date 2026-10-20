package me.nathanfallet.teamcode.domain.cloud

import me.nathanfallet.teamcode.domain.cloud.CloudSessionState.*
import me.nathanfallet.teamcode.domain.team.TeamFailure

/**
 * The GitHub token the runner serves to the agent's `git` and `gh` while the session runs (`POST /cloud/runner/github`).
 * Preparation receives the token once; this renews it, because user-to-server tokens last hours and a session lives
 * longer. The token is the one of the member the current execution runs for (`CloudExecution.ownerId`), so after a
 * transfer the recipient's link replaces the former owner's, and a former owner's token is never served again.
 * The runner keeps it in memory and hands the agent only the credential one command asked for.
 */
class RunnerGitHubUseCase(private val sessions: CloudSessionStore, private val github: GitHubAccessSource) {
    suspend fun access(teamId: String, sessionId: String, epoch: Int): GitHubAccess {
        val session = sessions.find(teamId, sessionId) ?: throw fenced()
        if (session.currentEpoch != epoch || session.state.terminal || session.desiredState == DesiredState.TERMINATED) throw fenced()
        val execution = sessions.execution(sessionId, epoch) ?: throw fenced()
        if (execution.state != ExecutionState.RUNNING) throw fenced()
        if (session.state !in STATES) throw TeamFailure("github_unavailable", 409, "The session cannot use GitHub in its current state.")
        val owner = sessions.member(teamId, execution.ownerId ?: session.ownerId)
        if (owner == null || owner.disabled) return GitHubAccess.NotLinked
        return github.userAccess(teamId, owner.id)
    }

    private fun fenced() = TeamFailure("execution_fenced", 409, "This execution is no longer current. Stop the agent and exit without checkpointing.")

    companion object {
        val STATES = setOf(PREPARING, RESUMING, READY, ACTIVE, PAUSING)
    }
}
