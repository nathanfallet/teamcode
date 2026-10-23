package me.nathanfallet.teamcode.infrastructure.database

import kotlinx.serialization.json.*
import me.nathanfallet.teamcode.domain.team.*
import me.nathanfallet.teamcode.infrastructure.database.tables.*
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*

/** Shared launch lifecycle and bounded registry reads; no process-local registry or credentials in views. */
class AgentRunServiceImpl(private val database: Database) : AgentRunService {
    override suspend fun start(actor: Principal, id: String, agent: String, workload: String, labels: Map<String, String>): RunLease = database.query {
        writeActor(this, actor); AgentRunService.validateStart(id, agent, workload, labels)
        val existing = AgentRuns.selectAll().where { AgentRuns.id eq id }.singleOrNull()
        if (existing != null) {
            if (existing[AgentRuns.teamId] != actor.teamId || existing[AgentRuns.memberId] != actor.member.id || existing[AgentRuns.tokenId] != actor.tokenId ||
                existing[AgentRuns.agent] != agent || existing[AgentRuns.workload] != workload ||
                labelsFor(listOf(id))[id].orEmpty() != labels) throw TeamFailure("run_conflict", 409, "The run identifier is already assigned.")
            return@query lease(existing, nowMillis())
        }
        if (AgentRuns.select(AgentRuns.id).where { AgentRuns.teamId eq actor.teamId }.count() >= AgentRunService.MAX_TEAM_RUNS ||
            AgentRuns.select(AgentRuns.id).where { (AgentRuns.teamId eq actor.teamId) and (AgentRuns.memberId eq actor.member.id) }.count() >= AgentRunService.MAX_MEMBER_RUNS)
            throw TeamFailure("run_limit", 409, "The retained run registry limit has been reached.")
        val now = nowMillis()
        val device = Tokens.select(Tokens.deviceId).where { Tokens.id eq actor.tokenId }.single()[Tokens.deviceId]
        AgentRuns.insert {
            it[AgentRuns.id] = id; it[teamId] = actor.teamId; it[memberId] = actor.member.id; it[tokenId] = actor.tokenId; it[deviceId] = device
            it[AgentRuns.agent] = agent; it[AgentRuns.workload] = workload; it[startedAt] = now; it[heartbeatAt] = now
        }
        for ((key, value) in labels) AgentRunLabels.insert { it[runId] = id; it[AgentRunLabels.key] = key; it[AgentRunLabels.value] = value }
        audit(actor.teamId, actor.member.id, "run.started", id)
        publishTeamEvent(actor.teamId, TeamEventType.RUN_STARTED, id, actor.member.id, buildJsonObject { put("runId", id); put("agent", agent); put("workload", workload) }, now)
        RunLease(id, now, now, null, RunState.RUNNING)
    }

    override suspend fun heartbeat(actor: Principal, id: String): RunLease = database.query {
        writeActor(this, actor); val row = creator(actor, id)
        if (row[AgentRuns.endedAt] != null) throw TeamFailure("run_ended", 409, "This run has ended.")
        val now = nowMillis()
        // A state reported before a silence is not carried over it (it would be a guess); one reported during it is current.
        val silent = now - row[AgentRuns.heartbeatAt] > AgentRunService.HEARTBEAT_TTL_MILLIS
        AgentRuns.update({ AgentRuns.id eq id }) { it[heartbeatAt] = now; if (silent && (row[AgentRuns.activityAt] ?: 0) <= row[AgentRuns.heartbeatAt]) { it[activity] = null; it[activityAt] = null } }
        RunLease(id, row[AgentRuns.startedAt], now, null, RunState.RUNNING)
    }

    /** Reads and writes under the team lock that [writeActor] takes, like `end` and `heartbeat`: replicas apply reports one at a time. */
    override suspend fun activity(actor: Principal, id: String, activity: RunActivity): RunActivityChange = database.query {
        writeActor(this, actor); val row = creator(actor, id)
        if (row[AgentRuns.endedAt] != null) throw TeamFailure("run_ended", 409, "This run has ended.")
        val previous = row[AgentRuns.activityAt]
        val now = nowMillis()
        // The same state writes nothing, except during a silence: the heartbeat that ends it would clear a report older than the silence.
        val silent = now - row[AgentRuns.heartbeatAt] > AgentRunService.HEARTBEAT_TTL_MILLIS
        if (row[AgentRuns.activity] == activity.wire && previous != null && !silent) return@query RunActivityChange(id, activity, previous)
        if (AgentRuns.update({ (AgentRuns.id eq id) and AgentRuns.endedAt.isNull() }) { it[AgentRuns.activity] = activity.wire; it[activityAt] = now } == 0)
            throw TeamFailure("run_ended", 409, "This run has ended.")
        RunActivityChange(id, activity, now)
    }

