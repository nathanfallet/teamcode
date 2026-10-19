package me.nathanfallet.teamcode.domain.cloud

import java.util.UUID
import me.nathanfallet.teamcode.domain.cloud.CloudSessionState.*
import me.nathanfallet.teamcode.domain.team.*

/**
 * Ownership transfers. Routes write here; the reconciler changes the owner once the session reached its paused
 * boundary ([CompleteCloudTransferUseCase]).
 *
 * - An administrator transfers any team session at once: the transfer starts `accepted`.
 * - An owner who is not an administrator offers their own session, when the team allows it; the recipient (or an
 *   administrator) accepts within the offer's validity. Nobody can take a session they do not own.
 * - Accepting pauses the session (drain, checkpoint, pod gone); the owner changes only then.
 *
 * Authorization uses the caller's own rights; `TeamCode-On-Behalf-Of` only selects the effective member recorded
 * with the transfer, as on the other cloud routes.
 */
class CloudTransferService(
    private val store: CloudSessionStore,
    private val transfers: CloudTransferStore,
    private val sessions: CloudSessionService,
    private val recipients: CloudRecipientPolicy,
) {
    suspend fun request(principal: Principal, onBehalfOf: String?, sessionId: String, request: CloudTransferRequest, idempotency: IdempotencyRequest): CloudTransferOutcome {
        val actor = sessions.actor(principal, onBehalfOf)
        store.revalidate(principal)
        if (!identifier.matches(request.toMemberId)) throw TeamFailure("invalid_transfer", 400, "Name the recipient by member identifier.")
        // A retry of a request that already succeeded replays it, whatever the session became since.
        transfers.byKey(principal.teamId, principal.member.id, idempotency.key)?.let { existing ->
            if (existing.requestDigest != idempotency.requestDigest)
                throw TeamFailure("idempotency_conflict", 422, "This Idempotency-Key was already used with a different request.")
            return CloudTransferOutcome.Replayed(existing)
        }
        val session = store.find(principal.teamId, sessionId)
        val admin = principal.member.role == Role.ADMIN
        if (session == null || (session.ownerId != principal.member.id && !admin)) throw missingSession()
        transferable(session)
        if (request.toMemberId == session.ownerId) throw TeamFailure("invalid_transfer", 400, "The recipient already owns this session.")
        val settings = store.settings(principal.teamId)
        if (!admin && settings.memberTransfers == MemberTransfers.DISABLED)
            throw TeamFailure("transfer_disabled", 403, "Your team does not allow members to transfer their sessions. Ask an administrator.")
        recipients.require(session, request.toMemberId)
        val now = store.now()
        val transfer = CloudTransfer(
            id = UUID.randomUUID().toString(), teamId = principal.teamId, sessionId = session.id, fromMemberId = session.ownerId, toMemberId = request.toMemberId,
            initiatedBy = actor.attribution, state = if (admin) CloudTransferState.ACCEPTED else CloudTransferState.OFFERED,
            resume = request.resume, force = request.force, pendingInputs = settings.transferPendingInputs, version = 1, createdAt = now, updatedAt = now,
            expiresAt = if (admin) null else now + settings.transferOfferDays * DAY_MILLIS,
            decidedBy = if (admin) actor.attribution else null, decidedAt = if (admin) now else null,
            idempotencyKey = idempotency.key, requestDigest = idempotency.requestDigest,
        )
        val change = if (admin) SessionChange(session, toward(session, transfer, now)) else null
        return transfers.open(transfer, actor, if (admin) "cloud.transfer.requested" else "cloud.transfer.offered", change)
    }

    /** The recipient (or an administrator) accepts an offer; the session then pauses and changes owner. Idempotent. */
    suspend fun accept(principal: Principal, onBehalfOf: String?, id: String, resume: Boolean): CloudTransfer {
        val actor = sessions.actor(principal, onBehalfOf)
        repeat(ATTEMPTS) {
            val transfer = visible(principal, id)
            if (transfer.toMemberId != principal.member.id && principal.member.role != Role.ADMIN)
                throw TeamFailure("transfer_forbidden", 403, "Only the recipient or an administrator can accept this transfer.")
            if (transfer.state == CloudTransferState.ACCEPTED || transfer.state == CloudTransferState.COMPLETED) return transfer
            val now = store.now()
            open(transfer, now)
            val session = store.find(transfer.teamId, transfer.sessionId) ?: throw ended()
            if (session.ownerId != transfer.fromMemberId) return close(transfer, actor, CloudTransferState.CANCELLED, "cloud.transfer.cancelled", now, CloudProblem("owner_changed", "The session changed owner since the offer."))
            if (session.state.terminal || session.desiredState == DesiredState.TERMINATED) return close(transfer, actor, CloudTransferState.CANCELLED, "cloud.transfer.cancelled", now, endedProblem)
            transferable(session, transfer.id)
            recipients.require(session, transfer.toMemberId)
            val accepted = transfer.copy(state = CloudTransferState.ACCEPTED, resume = resume, pendingInputs = store.settings(transfer.teamId).transferPendingInputs,
                decidedBy = actor.attribution, decidedAt = now, updatedAt = now, version = transfer.version + 1)
            transfers.update(transfer, accepted, actor, "cloud.transfer.accepted", SessionChange(session, toward(session, accepted, now)))?.let { return it }
        }
        throw busy()
    }

    suspend fun decline(principal: Principal, onBehalfOf: String?, id: String): CloudTransfer {
        val actor = sessions.actor(principal, onBehalfOf)
        repeat(ATTEMPTS) {
            val transfer = visible(principal, id)
            if (transfer.toMemberId != principal.member.id && principal.member.role != Role.ADMIN)
                throw TeamFailure("transfer_forbidden", 403, "Only the recipient or an administrator can decline this transfer.")
            if (transfer.state == CloudTransferState.DECLINED) return transfer
            val now = store.now()
            if (transfer.state != CloudTransferState.OFFERED) throw notOpen()
            open(transfer, now)
            transfers.update(transfer, decided(transfer, actor, CloudTransferState.DECLINED, now, null), actor, "cloud.transfer.declined")?.let { return it }
        }
        throw busy()
    }

    /**
     * The former owner withdraws an offer, or an administrator cancels any open transfer. An accepted transfer can be
     * cancelled until the owner change starts (`transferring`); the session stays paused or keeps pausing.
     */
    suspend fun cancel(principal: Principal, onBehalfOf: String?, id: String): CloudTransfer {
        val actor = sessions.actor(principal, onBehalfOf)
        repeat(ATTEMPTS) {
            val transfer = visible(principal, id)
            val admin = principal.member.role == Role.ADMIN
            if (transfer.fromMemberId != principal.member.id && !admin)
                throw TeamFailure("transfer_forbidden", 403, "Only the session owner or an administrator can cancel this transfer.")
            if (transfer.state == CloudTransferState.CANCELLED) return transfer
            if (!transfer.state.open) throw notOpen()
            // Accepted means the owner already gave the session away; only an administrator may stop it now.
            if (transfer.state == CloudTransferState.ACCEPTED && !admin)
                throw TeamFailure("transfer_forbidden", 403, "The transfer was accepted. Ask an administrator to cancel it.")
            val now = store.now()
            val change = if (transfer.state == CloudTransferState.ACCEPTED) {
                val session = store.find(transfer.teamId, transfer.sessionId)
                if (session?.transferId == transfer.id) {
                    if (session.state == TRANSFERRING) throw TeamFailure("transfer_in_progress", 409, "The owner is being changed; the transfer can no longer be cancelled.")
                    SessionChange(session, session.copy(transferId = null, updatedAt = now))
                } else null
            } else null
            transfers.update(transfer, decided(transfer, actor, CloudTransferState.CANCELLED, now, null), actor, "cloud.transfer.cancelled", change)?.let { return it }
        }
        throw busy()
    }

    suspend fun get(principal: Principal, id: String): CloudTransfer = visible(principal, id)

    suspend fun list(principal: Principal, query: CloudTransferQuery): CloudTransferPage {
        principal.requireReady(); principal.requireInteractive()
        if (query.limit !in 1..50) throw TeamFailure("invalid_limit", 400, "Use a page size between 1 and 50.")
        if (query.team) principal.requireAdmin()
        store.revalidate(principal)
        return transfers.list(principal.teamId, principal.member.id, query)
    }

    /** Offers past their validity become `expired`; run by a maintenance worker. Reads also treat them as expired. */
    suspend fun expireOffers(limit: Int = 100): Int = transfers.expireOffers(limit)

    /** Visible to both members and administrators; anyone else gets `404`. */
    private suspend fun visible(principal: Principal, id: String): CloudTransfer {
        principal.requireReady(); principal.requireInteractive()
        store.revalidate(principal)
        if (!identifier.matches(id)) throw missingTransfer()
        val transfer = transfers.find(principal.teamId, id) ?: throw missingTransfer()
        val member = principal.member.id
        if (principal.member.role != Role.ADMIN && member != transfer.fromMemberId && member != transfer.toMemberId) throw missingTransfer()
        return transfer
    }

    /** An open transfer whose offer is past its validity is expired on the spot. */
    private suspend fun open(transfer: CloudTransfer, now: Long) {
        if (!transfer.state.open) throw notOpen()
        if (transfer.state == CloudTransferState.OFFERED && transfer.expiresAt != null && transfer.expiresAt <= now) {
            transfers.update(transfer, transfer.copy(state = CloudTransferState.EXPIRED, updatedAt = now, version = transfer.version + 1), null, "cloud.transfer.expired")
            throw TeamFailure("transfer_expired", 409, "The offer expired. Ask the owner to offer the session again.")
        }
    }

    private suspend fun close(transfer: CloudTransfer, actor: CloudActor, state: CloudTransferState, audit: String, now: Long, problem: CloudProblem): Nothing {
        transfers.update(transfer, decided(transfer, actor, state, now, problem), actor, audit)
        throw TeamFailure(problem.code, 409, problem.message)
    }

    private fun decided(transfer: CloudTransfer, actor: CloudActor, state: CloudTransferState, now: Long, problem: CloudProblem?) =
        transfer.copy(state = state, decidedBy = actor.attribution, decidedAt = now, failure = problem, updatedAt = now, version = transfer.version + 1)

    companion object {
        const val DAY_MILLIS = 86_400_000L
        private const val ATTEMPTS = 5
        private val identifier = Regex("[a-zA-Z0-9-]{1,36}")
        private val endedProblem = CloudProblem("session_not_running", "The cloud session is terminated or being deleted.")

        /** States a transfer may start from: anything but creation, preparation and the end of the session. */
        fun transferable(session: CloudSession, allowing: String? = null) {
            when {
                session.state.terminal || session.desiredState == DesiredState.TERMINATED -> throw ended()
                session.state == CREATING || session.state == PREPARING ->
                    throw TeamFailure("session_preparing", 409, "The session is still being prepared. Transfer it once it is ready.")
                session.state == TRANSFERRING || (session.transferId != null && session.transferId != allowing) ->
                    throw TeamFailure("transfer_pending", 409, "The session already has an accepted transfer.")
            }
        }

        /**
         * The session moving toward an accepted transfer: it is paused (drained, checkpointed, its pod deleted) unless
         * it already is, and keeps [CloudSession.transferId] until the reconciler changed the owner. A pending resume
         * is cancelled; a resume happens only after the change, for the recipient, when the transfer asks for it.
         */
        fun toward(session: CloudSession, transfer: CloudTransfer, now: Long): CloudSession {
            val alreadyPaused = session.state == PAUSED && session.desiredState == DesiredState.PAUSED
            return session.copy(
                transferId = transfer.id, desiredState = DesiredState.PAUSED, updatedAt = now,
                pauseRequestedAt = if (alreadyPaused) session.pauseRequestedAt else session.pauseRequestedAt ?: now,
                pauseForce = if (alreadyPaused) session.pauseForce else session.pauseForce || transfer.force,
            )
        }

        private fun missingSession() = TeamFailure("not_found", 404, "Cloud session not found.")
        private fun missingTransfer() = TeamFailure("not_found", 404, "Transfer not found.")
        private fun notOpen() = TeamFailure("transfer_not_open", 409, "The transfer is no longer open.")
        private fun ended() = TeamFailure("session_not_running", 409, "The cloud session is terminated or being deleted.")
        private fun busy() = TeamFailure("session_busy", 409, "The cloud session changed concurrently. Retry the request.")
    }
}
