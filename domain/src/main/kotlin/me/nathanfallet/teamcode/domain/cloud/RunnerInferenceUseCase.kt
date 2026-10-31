package me.nathanfallet.teamcode.domain.cloud

import me.nathanfallet.teamcode.domain.cloud.CloudSessionState.*
import me.nathanfallet.teamcode.domain.team.TeamFailure

/** A `tcex_` inference credential, returned once to the runner of the execution it belongs to. */
class IssuedInferenceCredential(val token: String, val expiresAt: Long, val lifetimeMillis: Long) {
    override fun toString() = "IssuedInferenceCredential(token=[REDACTED], expiresAt=$expiresAt)"
}

/** What a `tcex_` credential may do: [CLOUD] is a pod's full execution credential, [REMOTE_SESSION] the confined one of a remote host's relays. */
enum class ExecutionCredentialKind { CLOUD, REMOTE_SESSION }

/**
 * Inference credentials of cloud executions. Each is a hashed member token limited to inference, attributed to
 * the member the execution runs for (`CloudExecution.ownerId`), so it goes through the gateway with that member's
 * model policy, quotas, pacing and routing, exactly like a local session.
 *
 * Revocation is not a separate call: the session store deletes an execution's credentials in the same transaction
 * that ends the execution (pause, loss, failure, deletion), starts a later epoch or changes the owner.
 */
interface CloudInferenceCredentialStore {
    /**
     * Issues a credential for the registered, current execution of a running session, valid for [ttlMillis], and
     * keeps at most [keep] credentials per execution (older ones are deleted). Checks and insert happen under the
     * session row lock, so an execution that ends concurrently never keeps a credential. Null when the execution is
     * no longer live or its member can no longer use TeamCode.
     */
    suspend fun issue(teamId: String, sessionId: String, epoch: Int, ttlMillis: Long, keep: Int, kind: ExecutionCredentialKind = ExecutionCredentialKind.CLOUD): IssuedInferenceCredential?
    /** Deletes expired credentials, and any left behind by an execution that is no longer live; bounded per call. */
    suspend fun purge(limit: Int): Int
}

/**
 * The runner's inference credential (`POST /cloud/runner/inference`). The runner keeps it in memory and forwards
 * the agent's inference through its loopback proxy; the agent itself only ever sees a random local token.
 * Credentials are short-lived and renewed by the runner well before they expire, so a credential copied out of
 * the runner's memory is useless after [TTL_MILLIS] even if revocation were missed.
 */
class RunnerInferenceUseCase(private val sessions: CloudSessionStore, private val credentials: CloudInferenceCredentialStore) {
    suspend fun issue(teamId: String, sessionId: String, epoch: Int): IssuedInferenceCredential {
        val session = sessions.find(teamId, sessionId) ?: throw fenced()
        if (session.currentEpoch != epoch || session.state.terminal || session.desiredState == DesiredState.TERMINATED) throw fenced()
        // A remote host's inference goes through its own member login, never through an execution credential.
        if (session.kind == CloudSessionKind.REMOTE) throw TeamFailure("inference_unavailable", 409, "A remote session's host runs inference with its member login.")
        val execution = sessions.execution(sessionId, epoch) ?: throw fenced()
        if (execution.state != ExecutionState.RUNNING) throw fenced()
        // A draining execution still finishes its turn, so `pausing` keeps its inference until the pod stops.
        if (session.state !in INFERENCE_STATES) throw TeamFailure("inference_unavailable", 409, "The session cannot run inference in its current state.")
        return credentials.issue(teamId, sessionId, epoch, TTL_MILLIS, KEEP) ?: throw fenced()
    }

    /**
     * The confined credential a remote session's host gives its loopback relays (`POST /cloud/runner/session-credential`,
     * `cloud.remote.session-credential.v1`): the session's own waits (never answering), its own workflow step and artifact
     * publication, and nothing else: no inference (the host's own login runs it), no MCP, no skills, no cloud route.
     * It lives and dies with the host's epoch exactly like a pod's credential.
     */
    suspend fun issueRemote(teamId: String, sessionId: String, epoch: Int): IssuedInferenceCredential {
        val session = sessions.find(teamId, sessionId) ?: throw fenced()
        if (session.currentEpoch != epoch || session.state.terminal || session.desiredState == DesiredState.TERMINATED) throw fenced()
        if (session.kind != CloudSessionKind.REMOTE) throw TeamFailure("session_credential_unavailable", 409, "Only a remote session's host asks for a session credential.")
        val execution = sessions.execution(sessionId, epoch) ?: throw fenced()
        if (execution.state != ExecutionState.RUNNING) throw fenced()
        if (session.state !in INFERENCE_STATES) throw TeamFailure("session_credential_unavailable", 409, "The session cannot hold a credential in its current state.")
        return credentials.issue(teamId, sessionId, epoch, TTL_MILLIS, KEEP, ExecutionCredentialKind.REMOTE_SESSION) ?: throw fenced()
    }

    private fun fenced() = TeamFailure("execution_fenced", 409, "This execution is no longer current. Stop the agent and exit without checkpointing.")

    companion object {
        const val TTL_MILLIS = 60 * 60_000L
        /** The current credential and the one before it, so a request in flight during a renewal is not cut. */
        const val KEEP = 2
        val INFERENCE_STATES = setOf(PREPARING, RESUMING, READY, ACTIVE, PAUSING)
        const val TOKEN_PREFIX = "tcex_"
    }
}
