package me.nathanfallet.teamcode.infrastructure.database

import me.nathanfallet.teamcode.domain.cloud.*
import me.nathanfallet.teamcode.domain.team.*
import me.nathanfallet.teamcode.infrastructure.database.tables.*
import me.nathanfallet.teamcode.infrastructure.secrets.CredentialCipher
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*
import java.util.UUID

/**
 * Presence lives on the device row (one primary-key update per poll, skipped within [DeviceDaemonRules.TOUCH_MILLIS]),
 * folders in `device_folders`, and the queue is read from `cloud_sessions` through `cloud_session_host_waiting`.
 * Listings live in `device_daemon_listings` for as long as a member waits, their answers sealed with [cipher].
 */
class SqlDeviceDaemonStore(private val database: Database, private val cipher: CredentialCipher) : DeviceDaemonStore {
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

    override suspend fun touch(deviceId: String, version: String?, features: List<String>) {
        database.query {
            val now = nowMillis()
            Devices.update({ (Devices.id eq deviceId) and (Devices.daemonSeenAt.isNull() or (Devices.daemonSeenAt less now - DeviceDaemonRules.TOUCH_MILLIS)) }) {
                it[daemonSeenAt] = now; it[daemonVersion] = version; it[daemonFeatures] = features.takeIf { it.isNotEmpty() }?.joinToString(",")
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
        val row = (Devices innerJoin Members).select(Devices.id, Devices.name, Devices.daemonSeenAt, Devices.daemonVersion, Devices.daemonFeatures).where {
            (Devices.id eq deviceId) and (Devices.memberId eq memberId) and (Members.teamId eq teamId)
        }.singleOrNull() ?: return@query null
        val folders = DeviceFolders.selectAll().where { DeviceFolders.deviceId eq deviceId }
            .orderBy(DeviceFolders.position to SortOrder.ASC).limit(DeviceDaemonRules.MAX_FOLDERS)
            .map { DeviceFolder(it[DeviceFolders.folderId], it[DeviceFolders.name], it[DeviceFolders.label]) }
        OwnedDevice(row[Devices.id], row[Devices.name], DaemonPresence.of(row[Devices.daemonSeenAt], row[Devices.daemonVersion], now, features(row[Devices.daemonFeatures])), folders)
    }

    // The device row belongs to one member of one team, so its id alone scopes the queue (no team filter after the lookup).
    override suspend fun orders(deviceId: String, limit: Int): List<DaemonOrder> = database.query {
        CloudSessions.select(CloudSessions.id, CloudSessions.hostFolderId, CloudSessions.hostDirectoryName, CloudSessions.agentKind,
            CloudSessions.permissionMode, CloudSessions.state, CloudSessions.updatedAt, CloudSessions.hostResumeSessionId).where {
            (CloudSessions.hostDeviceId eq deviceId) and (CloudSessions.phase eq CloudSessionService.WAITING_HOST) and
                (CloudSessions.desiredState eq DesiredState.RUNNING.wire) and CloudSessions.hostFolderId.isNotNull()
        }.orderBy(CloudSessions.updatedAt to SortOrder.ASC).limit(limit).map { row ->
            DaemonOrder(row[CloudSessions.id], row[CloudSessions.hostFolderId]!!, row[CloudSessions.hostDirectoryName].orEmpty(), row[CloudSessions.agentKind],
                RemotePermissionMode.parse(row[CloudSessions.permissionMode]) ?: RemotePermissionMode.ASK,
                CloudSessionState.parse(row[CloudSessions.state]), row[CloudSessions.updatedAt], row[CloudSessions.hostResumeSessionId])
        }
    }

    override suspend fun askListing(deviceId: String, folderId: String, limit: Int, before: Long?, expiresAt: Long): String = database.query {
        val now = nowMillis()
        // What a stopped replica left behind for this device goes now; the device's rows are few, found by the index's prefix.
        DeviceDaemonListings.deleteWhere { (DeviceDaemonListings.deviceId eq deviceId) and (DeviceDaemonListings.expiresAt lessEq now) }
        val id = UUID.randomUUID().toString()
        DeviceDaemonListings.insert {
            it[DeviceDaemonListings.id] = id; it[DeviceDaemonListings.deviceId] = deviceId; it[DeviceDaemonListings.folderId] = folderId
            it[maxResults] = limit; it[DeviceDaemonListings.before] = before; it[DeviceDaemonListings.expiresAt] = expiresAt
        }
        id
    }

    override suspend fun listings(deviceId: String, limit: Int): List<DaemonListing> = database.query {
        val now = nowMillis()
        DeviceDaemonListings.select(DeviceDaemonListings.id, DeviceDaemonListings.folderId, DeviceDaemonListings.maxResults, DeviceDaemonListings.before).where {
            (DeviceDaemonListings.deviceId eq deviceId) and DeviceDaemonListings.answeredAt.isNull() and (DeviceDaemonListings.expiresAt greater now)
        }.orderBy(DeviceDaemonListings.expiresAt to SortOrder.ASC).limit(limit).map {
            DaemonListing(it[DeviceDaemonListings.id], it[DeviceDaemonListings.folderId], it[DeviceDaemonListings.maxResults], it[DeviceDaemonListings.before])
        }
    }

    override suspend fun answerListing(deviceId: String, requestId: String, answer: ResumableAnswer): Boolean = database.query {
        val now = nowMillis()
        val sealed = cipher.encrypt(json.encodeToString(StoredAnswer.of(answer)).toByteArray(Charsets.UTF_8), context(requestId))
        DeviceDaemonListings.update({
            (DeviceDaemonListings.id eq requestId) and (DeviceDaemonListings.deviceId eq deviceId) and DeviceDaemonListings.answeredAt.isNull() and
                (DeviceDaemonListings.expiresAt greater now)
        }) { it[answeredAt] = now; it[DeviceDaemonListings.answer] = sealed } > 0
    }

    override suspend fun listingAnswer(requestId: String): ResumableAnswer? = database.query {
        val row = DeviceDaemonListings.select(DeviceDaemonListings.answeredAt, DeviceDaemonListings.answer).where { DeviceDaemonListings.id eq requestId }.singleOrNull()
        val sealed = row?.takeIf { it[DeviceDaemonListings.answeredAt] != null }?.get(DeviceDaemonListings.answer) ?: return@query null
        json.decodeFromString<StoredAnswer>(cipher.decrypt(sealed, context(requestId)).toString(Charsets.UTF_8)).domain()
    }

    override suspend fun dropListing(requestId: String) {
        database.query { DeviceDaemonListings.deleteWhere { DeviceDaemonListings.id eq requestId } }
    }

    private fun context(requestId: String) = "device-daemon-listing:$requestId"
    private fun features(value: String?) = value?.split(',')?.filter { it.isNotBlank() }.orEmpty()

    @Serializable
    private data class StoredSession(val id: String, val title: String, val lastActiveAt: Long, val turns: Int)
    @Serializable
    private data class StoredAnswer(val sessions: List<StoredSession>, val nextBefore: Long? = null, val refused: String? = null) {
        fun domain() = ResumableAnswer(sessions.map { ResumableSession(it.id, it.title, it.lastActiveAt, it.turns) }, nextBefore, refused)
        companion object {
            fun of(answer: ResumableAnswer) = StoredAnswer(answer.sessions.map { StoredSession(it.id, it.title, it.lastActiveAt, it.turns) }, answer.nextBefore, answer.refused)
        }
    }

    private companion object {
        val json = Json { ignoreUnknownKeys = true }
    }
}
