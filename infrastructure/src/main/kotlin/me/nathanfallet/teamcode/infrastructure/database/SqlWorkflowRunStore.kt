package me.nathanfallet.teamcode.infrastructure.database

import java.util.UUID
import kotlinx.serialization.json.*
import me.nathanfallet.teamcode.domain.cloud.CloudSessionState
import me.nathanfallet.teamcode.domain.cloud.DesiredState
import me.nathanfallet.teamcode.domain.team.*
import me.nathanfallet.teamcode.domain.workflows.*
import me.nathanfallet.teamcode.infrastructure.database.tables.*
import me.nathanfallet.teamcode.infrastructure.secrets.Passwords
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*

/**
 * Workflow runs in shared persistence (docs/workflows.md). Every change is one conditional update on the run's or the
 * visit's state, so of two replicas stepping the same run exactly one changes it; the engine's claim is a lease taken by a
 * conditional update. A run acts with a server-held token of its starter (never shown), minted with the root run and
 * removed when it ends.
 */
class SqlWorkflowRunStore(private val database: Database) : WorkflowRunStore {
    override suspend fun now(): Long = database.query { nowMillis() }
    override suspend fun revalidate(actor: Principal) = database.query { requireCurrent(this, actor) }

    override suspend fun workflow(teamId: String, id: String): Workflow? = database.query {
        Workflows.selectAll().where { (Workflows.id eq id) and (Workflows.teamId eq teamId) }.singleOrNull()?.let {
            Workflow(it[Workflows.id], it[Workflows.teamId], it[Workflows.version], WorkflowJson.definition(it[Workflows.definition]), it[Workflows.createdAt], it[Workflows.updatedAt], it[Workflows.updatedById])
        }
    }

    override suspend fun start(actor: Principal, run: WorkflowRun, first: NodeVisit, idempotencyKey: String?, limit: Int): RunChange = database.query {
        requireCurrent(this, actor)
        // The member's row serializes their starts, so two replicas cannot pass the cap together.
        Members.select(Members.id).where { Members.id eq actor.member.id }.forUpdate().single()
        if (idempotencyKey != null) {
            WorkflowRuns.selectAll().where { (WorkflowRuns.starterId eq actor.member.id) and (WorkflowRuns.idempotencyKey eq idempotencyKey) }.singleOrNull()
                ?.let { return@query RunChange(run(it), false) }
        }
        val unfinished = WorkflowRuns.select(WorkflowRuns.id).where { (WorkflowRuns.starterId eq actor.member.id) and (WorkflowRuns.status inList listOf(RunStatus.RUNNING.wire, RunStatus.WAITING.wire)) }
            .limit(limit).count()
        if (unfinished >= limit) throw TeamFailure("run_limit", 409, "At most $limit workflow runs at a time. Cancel one or wait for one to end.")
        val credential = UUID.randomUUID().toString()
        Tokens.insert {
            it[Tokens.id] = credential; it[memberId] = actor.member.id; it[tokenHash] = Passwords.digest("tcwr_" + Passwords.secret())
            it[device] = "workflow ${run.id.take(8)}"; it[deviceId] = null; it[createdAt] = run.createdAt; it[expiresAt] = run.createdAt + WorkflowEngine.MAX_AGE_MILLIS + 86_400_000L
            it[serviceScopes] = JsonArray(listOf(JsonPrimitive(ServiceScope.CLOUD_WRITE.wire))).toString()
        }
        insertRun(run.copy(credentialId = credential), idempotencyKey)
        insertVisit(first)
        audit(actor.teamId, actor.member.id, "workflow.run.started", run.id, null, actor.tokenId, details = run.workflowId)
        RunChange(run, true)
    }

    private fun JdbcTransaction.insertRun(run: WorkflowRun, idempotencyKey: String?) {
        WorkflowRuns.insert {
            it[id] = run.id; it[teamId] = run.teamId; it[workflowId] = run.workflowId; it[workflowName] = run.workflowName; it[workflowVersion] = run.workflowVersion
            it[definition] = WorkflowJson.definition(run.definition); it[starterId] = run.starterId; it[status] = run.status.wire; it[inputs] = WorkflowJson.strings(run.inputs)
            it[profileId] = run.profileId; it[sessionId] = run.sessionId; it[depth] = run.depth; it[parentRunId] = run.parentRunId; it[parentVisitId] = run.parentVisitId
            it[cancelRequested] = false; it[credentialId] = run.credentialId; it[WorkflowRuns.idempotencyKey] = idempotencyKey; it[leaseUntil] = 0
            it[createdAt] = run.createdAt; it[updatedAt] = run.updatedAt
        }
    }

