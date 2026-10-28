package me.nathanfallet.teamcode.infrastructure.database

import java.util.UUID
import kotlinx.serialization.json.*
import me.nathanfallet.teamcode.domain.automations.*
import me.nathanfallet.teamcode.domain.cloud.CloudConversationRules
import me.nathanfallet.teamcode.domain.cloud.CloudConversationService
import me.nathanfallet.teamcode.domain.cloud.CloudEventType
import me.nathanfallet.teamcode.domain.cloud.CloudInputKind
import me.nathanfallet.teamcode.domain.cloud.CloudInputStatus
import me.nathanfallet.teamcode.domain.cloud.CloudSessionState
import me.nathanfallet.teamcode.domain.cloud.DesiredState
import me.nathanfallet.teamcode.domain.team.*
import me.nathanfallet.teamcode.domain.automations.RunPage
import me.nathanfallet.teamcode.infrastructure.database.tables.*
import me.nathanfallet.teamcode.infrastructure.secrets.Passwords
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*

/**
 * Automations and their runs in shared persistence. Recording a run locks its automation's row, so the overlap policy,
 * the minute rate and the daily budget hold across replicas; every worker step is a conditional update.
 */
class SqlAutomationStore(private val database: Database) : AutomationStore {
    override suspend fun now(): Long = database.query { nowMillis() }
    override suspend fun revalidate(actor: Principal) = database.query { requireCurrent(this, actor) }
    override suspend fun member(teamId: String, memberId: String): Member? = database.query {
        Members.selectAll().where { (Members.id eq memberId) and (Members.teamId eq teamId) }.singleOrNull()
            ?.let { Member(it[Members.id], it[Members.username], readMemberRole(it), it[Members.disabled], it[Members.mustChangePassword]) }
    }

    override suspend fun create(actor: Principal, automation: Automation, sealedSecret: String?, limit: Int): Automation = database.query {
        requireCurrent(this, actor)
        // The owner's row serializes their creations, so two at once cannot pass the cap together.
        Members.select(Members.id).where { Members.id eq automation.ownerId }.forUpdate().single()
        if (Automations.select(Automations.id).where { (Automations.teamId eq automation.teamId) and (Automations.ownerId eq automation.ownerId) }.count() >= limit)
            throw TeamFailure("automation_limit", 409, "A member owns at most $limit automations. Delete one first.")
        val now = nowMillis()
        val credential = mintCredential(automation.ownerId, automation.id, now)
        Automations.insert {
            it[id] = automation.id; it[teamId] = automation.teamId; it[ownerId] = automation.ownerId
            writeDefinition(it, automation.definition)
            it[enabled] = false; it[disabledReason] = automation.disabledReason; it[disabledBy] = null; it[version] = 1; it[nextRunAt] = null
            it[consecutiveFailures] = 0; it[secret] = sealedSecret; it[previousSecret] = null; it[previousSecretUntil] = null; it[secretRotatedAt] = now
            it[credentialId] = credential; it[lastRunId] = null; it[createdAt] = now; it[updatedAt] = now
        }
        audit(actor.teamId, actor.member.id, "automation.created", automation.id, null, actor.tokenId)
        readAutomation(automation.teamId, automation.id)!!
    }

    override suspend fun update(actor: Principal, id: String, expectedVersion: Int, definition: AutomationDefinition, nextRunAt: Long?): Automation = database.query {
        requireCurrent(this, actor)
        val row = locked(actor.teamId, id) ?: throw missing()
        if (row[Automations.version] != expectedVersion) throw TeamFailure("stale_version", 409, "The automation changed. Reload it and try again.")
        Automations.update({ Automations.id eq id }) {
            writeDefinition(it, definition); it[version] = expectedVersion + 1; it[Automations.nextRunAt] = nextRunAt; it[updatedAt] = nowMillis()
        }
        audit(actor.teamId, actor.member.id, "automation.updated", id, null, actor.tokenId)
        readAutomation(actor.teamId, id)!!
    }

    override suspend fun setEnabled(actor: Principal, id: String, enabled: Boolean, nextRunAt: Long?): Automation = database.query {
        requireCurrent(this, actor)
        val row = locked(actor.teamId, id) ?: throw missing()
        val now = nowMillis()
        // Enabling is the owner's consent again: a credential their revocations deleted is replaced.
        val existing = row[Automations.credentialId]?.takeIf { !Tokens.select(Tokens.id).where { Tokens.id eq it }.empty() }
        val credential = existing ?: if (enabled) mintCredential(row[Automations.ownerId], id, now) else null
        Automations.update({ Automations.id eq id }) {
            it[Automations.enabled] = enabled; it[Automations.nextRunAt] = if (enabled) nextRunAt else null; it[updatedAt] = now
            it[disabledReason] = if (enabled) null else AutomationService.DISABLED; it[disabledBy] = if (enabled) null else actor.member.id
            it[credentialId] = credential
        }
        audit(actor.teamId, actor.member.id, if (enabled) "automation.enabled" else "automation.disabled", id, null, actor.tokenId,
            related = row[Automations.ownerId].takeIf { it != actor.member.id })
        readAutomation(actor.teamId, id)!!
    }

