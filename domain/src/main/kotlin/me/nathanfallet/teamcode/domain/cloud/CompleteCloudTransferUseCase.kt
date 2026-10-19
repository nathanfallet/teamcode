package me.nathanfallet.teamcode.domain.cloud

import me.nathanfallet.teamcode.domain.cloud.CloudSessionState.*

/**
 * The reconciler's side of a transfer, run under the session lease. The owner changes only at a paused boundary:
 *
 * 1. `paused` with an accepted transfer moves to `transferring`, which refuses resumes, wakes and new transfers.
 * 2. No pod of the session may exist: any one left is deleted and confirmed gone first, so nothing that ran for the
 *    former owner (their Git token, variables, MCP credentials, agent process) survives the change.
 * 3. The recipient is checked again on fresh data; a recipient no longer eligible fails the transfer and the session
 *    returns to `paused` for its former owner.
 * 4. One transaction changes the owner, completes the transfer, removes attachment leases and applies the
 *    pending-input policy ([CloudTransferStore.complete]). The next execution starts a new epoch for the recipient,
 *    and every credential of the pod is resolved for the recipient then.
 */
class CompleteCloudTransferUseCase(
    private val store: CloudSessionStore,
    private val transfers: CloudTransferStore,
    private val cluster: ClusterPort,
    private val recipients: CloudRecipientPolicy,
    private val transition: TransitionCloudSessionUseCase = TransitionCloudSessionUseCase(store),
) {
    /** A paused session with an accepted transfer starts its owner change. */
    suspend fun begin(s: CloudSession, owner: String, now: Long): Long? {
        val transfer = s.transferId?.let { transfers.find(s.teamId, it) }
        if (transfer == null || transfer.state != CloudTransferState.ACCEPTED || transfer.sessionId != s.id) {
            // Cancelled or failed meanwhile without clearing the session (it cannot happen through the service): drop it.
            transition.commit(s, CloudTransition(s.copy(transferId = null, updatedAt = now), CloudWrite(leaseOwner = owner)))
            return now
        }
        transition.commit(s, CloudTransition(s.copy(state = TRANSFERRING, phase = "transferring", updatedAt = now),
            CloudWrite(leaseOwner = owner, auditAction = "cloud.session.transferring")))
        return now
    }

    /** One bounded step of `transferring`. */
    suspend fun step(s: CloudSession, owner: String, now: Long): Long? {
        val transfer = s.transferId?.let { transfers.find(s.teamId, it) }
        if (transfer == null || transfer.state != CloudTransferState.ACCEPTED || transfer.sessionId != s.id) {
            transition.commit(s, CloudTransition(s.copy(state = PAUSED, phase = null, transferId = null, updatedAt = now), CloudWrite(leaseOwner = owner)))
            return now
        }
        // Belt and braces: every execution ended when the session paused, but a pod of an older epoch must not outlive
        // its owner either. Listing by label also finds a pod whose execution row was lost.
        val pods = cluster.managed(s.clusterId, s.namespace).pods.filter { it.name.startsWith("tc-${s.id}-e") }
        if (pods.isNotEmpty()) {
            pods.forEach { cluster.deletePod(s.clusterId, s.namespace, it.name, force = true) }
            if (s.phase != "stopping_pod") transition.commit(s, CloudTransition(s.copy(phase = "stopping_pod", updatedAt = now), CloudWrite(leaseOwner = owner)))
            return now + ReconcileCloudSessionsUseCase.STEP_MILLIS
        }
        val execution = if (s.currentEpoch > 0) store.execution(s.id, s.currentEpoch) else null
        if (execution != null && execution.state != ExecutionState.ENDED) {
            // Not produced by the lifecycle (a paused session has no live execution); close it before the change.
            transition.commit(s, CloudTransition(s, CloudWrite(leaseOwner = owner, updateExecution = execution.copy(state = ExecutionState.ENDED, endedAt = now, exitReason = "transferred"))))
            return now
        }
        val problems = recipients.problems(s, transfer.toMemberId)
        if (problems.isNotEmpty()) return fail(s, transfer, owner, now, problems.first().copy(message = "The recipient is no longer eligible: ${problems.joinToString { it.code }}."))
        val settings = store.settings(s.teamId)
        val next = if (transfer.resume) s.copy(ownerId = transfer.toMemberId, state = RESUMING, phase = "waiting_capacity", desiredState = DesiredState.RUNNING,
            transferId = null, pausedAt = null, expiresAt = null, resumeBlocked = null, failure = null, updatedAt = now)
            // The recipient gets the full paused retention: a session about to expire is not handed over for deletion.
            else s.copy(ownerId = transfer.toMemberId, state = PAUSED, phase = null, desiredState = DesiredState.PAUSED, transferId = null,
                expiresAt = settings.pausedRetentionDays?.let { now + it * ReconcileCloudSessionsUseCase.DAY_MILLIS }, failure = null, updatedAt = now)
        return when (transfers.complete(s, next, transfer, owner, settings.maxRetainedSessionsPerMember)) {
            is TransferCompletion.Completed, TransferCompletion.Conflict -> now
            TransferCompletion.RecipientAtLimit -> fail(s, transfer, owner, now, CloudProblem("cloud_session_limit", "The recipient already retains the maximum number of cloud sessions."))
            TransferCompletion.RecipientUnavailable -> fail(s, transfer, owner, now, CloudProblem("recipient_unavailable", "The recipient is disabled or has not completed password setup."))
        }
    }

    /** The session ended (deleted, expired or failed) before its owner changed: the transfer is cancelled. */
    suspend fun abandon(s: CloudSession, owner: String, now: Long): Long? {
        val transfer = s.transferId?.let { transfers.find(s.teamId, it) }
        val change = SessionChange(s, s.copy(transferId = null, state = if (s.state == TRANSFERRING) PAUSED else s.state, phase = if (s.state == TRANSFERRING) null else s.phase, updatedAt = now), owner)
        if (transfer == null || !transfer.state.open) transition.commit(change.expected, CloudTransition(change.next, CloudWrite(leaseOwner = owner)))
        else transfers.update(transfer, transfer.copy(state = CloudTransferState.CANCELLED, failure = CloudProblem("session_not_running", "The session ended before its owner changed."),
            updatedAt = now, version = transfer.version + 1), null, "cloud.transfer.cancelled", change)
        return now
    }

    private suspend fun fail(s: CloudSession, transfer: CloudTransfer, owner: String, now: Long, problem: CloudProblem): Long? {
        transfers.update(transfer, transfer.copy(state = CloudTransferState.FAILED, failure = problem, updatedAt = now, version = transfer.version + 1), null, "cloud.transfer.failed",
            SessionChange(s, s.copy(state = PAUSED, phase = null, transferId = null, updatedAt = now), owner))
        return now
    }
}
