package me.nathanfallet.teamcode.infrastructure.database

import me.nathanfallet.teamcode.domain.routing.RequestAffinity
import me.nathanfallet.teamcode.domain.capacity.Workload
import me.nathanfallet.teamcode.domain.team.*
import me.nathanfallet.teamcode.infrastructure.database.tables.*
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*

/** Every run constraint is checked in the existing admission transaction, including queued retries. */
internal fun requireRunAdmission(actor: Principal, id: String, units: Long, now: Long, workload: Workload? = null, checkConcurrency: Boolean = true) {
    AgentRunService.identifier(id)
    val row = AgentRuns.selectAll().where { (AgentRuns.id eq id) and (AgentRuns.teamId eq actor.teamId) and (AgentRuns.memberId eq actor.member.id) }
        .singleOrNull() ?: throw TeamFailure("run_not_found", 404, "Run not found.")
    val maximum = row[AgentRuns.maxConcurrent]
    val active = if (checkConcurrency && maximum > 0) Reservations.select(Reservations.id).where {
            (Reservations.runId eq id) and (Reservations.state eq "ACTIVE") and (Reservations.expiresAt greater now)
        }.count().toInt() else 0
    CheckRunAdmissionUseCase().check(actor, RunAdmissionState(row[AgentRuns.tokenId], row[AgentRuns.endedAt] != null, row[AgentRuns.workload],
        RunPolicy(row[AgentRuns.budgetUnits], if (checkConcurrency) maximum else 0, row[AgentRuns.paused]), row[AgentRuns.chargedUnits], active), units, workload?.name?.lowercase())
}

/** Snapshot linkage plus exact reservation deltas keep run totals after detailed ledger retention. */
internal fun recordRunAdmission(id: String, units: Long, affinity: RequestAffinity?, provider: String, model: String, now: Long) {
    AgentRuns.update({ AgentRuns.id eq id }) {
        it[chargedUnits] = chargedUnits + units; it[attempts] = attempts + 1; it[lastRequestAt] = now
    }
    if (affinity == null) return
    val key = (RunConversations.runId eq id) and (RunConversations.conversationKey eq affinity.conversationKey)
    if (RunConversations.select(RunConversations.runId).where { key }.empty() &&
        RunConversations.select(RunConversations.runId).where { RunConversations.runId eq id }.count() >= AgentRunService.MAX_CONTEXTS) {
        AgentRuns.update({ AgentRuns.id eq id }) { it[contextsTruncated] = true }
        return
    }
    RunConversations.upsert {
        it[runId] = id; it[conversationKey] = affinity.conversationKey; it[sessionKey] = affinity.sessionKey
        it[requestedModel] = affinity.requestedModel; it[RunConversations.provider] = provider; it[RunConversations.model] = model; it[lastSeenAt] = now
    }
}
