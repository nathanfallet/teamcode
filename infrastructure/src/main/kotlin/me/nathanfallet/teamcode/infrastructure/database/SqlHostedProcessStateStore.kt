package me.nathanfallet.teamcode.infrastructure.database

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.nathanfallet.teamcode.domain.mcp.HostedProcessChange
import me.nathanfallet.teamcode.domain.mcp.HostedProcessEvent
import me.nathanfallet.teamcode.domain.mcp.HostedProcessRecord
import me.nathanfallet.teamcode.domain.mcp.HostedProcessStateStore
import me.nathanfallet.teamcode.domain.team.Principal
import me.nathanfallet.teamcode.domain.team.TeamEventType
import me.nathanfallet.teamcode.infrastructure.database.tables.McpStdioStates
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*

/**
 * `mcp_stdio_states`: primary-key reads and compare-and-set writes, and the team's list by `mcp_stdio_state_team`.
 * A transition and its team event commit together, so an event exists exactly when its state was recorded.
 */
class SqlHostedProcessStateStore(private val database: Database) : HostedProcessStateStore {
    override suspend fun teamsWithStates(limit: Int): Set<String>? = database.query {
        // DISTINCT on the leading column of mcp_stdio_state_team: an index-only scan, one entry per team.
        val teams = McpStdioStates.select(McpStdioStates.teamId).withDistinct().limit(limit + 1).mapTo(HashSet()) { it[McpStdioStates.teamId] }
        teams.takeIf { it.size <= limit }
    }

    override suspend fun records(teamIds: Set<String>, limit: Int): List<HostedProcessRecord>? = database.query {
        if (teamIds.isEmpty()) return@query emptyList()
        val rows = McpStdioStates.selectAll().where { McpStdioStates.teamId inList teamIds }.limit(limit + 1).map(::record)
        rows.takeIf { it.size <= limit }
    }

    override suspend fun records(teamId: String, variantIds: Collection<String>, memberId: String): List<HostedProcessRecord> = database.query {
        if (variantIds.isEmpty()) return@query emptyList()
        // Primary-key ranges: (variant, '') for the shared process and (variant, member) for the member's own.
        McpStdioStates.selectAll().where {
            (McpStdioStates.variantId inList variantIds) and (McpStdioStates.memberId inList listOf("", memberId)) and (McpStdioStates.teamId eq teamId)
        }.limit(variantIds.size * 2).map(::record)
    }

    override suspend fun apply(changes: List<HostedProcessChange>, removals: List<HostedProcessRecord>): Int = database.query {
        for (removal in removals) McpStdioStates.deleteWhere {
            (variantId eq removal.variantId) and (memberId eq removal.memberId) and (changedAt eq removal.changedAt) and (state eq removal.state)
        }
        var applied = 0
        val events = mutableMapOf<String, MutableList<PendingTeamEvent>>()
        for (change in changes) {
            val next = change.next
            val previous = change.previous
            val written = if (previous == null) {
                McpStdioStates.insertIgnore {
                    it[variantId] = next.variantId; it[memberId] = next.memberId; it[teamId] = next.teamId; it[processKey] = next.processKey
                    it[state] = next.state; it[failureCode] = next.failure; it[changedAt] = next.changedAt
                }.insertedCount == 1
            } else {
                // Compare-and-set on what the sweep read: a row another replica changed meanwhile is left to it.
                McpStdioStates.update({
                    (McpStdioStates.variantId eq previous.variantId) and (McpStdioStates.memberId eq previous.memberId) and
                        (McpStdioStates.processKey eq previous.processKey) and (McpStdioStates.state eq previous.state) and (McpStdioStates.changedAt eq previous.changedAt)
                }) {
                    it[processKey] = next.processKey; it[state] = next.state; it[failureCode] = next.failure; it[changedAt] = next.changedAt
                } == 1
            }
            if (!written) continue
            applied++
            val event = change.event ?: continue
            val type = if (event == HostedProcessEvent.FAILED) TeamEventType.MCP_STDIO_FAILED else TeamEventType.MCP_STDIO_RECOVERED
            // RECOVERED names the failure it recovered from, which the new row no longer holds.
            val failure = if (event == HostedProcessEvent.FAILED) next.failure else previous?.failure
            events.getOrPut(next.teamId) { mutableListOf() } += PendingTeamEvent(type, next.variantId, next.memberId, buildJsonObject {
                put("server", change.server); put("variantId", next.variantId)
                if (next.memberId.isNotEmpty()) put("memberId", next.memberId)
                put("state", next.state); failure?.let { put("failure", it) }
            }, next.changedAt)
        }
        // Last, after every other write: the team's cursor lock (see publishTeamEvent).
        for ((team, pending) in events.toSortedMap()) publishTeamEvents(team, pending)
        applied
    }

    override suspend fun publishUnreachable(teamIds: Set<String>, reason: String) {
        // One short transaction per team: the cursor lock of one team is never held while another's is taken.
        for (team in teamIds.sorted()) database.query {
            publishTeamEvent(team, TeamEventType.MCP_UNREACHABLE, "mcp", "", buildJsonObject { put("reason", reason) }, nowMillis())
        }
    }

    override suspend fun recordRestart(actor: Principal, variantId: String, memberId: String?) = database.query {
        audit(actor.teamId, actor.member.id, "mcp.stdio.restarted", if (memberId == null) variantId else "$variantId/$memberId")
    }

    private fun record(row: ResultRow) = HostedProcessRecord(
        row[McpStdioStates.teamId], row[McpStdioStates.variantId], row[McpStdioStates.memberId], row[McpStdioStates.processKey],
        row[McpStdioStates.state], row[McpStdioStates.failureCode], row[McpStdioStates.changedAt],
    )
}
