package me.nathanfallet.teamcode.infrastructure.database

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
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
class SqlCloudConversationStore(private val database: Database) : CloudConversationStore, CloudActivitySource {
    override suspend fun now(): Long = database.query { nowMillis() }

    override suspend fun appendRunnerEvents(sessionId: String, epoch: Int, events: List<RunnerEvent>): RunnerEventsAck = database.query {
        val session = lockSession(sessionId)
        val teamId = session[CloudSessions.teamId]
        val cursor = cursorOf(sessionId)
        // The cursor survives retention, so a long-lived execution keeps its numbering after old events are purged.
        var through = cursor?.takeIf { it[CloudSessionTranscripts.runnerEpoch] == epoch }?.get(CloudSessionTranscripts.runnerSeq) ?: 0L
        var last = cursor?.get(CloudSessionTranscripts.lastSeq) ?: 0L
        val start = last
        val now = nowMillis()
        // Turns, permission requests and their expiries, in transcript order, so a request and what closes it reach the feed in that order.
        val feed = mutableListOf<Triple<Long, CloudEventType, String>>()
        val waited = readHumanWait(session)
        var wait = waited
        // A request raised while the session takes no answer (a pause asked: the runner denies it) never shows as waiting.
        val answerable = DesiredState.parse(session[CloudSessions.desiredState]) == DesiredState.RUNNING &&
            CloudSessionState.parse(session[CloudSessions.state]).let { it == CloudSessionState.READY || it == CloudSessionState.ACTIVE }
        for (event in events.sortedBy { it.sequence }) {
            if (event.sequence <= through) continue
            if (event.sequence != through + 1) break // A gap: the runner resends from acceptedThrough + 1.
            insertEvent(sessionId, teamId, ++last, epoch, event.sequence, event.type, now, event.data)
            if (event.type in FEED_TYPES) feed += Triple(last, event.type, event.data)
            // The wait follows the transcript event by event, so it is exact whatever the batch holds.
            wait = when {
                event.type == CloudEventType.PERMISSION_REQUEST -> (if (answerable) humanWait(jsonObject(event.data), now, last, epoch) else null) ?: wait
                event.turn == TurnMarker.ENDED -> null // Every request of the turn ended with it.
                event.type == CloudEventType.PERMISSION_EXPIRED -> closeWait(sessionId, wait, jsonObject(event.data).str("requestId"))
                else -> wait
            }
            through = event.sequence
        }
        if (last != start) saveCursor(sessionId, cursor != null, last, epoch, through)
        if (wait != waited) saveHumanWait(sessionId, wait)
        // Last, so the team's cursor lock is taken after every other write of this transaction.
        val owner = session[CloudSessions.ownerId]
        // One batch, however many turns and requests the upload carried.
        publishTeamEvents(teamId, feed.mapNotNull { (seq, type, data) ->
            when (type) {
                CloudEventType.TURN -> turnEvent(sessionId, owner, seq, data, now)
                CloudEventType.PERMISSION_EXPIRED -> expiredEvent(sessionId, owner, seq, data, now)
                else -> waitingEvent(sessionId, owner, seq, data, now)
            }?.ofKind(session)
        })
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

    /** The request [NewInput.permissionCheck] sees, read on its own (tests). */
    internal suspend fun permissionRequest(sessionId: String, requestId: String): PermissionRequestRecord? = database.query { findPermissionRequest(sessionId, requestId) }

    /**
     * Through the index (session, type, seq): the newest of the session's latest requests whose stored event starts
     * with this `requestId` (the runner writes it first, so only that head is read), then the first event of each
     * closing kind after it. Each query is bounded; none reads another request's input.
     */
    private fun findPermissionRequest(sessionId: String, requestId: String): PermissionRequestRecord? {
        val head = CloudSessionEvents.data.substring(1, REQUEST_HEAD_CHARS)
        val expected = "{\"requestId\":" + JsonPrimitive(requestId)
        val anchor = CloudSessionEvents.select(CloudSessionEvents.seq, head).where {
            (CloudSessionEvents.sessionId eq sessionId) and (CloudSessionEvents.type eq CloudEventType.PERMISSION_REQUEST.wire)
        }.orderBy(CloudSessionEvents.seq to SortOrder.DESC).limit(MAX_REQUESTS_SCANNED).firstOrNull { row ->
            row[head].removePrefix(expected).let { rest -> rest.length < row[head].length && (rest.startsWith(",") || rest.startsWith("}")) }
        }?.get(CloudSessionEvents.seq) ?: return null
        val asked = CloudSessionEvents.select(CloudSessionEvents.data, CloudSessionEvents.epoch)
            .where { (CloudSessionEvents.sessionId eq sessionId) and (CloudSessionEvents.seq eq anchor) }.single()
        val data = jsonObject(asked[CloudSessionEvents.data]) ?: return null
        return permissionRequestRecord(requestId, data, requestState(sessionId, anchor, requestId)).copy(epoch = asked[CloudSessionEvents.epoch])
    }

    /** Whether the request [requestId] stored at [anchor] is still open: the first closing event after it, through the index (session, type, seq). */
    private fun requestState(sessionId: String, anchor: Long, requestId: String): PermissionRequestState {
        fun closing(type: CloudEventType, scanned: Int, closes: (JsonObject) -> Boolean): Long? =
            CloudSessionEvents.select(CloudSessionEvents.seq, CloudSessionEvents.data).where {
                (CloudSessionEvents.sessionId eq sessionId) and (CloudSessionEvents.type eq type.wire) and (CloudSessionEvents.seq greater anchor)
            }.orderBy(CloudSessionEvents.seq to SortOrder.ASC).limit(scanned)
                .firstOrNull { row -> jsonObject(row[CloudSessionEvents.data])?.let(closes) == true }?.get(CloudSessionEvents.seq)
        // The request's turn ends at the first turn event after it that is an end; the oldest closing event wins (the agent saw it first).
        // Messages queued during the turn add `started` markers before its end: those are skipped.
        val ended = closing(CloudEventType.TURN, MAX_CLOSING_SCANNED) { it.str("phase") in CloudConversationRules.TURN_END_PHASES }
        val answered = closing(CloudEventType.INPUT, MAX_CLOSING_SCANNED) { it.str("requestId") == requestId && it.str("status") == CloudInputStatus.CONSUMED.wire }
        val expired = closing(CloudEventType.PERMISSION_EXPIRED, MAX_CLOSING_SCANNED) { it.str("requestId") == requestId }
        return listOfNotNull(ended?.let { it to PermissionRequestState.ENDED }, answered?.let { it to PermissionRequestState.ANSWERED }, expired?.let { it to PermissionRequestState.EXPIRED })
            .minByOrNull { it.first }?.second ?: PermissionRequestState.OPEN
    }

    /**
     * The wait a stored `permission.request` sets: why, which request, since when. None for an id no member could
     * answer with: the runner denies such a request at once, so nobody is waited for.
     */
    private fun humanWait(request: JsonObject?, now: Long, seq: Long, epoch: Int): CloudHumanWait? {
        val requestId = request.str("requestId")?.takeIf(CloudConversationRules::isPermissionRequestId) ?: return null
        return CloudHumanWait(if (request.str("question") == "true") CloudHumanWait.Reason.QUESTION else CloudHumanWait.Reason.PERMISSION, requestId, now, seq, epoch)
    }

    /**
     * The wait once the request [requestId] was answered or expired: unchanged unless it is the one shown, else the
     * newest earlier request of the same execution still open, or none. Requests are read newest first through the
     * index (session, type, seq), at most [MAX_OPEN_SCANNED], from their head only (never another request's input);
     * the first one whose turn ended stops the search, since every earlier one ended with that turn or before.
     * Called under the session row lock, after the closing event was written.
     */
    private fun closeWait(sessionId: String, wait: CloudHumanWait?, requestId: String?): CloudHumanWait? {
        if (wait == null || requestId == null || wait.requestId != requestId) return wait
        val head = CloudSessionEvents.data.substring(1, REQUEST_HEAD_CHARS)
        val earlier = CloudSessionEvents.select(CloudSessionEvents.seq, CloudSessionEvents.epoch, head).where {
            (CloudSessionEvents.sessionId eq sessionId) and (CloudSessionEvents.type eq CloudEventType.PERMISSION_REQUEST.wire) and (CloudSessionEvents.seq less wait.eventSeq)
        }.orderBy(CloudSessionEvents.seq to SortOrder.DESC).limit(MAX_OPEN_SCANNED).toList()
        for (row in earlier) {
            if (row[CloudSessionEvents.epoch] != wait.epoch) return null // Another execution's requests ended with it.
            val seq = row[CloudSessionEvents.seq]
            fun asked() = CloudSessionEvents.select(CloudSessionEvents.data, CloudSessionEvents.at)
                .where { (CloudSessionEvents.sessionId eq sessionId) and (CloudSessionEvents.seq eq seq) }.single()
            // The runner writes `requestId` first; a head without it is read whole (by primary key) rather than guessed.
            val id = requestHeadId(row[head]) ?: asked().let { jsonObject(it[CloudSessionEvents.data]).str("requestId")?.takeIf(CloudConversationRules::isPermissionRequestId) }
                // An id no member could answer with: the runner denied that request at once, it never waited.
                ?: continue
            when (requestState(sessionId, seq, id)) {
                PermissionRequestState.OPEN -> {
                    val open = asked()
                    return humanWait(jsonObject(open[CloudSessionEvents.data]), open[CloudSessionEvents.at], seq, wait.epoch)
                }
                PermissionRequestState.ENDED -> return null
                PermissionRequestState.ANSWERED, PermissionRequestState.EXPIRED -> continue
            }
        }
        return null
    }

    private fun saveHumanWait(sessionId: String, wait: CloudHumanWait?) {
        CloudSessions.update({ CloudSessions.id eq sessionId }) {
            it[awaitingReason] = wait?.reason?.wire; it[awaitingRequestId] = wait?.requestId; it[awaitingSince] = wait?.since
            it[awaitingEventSeq] = wait?.eventSeq; it[awaitingEpoch] = wait?.epoch
        }
    }

    override suspend fun insertInput(new: NewInput, pendingLimit: Int): InputOutcome = database.query {
        val input = new.input
        val session = lockSession(input.sessionId)
        // Serialized with a transfer's completion by the session lock: an input authorized against the former owner is refused.
        if (new.expectedOwnerId != null && session[CloudSessions.ownerId] != new.expectedOwnerId) throw ownerChanged()
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
        new.permissionCheck?.invoke(AnswerContext(new.permissionRequestId?.let { findPermissionRequest(input.sessionId, it) }, state, desired, session[CloudSessions.currentEpoch]))
        val expected = new.expectedLastEventSeq
        if (expected != null && lastSeqOf(input.sessionId) > expected)
            throw TeamFailure("stale_view", 409, "The transcript has newer events than expectedLastEventSeq. Read them first.")
        // A permission answer has its own small bound: messages queued behind a waiting turn must not block the answer that ends it.
        val answering = input.kind == CloudInputKind.PERMISSION
        val pending = CloudSessionInputs.selectAll().where {
            (CloudSessionInputs.sessionId eq input.sessionId) and (CloudSessionInputs.status inList listOf(CloudInputStatus.QUEUED.wire, CloudInputStatus.DELIVERED.wire)) and
                (if (answering) CloudSessionInputs.kind eq CloudInputKind.PERMISSION.wire else CloudSessionInputs.kind neq CloudInputKind.PERMISSION.wire)
        }.count()
        val limit = if (answering) CloudConversationRules.MAX_PENDING_PERMISSION_ANSWERS else pendingLimit
        // One answer per request at a time, decided under the session lock: a second one would only be rejected later by the runner.
        new.permissionRequestId?.let { requestId ->
            val answered = CloudSessionInputs.select(CloudSessionInputs.text).where {
                (CloudSessionInputs.sessionId eq input.sessionId) and (CloudSessionInputs.kind eq CloudInputKind.PERMISSION.wire) and
                    (CloudSessionInputs.status inList listOf(CloudInputStatus.QUEUED.wire, CloudInputStatus.DELIVERED.wire))
            }.limit(CloudConversationRules.MAX_PENDING_PERMISSION_ANSWERS).any { row ->
                jsonObject(row[CloudSessionInputs.text]).str("requestId") == requestId
            }
            if (answered) throw TeamFailure("permission_request_answered", 409, "This permission request already has an answer on its way to the agent.")
        }
        if (pending >= limit) throw TeamFailure("too_many_pending_inputs", 429, "The session already has $limit pending ${if (answering) "permission answers" else "inputs"}. Wait for the agent to consume them.")
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
        // Deliveries never acknowledged: to an execution that ended, or to this one but lost on the way (a dropped
        // long-poll response) or left alone while the agent stopped. Messages are retried, stale interrupts dropped.
        val stale = CloudSessionInputs.selectAll().where {
            (CloudSessionInputs.sessionId eq sessionId) and (CloudSessionInputs.status eq CloudInputStatus.DELIVERED.wire) and
                ((CloudSessionInputs.deliveredEpoch neq epoch) or (CloudSessionInputs.deliveredAt less (now - CloudConversationRules.REDELIVERY_MILLIS)))
        }.map(::readInput)
        for (input in stale) {
            // An interrupt or a permission answer concerns the agent process it was delivered to: never offered to another.
            // An interrupt is dropped; a permission answer is offered again to the same execution (the runner acknowledges a repeat without
            // writing it twice), never to another one.
            val redelivered = input.kind == CloudInputKind.MESSAGE || (input.kind == CloudInputKind.PERMISSION && input.deliveredEpoch == epoch)
            if (!redelivered || input.attempts >= CloudConversationRules.MAX_ATTEMPTS) {
                val reason = if (input.kind == CloudInputKind.MESSAGE) "delivery_failed" else if (input.deliveredEpoch != epoch) "execution_ended" else "delivery_failed"
                finish(teamId, input, CloudInputStatus.REJECTED, reason, now)
            } else CloudSessionInputs.update({ (CloudSessionInputs.id eq input.id) and (CloudSessionInputs.status eq CloudInputStatus.DELIVERED.wire) }) {
                it[status] = CloudInputStatus.QUEUED.wire
            }
        }
        fun queued(urgent: Boolean) = CloudSessionInputs.selectAll().where {
            (CloudSessionInputs.sessionId eq sessionId) and (CloudSessionInputs.status eq CloudInputStatus.QUEUED.wire) and
                (if (urgent) CloudSessionInputs.kind neq CloudInputKind.MESSAGE.wire else CloudSessionInputs.kind eq CloudInputKind.MESSAGE.wire)
        }.orderBy(CloudSessionInputs.createdAt to SortOrder.ASC, CloudSessionInputs.id to SortOrder.ASC).limit(limit).map(::readInput)
        // Interrupts and permission answers first, read on their own so a full message queue never hides them:
        // they concern the turn running now, not the queue behind it.
        val chosen = queued(urgent = true).let { urgent -> if (urgent.size >= limit) urgent else urgent + queued(urgent = false).take(limit - urgent.size) }
        chosen.map { input ->
            CloudSessionInputs.update({ (CloudSessionInputs.id eq input.id) and (CloudSessionInputs.status eq CloudInputStatus.QUEUED.wire) }) {
                it[status] = CloudInputStatus.DELIVERED.wire; it[deliveredAt] = now; it[deliveredEpoch] = epoch; it[attempts] = input.attempts + 1
            }
            input.copy(status = CloudInputStatus.DELIVERED, deliveredAt = now, deliveredEpoch = epoch, attempts = input.attempts + 1)
        }
    }

    override suspend fun acknowledge(sessionId: String, epoch: Int, acks: List<RunnerInputAck>): List<CloudInput> = database.query {
        val session = lockSession(sessionId)
        val teamId = session[CloudSessions.teamId]
        val now = nowMillis()
        acks.distinctBy { it.id }.mapNotNull { ack ->
            val input = CloudSessionInputs.selectAll().where {
                (CloudSessionInputs.id eq ack.id) and (CloudSessionInputs.sessionId eq sessionId) and
                    (CloudSessionInputs.status eq CloudInputStatus.DELIVERED.wire) and (CloudSessionInputs.deliveredEpoch eq epoch)
            }.singleOrNull()?.let(::readInput) ?: return@mapNotNull null
            if (ack.consumed) finish(teamId, input, CloudInputStatus.CONSUMED, null, now)
            else finish(teamId, input, CloudInputStatus.REJECTED, ack.reason ?: "rejected", now)
        }.also { finished ->
            val answers = finished.filter { it.kind == CloudInputKind.PERMISSION && it.status == CloudInputStatus.CONSUMED }
            // A consumed answer closes its request: the wait moves to the next request still open, if it was this one.
            val waited = readHumanWait(session)
            val wait = answers.fold(waited) { current, answer -> closeWait(sessionId, current, jsonObject(answer.text).str("requestId")) }
            if (wait != waited) saveHumanWait(sessionId, wait)
            // Last, so the team's cursor lock is taken after every other write of this transaction.
            publishTeamEvents(teamId, answers.map { answeredEvent(sessionId, session[CloudSessions.ownerId], it, now).ofKind(session) })
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
            }.limit(MAX_PENDING_ALL).map(::readInput).forEach { finish(teamId, it, CloudInputStatus.CANCELLED, "session_ended", now); changed++ }
        }
        changed
    }