    override suspend fun rotateSecret(actor: Principal, id: String, sealedSecret: String, previousValidUntil: Long): Automation = database.query {
        requireCurrent(this, actor)
        val row = locked(actor.teamId, id) ?: throw missing()
        val now = nowMillis()
        Automations.update({ Automations.id eq id }) {
            it[previousSecret] = row[Automations.secret]; it[previousSecretUntil] = previousValidUntil; it[secret] = sealedSecret
            it[secretRotatedAt] = now; it[updatedAt] = now
        }
        audit(actor.teamId, actor.member.id, "automation.secret_rotated", id, null, actor.tokenId)
        readAutomation(actor.teamId, id)!!
    }

    override suspend fun delete(actor: Principal, id: String) = database.query {
        requireCurrent(this, actor)
        val row = locked(actor.teamId, id) ?: throw missing()
        // The runs go with it (cascade); their sessions stay, labelled, like any of the owner's sessions.
        Automations.deleteWhere { Automations.id eq id }
        row[Automations.credentialId]?.let { credential -> Tokens.deleteWhere { Tokens.id eq credential } }
        audit(actor.teamId, actor.member.id, "automation.deleted", id, null, actor.tokenId, related = row[Automations.ownerId].takeIf { it != actor.member.id })
        Unit
    }

    override suspend fun find(teamId: String, id: String): Automation? = database.query { readAutomation(teamId, id) }

    override suspend fun list(teamId: String, owner: String?, before: String?, limit: Int): AutomationPage = database.query {
        val cursor = before?.let { id ->
            Automations.select(Automations.createdAt).where { (Automations.id eq id) and (Automations.teamId eq teamId) }.singleOrNull()?.get(Automations.createdAt)
                ?: throw TeamFailure("invalid_cursor", 400, "Unknown automation cursor.")
        }
        var condition: Op<Boolean> = Automations.teamId eq teamId
        if (owner != null) condition = condition and (Automations.ownerId eq owner)
        if (cursor != null) condition = condition and ((Automations.createdAt less cursor) or ((Automations.createdAt eq cursor) and (Automations.id less before!!)))
        val rows = withOwnerAndLastRun.selectAll().where { condition }
            .orderBy(Automations.createdAt to SortOrder.DESC, Automations.id to SortOrder.DESC).limit(limit + 1).toList()
        val page = rows.take(limit).map(::automation)
        AutomationPage(page, if (rows.size > limit) page.last().id else null)
    }

    override suspend fun count(teamId: String, owner: String): Int = database.query {
        Automations.select(Automations.id).where { (Automations.teamId eq teamId) and (Automations.ownerId eq owner) }.count().toInt()
    }

    override suspend fun forTrigger(id: String): Pair<Automation, SealedSecrets>? = database.query {
        withOwnerAndLastRun.selectAll().where { Automations.id eq id }.singleOrNull()?.let {
            automation(it) to SealedSecrets(it[Automations.secret], it[Automations.previousSecret], it[Automations.previousSecretUntil])
        }
    }

    override suspend fun runs(teamId: String, automationId: String, query: RunQuery): RunPage = database.query {
        val cursor = query.before?.let { id ->
            AutomationRuns.select(AutomationRuns.queuedAt).where { (AutomationRuns.id eq id) and (AutomationRuns.automationId eq automationId) }.singleOrNull()?.get(AutomationRuns.queuedAt)
                ?: throw TeamFailure("invalid_cursor", 400, "Unknown run cursor.")
        }
        var condition: Op<Boolean> = (AutomationRuns.automationId eq automationId) and (AutomationRuns.teamId eq teamId)
        if (query.sources.isNotEmpty()) condition = condition and (AutomationRuns.runSource inList query.sources.map { it.wire })
        if (query.statuses.isNotEmpty()) condition = condition and (AutomationRuns.status inList query.statuses.map { it.wire })
        if (cursor != null) condition = condition and ((AutomationRuns.queuedAt less cursor) or ((AutomationRuns.queuedAt eq cursor) and (AutomationRuns.id less query.before!!)))
        val rows = AutomationRuns.selectAll().where { condition }
            .orderBy(AutomationRuns.queuedAt to SortOrder.DESC, AutomationRuns.id to SortOrder.DESC).limit(query.limit + 1).toList()
        val page = rows.take(query.limit).map(::run)
        RunPage(page, if (rows.size > query.limit) page.last().id else null)
    }

