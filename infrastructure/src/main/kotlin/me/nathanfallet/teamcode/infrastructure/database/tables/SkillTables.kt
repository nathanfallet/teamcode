package me.nathanfallet.teamcode.infrastructure.database.tables

import org.jetbrains.exposed.v1.core.ColumnType
import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.vendors.currentDialect

/** MySQL BLOB stops at 64 KiB; artifact files are bounded at 5 MiB, so they use MEDIUMBLOB (16 MiB). */
internal class MediumBlobColumnType : ColumnType<ByteArray>() {
    override fun sqlType() = "MEDIUMBLOB"
    override fun valueFromDB(value: Any): ByteArray = when (value) {
        is ByteArray -> value
        is java.sql.Blob -> value.getBytes(1, value.length().toInt())
        else -> error("Unexpected binary value of type ${value::class.qualifiedName}")
    }
    override fun nonNullValueToString(value: ByteArray) = currentDialect.dataTypeProvider.hexToDb(value.joinToString("") { "%02x".format(it) })
}

/** Team-scoped catalog entries. The pinned version is checked in code to avoid a circular foreign key. */
object Skills : Table("skills") {
    val id = varchar("id", 36)
    val teamId = reference("team_id", Teams.id)
    val name = varchar("name", 64)
    val pinnedVersionId = varchar("pinned_version_id", 36).nullable()
    val retiredAt = long("retired_at").nullable()
    val createdAt = long("created_at")
    val updatedAt = long("updated_at")
    override val primaryKey = PrimaryKey(id)
    init { uniqueIndex("skill_team_name", teamId, name) }
}

/** Immutable published versions. Rows are never updated except for the retirement marker. */
object SkillVersions : Table("skill_versions") {
    val id = varchar("id", 36)
    val skillId = reference("skill_id", Skills.id)
    val version = varchar("version", 64)
    val digest = varchar("digest", 71)
    val sizeBytes = long("size_bytes")
    val fileCount = integer("file_count")
    val publishedBy = varchar("published_by", 36)
    val publishedAt = long("published_at")
    val retiredAt = long("retired_at").nullable()
    override val primaryKey = PrimaryKey(id)
    init { uniqueIndex("skill_version_label", skillId, version) }
}

object SkillFiles : Table("skill_files") {
    val versionId = reference("version_id", SkillVersions.id)
    val path = varchar("path", 255)
    val mode = varchar("mode", 16)
    val size = integer("size")
    val sha256 = char("sha256", 64)
    val content = registerColumn("content", MediumBlobColumnType())
    override val primaryKey = PrimaryKey(versionId, path)
}

/** Explicit per-member yes/no decisions. A missing row means not assigned. */
object SkillAssignments : Table("skill_assignments") {
    val skillId = reference("skill_id", Skills.id)
    val memberId = reference("member_id", Members.id, onDelete = ReferenceOption.CASCADE).index("skill_assignment_member")
    val decision = varchar("decision", 8)
    val updatedBy = varchar("updated_by", 36)
    val updatedAt = long("updated_at")
    override val primaryKey = PrimaryKey(skillId, memberId)
}

/** Last reported local state per device. Independent from assignments; removing a device removes its reports. */
object SkillInstallations : Table("skill_installations") {
    val deviceId = reference("device_id", Devices.id, onDelete = ReferenceOption.CASCADE)
    val skillId = reference("skill_id", Skills.id)
    val version = varchar("version", 64).nullable()
    val digest = varchar("digest", 71).nullable()
    val status = varchar("status", 16)
    val reportedAt = long("reported_at")
    override val primaryKey = PrimaryKey(deviceId, skillId)
}
