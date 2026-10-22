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
