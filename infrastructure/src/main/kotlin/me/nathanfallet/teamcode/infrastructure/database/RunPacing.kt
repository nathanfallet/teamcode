package me.nathanfallet.teamcode.infrastructure.database

import me.nathanfallet.teamcode.domain.team.*
import me.nathanfallet.teamcode.infrastructure.database.tables.*
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*

internal data class RunPacingContext(val scopes: Map<String, List<RunPacingPolicy>>, val truncated: Boolean)

/** Bounded recent-launch projection; hard run admission remains authoritative when metadata is incomplete. */
internal fun readRunPacingPolicies(teamId: String, memberId: String, now: Long): RunPacingContext {
    val rows = AgentRuns.select(AgentRuns.id, AgentRuns.budgetUnits, AgentRuns.maxConcurrent, AgentRuns.paused, AgentRuns.chargedUnits, AgentRuns.contextsTruncated).where {
        (AgentRuns.teamId eq teamId) and (AgentRuns.memberId eq memberId) and AgentRuns.endedAt.isNull() and
            (AgentRuns.lastRequestAt greater now - SqlSessionAffinity.ACTIVE_TTL) and exists(Tokens.select(Tokens.id).where {
                (Tokens.id eq AgentRuns.tokenId) and (Tokens.expiresAt greater now)
            })
    }.orderBy(AgentRuns.lastRequestAt to SortOrder.DESC).limit(1001).toList()
    val policies = rows.take(1000).associate { it[AgentRuns.id] to RunPacingPolicy(it[AgentRuns.id], RunPolicy(it[AgentRuns.budgetUnits], it[AgentRuns.maxConcurrent], it[AgentRuns.paused]), it[AgentRuns.chargedUnits]) }
    if (policies.isEmpty()) return RunPacingContext(emptyMap(), false)
    val bindings = RunConversations.select(RunConversations.runId, RunConversations.sessionKey).where { RunConversations.runId inList policies.keys }
        .withDistinct().limit(4097).toList()
    val scopes = bindings.take(4096).groupBy { it[RunConversations.sessionKey] }
        .mapValues { (_, contexts) -> contexts.map { policies.getValue(it[RunConversations.runId]) } }
    return RunPacingContext(scopes + ("" to policies.values.toList()), rows.size > 1000 || bindings.size > 4096 || rows.any { it[AgentRuns.contextsTruncated] })
}
