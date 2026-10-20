package me.nathanfallet.teamcode.domain.cloud

import me.nathanfallet.teamcode.domain.team.Principal

/** Closed, stable lifecycle state. The informational `phase` string is open and may gain values. */
enum class CloudSessionState(val wire: String) {
    CREATING("creating"), PREPARING("preparing"), READY("ready"), ACTIVE("active"), PAUSING("pausing"), PAUSED("paused"),
    RESUMING("resuming"), TRANSFERRING("transferring"), TERMINATED("terminated"), FAILED("failed");

    val terminal get() = this == TERMINATED || this == FAILED
    companion object { fun parse(value: String) = entries.single { it.wire == value } }
}
enum class DesiredState(val wire: String) {
    RUNNING("running"), PAUSED("paused"), TERMINATED("terminated");
    companion object { fun parse(value: String) = entries.single { it.wire == value } }
}
enum class WorkspaceResume(val wire: String) {
    CONSISTENT("consistent"), CRASH_CONSISTENT("crash_consistent"), LOST("lost");
    companion object { fun parse(value: String) = entries.single { it.wire == value } }
}
enum class ConversationResume(val wire: String) {
    RESUMABLE("resumable"), TRANSCRIPT_ONLY("transcript_only"), NONE("none");
    companion object { fun parse(value: String) = entries.single { it.wire == value } }
}
enum class ExecutionState(val wire: String) {
    /** Row committed and epoch advanced; the pod may not exist yet. */
    PENDING("pending"),
    /** The runner registered for this epoch. */
    RUNNING("running"),
    ENDED("ended");
    companion object { fun parse(value: String) = entries.single { it.wire == value } }
}
/** Proof that the workspace claim and the provider volume behind it no longer exist. */
enum class DeletionState(val wire: String) {
    NONE("none"), PENDING("pending"), PROVEN("proven"),
    /** The volume cannot be proven deleted (for example a `Retain` reclaim policy); an administrator must act. */
    BLOCKED("blocked");
    companion object { fun parse(value: String) = entries.single { it.wire == value } }
}

data class CloudProblem(val code: String, val message: String, val retryable: Boolean = false)
data class Attribution(val initiatorId: String, val credentialId: String, val effectiveMemberId: String)

/**
 * The caller of a cloud operation. `effectiveMemberId` differs from the principal only when an
 * interactive administrator acts on behalf of another member (`TeamCode-On-Behalf-Of`).
 */
data class CloudActor(val principal: Principal, val effectiveMemberId: String) {
    val attribution get() = Attribution(principal.member.id, principal.tokenId, effectiveMemberId)
}

data class CloudWorkspace(
    val claimName: String,
    val storageClass: String?,
    val sizeGibibytes: Int,
    /** Bound PersistentVolume name, once observed. */
    val volumeName: String? = null,
    /** Provider volume identifier (for example a CSI volume handle), once observed. Never a credential. */
    val volumeHandle: String? = null,
    val deletion: DeletionState = DeletionState.NONE,
    val claimDeletedAt: Long? = null,
    val volumeDeletedAt: Long? = null,
    val deletionProblem: CloudProblem? = null,
)

data class CloudSession(
    val id: String,
    val teamId: String,
    val ownerId: String,
    val name: String,
    val createdBy: Attribution,
    val profileId: String,
    val profileVersion: Int,
    val clusterId: String,
    val namespace: String,
    val imageReference: String,
    val imageDigest: String?,
    val agentKind: String,
    val agentVersion: String?,
    val agentModel: String?,
    val agentEffort: String?,
    val labels: Map<String, String>,
    val state: CloudSessionState,
    val phase: String?,
    val desiredState: DesiredState,
    val generation: Long,
    val currentEpoch: Int,
    val idleTimeoutMinutes: Int,
    val lastActivityAt: Long,
    val resumeWorkspace: WorkspaceResume,
    val resumeConversation: ConversationResume,
    val failure: CloudProblem?,
    val resumeBlocked: CloudProblem?,
    val pauseRequestedAt: Long?,
    val pauseForce: Boolean,
    val pausedAt: Long?,
    val expiresAt: Long?,
    val terminatedReason: String?,
    /** Execution start blocked by capacity since this database time; FIFO order within the cluster. */
    val waitingSince: Long?,
    val workspace: CloudWorkspace,
    val createdAt: Long,
    val updatedAt: Long,
    /** The accepted transfer this session is moving toward; set from acceptance until the owner changed or the transfer ended. */
    val transferId: String? = null,
    /** The resolved preparation recipe, fixed at creation (profile version merged with the request). */
    val environment: CloudEnvironmentSpec = CloudEnvironmentSpec(),
) {
    /** The runner reported a failed preparation; the reconciler fails the session or blocks the resume. */
    val preparationFailed get() = failure?.code == CloudEnvironmentRules.PREPARATION_FAILED && (state == CloudSessionState.PREPARING || state == CloudSessionState.RESUMING)
}

