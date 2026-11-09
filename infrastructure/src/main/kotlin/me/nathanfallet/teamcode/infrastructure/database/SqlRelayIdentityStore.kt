package me.nathanfallet.teamcode.infrastructure.database

import me.nathanfallet.teamcode.infrastructure.database.tables.PushRelayInstances
import me.nathanfallet.teamcode.infrastructure.push.RelayIdentity
import me.nathanfallet.teamcode.infrastructure.push.RelayIdentityStore
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*

/** One row per relay URL: the first replica to create it wins, the others read it. */
class SqlRelayIdentityStore(private val database: Database) : RelayIdentityStore {
    override suspend fun load(relayUrl: String): RelayIdentity? = database.query { read(relayUrl) }

    override suspend fun create(relayUrl: String, identity: RelayIdentity): RelayIdentity = database.query {
        val now = nowMillis()
        PushRelayInstances.insertIgnore {
            it[PushRelayInstances.relayUrl] = relayUrl; it[publicKey] = identity.publicKey; it[privateKey] = identity.sealedPrivateKey
            it[instanceId] = identity.instanceId; it[createdAt] = now
        }
        read(relayUrl)!!
    }

    override suspend fun setInstance(relayUrl: String, publicKey: String, instanceId: String?) = database.query {
        PushRelayInstances.update({ (PushRelayInstances.relayUrl eq relayUrl) and (PushRelayInstances.publicKey eq publicKey) }) { it[PushRelayInstances.instanceId] = instanceId }
        Unit
    }

    private fun read(relayUrl: String) = PushRelayInstances.selectAll().where { PushRelayInstances.relayUrl eq relayUrl }.singleOrNull()?.let {
        RelayIdentity(it[PushRelayInstances.publicKey], it[PushRelayInstances.privateKey], it[PushRelayInstances.instanceId])
    }
}
