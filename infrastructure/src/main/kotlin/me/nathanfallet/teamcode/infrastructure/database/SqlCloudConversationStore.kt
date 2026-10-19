package me.nathanfallet.teamcode.infrastructure.database

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.nathanfallet.teamcode.domain.cloud.*
import me.nathanfallet.teamcode.domain.team.*
import me.nathanfallet.teamcode.infrastructure.database.tables.*
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*

/**
 * Transcript, inputs, attachments and the access policy in MySQL. Writes that number events or count pending
 * inputs lock the session row first, so concurrent replicas and retried runner batches agree.
 */
class SqlCloudConversationStore(private val database: Database) : CloudConversationStore {
    override suspend fun now(): Long = database.query { nowMillis() }

    override suspend fun appendRunnerEvents(sessionId: String, epoch: Int, events: List<RunnerEvent>): RunnerEventsAck = database.query {
        val teamId = lockSession(sessionId)[CloudSessions.teamId]
        var through = CloudSessionEvents.select(CloudSessionEvents.runnerSeq).where {
            (CloudSessionEvents.sessionId eq sessionId) and (CloudSessionEvents.epoch eq epoch) and CloudSessionEvents.runnerSeq.isNotNull()
        }.orderBy(CloudSessionEvents.runnerSeq to SortOrder.DESC).limit(1).singleOrNull()?.get(CloudSessionEvents.runnerSeq) ?: 0L
        var last = lastSeqOf(sessionId)
        val now = nowMillis()
        for (event in events.sortedBy { it.sequence }) {
            if (event.sequence <= through) continue
            if (event.sequence != through + 1) break // A gap: the runner resends from acceptedThrough + 1.
            last += 1
            insertEvent(sessionId, teamId, last, epoch, event.sequence, event.type, now, event.data)
            through = event.sequence
        }
        RunnerEventsAck(through, last)
    }

    override suspend fun events(sessionId: String, after: Long?, before: Long?, limit: Int): CloudEventPage = database.query {
        val last = lastSeqOf(sessionId)
        val first = CloudSessionEvents.select(CloudSessionEvents.seq).where { CloudSessionEvents.sessionId eq sessionId }
            .orderBy(CloudSessionEvents.seq to SortOrder.ASC).limit(1).singleOrNull()?.get(CloudSessionEvents.seq)
        if (before != null) {
            val rows = CloudSessionEvents.selectAll().where { (CloudSessionEvents.sessionId eq sessionId) and (CloudSessionEvents.seq less before) }
                .orderBy(CloudSessionEvents.seq to SortOrder.DESC).limit(limit + 1).toList()
            val page = rows.take(limit).map(::readEvent).reversed()
            CloudEventPage(page, first, last, page.lastOrNull()?.seq?.takeIf { it < last },
                if (rows.size > limit) page.first().seq else null)
        } else {
            val rows = CloudSessionEvents.selectAll().where { (CloudSessionEvents.sessionId eq sessionId) and (CloudSessionEvents.seq greater (after ?: 0L)) }
                .orderBy(CloudSessionEvents.seq to SortOrder.ASC).limit(limit + 1).toList()
            val page = rows.take(limit).map(::readEvent)
            CloudEventPage(page, first, last, if (rows.size > limit) page.last().seq else null,
                page.firstOrNull()?.seq?.takeIf { first != null && it > first })
        }
    }

    override suspend fun lastSeq(sessionId: String): Long = database.query { lastSeqOf(sessionId) }