    override suspend fun attach(attachment: CloudAttachment, expectedOwnerId: String): CloudAttachment = database.query {
        if (lockSession(attachment.sessionId)[CloudSessions.ownerId] != expectedOwnerId) throw ownerChanged()
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
        val now = nowMillis()
        CloudSessionAttachments.update({ (CloudSessionAttachments.id eq id) and (CloudSessionAttachments.sessionId eq sessionId) and
            (CloudSessionAttachments.memberId eq memberId) and (CloudSessionAttachments.leaseUntil greater now) }) { it[leaseUntil] = now }
        Unit
    }

    override suspend fun lastActivity(sessionId: String, now: Long): Long? = database.query {
        // At most MAX_PENDING_INPUTS rows, on the (session_id, status, created_at) index.
        val queued = CloudSessionInputs.createdAt.max(); val delivered = CloudSessionInputs.deliveredAt.max()
        val inputs = CloudSessionInputs.select(queued, delivered).where {
            (CloudSessionInputs.sessionId eq sessionId) and (CloudSessionInputs.status inList listOf(CloudInputStatus.QUEUED.wire, CloudInputStatus.DELIVERED.wire))
        }.singleOrNull()
        val lease = CloudSessionAttachments.leaseUntil.max()
        val leaseEnd = CloudSessionAttachments.select(lease).where { CloudSessionAttachments.sessionId eq sessionId }.singleOrNull()?.get(lease)
        listOfNotNull(inputs?.get(queued), inputs?.get(delivered), leaseEnd).maxOrNull()?.coerceAtMost(now)
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
        // Every team, in pages; each team's deletes are bounded so one large transcript cannot hold the job.
        var after = ""
        while (true) {
            val teams = Teams.select(Teams.id).where { Teams.id greater after }.orderBy(Teams.id to SortOrder.ASC).limit(100).map { it[Teams.id] }
            for (team in teams) {
                val days = readCloudSettings(team).transcriptRetentionDays ?: continue
                val cutoff = now - days * DAY_MILLIS
                deleted += CloudSessionEvents.deleteWhere(limit = limit) { (CloudSessionEvents.teamId eq team) and (CloudSessionEvents.at less cutoff) }
                deleted += CloudSessionInputs.deleteWhere(limit = limit) { (CloudSessionInputs.teamId eq team) and (CloudSessionInputs.finishedAt less cutoff) }
            }
            after = teams.lastOrNull() ?: break
            if (teams.size < 100) break
        }
        // Ended leases are kept as long as the longest idle timeout, so idle detection still sees a recent detach.
        CloudSessionAttachments.deleteWhere(limit = limit) { CloudSessionAttachments.leaseUntil less (now - maxOf(DAY_MILLIS, CloudIdleRules.MAX_TIMEOUT_MINUTES * 60_000L)) }
        deleted
    }

