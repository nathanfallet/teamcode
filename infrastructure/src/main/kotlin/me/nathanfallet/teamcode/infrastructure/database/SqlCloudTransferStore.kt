package me.nathanfallet.teamcode.infrastructure.database

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.nathanfallet.teamcode.domain.cloud.*
import me.nathanfallet.teamcode.domain.team.*
import me.nathanfallet.teamcode.infrastructure.database.tables.*
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.core.statements.UpdateBuilder
import org.jetbrains.exposed.v1.jdbc.*

/**
 * Transfers in MySQL. Every write that concerns a session first locks the session row, so an offer, an acceptance,
 * a cancellation and the owner change are serialized with messages, attachments and the reconciler.
 */
class SqlCloudTransferStore(private val database: Database) : CloudTransferStore {
    private val conversations = SqlCloudConversationStore(database)

    override suspend fun find(teamId: String, id: String): CloudTransfer? = database.query {
        CloudTransfers.selectAll().where { (CloudTransfers.id eq id) and (CloudTransfers.teamId eq teamId) }.singleOrNull()?.let(::readTransfer)
    }

    override suspend fun byKey(teamId: String, initiatorId: String, key: String): CloudTransfer? = database.query {
        CloudTransfers.selectAll().where { (CloudTransfers.teamId eq teamId) and (CloudTransfers.initiatorId eq initiatorId) and (CloudTransfers.idempotencyKey eq key) }
            .singleOrNull()?.let(::readTransfer)
    }

    override suspend fun list(teamId: String, memberId: String, query: CloudTransferQuery): CloudTransferPage = database.query {
        val cursor = query.beforeId?.let { before ->
            CloudTransfers.select(CloudTransfers.createdAt).where { (CloudTransfers.id eq before) and (CloudTransfers.teamId eq teamId) }.singleOrNull()?.get(CloudTransfers.createdAt)
                ?: throw TeamFailure("invalid_cursor", 400, "Unknown transfer cursor.")
        }
        var condition: Op<Boolean> = CloudTransfers.teamId eq teamId
        if (!query.team) condition = condition and when (query.direction) {
            CloudTransferDirection.INCOMING -> CloudTransfers.toMemberId eq memberId
            CloudTransferDirection.OUTGOING -> CloudTransfers.fromMemberId eq memberId
            CloudTransferDirection.BOTH -> (CloudTransfers.toMemberId eq memberId) or (CloudTransfers.fromMemberId eq memberId)
        }
        if (query.states.isNotEmpty()) condition = condition and (CloudTransfers.state inList query.states.map { it.wire })
        query.sessionId?.let { condition = condition and (CloudTransfers.sessionId eq it) }
        if (cursor != null) condition = condition and ((CloudTransfers.createdAt less cursor) or ((CloudTransfers.createdAt eq cursor) and (CloudTransfers.id less query.beforeId!!)))
        val rows = CloudTransfers.selectAll().where { condition }
            .orderBy(CloudTransfers.createdAt to SortOrder.DESC, CloudTransfers.id to SortOrder.DESC).limit(query.limit + 1).map(::readTransfer)
        val page = rows.take(query.limit)
        CloudTransferPage(page, if (rows.size > query.limit) page.last().id else null)
    }

