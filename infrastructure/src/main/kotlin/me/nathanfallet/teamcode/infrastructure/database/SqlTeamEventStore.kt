package me.nathanfallet.teamcode.infrastructure.database

import kotlinx.serialization.json.*
import me.nathanfallet.teamcode.domain.cloud.CloudSession
import me.nathanfallet.teamcode.domain.team.*
import me.nathanfallet.teamcode.infrastructure.database.tables.*
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*

/**
 * Appends to the team's feed inside the caller's transaction, so an event exists exactly when the change it reports
 * committed. Call it last, after every other lock of the transaction: the cursor row is the one lock all publishers
 * of a team share, and taking it last keeps lock order the same everywhere.
 */
internal fun publishTeamEvent(team: String, type: TeamEventType, subject: String, owner: String, data: JsonObject, at: Long) {
    // One statement takes the row's lock and the next number: a read-then-insert for a team's first event would deadlock
    // two publishers on the gap lock. The lock is held to commit, so commit order is sequence order.
    TeamEventCursors.upsert(onUpdate = { it[TeamEventCursors.lastSeq] = TeamEventCursors.lastSeq + 1L }) { it[teamId] = team; it[lastSeq] = 1L }
    val seq = TeamEventCursors.select(TeamEventCursors.lastSeq).where { TeamEventCursors.teamId eq team }.single()[TeamEventCursors.lastSeq]
    TeamEvents.insert {
        it[teamId] = team; it[TeamEvents.seq] = seq; it[TeamEvents.type] = type.wire; it[subjectId] = subject
        it[ownerId] = owner; it[TeamEvents.at] = at; it[TeamEvents.data] = data.toString()
    }
}

/**
 * Publishes what a session write means to the feed (see [TeamEventRules.session]); the caller holds the session row
 * lock and calls this after its other writes. [action] is the write's audit action, which names the reason.
 */
internal fun publishSessionChange(before: CloudSession, after: CloudSession, action: String?, at: Long) {
    for (type in TeamEventRules.session(before.state, after.state, action)) {
        publishTeamEvent(after.teamId, type, after.id, after.ownerId, sessionData(after, before.state, action), at)
    }
}

internal fun sessionData(session: CloudSession, previous: me.nathanfallet.teamcode.domain.cloud.CloudSessionState?, action: String?) = buildJsonObject {
    put("sessionId", session.id); put("name", session.name); put("state", session.state.wire)
    if (previous != null) put("previousState", previous.wire)
    // The audit action names why, for example `idle_paused` or `expired`.
    action?.removePrefix("cloud.session.")?.let { put("reason", it) }
}

/** Reads the feed with indexed, bounded queries; see [publishTeamEvent] for how the sequence stays gap-free. */
class SqlTeamEventStore(private val database: Database) : TeamEventStore {
    override suspend fun revalidate(actor: Principal) = database.query { requireCurrent(this, actor) }

    override suspend fun head(teamId: String): Long = database.query { headOf(teamId) }

    override suspend fun oldest(teamId: String): Long? = database.query {
        TeamEvents.select(TeamEvents.seq).where { TeamEvents.teamId eq teamId }.orderBy(TeamEvents.seq to SortOrder.ASC).limit(1).singleOrNull()?.get(TeamEvents.seq)
    }

    override suspend fun read(teamId: String, audience: TeamEventAudience, after: Long, types: Set<TeamEventType>?, limit: Int): TeamEventPage = database.query {
        // The head is read first: everything up to it is committed, and nothing above it is returned, so `through` is exact.
        val head = headOf(teamId)
        if (head <= after) return@query TeamEventPage(emptyList(), after, false)
        val visible = TeamEventType.entries.filter { (types == null || it in types) && audience.allows(it) }
        if (visible.isEmpty()) return@query TeamEventPage(emptyList(), head, false)
        var seen: Op<Boolean> = Op.FALSE
        for (subject in TeamEventSubject.entries) {
            val wires = visible.filter { it.subject == subject }.map { it.wire }
            if (wires.isEmpty()) continue
            val team = if (subject == TeamEventSubject.SESSION) audience.teamSessions else audience.teamRuns
            seen = seen or ((TeamEvents.type inList wires) and (if (team) Op.TRUE else TeamEvents.ownerId eq audience.memberId))
        }
        val rows = TeamEvents.selectAll().where { (TeamEvents.teamId eq teamId) and (TeamEvents.seq greater after) and (TeamEvents.seq lessEq head) and seen }
            .orderBy(TeamEvents.seq to SortOrder.ASC).limit(limit + 1).toList()
        val page = rows.take(limit)
        val events = page.mapNotNull { row ->
            val type = TeamEventType.parse(row[TeamEvents.type]) ?: return@mapNotNull null
            TeamEvent(row[TeamEvents.seq], type, row[TeamEvents.subjectId], row[TeamEvents.ownerId], row[TeamEvents.at], row[TeamEvents.data])
        }
        if (rows.size > limit) TeamEventPage(events, page.last()[TeamEvents.seq], true) else TeamEventPage(events, head, false)
    }

    override suspend fun purge(limit: Int): Int = database.query {
        val cutoff = nowMillis() - TeamEventService.RETENTION_MILLIS
        TeamEvents.deleteWhere(limit = limit) { at less cutoff }
    }

    private fun JdbcTransaction.headOf(teamId: String): Long =
        TeamEventCursors.select(TeamEventCursors.lastSeq).where { TeamEventCursors.teamId eq teamId }.singleOrNull()?.get(TeamEventCursors.lastSeq) ?: 0L
}

/**
 * Reports runs that went silent. Staleness is a clock fact with no write of its own, so this finds the runs whose last
 * heartbeat is older than the TTL, marks each once per silence in `stale_notified_at` and publishes `run.stale`.
 * Only the last day is scanned (an index range on the heartbeat): an older silent run is long past being news.
 */
class SqlRunStaleSweep(private val database: Database) {
    suspend fun sweep(limit: Int = 200): Int = database.query {
        val now = nowMillis()
        val newest = now - AgentRunService.HEARTBEAT_TTL_MILLIS
        val oldest = now - 24 * 3_600_000L
        val rows = AgentRuns.selectAll().where {
            AgentRuns.endedAt.isNull() and (AgentRuns.heartbeatAt less newest) and (AgentRuns.heartbeatAt greater oldest) and
                (AgentRuns.staleNotifiedAt.isNull() or (AgentRuns.staleNotifiedAt less AgentRuns.heartbeatAt))
        }.orderBy(AgentRuns.teamId to SortOrder.ASC).limit(limit).toList()
        var reported = 0
        for (row in rows) {
            // Conditional on the heartbeat we read: a run that beat meanwhile is not stale.
            val marked = AgentRuns.update({ (AgentRuns.id eq row[AgentRuns.id]) and AgentRuns.endedAt.isNull() and (AgentRuns.heartbeatAt eq row[AgentRuns.heartbeatAt]) }) { it[staleNotifiedAt] = now }
            if (marked != 1) continue
            publishTeamEvent(row[AgentRuns.teamId], TeamEventType.RUN_STALE, row[AgentRuns.id], row[AgentRuns.memberId], buildJsonObject {
                put("runId", row[AgentRuns.id]); put("agent", row[AgentRuns.agent]); put("workload", row[AgentRuns.workload]); put("lastHeartbeatAt", row[AgentRuns.heartbeatAt])
            }, now)
            reported++
        }
        reported
    }
}
