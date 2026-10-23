package me.nathanfallet.teamcode.infrastructure.database

import me.nathanfallet.teamcode.domain.cloud.*
import me.nathanfallet.teamcode.domain.team.*
import me.nathanfallet.teamcode.infrastructure.database.tables.*
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*

/**
 * Presence lives on the device row (one primary-key update per poll, skipped within [DeviceDaemonRules.TOUCH_MILLIS]),
 * folders in `device_folders`, and the queue is read from `cloud_sessions` through `cloud_session_host_waiting`.
 */
class SqlDeviceDaemonStore(private val database: Database) : DeviceDaemonStore {
    override suspend fun now(): Long = database.query { nowMillis() }

    override suspend fun deviceOf(principal: Principal): String? = database.query {
        if (principal.serviceScopes != null) null
        else {
            requireCurrent(this, principal)
            Tokens.select(Tokens.deviceId).where { Tokens.id eq principal.tokenId }.singleOrNull()?.get(Tokens.deviceId)
        }
    }

    override suspend fun cloudExecution(principal: Principal): Boolean = database.query {
        CloudInferenceCredentials.select(CloudInferenceCredentials.tokenId).where { CloudInferenceCredentials.tokenId eq principal.tokenId }.limit(1).any()
    }

    override suspend fun touch(deviceId: String, version: String?) {
        database.query {
            val now = nowMillis()
            Devices.update({ (Devices.id eq deviceId) and (Devices.daemonSeenAt.isNull() or (Devices.daemonSeenAt less now - DeviceDaemonRules.TOUCH_MILLIS)) }) {
                it[daemonSeenAt] = now; it[daemonVersion] = version
            }
        }
    }

    override suspend fun offline(deviceId: String) {
        database.query { Devices.update({ Devices.id eq deviceId }) { it[daemonSeenAt] = null } }
    }

    override suspend fun publish(deviceId: String, folders: List<DeviceFolder>) {
        database.query {
            // Serialized on the device row, so two publications never interleave their delete and insert.
            Devices.select(Devices.id).where { Devices.id eq deviceId }.forUpdate().singleOrNull() ?: return@query
            DeviceFolders.deleteWhere { DeviceFolders.deviceId eq deviceId }
            DeviceFolders.batchInsert(folders.withIndex(), shouldReturnGeneratedValues = false) { (index, folder) ->
                this[DeviceFolders.deviceId] = deviceId; this[DeviceFolders.folderId] = folder.id
                this[DeviceFolders.name] = folder.name; this[DeviceFolders.label] = folder.label; this[DeviceFolders.position] = index
            }
        }
    }

    override suspend fun owned(teamId: String, memberId: String, deviceId: String): OwnedDevice? = database.query {
        val now = nowMillis()
        // The device row's member decides, joined to the team: never the caller's role.
        val row = (Devices innerJoin Members).select(Devices.id, Devices.name, Devices.daemonSeenAt, Devices.daemonVersion).where {
            (Devices.id eq deviceId) and (Devices.memberId eq memberId) and (Members.teamId eq teamId)
        }.singleOrNull() ?: return@query null
        val folders = DeviceFolders.selectAll().where { DeviceFolders.deviceId eq deviceId }
            .orderBy(DeviceFolders.position to SortOrder.ASC).limit(DeviceDaemonRules.MAX_FOLDERS)
            .map { DeviceFolder(it[DeviceFolders.folderId], it[DeviceFolders.name], it[DeviceFolders.label]) }
        OwnedDevice(row[Devices.id], row[Devices.name], DaemonPresence.of(row[Devices.daemonSeenAt], row[Devices.daemonVersion], now), folders)
    }

    // The device row belongs to one member of one team, so its id alone scopes the queue (no team filter after the lookup).
    override suspend fun orders(deviceId: String, limit: Int): List<DaemonOrder> = database.query {
        CloudSessions.select(CloudSessions.id, CloudSessions.hostFolderId, CloudSessions.hostDirectoryName, CloudSessions.agentKind,
            CloudSessions.permissionMode, CloudSessions.state, CloudSessions.updatedAt).where {
            (CloudSessions.hostDeviceId eq deviceId) and (CloudSessions.phase eq CloudSessionService.WAITING_HOST) and
                (CloudSessions.desiredState eq DesiredState.RUNNING.wire) and CloudSessions.hostFolderId.isNotNull()
        }.orderBy(CloudSessions.updatedAt to SortOrder.ASC).limit(limit).map { row ->
            DaemonOrder(row[CloudSessions.id], row[CloudSessions.hostFolderId]!!, row[CloudSessions.hostDirectoryName].orEmpty(), row[CloudSessions.agentKind],
                RemotePermissionMode.parse(row[CloudSessions.permissionMode]) ?: RemotePermissionMode.ASK,
                CloudSessionState.parse(row[CloudSessions.state]), row[CloudSessions.updatedAt])
        }
    }
}