    private fun JdbcTransaction.insertVisit(visit: NodeVisit) {
        WorkflowVisits.insert {
            it[id] = visit.id; it[runId] = visit.runId; it[seq] = visit.seq; it[nodeId] = visit.nodeId; it[kind] = visit.kind.wire; it[status] = visit.status.wire
            it[startedAt] = visit.startedAt
        }
    }

    override suspend fun find(teamId: String, id: String): WorkflowRun? = database.query {
        WorkflowRuns.selectAll().where { (WorkflowRuns.id eq id) and (WorkflowRuns.teamId eq teamId) }.singleOrNull()?.let(::run)
    }

    override suspend fun executionSession(tokenId: String): String? = database.query {
        CloudInferenceCredentials.select(CloudInferenceCredentials.sessionId).where { CloudInferenceCredentials.tokenId eq tokenId }.singleOrNull()?.get(CloudInferenceCredentials.sessionId)
    }

    override suspend fun runOfSession(teamId: String, sessionId: String): Pair<WorkflowRun, NodeVisit>? = database.query {
        // The visit is found on (session, status); the run is one primary-key lookup of the join, whose team and status filter the row.
        (WorkflowVisits innerJoin WorkflowRuns).selectAll().where {
            (WorkflowVisits.sessionId eq sessionId) and (WorkflowVisits.status eq VisitStatus.ACTIVE.wire) and (WorkflowVisits.kind eq NodeKind.AGENT.wire) and
                (WorkflowRuns.teamId eq teamId) and (WorkflowRuns.status inList ACTIVE)
        }.orderBy(WorkflowVisits.seq to SortOrder.DESC).limit(1).singleOrNull()?.let { run(it) to visit(it) }
    }

    override suspend fun sessionOwner(teamId: String, sessionId: String): String? = database.query {
        CloudSessions.select(CloudSessions.ownerId).where { (CloudSessions.id eq sessionId) and (CloudSessions.teamId eq teamId) }.singleOrNull()?.get(CloudSessions.ownerId)
    }

    override suspend fun visits(runId: String): List<NodeVisit> = database.query {
        WorkflowVisits.selectAll().where { WorkflowVisits.runId eq runId }.orderBy(WorkflowVisits.seq to SortOrder.ASC).limit(MAX_VISITS).map(::visit)
    }

    override suspend fun list(teamId: String, starterId: String?, workflowId: String?, status: RunStatus?, before: String?, limit: Int): WorkflowRunPage = database.query {
        val cursor = before?.let { id ->
            WorkflowRuns.select(WorkflowRuns.createdAt).where { (WorkflowRuns.id eq id) and (WorkflowRuns.teamId eq teamId) }.singleOrNull()?.get(WorkflowRuns.createdAt)
                ?: throw TeamFailure("invalid_cursor", 400, "Unknown run cursor.")
        }
        var condition: Op<Boolean> = WorkflowRuns.teamId eq teamId
        starterId?.let { condition = condition and (WorkflowRuns.starterId eq it) }
        workflowId?.let { condition = condition and (WorkflowRuns.workflowId eq it) }
        status?.let { condition = condition and (WorkflowRuns.status eq it.wire) }
        if (cursor != null) condition = condition and ((WorkflowRuns.createdAt less cursor) or ((WorkflowRuns.createdAt eq cursor) and (WorkflowRuns.id less before!!)))
        // The definition snapshot is not read for a list: a page shows the run, not its graph.
        val rows = WorkflowRuns.select(listColumns).where { condition }.orderBy(WorkflowRuns.createdAt to SortOrder.DESC, WorkflowRuns.id to SortOrder.DESC).limit(limit + 1).toList()
        val page = rows.take(limit).map { run(it, summary = true) }
        WorkflowRunPage(page, if (rows.size > limit) page.last().id else null)
    }

    override suspend fun requestCancel(teamId: String, id: String): Boolean = database.query {
        WorkflowRuns.update({ (WorkflowRuns.id eq id) and (WorkflowRuns.teamId eq teamId) and (WorkflowRuns.status inList ACTIVE) }) { it[cancelRequested] = true } > 0
    }

