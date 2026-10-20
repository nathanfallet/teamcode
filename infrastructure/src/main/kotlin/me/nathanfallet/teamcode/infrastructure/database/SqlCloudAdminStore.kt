package me.nathanfallet.teamcode.infrastructure.database

import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import me.nathanfallet.teamcode.domain.cloud.*
import me.nathanfallet.teamcode.domain.team.*
import me.nathanfallet.teamcode.infrastructure.database.tables.*
import me.nathanfallet.teamcode.infrastructure.secrets.CredentialCipher
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*

/** A decrypted cluster connection for the cluster adapter only. Never logged or serialized. */
class ClusterConnection(val apiUrl: String, val caCertificate: String, val token: String) {
    override fun toString() = "ClusterConnection(apiUrl=$apiUrl, token=[REDACTED])"
}

/** Clusters, versioned profiles and settings. Writes revalidate the interactive administrator under the team lock. */
class SqlCloudAdminStore(private val database: Database, private val cipher: CredentialCipher) : CloudAdminStore {
    override suspend fun clusters(actor: Principal): List<CloudCluster> = database.query {
        requireCurrent(this, actor)
        CloudClusters.selectAll().where { CloudClusters.teamId eq actor.teamId }.orderBy(CloudClusters.name).map(::readCluster)
    }

    override suspend fun saveCluster(actor: Principal, id: String?, expectedVersion: Int?, input: CloudClusterInput): CloudCluster = database.query {
        lockTeam(actor.teamId); requireCurrent(this, actor)
        val now = nowMillis()
        val duplicate = CloudClusters.select(CloudClusters.id).where { (CloudClusters.teamId eq actor.teamId) and (CloudClusters.name eq input.name) }.singleOrNull()?.get(CloudClusters.id)
        if (duplicate != null && duplicate != id) throw TeamFailure("cluster_conflict", 409, "Another cluster already uses this name.")
        val clusterId = id ?: UUID.randomUUID().toString()
        if (id == null) {
            if (CloudClusters.select(CloudClusters.id).where { CloudClusters.teamId eq actor.teamId }.count() >= 16) throw TeamFailure("cluster_limit", 409, "A team can connect at most 16 clusters.")
            CloudClusters.insert {
                it[CloudClusters.id] = clusterId; it[teamId] = actor.teamId; it[name] = input.name; it[apiUrl] = input.apiUrl
                it[caCertificate] = requireNotNull(input.caCertificate)
                it[credential] = cipher.encrypt(requireNotNull(input.serviceAccountToken).encodeToByteArray(), context(clusterId))
                it[allowedNamespaces] = Json.encodeToString(input.allowedNamespaces); it[maxRunningPods] = input.maxRunningPods
                it[defaultStorageClass] = input.defaultStorageClass; it[version] = 1; it[createdAt] = now; it[updatedAt] = now
            }
        } else {
            val updated = CloudClusters.update({ (CloudClusters.id eq id) and (CloudClusters.teamId eq actor.teamId) and (CloudClusters.version eq (expectedVersion ?: -1)) }) {
                it[name] = input.name; it[apiUrl] = input.apiUrl
                input.caCertificate?.let { ca -> it[caCertificate] = ca }
                input.serviceAccountToken?.let { token -> it[credential] = cipher.encrypt(token.encodeToByteArray(), context(clusterId)) }
                it[allowedNamespaces] = Json.encodeToString(input.allowedNamespaces); it[maxRunningPods] = input.maxRunningPods
                it[defaultStorageClass] = input.defaultStorageClass; it[version] = (expectedVersion ?: 0) + 1; it[updatedAt] = now
            }
            if (updated != 1) throw TeamFailure("cluster_conflict", 409, "This cluster changed. Reload it before saving.")
        }
        audit(actor.teamId, actor.member.id, "cloud.cluster.saved", clusterId, null, actor.tokenId)
        readCluster(CloudClusters.selectAll().where { CloudClusters.id eq clusterId }.single())
    }

    override suspend fun profiles(actor: Principal): List<CloudProfile> = database.query {
        requireCurrent(this, actor)
        val ids = if (actor.member.role.isAdministrator) CloudProfiles.select(CloudProfiles.id).where { CloudProfiles.teamId eq actor.teamId }.map { it[CloudProfiles.id] }
            else (CloudProfiles innerJoin CloudProfileAssignments).select(CloudProfiles.id).where {
                (CloudProfiles.teamId eq actor.teamId) and (CloudProfileAssignments.memberId eq actor.member.id)
            }.map { it[CloudProfiles.id] }
        ids.take(200).mapNotNull { readProfile(actor.teamId, it) }.sortedBy { it.name }
    }

