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
    /** The last poll of the device's daemon (database clock); null when it never connected or said it stopped. */
    val daemonSeenAt = long("daemon_seen_at").nullable()
    val daemonVersion = varchar("daemon_version", 32).nullable()
    override val primaryKey = PrimaryKey(id)
    init { uniqueIndex("device_member_installation", memberId, installationId) }
}

/** The folders a device's daemon publishes: its own key and the folder's name, never a path. Replaced as a whole. */
object DeviceFolders : Table("device_folders") {
    val deviceId = reference("device_id", Devices.id, onDelete = ReferenceOption.CASCADE, onUpdate = ReferenceOption.NO_ACTION, fkName = "device_folders_device")
    val folderId = varchar("folder_id", 64)
    val name = varchar("name", 255)
    val label = varchar("label", 512).nullable()
    val position = integer("position")
    // Read in order by the primary key's prefix, without a sort; an id appears once per device.
    override val primaryKey = PrimaryKey(deviceId, position)
    init { uniqueIndex("device_folder_id", deviceId, folderId) }
}