    override suspend fun starter(run: WorkflowRun): Principal? = database.query {
        val credential = run.credentialId ?: return@query null
        val now = nowMillis()
        (Tokens innerJoin Members).select(Tokens.id, Tokens.serviceScopes, Members.id, Members.username, Members.role, Members.isOwner)
            .where { (Tokens.id eq credential) and (Members.id eq run.starterId) and (Members.teamId eq run.teamId) and (Members.disabled eq false) and (Members.mustChangePassword eq false) and (Tokens.expiresAt greater now) }
            .singleOrNull()?.let { Principal(Member(it[Members.id], it[Members.username], readMemberRole(it), false, false), it[Tokens.id], run.teamId, readServiceScopes(it[Tokens.serviceScopes])) }
    }

    override suspend fun claim(limit: Int, leaseMillis: Long): List<RunWork> = database.query {
        val now = nowMillis()
        val candidates = WorkflowRuns.select(WorkflowRuns.id).where { (WorkflowRuns.status inList ACTIVE) and (WorkflowRuns.leaseUntil lessEq now) }
            .orderBy(WorkflowRuns.leaseUntil to SortOrder.ASC).limit(limit).map { it[WorkflowRuns.id] }
        val claimed = candidates.filter { id ->
            WorkflowRuns.update({ (WorkflowRuns.id eq id) and (WorkflowRuns.status inList ACTIVE) and (WorkflowRuns.leaseUntil lessEq now) }) { it[leaseUntil] = now + leaseMillis } == 1
        }
        if (claimed.isEmpty()) return@query emptyList()
        val runs = WorkflowRuns.selectAll().where { WorkflowRuns.id inList claimed }.map(::run)
        val visits = WorkflowVisits.selectAll().where { WorkflowVisits.runId inList claimed }.orderBy(WorkflowVisits.runId to SortOrder.ASC, WorkflowVisits.seq to SortOrder.ASC)
            .map(::visit).groupBy { it.runId }
        runs.map { RunWork(it, visits[it.id].orEmpty()) }
    }

    override suspend fun release(ids: Collection<String>) {
        if (ids.isNotEmpty()) database.query { WorkflowRuns.update({ WorkflowRuns.id inList ids }) { it[leaseUntil] = 0 } }
    }

    override suspend fun children(ids: Set<String>): Map<String, ChildState> = database.query {
        if (ids.isEmpty()) return@query emptyMap()
        WorkflowRuns.select(WorkflowRuns.id, WorkflowRuns.status, WorkflowRuns.output).where { WorkflowRuns.id inList ids }
            .associate { it[WorkflowRuns.id] to ChildState(RunStatus.parse(it[WorkflowRuns.status]) ?: RunStatus.FAILED, it[WorkflowRuns.output]) }
    }

    override suspend fun dispatched(visitId: String, sessionId: String, bindRun: Boolean, at: Long): Boolean = database.query {
        val visit = WorkflowVisits.select(WorkflowVisits.runId).where { WorkflowVisits.id eq visitId }.singleOrNull() ?: return@query false
        val changed = WorkflowVisits.update({ (WorkflowVisits.id eq visitId) and (WorkflowVisits.status eq VisitStatus.ACTIVE.wire) and WorkflowVisits.sessionId.isNull() }) {
            it[WorkflowVisits.sessionId] = sessionId; it[dispatchedAt] = at
        } > 0
        if (changed && bindRun) WorkflowRuns.update({ WorkflowRuns.id eq visit[WorkflowVisits.runId] }) { it[WorkflowRuns.sessionId] = sessionId; it[updatedAt] = at }
        changed
    }

    override suspend fun waiting(visitId: String, waitId: String): Boolean = database.query {
        val visit = WorkflowVisits.select(WorkflowVisits.runId).where { WorkflowVisits.id eq visitId }.singleOrNull() ?: return@query false
        val changed = WorkflowVisits.update({ (WorkflowVisits.id eq visitId) and (WorkflowVisits.status eq VisitStatus.ACTIVE.wire) and WorkflowVisits.waitId.isNull() }) { it[WorkflowVisits.waitId] = waitId } > 0
        if (changed) WorkflowRuns.update({ (WorkflowRuns.id eq visit[WorkflowVisits.runId]) and (WorkflowRuns.status eq RunStatus.RUNNING.wire) }) { it[status] = RunStatus.WAITING.wire; it[updatedAt] = nowMillis() }
        changed
    }

