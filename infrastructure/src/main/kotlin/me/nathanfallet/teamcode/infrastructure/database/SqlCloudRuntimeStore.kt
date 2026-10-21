package me.nathanfallet.teamcode.infrastructure.database

import me.nathanfallet.teamcode.domain.cloud.CloudRuntime
import me.nathanfallet.teamcode.domain.cloud.CloudRuntimeStore
import me.nathanfallet.teamcode.domain.team.Principal
import me.nathanfallet.teamcode.domain.team.requireAdmin
import me.nathanfallet.teamcode.infrastructure.database.tables.CloudRuntimeTable
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.*

/**
 * The cloud runtime of the server's team ([teamId]: the single team the server bootstraps). Writes revalidate the
 * interactive administrator under the team lock; no value here is a credential.
 */
class SqlCloudRuntimeStore(private val database: Database, private val teamId: String = "default") : CloudRuntimeStore {
    override suspend fun runtime(actor: Principal): CloudRuntime = database.query { requireCurrent(this, actor); read(actor.teamId) }

    override suspend fun saveRuntime(actor: Principal, runtime: CloudRuntime): CloudRuntime = database.query {
        actor.requireAdmin(); lockTeam(actor.teamId); requireCurrent(this, actor)
        write(actor.teamId, actor.member.id, runtime, nowMillis())
        audit(actor.teamId, actor.member.id, "cloud.runtime.updated", actor.teamId, null, actor.tokenId)
        read(actor.teamId)
    }

    override suspend fun current(): CloudRuntime = database.query { read(teamId) }

    override suspend fun seed(initial: CloudRuntime): CloudRuntime = database.query {
        lockTeam(teamId)
        if (CloudRuntimeTable.select(CloudRuntimeTable.teamId).where { CloudRuntimeTable.teamId eq teamId }.empty()) {
            write(teamId, "system", initial, nowMillis())
            audit(teamId, "system", "cloud.runtime.seeded", teamId)
        }
        read(teamId)
    }

    private fun write(team: String, actor: String, runtime: CloudRuntime, now: Long) {
        CloudRuntimeTable.upsert {
            it[CloudRuntimeTable.teamId] = team; it[enabled] = runtime.enabled; it[runnerImage] = runtime.runnerImage
            it[runnerBinary] = runtime.runnerBinary; it[serverUrl] = runtime.serverUrl; it[workspaceAccessMode] = runtime.workspaceAccessMode
            it[updatedBy] = actor; it[updatedAt] = now
        }
    }

    private fun read(team: String): CloudRuntime = CloudRuntimeTable.selectAll().where { CloudRuntimeTable.teamId eq team }.singleOrNull()?.let(::runtime) ?: CloudRuntime()

    private fun runtime(row: ResultRow) = CloudRuntime(row[CloudRuntimeTable.enabled], row[CloudRuntimeTable.runnerImage], row[CloudRuntimeTable.runnerBinary],
        row[CloudRuntimeTable.serverUrl], row[CloudRuntimeTable.workspaceAccessMode], row[CloudRuntimeTable.updatedAt])
}
