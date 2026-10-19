package me.nathanfallet.teamcode.domain.cloud

import me.nathanfallet.teamcode.domain.team.Member
import me.nathanfallet.teamcode.domain.team.Principal

/** What a compare-and-set commits besides the session row itself, in the same transaction. */
data class CloudWrite(
    /** Revalidated against current SQL state (role, revocation, expiry) inside the write transaction. */
    val actor: CloudActor? = null,
    /** Only the current reconciler lease owner may commit; a stale owner is fenced. */
    val leaseOwner: String? = null,
    /** Metadata-only audit action, journaled with initiator, credential and effective member. */
    val auditAction: String? = null,
    /** Replace the stored execution with the same `(session, epoch)`. */
    val updateExecution: CloudExecution? = null,
    /** Next reconciliation time; null leaves the session idle until the next API write. */
    val reconcileAt: Long? = null,
)

sealed interface StartOutcome {
    data class Started(val session: CloudSession) : StartOutcome
    data object WaitingCapacity : StartOutcome
    /** Another writer changed the session or the lease was lost. */
    data object Conflict : StartOutcome
}

sealed interface CloudCreateOutcome {
    data class Created(val session: CloudSession) : CloudCreateOutcome
    data class Replayed(val session: CloudSession) : CloudCreateOutcome
}

/** Persistence of sessions and executions. Every mutation is a conditional update on `generation`. */
interface CloudSessionStore {
    /** The database clock, shared by every replica. */
    suspend fun now(): Long
    /** Fails with 401 when the principal was revoked, expired, disabled or changed role. */
    suspend fun revalidate(actor: Principal)
    suspend fun member(teamId: String, memberId: String): Member?
    suspend fun profile(teamId: String, profileId: String): CloudProfile?
    suspend fun profileVersion(teamId: String, profileId: String, version: Int): CloudProfileSpec?
    suspend fun cluster(teamId: String, clusterId: String): CloudCluster?
    suspend fun settings(teamId: String): CloudSettings

    /**
     * Inserts a new session after checking the effective member's retained-session cap under its row lock.
     * With [idempotency], the record is written in the same transaction; a repeated key with the same digest
     * replays the stored session, and a different digest fails with `idempotency_conflict`.
     */
    suspend fun insert(session: CloudSession, actor: CloudActor, idempotency: IdempotencyRequest?, retainedLimit: Int): CloudCreateOutcome
    suspend fun find(teamId: String, id: String): CloudSession?
    suspend fun list(teamId: String, query: CloudSessionQuery, viewer: String?): CloudSessionPage
    suspend fun executions(teamId: String, sessionId: String): List<CloudExecution>
    suspend fun execution(sessionId: String, epoch: Int): CloudExecution?

    /** Returns the committed row (generation + 1), or null when [expected] is no longer current. */
    suspend fun compareAndSet(expected: CloudSession, next: CloudSession, write: CloudWrite = CloudWrite()): CloudSession?

    /** Claims up to [limit] due, unleased sessions for [owner] until database time + [leaseMillis]. */
    suspend fun claimDue(owner: String, leaseMillis: Long, limit: Int): List<CloudSession>
    /** Releases only a lease still held by [owner]; a stale owner cannot release its successor's lease. */
    suspend fun release(sessionId: String, owner: String, reconcileAt: Long?)

    /**
     * Starts the next epoch: in one transaction serialized on the cluster row, checks the cluster's
     * `maxRunningPods`, the owner's running cap and FIFO order (no older waiting session on the cluster whose
     * owner is under cap), then advances `currentEpoch` by compare-and-set and inserts [execution]. Its
     * `(session, epoch)` key is unique, so an epoch can start at most once.
     */
    suspend fun startExecution(expected: CloudSession, next: CloudSession, execution: CloudExecution, leaseOwner: String, memberRunningLimit: Int): StartOutcome

    /** Records a heartbeat for the current execution; false when the epoch is fenced. */
    suspend fun heartbeat(sessionId: String, epoch: Int, runnerReady: Boolean): Boolean