    override suspend fun startChild(visitId: String, child: WorkflowRun, first: NodeVisit): Boolean = database.query {
        val visit = WorkflowVisits.select(WorkflowVisits.runId).where { WorkflowVisits.id eq visitId }.singleOrNull() ?: return@query false
        val parent = WorkflowRuns.select(WorkflowRuns.credentialId, WorkflowRuns.status).where { WorkflowRuns.id eq visit[WorkflowVisits.runId] }.single()
        if (RunStatus.parse(parent[WorkflowRuns.status])?.terminal != false) return@query false
        // The visit row is the guard: only the pass that records the child on it creates the child.
        val recorded = WorkflowVisits.update({ (WorkflowVisits.id eq visitId) and (WorkflowVisits.status eq VisitStatus.ACTIVE.wire) and WorkflowVisits.childRunId.isNull() }) { it[childRunId] = child.id } > 0
        if (!recorded) return@query false
        insertRun(child.copy(credentialId = parent[WorkflowRuns.credentialId]), null)
        insertVisit(first)
        true
    }

    override suspend fun complete(visitId: String, status: VisitStatus, outcome: String?, output: String?, at: Long): Boolean = database.query {
        val visit = WorkflowVisits.select(WorkflowVisits.runId).where { WorkflowVisits.id eq visitId }.singleOrNull() ?: return@query false
        val changed = WorkflowVisits.update({ (WorkflowVisits.id eq visitId) and (WorkflowVisits.status eq VisitStatus.ACTIVE.wire) }) {
            it[WorkflowVisits.status] = status.wire; it[WorkflowVisits.outcome] = outcome; it[WorkflowVisits.output] = output; it[finishedAt] = at
        } > 0
        if (changed) WorkflowRuns.update({ (WorkflowRuns.id eq visit[WorkflowVisits.runId]) and (WorkflowRuns.status eq RunStatus.WAITING.wire) }) { it[WorkflowRuns.status] = RunStatus.RUNNING.wire; it[updatedAt] = at }
        changed
    }

    override suspend fun next(runId: String, visit: NodeVisit): Boolean = database.query {
        val run = WorkflowRuns.select(WorkflowRuns.status).where { WorkflowRuns.id eq runId }.singleOrNull() ?: return@query false
        if (RunStatus.parse(run[WorkflowRuns.status])?.terminal != false) return@query false
        // The unique (run, seq) is the guard: a second replica opening the same visit fails instead of duplicating it.
        if (!WorkflowVisits.select(WorkflowVisits.id).where { (WorkflowVisits.runId eq runId) and (WorkflowVisits.seq eq visit.seq) }.empty()) return@query false
        insertVisit(visit)
        WorkflowRuns.update({ WorkflowRuns.id eq runId }) { it[updatedAt] = visit.startedAt }
        true
    }

    override suspend fun finish(runId: String, status: RunStatus, failure: String?, output: String?, at: Long): Boolean = database.query {
        val changed = WorkflowRuns.update({ (WorkflowRuns.id eq runId) and (WorkflowRuns.status inList ACTIVE) }) {
            it[WorkflowRuns.status] = status.wire; it[WorkflowRuns.failure] = failure; it[WorkflowRuns.output] = output?.take(MAX_OUTPUT); it[finishedAt] = at; it[updatedAt] = at; it[leaseUntil] = 0
        } > 0
        if (changed) {
            val row = WorkflowRuns.select(WorkflowRuns.parentRunId, WorkflowRuns.credentialId, WorkflowRuns.teamId, WorkflowRuns.starterId).where { WorkflowRuns.id eq runId }.single()
            // The credential belongs to the root run: it goes with it.
            if (row[WorkflowRuns.parentRunId] == null) row[WorkflowRuns.credentialId]?.let { credential -> Tokens.deleteWhere { Tokens.id eq credential } }
            audit(row[WorkflowRuns.teamId], row[WorkflowRuns.starterId], "workflow.run.${status.wire}", runId, null, null, details = failure)
        }
        changed
    }

    private val listColumns = WorkflowRuns.columns - WorkflowRuns.definition

    private fun run(row: ResultRow, summary: Boolean = false) = WorkflowRun(
        row[WorkflowRuns.id], row[WorkflowRuns.teamId], row[WorkflowRuns.workflowId], row[WorkflowRuns.workflowName], row[WorkflowRuns.workflowVersion],
        if (summary) EMPTY else WorkflowJson.definition(row[WorkflowRuns.definition]), row[WorkflowRuns.starterId], RunStatus.parse(row[WorkflowRuns.status]) ?: RunStatus.FAILED,
        WorkflowJson.strings(row[WorkflowRuns.inputs]), row[WorkflowRuns.profileId], row[WorkflowRuns.sessionId], row[WorkflowRuns.depth], row[WorkflowRuns.parentRunId],
        row[WorkflowRuns.parentVisitId], row[WorkflowRuns.cancelRequested], row[WorkflowRuns.failure], row[WorkflowRuns.output], row[WorkflowRuns.createdAt],
        row[WorkflowRuns.updatedAt], row[WorkflowRuns.finishedAt], row[WorkflowRuns.credentialId],
    )

