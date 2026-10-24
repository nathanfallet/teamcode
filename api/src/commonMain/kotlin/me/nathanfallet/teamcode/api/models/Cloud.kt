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

/** A repository cloned into `/workspace/<path>` on the first execution and only fetched on resume. */
@Serializable
data class CloudRepositoryView(val url: String, val path: String, val ref: String? = null, val branch: String? = null)
/** `create` runs once per workspace, `start` on every execution, each with `/bin/sh -c` in the working directory. */
@Serializable
data class CloudSetupView(val create: List<String> = emptyList(), val start: List<String> = emptyList())
/** The non-secret preparation recipe of a profile, and the resolved copy a session keeps. */
@Serializable
data class CloudEnvironmentView(
    val repositories: List<CloudRepositoryView> = emptyList(),
    val workingDirectory: String? = null,
    val setup: CloudSetupView = CloudSetupView(),
    val requiredVariables: List<String> = emptyList(),
    val requireGitHub: Boolean = false,
    val gitEmailDomain: String? = null,
    /** Hosts repositories may be cloned from. */
    val repositoryHosts: List<String> = listOf("github.com"),
)
/** What a session request may change in its profile's recipe; null keeps the profile's value. */
@Serializable
data class CloudEnvironmentRequest(val repositories: List<CloudRepositoryView>? = null, val workingDirectory: String? = null)

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
    /** The accepted transfer the session is moving toward (`cloud.transfer.v1`); null otherwise. */
    val transferId: String? = null,
    val environment: CloudEnvironmentView = CloudEnvironmentView(),
    /** Set while the agent waits for a person (`cloud.sessions.waiting.v1`); null when nothing waits. */
    val waiting: CloudWaitingView? = null,
    /** `cloud` (a pod started by the server) or `remote` (a member's device); a server without this field has cloud sessions only. */
    val kind: String = CloudSessionKinds.CLOUD,
    /** Where a remote session runs; null for cloud sessions. */
    val host: CloudSessionHostView? = null,
)
object CloudSessionKinds {
    const val CLOUD = "cloud"
    const val REMOTE = "remote"
}
/**
 * The device of a remote session. [deviceId] is null once the device was removed; [deviceName], [platform] and
 * [osVersion] describe the device as it is now ([platform]: `macos`, `windows`, `linux`, `ios` or `android`, null
 * until the installation reported it). [directoryName] is the last segment of the session's directory, never its path.
 * [online]: the host runs the session now (an execution registered and heartbeating; the server notices a silent host
 * within 90 s), with [lastHeartbeatAt]. [permissionMode]: `ask` (Claude Code's permission requests come to the team's
 * clients, Codex stays in its sandbox limited to the directory) or `bypass`.
 */
@Serializable
data class CloudSessionHostView(
    val deviceId: String? = null,
    val deviceName: String? = null,
    val platform: String? = null,
    val osVersion: String? = null,
    val directoryName: String? = null,
    val online: Boolean = false,
    val lastHeartbeatAt: Long? = null,
    val permissionMode: String? = null,
    /** The published folder the device's daemon started the session in (`cloud.sessions.remote.daemon.v1`); null otherwise. */
    val folderId: String? = null,
)
/**
 * What the agent of a session waits for: the open `permission.request` at [eventSeq] in its transcript, since [since]
 * (server time, milliseconds). [reason] is `permission` (a tool use to approve) or `question` (a question to answer);
 * [requestId] is what `POST /cloud/sessions/{id}/permissions` takes: always present in this version (a request with an id
 * that route would refuse is denied by the runner at once and never shows), optional for later reasons to wait.
 * When several requests are open, the most recent one.
 */
@Serializable
data class CloudWaitingView(val reason: String, val requestId: String? = null, val since: Long, val eventSeq: Long)

object RemotePermissionModes {
    const val ASK = "ask"
    const val BYPASS = "bypass"
}
@Serializable
data class CloudSessionPageView(val sessions: List<CloudSessionView>, val nextBefore: String? = null)

/** [kind]: the agent of a remote session (`claude` or `codex`); a cloud session takes its profile's agent. */
@Serializable
data class CloudAgentRequest(val model: String? = null, val effort: String? = null, val kind: String? = null)
/**
 * A cloud session (with [profileId]) or, with [kind] `remote` (`cloud.sessions.remote.v1`), a session on the caller's own
 * device: no profile, image, idle timeout or environment; [agent] names the agent, [directoryName] is the last segment
 * of the directory it works in (never its path) and [permissionMode] is `ask` (the default) or `bypass`.
 */
