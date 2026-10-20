package me.nathanfallet.teamcode.api.models

import kotlinx.serialization.Serializable

/** Wire names of the closed session state enum. `phase` is open: clients must tolerate unknown values. */
object CloudSessionStates {
    val all = listOf("creating", "preparing", "ready", "active", "pausing", "paused", "resuming", "transferring", "terminated", "failed")
}

@Serializable
data class CloudAttributionView(val initiatorId: String, val credentialId: String, val effectiveMemberId: String)
@Serializable
data class CloudProfileRef(val id: String, val version: Int)
@Serializable
data class CloudImageView(val reference: String, val digest: String? = null)
@Serializable
data class CloudAgentView(val kind: String, val version: String? = null, val model: String? = null, val effort: String? = null)
@Serializable
data class CloudActivityView(val lastActivityAt: Long, val idleTimeoutMinutes: Int)
@Serializable
data class CloudResumeView(val workspace: String, val conversation: String)
@Serializable
data class CloudProblemView(val code: String, val message: String, val retryable: Boolean = false)
/** Deletion proof: `proven` only once the claim and the bound provider volume are both confirmed gone. */
@Serializable
data class CloudWorkspaceDeletionView(
    val state: String,
    val claimDeletedAt: Long? = null,
    val volumeDeletedAt: Long? = null,
    val volumeHandle: String? = null,
    val problem: CloudProblemView? = null,
)

@Serializable
data class CloudSessionView(
    val id: String,
    val name: String,
    val ownerId: String,
    val createdBy: CloudAttributionView,
    val state: String,
    val phase: String? = null,
    val desiredState: String,
    val generation: Long,
    val currentEpoch: Int,
    val profile: CloudProfileRef,
    val image: CloudImageView,
    val agent: CloudAgentView,
    val labels: Map<String, String> = emptyMap(),
    val activity: CloudActivityView,
    val resume: CloudResumeView,
    val failure: CloudProblemView? = null,
    val resumeBlocked: CloudProblemView? = null,
    val workspaceDeletion: CloudWorkspaceDeletionView,
    val createdAt: Long,
    val updatedAt: Long,
    val pausedAt: Long? = null,
    /** When a paused session's workspace will be deleted, or when a failed session's workspace is deleted. */
    val expiresAt: Long? = null,
    val terminatedReason: String? = null,
)
@Serializable
data class CloudSessionPageView(val sessions: List<CloudSessionView>, val nextBefore: String? = null)

@Serializable
data class CloudAgentRequest(val model: String? = null, val effort: String? = null)
@Serializable
data class CreateCloudSessionRequest(
    val name: String,
    val profileId: String,
    val image: String? = null,
    val agent: CloudAgentRequest? = null,
    val labels: Map<String, String> = emptyMap(),
    val idleTimeoutMinutes: Int? = null,
)
@Serializable
data class PauseCloudSessionRequest(val force: Boolean = false)

@Serializable
data class CloudExecutionView(
    val epoch: Int,
    val podName: String,
    val state: String,
    val createdAt: Long,
    val registeredAt: Long? = null,
    val lastHeartbeatAt: Long? = null,
    val nodeName: String? = null,
    val imageDigest: String? = null,
    val endedAt: Long? = null,
    val exitReason: String? = null,
    val checkpoint: String? = null,
)

@Serializable
data class CloudResourcesView(val cpuMillis: Int = 1000, val memoryMebibytes: Int = 2048)
@Serializable
data class CloudProfileSpecView(
    val clusterId: String,
    val namespace: String,
    val image: String,
    val allowedImageOverrides: List<String> = emptyList(),
    val imagePullSecrets: List<String> = emptyList(),
    val resources: CloudResourcesView = CloudResourcesView(),
    val workspaceSizeGibibytes: Int = 10,
    val storageClass: String? = null,
    val agentKind: String = "claude",
    val agentVersion: String? = null,
    val idleTimeoutMinutes: Int = 30,
    val minIdleTimeoutMinutes: Int = 5,
    val maxIdleTimeoutMinutes: Int = 1440,
)
@Serializable
data class CloudProfileView(val id: String, val name: String, val version: Int, val spec: CloudProfileSpecView, val assignedMemberIds: List<String>, val createdAt: Long, val updatedAt: Long)
/** `version` is the current version being replaced (0 for a new profile); each save publishes an immutable version. */
@Serializable
data class SaveCloudProfileRequest(val name: String, val version: Int = 0, val spec: CloudProfileSpecView, val assignedMemberIds: List<String> = emptyList())

@Serializable
data class CloudClusterView(
    val id: String,
    val name: String,
    val apiUrl: String,
    val allowedNamespaces: List<String>,
    val maxRunningPods: Int,
    val defaultStorageClass: String? = null,
    val version: Int,
    /** Provider volumes left behind by TeamCode workspaces (for example under a Retain policy); act on them manually. */
    val orphanedVolumes: Int = 0,
    val lastGarbageCollectionAt: Long? = null,
    val createdAt: Long,
    val updatedAt: Long,
)
/** On update, null `caCertificate`/`serviceAccountToken` keep the stored values; `version` must match. */
@Serializable
class SaveCloudClusterRequest(
    val name: String,
    val apiUrl: String,
    val caCertificate: String? = null,
    val serviceAccountToken: String? = null,
    val allowedNamespaces: List<String>,
    val maxRunningPods: Int = 10,
    val defaultStorageClass: String? = null,
    val version: Int? = null,
) {
    override fun toString() = "SaveCloudClusterRequest(name=$name, apiUrl=$apiUrl, serviceAccountToken=[REDACTED])"
}

/** Persistent administrator settings; every field is required so a partial update cannot reset the others. `pausedRetentionDays = null` keeps paused workspaces indefinitely. */
@Serializable
data class CloudSettingsView(
    val pausedRetentionDays: Int?,
    val failedWorkspaceRetentionDays: Int,
    val maxRunningSessionsPerMember: Int,
    val maxRetainedSessionsPerMember: Int,
    /** Days transcript events and finished messages are kept; null keeps them indefinitely. */
    val transcriptRetentionDays: Int?,
)

object CloudHeaders {
    const val ON_BEHALF_OF = "TeamCode-On-Behalf-Of"
    const val IDEMPOTENCY_KEY = "Idempotency-Key"
    const val IDEMPOTENCY_REPLAYED = "Idempotency-Replayed"
    val idempotencyKey = Regex("[A-Za-z0-9._:-]{1,128}")
}

/** Runner channel. The runner authenticates with its projected service-account token, never a member credential. */
@Serializable
data class RunnerRegisterRequest(val teamId: String, val sessionId: String, val epoch: Int)
@Serializable
data class RunnerHeartbeatRequest(val teamId: String, val sessionId: String, val epoch: Int, val ready: Boolean)
@Serializable
data class RunnerCheckpointRequest(val teamId: String, val sessionId: String, val epoch: Int, val completed: Boolean)
/** What the runner must do next: keep running, or pause before [pauseDeadline] (epoch milliseconds). */
@Serializable
data class RunnerInstructionView(val desiredState: String, val pauseDeadline: Long? = null, val epoch: Int)
