package me.nathanfallet.teamcode.infrastructure.database.tables

import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.Table

/** Team-scoped hook catalog entries. The pinned version is checked in code to avoid a circular foreign key. */
object Hooks : Table("hooks") {
    val id = varchar("id", 36)
    val teamId = reference("team_id", Teams.id)
    val name = varchar("name", 64)
    val pinnedVersionId = varchar("pinned_version_id", 36).nullable()
    val createdAt = long("created_at")
    val updatedAt = long("updated_at")
    /** Delivered to every member without an explicit decision, including later members, once pinned. */
    val allMembers = bool("all_members").default(false)
    override val primaryKey = PrimaryKey(id)
    init { uniqueIndex("hook_team_name", teamId, name) }
}

/** Immutable published versions with their definition. Rows are never updated. */
object HookVersions : Table("hook_versions") {
    val id = varchar("id", 36)
    val hookId = reference("hook_id", Hooks.id)
    /** Labels are case-sensitive in the domain; the default collation would merge `v1` and `V1`. */
    val version = varchar("version", 64, collate = "utf8mb4_bin")
    val digest = varchar("digest", 71)
    /** Event wire names, sorted and comma-separated. */
    val events = varchar("events", 255)
    val matcher = varchar("matcher", 256, collate = "utf8mb4_bin").nullable()
    val command = varchar("command", 255)
    val timeoutSeconds = integer("timeout_seconds")
    val sizeBytes = long("size_bytes")
    val fileCount = integer("file_count")
    val publishedBy = varchar("published_by", 36)
    val publishedAt = long("published_at")
    override val primaryKey = PrimaryKey(id)
    init { uniqueIndex("hook_version_label", hookId, version) }
}

object HookFiles : Table("hook_files") {
    val versionId = reference("version_id", HookVersions.id)
    val path = varchar("path", 255)
    val mode = varchar("mode", 16)
    val size = integer("size")
    val sha256 = char("sha256", 64)
    val content = registerColumn("content", MediumBlobColumnType())
    override val primaryKey = PrimaryKey(versionId, path)
}

/**
 * Explicit per-member yes/no decisions; a missing row or a null decision means not assigned. A row is kept when its
 * decision is cleared so its stable ID, used as the audit subject, keeps resolving to the hook and member.
 */
object HookAssignments : Table("hook_assignments") {
    val id = varchar("id", 36).uniqueIndex("hook_assignment_id")
    val hookId = reference("hook_id", Hooks.id)
    val memberId = reference("member_id", Members.id, onDelete = ReferenceOption.CASCADE).index("hook_assignment_member")
    val decision = varchar("decision", 8).nullable()
    val updatedBy = varchar("updated_by", 36)
    val updatedAt = long("updated_at")
    override val primaryKey = PrimaryKey(hookId, memberId)
}
