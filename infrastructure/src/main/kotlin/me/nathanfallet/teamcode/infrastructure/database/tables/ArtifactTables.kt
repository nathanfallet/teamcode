package me.nathanfallet.teamcode.infrastructure.database.tables

import org.jetbrains.exposed.v1.core.*

/** The team's S3-compatible bucket; [secret] is the secret access key sealed in the `artifact-storage:<teamId>` context. */
object ArtifactStorageTable : Table("artifact_storage") {
    val teamId = varchar("team_id", 36)
    val endpoint = varchar("endpoint", 512)
    val region = varchar("region", 64)
    val bucket = varchar("bucket", 63)
    val prefix = varchar("prefix", 200)
    val pathStyle = bool("path_style")
    val accessKeyId = varchar("access_key_id", 256)
    val secret = text("secret_envelope")
    val serverSideEncryption = varchar("server_side_encryption", 16)
    val verifiedAt = long("verified_at").nullable()
    val version = integer("version")
    val updatedAt = long("updated_at")
    override val primaryKey = PrimaryKey(teamId)
}

/** Switches, quotas and retention; absent means the defaults with artifacts off. */
object ArtifactSettingsTable : Table("artifact_settings") {
    val teamId = varchar("team_id", 36)
    val enabled = bool("enabled")
    val whoCanPublish = varchar("who_can_publish", 16)
    val publicSharing = varchar("public_sharing", 24)
    val memberBytes = long("quota_member_bytes")
    val teamBytes = long("quota_team_bytes")
    val artifactsPerMember = integer("quota_artifacts_per_member")
    val publishesPerHour = integer("quota_publishes_per_hour")
    val personalRetentionDays = integer("personal_retention_days").nullable()
    val sharedRetentionDays = integer("shared_retention_days").nullable()
    val maxVersionsPerArtifact = integer("max_versions_per_artifact")
    val version = integer("version")
    val updatedAt = long("updated_at")
    override val primaryKey = PrimaryKey(teamId)
}

/**
 * One artifact: owned by the member it was published for, whatever session or run it came from. [access] is
 * `personal`, `team` or `public`; [state] is `draft` (an id reserved by an upload, never committed), `active` or
 * `deleted` (404 at once, its storage folder removed afterwards: [storageDeletedAt] is the proof).
 */
object Artifacts : Table("artifacts") {
    val id = varchar("id", 36)
    val teamId = varchar("team_id", 36)
    val ownerId = varchar("owner_id", 36)
    val createdBy = varchar("created_by", 36)
    val credentialId = varchar("credential_id", 36).nullable()
    val originKind = varchar("origin_kind", 16)
    val originRunId = varchar("origin_run_id", 36).nullable()
    val originSessionId = varchar("origin_session_id", 36).nullable()
    val originEpoch = integer("origin_epoch").nullable()
    val title = varchar("title", 200)
    val description = varchar("description", 300)
    val icon = varchar("icon", 40)
    val access = varchar("access", 16)
    val publicFollowsLatest = bool("public_follows_latest")
    val publicVersion = integer("public_version").nullable()
    val currentVersion = integer("current_version")
    val state = varchar("state", 16)
    val storedBytes = long("stored_bytes")
    /** Compare-and-set counter for every change of the row. */
    val generation = long("generation")
    val createdAt = long("created_at")
    val updatedAt = long("updated_at")
    val publishedAt = long("published_at").nullable()
    val deletedAt = long("deleted_at").nullable()
    val storageDeletedAt = long("storage_deleted_at").nullable()
    override val primaryKey = PrimaryKey(id)
    init {
        index("artifacts_owner", false, teamId, ownerId, updatedAt)
        index("artifacts_team", false, teamId, state, updatedAt)
        index("artifacts_session", false, originSessionId)
        index("artifacts_run", false, originRunId)
        index("artifacts_state", false, state, updatedAt)
    }
}

/** Members invited to an artifact, as `viewer` or `editor`; [reason] is `invited` or `session_transfer`. */
object ArtifactMembers : Table("artifact_members") {
    val artifactId = reference("artifact_id", Artifacts.id, onDelete = ReferenceOption.CASCADE, onUpdate = ReferenceOption.NO_ACTION, fkName = "artifact_members_artifact")
    val memberId = varchar("member_id", 36)
    val role = varchar("role", 16)
    val reason = varchar("reason", 24)
    val addedBy = varchar("added_by", 36)
    val originSessionId = varchar("origin_session_id", 36).nullable()
    val addedAt = long("added_at")
    override val primaryKey = PrimaryKey(artifactId, memberId)
    init { index("artifact_members_member", false, memberId) }
}

