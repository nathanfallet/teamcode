package me.nathanfallet.teamcode.infrastructure.database.tables

import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.Table

object Devices : Table("devices") {
    val id = varchar("id", 36)
    val memberId = reference("member_id", Members.id, onDelete = ReferenceOption.CASCADE)
    val installationId = varchar("installation_id", 36)
    val name = varchar("name", 120)
    val createdAt = long("created_at")
    val lastLoginAt = long("last_login_at")
    /** Reported by the installation at login: `macos`, `windows`, `linux`, `ios` or `android`; null until a client reports it. */
    val platform = varchar("platform", 16).nullable()
    val osVersion = varchar("os_version", 64).nullable()
    override val primaryKey = PrimaryKey(id)
    init { uniqueIndex("device_member_installation", memberId, installationId) }
}