    /** Final status for an input, with its transcript event: the consumed message itself, or an `input` notice. */
    internal fun JdbcTransaction.finish(teamId: String, input: CloudInput, status: CloudInputStatus, reason: String?, now: Long): CloudInput {
        val data = if (status == CloudInputStatus.CONSUMED && input.kind == CloudInputKind.MESSAGE) buildJsonObject {
            put("inputId", input.id); put("authorId", input.authorId); put("text", CloudConversationRules.bound(input.text.orEmpty()))
        } else buildJsonObject {
            put("inputId", input.id); put("kind", input.kind.wire); put("status", status.wire); reason?.let { put("reason", it) }
            if (input.kind == CloudInputKind.PERMISSION) {
                // Who answered which request, and how; the answer's texts stay in the input.
                put("authorId", input.authorId)
                val answer = jsonObject(input.text)
                answer.str("requestId")?.let { put("requestId", it) }
                answer.str("behavior")?.let { put("behavior", it) }
            }
        }
        val type = if (status == CloudInputStatus.CONSUMED && input.kind == CloudInputKind.MESSAGE) CloudEventType.USER_MESSAGE else CloudEventType.INPUT
        val cursor = cursorOf(input.sessionId)
        val seq = (cursor?.get(CloudSessionTranscripts.lastSeq) ?: 0L) + 1
        insertEvent(input.sessionId, teamId, seq, 0, null, type, now, data.toString())
        saveCursor(input.sessionId, cursor != null, seq, cursor?.get(CloudSessionTranscripts.runnerEpoch) ?: 0, cursor?.get(CloudSessionTranscripts.runnerSeq) ?: 0L)
        CloudSessionInputs.update({ CloudSessionInputs.id eq input.id }) {
            it[CloudSessionInputs.status] = status.wire; it[finishedAt] = now; it[CloudSessionInputs.reason] = reason; it[eventSeq] = seq
            if (status == CloudInputStatus.CONSUMED) it[consumedAt] = now
            it[wakePending] = false
        }
        return input.copy(status = status, finishedAt = now, reason = reason, eventSeq = seq, consumedAt = if (status == CloudInputStatus.CONSUMED) now else input.consumedAt)
    }