data class CloudExecution(
    val sessionId: String,
    val epoch: Int,
    val podName: String,
    val state: ExecutionState,
    val createdAt: Long,
    val registeredAt: Long? = null,
    val lastHeartbeatAt: Long? = null,
    val runnerReady: Boolean = false,
    val nodeName: String? = null,
    val imageDigest: String? = null,
    val endedAt: Long? = null,
    val exitReason: String? = null,
    /** `completed` or `failed`, reported by the runner's checkpoint call. */
    val checkpoint: String? = null,
    /**
     * The session owner this execution ran for, fixed when it started. Work of the execution (inference, pod time)
     * is attributed to this member, so history stays with the former owner after a transfer. Null for executions
     * recorded before transfers existed: they predate any change of owner.
     */
    val ownerId: String? = null,
)

data class CloudResources(val cpuMillis: Int, val memoryMebibytes: Int)

/** An immutable published profile version. Only fields used by the lifecycle are modeled in this release. */
data class CloudProfileSpec(
    val clusterId: String,
    val namespace: String,
    val image: String,
    val allowedImageOverrides: List<String> = emptyList(),
    val imagePullSecrets: List<String> = emptyList(),
    val resources: CloudResources = CloudResources(1000, 2048),
    val workspaceSizeGibibytes: Int = 10,
    val storageClass: String? = null,
    val agentKind: String = "claude",
    val agentVersion: String? = null,
    val idleTimeoutMinutes: Int = 30,
    val minIdleTimeoutMinutes: Int = 5,
    val maxIdleTimeoutMinutes: Int = 1440,
    val environment: CloudEnvironmentSpec = CloudEnvironmentSpec(),
)
data class CloudProfile(
    val id: String,
    val teamId: String,
    val name: String,
    val version: Int,
    val spec: CloudProfileSpec,
    /** Members allowed to use this profile. Administrators may always use any profile. */
    val assignedMemberIds: Set<String>,
    val createdAt: Long,
    val updatedAt: Long,
)

/** A connected cluster. Its service-account credential stays encrypted in persistence and never reaches the domain. */
data class CloudCluster(
    val id: String,
    val teamId: String,
    val name: String,
    val apiUrl: String,
    val allowedNamespaces: List<String>,
    val maxRunningPods: Int,
    val defaultStorageClass: String?,
    val version: Int,
    val orphanedVolumes: Int,
    val lastGarbageCollectionAt: Long?,
    val createdAt: Long,
    val updatedAt: Long,
)
data class CloudClusterInput(
    val name: String,
    val apiUrl: String,
    /** PEM bundle; null keeps the stored CA on update. */
    val caCertificate: String?,
    /** Service-account bearer token; null keeps the stored credential on update. */
    val serviceAccountToken: String?,
    val allowedNamespaces: List<String>,
    val maxRunningPods: Int,
    val defaultStorageClass: String?,
) {
    override fun toString() = "CloudClusterInput(name=$name, apiUrl=$apiUrl, serviceAccountToken=[REDACTED])"
}

/** Persistent administrator settings for cloud sessions, edited like the model-policy experiment flag. */
data class CloudSettings(
    /** Days a paused session keeps its workspace before deletion; null keeps it indefinitely. */
    val pausedRetentionDays: Int? = 30,
    /** Days a failed session keeps its workspace before deletion. */
    val failedWorkspaceRetentionDays: Int = 7,
    val maxRunningSessionsPerMember: Int = 2,
    val maxRetainedSessionsPerMember: Int = 20,
    /** Days transcript events and finished messages are kept, counted from each event; null keeps them indefinitely. Independent of pods and workspaces. */
    val transcriptRetentionDays: Int? = 30,
    /** Whether an owner who is not an administrator may offer a session to another member. Administrators always transfer directly. */
    val memberTransfers: MemberTransfers = MemberTransfers.OFFER,
    /** Days a recipient has to accept an offer. */
    val transferOfferDays: Int = 7,
    /** What happens to messages still queued or delivered but unconsumed when the owner changes. */
    val transferPendingInputs: TransferPendingInputs = TransferPendingInputs.CANCEL,
)
enum class MemberTransfers(val wire: String) {
    DISABLED("disabled"), OFFER("offer");
    companion object { fun parse(value: String) = entries.singleOrNull { it.wire == value } }
}
enum class TransferPendingInputs(val wire: String) {
    /** Cancelled with reason `transferred`: the former owner's instructions never run under the new owner. */
    CANCEL("cancel"),
    /** Kept queued and delivered to the next execution, under the new owner. */
    KEEP("keep");
    companion object { fun parse(value: String) = entries.singleOrNull { it.wire == value } }
}

data class CloudSessionQuery(
    /** null: the caller's own sessions; "*": every team session (administrators only); otherwise a member id. */
    val owner: String? = null,
    val states: Set<CloudSessionState> = emptySet(),
    val labels: Map<String, String> = emptyMap(),
    val beforeId: String? = null,
    val limit: Int = 50,
)
data class CloudSessionPage(val sessions: List<CloudSession>, val nextBeforeId: String?)

data class CloudSessionDraft(
    val name: String,
    val profileId: String,
    val image: String?,
    val agentModel: String?,
    val agentEffort: String?,
    val labels: Map<String, String>,
    val idleTimeoutMinutes: Int?,
    val environment: CloudEnvironmentOverride? = null,
    /** The first message for the agent, queued with the session and delivered once it is ready. */
    val initialMessage: String? = null,
)

/** Idempotency of a mutating request: scope is team + initiator + route + key, compared on a body digest. */
data class IdempotencyRequest(val route: String, val key: String, val requestDigest: String)