    /** Sessions (live or not) known for a cluster, for garbage collection of orphaned objects. */
    suspend fun liveObjects(clusterId: String): LiveObjects
    suspend fun recordGarbageCollection(clusterId: String, orphanedVolumes: Int)
}
/** Workspace claims not yet deleted and current-epoch pods. Anything else labelled by TeamCode for the cluster is orphaned. */
data class LiveObjects(val claims: Set<String>, val pods: Set<String>)

data class WorkspaceRequest(val namespace: String, val claimName: String, val storageClass: String?, val sizeGibibytes: Int, val labels: Map<String, String>)
data class PodRequest(
    val namespace: String,
    val name: String,
    val image: String,
    val claimName: String,
    val labels: Map<String, String>,
    val resources: CloudResources,
    val imagePullSecrets: List<String>,
)
enum class ClaimPhase { PENDING, BOUND, LOST }
data class ClaimObservation(val phase: ClaimPhase, val volumeName: String?, val storageClass: String?)
data class VolumeObservation(val name: String, val phase: String, val reclaimPolicy: String, val volumeHandle: String?, val claimNamespace: String?, val claimName: String?, val managed: Boolean)
data class StorageClassObservation(val name: String, val reclaimPolicy: String, val volumeBindingMode: String?)
enum class PodPhase { PENDING, RUNNING, SUCCEEDED, FAILED, UNKNOWN }
data class PodObservation(
    val name: String,
    val phase: PodPhase,
    val epoch: Int?,
    val nodeName: String?,
    val imageId: String?,
    val terminating: Boolean,
    /** Sanitized, bounded reason (for example `ImagePullBackOff`); never a raw API body. */
    val reason: String?,
)
data class ManagedObjects(val pods: List<PodObservation>, val claims: List<String>)
data class TokenIdentity(val namespace: String, val serviceAccount: String, val podName: String?, val podUid: String?)

/**
 * The cluster boundary. All calls are idempotent: creating an existing object succeeds and deleting a missing
 * one succeeds. No implementation may hold a database transaction while calling the cluster.
 */
interface ClusterPort {
    suspend fun storageClass(clusterId: String, name: String?): StorageClassObservation?
    suspend fun ensureClaim(clusterId: String, request: WorkspaceRequest)
    suspend fun claim(clusterId: String, namespace: String, name: String): ClaimObservation?
    suspend fun deleteClaim(clusterId: String, namespace: String, name: String)
    suspend fun volume(clusterId: String, name: String): VolumeObservation?
    /** Released or failed volumes whose claim was a TeamCode workspace. */
    suspend fun orphanedVolumes(clusterId: String): List<VolumeObservation>
    suspend fun deleteVolume(clusterId: String, name: String)
    suspend fun createPod(clusterId: String, request: PodRequest)
    suspend fun pod(clusterId: String, namespace: String, name: String): PodObservation?
    suspend fun deletePod(clusterId: String, namespace: String, name: String, force: Boolean)
    suspend fun managed(clusterId: String, namespace: String): ManagedObjects
    /** Verifies a projected service-account token through the cluster's TokenReview API. */
    suspend fun reviewToken(clusterId: String, token: String, audience: String): TokenIdentity?
}

/** Administration of clusters, profiles and settings. */
interface CloudAdminStore {
    suspend fun clusters(actor: Principal): List<CloudCluster>
    suspend fun saveCluster(actor: Principal, id: String?, expectedVersion: Int?, input: CloudClusterInput): CloudCluster
    suspend fun profiles(actor: Principal): List<CloudProfile>
    /** Publishes a new immutable version; [expectedVersion] must equal the current one (0 for a new profile). */
    suspend fun saveProfile(actor: Principal, id: String?, expectedVersion: Int, name: String, spec: CloudProfileSpec, assignedMemberIds: Set<String>): CloudProfile
    suspend fun settings(actor: Principal): CloudSettings
    suspend fun saveSettings(actor: Principal, settings: CloudSettings): CloudSettings
}