    /** A server-written event (epoch 0); the caller holds the session row lock. Returns its sequence number. */
    internal fun serverEvent(sessionId: String, teamId: String, type: CloudEventType, data: String, now: Long): Long {
        val cursor = cursorOf(sessionId)
        val seq = (cursor?.get(CloudSessionTranscripts.lastSeq) ?: 0L) + 1
        insertEvent(sessionId, teamId, seq, 0, null, type, now, data)
        saveCursor(sessionId, cursor != null, seq, cursor?.get(CloudSessionTranscripts.runnerEpoch) ?: 0, cursor?.get(CloudSessionTranscripts.runnerSeq) ?: 0L)
        return seq
    }

    /** Queued and delivered inputs of a session, oldest first; the caller holds the session row lock. */
    internal fun pendingInputs(sessionId: String): List<CloudInput> = CloudSessionInputs.selectAll().where {
        (CloudSessionInputs.sessionId eq sessionId) and (CloudSessionInputs.status inList listOf(CloudInputStatus.QUEUED.wire, CloudInputStatus.DELIVERED.wire))
    }.orderBy(CloudSessionInputs.createdAt to SortOrder.ASC).limit(MAX_PENDING_ALL).map(::readInput)

    /** The caller holds the session row lock, so sequences are dense; [saveCursor] records the new last sequence. */
    private fun insertEvent(sessionId: String, teamId: String, seq: Long, epoch: Int, runnerSeq: Long?, type: CloudEventType, at: Long, data: String) {
        CloudSessionEvents.insert {
            it[CloudSessionEvents.sessionId] = sessionId; it[CloudSessionEvents.seq] = seq; it[CloudSessionEvents.teamId] = teamId
            it[CloudSessionEvents.epoch] = epoch; it[CloudSessionEvents.runnerSeq] = runnerSeq; it[CloudSessionEvents.type] = type.wire
            it[CloudSessionEvents.at] = at; it[CloudSessionEvents.data] = data
        }
    }

