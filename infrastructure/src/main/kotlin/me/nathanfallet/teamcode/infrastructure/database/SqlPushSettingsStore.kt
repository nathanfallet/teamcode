package me.nathanfallet.teamcode.infrastructure.database

import me.nathanfallet.teamcode.domain.notifications.*
import me.nathanfallet.teamcode.domain.team.*
import me.nathanfallet.teamcode.infrastructure.database.tables.PushSettingsTable
import me.nathanfallet.teamcode.infrastructure.secrets.CredentialCipher
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*

/**
 * A team's push provider. Writes run under the team lock and revalidate the administrator in the same transaction; the
 * Firebase key is sealed with the server's credential key, bound to the team, and opened only by [connections].
 */
class SqlPushSettingsStore(private val database: Database, private val cipher: CredentialCipher) : PushSettingsStore {
    override suspend fun settings(actor: Principal): PushSettings = database.query { requireCurrent(this, actor); read(actor.teamId) }

    override suspend fun save(actor: Principal, provider: PushProvider, relayUrl: String, serviceAccount: FirebaseServiceAccount?, version: Int): PushSettings = database.query {
        lockTeam(actor.teamId); requireCurrent(this, actor)
        val current = read(actor.teamId)
        if (current.version != version) throw TeamFailure("push_settings_conflict", 409, "The push settings changed. Reload them before saving.")
        if (provider == PushProvider.FIREBASE && serviceAccount == null && current.firebaseProjectId == null)
            throw TeamFailure("push_firebase_credentials_required", 400, "Paste the service account JSON key of your Firebase project.")
        PushSettingsTable.upsert {
            it[teamId] = actor.teamId; it[PushSettingsTable.provider] = provider.wire; it[PushSettingsTable.relayUrl] = relayUrl
            // The key is kept only while the team's own Firebase project sends; leaving it removes the key.
            if (provider == PushProvider.FIREBASE && serviceAccount != null) {
                it[firebaseProjectId] = serviceAccount.projectId; it[firebaseClientEmail] = serviceAccount.clientEmail
                it[firebaseCredentials] = cipher.encrypt(serviceAccount.json.encodeToByteArray(), context(actor.teamId))
            } else if (provider != PushProvider.FIREBASE) {
                it[firebaseProjectId] = null; it[firebaseClientEmail] = null; it[firebaseCredentials] = null
            }
            it[PushSettingsTable.version] = current.version + 1; it[updatedAt] = nowMillis()
        }
        audit(actor.teamId, actor.member.id, "push.settings_updated", provider.wire, null, actor.tokenId)
        if (provider == PushProvider.FIREBASE && serviceAccount != null) audit(actor.teamId, actor.member.id, "push.firebase_key_saved", serviceAccount.projectId, null, actor.tokenId)
        read(actor.teamId)
    }

    override suspend fun connections(teamIds: Set<String>): Map<String, PushConnection> {
        if (teamIds.isEmpty()) return emptyMap()
        return database.query {
            PushSettingsTable.select(PushSettingsTable.teamId, PushSettingsTable.provider, PushSettingsTable.relayUrl, PushSettingsTable.firebaseCredentials, PushSettingsTable.version)
                .where { (PushSettingsTable.teamId inList teamIds) and (PushSettingsTable.provider neq PushProvider.OFF.wire) }
                .mapNotNull { row ->
                    val team = row[PushSettingsTable.teamId]
                    val provider = PushProvider.parse(row[PushSettingsTable.provider]) ?: return@mapNotNull null
                    // The key is opened only when it is the one that sends.
                    val key = if (provider == PushProvider.FIREBASE) row[PushSettingsTable.firebaseCredentials]?.let { cipher.decrypt(it, context(team)).decodeToString() } ?: return@mapNotNull null else null
                    team to PushConnection(team, provider, row[PushSettingsTable.relayUrl], key, row[PushSettingsTable.version])
                }.toMap()
        }
    }

    private fun read(teamId: String): PushSettings = PushSettingsTable
        .select(PushSettingsTable.provider, PushSettingsTable.relayUrl, PushSettingsTable.firebaseProjectId, PushSettingsTable.firebaseClientEmail, PushSettingsTable.version, PushSettingsTable.updatedAt)
        .where { PushSettingsTable.teamId eq teamId }.singleOrNull()?.let {
            PushSettings(PushProvider.parse(it[PushSettingsTable.provider]) ?: PushProvider.OFF, it[PushSettingsTable.relayUrl], it[PushSettingsTable.firebaseProjectId],
                it[PushSettingsTable.firebaseClientEmail], it[PushSettingsTable.version], it[PushSettingsTable.updatedAt])
        } ?: PushSettings()

    private fun context(teamId: String) = "push-firebase:$teamId"
}