@Serializable
data class CreateCloudSessionRequest(
    val name: String,
    val profileId: String? = null,
    val image: String? = null,
    val agent: CloudAgentRequest? = null,
    val labels: Map<String, String> = emptyMap(),
    val idleTimeoutMinutes: Int? = null,
    val environment: CloudEnvironmentRequest? = null,
    /** The first message for the agent (`cloud.sessions.initial-message.v1`), delivered once the session is ready. */
    val initialMessage: String? = null,
    val kind: String? = null,
    val directoryName: String? = null,
    val permissionMode: String? = null,
    /**
     * With [kind] `remote` (`cloud.sessions.remote.daemon.v1`): one of the caller's own devices, whose daemon starts the
     * session in [folderId], one of the folders it published (`GET /devices/{id}/folders`). Omit [directoryName]: it is
     * the folder's name. Without [deviceId], the session runs on the caller's own device.
     */
    val deviceId: String? = null,
    val folderId: String? = null,
)
/**
 * The execution a remote session's host just started (`POST /api/v1/cloud/sessions/{id}/host`): the host calls the
 * runner channel for [teamId], the session and [epoch] with the same login, until it is fenced.
 */
@Serializable
data class CloudHostView(val teamId: String, val epoch: Int, val session: CloudSessionView)
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
    /** The member this execution ran (and was charged) for; absent for executions recorded before transfers existed. */
    val ownerId: String? = null,
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
    val environment: CloudEnvironmentView = CloudEnvironmentView(),
)
/** [clusterName] is the name of `spec.clusterId`, filled by `GET /cloud/profiles` (null from an older server). */
@Serializable
data class CloudProfileView(val id: String, val name: String, val version: Int, val spec: CloudProfileSpecView, val assignedMemberIds: List<String>, val createdAt: Long, val updatedAt: Long, val clusterName: String? = null)
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

/** `found` or `missing` when the service account may read the namespace, `unknown` otherwise. */
@Serializable
data class CloudNamespaceChoiceView(val name: String, val status: String)
/** `keepsVolumes` is true for any reclaim policy but `Delete`: a profile on such a class is refused. */
@Serializable
data class CloudStorageClassChoiceView(val name: String, val reclaimPolicy: String, val default: Boolean, val keepsVolumes: Boolean, val provisioner: String? = null)
/** `status` is `listed`, `forbidden` (the service account lacks the optional right) or `unavailable` (the cluster could not be read). */
@Serializable
data class CloudStorageClassChoicesView(val status: String, val items: List<CloudStorageClassChoiceView> = emptyList())
/** Names only, of `kubernetes.io/dockerconfigjson` secrets; `status` as for storage classes. */
@Serializable
data class CloudImagePullSecretChoicesView(val status: String, val names: List<String> = emptyList())
/** What a profile on a cluster can choose from, read from the cluster's API. Publishing still checks every value. */
@Serializable
data class CloudClusterChoicesView(
    val clusterId: String,
    /** The namespace the storage classes and secrets were read for. */
    val namespace: String,
    val namespaces: List<CloudNamespaceChoiceView>,
    /** The cluster record's default class, used when a profile names none. */
    val defaultStorageClass: String? = null,
    val storageClasses: CloudStorageClassChoicesView,
    val imagePullSecrets: CloudImagePullSecretChoicesView,
)

/**
 * Persistent administrator settings; the retention and limit fields are required so a partial update cannot reset the others.
 * `pausedRetentionDays = null` keeps paused workspaces indefinitely. The transfer fields and [permissionDeadlineMinutes] are always
 * present in responses; in a `PUT`, an absent (null) one keeps the stored value, so a client written before they existed does not reset them.
 */
@Serializable
data class CloudSettingsView(
    val pausedRetentionDays: Int?,
    val failedWorkspaceRetentionDays: Int,
    val maxRunningSessionsPerMember: Int,
    val maxRetainedSessionsPerMember: Int,
    /** Days transcript events and finished messages are kept; null keeps them indefinitely. */
    val transcriptRetentionDays: Int?,
    /** `offer` (owners may offer their sessions; the recipient accepts) or `disabled` (administrators only). */
    val memberTransfers: String? = null,
    /** Days a recipient has to accept an offer (1 to 30). */
    val transferOfferDays: Int? = null,
    /** `cancel` or `keep`: unconsumed messages when a session changes owner. */
    val transferPendingInputs: String? = null,
    /**
     * Minutes a permission request of a cloud agent waits for an answer before it is denied (1 to 1440); 0 waits
     * without a deadline (the default). Absent (null) in a `PUT` keeps the stored value.
     */
    val permissionDeadlineMinutes: Int? = null,
    /**
     * Minutes without activity before a remote session pauses (`cloud.sessions.remote.v1`; 1 to 1440, 60 by default);
     * 0 never pauses one. Absent (null) in a `PUT` keeps the stored value.
     */
    val remoteIdleTimeoutMinutes: Int? = null,
    /** Largest file sent to a session, in MiB (`cloud.files.v1`; 1 to 100, 20 by default). Absent (null) in a `PUT` keeps the stored value. */
    val fileMaxMebibytes: Int? = null,
    /** Largest total of one message's files, in MiB (1 to 100, 25 by default). Absent (null) in a `PUT` keeps the stored value. */
    val messageFileMebibytes: Int? = null,
)