    override suspend fun insertInput(new: NewInput, pendingLimit: Int): InputOutcome = database.query {
        val input = new.input
        val session = lockSession(input.sessionId)
        val state = CloudSessionState.parse(session[CloudSessions.state])
        val desired = DesiredState.parse(session[CloudSessions.desiredState])
        if (state.terminal || desired == DesiredState.TERMINATED) throw TeamFailure("session_not_running", 409, "The cloud session is terminated or being deleted.")
        if (input.clientKey != null) {
            val existing = CloudSessionInputs.selectAll().where {
                (CloudSessionInputs.sessionId eq input.sessionId) and (CloudSessionInputs.authorId eq input.authorId) and (CloudSessionInputs.clientKey eq input.clientKey)
            }.singleOrNull()
            if (existing != null) {
                if (existing[CloudSessionInputs.requestDigest] != input.requestDigest || existing[CloudSessionInputs.kind] != input.kind.wire)
                    throw TeamFailure("idempotency_conflict", 422, "This idempotency key was already used with a different message.")
                return@query InputOutcome.Replayed(readInput(existing))
            }
        }
        val expected = new.expectedLastEventSeq
        if (expected != null && lastSeqOf(input.sessionId) > expected)
            throw TeamFailure("stale_view", 409, "The transcript has newer events than expectedLastEventSeq. Read them first.")
        val pending = CloudSessionInputs.selectAll().where {
            (CloudSessionInputs.sessionId eq input.sessionId) and (CloudSessionInputs.status inList listOf(CloudInputStatus.QUEUED.wire, CloudInputStatus.DELIVERED.wire))
        }.count()
        if (pending >= pendingLimit) throw TeamFailure("too_many_pending_inputs", 429, "The session already has $pendingLimit pending inputs. Wait for the agent to consume them.")
        val sleeping = desired == DesiredState.PAUSED || state == CloudSessionState.PAUSING || state == CloudSessionState.PAUSED || session[CloudSessions.blockedCode] != null
        val now = nowMillis()
        val stored = input.copy(createdAt = now)
        CloudSessionInputs.insert {
            it[id] = stored.id; it[sessionId] = stored.sessionId; it[teamId] = stored.teamId; it[kind] = stored.kind.wire; it[status] = stored.status.wire
            it[authorId] = stored.authorId; it[credentialId] = stored.credentialId; it[text] = stored.text; it[wake] = stored.wake
            it[wakePending] = stored.kind == CloudInputKind.MESSAGE && stored.wake && sleeping
            it[clientKey] = stored.clientKey; it[requestDigest] = stored.requestDigest; it[createdAt] = now
        }
        InputOutcome.Accepted(stored)
    }

    override suspend fun input(sessionId: String, id: String): CloudInput? = database.query {
        CloudSessionInputs.selectAll().where { (CloudSessionInputs.id eq id) and (CloudSessionInputs.sessionId eq sessionId) }.singleOrNull()?.let(::readInput)
    }

    override suspend fun inputs(sessionId: String, beforeId: String?, limit: Int): CloudInputPage = database.query {
        val cursor = beforeId?.let { before ->
            CloudSessionInputs.select(CloudSessionInputs.createdAt).where { (CloudSessionInputs.id eq before) and (CloudSessionInputs.sessionId eq sessionId) }
                .singleOrNull()?.get(CloudSessionInputs.createdAt) ?: throw TeamFailure("invalid_cursor", 400, "Unknown input cursor.")
        }
        var condition: Op<Boolean> = CloudSessionInputs.sessionId eq sessionId
        if (cursor != null) condition = condition and ((CloudSessionInputs.createdAt less cursor) or ((CloudSessionInputs.createdAt eq cursor) and (CloudSessionInputs.id less beforeId!!)))
        val rows = CloudSessionInputs.selectAll().where { condition }
            .orderBy(CloudSessionInputs.createdAt to SortOrder.DESC, CloudSessionInputs.id to SortOrder.DESC).limit(limit + 1).toList()
        val page = rows.take(limit).map(::readInput)
        CloudInputPage(page, if (rows.size > limit) page.last().id else null)
    }

    override suspend fun clearWake(sessionId: String) = database.query {
        CloudSessionInputs.update({ (CloudSessionInputs.sessionId eq sessionId) and (CloudSessionInputs.wakePending eq true) }) { it[wakePending] = false }
        Unit
    }

    override suspend fun pendingWakes(limit: Int): List<Pair<String, String>> = database.query {
        CloudSessionInputs.select(CloudSessionInputs.teamId, CloudSessionInputs.sessionId).withDistinct()
            .where { (CloudSessionInputs.wakePending eq true) and (CloudSessionInputs.status eq CloudInputStatus.QUEUED.wire) }
            .limit(limit).map { it[CloudSessionInputs.teamId] to it[CloudSessionInputs.sessionId] }
    }