    override suspend fun run(teamId: String, automationId: String, runId: String): AutomationRun? = database.query {
        AutomationRuns.selectAll().where { (AutomationRuns.id eq runId) and (AutomationRuns.automationId eq automationId) and (AutomationRuns.teamId eq teamId) }.singleOrNull()?.let(::run)
    }

    override suspend fun enqueue(actor: Principal?, automationId: String, run: NewRun): RunOutcome = database.query {
        if (actor != null) requireCurrent(this, actor)
        val row = Automations.selectAll().where { Automations.id eq automationId }.forUpdate().singleOrNull() ?: throw missing()
        if (actor != null && row[Automations.teamId] != actor.teamId) throw missing()
        record(row, run, actor)
    }

    /** Records [run] for the automation of [row], whose row lock the caller holds. */
    private fun JdbcTransaction.record(row: ResultRow, run: NewRun, actor: Principal?): RunOutcome {
        val automationId = row[Automations.id]
        AutomationRuns.selectAll().where { (AutomationRuns.automationId eq automationId) and (AutomationRuns.deliveryKey eq run.deliveryKey) }.singleOrNull()?.let { existing ->
            if (run.requestDigest != null && existing[AutomationRuns.requestDigest] != null && existing[AutomationRuns.requestDigest] != run.requestDigest)
                throw TeamFailure("idempotency_conflict", 422, "This Idempotency-Key was already used with a different payload.")
            return RunOutcome.Replayed(run(existing))
        }
        val now = nowMillis()
        if (run.source == RunSource.WEBHOOK &&
            recent(automationId, now - 60_000).andWhere { AutomationRuns.runSource eq RunSource.WEBHOOK.wire }.limit(AutomationRules.WEBHOOK_PER_MINUTE).count() >= AutomationRules.WEBHOOK_PER_MINUTE)
            throw TeamFailure("automation_rate_limit", 429, "This automation receives at most ${AutomationRules.WEBHOOK_PER_MINUTE} requests a minute.", retryAfterSeconds = 60)
        val overlap = OverlapPolicy.parse(row[Automations.overlap]) ?: OverlapPolicy.SKIP
        val maxRunsPerDay = row[Automations.maxRunsPerDay]
        val status = when {
            run.status != RunStatus.QUEUED -> run.status
            overlap == OverlapPolicy.SKIP && activeRuns(automationId, RunStatus.ACTIVE) > 0 -> RunStatus.SKIPPED_OVERLAP
            overlap == OverlapPolicy.QUEUE && activeRuns(automationId, setOf(RunStatus.QUEUED)) > 0 -> RunStatus.SKIPPED_OVERLAP
            run.source != RunSource.TEST && budgetUsed(automationId, now, maxRunsPerDay) -> RunStatus.SKIPPED_BUDGET
            else -> RunStatus.QUEUED
        }
        val reason = if (status == run.status) run.reason else status.wire.removePrefix("skipped_")
        AutomationRuns.insert {
            it[id] = run.id; it[AutomationRuns.automationId] = automationId; it[teamId] = row[Automations.teamId]; it[ownerId] = row[Automations.ownerId]
            it[automationVersion] = row[Automations.version]; it[runSource] = run.source.wire; it[deliveryKey] = run.deliveryKey; it[AutomationRuns.status] = status.wire
            it[AutomationRuns.reason] = reason; it[prompt] = run.prompt.takeIf { status == RunStatus.QUEUED }; it[variables] = encodeExtracted(run.variables)
            it[variablesDigest] = run.variablesDigest; it[requestDigest] = run.requestDigest; it[sessionId] = null; it[profileVersion] = null
            it[scheduledFor] = run.scheduledFor; it[queuedAt] = now; it[startedAt] = null; it[endedAt] = if (status.final) now else null
            it[nextAttemptAt] = now; it[cancelRequested] = false; it[sessionReleased] = status.final; it[initiatorId] = run.initiatorId; it[credentialId] = run.credentialId
        }
        Automations.update({ Automations.id eq automationId }) {
            it[lastRunId] = run.id
            if (status.final && run.source != RunSource.TEST) failures(it, row[Automations.consecutiveFailures], status)
        }
        if (run.source == RunSource.TEST && actor != null) audit(actor.teamId, actor.member.id, "automation.test_run", "$automationId/${run.id}", null, actor.tokenId)
        return RunOutcome.Created(run(AutomationRuns.selectAll().where { AutomationRuns.id eq run.id }.single()))
    }

    /** An automation's runs recorded after [since], read on its history index. */
    private fun recent(automationId: String, since: Long) =
        AutomationRuns.select(AutomationRuns.id).where { (AutomationRuns.automationId eq automationId) and (AutomationRuns.queuedAt greater since) }

