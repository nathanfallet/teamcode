package me.nathanfallet.teamcode.infrastructure.database

import me.nathanfallet.teamcode.domain.artifacts.*
import me.nathanfallet.teamcode.domain.team.*
import me.nathanfallet.teamcode.infrastructure.database.tables.*
import me.nathanfallet.teamcode.infrastructure.secrets.CredentialCipher
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*

/**
 * The artifact bucket and settings. Writes run under the team lock and revalidate the interactive administrator in
 * the same transaction; the secret access key is sealed with the server's credential key, bound to the team, and never
 * read back except by [connection].
 */
class SqlArtifactAdminStore(private val database: Database, private val cipher: CredentialCipher) : ArtifactAdminStore {
    override suspend fun storage(actor: Principal): ArtifactStorage? = database.query { requireCurrent(this, actor); readStorage(actor.teamId) }

    override suspend fun inUse(actor: Principal): Boolean = database.query { requireCurrent(this, actor); hasArtifacts(actor.teamId) }

    override suspend fun overview(actor: Principal): ArtifactAdminOverview = database.query {
        requireCurrent(this, actor)
        ArtifactAdminOverview(readStorage(actor.teamId), readSettings(actor.teamId), hasArtifacts(actor.teamId))
    }

    override suspend fun usage(actor: Principal): ArtifactUsage = database.query {
        requireCurrent(this, actor)
        val total = Artifacts.storedBytes.sum()
        val teamBytes = Artifacts.select(total).where { (Artifacts.teamId eq actor.teamId) and (Artifacts.state eq "active") }.single()[total] ?: 0
        ArtifactUsage(teamBytes, usage(actor.teamId))
    }

    /** One grouped read of the team's published artifacts (`artifacts_team`), then the owners' names by primary key. */
    private fun usage(teamId: String): List<ArtifactMemberUsage> {
        val bytes = Artifacts.storedBytes.sum()
        val count = Artifacts.id.count()
        val rows = Artifacts.select(Artifacts.ownerId, bytes, count).where { (Artifacts.teamId eq teamId) and (Artifacts.state eq "active") }
            .groupBy(Artifacts.ownerId).orderBy(bytes to SortOrder.DESC, Artifacts.ownerId to SortOrder.ASC).limit(ArtifactAdministrationService.MAX_USAGE_ROWS).toList()
        if (rows.isEmpty()) return emptyList()
        val names = Members.select(Members.id, Members.username).where { Members.id inList rows.map { it[Artifacts.ownerId] } }.associate { it[Members.id] to it[Members.username] }
        return rows.map { ArtifactMemberUsage(it[Artifacts.ownerId], names[it[Artifacts.ownerId]].orEmpty(), it[count].toInt(), it[bytes] ?: 0) }
    }

    override suspend fun saveStorage(actor: Principal, expectedVersion: Int, input: ArtifactStorageInput): ArtifactStorage = database.query {
        lockTeam(actor.teamId); requireCurrent(this, actor)
        val now = nowMillis()
        val current = readStorage(actor.teamId)
        if ((current?.version ?: 0) != expectedVersion) throw TeamFailure("artifact_storage_conflict", 409, "The artifact storage changed. Reload it before saving.")
        if (current == null) {
            val secret = input.secretAccessKey ?: throw TeamFailure("artifact_storage_secret_required", 400, "Paste the secret access key.")
            ArtifactStorageTable.insert {
                it[teamId] = actor.teamId; write(it, input); it[ArtifactStorageTable.secret] = seal(secret, actor.teamId)
                it[verifiedAt] = null; it[version] = 1; it[updatedAt] = now
            }
        } else {
            val moved = current.endpoint != input.endpoint || current.bucket != input.bucket || current.prefix != input.prefix
            // Artifacts already stored live under the current endpoint, bucket and prefix; moving them comes later.
            if (moved && hasArtifacts(actor.teamId)) throw TeamFailure("artifact_storage_in_use", 409, "Artifacts are stored in this bucket: the endpoint, bucket and prefix cannot change. Keys can still be rotated.")
            // The stored secret belongs to the stored endpoint and bucket; a different bucket must be given its own.
            if ((current.endpoint != input.endpoint || current.bucket != input.bucket) && input.secretAccessKey == null)
                throw TeamFailure("artifact_storage_secret_required", 400, "Changing the endpoint or the bucket requires the secret access key for it.")
            ArtifactStorageTable.update({ (ArtifactStorageTable.teamId eq actor.teamId) and (ArtifactStorageTable.version eq expectedVersion) }) {
                write(it, input); input.secretAccessKey?.let { secret -> it[ArtifactStorageTable.secret] = seal(secret, actor.teamId) }
                it[verifiedAt] = null; it[version] = expectedVersion + 1; it[updatedAt] = now
            }
        }
        audit(actor.teamId, actor.member.id, "artifacts.storage_saved", actor.teamId, null, actor.tokenId)
        readStorage(actor.teamId)!!
    }