    override suspend fun open(transfer: CloudTransfer, actor: CloudActor, auditAction: String, session: SessionChange?): CloudTransferOutcome = database.query {
        requireCurrent(this, actor.principal)
        lockSession(transfer.sessionId)
        // Under the session lock: one request with this key creates, concurrent ones replay it.
        val key = transfer.idempotencyKey
        if (key != null) CloudTransfers.selectAll().where {
            (CloudTransfers.teamId eq transfer.teamId) and (CloudTransfers.initiatorId eq transfer.initiatedBy.initiatorId) and (CloudTransfers.idempotencyKey eq key)
        }.singleOrNull()?.let(::readTransfer)?.let { existing ->
            if (existing.requestDigest != transfer.requestDigest) throw TeamFailure("idempotency_conflict", 422, "This Idempotency-Key was already used with a different request.")
            return@query CloudTransferOutcome.Replayed(existing)
        }
        val now = nowMillis()
        val open = CloudTransfers.selectAll().where {
            (CloudTransfers.sessionId eq transfer.sessionId) and (CloudTransfers.state inList listOf(CloudTransferState.OFFERED.wire, CloudTransferState.ACCEPTED.wire))
        }.map(::readTransfer)
        for (other in open) {
            // An administrator's transfer replaces pending offers; anything else waits for the open transfer to end.
            if (transfer.state != CloudTransferState.ACCEPTED || other.state != CloudTransferState.OFFERED)
                throw TeamFailure("transfer_pending", 409, "The session already has an open transfer.")
            writeTransfer(other, other.copy(state = CloudTransferState.CANCELLED, decidedBy = transfer.initiatedBy, decidedAt = now, updatedAt = now, version = other.version + 1,
                failure = CloudProblem("superseded", "An administrator transferred the session.")))
            audit(other.teamId, actor.principal.member.id, "cloud.transfer.cancelled", other.id, actor.effectiveMemberId, actor.principal.tokenId)
        }
        if (session != null && !commitSession(session, now)) throw TeamFailure("session_busy", 409, "The cloud session changed concurrently. Retry the request.")
        val stored = transfer.copy(createdAt = now, updatedAt = now, decidedAt = transfer.decidedAt?.let { now },
            expiresAt = transfer.expiresAt?.let { now + (it - transfer.createdAt) })
        CloudTransfers.insert {
            it[id] = stored.id; it[teamId] = stored.teamId; it[sessionId] = stored.sessionId; it[createdAt] = now
            it[initiatorId] = stored.initiatedBy.initiatorId; it[credentialId] = stored.initiatedBy.credentialId; it[effectiveMemberId] = stored.initiatedBy.effectiveMemberId
            it[idempotencyKey] = stored.idempotencyKey; it[requestDigest] = stored.requestDigest
            writeMutable(it, stored)
        }
        audit(stored.teamId, actor.principal.member.id, auditAction, stored.id, actor.effectiveMemberId, actor.principal.tokenId)
        CloudTransferOutcome.Created(stored)
    }

    override suspend fun update(expected: CloudTransfer, next: CloudTransfer, actor: CloudActor?, auditAction: String?, session: SessionChange?): CloudTransfer? = database.query {
        actor?.let { requireCurrent(this, it.principal) }
        lockSession(expected.sessionId)
        val now = nowMillis()
        val current = CloudTransfers.select(CloudTransfers.version).where { CloudTransfers.id eq expected.id }.forUpdate().singleOrNull()?.get(CloudTransfers.version)
        if (current != expected.version) return@query null
        if (session != null && !commitSession(session, now)) return@query null
        // Times come from the database clock, like every other cloud write.
        val stored = next.copy(updatedAt = now, decidedAt = if (next.decidedAt != expected.decidedAt) now else next.decidedAt)
        writeTransfer(expected, stored)
        auditAction?.let { action ->
            if (actor != null) audit(expected.teamId, actor.principal.member.id, action, expected.id, actor.effectiveMemberId, actor.principal.tokenId)
            else audit(expected.teamId, "system", action, expected.id, expected.toMemberId, null, expected.fromMemberId)
        }
        stored
    }