    /** The session's kind on its feed events, so the feed carries a remote session's only to clients that ask for them. */
    private fun PendingTeamEvent.ofKind(session: ResultRow) = copy(data = JsonObject(data + ("kind" to JsonPrimitive(session[CloudSessions.kind]))))

    private val FEED_TYPES = setOf(CloudEventType.TURN, CloudEventType.PERMISSION_REQUEST, CloudEventType.PERMISSION_EXPIRED)

    /** `cloud.turn` in the team feed: the phase and where to read the transcript, never its text. Called under the session row lock. */
    private val TURN_PHASES = setOf("started", "completed", "interrupted", "failed")

    private fun turnEvent(sessionId: String, owner: String, seq: Long, data: String, at: Long): PendingTeamEvent? {
        val phase = try { (Json.parseToJsonElement(data) as? JsonObject)?.get("phase")?.jsonPrimitive?.contentOrNull } catch (_: Exception) { null }
        if (phase !in TURN_PHASES) return null
        return PendingTeamEvent(TeamEventType.CLOUD_TURN, sessionId, owner, buildJsonObject { put("sessionId", sessionId); put("phase", phase); put("eventSeq", seq) }, at)
    }

    /** `cloud.session.waiting`: why the agent waits and where the request is, never the tool input or the question. */
    private fun waitingEvent(sessionId: String, owner: String, seq: Long, data: String, at: Long): PendingTeamEvent {
        val request = jsonObject(data)
        val question = request.str("question") == "true"
        return PendingTeamEvent(TeamEventType.SESSION_WAITING, sessionId, owner, buildJsonObject {
            put("sessionId", sessionId); put("reason", if (question) "question" else "permission")
            // Only an id a member can answer with; the runner (and the code in its pod) chose it.
            request.str("requestId")?.takeIf(CloudConversationRules::isPermissionRequestId)?.let { put("requestId", it) }
            put("eventSeq", seq)
        }, at)
    }