    /** Whether the cron and webhook runs of the last 24 hours that were not skipped reached [max]. */
    private fun budgetUsed(automationId: String, now: Long, max: Int) = recent(automationId, now - AutomationRules.DAY_MILLIS).andWhere {
        (AutomationRuns.runSource neq RunSource.TEST.wire) and (AutomationRuns.status notInList RunStatus.SKIPPED.map { it.wire })
    }.limit(max).count() >= max

    private fun activeRuns(automationId: String, statuses: Set<RunStatus>): Long =
        AutomationRuns.select(AutomationRuns.id).where { (AutomationRuns.automationId eq automationId) and (AutomationRuns.status inList statuses.map { it.wire }) }.limit(1).count()

    override suspend fun auditPreview(actor: Principal, automationId: String) = database.query {
        audit(actor.teamId, actor.member.id, "automation.test_previewed", automationId, null, actor.tokenId)
    }

    override suspend fun wouldSkip(automationId: String): String? = database.query {
        val row = Automations.select(Automations.overlap, Automations.maxRunsPerDay).where { Automations.id eq automationId }.singleOrNull() ?: return@query null
        val overlapping = when (OverlapPolicy.parse(row[Automations.overlap])) {
            OverlapPolicy.ALLOW -> false
            OverlapPolicy.QUEUE -> activeRuns(automationId, setOf(RunStatus.QUEUED)) > 0
            else -> activeRuns(automationId, RunStatus.ACTIVE) > 0
        }
        val max = row[Automations.maxRunsPerDay]
        when {
            overlapping -> "overlap"
            budgetUsed(automationId, nowMillis(), max) -> "budget"
            else -> null
        }
    }

    override suspend fun requestCancel(actor: Principal, automationId: String, runId: String): AutomationRun = database.query {
        requireCurrent(this, actor)
        val row = AutomationRuns.selectAll().where { (AutomationRuns.id eq runId) and (AutomationRuns.automationId eq automationId) and (AutomationRuns.teamId eq actor.teamId) }
            .forUpdate().singleOrNull() ?: throw TeamFailure("not_found", 404, "Run not found.")
        when (RunStatus.parse(row[AutomationRuns.status])) {
            RunStatus.QUEUED -> AutomationRuns.update({ AutomationRuns.id eq runId }) {
                it[status] = RunStatus.CANCELLED.wire; it[reason] = "cancelled"; it[endedAt] = nowMillis(); it[prompt] = null; it[cancelRequested] = true
                // It has no session: nothing to release (one created meanwhile is recorded by started(), unreleased).
                it[sessionReleased] = true
            }
            RunStatus.STARTING, RunStatus.RUNNING -> AutomationRuns.update({ AutomationRuns.id eq runId }) { it[cancelRequested] = true }
            else -> return@query run(row)
        }
        audit(actor.teamId, actor.member.id, "automation.run_cancelled", "$automationId/$runId", null, actor.tokenId)
        run(AutomationRuns.selectAll().where { AutomationRuns.id eq runId }.single())
    }

    override suspend fun dueCron(limit: Int): List<Automation> = database.query {
        val now = nowMillis()
        val ids = Automations.select(Automations.id).where {
            (Automations.enabled eq true) and (Automations.triggerKind eq TriggerKind.CRON.wire) and (Automations.nextRunAt lessEq now)
        }.orderBy(Automations.nextRunAt to SortOrder.ASC).limit(limit).map { it[Automations.id] }
        if (ids.isEmpty()) emptyList() else withOwnerAndLastRun.selectAll().where { Automations.id inList ids }.map(::automation).sortedBy { it.nextRunAt }
    }

    override suspend fun advance(automationId: String, expected: Long, next: Long?, run: NewRun?): Boolean = database.query {
        val row = Automations.selectAll().where { (Automations.id eq automationId) and (Automations.nextRunAt eq expected) and (Automations.enabled eq true) }
            .forUpdate().singleOrNull() ?: return@query false
        Automations.update({ Automations.id eq automationId }) { it[nextRunAt] = next }
        if (run != null) record(row, run, null)
        true
    }

    override suspend fun claimQueued(limit: Int, leaseMillis: Long): List<DispatchableRun> = database.query {
        val now = nowMillis()
        val candidates = AutomationRuns.select(AutomationRuns.id).where { (AutomationRuns.status eq RunStatus.QUEUED.wire) and (AutomationRuns.nextAttemptAt lessEq now) }
            .orderBy(AutomationRuns.nextAttemptAt to SortOrder.ASC).limit(limit).map { it[AutomationRuns.id] }
        val claimed = candidates.filter { id ->
            AutomationRuns.update({ (AutomationRuns.id eq id) and (AutomationRuns.status eq RunStatus.QUEUED.wire) and (AutomationRuns.nextAttemptAt lessEq now) }) {
                it[nextAttemptAt] = now + leaseMillis
            } == 1
        }
        if (claimed.isEmpty()) return@query emptyList()
        val runs = AutomationRuns.selectAll().where { AutomationRuns.id inList claimed }.toList()
        val automations = withOwnerAndLastRun.selectAll().where { Automations.id inList runs.map { it[AutomationRuns.automationId] }.distinct() }
            .associate { it[Automations.id] to automation(it) }
        runs.mapNotNull { row -> automations[row[AutomationRuns.automationId]]?.let { DispatchableRun(run(row), it, row[AutomationRuns.prompt]) } }
    }