    override suspend fun deleteStorage(actor: Principal) = database.query {
        lockTeam(actor.teamId); requireCurrent(this, actor)
        if (readStorage(actor.teamId) == null) throw TeamFailure("artifact_storage_missing", 404, "No artifact storage is configured.")
        if (hasArtifacts(actor.teamId)) throw TeamFailure("artifact_storage_in_use", 409, "Artifacts are stored in this bucket: delete them before removing the storage.")
        ArtifactStorageTable.deleteWhere { ArtifactStorageTable.teamId eq actor.teamId }
        // Without a bucket artifacts cannot run: the switch goes off, so a new bucket must be tested and turned on again.
        val settings = readSettings(actor.teamId)
        if (settings.enabled) {
            // A settings change like any other: a new version, so a form loaded before cannot turn them back on unnoticed.
            ArtifactSettingsTable.update({ ArtifactSettingsTable.teamId eq actor.teamId }) { it[enabled] = false; it[version] = settings.version + 1; it[updatedAt] = nowMillis() }
            audit(actor.teamId, actor.member.id, "artifacts.settings_updated", actor.teamId, null, actor.tokenId)
        }
        audit(actor.teamId, actor.member.id, "artifacts.storage_deleted", actor.teamId, null, actor.tokenId)
        Unit
    }

    override suspend fun recordTest(actor: Principal, version: Int, passed: Boolean): Boolean = database.query {
        lockTeam(actor.teamId); requireCurrent(this, actor)
        val now = nowMillis()
        val updated = ArtifactStorageTable.update({ (ArtifactStorageTable.teamId eq actor.teamId) and (ArtifactStorageTable.version eq version) }) {
            it[verifiedAt] = if (passed) now else null
        }
        if (updated == 1) audit(actor.teamId, actor.member.id, "artifacts.storage_tested", if (passed) "passed" else "failed", null, actor.tokenId)
        updated == 1
    }

    override suspend fun settings(actor: Principal): ArtifactSettings = database.query { requireCurrent(this, actor); readSettings(actor.teamId) }

    override suspend fun saveSettings(actor: Principal, settings: ArtifactSettings): ArtifactSettings = database.query {
        lockTeam(actor.teamId); requireCurrent(this, actor)
        val current = readSettings(actor.teamId)
        if (current.version != settings.version) throw TeamFailure("artifact_settings_conflict", 409, "The artifact settings changed. Reload them before saving.")
        if (settings.enabled && !current.enabled) {
            val storage = readStorage(actor.teamId) ?: throw TeamFailure("artifact_storage_missing", 409, "Configure the artifact storage and test it before turning artifacts on.")
            if (storage.verifiedAt == null) throw TeamFailure("artifact_storage_unverified", 409, "Test the artifact storage successfully before turning artifacts on.")
        }
        val now = nowMillis()
        ArtifactSettingsTable.upsert {
            it[teamId] = actor.teamId; it[enabled] = settings.enabled
            it[whoCanPublish] = settings.whoCanPublish.wire; it[publicSharing] = settings.publicSharing.wire
            it[memberBytes] = settings.quotas.memberBytes; it[teamBytes] = settings.quotas.teamBytes
            it[artifactsPerMember] = settings.quotas.artifactsPerMember; it[publishesPerHour] = settings.quotas.publishesPerHour
            it[personalRetentionDays] = settings.retention.personalDays; it[sharedRetentionDays] = settings.retention.sharedDays
            it[maxVersionsPerArtifact] = settings.retention.maxVersionsPerArtifact
            it[version] = current.version + 1; it[updatedAt] = now
        }
        audit(actor.teamId, actor.member.id, "artifacts.settings_updated", actor.teamId, null, actor.tokenId)
        if (current.publicSharing != settings.publicSharing)
            audit(actor.teamId, actor.member.id, "artifacts.public_sharing_changed", "${current.publicSharing.wire}->${settings.publicSharing.wire}", null, actor.tokenId)
        readSettings(actor.teamId)
    }