    override suspend fun saveProfile(actor: Principal, id: String?, expectedVersion: Int, name: String, spec: CloudProfileSpec, assignedMemberIds: Set<String>): CloudProfile = database.query {
        lockTeam(actor.teamId); requireCurrent(this, actor)
        val now = nowMillis()
        if (CloudClusters.select(CloudClusters.id).where { (CloudClusters.id eq spec.clusterId) and (CloudClusters.teamId eq actor.teamId) }.empty())
            throw TeamFailure("cluster_not_found", 404, "Cluster not found.")
        if (assignedMemberIds.isNotEmpty() && Members.select(Members.id).where { (Members.teamId eq actor.teamId) and (Members.id inList assignedMemberIds) }.count() != assignedMemberIds.size.toLong())
            throw TeamFailure("member_not_found", 404, "An assigned member does not exist in this team.")
        val duplicate = CloudProfiles.select(CloudProfiles.id).where { (CloudProfiles.teamId eq actor.teamId) and (CloudProfiles.name eq name) }.singleOrNull()?.get(CloudProfiles.id)
        if (duplicate != null && duplicate != id) throw TeamFailure("profile_conflict", 409, "Another profile already uses this name.")
        val profileId = id ?: UUID.randomUUID().toString()
        val version = expectedVersion + 1
        if (id == null) {
            if (expectedVersion != 0) throw TeamFailure("profile_conflict", 409, "A new profile starts from version 0.")
            if (CloudProfiles.select(CloudProfiles.id).where { CloudProfiles.teamId eq actor.teamId }.count() >= 200) throw TeamFailure("profile_limit", 409, "A team can define at most 200 profiles.")
            CloudProfiles.insert { it[CloudProfiles.id] = profileId; it[teamId] = actor.teamId; it[CloudProfiles.name] = name; it[currentVersion] = version; it[createdAt] = now; it[updatedAt] = now }
        } else {
            val updated = CloudProfiles.update({ (CloudProfiles.id eq id) and (CloudProfiles.teamId eq actor.teamId) and (CloudProfiles.currentVersion eq expectedVersion) }) {
                it[CloudProfiles.name] = name; it[currentVersion] = version; it[updatedAt] = now
            }
            if (updated != 1) throw TeamFailure("profile_conflict", 409, "This profile changed. Reload it before saving.")
        }
        CloudProfileVersions.insert { it[CloudProfileVersions.profileId] = profileId; it[CloudProfileVersions.version] = version; it[CloudProfileVersions.spec] = encodeProfileSpec(spec); it[createdBy] = actor.member.id; it[createdAt] = now }
        CloudProfileAssignments.deleteWhere { CloudProfileAssignments.profileId eq profileId }
        for (member in assignedMemberIds.sorted()) CloudProfileAssignments.insert { it[CloudProfileAssignments.profileId] = profileId; it[memberId] = member }
        audit(actor.teamId, actor.member.id, "cloud.profile.saved", profileId, null, actor.tokenId)
        readProfile(actor.teamId, profileId)!!
    }

    override suspend fun settings(actor: Principal): CloudSettings = database.query { requireCurrent(this, actor); readCloudSettings(actor.teamId) }
    override suspend fun saveSettings(actor: Principal, settings: CloudSettings): CloudSettings = database.query {
        lockTeam(actor.teamId); requireCurrent(this, actor)
        CloudSettingsTable.upsert {
            it[teamId] = actor.teamId; it[pausedRetentionDays] = settings.pausedRetentionDays
            it[failedWorkspaceRetentionDays] = settings.failedWorkspaceRetentionDays
            it[maxRunningSessionsPerMember] = settings.maxRunningSessionsPerMember; it[maxRetainedSessionsPerMember] = settings.maxRetainedSessionsPerMember
        }
        audit(actor.teamId, actor.member.id, "cloud.settings.updated", actor.teamId, null, actor.tokenId)
        settings
    }