    private fun visit(row: ResultRow) = NodeVisit(
        row[WorkflowVisits.id], row[WorkflowVisits.runId], row[WorkflowVisits.seq], row[WorkflowVisits.nodeId], NodeKind.parse(row[WorkflowVisits.kind]) ?: NodeKind.AGENT,
        VisitStatus.parse(row[WorkflowVisits.status]) ?: VisitStatus.FAILED, row[WorkflowVisits.outcome], row[WorkflowVisits.output], row[WorkflowVisits.sessionId],
        row[WorkflowVisits.waitId], row[WorkflowVisits.childRunId], row[WorkflowVisits.dispatchedAt], row[WorkflowVisits.startedAt], row[WorkflowVisits.finishedAt],
    )

    private companion object {
        val ACTIVE = listOf(RunStatus.RUNNING.wire, RunStatus.WAITING.wire)
        const val MAX_VISITS = 1_000
        const val MAX_OUTPUT = 64_000
        /** A listed run carries no graph. */
        val EMPTY = WorkflowDefinition("", null, "", SessionMode.SAME, emptyList(), emptyList())
    }
}

/**
 * What agent nodes need of their sessions, in two or three reads for all of a pass's sessions: the state, and the latest
 * turn (when it started, and how it ended: null while it runs), read on the events' type index.
 */
class SqlWorkflowProgress(private val database: Database) {
    suspend fun progress(sessionIds: Set<String>): Map<String, SessionProgress> = database.query {
        if (sessionIds.isEmpty()) return@query emptyMap()
        val sessions = CloudSessions.select(CloudSessions.id, CloudSessions.state, CloudSessions.desiredState).where { CloudSessions.id inList sessionIds }.toList()
        val ids = sessions.map { it[CloudSessions.id] }
        val seq = CloudSessionEvents.seq.max()
        fun latest(below: Map<String, Long>?): Map<String, Long> {
            val condition: Op<Boolean> = if (below == null) CloudSessionEvents.sessionId inList ids
                else below.entries.map { (id, s) -> (CloudSessionEvents.sessionId eq id) and (CloudSessionEvents.seq less s) }.reduce { a, b -> a or b }
            return CloudSessionEvents.select(CloudSessionEvents.sessionId, seq).where { (CloudSessionEvents.type eq me.nathanfallet.teamcode.domain.cloud.CloudEventType.TURN.wire) and condition }
                .groupBy(CloudSessionEvents.sessionId).associate { it[CloudSessionEvents.sessionId] to it[seq]!! }
        }
        fun events(at: Map<String, Long>): Map<String, Pair<String?, Long>> {
            if (at.isEmpty()) return emptyMap()
            val condition = at.entries.map { (id, s) -> (CloudSessionEvents.sessionId eq id) and (CloudSessionEvents.seq eq s) }.reduce { a, b -> a or b }
            return CloudSessionEvents.select(CloudSessionEvents.sessionId, CloudSessionEvents.data, CloudSessionEvents.at).where { condition }.associate {
                it[CloudSessionEvents.sessionId] to (runCatching { Json.parseToJsonElement(it[CloudSessionEvents.data]).jsonObject["phase"]?.jsonPrimitive?.contentOrNull }.getOrNull() to it[CloudSessionEvents.at])
            }
        }
        val last = if (ids.isEmpty()) emptyMap() else latest(null)
        val lastEvents = events(last)
        // A turn that ended: the event before it tells when it began.
        val ended = lastEvents.filter { it.value.first in me.nathanfallet.teamcode.domain.cloud.CloudConversationRules.TURN_END_PHASES }.keys
        val before = if (ended.isEmpty()) emptyMap() else events(latest(last.filterKeys { it in ended }))
        sessions.associate { row ->
            val id = row[CloudSessions.id]
            val (phase, at) = lastEvents[id] ?: (null to null)
            val finished = phase in me.nathanfallet.teamcode.domain.cloud.CloudConversationRules.TURN_END_PHASES
            val started = if (!finished) at else before[id]?.second ?: at
            id to SessionProgress(CloudSessionState.parse(row[CloudSessions.state]), DesiredState.parse(row[CloudSessions.desiredState]), started, if (finished) phase else null)
        }
    }

}