    override suspend fun working(teamId: String, ownerId: String): Int = database.query {
        AutomationRuns.select(AutomationRuns.id).where {
            (AutomationRuns.ownerId eq ownerId) and (AutomationRuns.status inList RunStatus.WORKING.map { it.wire }) and (AutomationRuns.teamId eq teamId)
        }.count().toInt()
    }

    override suspend fun busy(automationId: String): Boolean = activeRuns(automationId, RunStatus.WORKING) > 0

    override suspend fun ownerPrincipal(automationId: String): Principal? = database.query {
        val now = nowMillis()
        Automations.join(Tokens, JoinType.INNER, Automations.credentialId, Tokens.id).join(Members, JoinType.INNER, Tokens.memberId, Members.id)
            .select(Automations.teamId, Tokens.id, Tokens.serviceScopes, Members.id, Members.username, Members.role, Members.isOwner, Members.disabled, Members.mustChangePassword)
            .where {
                (Automations.id eq automationId) and (Members.id eq Automations.ownerId) and (Members.teamId eq Automations.teamId) and
                    (Members.disabled eq false) and (Members.mustChangePassword eq false) and (Tokens.expiresAt greater now)
            }.singleOrNull()?.let {
                Principal(Member(it[Members.id], it[Members.username], readMemberRole(it), false, false), it[Tokens.id], it[Automations.teamId], readServiceScopes(it[Tokens.serviceScopes]))
            }
    }

    override suspend fun retry(runId: String, at: Long) = database.query {
        AutomationRuns.update({ (AutomationRuns.id eq runId) and (AutomationRuns.status eq RunStatus.QUEUED.wire) }) { it[nextAttemptAt] = at }
        Unit
    }

    override suspend fun started(runId: String, sessionId: String, profileVersion: Int): Boolean = database.query {
        val row = AutomationRuns.select(AutomationRuns.automationId, AutomationRuns.teamId, AutomationRuns.ownerId).where { AutomationRuns.id eq runId }.singleOrNull() ?: return@query false
        val now = nowMillis()
        val updated = AutomationRuns.update({ (AutomationRuns.id eq runId) and (AutomationRuns.status eq RunStatus.QUEUED.wire) }) {
            it[status] = RunStatus.STARTING.wire; it[AutomationRuns.sessionId] = sessionId; it[AutomationRuns.profileVersion] = profileVersion
            it[startedAt] = now; it[nextAttemptAt] = now; it[prompt] = null
        }
        if (updated == 1) audit(row[AutomationRuns.teamId], "system", "automation.run_started", "${row[AutomationRuns.automationId]}/$runId", row[AutomationRuns.ownerId])
        // Ended meanwhile (cancelled): it keeps the session, unreleased, so that a failed delete is retried by a later release.
        else AutomationRuns.update({ (AutomationRuns.id eq runId) and AutomationRuns.sessionId.isNull() }) { it[AutomationRuns.sessionId] = sessionId; it[sessionReleased] = false }
        updated == 1
    }

    override suspend fun running(runId: String) = database.query {
        AutomationRuns.update({ (AutomationRuns.id eq runId) and (AutomationRuns.status eq RunStatus.STARTING.wire) }) { it[status] = RunStatus.RUNNING.wire }
        Unit
    }

    override suspend fun finish(runId: String, status: RunStatus, reason: String?) = database.query {
        val row = AutomationRuns.select(AutomationRuns.automationId, AutomationRuns.status, AutomationRuns.runSource, AutomationRuns.sessionId).where { AutomationRuns.id eq runId }.singleOrNull() ?: return@query
        if (RunStatus.parse(row[AutomationRuns.status])?.final != false) return@query
        // Lock order: the automation, then its run, as recording a run does.
        val automation = Automations.select(Automations.consecutiveFailures).where { Automations.id eq row[AutomationRuns.automationId] }.forUpdate().singleOrNull()
        val updated = AutomationRuns.update({ (AutomationRuns.id eq runId) and (AutomationRuns.status inList RunStatus.ACTIVE.map { it.wire }) }) {
            it[AutomationRuns.status] = status.wire; it[AutomationRuns.reason] = reason; it[endedAt] = nowMillis(); it[prompt] = null
            // A run without a session has nothing to release: retention may purge it.
            if (row[AutomationRuns.sessionId] == null) it[sessionReleased] = true
        }
        if (updated == 1 && automation != null && row[AutomationRuns.runSource] != RunSource.TEST.wire)
            Automations.update({ Automations.id eq row[AutomationRuns.automationId] }) { failures(it, automation[Automations.consecutiveFailures], status) }
    }

