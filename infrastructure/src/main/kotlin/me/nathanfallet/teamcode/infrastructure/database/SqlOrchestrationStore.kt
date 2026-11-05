package me.nathanfallet.teamcode.infrastructure.database

import me.nathanfallet.teamcode.domain.cloud.CloudSessionPage
import me.nathanfallet.teamcode.domain.cloud.OrchestrationStore
import me.nathanfallet.teamcode.domain.team.TeamFailure
import me.nathanfallet.teamcode.infrastructure.database.tables.CloudInferenceCredentials
import me.nathanfallet.teamcode.infrastructure.database.tables.CloudSessions
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*

/** The reads of session orchestration (docs/orchestration.md); each call is one transaction. */
class SqlOrchestrationStore(private val database: Database) : OrchestrationStore {
    override suspend fun executionSession(tokenId: String): String? = database.query {
        CloudInferenceCredentials.select(CloudInferenceCredentials.sessionId).where { CloudInferenceCredentials.tokenId eq tokenId }.singleOrNull()?.get(CloudInferenceCredentials.sessionId)
    }

    /**
     * `cloud_session_parent (parent_session_id, created_at, id)`: the parent's children in index order from the cursor, at most
     * [limit] + 1 rows, then their labels in one `inList` read. No sort step, no read of another parent's children.
     */
    override suspend fun children(teamId: String, parentId: String, beforeId: String?, limit: Int): CloudSessionPage = database.query {
        val cursor = beforeId?.let { before ->
            CloudSessions.select(CloudSessions.createdAt).where { (CloudSessions.id eq before) and (CloudSessions.parentSessionId eq parentId) }.singleOrNull()?.get(CloudSessions.createdAt)
                ?: throw TeamFailure("invalid_cursor", 400, "Unknown session cursor.")
        }
        var condition: Op<Boolean> = (CloudSessions.parentSessionId eq parentId) and (CloudSessions.teamId eq teamId)
        if (cursor != null) condition = condition and ((CloudSessions.createdAt less cursor) or ((CloudSessions.createdAt eq cursor) and (CloudSessions.id less beforeId!!)))
        val rows = sessionsWithHost.selectAll().where { condition }
            .orderBy(CloudSessions.createdAt to SortOrder.DESC, CloudSessions.id to SortOrder.DESC).limit(limit + 1).toList()
        val page = rows.take(limit)
        val labels = labelsFor(page.map { it[CloudSessions.id] })
        val sessions = page.map { readSessionRow(it, labels[it[CloudSessions.id]].orEmpty()) }
        CloudSessionPage(sessions, if (rows.size > limit) sessions.last().id else null)
    }
}
