package me.nathanfallet.teamcode.infrastructure.database

import me.nathanfallet.teamcode.domain.notifications.*
import me.nathanfallet.teamcode.domain.team.*
import me.nathanfallet.teamcode.infrastructure.database.tables.*
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*

/** Push destinations on the `devices` rows. A destination counts only while its device has a signed-in interactive session. */
class SqlPushDeviceStore(private val database: Database) : PushDeviceStore {
    override suspend fun currentDevice(actor: Principal): PushDevice? = database.query {
        requireCurrent(this, actor)
        (Tokens innerJoin Devices).select(Devices.id, Devices.memberId, Devices.platform)
            .where { (Tokens.id eq actor.tokenId) and (Devices.memberId eq actor.member.id) and Tokens.serviceScopes.isNull() }
            .singleOrNull()?.let { PushDevice(it[Devices.id], it[Devices.memberId], it[Devices.platform]) }
    }

    override suspend fun register(deviceId: String, token: String, publicKey: String, platform: String?) = database.query {
        val now = nowMillis()
        Devices.update({ Devices.id eq deviceId }) { it[pushToken] = token; it[pushPublicKey] = publicKey; it[pushTokenUpdatedAt] = now }
        // Only a device whose login reported nothing: the login's own report stays.
        if (platform != null) Devices.update({ (Devices.id eq deviceId) and Devices.platform.isNull() }) { it[Devices.platform] = platform }
        Unit
    }

    override suspend fun clear(deviceId: String, onlyToken: String?) = database.query {
        Devices.update({ (Devices.id eq deviceId) and (if (onlyToken == null) Op.TRUE else Devices.pushToken eq onlyToken) }) {
            it[pushToken] = null; it[pushPublicKey] = null; it[pushTokenUpdatedAt] = null
        }
        Unit
    }

    override suspend fun targets(teamId: String, memberIds: Set<String>): List<PushTarget> = database.query {
        if (memberIds.isEmpty()) return@query emptyList()
        val now = nowMillis()
        // A device signed out of the app (its tokens deleted or expired) keeps its row but receives nothing.
        val signedIn = Tokens.select(Tokens.id).where { (Tokens.deviceId eq Devices.id) and (Tokens.expiresAt greater now) and Tokens.serviceScopes.isNull() }
        (Devices innerJoin Members).select(Devices.id, Devices.memberId, Devices.pushToken, Devices.pushPublicKey, Devices.platform)
            .where {
                (Devices.memberId inList memberIds) and (Members.teamId eq teamId) and Devices.pushToken.isNotNull() and Devices.pushPublicKey.isNotNull() and
                    (Devices.platform inList PushRegistrationService.PUSH_PLATFORMS) and exists(signedIn)
            }
            .limit(memberIds.size * DeviceService.DEVICE_LIMIT.toInt())
            .map { PushTarget(it[Devices.id], it[Devices.memberId], it[Devices.pushToken]!!, it[Devices.pushPublicKey]!!, it[Devices.platform]!!) }
    }
}