    override suspend fun disableBySystem(automationId: String, reason: String) = database.query {
        val row = Automations.select(Automations.teamId, Automations.ownerId).where { Automations.id eq automationId }.forUpdate().singleOrNull() ?: return@query
        val updated = Automations.update({ (Automations.id eq automationId) and (Automations.enabled eq true) }) {
            it[enabled] = false; it[disabledReason] = reason; it[disabledBy] = null; it[nextRunAt] = null; it[updatedAt] = nowMillis()
        }
        if (updated == 1) audit(row[Automations.teamId], "system", "automation.$reason", automationId, row[Automations.ownerId])
    }

    override suspend fun active(limit: Int): List<ActiveRun> = database.query {
        val rows = AutomationRuns.selectAll().where { AutomationRuns.status inList RunStatus.WORKING.map { it.wire } }
            .orderBy(AutomationRuns.nextAttemptAt to SortOrder.ASC).limit(limit).toList()
        if (rows.isEmpty()) return@query emptyList()
        val runtimes = Automations.select(Automations.id, Automations.maxRuntimeMinutes).where { Automations.id inList rows.map { it[AutomationRuns.automationId] }.distinct() }
            .associate { it[Automations.id] to it[Automations.maxRuntimeMinutes] }
        rows.mapNotNull { row -> runtimes[row[AutomationRuns.automationId]]?.let { ActiveRun(run(row), it) } }
    }

    /**
     * Three queries for all the [sessionIds]: their sessions, their prompts (the initial message, found by its client key
     * on the unique index) and the `turn` events after each consumed prompt.
     */
    override suspend fun progress(sessionIds: List<String>): Map<String, SessionProgress> = database.query {
        if (sessionIds.isEmpty()) return@query emptyMap()
        val sessions = CloudSessions.select(CloudSessions.id, CloudSessions.ownerId, CloudSessions.state, CloudSessions.desiredState).where { CloudSessions.id inList sessionIds }.toList()
        if (sessions.isEmpty()) return@query emptyMap()
        val prompts = CloudSessionInputs.select(CloudSessionInputs.sessionId, CloudSessionInputs.status, CloudSessionInputs.eventSeq)
            .where { sessions.map { (CloudSessionInputs.sessionId eq it[CloudSessions.id]) and (CloudSessionInputs.authorId eq it[CloudSessions.ownerId]) }.reduce { a, b -> a or b } }
            .andWhere { CloudSessionInputs.clientKey eq CloudConversationService.INITIAL_MESSAGE_KEY }
            .associateBy { it[CloudSessionInputs.sessionId] }
        val consumed = prompts.mapNotNull { (id, row) ->
            row[CloudSessionInputs.eventSeq]?.takeIf { CloudInputStatus.parse(row[CloudSessionInputs.status]) == CloudInputStatus.CONSUMED }?.let { id to it }
        }.toMap()
        val ends = turnEnds(consumed)
        sessions.associate { row ->
            val id = row[CloudSessions.id]
            val status = prompts[id]?.let { CloudInputStatus.parse(it[CloudSessionInputs.status]) }
            id to SessionProgress(CloudSessionState.parse(row[CloudSessions.state]), DesiredState.parse(row[CloudSessions.desiredState]), status, ends[id])
        }
    }

    /** The phase of the first turn that ended after each session's consumed prompt (seq), read on the events' type index. */
    private fun turnEnds(after: Map<String, Long>): Map<String, String> {
        if (after.isEmpty()) return emptyMap()
        val condition = after.entries.map { (id, seq) -> (CloudSessionEvents.sessionId eq id) and (CloudSessionEvents.seq greater seq) }.reduce { a, b -> a or b }
        // In the index's order (session, then seq): a session cut by the limit is read on a later tick, once the sessions
        // before it have ended (a working run's session has few turn events after its prompt).
        return CloudSessionEvents.select(CloudSessionEvents.sessionId, CloudSessionEvents.seq, CloudSessionEvents.data)
            .where { (CloudSessionEvents.type eq CloudEventType.TURN.wire) and condition }
            .orderBy(CloudSessionEvents.sessionId to SortOrder.ASC, CloudSessionEvents.seq to SortOrder.ASC).limit(after.size * TURN_EVENTS).toList()
            .groupBy { it[CloudSessionEvents.sessionId] }
            .mapNotNull { (id, events) ->
                events.firstNotNullOfOrNull { event ->
                    runCatching { Json.parseToJsonElement(event[CloudSessionEvents.data]).jsonObject["phase"]?.jsonPrimitive?.contentOrNull }.getOrNull()
                        ?.takeIf { it in CloudConversationRules.TURN_END_PHASES }
                }?.let { id to it }
            }.toMap()
    }