    override suspend fun deliver(sessionId: String, epoch: Int, limit: Int): List<CloudInput> = database.query {
        val teamId = lockSession(sessionId)[CloudSessions.teamId]
        val now = nowMillis()
        // Deliveries to an execution that ended without consuming them: retry messages, drop stale interrupts.
        val stale = CloudSessionInputs.selectAll().where {
            (CloudSessionInputs.sessionId eq sessionId) and (CloudSessionInputs.status eq CloudInputStatus.DELIVERED.wire) and (CloudSessionInputs.deliveredEpoch neq epoch)
        }.map(::readInput)
        for (input in stale) {
            if (input.kind == CloudInputKind.INTERRUPT || input.attempts >= CloudConversationRules.MAX_ATTEMPTS) {
                finish(teamId, input, CloudInputStatus.REJECTED, if (input.kind == CloudInputKind.INTERRUPT) "execution_ended" else "delivery_failed", now)
            } else CloudSessionInputs.update({ (CloudSessionInputs.id eq input.id) and (CloudSessionInputs.status eq CloudInputStatus.DELIVERED.wire) }) {
                it[status] = CloudInputStatus.QUEUED.wire
            }
        }
        val queued = CloudSessionInputs.selectAll().where { (CloudSessionInputs.sessionId eq sessionId) and (CloudSessionInputs.status eq CloudInputStatus.QUEUED.wire) }
            .orderBy(CloudSessionInputs.createdAt to SortOrder.ASC, CloudSessionInputs.id to SortOrder.ASC).limit(CloudConversationRules.MAX_PENDING_INPUTS).map(::readInput)
        // Interrupts first: they concern the turn running now, not the queue behind it.
        val chosen = (queued.filter { it.kind == CloudInputKind.INTERRUPT } + queued.filter { it.kind == CloudInputKind.MESSAGE }).take(limit)
        chosen.map { input ->
            CloudSessionInputs.update({ (CloudSessionInputs.id eq input.id) and (CloudSessionInputs.status eq CloudInputStatus.QUEUED.wire) }) {
                it[status] = CloudInputStatus.DELIVERED.wire; it[deliveredAt] = now; it[deliveredEpoch] = epoch; it[attempts] = input.attempts + 1
            }
            input.copy(status = CloudInputStatus.DELIVERED, deliveredAt = now, deliveredEpoch = epoch, attempts = input.attempts + 1)
        }
    }

    override suspend fun acknowledge(sessionId: String, epoch: Int, acks: List<RunnerInputAck>): List<CloudInput> = database.query {
        val teamId = lockSession(sessionId)[CloudSessions.teamId]
        val now = nowMillis()
        acks.distinctBy { it.id }.mapNotNull { ack ->
            val input = CloudSessionInputs.selectAll().where {
                (CloudSessionInputs.id eq ack.id) and (CloudSessionInputs.sessionId eq sessionId) and
                    (CloudSessionInputs.status eq CloudInputStatus.DELIVERED.wire) and (CloudSessionInputs.deliveredEpoch eq epoch)
            }.singleOrNull()?.let(::readInput) ?: return@mapNotNull null
            if (ack.consumed) finish(teamId, input, CloudInputStatus.CONSUMED, null, now)
            else finish(teamId, input, CloudInputStatus.REJECTED, ack.reason ?: "rejected", now)
        }
    }

    override suspend fun cancelEnded(limit: Int): Int = database.query {
        val ended = (CloudSessionInputs innerJoin CloudSessions).select(CloudSessionInputs.sessionId).withDistinct().where {
            (CloudSessionInputs.status inList listOf(CloudInputStatus.QUEUED.wire, CloudInputStatus.DELIVERED.wire)) and
                ((CloudSessions.state inList listOf(CloudSessionState.TERMINATED.wire, CloudSessionState.FAILED.wire)) or (CloudSessions.desiredState eq DesiredState.TERMINATED.wire))
        }.limit(limit).map { it[CloudSessionInputs.sessionId] }
        var changed = 0
        val now = nowMillis()
        for (sessionId in ended) {
            val teamId = lockSession(sessionId)[CloudSessions.teamId]
            CloudSessionInputs.selectAll().where {
                (CloudSessionInputs.sessionId eq sessionId) and (CloudSessionInputs.status inList listOf(CloudInputStatus.QUEUED.wire, CloudInputStatus.DELIVERED.wire))
            }.limit(CloudConversationRules.MAX_PENDING_INPUTS).map(::readInput).forEach { finish(teamId, it, CloudInputStatus.CANCELLED, "session_ended", now); changed++ }
        }
        changed
    }

    override suspend fun attach(attachment: CloudAttachment): CloudAttachment = database.query {
        val now = nowMillis()
        val stored = attachment.copy(createdAt = now, leaseUntil = now + CloudConversationRules.ATTACHMENT_LEASE_MILLIS)
        CloudSessionAttachments.insert {
            it[id] = stored.id; it[sessionId] = stored.sessionId; it[teamId] = stored.teamId; it[memberId] = stored.memberId
            it[credentialId] = stored.credentialId; it[permissions] = stored.permissions.map { p -> p.wire }.sorted().joinToString(",")
            it[createdAt] = now; it[leaseUntil] = stored.leaseUntil
        }
        stored
    }

