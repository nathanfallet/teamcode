package me.nathanfallet.teamcode.api.models

import kotlinx.serialization.Serializable

/**
 * One catalog entry as seen by an administrator. `allMembers` delivers the skill to every member without an explicit
 * decision, including later members; absent (false) from a server older than `team.skills.all-members.v1`.
 */
@Serializable
data class SkillView(
    val id: String, val name: String, val pinnedVersion: String? = null, val retired: Boolean, val createdAt: Long, val updatedAt: Long,
    val allMembers: Boolean = false,
)

@Serializable
data class SkillVersionView(
    val version: String,
    val digest: String,
    val sizeBytes: Long,
    val fileCount: Int,
    val publishedBy: String,
    val publishedAt: Long,
    val retired: Boolean,
    val pinned: Boolean,
)

@Serializable
data class SkillDetailView(val skill: SkillView, val versions: List<SkillVersionView>)

/**
 * One file of a published artifact. `mode` is `file` or `executable`; symbolic links cannot be published.
 * `contentBase64` uses the standard alphabet with padding.
 */
@Serializable
class SkillFileUpload(val path: String, val mode: String = "file", val contentBase64: String) {
    override fun toString() = "SkillFileUpload(path=$path, mode=$mode)"
}

/**
 * With `pin`, the published version also becomes the delivered one in the same transaction. Republishing the same
 * content with `pin` is idempotent (a key needs `skills:pin` besides `skills:publish` on servers advertising
 * `team.skills.pin-scope.v1`). Servers advertise `team.skills.publish-pin.v1`; an older server ignores the field,
 * which a client detects from `version.pinned` in the response.
 *
 * With `allMembers`, the skill is also delivered to every member without an explicit decision, in the same transaction
 * (a key needs `skills:assign` besides `skills:publish`). `false` leaves the audience unchanged. Servers advertise
 * `team.skills.all-members.v1`; an older server ignores the field, which a client detects from `skill.allMembers`.
 */
@Serializable
data class PublishSkillVersionRequest(val version: String, val files: List<SkillFileUpload>, val pin: Boolean = false, val allMembers: Boolean = false)

@Serializable
data class PublishSkillVersionResponse(val skill: SkillView, val version: SkillVersionView, val created: Boolean)

@Serializable
data class PinSkillRequest(val version: String)

/** `true`: every member without an explicit decision receives the skill; `false`: only members set to *yes*. */
@Serializable
data class SetSkillAudienceRequest(val allMembers: Boolean)

/** A member's explicit yes/no access decision: `allow` or `deny`. */
@Serializable
data class SkillAssignmentView(val memberId: String, val decision: String, val updatedAt: Long)

@Serializable
data class SetSkillAssignmentRequest(val decision: String)

/** A pinned skill version the member is currently authorized to install. */
@Serializable
data class EffectiveSkillView(val name: String, val version: String, val digest: String, val sizeBytes: Long, val fileCount: Int)

@Serializable
data class SkillManifestView(val memberId: String, val skills: List<EffectiveSkillView>)

@Serializable
class SkillArtifactFile(val path: String, val mode: String, val size: Int, val sha256: String, val contentBase64: String) {
    override fun toString() = "SkillArtifactFile(path=$path, mode=$mode, size=$size)"
}

/** The approved files of one effective version. `digest` is recomputable from the file metadata. */
@Serializable
data class SkillArtifactView(val name: String, val version: String, val digest: String, val files: List<SkillArtifactFile>)

/** Installation status on the reporting device: `installed`, `failed`, `conflict` or `removed`. */
@Serializable
data class SkillInstallationReportView(val name: String, val version: String? = null, val digest: String? = null, val status: String)

@Serializable
data class ReportSkillInstallationsRequest(val installations: List<SkillInstallationReportView>)

@Serializable
data class SkillInstallationView(
    val deviceId: String,
    val deviceName: String,
    val name: String,
    val version: String? = null,
    val digest: String? = null,
    val status: String,
    val reportedAt: Long,
)
