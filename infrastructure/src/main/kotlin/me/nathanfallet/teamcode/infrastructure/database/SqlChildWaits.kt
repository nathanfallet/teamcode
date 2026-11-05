package me.nathanfallet.teamcode.infrastructure.database

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.nathanfallet.teamcode.domain.cloud.CloudSession
import me.nathanfallet.teamcode.domain.cloud.CloudSessionState
import me.nathanfallet.teamcode.domain.cloud.DesiredState
import me.nathanfallet.teamcode.domain.team.TeamEventType
import me.nathanfallet.teamcode.domain.waits.WaitKind
import me.nathanfallet.teamcode.domain.waits.WaitService
import me.nathanfallet.teamcode.domain.waits.WaitStatus
import me.nathanfallet.teamcode.infrastructure.database.tables.Waits
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*

/**
 * The `child` waits of orchestration (docs/orchestration.md), resolved in the transaction that records what the child did, under the
 * child's row lock: the transcript ingest when a turn ends or a permission request opens, the session store when the child is paused
 * or ends. Only a session with a parent pays for it, one range read on `wait_child (child_session_id, status)` that finds nothing in
 * the usual case. The wake message is owed (`wake_pending`) and queued by the waits worker's existing pass, so nothing here calls out.
 */
internal object ChildWaits {
    /** A child has one parent, which holds at most 20 open waits: this bounds the rows a resolution touches. */
    private const val MAX_OPEN = 20

    /**
     * Closes the open waits that follow [childId] with [action] and [values], owing each its wake message when [wake]. Returns the
     * feed events to publish last in the caller's transaction (after its other writes, for the team cursor lock order).
     */
    fun JdbcTransaction.resolve(childId: String, action: String, values: Map<String, JsonElement>, at: Long, wake: Boolean = true): List<PendingTeamEvent> {
        val open = Waits.select(Waits.id, Waits.teamId, Waits.sessionId, Waits.assigneeId).where {
            (Waits.childSessionId eq childId) and (Waits.status eq WaitStatus.OPEN.wire)
        }.limit(MAX_OPEN).toList()
        if (open.isEmpty()) return emptyList()
        val events = mutableListOf<PendingTeamEvent>()
        for (row in open) {
            val id = row[Waits.id]
            // One conditional update per wait: of a turn end and a timeout, or two replicas, exactly one changes it.
            val changed = Waits.update({ (Waits.id eq id) and (Waits.status eq WaitStatus.OPEN.wire) }) {
                it[status] = WaitStatus.RESOLVED.wire; it[answerAction] = action; it[answerValues] = JsonObject(values).toString()
                it[answerAuthorId] = null; it[resolvedAt] = at; it[wakePending] = wake
            } > 0
            if (!changed) continue
            audit(row[Waits.teamId], row[Waits.assigneeId], "wait.$action", id, details = childId)
            events += PendingTeamEvent(TeamEventType.WAIT_RESOLVED, id, row[Waits.assigneeId], buildJsonObject {
                put("waitId", id); put("sessionId", row[Waits.sessionId]); put("waitKind", WaitKind.TIMED.wire); put("assigneeId", row[Waits.assigneeId])
                put("kind", "cloud"); put("action", action)
            }, at)
        }
        return events
    }

    /** What a write of the child's row means for the waits that follow it: ended, or paused; null for anything else. */
    fun change(expected: CloudSession, next: CloudSession): Pair<String, Map<String, JsonElement>>? {
        if (next.parentSessionId == null) return null
        val ended = (next.state.terminal && !expected.state.terminal) || (next.desiredState == DesiredState.TERMINATED && expected.desiredState != DesiredState.TERMINATED)
        val reason = next.failure?.code ?: next.terminatedReason
        return when {
            ended -> WaitService.CHILD_ENDED to buildMap {
                put("state", JsonPrimitive(if (next.state.terminal) next.state.wire else "terminating")); reason?.let { put("reason", JsonPrimitive(it.take(64))) }
            }
            next.state == CloudSessionState.PAUSED && expected.state != CloudSessionState.PAUSED -> WaitService.CHILD_PAUSED to buildMap {
                put("state", JsonPrimitive(next.state.wire)); next.failure?.code?.let { put("reason", JsonPrimitive(it.take(64))) }
            }
            else -> null
        }
    }
}