    override suspend fun end(actor: Principal, id: String, exitCode: Int?): RunLease = database.query {
        writeActor(this, actor); val row = creator(actor, id)
        val now = nowMillis()
        if (row[AgentRuns.endedAt] == null) {
            AgentRuns.update({ AgentRuns.id eq id }) { it[endedAt] = now; it[AgentRuns.exitCode] = exitCode }
            audit(actor.teamId, actor.member.id, "run.ended", id)
            publishTeamEvent(actor.teamId, TeamEventType.RUN_ENDED, id, row[AgentRuns.memberId], buildJsonObject {
                put("runId", id); put("agent", row[AgentRuns.agent]); put("workload", row[AgentRuns.workload]); put("exitCode", exitCode)
            }, now)
        }
        RunLease(id, row[AgentRuns.startedAt], row[AgentRuns.heartbeatAt], row[AgentRuns.endedAt] ?: now, RunState.ENDED)
    }

    override suspend fun list(actor: Principal, memberId: String?, all: Boolean, beforeId: String?, labels: Map<String, String>): RunPage = database.query {
        readActor(this, actor); LabelRules.validateFilter(labels)
        if (all && memberId != null) throw TeamFailure("invalid_run_filter", 400, "Choose one member or all members.")
        val selected = memberId ?: actor.member.id
        memberId?.let(AgentRunService::identifier)
        if (all || selected != actor.member.id) actor.requireTeamRead()
        var scope = (AgentRuns.teamId eq actor.teamId) and (if (all) Op.TRUE else AgentRuns.memberId eq selected)
        for ((key, value) in labels) scope = scope and (AgentRuns.id inSubQuery AgentRunLabels.select(AgentRunLabels.runId)
            .where { (AgentRunLabels.key eq key) and (AgentRunLabels.value eq value) })
        val cursor = beforeId?.let { id ->
            AgentRunService.identifier(id)
            AgentRuns.select(AgentRuns.startedAt, AgentRuns.id).where { scope and (AgentRuns.id eq id) }.singleOrNull()
                ?: throw TeamFailure("invalid_run_cursor", 400, "The run cursor is no longer accessible. Refresh the list.")
        }
        val page = AgentRuns.selectAll().where { scope and (cursor?.let {
            (AgentRuns.startedAt less it[AgentRuns.startedAt]) or ((AgentRuns.startedAt eq it[AgentRuns.startedAt]) and (AgentRuns.id less it[AgentRuns.id]))
        } ?: Op.TRUE) }.orderBy(AgentRuns.startedAt to SortOrder.DESC, AgentRuns.id to SortOrder.DESC).limit(AgentRunService.PAGE_SIZE + 1).toList()
        RunPage(views(page.take(AgentRunService.PAGE_SIZE), nowMillis()), page.takeIf { it.size > AgentRunService.PAGE_SIZE }?.get(AgentRunService.PAGE_SIZE - 1)?.get(AgentRuns.id))
    }

    override suspend fun get(actor: Principal, id: String): RunDetail = database.query {
        readActor(this, actor); val row = accessible(actor, id); val now = nowMillis()
        val run = views(listOf(row), now).single()
        val includeAdvice = run.state !in setOf(RunState.ENDED, RunState.REVOKED)
        val contexts = RunConversations.selectAll().where { RunConversations.runId eq id }
            .orderBy(RunConversations.lastSeenAt to SortOrder.DESC).limit(AgentRunService.MAX_CONTEXTS).toList()
        val sessions = contexts.map { it[RunConversations.sessionKey] }.distinct()
        val member = row[AgentRuns.memberId]
        val advice = if (sessions.isEmpty() || !includeAdvice) emptyMap() else SessionDirectives.selectAll().where {
            (SessionDirectives.memberId eq member) and (SessionDirectives.scopeKey inList sessions) and (SessionDirectives.expiresAt greater now)
        }.associateBy { it[SessionDirectives.scopeKey] }
        val deliveries = if (sessions.isEmpty() || !includeAdvice) emptyMap() else DirectiveDeliveries.select(
            DirectiveDeliveries.sessionKey, DirectiveDeliveries.stage, DirectiveDeliveries.directiveId, DirectiveDeliveries.emittedAt
        ).where { (DirectiveDeliveries.memberId eq member) and (DirectiveDeliveries.sessionKey inList sessions) }
            .associateBy { it[DirectiveDeliveries.sessionKey] to it[DirectiveDeliveries.stage] }
        RunDetail(run, contexts.map { context ->
            val session = context[RunConversations.sessionKey]; val directive = advice[session]
            fun delivered(stage: String) = deliveries[session to stage]?.takeIf { it[DirectiveDeliveries.directiveId] == directive?.get(SessionDirectives.id) }?.get(DirectiveDeliveries.emittedAt)
            RunConversation(session, context[RunConversations.conversationKey], context[RunConversations.requestedModel], context[RunConversations.provider], context[RunConversations.model],
                context[RunConversations.lastSeenAt], directive?.get(SessionDirectives.pace), directive?.get(SessionDirectives.id), delivered("RECEIVED_BY_CLI"), delivered("EMITTED_TO_HOOK"))
        }, readPacingSettings(actor.teamId).observationOnly)
    }