    override suspend fun complete(expected: CloudSession, next: CloudSession, transfer: CloudTransfer, leaseOwner: String, retainedLimit: Int): TransferCompletion = database.query {
        require(next.ownerId == transfer.toMemberId && expected.ownerId == transfer.fromMemberId && next.id == transfer.sessionId) { "A completion moves the session to the transfer's recipient." }
        CloudStateMachine.require(expected.state, next.state)
        // Lock order: the session row, then the recipient's member row (as creation locks members before inserting).
        lockSession(expected.id)
        val recipient = Members.selectAll().where { (Members.id eq transfer.toMemberId) and (Members.teamId eq expected.teamId) }.forUpdate().singleOrNull()
        if (recipient == null || recipient[Members.disabled] || recipient[Members.mustChangePassword]) return@query TransferCompletion.RecipientUnavailable
        val retained = CloudSessions.select(CloudSessions.id).where {
            (CloudSessions.teamId eq expected.teamId) and (CloudSessions.ownerId eq transfer.toMemberId) and
                (CloudSessions.state notInList listOf(CloudSessionState.TERMINATED.wire, CloudSessionState.FAILED.wire))
        }.count()
        if (retained >= retainedLimit) return@query TransferCompletion.RecipientAtLimit
        val version = CloudTransfers.select(CloudTransfers.version, CloudTransfers.state).where { CloudTransfers.id eq transfer.id }.forUpdate().singleOrNull()
        if (version == null || version[CloudTransfers.version] != transfer.version || version[CloudTransfers.state] != CloudTransferState.ACCEPTED.wire) return@query TransferCompletion.Conflict
        val now = nowMillis()
        val updated = CloudSessions.update({ sessionGuard(expected, leaseOwner, now) }) {
            writeSessionMutable(it, next.copy(generation = expected.generation + 1))
            it[ownerId] = next.ownerId
        }
        if (updated != 1) return@query TransferCompletion.Conflict

        // Former-owner access ends here: attachment leases go (a renewal then fails and the CLI detaches), and the
        // session's own routes now authorize against the recipient.
        CloudSessionAttachments.deleteWhere { CloudSessionAttachments.sessionId eq expected.id }
        // The pause already revoked the former owner's inference credentials with their execution; nothing issued
        // for them may survive the change of owner.
        revokeInferenceCredentials(expected.id)
        // The former owner's artifacts from the session stay theirs; the recipient becomes an editor of each, to continue them.
        grantSessionArtifacts(expected.teamId, expected.id, transfer.fromMemberId, transfer.toMemberId, transfer.initiatedBy.initiatorId, now)
        val event = buildJsonObject {
            put("transferId", transfer.id); put("fromMemberId", transfer.fromMemberId); put("toMemberId", transfer.toMemberId); put("atEpoch", expected.currentEpoch)
        }
        val seq = conversations.serverEvent(expected.id, expected.teamId, CloudEventType.TRANSFER, event.toString(), now)
        with(conversations) {
            for (input in conversations.pendingInputs(expected.id)) {
                if (transfer.pendingInputs == TransferPendingInputs.CANCEL) finish(expected.teamId, input, CloudInputStatus.CANCELLED, "transferred", now)
                // Kept inputs go to the recipient's next execution: requeued, attempts preserved.
                else if (input.status == CloudInputStatus.DELIVERED) CloudSessionInputs.update({ CloudSessionInputs.id eq input.id }) { it[status] = CloudInputStatus.QUEUED.wire }
            }
        }
        // A former owner's message never wakes the recipient's session.
        CloudSessionInputs.update({ (CloudSessionInputs.sessionId eq expected.id) and (CloudSessionInputs.wakePending eq true) }) { it[wakePending] = false }

        val done = transfer.copy(state = CloudTransferState.COMPLETED, completedAt = now, updatedAt = now, atEpoch = expected.currentEpoch,
            eventSeq = seq, version = transfer.version + 1)
        writeTransfer(transfer, done)
        val initiator = transfer.initiatedBy
        audit(expected.teamId, initiator.initiatorId, "cloud.session.transferred", expected.id, transfer.toMemberId, initiator.credentialId, transfer.fromMemberId)
        TransferCompletion.Completed(done, next.copy(generation = expected.generation + 1))
    }

    override suspend fun expireOffers(limit: Int): Int = database.query {
        val now = nowMillis()
        val due = CloudTransfers.selectAll().where {
            (CloudTransfers.state eq CloudTransferState.OFFERED.wire) and (CloudTransfers.expiresAt lessEq now)
        }.limit(limit).map(::readTransfer)
        due.count { transfer ->
            CloudTransfers.update({ (CloudTransfers.id eq transfer.id) and (CloudTransfers.version eq transfer.version) }) {
                it[state] = CloudTransferState.EXPIRED.wire; it[updatedAt] = now; it[version] = transfer.version + 1
            }.also { if (it == 1) audit(transfer.teamId, "system", "cloud.transfer.expired", transfer.id, transfer.toMemberId, null, transfer.fromMemberId) } == 1
        }
    }

    override suspend fun maintain() = database.query {
        val cutoff = nowMillis() - SqlCloudSessionStore.IDEMPOTENCY_MILLIS
        val stale = CloudTransfers.select(CloudTransfers.id).where { CloudTransfers.idempotencyKey.isNotNull() and (CloudTransfers.createdAt less cutoff) }
            .limit(500).map { it[CloudTransfers.id] }
        if (stale.isNotEmpty()) CloudTransfers.update({ CloudTransfers.id inList stale }) { it[idempotencyKey] = null; it[requestDigest] = null }
        Unit
    }

