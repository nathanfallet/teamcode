package me.nathanfallet.teamcode.domain.cloud

import java.util.UUID
import kotlinx.coroutines.delay
import me.nathanfallet.teamcode.domain.cloud.CloudSessionState.*
import me.nathanfallet.teamcode.domain.team.*

/** A message or interrupt as sent by a member. [clientKey] is the `Idempotency-Key` header or `clientMessageId`. */
data class InputRequest(val text: String?, val wake: Boolean, val clientKey: String?, val requestDigest: String?, val expectedLastEventSeq: Long?)

/**
 * Transcript reads, messages, interrupts and attachments for members. Each call is authorized with the caller's own
 * rights through [CloudAccessService]; a session the caller can neither see nor reach through the policy is `404`.
 */
class CloudConversationService(
    private val store: CloudConversationStore,
    private val sessions: CloudSessionStore,
    private val access: CloudAccessService,
    private val transition: TransitionCloudSessionUseCase = TransitionCloudSessionUseCase(sessions),
    private val sleep: suspend (Long) -> Unit = { delay(it) },
) {
    suspend fun events(principal: Principal, id: String, after: Long?, before: Long?, limit: Int): CloudEventPage {
        CloudConversationRules.validatePage(limit, after, before)
        val session = open(principal, id, CloudPermission.TRANSCRIPT)
        return store.events(session.id, after, before, limit)
    }

    /**
     * Follows the transcript from [after]: [emit] receives each new batch, [idle] runs when nothing arrived for a
     * while (the route sends a keep-alive). The caller is revalidated and reauthorized every 15 s, so a revoked
     * credential or a changed policy ends the stream. Returns once the session has ended and every event was sent.
     */
    suspend fun follow(principal: Principal, id: String, after: Long, emit: suspend (List<CloudEvent>) -> Unit, idle: suspend () -> Unit) {
        var cursor = after
        var session = open(principal, id, CloudPermission.TRANSCRIPT)
        var checkedAt = sessions.now()
        var quiet = 0L
        while (true) {
            // One indexed read per poll; the page is read only when something new arrived.
            val page = if (store.lastSeq(session.id) > cursor) store.events(session.id, cursor, null, CloudConversationRules.MAX_PAGE)
                else CloudEventPage(emptyList(), null, cursor, null, null)
            if (page.events.isNotEmpty()) {
                emit(page.events)
                cursor = page.events.last().seq
                quiet = 0
                if (page.nextAfter != null) continue
            } else if (session.state == TERMINATED || session.state == FAILED) return
            else if (quiet >= KEEPALIVE_MILLIS) { idle(); quiet = 0 }
            sleep(POLL_MILLIS)
            quiet += POLL_MILLIS
            val now = sessions.now()
            if (now - checkedAt >= RECHECK_MILLIS) {
                session = open(principal, id, CloudPermission.TRANSCRIPT)
                checkedAt = now
            }
        }
    }

    suspend fun send(principal: Principal, id: String, request: InputRequest): InputOutcome {
        val text = request.text ?: throw TeamFailure("invalid_message", 400, "Send a message text.")
        CloudConversationRules.validateMessage(text)
        val session = open(principal, id, CloudPermission.MESSAGE)
        val pausing = session.state == PAUSING
        val input = newInput(principal, session, CloudInputKind.MESSAGE, text, request)
        return when (val outcome = store.insertInput(NewInput(input.copy(wake = request.wake), request.expectedLastEventSeq, session.ownerId), CloudConversationRules.MAX_PENDING_INPUTS)) {
            is InputOutcome.Replayed -> outcome
            is InputOutcome.Accepted -> if (request.wake && !pausing) InputOutcome.Accepted(outcome.input.copy(woke = wake(principal.teamId, session.id, principal))) else outcome
        }
    }

    /** Stops the current agent turn. Interrupts are delivered before queued messages and never wake a session. */
    suspend fun interrupt(principal: Principal, id: String, request: InputRequest): InputOutcome {
        val session = open(principal, id, CloudPermission.MESSAGE)
        if (session.state != READY && session.state != ACTIVE)
            throw TeamFailure("nothing_to_interrupt", 409, "The session has no running agent to interrupt.")
        val input = newInput(principal, session, CloudInputKind.INTERRUPT, null, request.copy(wake = false))
        return store.insertInput(NewInput(input, null, session.ownerId), CloudConversationRules.MAX_PENDING_INPUTS)
    }

    suspend fun inputs(principal: Principal, id: String, beforeId: String?, limit: Int): CloudInputPage {
        if (limit !in 1..CloudConversationRules.MAX_PAGE) throw TeamFailure("invalid_limit", 400, "Use a page size between 1 and ${CloudConversationRules.MAX_PAGE}.")
        val session = open(principal, id, CloudPermission.TRANSCRIPT)
        return store.inputs(session.id, beforeId, limit)
    }

    /** The author of an input may always read its acknowledgement; others need the transcript permission. */
    suspend fun input(principal: Principal, id: String, inputId: String): CloudInput {
        val (session, granted) = reach(principal, id)
        val input = store.input(session.id, inputId) ?: throw TeamFailure("not_found", 404, "Input not found.")
        if (input.authorId != principal.member.id && CloudPermission.TRANSCRIPT !in granted)
            throw TeamFailure("not_found", 404, "Input not found.")
        return input
    }

    /** Opens an attachment lease; requires `attach` and `transcript`. The lease lists whether the member may also send messages. */
    suspend fun attach(principal: Principal, id: String): CloudAttachment {
        val (session, granted) = reach(principal, id)
        if (CloudPermission.ATTACH !in granted || CloudPermission.TRANSCRIPT !in granted) throw denied(CloudPermission.ATTACH)
        if (session.state.terminal || session.desiredState == DesiredState.TERMINATED) throw ended()
        val now = store.now()
        return store.attach(CloudAttachment(UUID.randomUUID().toString(), principal.teamId, session.id, principal.member.id, principal.tokenId,
            granted.intersect(setOf(CloudPermission.TRANSCRIPT, CloudPermission.MESSAGE)), now, now + CloudConversationRules.ATTACHMENT_LEASE_MILLIS), session.ownerId)
    }

    suspend fun renew(principal: Principal, id: String, attachmentId: String): CloudAttachment {
        val (session, granted) = reach(principal, id)
        if (CloudPermission.ATTACH !in granted) throw denied(CloudPermission.ATTACH)
        return store.renew(session.id, attachmentId, principal.member.id, store.now() + CloudConversationRules.ATTACHMENT_LEASE_MILLIS)
            ?: throw TeamFailure("attachment_expired", 409, "The attachment expired or was released. Attach again.")
    }

    suspend fun detach(principal: Principal, id: String, attachmentId: String) {
        val (session, _) = reach(principal, id)
        store.detach(session.id, attachmentId, principal.member.id)
    }

    /**
     * Wakes a session that waits for a message sent while it was pausing, once it is paused. Run by the server's
     * maintenance worker; failures leave the message queued for the next explicit resume.
     */
    suspend fun wakePending(limit: Int = 50): Int {
        var woken = 0
        for ((teamId, sessionId) in store.pendingWakes(limit)) {
            val session = sessions.find(teamId, sessionId)
            if (session == null || session.state.terminal || session.desiredState == DesiredState.TERMINATED) { store.clearWake(sessionId); continue }
            // The completed transfer clears wake requests: a former owner's message never resumes the recipient's session.
            if (session.state == PAUSING || session.state == TRANSFERRING || session.transferId != null) continue
            if (wake(teamId, session.id, null)) woken++
            store.clearWake(sessionId)
        }
        return woken
    }

    /**
     * Sets the desired state to running for a paused (or resume-blocked) session after the same rechecks as an
     * explicit resume. Returns whether this call woke it. The message stays queued whatever the outcome.
     */
    private suspend fun wake(teamId: String, sessionId: String, principal: Principal?): Boolean {
        val session = sessions.find(teamId, sessionId) ?: return false
        if (session.state.terminal || session.desiredState == DesiredState.TERMINATED) return false
        if (session.desiredState == DesiredState.RUNNING && session.resumeBlocked == null) return false
        // A session moving to another owner stays paused; the message waits for the transfer's own policy.
        if (session.transferId != null || session.state == TRANSFERRING) return false
        val owner = sessions.member(teamId, session.ownerId)
        if (owner == null || owner.disabled || owner.passwordChangeRequired) return false
        val profile = sessions.profile(teamId, session.profileId)
        if (profile == null || (owner.role != Role.ADMIN && owner.id !in profile.assignedMemberIds)) return false
        var woke = false
        transition(teamId, sessionId) { current ->
            if (current.state.terminal || current.state == PAUSING || current.desiredState == DesiredState.TERMINATED) return@transition null
            if (current.transferId != null || current.state == TRANSFERRING) return@transition null
            if (current.desiredState == DesiredState.RUNNING && current.resumeBlocked == null) return@transition null
            val now = sessions.now()
            woke = true
            CloudTransition(
                current.copy(desiredState = DesiredState.RUNNING, resumeBlocked = null, pauseRequestedAt = null, pauseForce = false, updatedAt = now),
                CloudWrite(actor = principal?.let { CloudActor(it, it.member.id) }, auditAction = "cloud.session.woken", reconcileAt = now),
            )
        }
        if (woke) store.clearWake(sessionId)
        return woke
    }

    private suspend fun newInput(principal: Principal, session: CloudSession, kind: CloudInputKind, text: String?, request: InputRequest): CloudInput {
        if (session.state.terminal || session.desiredState == DesiredState.TERMINATED) throw ended()
        return CloudInput(
            id = UUID.randomUUID().toString(), teamId = principal.teamId, sessionId = session.id, kind = kind, status = CloudInputStatus.QUEUED,
            authorId = principal.member.id, credentialId = principal.tokenId, text = text, wake = request.wake, clientKey = request.clientKey,
            requestDigest = request.requestDigest, createdAt = store.now(),
        )
    }

    /** The session and the caller's permissions on it; `404` when the caller has no way to reach it. */
    private suspend fun reach(principal: Principal, id: String): Pair<CloudSession, Set<CloudPermission>> {
        principal.requireReady(); principal.requireInteractive()
        sessions.revalidate(principal)
        val session = sessions.find(principal.teamId, id) ?: throw missing()
        val granted = access.permissions(principal.teamId, principal.member, session.ownerId)
        val owner = session.ownerId == principal.member.id
        if (!owner && principal.member.role != Role.ADMIN && (granted - CloudPermission.CREATE).isEmpty()) throw missing()
        return session to granted
    }

    private suspend fun open(principal: Principal, id: String, permission: CloudPermission): CloudSession {
        val (session, granted) = reach(principal, id)
        if (permission !in granted) throw denied(permission)
        return session
    }

    companion object {
        const val POLL_MILLIS = 1_000L
        const val KEEPALIVE_MILLIS = 15_000L
        const val RECHECK_MILLIS = 15_000L
        private fun missing() = TeamFailure("not_found", 404, "Cloud session not found.")
        private fun ended() = TeamFailure("session_not_running", 409, "The cloud session is terminated or being deleted.")
        private fun denied(permission: CloudPermission) =
            TeamFailure("cloud_permission_denied", 403, "The team's cloud access policy does not allow ${permission.wire} on this session.")
    }
}
