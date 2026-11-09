package me.nathanfallet.teamcode.infrastructure.database.tables

import org.jetbrains.exposed.v1.core.*

/**
 * This server's identity at a TeamCode Push relay: an Ed25519 key pair made once and shared by every replica, and the
 * instance id the relay gave for its public key. [privateKey] is sealed (`push-relay:<relay url>` context).
 */
object PushRelayInstances : Table("push_relay_instances") {
    val relayUrl = varchar("relay_url", 255)
    val publicKey = varchar("public_key", 64)
    val privateKey = text("private_key")
    val instanceId = varchar("instance_id", 64).nullable()
    val createdAt = long("created_at")
    override val primaryKey = PrimaryKey(relayUrl)
}

/**
 * A team's push provider; absent means push off. [firebaseCredentials] is the service account JSON sealed with the
 * server's key (`push-firebase:<team>` context), set only while it is the team's own Firebase project that sends.
 */
object PushSettingsTable : Table("push_settings") {
    val teamId = varchar("team_id", 36)
    val provider = varchar("provider", 16)
    val relayUrl = varchar("relay_url", 255)
    val firebaseProjectId = varchar("firebase_project_id", 64).nullable()
    val firebaseClientEmail = varchar("firebase_client_email", 255).nullable()
    val firebaseCredentials = text("firebase_credentials").nullable()
    val version = integer("version")
    val updatedAt = long("updated_at")
    override val primaryKey = PrimaryKey(teamId)
}