    /** `cloud.session.answered` with `reason: answered`: a member's answer reached the agent. */
    private fun answeredEvent(sessionId: String, owner: String, input: CloudInput, at: Long): PendingTeamEvent {
        val answer = jsonObject(input.text)
        return PendingTeamEvent(TeamEventType.SESSION_ANSWERED, sessionId, owner, buildJsonObject {
            put("sessionId", sessionId)
            answer.str("requestId")?.let { put("requestId", it) }
            answer.str("behavior")?.let { put("behavior", it) }
            put("reason", "answered")
            input.eventSeq?.let { put("eventSeq", it) }
        }, at)
    }

    /**
     * `cloud.session.answered` with `behavior: deny, reason: expired`: the runner denied a request nobody answered within
     * the team's deadline, which ends the wait like an answer. [seq] is the `permission.expired` event.
     */
    private fun expiredEvent(sessionId: String, owner: String, seq: Long, data: String, at: Long): PendingTeamEvent {
        val expired = jsonObject(data)
        return PendingTeamEvent(TeamEventType.SESSION_ANSWERED, sessionId, owner, buildJsonObject {
            put("sessionId", sessionId)
            expired.str("requestId")?.takeIf(CloudConversationRules::isPermissionRequestId)?.let { put("requestId", it) }
            put("behavior", "deny"); put("reason", "expired"); put("eventSeq", seq)
        }, at)
    }

