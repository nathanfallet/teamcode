package me.nathanfallet.teamcode.infrastructure.database.tables

import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.Table

object CloudClusters : Table("cloud_clusters") {
    val id = varchar("id", 36)
    val teamId = reference("team_id", Teams.id, onDelete = ReferenceOption.NO_ACTION, onUpdate = ReferenceOption.NO_ACTION, fkName = "cloud_clusters_team")
    val name = varchar("name", 120)
    val apiUrl = varchar("api_url", 512)
    val caCertificate = text("ca_certificate")
    /** AES-GCM envelope bound to the cluster id; never returned by any API. */
    val credential = text("credential")
    val allowedNamespaces = text("allowed_namespaces")
    val maxRunningPods = integer("max_running_pods")
    val defaultStorageClass = varchar("default_storage_class", 253).nullable()
    val version = integer("version")
    val orphanedVolumes = integer("orphaned_volumes").default(0)
    val lastGarbageCollectionAt = long("last_gc_at").nullable()
    val createdAt = long("created_at")
    val updatedAt = long("updated_at")
    override val primaryKey = PrimaryKey(id)
    init { uniqueIndex("cloud_cluster_name", teamId, name) }
}
object CloudProfiles : Table("cloud_profiles") {
    val id = varchar("id", 36)
    val teamId = reference("team_id", Teams.id, onDelete = ReferenceOption.NO_ACTION, onUpdate = ReferenceOption.NO_ACTION, fkName = "cloud_profiles_team")
    val name = varchar("name", 120)
    val currentVersion = integer("current_version")
    val createdAt = long("created_at")
    val updatedAt = long("updated_at")
    override val primaryKey = PrimaryKey(id)
    init { uniqueIndex("cloud_profile_name", teamId, name) }
}
/** Immutable once published; sessions pin `(profile_id, version)`. */
object CloudProfileVersions : Table("cloud_profile_versions") {
    val profileId = reference("profile_id", CloudProfiles.id, onDelete = ReferenceOption.NO_ACTION, onUpdate = ReferenceOption.NO_ACTION, fkName = "cloud_profile_versions_profile")
    val version = integer("version")
    val spec = text("spec")
    val createdBy = varchar("created_by", 36)
    val createdAt = long("created_at")
    override val primaryKey = PrimaryKey(profileId, version)
}
object CloudProfileAssignments : Table("cloud_profile_assignments") {
    val profileId = reference("profile_id", CloudProfiles.id, onDelete = ReferenceOption.CASCADE, onUpdate = ReferenceOption.NO_ACTION, fkName = "cloud_profile_assignments_profile")
    val memberId = reference("member_id", Members.id, onDelete = ReferenceOption.CASCADE, onUpdate = ReferenceOption.NO_ACTION, fkName = "cloud_profile_assignments_member")
    override val primaryKey = PrimaryKey(profileId, memberId)
    init { index("cloud_assignment_member", false, memberId) }
}
object CloudSettingsTable : Table("cloud_settings") {
    val teamId = reference("team_id", Teams.id, onDelete = ReferenceOption.NO_ACTION, onUpdate = ReferenceOption.NO_ACTION, fkName = "cloud_settings_team")
    /** Null keeps paused workspaces indefinitely; a missing row means the documented defaults. */
    val pausedRetentionDays = integer("paused_retention_days").nullable()
    val failedWorkspaceRetentionDays = integer("failed_workspace_retention_days")
    val maxRunningSessionsPerMember = integer("max_running_sessions_per_member")
    val maxRetainedSessionsPerMember = integer("max_retained_sessions_per_member")
    /** Null keeps transcripts indefinitely. Existing rows get the 30-day default. */
    val transcriptRetentionDays = integer("transcript_retention_days").nullable().default(30)
    /** `offer` or `disabled`: whether owners who are not administrators may offer their sessions. */
    val memberTransfers = varchar("member_transfers", 16).default("offer")
    val transferOfferDays = integer("transfer_offer_days").default(7)
    /** `cancel` or `keep`: unconsumed messages when a session changes owner. */
    val transferPendingInputs = varchar("transfer_pending_inputs", 16).default("cancel")
    /** Minutes a cloud agent's permission request waits before the runner denies it; 0 waits without a deadline. */
    val permissionDeadlineMinutes = integer("permission_deadline_minutes").default(0)
    val remoteIdleTimeoutMinutes = integer("remote_idle_timeout_minutes").default(60)
    /** Largest file sent to a session and largest total per message, in MiB. */
    val fileMaxMebibytes = integer("file_max_mebibytes").default(20)
    val messageFileMebibytes = integer("message_file_mebibytes").default(25)
    /** Minutes background work keeps an idle session running, from the start of its unbroken stretch; 0 never. */
    val backgroundWorkMaxMinutes = integer("background_work_max_minutes").default(240)
    override val primaryKey = PrimaryKey(teamId)
}
/** Whether cloud sessions run and how their pods are built; no row until an administrator or the initial seed saves it. */
object CloudRuntimeTable : Table("cloud_runtime") {
    val teamId = reference("team_id", Teams.id, onDelete = ReferenceOption.NO_ACTION, onUpdate = ReferenceOption.NO_ACTION, fkName = "cloud_runtime_team")
    val enabled = bool("enabled")
    val runnerImage = varchar("runner_image", 512).nullable()
    val runnerBinary = varchar("runner_binary", 256)
    /** Null: session pods call the server's public URL. */
    val serverUrl = varchar("server_url", 512).nullable()
    val workspaceAccessMode = varchar("workspace_access_mode", 32)
    val updatedBy = varchar("updated_by", 36)
    val updatedAt = long("updated_at")
    override val primaryKey = PrimaryKey(teamId)
}
object CloudSessions : Table("cloud_sessions") {
    val id = varchar("id", 36)
    val teamId = reference("team_id", Teams.id, onDelete = ReferenceOption.NO_ACTION, onUpdate = ReferenceOption.NO_ACTION, fkName = "cloud_sessions_team")
    val ownerId = reference("owner_id", Members.id, onDelete = ReferenceOption.NO_ACTION, onUpdate = ReferenceOption.NO_ACTION, fkName = "cloud_sessions_owner")
    val name = varchar("name", 120)
    val initiatorId = varchar("initiator_id", 36)
    val credentialId = varchar("credential_id", 36)
    val effectiveMemberId = varchar("effective_member_id", 36)
    /** Null, like the cluster, namespace and image, for a remote session (its profile version and workspace size are 0, its claim name empty). */
    val profileId = optReference("profile_id", CloudProfiles.id, onDelete = ReferenceOption.NO_ACTION, onUpdate = ReferenceOption.NO_ACTION, fkName = "cloud_sessions_profile")
    val profileVersion = integer("profile_version")
    val clusterId = optReference("cluster_id", CloudClusters.id, onDelete = ReferenceOption.NO_ACTION, onUpdate = ReferenceOption.NO_ACTION, fkName = "cloud_sessions_cluster")
    val namespace = varchar("namespace", 63).nullable()
    val imageReference = varchar("image_reference", 512).nullable()
    val imageDigest = varchar("image_digest", 100).nullable()
    val agentKind = varchar("agent_kind", 16)
    val agentVersion = varchar("agent_version", 64).nullable()
    val agentModel = varchar("agent_model", 120).nullable()
    val agentEffort = varchar("agent_effort", 120).nullable()
    val state = varchar("state", 16)
    val phase = varchar("phase", 64).nullable()
    val desiredState = varchar("desired_state", 16)
    val generation = long("generation")
    val currentEpoch = integer("current_epoch")
    val idleTimeoutMinutes = integer("idle_timeout_minutes")
    val lastActivityAt = long("last_activity_at")
    val resumeWorkspace = varchar("resume_workspace", 20)
    val resumeConversation = varchar("resume_conversation", 20)
    val failureCode = varchar("failure_code", 64).nullable()
    val failureMessage = varchar("failure_message", 512).nullable()
    val failureRetryable = bool("failure_retryable").default(false)
    val blockedCode = varchar("blocked_code", 64).nullable()
    val blockedMessage = varchar("blocked_message", 512).nullable()
    val blockedRetryable = bool("blocked_retryable").default(false)
    val pauseRequestedAt = long("pause_requested_at").nullable()
    val pauseForce = bool("pause_force").default(false)
    val pausedAt = long("paused_at").nullable()
    val expiresAt = long("expires_at").nullable()
    val terminatedReason = varchar("terminated_reason", 32).nullable()
    val waitingSince = long("waiting_since").nullable()
    val claimName = varchar("claim_name", 63)
    val storageClass = varchar("storage_class", 253).nullable()
    val workspaceSize = integer("workspace_size_gib")
    val volumeName = varchar("volume_name", 253).nullable()
    val volumeHandle = varchar("volume_handle", 512).nullable()
    val deletionState = varchar("deletion_state", 16)
    val claimDeletedAt = long("claim_deleted_at").nullable()
    val volumeDeletedAt = long("volume_deleted_at").nullable()
    val deletionCode = varchar("deletion_code", 64).nullable()
    val deletionMessage = varchar("deletion_message", 512).nullable()
    val reconcileOwner = varchar("reconcile_owner", 36).nullable()
    val reconcileUntil = long("reconcile_until").default(0)
    /** Next due time; null when the session needs no further reconciliation until the next write. */
    val reconcileAfter = long("reconcile_after").nullable()
    /** Set by a write during a pass so the lease release schedules another pass immediately. */
    val reconcileRequested = bool("reconcile_requested").default(false)
    /** The resolved preparation recipe as JSON, fixed at creation; never holds a secret. Null for an empty recipe. */
    val environment = text("environment").nullable()
    val createdAt = long("created_at")
    val updatedAt = long("updated_at")
    /** The accepted transfer the session is moving toward, until its owner changed. */
    val transferId = varchar("transfer_id", 36).nullable()
    /**
     * The newest open permission request: written only by the conversation store, under the session row lock, with the
     * transcript event that opens or closes it; the session store reads these columns and never writes them.
     */
    val awaitingReason = varchar("awaiting_reason", 16).nullable()
    val awaitingRequestId = varchar("awaiting_request_id", 128).nullable()
    val awaitingSince = long("awaiting_since").nullable()
    val awaitingEventSeq = long("awaiting_event_seq").nullable()
    val awaitingEpoch = integer("awaiting_epoch").nullable()
    /** `cloud` (a pod) or `remote` (a member's device); fixed at creation. */
    val kind = varchar("kind", 16).default("cloud")
    /** Whose instructions an agent holding the owner's GitHub token follows: `owner`, `shared` or `none` (audit M2). */
    val githubAccess = varchar("github_access", 16).default("owner")
    /** The device a remote session runs on; null for cloud sessions. */
    val hostDeviceId = optReference("host_device_id", Devices.id, onDelete = ReferenceOption.NO_ACTION, onUpdate = ReferenceOption.NO_ACTION, fkName = "cloud_sessions_host_device")
    /** The last segment of a remote session's directory, never its full path. */
    val hostDirectoryName = varchar("host_directory_name", 255).nullable()
    /** A remote session's permission mode on its host (`ask` or `bypass`); null for cloud sessions. */
    val permissionMode = varchar("permission_mode", 16).nullable()
    /** The published folder a device's daemon was asked to start the session in (its key, never a path); null otherwise. */
    val hostFolderId = varchar("host_folder_id", 64).nullable()
    override val primaryKey = PrimaryKey(id)
    init {
        index("cloud_session_owner", false, teamId, ownerId, createdAt)
        index("cloud_session_team", false, teamId, createdAt)
        // A list of one kind reads these instead of filtering the rows of the other kind after the lookup.
        index("cloud_session_owner_kind", false, teamId, ownerId, kind, createdAt)
        index("cloud_session_team_kind", false, teamId, kind, createdAt)
        index("cloud_session_due", false, reconcileAfter)
        index("cloud_session_cluster_waiting", false, clusterId, waitingSince)
        // The daemon's queue: a device's sessions waiting for their host, oldest request first.
        index("cloud_session_host_waiting", false, hostDeviceId, phase, desiredState, updatedAt)
    }
}
object CloudSessionLabels : Table("cloud_session_labels") {
    val sessionId = reference("session_id", CloudSessions.id, onDelete = ReferenceOption.CASCADE, onUpdate = ReferenceOption.NO_ACTION, fkName = "cloud_session_labels_session")
    val key = varchar("label_key", 63)
    val value = varchar("label_value", 256)
    override val primaryKey = PrimaryKey(sessionId, key)
    init { index("cloud_label_lookup", false, key, value) }
}
/** One pod lifetime; `(session_id, epoch)` is unique, which fences duplicate starts across replicas. */
object CloudExecutions : Table("cloud_executions") {
    val sessionId = reference("session_id", CloudSessions.id, onDelete = ReferenceOption.CASCADE, onUpdate = ReferenceOption.NO_ACTION, fkName = "cloud_executions_session")
    val epoch = integer("epoch")
    val podName = varchar("pod_name", 63)
    val state = varchar("state", 16)
    val createdAt = long("created_at")
    val registeredAt = long("registered_at").nullable()
    val lastHeartbeatAt = long("last_heartbeat_at").nullable()
    val runnerReady = bool("runner_ready").default(false)
    val nodeName = varchar("node_name", 253).nullable()
    val imageDigest = varchar("image_digest", 100).nullable()
    val endedAt = long("ended_at").nullable()
    val exitReason = varchar("exit_reason", 64).nullable()
    val checkpoint = varchar("checkpoint", 16).nullable()
    /** The owner the execution ran for; null for executions recorded before transfers existed. */
    val ownerId = varchar("owner_id", 36).nullable()
    /** The agent's background work reported by heartbeats: start of the current or last unbroken stretch, and its last report. Written by heartbeats only. */
    val backgroundSince = long("background_since").nullable()
    val backgroundSeenAt = long("background_seen_at").nullable()
    override val primaryKey = PrimaryKey(sessionId, epoch)
}
/** The stored outcome of a mutating request, replayed for 24 hours. */
object CloudIdempotency : Table("cloud_idempotency") {
    val teamId = reference("team_id", Teams.id, onDelete = ReferenceOption.NO_ACTION, onUpdate = ReferenceOption.NO_ACTION, fkName = "cloud_idempotency_team")
    val initiatorId = varchar("initiator_id", 36)
    val route = varchar("route", 120)
    val key = varchar("idempotency_key", 128, "ascii_bin")
    val requestDigest = char("request_digest", 64)
    val sessionId = varchar("session_id", 36)
    val createdAt = long("created_at")
    val expiresAt = long("expires_at")
    override val primaryKey = PrimaryKey(teamId, initiatorId, route, key)
    init { index("cloud_idempotency_expiry", false, expiresAt) }
}