/**
 * Whether cloud sessions run on the server and how their pods are built (`GET/PUT /api/v1/cloud/runtime`, administrators).
 * Stored in the database; no field is a credential. `serverUrl = null` makes pods call the server's public URL, shown
 * as `effectiveServerUrl`, which a `PUT` ignores. A `PUT` must carry the `updatedAt` it read (null when nothing was stored yet):
 * a runtime changed meanwhile answers `409 cloud_runtime_conflict`.
 */
@Serializable
data class CloudRuntimeView(
    val enabled: Boolean,
    val runnerImage: String?,
    /** Required in a `PUT`, like every field but the two below: a `PUT` replaces the whole runtime. */
    val runnerBinary: String,
    val serverUrl: String?,
    /** `ReadWriteOncePod`, or `ReadWriteOnce` for storage drivers without it. */
    val workspaceAccessMode: String,
    val effectiveServerUrl: String? = null,
    val updatedAt: Long? = null,
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
@Serializable
data class RunnerInferenceRequest(val teamId: String, val sessionId: String, val epoch: Int)
/** Asks for the GitHub token of the member the execution runs for (`cloud.github.v1`). */
@Serializable
data class RunnerGitHubRequest(val teamId: String, val sessionId: String, val epoch: Int)
/** Asks for the environment variables of the member the execution runs for (`cloud.variables.v1`), while the session runs. */
@Serializable
data class RunnerVariablesRequest(val teamId: String, val sessionId: String, val epoch: Int)
/** A `tcex_` inference credential for the execution's owner, kept in the runner's memory only. */
@Serializable
data class RunnerInferenceCredentialView(val token: String, val expiresAt: Long, val expiresInMillis: Long? = null) {
    override fun toString() = "RunnerInferenceCredentialView(token=[REDACTED], expiresAt=$expiresAt, expiresInMillis=$expiresInMillis)"
}
/** Preparation: the runner reads its recipe and the owner's credentials, then reports progress or a failed step. */
@Serializable
data class RunnerEnvironmentRequest(val teamId: String, val sessionId: String, val epoch: Int)
@Serializable
data class RunnerGitIdentityView(val name: String, val email: String)
/** `status`: `linked` (with a GitHub App user token, its expiry and the linked account's `login`), `not_linked` or `unavailable`. */
@Serializable
class RunnerGitHubView(val status: String, val token: String? = null, val expiresAt: Long? = null, val login: String? = null) {
    override fun toString() = "RunnerGitHubView(status=$status, login=$login, token=[REDACTED])"
}
/** Holds the owner's variable values and GitHub token: never log, persist or echo it. */
@Serializable
class RunnerEnvironmentView(
    val environment: CloudEnvironmentView,
    val git: RunnerGitIdentityView,
    val variables: Map<String, String> = emptyMap(),
    val unavailableVariables: List<String> = emptyList(),
    val github: RunnerGitHubView,
    /**
     * Another member worked on this workspace since the owner's last prepared execution (a transfer). The runner then
     * treats the workspace as untrusted input: it resets the agent's home and each repository's Git configuration and
     * hooks before running anything with the owner's credentials.
     */
    val untrustedWorkspace: Boolean = false,
) {
    override fun toString() = "RunnerEnvironmentView(repositories=${environment.repositories.size}, variables=${variables.keys.sorted()}, github=${github.status}, untrustedWorkspace=$untrustedWorkspace)"
}
/** A failed step as structured fields only; the server writes the message, and command output never leaves the pod. */
@Serializable
data class RunnerPreparationFailureView(val step: String, val index: Int? = null, val exitCode: Int? = null)
@Serializable
data class RunnerPreparationRequest(val teamId: String, val sessionId: String, val epoch: Int, val phase: String, val failure: RunnerPreparationFailureView? = null)