    /** The session compare-and-set of a transfer write; the caller holds the session row lock. */
    private fun commitSession(change: SessionChange, now: Long): Boolean {
        val updated = CloudSessions.update({ sessionGuard(change.expected, change.leaseOwner, now) }) {
            writeSessionMutable(it, change.next.copy(generation = change.expected.generation + 1))
            // A route's write asks for a pass now; a reconciler's own write is scheduled by its lease release.
            if (change.leaseOwner == null) { it[reconcileAfter] = now; it[reconcileRequested] = true }
        }
        if (updated == 1) { revokeInferenceCredentialsOnWrite(change.expected, change.next, null); publishSessionChange(change.expected, change.next, null, now) }
        return updated == 1
    }

    private fun writeTransfer(expected: CloudTransfer, next: CloudTransfer) {
        CloudTransfers.update({ (CloudTransfers.id eq expected.id) and (CloudTransfers.version eq expected.version) }) { writeMutable(it, next) }
    }

    private fun writeMutable(it: UpdateBuilder<*>, t: CloudTransfer) {
        it[CloudTransfers.fromMemberId] = t.fromMemberId; it[CloudTransfers.toMemberId] = t.toMemberId; it[CloudTransfers.state] = t.state.wire
        it[CloudTransfers.resume] = t.resume; it[CloudTransfers.force] = t.force; it[CloudTransfers.pendingInputs] = t.pendingInputs.wire
        it[CloudTransfers.version] = t.version; it[CloudTransfers.updatedAt] = t.updatedAt; it[CloudTransfers.expiresAt] = t.expiresAt
        it[CloudTransfers.deciderId] = t.decidedBy?.initiatorId; it[CloudTransfers.deciderCredentialId] = t.decidedBy?.credentialId
        it[CloudTransfers.deciderEffectiveMemberId] = t.decidedBy?.effectiveMemberId; it[CloudTransfers.decidedAt] = t.decidedAt
        it[CloudTransfers.completedAt] = t.completedAt; it[CloudTransfers.atEpoch] = t.atEpoch; it[CloudTransfers.eventSeq] = t.eventSeq
        it[CloudTransfers.failureCode] = t.failure?.code; it[CloudTransfers.failureMessage] = t.failure?.message?.take(512)
    }

    private fun lockSession(sessionId: String) {
        CloudSessions.select(CloudSessions.id).where { CloudSessions.id eq sessionId }.forUpdate().singleOrNull()
            ?: throw TeamFailure("not_found", 404, "Cloud session not found.")
    }
}

private fun readTransfer(row: ResultRow) = CloudTransfer(
    id = row[CloudTransfers.id], teamId = row[CloudTransfers.teamId], sessionId = row[CloudTransfers.sessionId],
    fromMemberId = row[CloudTransfers.fromMemberId], toMemberId = row[CloudTransfers.toMemberId],
    initiatedBy = Attribution(row[CloudTransfers.initiatorId], row[CloudTransfers.credentialId], row[CloudTransfers.effectiveMemberId]),
    state = CloudTransferState.parse(row[CloudTransfers.state]) ?: CloudTransferState.FAILED,
    resume = row[CloudTransfers.resume], force = row[CloudTransfers.force],
    pendingInputs = TransferPendingInputs.parse(row[CloudTransfers.pendingInputs]) ?: TransferPendingInputs.CANCEL,
    version = row[CloudTransfers.version], createdAt = row[CloudTransfers.createdAt], updatedAt = row[CloudTransfers.updatedAt], expiresAt = row[CloudTransfers.expiresAt],
    decidedBy = row[CloudTransfers.deciderId]?.let { Attribution(it, row[CloudTransfers.deciderCredentialId].orEmpty(), row[CloudTransfers.deciderEffectiveMemberId] ?: it) },
    decidedAt = row[CloudTransfers.decidedAt], completedAt = row[CloudTransfers.completedAt], atEpoch = row[CloudTransfers.atEpoch], eventSeq = row[CloudTransfers.eventSeq],
    failure = row[CloudTransfers.failureCode]?.let { CloudProblem(it, row[CloudTransfers.failureMessage].orEmpty()) },
    idempotencyKey = row[CloudTransfers.idempotencyKey], requestDigest = row[CloudTransfers.requestDigest],
)

/** The recipient's daily allowance, with the calculation admission uses. */
class SqlMemberAllowance(private val database: Database) : MemberAllowanceSource {
    override suspend fun dailyAllowance(teamId: String, memberId: String): Long = database.query {
        readMemberCapacity(this, readMemberPolicy(this, memberId), nowMillis(), teamId).dailyAllowanceUnits
    }
}