    private fun saveCursor(sessionId: String, exists: Boolean, last: Long, epoch: Int, through: Long) {
        if (exists) CloudSessionTranscripts.update({ CloudSessionTranscripts.sessionId eq sessionId }) {
            it[lastSeq] = last; it[runnerEpoch] = epoch; it[runnerSeq] = through
        } else CloudSessionTranscripts.insert {
            it[CloudSessionTranscripts.sessionId] = sessionId; it[lastSeq] = last; it[runnerEpoch] = epoch; it[runnerSeq] = through
        }
    }

    private fun lockSession(sessionId: String): ResultRow =
        CloudSessions.select(CloudSessions.id, CloudSessions.teamId, CloudSessions.ownerId, CloudSessions.state, CloudSessions.desiredState, CloudSessions.blockedCode, CloudSessions.currentEpoch, CloudSessions.kind,
            CloudSessions.awaitingReason, CloudSessions.awaitingRequestId, CloudSessions.awaitingSince, CloudSessions.awaitingEventSeq, CloudSessions.awaitingEpoch)
            .where { CloudSessions.id eq sessionId }.forUpdate().singleOrNull() ?: throw TeamFailure("not_found", 404, "Cloud session not found.")

    private fun ownerChanged() = TeamFailure("session_busy", 409, "The cloud session changed owner. Retry the request.")

