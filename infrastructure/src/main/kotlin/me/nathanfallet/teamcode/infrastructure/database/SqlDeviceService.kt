package me.nathanfallet.teamcode.infrastructure.database

import java.util.UUID
import me.nathanfallet.teamcode.domain.team.*
import me.nathanfallet.teamcode.infrastructure.database.tables.*
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*

/** Called only after password verification, while holding the member row lock. */
internal fun registerDevice(c: JdbcTransaction, member: Member, installation: String, name: String, now: Long, platform: String? = null, osVersion: String? = null): String {
    val existing = Devices.select(Devices.id).where { (Devices.memberId eq member.id) and (Devices.installationId eq installation) }.singleOrNull()?.get(Devices.id)
    if (existing != null) {
        Devices.update({ Devices.id eq existing }) {
            it[lastLoginAt] = now
            // An older client reports nothing: keep what a newer one reported.
            if (platform != null) { it[Devices.platform] = platform; it[Devices.osVersion] = osVersion }
        }
        return existing // Preserve a name edited in the dashboard.
    }
    if (Devices.select(Devices.id).where { Devices.memberId eq member.id }.count() >= DeviceService.DEVICE_LIMIT)
        throw TeamFailure("device_limit", 409, "Remove an unused device before registering another installation.")
    val id = UUID.randomUUID().toString()
    Devices.insert {
        it[Devices.id] = id; it[memberId] = member.id; it[installationId] = installation
        it[Devices.name] = name; it[createdAt] = now; it[lastLoginAt] = now
        it[Devices.platform] = platform; it[Devices.osVersion] = osVersion
    }
    c.audit("default", member.id, "device.registered", id)
    return id
}

/** Devices identify logical installations. Every allowance remains scoped to the member. */
class SqlDeviceService(private val database: Database) : DeviceService {
    override suspend fun devices(actor: Principal, memberId: String): List<Device> = database.query {
        requireActor(this, actor); requireMember(actor, memberId)
        val sessions = sessionRows(memberId, nowMillis())
        val current = sessions.find { it[Tokens.id] == actor.tokenId }?.get(Tokens.deviceId)
        Devices.selectAll().where { Devices.memberId eq memberId }.orderBy(Devices.lastLoginAt to SortOrder.DESC, Devices.id to SortOrder.ASC)
            .limit(DeviceService.DEVICE_LIMIT.toInt()).map { row ->
                row.device(sessions.count { it[Tokens.deviceId] == row[Devices.id] }, current == row[Devices.id])
            }
    }
    override suspend fun sessions(actor: Principal, memberId: String): List<MemberSession> = database.query {
        requireActor(this, actor); requireMember(actor, memberId)
        val names = Devices.select(Devices.id, Devices.name).where { Devices.memberId eq memberId }.associate { it[Devices.id] to it[Devices.name] }
        sessionRows(memberId, nowMillis()).map { row ->
            MemberSession(row[Tokens.id], memberId, names[row[Tokens.deviceId]] ?: row[Tokens.device], row[Tokens.deviceId], row[Tokens.createdAt], row[Tokens.expiresAt], row[Tokens.id] == actor.tokenId)
        }
    }
    override suspend fun rename(actor: Principal, deviceId: String, name: String): Device {
        DeviceService.validateName(name)
        return database.query {
            lockTeam(actor.teamId); requireActor(this, actor)
            val row = deviceRow(actor, deviceId)
            requireMember(actor, row[Devices.memberId], lock = true)
            Devices.update({ Devices.id eq deviceId }) { it[Devices.name] = name }
            audit(actor.teamId, actor.member.id, "device.renamed", deviceId)
            val sessions = sessionRows(row[Devices.memberId], nowMillis())
            deviceRow(actor, deviceId).device(sessions.count { it[Tokens.deviceId] == deviceId }, sessions.any { it[Tokens.id] == actor.tokenId && it[Tokens.deviceId] == deviceId })
        }
    }
    override suspend fun revokeDevice(actor: Principal, deviceId: String): String = database.query {
        lockTeam(actor.teamId); requireActor(this, actor)
        val row = deviceRow(actor, deviceId)
        val memberId = row[Devices.memberId]
        requireMember(actor, memberId, lock = true)
        // Explicit deletion documents the invariant; the FK also protects against orphan tokens.
        Tokens.deleteWhere { Tokens.deviceId eq deviceId }
        // The device's remote sessions end with it, in this transaction: they could only ever run there. The reconciler
        // finishes the termination; transcripts are kept and the sessions lose their host (the foreign key's own index finds them).
        val now = nowMillis()
        CloudSessions.update({
            (CloudSessions.hostDeviceId eq deviceId) and (CloudSessions.state neq "terminated") and (CloudSessions.desiredState neq "terminated")
        }) {
            it[desiredState] = "terminated"; it[terminatedReason] = "device_removed"; it[generation] = generation + 1L
            it[updatedAt] = now; it[reconcileAfter] = now; it[reconcileRequested] = true
        }
        CloudSessions.update({ CloudSessions.hostDeviceId eq deviceId }) { it[hostDeviceId] = null }
        Devices.deleteWhere { Devices.id eq deviceId }
        audit(actor.teamId, actor.member.id, "device.revoked", deviceId)
        memberId
    }
    override suspend fun revokeSession(actor: Principal, sessionId: String): String = database.query {
        lockTeam(actor.teamId); requireActor(this, actor)
        val row = (Tokens innerJoin Members).select(Tokens.memberId).where {
            (Tokens.id eq sessionId) and (Members.teamId eq actor.teamId) and visible(actor, Tokens.memberId)
        }.singleOrNull() ?: throw missing()
        val memberId = row[Tokens.memberId]
        requireMember(actor, memberId, lock = true)
        Tokens.deleteWhere { Tokens.id eq sessionId }
        audit(actor.teamId, actor.member.id, "session.revoked", sessionId)
        memberId
    }

    private fun requireActor(c: JdbcTransaction, actor: Principal) { actor.requireInteractive(); requireCurrent(c, actor) }
    private fun requireMember(actor: Principal, member: String, lock: Boolean = false) {
        if (actor.member.id != member) actor.requireAdmin()
        val query = Members.select(Members.id).where { (Members.id eq member) and (Members.teamId eq actor.teamId) }
        if (lock) query.forUpdate()
        if (query.singleOrNull() == null) throw missing()
    }
    private fun visible(actor: Principal, column: Column<String>): Op<Boolean> = if (actor.member.role.isAdministrator) Op.TRUE else column eq actor.member.id
    private fun deviceRow(actor: Principal, id: String) = (Devices innerJoin Members).select(Devices.columns).where {
        (Devices.id eq id) and (Members.teamId eq actor.teamId) and visible(actor, Devices.memberId)
    }.singleOrNull() ?: throw missing()
    private fun sessionRows(member: String, now: Long) = Tokens.select(Tokens.id, Tokens.memberId, Tokens.device, Tokens.deviceId, Tokens.createdAt, Tokens.expiresAt)
        .where { (Tokens.memberId eq member) and (Tokens.expiresAt greater now) and Tokens.serviceScopes.isNull() }
        .orderBy(Tokens.expiresAt to SortOrder.DESC, Tokens.id to SortOrder.ASC).limit(50).toList()
    private fun ResultRow.device(active: Int, current: Boolean) = Device(get(Devices.id), get(Devices.memberId), get(Devices.name), get(Devices.createdAt), get(Devices.lastLoginAt), active, current,
        get(Devices.platform), get(Devices.osVersion))
    private fun missing() = TeamFailure("not_found", 404, "Device or session not found.")
}