    override suspend fun configure(actor: Principal, id: String, policy: RunPolicy): AgentRun = database.query {
        actor.requireAdmin(); lockTeam(actor.teamId); requireCurrent(this, actor); AgentRunService.validate(policy)
        val row = accessible(actor, id)
        if (row[AgentRuns.endedAt] != null) throw TeamFailure("run_ended", 409, "This run has ended.")
        AgentRuns.update({ AgentRuns.id eq id }) { it[budgetUnits] = policy.budgetUnits; it[maxConcurrent] = policy.maxConcurrent; it[paused] = policy.paused }
        audit(actor.teamId, actor.member.id, "run.policy_updated", id)
        viewOne(actor.teamId, id, nowMillis())
    }

    private fun writeActor(transaction: JdbcTransaction, actor: Principal) { actor.requireScope(ServiceScope.INFERENCE); transaction.lockTeam(actor.teamId); requireCurrent(transaction, actor) }
    private fun readActor(transaction: JdbcTransaction, actor: Principal) { actor.requireUsageRead(); transaction.lockTeam(actor.teamId); requireCurrent(transaction, actor) }
    private fun accessible(actor: Principal, id: String): ResultRow {
        AgentRunService.identifier(id)
        val row = AgentRuns.selectAll().where { (AgentRuns.id eq id) and (AgentRuns.teamId eq actor.teamId) }.singleOrNull() ?: throw missing()
        if (row[AgentRuns.memberId] != actor.member.id) actor.requireTeamRead()
        return row
    }
    private fun creator(actor: Principal, id: String): ResultRow {
        val row = accessible(actor, id)
        if (row[AgentRuns.memberId] != actor.member.id || row[AgentRuns.tokenId] != actor.tokenId)
            throw TeamFailure("run_credential_changed", 403, "Use the credential that created this launch.")
        return row
    }
    private fun viewOne(teamId: String, id: String, now: Long) = views(listOf(AgentRuns.selectAll().where {
        (AgentRuns.id eq id) and (AgentRuns.teamId eq teamId)
    }.single()), now).single()
    private fun views(rows: List<ResultRow>, now: Long): List<AgentRun> {
        if (rows.isEmpty()) return emptyList()
        val members = Members.select(Members.id, Members.username, Members.disabled, Members.mustChangePassword)
            .where { Members.id inList rows.map { it[AgentRuns.memberId] }.distinct() }.associateBy { it[Members.id] }
        val validTokens = Tokens.select(Tokens.id).where { (Tokens.id inList rows.map { it[AgentRuns.tokenId] }.distinct()) and (Tokens.expiresAt greater now) }.map { it[Tokens.id] }.toSet()
        val count = Reservations.id.count()
        val active = Reservations.select(Reservations.runId, count).where {
            (Reservations.runId inList rows.map { it[AgentRuns.id] }) and (Reservations.state eq "ACTIVE") and (Reservations.expiresAt greater now)
        }.groupBy(Reservations.runId).associate { it[Reservations.runId] to it[count].toInt() }
        val waiterCount = AdmissionWaiters.id.count()
        val queued = AdmissionWaiters.select(AdmissionWaiters.runId, waiterCount).where {
            (AdmissionWaiters.runId inList rows.map { it[AgentRuns.id] }) and (AdmissionWaiters.expiresAt greater now)
        }.groupBy(AdmissionWaiters.runId).associate { it[AdmissionWaiters.runId] to it[waiterCount].toInt() }
        val labels = labelsFor(rows.map { it[AgentRuns.id] })
        return rows.map { row -> with(AgentRuns) {
            val member = members.getValue(row[memberId])
            val state = RunStateUseCase().state(row[endedAt], row[heartbeatAt], now, row[tokenId] in validTokens && !member[Members.disabled] && !member[Members.mustChangePassword])
            AgentRun(row[id], row[memberId], member[Members.username], row[agent], row[workload], row[deviceId], row[startedAt], row[heartbeatAt], row[lastRequestAt], row[endedAt], row[exitCode],
                state, RunPolicy(row[budgetUnits], row[maxConcurrent], row[paused]), row[chargedUnits], row[attempts], row[completed], active[row[id]] ?: 0, row[contextsTruncated], queued[row[id]] ?: 0, labels[row[id]].orEmpty(),
                // Only a launch with a live wrapper has a current state: after STALE or the end it would be a guess.
                row[activity]?.takeIf { state == RunState.RUNNING }?.let { wire -> RunActivity.entries.firstOrNull { it.wire == wire } },
                row[activityAt]?.takeIf { state == RunState.RUNNING && row[activity] != null })
        } }
    }
    private fun labelsFor(ids: List<String>): Map<String, Map<String, String>> = if (ids.isEmpty()) emptyMap() else
        AgentRunLabels.selectAll().where { AgentRunLabels.runId inList ids }.groupBy({ it[AgentRunLabels.runId] }) { it[AgentRunLabels.key] to it[AgentRunLabels.value] }
            .mapValues { it.value.toMap() }
    private fun missing() = TeamFailure("run_not_found", 404, "Run not found.")
    private fun lease(row: ResultRow, now: Long) = RunLease(row[AgentRuns.id], row[AgentRuns.startedAt], row[AgentRuns.heartbeatAt], row[AgentRuns.endedAt],
        RunStateUseCase().state(row[AgentRuns.endedAt], row[AgentRuns.heartbeatAt], now, true))
}