    private fun cursorOf(sessionId: String): ResultRow? = CloudSessionTranscripts.selectAll().where { CloudSessionTranscripts.sessionId eq sessionId }.singleOrNull()
    private fun lastSeqOf(sessionId: String): Long = cursorOf(sessionId)?.get(CloudSessionTranscripts.lastSeq) ?: 0L

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
/** How many of the session's latest requests an answer is matched against. */
private const val MAX_REQUESTS_SCANNED = 200
/** Every input a session may hold pending: messages and interrupts, plus permission answers under their own bound. */
private const val MAX_PENDING_ALL = CloudConversationRules.MAX_PENDING_INPUTS + CloudConversationRules.MAX_PENDING_PERMISSION_ANSWERS
/** How many later `turn`, `input` or `permission.expired` events are read to find the one that closed a request. */
private const val MAX_CLOSING_SCANNED = 200
/** Enough of a stored `permission.request` for its leading `requestId` (at most 128 characters, escaped). */
private const val REQUEST_HEAD_CHARS = 300
/** Earlier requests read when the shown one closes: more than a turn holds open at once in practice (parallel tool uses). */
private const val MAX_OPEN_SCANNED = 16
private val REQUEST_HEAD_ID = Regex("""^\{"requestId":("(?:[^"\\]|\\.)*")""")
/** The `requestId` a stored request starts with (the runner writes it first), or null. */
private fun requestHeadId(head: String): String? = REQUEST_HEAD_ID.find(head)?.groupValues?.get(1)?.let {
    try { (Json.parseToJsonElement(it) as? JsonPrimitive)?.contentOrNull } catch (_: IllegalArgumentException) { null }
}?.takeIf(CloudConversationRules::isPermissionRequestId)

/** Stored JSON text (an event's data, a permission answer) as an object; null when absent or not an object. */
private fun jsonObject(text: String?): JsonObject? = text?.let { try { Json.parseToJsonElement(it) as? JsonObject } catch (_: IllegalArgumentException) { null } }
private fun JsonObject?.str(name: String): String? = (this?.get(name) as? JsonPrimitive)?.contentOrNull

/** A stored `permission.request`: whether it is a question, and each question's option count when its input was kept. */
private fun permissionRequestRecord(requestId: String, data: JsonObject, state: PermissionRequestState): PermissionRequestRecord {
    val questions = ((data["input"] as? JsonObject)?.get("questions") as? JsonArray)?.map { question ->
        val shape = question as? JsonObject
        QuestionShape((shape?.get("options") as? JsonArray)?.size ?: 0, (shape?.get("multiSelect") as? JsonPrimitive)?.booleanOrNull == true,
            (shape?.get("question") as? JsonPrimitive)?.contentOrNull)
    }
    return PermissionRequestRecord(requestId, (data["question"] as? JsonPrimitive)?.booleanOrNull == true, questions, state,
        inputLeftOut = (data["inputTruncated"] as? JsonPrimitive)?.booleanOrNull == true || data["input"] !is JsonObject)
}
private fun readInput(row: ResultRow) = CloudInput(
    id = row[CloudSessionInputs.id], teamId = row[CloudSessionInputs.teamId], sessionId = row[CloudSessionInputs.sessionId],
    kind = CloudInputKind.parse(row[CloudSessionInputs.kind]) ?: CloudInputKind.MESSAGE,
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