    override suspend fun releasable(automationId: String, keep: Int): List<String> = database.query {
        val ended = AutomationRuns.select(AutomationRuns.id, AutomationRuns.sessionId, AutomationRuns.status, AutomationRuns.sessionReleased)
            .where { (AutomationRuns.automationId eq automationId) and AutomationRuns.sessionId.isNotNull() }
            .orderBy(AutomationRuns.queuedAt to SortOrder.DESC, AutomationRuns.id to SortOrder.DESC).limit(keep + RELEASE_WINDOW).toList()
            .filter { RunStatus.parse(it[AutomationRuns.status])?.final == true }
        val release = ended.drop(keep).filter { !it[AutomationRuns.sessionReleased] }
        release.mapNotNull { it[AutomationRuns.sessionId] }
    }

    override suspend fun released(automationId: String, sessionIds: List<String>) = database.query {
        AutomationRuns.update({ (AutomationRuns.automationId eq automationId) and (AutomationRuns.sessionId inList sessionIds) }) { it[sessionReleased] = true }
        Unit
    }

    override suspend fun purge(limit: Int): Int = database.query {
        val ids = AutomationRuns.select(AutomationRuns.id).where { AutomationRuns.endedAt less nowMillis() - AutomationRules.RETENTION_MILLIS }
            .andWhere { AutomationRuns.sessionReleased eq true }
            .orderBy(AutomationRuns.endedAt to SortOrder.ASC).limit(limit).map { it[AutomationRuns.id] }
        if (ids.isEmpty()) 0 else AutomationRuns.deleteWhere { AutomationRuns.id inList ids }
    }

    private fun locked(teamId: String, id: String) = Automations.selectAll().where { (Automations.id eq id) and (Automations.teamId eq teamId) }.forUpdate().singleOrNull()

    private fun readAutomation(teamId: String, id: String): Automation? =
        withOwnerAndLastRun.selectAll().where { (Automations.id eq id) and (Automations.teamId eq teamId) }.singleOrNull()?.let(::automation)

    /** A server-held token of the owner, never shown: the automation's sessions are created with it. */
    private fun JdbcTransaction.mintCredential(ownerId: String, automationId: String, now: Long): String {
        val id = UUID.randomUUID().toString()
        Tokens.insert {
            it[Tokens.id] = id; it[memberId] = ownerId; it[tokenHash] = Passwords.digest("tcau_" + Passwords.secret())
            it[device] = "automation ${automationId.take(8)}"; it[deviceId] = null; it[createdAt] = now; it[expiresAt] = ServiceKeyService.NO_EXPIRY
            it[serviceScopes] = JsonArray(listOf(JsonPrimitive(ServiceScope.CLOUD_WRITE.wire))).toString()
        }
        return id
    }

    private fun failures(statement: org.jetbrains.exposed.v1.core.statements.UpdateBuilder<*>, current: Int, status: RunStatus) {
        when {
            status == RunStatus.SUCCEEDED -> statement[Automations.consecutiveFailures] = 0
            status.failure -> statement[Automations.consecutiveFailures] = current + 1
        }
    }

    private fun missing() = TeamFailure("not_found", 404, "Automation not found.")