    override suspend fun renew(sessionId: String, id: String, memberId: String, leaseUntil: Long): CloudAttachment? = database.query {
        val now = nowMillis()
        val scope = (CloudSessionAttachments.id eq id) and (CloudSessionAttachments.sessionId eq sessionId) and (CloudSessionAttachments.memberId eq memberId)
        val updated = CloudSessionAttachments.update({ scope and (CloudSessionAttachments.leaseUntil greater now) }) { it[CloudSessionAttachments.leaseUntil] = leaseUntil }
        if (updated != 1) null else CloudSessionAttachments.selectAll().where { scope }.singleOrNull()?.let(::readAttachment)
    }

    override suspend fun detach(sessionId: String, id: String, memberId: String) = database.query {
        CloudSessionAttachments.deleteWhere { (CloudSessionAttachments.id eq id) and (CloudSessionAttachments.sessionId eq sessionId) and (CloudSessionAttachments.memberId eq memberId) }
        Unit
    }

    override suspend fun attachments(sessionId: String, now: Long): List<CloudAttachment> = database.query {
        CloudSessionAttachments.selectAll().where { (CloudSessionAttachments.sessionId eq sessionId) and (CloudSessionAttachments.leaseUntil greater now) }
            .limit(100).map(::readAttachment)
    }

    override suspend fun accessPolicy(teamId: String): CloudAccessPolicy = database.query { readAccessPolicy(teamId) }

    override suspend fun saveAccessPolicy(actor: Principal, expectedVersion: Int, rules: List<CloudAccessRule>): CloudAccessPolicy = database.query {
        lockTeam(actor.teamId); requireCurrent(this, actor)
        val current = readAccessPolicy(actor.teamId)
        if (current.version != expectedVersion) throw TeamFailure("access_policy_conflict", 409, "The access policy changed. Read it again and retry.")
        val now = nowMillis()
        val saved = CloudAccessPolicy(expectedVersion + 1, rules, now, actor.member.id)
        CloudAccessPolicies.upsert {
            it[teamId] = actor.teamId; it[version] = saved.version
            it[CloudAccessPolicies.rules] = Json.encodeToString(rules.map { rule -> StoredAccessRule(rule.subject.wire, rule.permissions.map { p -> p.wire }.sorted(), rule.scope.wire) })
            it[updatedAt] = now; it[updatedBy] = actor.member.id
        }
        audit(actor.teamId, actor.member.id, "cloud.access.updated", actor.teamId, null, actor.tokenId)
        saved
    }

    override suspend fun purgeTranscripts(limit: Int): Int = database.query {
        val now = nowMillis()
        var deleted = 0
        for (team in Teams.select(Teams.id).limit(100).map { it[Teams.id] }) {
            val days = readCloudSettings(team).transcriptRetentionDays ?: continue
            val cutoff = now - days * DAY_MILLIS
            deleted += CloudSessionEvents.deleteWhere(limit = limit) { (CloudSessionEvents.teamId eq team) and (CloudSessionEvents.at less cutoff) }
            deleted += CloudSessionInputs.deleteWhere(limit = limit) { (CloudSessionInputs.teamId eq team) and (CloudSessionInputs.finishedAt less cutoff) }
        }
        CloudSessionAttachments.deleteWhere(limit = limit) { CloudSessionAttachments.leaseUntil less (now - DAY_MILLIS) }
        deleted
    }

    /** Final status for an input, with its transcript event: the consumed message itself, or an `input` notice. */
    private fun JdbcTransaction.finish(teamId: String, input: CloudInput, status: CloudInputStatus, reason: String?, now: Long): CloudInput {
        val seq = lastSeqOf(input.sessionId) + 1
        val data = if (status == CloudInputStatus.CONSUMED && input.kind == CloudInputKind.MESSAGE) buildJsonObject {
            put("inputId", input.id); put("authorId", input.authorId); put("text", CloudConversationRules.bound(input.text.orEmpty()))
        } else buildJsonObject {
            put("inputId", input.id); put("kind", input.kind.wire); put("status", status.wire); reason?.let { put("reason", it) }
        }
        val type = if (status == CloudInputStatus.CONSUMED && input.kind == CloudInputKind.MESSAGE) CloudEventType.USER_MESSAGE else CloudEventType.INPUT
        insertEvent(input.sessionId, teamId, seq, 0, null, type, now, data.toString())
        CloudSessionInputs.update({ CloudSessionInputs.id eq input.id }) {
            it[CloudSessionInputs.status] = status.wire; it[finishedAt] = now; it[CloudSessionInputs.reason] = reason; it[eventSeq] = seq
            if (status == CloudInputStatus.CONSUMED) it[consumedAt] = now
            it[wakePending] = false
        }
        return input.copy(status = status, finishedAt = now, reason = reason, eventSeq = seq, consumedAt = if (status == CloudInputStatus.CONSUMED) now else input.consumedAt)
    }

