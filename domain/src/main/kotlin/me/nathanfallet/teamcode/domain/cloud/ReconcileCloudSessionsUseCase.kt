package me.nathanfallet.teamcode.domain.cloud

import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import me.nathanfallet.teamcode.domain.cloud.CloudSessionState.*

/**
 * Drives sessions toward their desired state. Any replica may run a pass: each session is leased in the
 * database, a pass makes bounded progress, and no transaction is held during cluster calls. Every write is a
 * compare-and-set that also requires the lease to still be held, so a stale reconciler cannot commit.
 */
class ReconcileCloudSessionsUseCase(
    private val store: CloudSessionStore,
    private val cluster: ClusterPort,
    private val transition: TransitionCloudSessionUseCase = TransitionCloudSessionUseCase(store),
    /** Called for every failed step, so the server can log and count it; the session is retried either way. */
    private val onError: (sessionId: String, error: Exception) -> Unit = { _, _ -> },
) {
    /**
     * Reconciles up to [limit] due sessions; returns how many were claimed. All leases are taken together, so
     * sessions not started within half a lease are handed back due immediately rather than worked on with a
     * lease about to expire.
     */
    suspend operator fun invoke(owner: String = UUID.randomUUID().toString(), limit: Int = 8): Int {
        val claimed = store.claimDue(owner, LEASE_MILLIS, limit)
        val claimedAt = store.now()
        for (session in claimed) {
            var next: Long? = null
            try {
                val now = store.now()
                next = if (now - claimedAt > LEASE_MILLIS / 2) now else reconcile(session, owner)
            }
            catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                // Cluster or database unavailable, or a bug: report it and retry later. The raw failure never
                // reaches the session row or its API view.
                onError(session.id, error)
                next = store.now() + RETRY_MILLIS
            } finally {
                withContext(NonCancellable) { store.release(session.id, owner, next) }
            }
        }
        return claimed.size
    }

    /** One bounded step for a leased session. Returns the next due time, or null when nothing remains to do. */
    suspend fun reconcile(session: CloudSession, owner: String): Long? {
        val now = store.now()
        val s = session
        return when {
            s.state == TERMINATED -> cleanup(s, owner, now)
            s.desiredState == DesiredState.TERMINATED -> terminate(s, owner, now)
            s.state == FAILED && s.workspace.deletion != DeletionState.NONE -> cleanup(s, owner, now)
            s.state == FAILED -> if (s.expiresAt != null && s.expiresAt <= now) {
                commit(s, owner, s.copy(workspace = s.workspace.copy(deletion = DeletionState.PENDING), phase = "cleanup_pending", updatedAt = now)); now
            } else s.expiresAt
            s.state == PAUSED -> paused(s, owner, now)
            s.state == CREATING || s.state == RESUMING -> if (s.desiredState == DesiredState.PAUSED && s.state == RESUMING) abandonResume(s, owner, now) else start(s, owner, now)
            s.state == PREPARING || s.state == READY || s.state == ACTIVE -> if (s.desiredState == DesiredState.PAUSED && s.state != PREPARING) {
                commit(s, owner, s.copy(state = PAUSING, phase = "draining", pauseRequestedAt = s.pauseRequestedAt ?: now, updatedAt = now), "cloud.session.pausing"); now
            } else monitor(s, owner, now)
            s.state == PAUSING -> drain(s, owner, now)
            else -> now + MONITOR_MILLIS // TRANSFERRING is not produced by this release.
        }
    }

    private suspend fun paused(s: CloudSession, owner: String, now: Long): Long? = when {
        s.expiresAt != null && s.expiresAt <= now -> {
            commit(s, owner, s.copy(desiredState = DesiredState.TERMINATED, terminatedReason = "expired", updatedAt = now), "cloud.session.expired"); now
        }
        s.desiredState == DesiredState.RUNNING -> {
            commit(s, owner, s.copy(state = RESUMING, phase = "waiting_capacity", pausedAt = null, expiresAt = null, updatedAt = now)); now
        }
        else -> s.expiresAt
    }

    private suspend fun start(s: CloudSession, owner: String, now: Long): Long? {
        val first = s.state == CREATING
        // A cluster narrowed after the session was created: never create objects outside its allowed namespaces.
        val target = store.cluster(s.teamId, s.clusterId)
        if (target == null || s.namespace !in target.allowedNamespaces) {
            val problem = CloudProblem("namespace_not_allowed", "The session's cluster or namespace is no longer allowed.")
            if (first) return fail(s, owner, now, problem)
            val execution = if (s.currentEpoch > 0) store.execution(s.id, s.currentEpoch) else null
            if (execution != null && execution.state != ExecutionState.ENDED) return block(s, owner, now, execution, problem)
            commit(s, owner, pausedCopy(s, now, s.resumeWorkspace).copy(resumeBlocked = problem), "cloud.session.resume_blocked")
            return now
        }
        // Workspace: one claim per session, refused when its storage class would retain the provider volume.
        var claim = cluster.claim(s.clusterId, s.namespace, s.workspace.claimName)
        if (claim == null) {
            if (!first) return fail(s, owner, now, CloudProblem("workspace_lost", "The session workspace no longer exists."), WorkspaceResume.LOST)
            val storageClass = cluster.storageClass(s.clusterId, s.workspace.storageClass)
                ?: return fail(s, owner, now, CloudProblem("storage_class_missing", "The workspace storage class does not exist on the cluster."))
            if (storageClass.reclaimPolicy != "Delete")
                return fail(s, owner, now, CloudProblem("storage_class_retains_volumes", "The workspace storage class does not delete provider volumes (reclaimPolicy ${storageClass.reclaimPolicy}). Use a class with reclaimPolicy Delete."))
            cluster.ensureClaim(s.clusterId, WorkspaceRequest(s.namespace, s.workspace.claimName, storageClass.name, s.workspace.sizeGibibytes, labels(s)))
            claim = cluster.claim(s.clusterId, s.namespace, s.workspace.claimName)
        }
        if (claim?.phase == ClaimPhase.LOST) return fail(s, owner, now, CloudProblem("workspace_lost", "The session workspace volume was lost."), WorkspaceResume.LOST)
        var current = s
        if (claim?.volumeName != null && claim.volumeName != s.workspace.volumeName) {
            val handle = cluster.volume(s.clusterId, claim.volumeName)?.volumeHandle
            current = commit(s, owner, s.copy(workspace = s.workspace.copy(volumeName = claim.volumeName, volumeHandle = handle), updatedAt = now)) ?: return now
        }
        val execution = if (current.currentEpoch > 0) store.execution(current.id, current.currentEpoch) else null
        if (execution == null || execution.state == ExecutionState.ENDED) {
            // Never two pods for one workspace: older epochs must be confirmed gone before the next starts.
            val stale = cluster.managed(current.clusterId, current.namespace).pods.filter { it.name.startsWith("tc-${current.id}-e") }
            if (stale.isNotEmpty()) {
                stale.forEach { cluster.deletePod(current.clusterId, current.namespace, it.name, force = true) }
                return now + STEP_MILLIS
            }
            val epoch = current.currentEpoch + 1
            val pod = CloudSessionService.podName(current.id, epoch)
            val limit = store.settings(current.teamId).maxRunningSessionsPerMember
            val outcome = transition.start(current, current.copy(currentEpoch = epoch, phase = "scheduling", waitingSince = null, updatedAt = now),
                CloudExecution(current.id, epoch, pod, ExecutionState.PENDING, now), owner, limit)
            if (outcome == StartOutcome.WaitingCapacity) {
                if (current.phase != "waiting_capacity" || current.waitingSince == null)
                    commit(current, owner, current.copy(phase = "waiting_capacity", waitingSince = current.waitingSince ?: now, updatedAt = now))
                return now + CAPACITY_MILLIS
            }
            return now
        }
        val pod = cluster.pod(current.clusterId, current.namespace, execution.podName)
        if (pod == null) {
            if (execution.state == ExecutionState.PENDING) {
                val profile = store.profileVersion(current.teamId, current.profileId, current.profileVersion)
                    ?: return fail(current, owner, now, CloudProblem("profile_unavailable", "The pinned profile version no longer exists."))
                val image = current.imageDigest?.let { digest -> current.imageReference.substringBefore('@') + "@" + digest } ?: current.imageReference
                cluster.createPod(current.clusterId, PodRequest(current.namespace, execution.podName, image, current.workspace.claimName,
                    labels(current) + (EPOCH_LABEL to execution.epoch.toString()), profile.resources, profile.imagePullSecrets,
                    mapOf(TEAM_ENV to current.teamId, SESSION_ENV to current.id, EPOCH_ENV to execution.epoch.toString())))
                return now + STEP_MILLIS
            }
            return lost(current, owner, now, execution, "pod_missing")
        }
        // Pulls are retried by the kubelet: a pull error fails the start only once it has lasted past the grace period.
        val imageFailed = pod.reason in IMAGE_FAILURES || (pod.reason in IMAGE_PULL_RETRIES && now - execution.createdAt > IMAGE_PULL_GRACE_MILLIS)
        if (imageFailed || pod.phase == PodPhase.FAILED || pod.phase == PodPhase.SUCCEEDED) {
            val problem = if (imageFailed) CloudProblem("image_unavailable", "The session image could not be pulled (${pod.reason}).", true)
                else CloudProblem("execution_failed", "The session pod stopped before the runner was ready.", true)
            return if (first) fail(current, owner, now, problem) else block(current, owner, now, execution, problem)
        }
        // A registered runner on a resume is preparing the environment: it fails or blocks like a first preparation.
        val registered = execution.state == ExecutionState.RUNNING
        if (registered && current.preparationFailed) return block(current, owner, now, execution, current.failure!!)
        // A registered runner heartbeats while it prepares; silence is a lost execution, as in monitor().
        if (registered && now - (execution.lastHeartbeatAt ?: execution.registeredAt ?: execution.createdAt) > HEARTBEAT_LOSS_MILLIS)
            return lost(current, owner, now, execution, "heartbeat_lost")
        if (now - execution.createdAt > (if (registered) PREPARE_TIMEOUT_MILLIS else START_TIMEOUT_MILLIS)) {
            val problem = if (registered) CloudProblem("prepare_timeout", "The session did not become ready within 30 minutes.", true)
                else CloudProblem("start_timeout", "The session pod did not start a runner within 15 minutes.", true)
            return if (first) fail(current, owner, now, problem) else block(current, owner, now, execution, problem)
        }
        val digest = pod.imageId?.substringAfter('@', "")?.takeIf { it.startsWith("sha256:") }
        if (pod.phase == PodPhase.RUNNING && (execution.nodeName == null || (current.imageDigest == null && digest != null))) {
            // The phase is the runner's once it registered (preparation progress); only the digest and node are recorded then.
            commit(current, owner, current.copy(imageDigest = current.imageDigest ?: digest, phase = if (registered) current.phase else "starting_runner", updatedAt = now),
                updateExecution = execution.copy(nodeName = pod.nodeName, imageDigest = digest))
        }
        // The runner's registration moves the session forward; until then keep watching the pod.
        return now + STEP_MILLIS
    }

    private suspend fun monitor(s: CloudSession, owner: String, now: Long): Long? {
        val execution = store.execution(s.id, s.currentEpoch) ?: return lostWithoutExecution(s, owner, now)
        val seen = execution.lastHeartbeatAt ?: execution.registeredAt ?: execution.createdAt
        val pod = cluster.pod(s.clusterId, s.namespace, execution.podName)
        if (pod == null || pod.phase == PodPhase.FAILED || pod.phase == PodPhase.SUCCEEDED || now - seen > HEARTBEAT_LOSS_MILLIS)
            return lost(s, owner, now, execution, if (pod == null) "pod_missing" else "heartbeat_lost")
        if (s.preparationFailed) return fail(s, owner, now, s.failure!!)
        if (s.state == PREPARING && now - execution.createdAt > PREPARE_TIMEOUT_MILLIS)
            return fail(s, owner, now, CloudProblem("prepare_timeout", "The session did not become ready within 30 minutes.", true))
        return now + MONITOR_MILLIS
    }

    private suspend fun drain(s: CloudSession, owner: String, now: Long): Long? {
        val execution = store.execution(s.id, s.currentEpoch) ?: return lostWithoutExecution(s, owner, now)
        val seen = execution.lastHeartbeatAt ?: execution.registeredAt ?: execution.createdAt
        val requested = s.pauseRequestedAt ?: now
        val checkpointed = execution.checkpoint == "completed"
        val expired = now - requested > PAUSING_DEADLINE_MILLIS || now - seen > HEARTBEAT_LOSS_MILLIS || execution.checkpoint == "failed"
        if (!checkpointed && !expired) return now + STEP_MILLIS
        if (!stopPod(s, execution, force = !checkpointed)) {
            if (s.phase != "stopping_pod") commit(s, owner, s.copy(phase = "stopping_pod", updatedAt = now))
            return now + STEP_MILLIS
        }
        val quality = if (checkpointed) WorkspaceResume.CONSISTENT else WorkspaceResume.CRASH_CONSISTENT
        commit(s, owner, pausedCopy(s, now, quality), "cloud.session.paused_checkpoint",
            updateExecution = execution.copy(state = ExecutionState.ENDED, endedAt = now, exitReason = if (checkpointed) "paused" else "pause_forced"))
        return now
    }

    private suspend fun abandonResume(s: CloudSession, owner: String, now: Long): Long? {
        val execution = if (s.currentEpoch > 0) store.execution(s.id, s.currentEpoch) else null
        if (execution != null && execution.state != ExecutionState.ENDED) {
            if (!stopPod(s, execution, force = true)) return now + STEP_MILLIS
            commit(s, owner, pausedCopy(s, now, s.resumeWorkspace), updateExecution = execution.copy(state = ExecutionState.ENDED, endedAt = now, exitReason = "resume_cancelled"))
        } else commit(s, owner, pausedCopy(s, now, s.resumeWorkspace))
        return now
    }

    private suspend fun terminate(s: CloudSession, owner: String, now: Long): Long? {
        val execution = if (s.currentEpoch > 0) store.execution(s.id, s.currentEpoch) else null
        if (execution != null && execution.state != ExecutionState.ENDED) {
            if (!stopPod(s, execution, force = true)) return now + STEP_MILLIS
            commit(s, owner, s.copy(phase = "stopping_pod", updatedAt = now), updateExecution = execution.copy(state = ExecutionState.ENDED, endedAt = now, exitReason = "deleted"))
            return now
        }
        // A failed session may already be cleaning up, or done: keep its deletion progress.
        val deletion = if (s.workspace.deletion == DeletionState.NONE) DeletionState.PENDING else s.workspace.deletion
        commit(s, owner, s.copy(state = TERMINATED, phase = if (deletion == DeletionState.PROVEN) "cleaned" else "cleanup_pending", expiresAt = null,
            workspace = s.workspace.copy(deletion = deletion), updatedAt = now), "cloud.session.terminated")
        return now
    }

    /** Deletes the claim, then waits until the bound volume — and so the provider volume behind it — is gone. */
    private suspend fun cleanup(s: CloudSession, owner: String, now: Long): Long? {
        val w = s.workspace
        if (w.deletion == DeletionState.PROVEN) return null
        val claim = cluster.claim(s.clusterId, s.namespace, w.claimName)
        if (claim != null) {
            val volume = claim.volumeName ?: w.volumeName
            val handle = if (volume != null && w.volumeHandle == null) cluster.volume(s.clusterId, volume)?.volumeHandle else w.volumeHandle
            if (volume != w.volumeName || handle != w.volumeHandle) commit(s, owner, s.copy(workspace = w.copy(volumeName = volume, volumeHandle = handle), updatedAt = now))
            cluster.deleteClaim(s.clusterId, s.namespace, w.claimName)
            return now + STEP_MILLIS
        }
        val claimDeletedAt = w.claimDeletedAt ?: now
        val volume = w.volumeName?.let { cluster.volume(s.clusterId, it) }
        val proven = w.copy(claimDeletedAt = claimDeletedAt, volumeDeletedAt = if (w.volumeName == null) null else (w.volumeDeletedAt ?: now), deletion = DeletionState.PROVEN, deletionProblem = null)
        val next = when {
            volume == null -> s.copy(workspace = proven, phase = "cleaned", updatedAt = now)
            volume.reclaimPolicy != "Delete" -> s.copy(workspace = w.copy(claimDeletedAt = claimDeletedAt, deletion = DeletionState.BLOCKED,
                deletionProblem = CloudProblem("volume_retained", "The workspace volume ${volume.name} has reclaimPolicy ${volume.reclaimPolicy}; delete the provider volume manually.")), updatedAt = now)
            volume.phase == "Failed" || now - claimDeletedAt > VOLUME_DELETION_TIMEOUT_MILLIS -> s.copy(workspace = w.copy(claimDeletedAt = claimDeletedAt, deletion = DeletionState.BLOCKED,
                deletionProblem = CloudProblem("volume_deletion_stalled", "The provisioner has not deleted workspace volume ${volume.name}.", true)), updatedAt = now)
            else -> s.copy(workspace = w.copy(claimDeletedAt = claimDeletedAt), phase = "deleting_volume", updatedAt = now)
        }
        if (next != s.copy(updatedAt = now)) commit(s, owner, next, if (next.workspace.deletion == DeletionState.PROVEN) "cloud.session.workspace_deleted" else null)
        return when (next.workspace.deletion) {
            DeletionState.PROVEN -> null
            DeletionState.BLOCKED -> now + BLOCKED_RECHECK_MILLIS
            else -> now + STEP_MILLIS
        }
    }

    private suspend fun lost(s: CloudSession, owner: String, now: Long, execution: CloudExecution, reason: String): Long? {
        if (!stopPod(s, execution, force = true)) return now + STEP_MILLIS
        val ended = execution.copy(state = ExecutionState.ENDED, endedAt = now, exitReason = reason)
        if (s.state == PREPARING || s.state == CREATING) return fail(s, owner, now, CloudProblem("execution_lost", "The session pod was lost during preparation.", true), ended = ended)
        if (s.state == RESUMING) return block(s, owner, now, execution, CloudProblem("execution_lost", "The session pod was lost while resuming.", true))
        commit(s, owner, pausedCopy(s, now, WorkspaceResume.CRASH_CONSISTENT).copy(failure = CloudProblem("execution_lost", "The session pod was lost; the workspace is crash-consistent.", true)),
            "cloud.session.execution_lost", updateExecution = ended)
        return now
    }

    /** A running state without its execution row: preparation fails, otherwise the session pauses crash-consistent. */
    private suspend fun lostWithoutExecution(s: CloudSession, owner: String, now: Long): Long? {
        val problem = CloudProblem("execution_missing", "The session has no execution record.", true)
        // Without the row the pod name is unknown: remove any pod of this session before leaving the running states.
        val pods = cluster.managed(s.clusterId, s.namespace).pods.filter { it.name.startsWith("tc-${s.id}-e") }
        if (pods.isNotEmpty()) {
            pods.forEach { cluster.deletePod(s.clusterId, s.namespace, it.name, force = true) }
            return now + STEP_MILLIS
        }
        if (s.state == PREPARING) return fail(s, owner, now, problem)
        commit(s, owner, pausedCopy(s, now, WorkspaceResume.CRASH_CONSISTENT).copy(failure = problem), "cloud.session.execution_lost")
        return now
    }

    /** Asynchronous resume failure: back to paused with `resumeBlocked`, never `failed`; the workspace is kept. */
    private suspend fun block(s: CloudSession, owner: String, now: Long, execution: CloudExecution, problem: CloudProblem): Long? {
        if (!stopPod(s, execution, force = true)) return now + STEP_MILLIS
        // A failed preparation moves from `failure` to `resumeBlocked`: the session itself has not failed.
        commit(s, owner, pausedCopy(s, now, s.resumeWorkspace).copy(resumeBlocked = problem, failure = s.failure.takeUnless { s.preparationFailed }),
            "cloud.session.resume_blocked", updateExecution = execution.copy(state = ExecutionState.ENDED, endedAt = now, exitReason = problem.code))
        return now
    }

    /** First-execution failure: the workspace never held work, so it is deleted right away. */
    private suspend fun fail(s: CloudSession, owner: String, now: Long, problem: CloudProblem, workspace: WorkspaceResume = s.resumeWorkspace, ended: CloudExecution? = null): Long? {
        val execution = ended ?: if (s.currentEpoch > 0) store.execution(s.id, s.currentEpoch) else null
        if (execution != null && execution.state != ExecutionState.ENDED) {
            if (!stopPod(s, execution, force = true)) return now + STEP_MILLIS
        }
        val firstExecution = s.state == CREATING || s.state == PREPARING
        val settings = store.settings(s.teamId)
        val deleteAt = if (firstExecution) now else now + settings.failedWorkspaceRetentionDays * DAY_MILLIS
        val closed = execution?.takeIf { it.state != ExecutionState.ENDED || ended != null }?.let {
            it.copy(state = ExecutionState.ENDED, endedAt = it.endedAt ?: now, exitReason = it.exitReason ?: problem.code)
        }
        commit(s, owner, s.copy(state = FAILED, phase = null, failure = problem, resumeWorkspace = workspace, expiresAt = deleteAt, waitingSince = null, updatedAt = now),
            "cloud.session.failed", updateExecution = closed)
        return now
    }

    private suspend fun pausedCopy(s: CloudSession, now: Long, quality: WorkspaceResume): CloudSession {
        val retention = store.settings(s.teamId).pausedRetentionDays
        return s.copy(state = PAUSED, phase = null, desiredState = DesiredState.PAUSED, resumeWorkspace = quality, pausedAt = now,
            expiresAt = retention?.let { now + it * DAY_MILLIS }, pauseRequestedAt = null, pauseForce = false, waitingSince = null, updatedAt = now)
    }

    /** Requests deletion and reports whether the pod is confirmed gone. */
    private suspend fun stopPod(s: CloudSession, execution: CloudExecution, force: Boolean): Boolean {
        if (cluster.pod(s.clusterId, s.namespace, execution.podName) == null) return true
        cluster.deletePod(s.clusterId, s.namespace, execution.podName, force)
        return cluster.pod(s.clusterId, s.namespace, execution.podName) == null
    }

    private suspend fun commit(s: CloudSession, owner: String, next: CloudSession, audit: String? = null, updateExecution: CloudExecution? = null): CloudSession? =
        transition.commit(s, CloudTransition(next, CloudWrite(leaseOwner = owner, auditAction = audit, updateExecution = updateExecution)))

    companion object {
        const val MANAGED_LABEL = "app.kubernetes.io/managed-by"
        const val SESSION_LABEL = "teamcode.nathanfallet.me/session"
        const val CLUSTER_LABEL = "teamcode.nathanfallet.me/cluster"
        const val EPOCH_LABEL = "teamcode.nathanfallet.me/epoch"
        const val TEAM_ENV = "TEAMCODE_CLOUD_TEAM"
        const val SESSION_ENV = "TEAMCODE_CLOUD_SESSION"
        const val EPOCH_ENV = "TEAMCODE_CLOUD_EPOCH"
        const val LEASE_MILLIS = 60_000L
        const val RETRY_MILLIS = 30_000L
        const val STEP_MILLIS = 2_000L
        const val CAPACITY_MILLIS = 5_000L
        const val MONITOR_MILLIS = 15_000L
        const val HEARTBEAT_LOSS_MILLIS = 90_000L
        const val PAUSING_DEADLINE_MILLIS = 15 * 60_000L
        const val START_TIMEOUT_MILLIS = 15 * 60_000L
        const val PREPARE_TIMEOUT_MILLIS = 30 * 60_000L
        const val IMAGE_PULL_GRACE_MILLIS = 5 * 60_000L
        const val VOLUME_DELETION_TIMEOUT_MILLIS = 60 * 60_000L
        const val BLOCKED_RECHECK_MILLIS = 10 * 60_000L
        const val DAY_MILLIS = 86_400_000L
        /** Pod reasons that no retry can fix. */
        val IMAGE_FAILURES = setOf("InvalidImageName", "ErrImageNeverPull")
        /** Pod reasons the kubelet keeps retrying (registry outage, rate limit, missing tag). */
        val IMAGE_PULL_RETRIES = setOf("ErrImagePull", "ImagePullBackOff")
        fun labels(s: CloudSession) = mapOf(MANAGED_LABEL to "teamcode", SESSION_LABEL to s.id, CLUSTER_LABEL to s.clusterId)
    }
}