    companion object {
        /** How far past the kept sessions [releasable] looks: each ended run releases at once, so a few suffice. */
        const val RELEASE_WINDOW = 50
        /** Turn events read after the prompt: a turn has a start and an end, so the first few hold its end. */
        const val TURN_EVENTS = 10
        private val lastRun = AutomationRuns.alias("last_run")
        internal val withOwnerAndLastRun get() = Automations.join(Members, JoinType.INNER, Automations.ownerId, Members.id)
            .join(lastRun, JoinType.LEFT, Automations.lastRunId, lastRun[AutomationRuns.id])

        internal fun writeDefinition(it: org.jetbrains.exposed.v1.core.statements.UpdateBuilder<*>, d: AutomationDefinition) {
            it[Automations.name] = d.name; it[Automations.triggerKind] = d.trigger.kind.wire; it[Automations.cron] = d.trigger.cron; it[Automations.timezone] = d.trigger.timezone
            it[Automations.catchup] = d.trigger.catchup.wire; it[Automations.catchupGraceMinutes] = d.trigger.catchupGraceMinutes
            it[Automations.actionKind] = d.action.kind.wire; it[Automations.profileId] = d.action.profileId; it[Automations.model] = d.action.model
            it[Automations.effort] = d.action.effort; it[Automations.maxRuntimeMinutes] = d.action.maxRuntimeMinutes
            it[Automations.prompt] = d.prompt; it[Automations.variables] = encodeVariables(d.variables); it[Automations.samplePayload] = d.samplePayload?.toString()
            it[Automations.overlap] = d.overlap.wire; it[Automations.maxRunsPerDay] = d.maxRunsPerDay
        }

        internal fun automation(row: ResultRow): Automation {
            val definition = AutomationDefinition(
                row[Automations.name],
                AutomationTrigger(TriggerKind.parse(row[Automations.triggerKind]) ?: TriggerKind.WEBHOOK, row[Automations.cron], row[Automations.timezone],
                    CatchupPolicy.parse(row[Automations.catchup]) ?: CatchupPolicy.ONCE, row[Automations.catchupGraceMinutes]),
                AutomationAction(ActionKind.parse(row[Automations.actionKind]) ?: ActionKind.CLOUD, row[Automations.profileId], row[Automations.model], row[Automations.effort],
                    row[Automations.maxRuntimeMinutes]),
                row[Automations.prompt], decodeVariables(row[Automations.variables]),
                row[Automations.samplePayload]?.let { runCatching { Json.parseToJsonElement(it).jsonObject }.getOrNull() },
                OverlapPolicy.parse(row[Automations.overlap]) ?: OverlapPolicy.SKIP, row[Automations.maxRunsPerDay],
            )
            val last = row.getOrNull(lastRun[AutomationRuns.id])?.let {
                RunSummary(it, RunSource.parse(row[lastRun[AutomationRuns.runSource]]) ?: RunSource.TEST, RunStatus.parse(row[lastRun[AutomationRuns.status]]) ?: RunStatus.FAILED,
                    row[lastRun[AutomationRuns.queuedAt]])
            }
            return Automation(row[Automations.id], row[Automations.teamId], row[Automations.ownerId], row[Members.username], definition, row[Automations.enabled],
                row[Automations.disabledReason], row[Automations.disabledBy], row[Automations.version], row[Automations.nextRunAt], row[Automations.consecutiveFailures], last,
                row[Automations.secretRotatedAt], row[Automations.previousSecretUntil], row[Automations.createdAt], row[Automations.updatedAt])
        }

        internal fun run(row: ResultRow) = AutomationRun(
            row[AutomationRuns.id], row[AutomationRuns.automationId], row[AutomationRuns.teamId], row[AutomationRuns.ownerId], row[AutomationRuns.automationVersion],
            RunSource.parse(row[AutomationRuns.runSource]) ?: RunSource.TEST, RunStatus.parse(row[AutomationRuns.status]) ?: RunStatus.FAILED, row[AutomationRuns.reason],
            row[AutomationRuns.sessionId], row[AutomationRuns.profileVersion], row[AutomationRuns.scheduledFor], row[AutomationRuns.queuedAt], row[AutomationRuns.startedAt],
            row[AutomationRuns.endedAt], row[AutomationRuns.cancelRequested], row[AutomationRuns.initiatorId], decodeExtracted(row[AutomationRuns.variables]),
        )

        internal fun encodeVariables(variables: List<AutomationVariable>) = JsonArray(variables.map { v ->
            buildJsonObject {
                put("name", v.name); put("path", v.path); put("type", v.type.wire); put("values", JsonArray(v.values.map(::JsonPrimitive)))
                put("maxLength", v.maxLength); put("required", v.required); put("sensitive", v.sensitive)
            }
        }).toString()

        internal fun decodeVariables(text: String): List<AutomationVariable> = Json.parseToJsonElement(text).jsonArray.map { element ->
            val o = element.jsonObject
            AutomationVariable(o.getValue("name").jsonPrimitive.content, o.getValue("path").jsonPrimitive.content,
                VariableType.parse(o.getValue("type").jsonPrimitive.content) ?: VariableType.STRING, o["values"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty(),
                o.getValue("maxLength").jsonPrimitive.int, o.getValue("required").jsonPrimitive.boolean, o.getValue("sensitive").jsonPrimitive.boolean)
        }

        /** A sensitive value is never written: [ExtractedVariable.value] is already null for it. */
        internal fun encodeExtracted(variables: List<ExtractedVariable>) = JsonArray(variables.map { v ->
            buildJsonObject {
                put("name", v.name); put("type", v.type.wire); put("value", if (v.sensitive) null else v.value); put("sensitive", v.sensitive); put("present", v.present)
            }
        }).toString()

        internal fun decodeExtracted(text: String): List<ExtractedVariable> = Json.parseToJsonElement(text).jsonArray.map { element ->
            val o = element.jsonObject
            ExtractedVariable(o.getValue("name").jsonPrimitive.content, VariableType.parse(o.getValue("type").jsonPrimitive.content) ?: VariableType.STRING,
                o["value"]?.jsonPrimitive?.contentOrNull, o.getValue("sensitive").jsonPrimitive.boolean, o.getValue("present").jsonPrimitive.boolean)
        }
    }
}