    private fun insertEvent(sessionId: String, teamId: String, seq: Long, epoch: Int, runnerSeq: Long?, type: CloudEventType, at: Long, data: String) {
        CloudSessionEvents.insert {
            it[CloudSessionEvents.sessionId] = sessionId; it[CloudSessionEvents.seq] = seq; it[CloudSessionEvents.teamId] = teamId
            it[CloudSessionEvents.epoch] = epoch; it[CloudSessionEvents.runnerSeq] = runnerSeq; it[CloudSessionEvents.type] = type.wire
            it[CloudSessionEvents.at] = at; it[CloudSessionEvents.data] = data
        }
    }

    private fun lockSession(sessionId: String): ResultRow =
        CloudSessions.select(CloudSessions.id, CloudSessions.teamId, CloudSessions.state, CloudSessions.desiredState, CloudSessions.blockedCode)
            .where { CloudSessions.id eq sessionId }.forUpdate().singleOrNull() ?: throw TeamFailure("not_found", 404, "Cloud session not found.")

    private fun lastSeqOf(sessionId: String): Long = CloudSessionEvents.select(CloudSessionEvents.seq).where { CloudSessionEvents.sessionId eq sessionId }
        .orderBy(CloudSessionEvents.seq to SortOrder.DESC).limit(1).singleOrNull()?.get(CloudSessionEvents.seq) ?: 0L

    private companion object {
        const val DAY_MILLIS = 86_400_000L
    }
}

@Serializable
private data class StoredAccessRule(val subject: String, val permissions: List<String>, val scope: String)

internal fun readAccessPolicy(teamId: String): CloudAccessPolicy =
    CloudAccessPolicies.selectAll().where { CloudAccessPolicies.teamId eq teamId }.singleOrNull()?.let { row ->
        val rules = Json.decodeFromString<List<StoredAccessRule>>(row[CloudAccessPolicies.rules]).mapNotNull { stored ->
            val subject = CloudAccessSubject.parse(stored.subject) ?: return@mapNotNull null
            val scope = CloudAccessScope.parse(stored.scope) ?: return@mapNotNull null
            CloudAccessRule(subject, stored.permissions.mapNotNull(CloudPermission::parse).toSet(), scope)
        }
        CloudAccessPolicy(row[CloudAccessPolicies.version], rules, row[CloudAccessPolicies.updatedAt], row[CloudAccessPolicies.updatedBy])
    } ?: CloudAccessPolicy.DEFAULT

private fun readEvent(row: ResultRow) = CloudEvent(
    row[CloudSessionEvents.sessionId], row[CloudSessionEvents.seq], CloudEventType.parse(row[CloudSessionEvents.type]) ?: CloudEventType.STATUS,
    row[CloudSessionEvents.at], row[CloudSessionEvents.epoch], row[CloudSessionEvents.data],
)
private fun readInput(row: ResultRow) = CloudInput(
    id = row[CloudSessionInputs.id], teamId = row[CloudSessionInputs.teamId], sessionId = row[CloudSessionInputs.sessionId],
    kind = if (row[CloudSessionInputs.kind] == CloudInputKind.INTERRUPT.wire) CloudInputKind.INTERRUPT else CloudInputKind.MESSAGE,
    status = CloudInputStatus.parse(row[CloudSessionInputs.status]), authorId = row[CloudSessionInputs.authorId], credentialId = row[CloudSessionInputs.credentialId],
    text = row[CloudSessionInputs.text], wake = row[CloudSessionInputs.wake], clientKey = row[CloudSessionInputs.clientKey], requestDigest = row[CloudSessionInputs.requestDigest],
    createdAt = row[CloudSessionInputs.createdAt], deliveredAt = row[CloudSessionInputs.deliveredAt], deliveredEpoch = row[CloudSessionInputs.deliveredEpoch],
    attempts = row[CloudSessionInputs.attempts], consumedAt = row[CloudSessionInputs.consumedAt], finishedAt = row[CloudSessionInputs.finishedAt],
    reason = row[CloudSessionInputs.reason], eventSeq = row[CloudSessionInputs.eventSeq],
)
private fun readAttachment(row: ResultRow) = CloudAttachment(
    row[CloudSessionAttachments.id], row[CloudSessionAttachments.teamId], row[CloudSessionAttachments.sessionId], row[CloudSessionAttachments.memberId],
    row[CloudSessionAttachments.credentialId], row[CloudSessionAttachments.permissions].split(',').mapNotNull(CloudPermission::parse).toSet(),
    row[CloudSessionAttachments.createdAt], row[CloudSessionAttachments.leaseUntil],
)