/**
 * Ownership transfers. Rows are kept after completion: with the executions' owners they record which member each
 * part of a session's history belongs to. The idempotency key is kept 24 hours, then cleared.
 */
object CloudTransfers : Table("cloud_transfers") {
    val id = varchar("id", 36)
    val teamId = reference("team_id", Teams.id, onDelete = ReferenceOption.NO_ACTION, onUpdate = ReferenceOption.NO_ACTION, fkName = "cloud_transfers_team")
    val sessionId = reference("session_id", CloudSessions.id, onDelete = ReferenceOption.CASCADE, onUpdate = ReferenceOption.NO_ACTION, fkName = "cloud_transfers_session")
    val fromMemberId = varchar("from_member_id", 36)
    val toMemberId = varchar("to_member_id", 36)
    val initiatorId = varchar("initiator_id", 36)
    val credentialId = varchar("credential_id", 36)
    val effectiveMemberId = varchar("effective_member_id", 36)
    val state = varchar("state", 16)
    val resume = bool("resume")
    val force = bool("force_pause")
    val pendingInputs = varchar("pending_inputs", 16)
    val version = integer("version")
    val createdAt = long("created_at")
    val updatedAt = long("updated_at")
    val expiresAt = long("expires_at").nullable()
    val deciderId = varchar("decider_id", 36).nullable()
    val deciderCredentialId = varchar("decider_credential_id", 36).nullable()
    val deciderEffectiveMemberId = varchar("decider_effective_member_id", 36).nullable()
    val decidedAt = long("decided_at").nullable()
    val completedAt = long("completed_at").nullable()
    val atEpoch = integer("at_epoch").nullable()
    val eventSeq = long("event_seq").nullable()
    val failureCode = varchar("failure_code", 64).nullable()
    val failureMessage = varchar("failure_message", 512).nullable()
    val idempotencyKey = varchar("idempotency_key", 128, "ascii_bin").nullable()
    val requestDigest = char("request_digest", 64).nullable()
    override val primaryKey = PrimaryKey(id)
    init {
        uniqueIndex("cloud_transfer_idempotency", teamId, initiatorId, idempotencyKey)
        index("cloud_transfer_session", false, sessionId, state)
        index("cloud_transfer_from", false, teamId, fromMemberId, createdAt)
        index("cloud_transfer_to", false, teamId, toMemberId, createdAt)
        index("cloud_transfer_team", false, teamId, createdAt)
        index("cloud_transfer_expiry", false, state, expiresAt)
    }
}