    override suspend fun connection(teamId: String): ArtifactStorageConnection? = database.query {
        ArtifactStorageTable.selectAll().where { ArtifactStorageTable.teamId eq teamId }.singleOrNull()?.let {
            ArtifactStorageConnection(it[ArtifactStorageTable.endpoint], it[ArtifactStorageTable.region], it[ArtifactStorageTable.bucket], it[ArtifactStorageTable.prefix],
                it[ArtifactStorageTable.pathStyle], it[ArtifactStorageTable.accessKeyId], cipher.decrypt(it[ArtifactStorageTable.secret], context(teamId)).decodeToString(),
                ServerSideEncryption.parse(it[ArtifactStorageTable.serverSideEncryption]) ?: ServerSideEncryption.NONE, it[ArtifactStorageTable.version])
        }
    }

    private fun write(it: org.jetbrains.exposed.v1.core.statements.UpdateBuilder<*>, input: ArtifactStorageInput) {
        it[ArtifactStorageTable.endpoint] = input.endpoint.removeSuffix("/"); it[ArtifactStorageTable.region] = input.region; it[ArtifactStorageTable.bucket] = input.bucket
        it[ArtifactStorageTable.prefix] = input.prefix; it[ArtifactStorageTable.pathStyle] = input.pathStyle; it[ArtifactStorageTable.accessKeyId] = input.accessKeyId
        it[ArtifactStorageTable.serverSideEncryption] = input.serverSideEncryption.wire
    }
    private fun seal(secret: String, teamId: String) = cipher.encrypt(secret.encodeToByteArray(), context(teamId))
    private fun context(teamId: String) = "artifact-storage:$teamId"
    /** Every artifact but a deleted one whose folder was proven empty: drafts and deletions in progress may still hold files. */
    private fun hasArtifacts(teamId: String) = !Artifacts.select(Artifacts.id).where {
        (Artifacts.teamId eq teamId) and not((Artifacts.state eq "deleted") and Artifacts.storageDeletedAt.isNotNull())
    }.limit(1).empty()
}

internal fun readStorage(teamId: String): ArtifactStorage? = ArtifactStorageTable.selectAll().where { ArtifactStorageTable.teamId eq teamId }.singleOrNull()?.let {
    ArtifactStorage(teamId, it[ArtifactStorageTable.endpoint], it[ArtifactStorageTable.region], it[ArtifactStorageTable.bucket], it[ArtifactStorageTable.prefix],
        it[ArtifactStorageTable.pathStyle], it[ArtifactStorageTable.accessKeyId], ServerSideEncryption.parse(it[ArtifactStorageTable.serverSideEncryption]) ?: ServerSideEncryption.NONE,
        it[ArtifactStorageTable.verifiedAt], it[ArtifactStorageTable.version], it[ArtifactStorageTable.updatedAt])
}

internal fun readSettings(teamId: String): ArtifactSettings = ArtifactSettingsTable.selectAll().where { ArtifactSettingsTable.teamId eq teamId }.singleOrNull()?.let {
    ArtifactSettings(it[ArtifactSettingsTable.enabled], WhoCanPublish.parse(it[ArtifactSettingsTable.whoCanPublish]) ?: WhoCanPublish.EVERYONE,
        PublicSharing.parse(it[ArtifactSettingsTable.publicSharing]) ?: PublicSharing.OWNERS,
        ArtifactQuotas(it[ArtifactSettingsTable.memberBytes], it[ArtifactSettingsTable.teamBytes], it[ArtifactSettingsTable.artifactsPerMember], it[ArtifactSettingsTable.publishesPerHour]),
        ArtifactRetention(it[ArtifactSettingsTable.personalRetentionDays], it[ArtifactSettingsTable.sharedRetentionDays], it[ArtifactSettingsTable.maxVersionsPerArtifact]),
        it[ArtifactSettingsTable.version], it[ArtifactSettingsTable.updatedAt])
} ?: ArtifactSettings()