    /** For the cluster adapter: the decrypted connection of a cluster, bound to its id by the cipher context. */
    suspend fun connection(clusterId: String): ClusterConnection? = database.query {
        CloudClusters.select(CloudClusters.apiUrl, CloudClusters.caCertificate, CloudClusters.credential).where { CloudClusters.id eq clusterId }.singleOrNull()?.let {
            ClusterConnection(it[CloudClusters.apiUrl], it[CloudClusters.caCertificate], cipher.decrypt(it[CloudClusters.credential], context(clusterId)).decodeToString())
        }
    }

    private fun context(clusterId: String) = "cloud-cluster:$clusterId"
}

@Serializable
private data class StoredResources(val cpuMillis: Int, val memoryMebibytes: Int)
@Serializable
private data class StoredProfileSpec(
    val clusterId: String, val namespace: String, val image: String, val allowedImageOverrides: List<String>, val imagePullSecrets: List<String>,
    val resources: StoredResources, val workspaceSizeGibibytes: Int, val storageClass: String?, val agentKind: String, val agentVersion: String?,
    val idleTimeoutMinutes: Int, val minIdleTimeoutMinutes: Int, val maxIdleTimeoutMinutes: Int,
)
internal fun encodeProfileSpec(s: CloudProfileSpec) = Json.encodeToString(StoredProfileSpec(s.clusterId, s.namespace, s.image, s.allowedImageOverrides, s.imagePullSecrets,
    StoredResources(s.resources.cpuMillis, s.resources.memoryMebibytes), s.workspaceSizeGibibytes, s.storageClass, s.agentKind, s.agentVersion,
    s.idleTimeoutMinutes, s.minIdleTimeoutMinutes, s.maxIdleTimeoutMinutes))
internal fun decodeProfileSpec(value: String) = Json.decodeFromString<StoredProfileSpec>(value).let { s ->
    CloudProfileSpec(s.clusterId, s.namespace, s.image, s.allowedImageOverrides, s.imagePullSecrets, CloudResources(s.resources.cpuMillis, s.resources.memoryMebibytes),
        s.workspaceSizeGibibytes, s.storageClass, s.agentKind, s.agentVersion, s.idleTimeoutMinutes, s.minIdleTimeoutMinutes, s.maxIdleTimeoutMinutes)
}
internal fun readProfile(teamId: String, profileId: String): CloudProfile? {
    val row = CloudProfiles.selectAll().where { (CloudProfiles.id eq profileId) and (CloudProfiles.teamId eq teamId) }.singleOrNull() ?: return null
    val version = row[CloudProfiles.currentVersion]
    val spec = CloudProfileVersions.select(CloudProfileVersions.spec).where { (CloudProfileVersions.profileId eq profileId) and (CloudProfileVersions.version eq version) }.single()[CloudProfileVersions.spec]
    val assigned = CloudProfileAssignments.select(CloudProfileAssignments.memberId).where { CloudProfileAssignments.profileId eq profileId }.map { it[CloudProfileAssignments.memberId] }.toSet()
    return CloudProfile(profileId, teamId, row[CloudProfiles.name], version, decodeProfileSpec(spec), assigned, row[CloudProfiles.createdAt], row[CloudProfiles.updatedAt])
}
internal fun readCluster(row: ResultRow) = CloudCluster(
    row[CloudClusters.id], row[CloudClusters.teamId], row[CloudClusters.name], row[CloudClusters.apiUrl], Json.decodeFromString(row[CloudClusters.allowedNamespaces]),
    row[CloudClusters.maxRunningPods], row[CloudClusters.defaultStorageClass], row[CloudClusters.version], row[CloudClusters.orphanedVolumes],
    row[CloudClusters.lastGarbageCollectionAt], row[CloudClusters.createdAt], row[CloudClusters.updatedAt],
)
internal fun readCloudSettings(teamId: String): CloudSettings = CloudSettingsTable.selectAll().where { CloudSettingsTable.teamId eq teamId }.singleOrNull()?.let {
    CloudSettings(it[CloudSettingsTable.pausedRetentionDays], it[CloudSettingsTable.failedWorkspaceRetentionDays], it[CloudSettingsTable.maxRunningSessionsPerMember], it[CloudSettingsTable.maxRetainedSessionsPerMember])
} ?: CloudSettings()
internal fun readMember(row: ResultRow) = Member(row[Members.id], row[Members.username], Role.valueOf(row[Members.role]), row[Members.disabled], row[Members.mustChangePassword])
