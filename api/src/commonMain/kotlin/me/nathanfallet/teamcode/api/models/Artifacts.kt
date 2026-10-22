package me.nathanfallet.teamcode.api.models

import kotlinx.serialization.Serializable

/**
 * The team's artifact bucket, as administrators see it. The secret access key is never returned: [secretConfigured]
 * only says one is stored. [verifiedAt] is the last passed connection test of this [version], null after any change.
 * [inUse] is true once artifacts exist: the endpoint, bucket and prefix are then fixed.
 */
@Serializable
data class ArtifactStorageView(
    val configured: Boolean, val endpoint: String? = null, val region: String? = null, val bucket: String? = null, val prefix: String? = null,
    val pathStyle: Boolean = false, val accessKeyId: String? = null, val secretConfigured: Boolean = false, val serverSideEncryption: String = "none",
    val verifiedAt: Long? = null, val version: Int = 0, val updatedAt: Long? = null, val inUse: Boolean = false,
)

/**
 * [version] is the one being replaced (0 for the first save). An omitted [secretAccessKey] keeps the stored one, which
 * is refused when the endpoint or the bucket changes. [serverSideEncryption] is `none` or `AES256`.
 */
@Serializable
data class SaveArtifactStorageRequest(
    val version: Int, val endpoint: String, val region: String, val bucket: String, val prefix: String = "", val pathStyle: Boolean = false,
    val accessKeyId: String, val secretAccessKey: String? = null, val serverSideEncryption: String = "none",
) {
    override fun toString() = "SaveArtifactStorageRequest(endpoint=$endpoint, bucket=$bucket, secretAccessKey=[REDACTED])"
}

/** One step of a connection test (`put`, `get`, `list`, `delete`); [code] is the S3 error code or a short local code, never a response body. */
@Serializable
data class ArtifactStorageTestStepView(val step: String, val ok: Boolean, val status: Int? = null, val code: String? = null)

@Serializable
data class ArtifactStorageTestView(val ok: Boolean, val steps: List<ArtifactStorageTestStepView>)

/**
 * Switches, quotas and retention. [active] is whether artifacts work right now (on, with a stored bucket whose current
 * version passed a test); [inactiveReason] is `artifact_storage_missing`, `artifact_storage_unverified` or
 * `artifacts_disabled` otherwise. [whoCanPublish] is `everyone` or `admins`; [publicSharing] is `owners_of_artifacts`,
 * `admins` or `off`. Null retention days keep artifacts indefinitely.
 */
@Serializable
data class ArtifactSettingsView(
    val enabled: Boolean, val whoCanPublish: String, val publicSharing: String,
    val quotaMemberBytes: Long, val quotaTeamBytes: Long, val quotaArtifactsPerMember: Int, val quotaPublishesPerHour: Int,
    val personalRetentionDays: Int?, val sharedRetentionDays: Int?, val maxVersionsPerArtifact: Int,
    val version: Int, val updatedAt: Long? = null, val active: Boolean = false, val inactiveReason: String? = null,
)

/** Every field is required; [version] is the one being replaced (0 before the first save). */
@Serializable
data class SaveArtifactSettingsRequest(
    val version: Int, val enabled: Boolean, val whoCanPublish: String, val publicSharing: String,
    val quotaMemberBytes: Long, val quotaTeamBytes: Long, val quotaArtifactsPerMember: Int, val quotaPublishesPerHour: Int,
    val personalRetentionDays: Int?, val sharedRetentionDays: Int?, val maxVersionsPerArtifact: Int,
)

/** A file of the version being published: its content hash (lowercase hex SHA-256) and size in bytes. */
@Serializable
data class ArtifactFileRef(val sha256: String, val size: Long)

/**
 * Starts an upload. [files] are the distinct blobs of the whole version to publish; the answer names the ones the
 * server does not already hold for this artifact. Without [artifactId], a new artifact id is reserved.
 */
@Serializable
data class CreateArtifactUploadRequest(val artifactId: String? = null, val files: List<ArtifactFileRef>)

