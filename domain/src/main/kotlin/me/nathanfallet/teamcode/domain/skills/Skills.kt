package me.nathanfallet.teamcode.domain.skills

import me.nathanfallet.teamcode.domain.team.TeamFailure

/**
 * A catalog entry. A skill is served only through its approved pinned version. With [allMembers], every member without
 * an explicit decision receives it, including members who join later; an explicit *no* still excludes a member.
 */
data class Skill(
    val id: String,
    val name: String,
    val pinnedVersion: String?,
    val retired: Boolean,
    val createdAt: Long,
    val updatedAt: Long,
    val allMembers: Boolean = false,
)

/** An immutable published version. Its files and digest never change after publication. */
data class SkillVersion(
    val id: String,
    val skillId: String,
    val version: String,
    val digest: String,
    val sizeBytes: Long,
    val fileCount: Int,
    val publishedBy: String,
    val publishedAt: Long,
    val retired: Boolean,
)

enum class SkillFileMode(val wire: String) { FILE("file"), EXECUTABLE("executable") }

/** One file of an artifact, addressed by a validated relative path. */
class SkillFile(val path: String, val mode: SkillFileMode, val content: ByteArray) {
    override fun toString() = "SkillFile(path=$path, mode=$mode, size=${content.size})"
}

/** A file's metadata inside a validated artifact. */
data class SkillFileEntry(val path: String, val mode: SkillFileMode, val size: Int, val sha256: String)

/** A validated artifact ready for persistence: files sorted by path with their canonical digest. */
class SkillArtifact(val digest: String, val files: List<SkillFile>, val entries: List<SkillFileEntry>) {
    val sizeBytes: Long get() = entries.sumOf { it.size.toLong() }
    override fun toString() = "SkillArtifact(digest=$digest, files=${entries.size})"
}

/**
 * A member's explicit yes/no decision for one skill. Absence means "not assigned": it delivers nothing, unless the skill
 * is delivered to all members. Group decisions will be combined through [SkillAccessPolicy] when groups exist.
 */
enum class SkillDecision(val wire: String) { ALLOW("allow"), DENY("deny") }

data class SkillAssignment(val skillId: String, val memberId: String, val decision: SkillDecision, val updatedAt: Long)

/** A pinned, authorized skill version as delivered to one member. */
data class EffectiveSkill(val name: String, val version: String, val digest: String, val sizeBytes: Long, val fileCount: Int)

/** The persisted facts needed to decide whether one skill is delivered to one member. */
data class SkillCandidate(
    val skill: Skill,
    val pinned: SkillVersion?,
    val memberDecision: SkillDecision?,
    val groupDecisions: List<SkillDecision> = emptyList(),
)

data class SkillPublication(val skill: Skill, val version: SkillVersion, val created: Boolean)

enum class SkillInstallationStatus(val wire: String) { INSTALLED("installed"), FAILED("failed"), CONFLICT("conflict"), REMOVED("removed") }

/** What a device reports about a skill's local installation. Independent from the permission to receive it. */
data class SkillInstallationReport(val name: String, val version: String?, val digest: String?, val status: SkillInstallationStatus)

data class SkillInstallation(
    val deviceId: String,
    val deviceName: String,
    val name: String,
    val version: String?,
    val digest: String?,
    val status: SkillInstallationStatus,
    val reportedAt: Long,
)

/**
 * Precedence rule for skill and hook access. Today only member decisions exist, so `groups` is always empty.
 * An explicit member decision wins; otherwise (planned) any group denial wins over a group grant; otherwise a group
 * grant allows; with no decision at all, the item's audience decides: [allMembers] delivers, otherwise nothing is
 * delivered. "All members" is therefore the weakest rule: any explicit *no* overrides it.
 */
object SkillAccessPolicy {
    fun allowed(member: SkillDecision?, groups: Collection<SkillDecision> = emptyList(), allMembers: Boolean = false): Boolean = when {
        member != null -> member == SkillDecision.ALLOW
        SkillDecision.DENY in groups -> false
        SkillDecision.ALLOW in groups -> true
        else -> allMembers
    }
}

internal fun skillFailure(code: String, message: String, status: Int = 400) = TeamFailure(code, status, message)
