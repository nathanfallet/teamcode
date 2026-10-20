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
    val profileId = reference("profile_id", CloudProfiles.id, onDelete = ReferenceOption.NO_ACTION, onUpdate = ReferenceOption.NO_ACTION, fkName = "cloud_sessions_profile")
    val profileVersion = integer("profile_version")
    val clusterId = reference("cluster_id", CloudClusters.id, onDelete = ReferenceOption.NO_ACTION, onUpdate = ReferenceOption.NO_ACTION, fkName = "cloud_sessions_cluster")
    val namespace = varchar("namespace", 63)
    val imageReference = varchar("image_reference", 512)
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
    override val primaryKey = PrimaryKey(id)
    init {
        index("cloud_session_owner", false, teamId, ownerId, createdAt)
        index("cloud_session_team", false, teamId, createdAt)
        index("cloud_session_due", false, reconcileAfter)
        index("cloud_session_cluster_waiting", false, clusterId, waitingSince)
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
