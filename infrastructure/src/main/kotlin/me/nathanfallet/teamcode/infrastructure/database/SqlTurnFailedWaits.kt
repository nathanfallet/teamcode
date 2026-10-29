package me.nathanfallet.teamcode.infrastructure.database

import java.util.UUID
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.nathanfallet.teamcode.domain.team.TeamEventType
import me.nathanfallet.teamcode.domain.waits.TurnFailedWait
import me.nathanfallet.teamcode.domain.waits.WaitKind
import me.nathanfallet.teamcode.domain.waits.WaitStatus
import me.nathanfallet.teamcode.infrastructure.database.tables.Waits
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*

/**
 * The `turn_failed` waits of a session, kept by the transcript ingest in the transaction that records the turn, under the
 * session\'s row lock: a failed turn opens one for the session\'s owner (replacing an older open one), and the next turn
 * that starts cancels it (docs/waits.md). Nothing here reads the transcript: the wait names the session and nothing else.
 */
internal object TurnFailedWaits {
    /** At most this many open waits per session, as for any wait (WaitRules.MAX_OPEN_PER_SESSION); past it a failure opens none. */
    private const val MAX_OPEN = 20

    /** Opens the wait for a turn that failed at [at]; the events to publish (the replaced wait\'s cancellation, then the new wait). */
    fun JdbcTransaction.opened(teamId: String, sessionId: String, owner: String, at: Long): List<PendingTeamEvent> {
        val events = cancelled(teamId, sessionId, owner, at).toMutableList()
        if (Waits.select(Waits.id).where { (Waits.sessionId eq sessionId) and (Waits.status eq WaitStatus.OPEN.wire) }.limit(MAX_OPEN).count() >= MAX_OPEN) return events
        val id = UUID.randomUUID().toString()
        Waits.insert {
            it[Waits.id] = id; it[Waits.teamId] = teamId; it[Waits.sessionId] = sessionId; it[kind] = WaitKind.TURN_FAILED.wire; it[resolver] = "human"
            it[status] = WaitStatus.OPEN.wire; it[assigneeId] = owner; it[createdById] = null; it[title] = TurnFailedWait.TITLE; it[body] = TurnFailedWait.BODY
            it[refs] = WaitJson.refs(TurnFailedWait.refs(sessionId)); it[fieldDefs] = WaitJson.fields(TurnFailedWait.fields); it[actions] = WaitJson.actions(TurnFailedWait.actions)
            it[createdAt] = at
        }
        events += event(TeamEventType.WAIT_CREATED, id, sessionId, owner, at)
        return events
    }

    /** Cancels the session\'s open `turn_failed` waits (another turn started); the events to publish. */
    fun JdbcTransaction.cancelled(teamId: String, sessionId: String, owner: String, at: Long): List<PendingTeamEvent> {
        val open = Waits.select(Waits.id).where {
            (Waits.sessionId eq sessionId) and (Waits.status eq WaitStatus.OPEN.wire) and (Waits.kind eq WaitKind.TURN_FAILED.wire) and (Waits.teamId eq teamId)
        }.limit(MAX_OPEN).map { it[Waits.id] }
        if (open.isEmpty()) return emptyList()
        Waits.update({ (Waits.id inList open) and (Waits.status eq WaitStatus.OPEN.wire) }) { it[status] = WaitStatus.CANCELLED.wire; it[resolvedAt] = at }
        return open.map { event(TeamEventType.WAIT_CANCELLED, it, sessionId, owner, at) }
    }

    /** As the wait store publishes them; the conversation store adds the session\'s kind. */
    private fun event(type: TeamEventType, waitId: String, sessionId: String, owner: String, at: Long) = PendingTeamEvent(type, waitId, owner, buildJsonObject {
        put("waitId", waitId); put("sessionId", sessionId); put("waitKind", WaitKind.TURN_FAILED.wire); put("assigneeId", owner)
    }, at)
}