/** Immutable versions; [manifestDigest] is the SHA-256 of the canonical manifest. */
object ArtifactVersions : Table("artifact_versions") {
    val id = varchar("id", 36)
    val artifactId = reference("artifact_id", Artifacts.id, onDelete = ReferenceOption.CASCADE, onUpdate = ReferenceOption.NO_ACTION, fkName = "artifact_versions_artifact")
    val number = integer("number")
    val entryPath = varchar("entry_path", 255)
    val manifestDigest = varchar("manifest_digest", 64)
    val fileCount = integer("file_count")
    val totalBytes = long("total_bytes")
    val createdBy = varchar("created_by", 36)
    val credentialId = varchar("credential_id", 36).nullable()
    val baseVersion = integer("base_version")
    val originSessionId = varchar("origin_session_id", 36).nullable()
    val originEpoch = integer("origin_epoch").nullable()
    val createdAt = long("created_at")
    override val primaryKey = PrimaryKey(id)
    init {
        uniqueIndex("artifact_versions_number", artifactId, number)
        index("artifact_versions_replay", false, artifactId, baseVersion, manifestDigest)
    }
}

/** The files of a version: published path to content hash. [role] is `page`, `html` or `asset`. */
object ArtifactVersionFiles : Table("artifact_version_files") {
    val versionId = reference("version_id", ArtifactVersions.id, onDelete = ReferenceOption.CASCADE, onUpdate = ReferenceOption.NO_ACTION, fkName = "artifact_version_files_version")
    val path = varchar("path", 255)
    val sha256 = varchar("sha256", 64)
    val size = long("size")
    val contentType = varchar("content_type", 100)
    val role = varchar("role", 8)
    override val primaryKey = PrimaryKey(versionId, path)
    init { index("artifact_version_files_blob", false, sha256) }
}

/** The blobs stored in an artifact's folder, for its byte count and its garbage collection. */
object ArtifactBlobs : Table("artifact_blobs") {
    val artifactId = reference("artifact_id", Artifacts.id, onDelete = ReferenceOption.CASCADE, onUpdate = ReferenceOption.NO_ACTION, fkName = "artifact_blobs_artifact")
    val sha256 = varchar("sha256", 64)
    val size = long("size")
    val storedAt = long("stored_at")
    val lastReferencedVersion = integer("last_referenced_version").nullable()
    override val primaryKey = PrimaryKey(artifactId, sha256)
}

/** An upload in progress, bound to the credential that started it, expired after an hour. */
object ArtifactUploads : Table("artifact_uploads") {
    val id = varchar("id", 36)
    val artifactId = reference("artifact_id", Artifacts.id, onDelete = ReferenceOption.CASCADE, onUpdate = ReferenceOption.NO_ACTION, fkName = "artifact_uploads_artifact")
    val teamId = varchar("team_id", 36)
    val memberId = varchar("member_id", 36)
    val credentialId = varchar("credential_id", 36)
    /** The announced `{sha256: size}` of the files, as JSON. */
    val files = text("files")
    val receivedBytes = long("received_bytes")
    val createdAt = long("created_at")
    val expiresAt = long("expires_at")
    override val primaryKey = PrimaryKey(id)
    init { index("artifact_uploads_expiry", false, expiresAt) }
}

/** Chunks of at most 1 MiB until a blob is complete and sent to the bucket; MySQL is the only bus between replicas. */
object ArtifactUploadChunks : Table("artifact_upload_chunks") {
    val uploadId = reference("upload_id", ArtifactUploads.id, onDelete = ReferenceOption.CASCADE, onUpdate = ReferenceOption.NO_ACTION, fkName = "artifact_upload_chunks_upload")
    val sha256 = varchar("sha256", 64)
    val number = integer("number")
    val bytes = registerColumn("bytes", MediumBlobColumnType())
    val createdAt = long("created_at")
    override val primaryKey = PrimaryKey(uploadId, sha256, number)
}

/** Blobs fully received in an upload and stored in the bucket, which a commit of the same upload may use. */
object ArtifactUploadBlobs : Table("artifact_upload_blobs") {
    val uploadId = reference("upload_id", ArtifactUploads.id, onDelete = ReferenceOption.CASCADE, onUpdate = ReferenceOption.NO_ACTION, fkName = "artifact_upload_blobs_upload")
    val sha256 = varchar("sha256", 64)
    val size = long("size")
    val storedAt = long("stored_at")
    override val primaryKey = PrimaryKey(uploadId, sha256)
}