/** [missing] are the SHA-256 values to send, in chunks of at most [chunkBytes], before [expiresAt]. */
@Serializable
data class ArtifactUploadView(val uploadId: String, val artifactId: String, val missing: List<String>, val chunkBytes: Int, val expiresAt: Long)

/** One chunk received; [complete] once every chunk of the blob arrived and the blob was checked and stored. */
@Serializable
data class ArtifactChunkView(val complete: Boolean, val received: Int, val total: Int)

/**
 * Commits a version. [entry] is the page (`.html`, `.htm` or `.md`) among [files] (published path to content).
 * [baseVersion] is the version the publisher started from (0 for a new artifact). [access] is `personal` or `team`:
 * no publication makes an artifact public. [capabilities] is reserved and must stay empty.
 */
@Serializable
data class CommitArtifactVersionRequest(
    val uploadId: String, val entry: String, val files: Map<String, ArtifactFileRef>, val baseVersion: Int,
    val title: String? = null, val description: String? = null, val icon: String? = null, val access: String? = null,
    val capabilities: Map<String, String>? = null,
)

/** A published file: [role] is `page` (the entry), `html` or `asset`. */
@Serializable
data class ArtifactFileView(val path: String, val sha256: String, val size: Long, val contentType: String, val role: String)

/** An immutable version and its files. [digest] is the SHA-256 of the canonical manifest. */
@Serializable
data class ArtifactVersionView(
    val artifactId: String, val number: Int, val entry: String, val digest: String, val fileCount: Int, val totalBytes: Long,
    val createdBy: String, val createdAt: Long, val baseVersion: Int, val files: List<ArtifactFileView> = emptyList(),
    val originSessionId: String? = null, val originEpoch: Int? = null,
)

/**
 * An artifact as the caller may see it. [url] is the only link to share, whatever the access. [relation] is the
 * caller's: `owner`, `editor`, `viewer` (invited), `team` (a team or public artifact) or `admin` (an administrator's
 * read of another member's artifact). [originKind] is `local_run`, `cloud_session` or `api`.
 */
@Serializable
data class ArtifactView(
    val id: String, val url: String, val title: String, val description: String, val icon: String, val access: String,
    val ownerId: String, val ownerName: String, val currentVersion: Int, val storedBytes: Long, val relation: String,
    val originKind: String, val originRunId: String? = null, val originSessionId: String? = null, val originEpoch: Int? = null,
    val publicFollowsLatest: Boolean = true, val publicVersion: Int? = null,
    val createdAt: Long, val updatedAt: Long, val publishedAt: Long? = null, val expiresAt: Long? = null,
)

/** [nextBefore] continues the listing; null at its end. */
@Serializable
data class ArtifactListView(val artifacts: List<ArtifactView>, val nextBefore: String? = null)

/** The answer of a commit: the artifact after it, the version, and whether this was a replay of the same commit. */
@Serializable
data class ArtifactPublishedView(val artifact: ArtifactView, val version: ArtifactVersionView, val replayed: Boolean = false)

/** `409 artifact_conflict`: the version now live, for the publisher to merge before publishing again. */
@Serializable
data class ArtifactConflictView(val error: ErrorDetail, val current: ArtifactVersionView? = null)

/** `422 artifact_secret_detected`: which blob holds what kind of secret, never the value. */
@Serializable
data class ArtifactSecretView(val error: ErrorDetail, val sha256: String, val kind: String)

/** [access] is `personal`, `team` or `public`; for `public`, either follow the latest version or pin [publicVersion]. */
@Serializable
data class SetArtifactAccessRequest(val access: String, val publicFollowsLatest: Boolean = true, val publicVersion: Int? = null)

/** An invited member: [role] is `viewer` or `editor`; [reason] is `invited` or `session_transfer`. */
@Serializable
data class ArtifactMemberView(val memberId: String, val username: String, val role: String, val reason: String, val addedBy: String, val addedAt: Long, val originSessionId: String? = null)

@Serializable
data class ArtifactMembersView(val ownerId: String, val members: List<ArtifactMemberView>)

/** [member] is a member id, a username or a linked GitHub login. */
@Serializable
data class ShareArtifactRequest(val member: String, val role: String = "viewer")
