package me.nathanfallet.teamcode.domain.waits

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import kotlinx.serialization.json.*
import me.nathanfallet.teamcode.domain.team.*

/**
 * Creation, reading, answers and cancellation of waits. Business rules live here; routes only decode and encode
 * (docs/waits.md). A wait is asked of its assignee (the session's owner unless the asker names another member); the
 * session stays its owner's. The assignee answers; an administrator may answer or cancel any wait of the team, and
 * the store audits it.
 *
 * Notifications on creation are a separate piece of work: the place to hook them is [create], after the store
 * returned a changed wait.
 */
class WaitService(private val store: WaitStore, private val sessions: WaitSessions? = null, private val limiter: EventRateLimit = EventRateLimit()) {
    private fun missing() = TeamFailure("not_found", 404, "Wait not found.")
    private fun closed() = TeamFailure("wait_closed", 409, "This wait was already answered or cancelled.")

    private fun Principal.administrator() = member.role.isAdministrator && serviceScopes == null

    private fun involved(actor: Principal, wait: Wait) =
        wait.assigneeId == actor.member.id || wait.createdById == actor.member.id || actor.canReadTeam

    /**
     * The session an execution credential is limited to, or null for any other caller. The credential holds
     * `waits:session` and is bound to the one session it was issued for; a key that carries the scope without being
     * bound to a session gets nothing.
     */
    private suspend fun confined(actor: Principal): String? {
        if (!actor.waitsSessionOnly) return null
        return store.executionSession(actor.tokenId) ?: throw TeamFailure("credential_scope", 403, "This credential is not bound to a cloud session.")
    }

    suspend fun get(actor: Principal, id: String): Wait {
        actor.requireWaitRead(); store.revalidate(actor)
        val only = confined(actor)
        val wait = store.find(actor.teamId, id)
        if (wait == null || !involved(actor, wait) || (only != null && wait.sessionId != only)) throw missing()
        return wait
    }

    /**
     * [assignee] null: the caller's; a member id or [ALL] for another member's or the team's (administrators).
     * [session]: only that session's waits, asked of anyone, to whoever may read the session's waits (its owner, who asked,
     * an administrator); an execution credential always gets its own session's, whatever it names.
     */
    suspend fun list(actor: Principal, assignee: String?, status: String, before: String?, limit: Int, session: String? = null, resolver: String? = null): WaitPage {
        actor.requireWaitRead(); store.revalidate(actor)
        // Without a resolver only the waits that wait for a person: "Waiting for you" and its badge never show a CI or a timer.
        val resolvers = when (resolver) {
            null -> setOf(WaitResolver.HUMAN)
            ALL -> null
            else -> setOf(WaitResolver.parse(resolver) ?: throw TeamFailure("invalid_request", 400, "Use resolver human, time, event or all."))
        }
        val only = confined(actor)
        if (limit !in 1..MAX_PAGE) throw TeamFailure("invalid_request", 400, "Use a page size between 1 and $MAX_PAGE.")
        val parsed = if (status == "all") null else WaitStatus.parse(status) ?: throw TeamFailure("invalid_request", 400, "Use open, resolved, cancelled or all.")
        if (only != null) return store.list(actor.teamId, WaitQuery(null, parsed, before, limit, only, resolvers))
        if (session != null) {
            val owner = store.session(actor.teamId, session)?.ownerId
            if (owner == null || (owner != actor.member.id && !actor.canReadTeam)) throw TeamFailure("not_found", 404, "Session not found.")
            return store.list(actor.teamId, WaitQuery(null, parsed, before, limit, session, resolvers))
        }
        val target = when {
            assignee == null || assignee == actor.member.id -> actor.member.id
            actor.canReadTeam -> assignee.takeUnless { it == ALL }
            else -> throw TeamFailure("forbidden", 403, "Only an administrator reads another member's waits.")
        }
        return store.list(actor.teamId, WaitQuery(target, parsed, before, limit, null, resolvers))
    }

    /** The session's owner asks (an agent acts with its owner's credential). */
    suspend fun create(actor: Principal, definition: WaitDefinition, idempotencyKey: String?): WaitChange {
        actor.requireWaitWrite(); store.revalidate(actor)
        val now = store.now()
        val valid = WaitRules.validate(definition, now)
        confined(actor)?.let { if (valid.sessionId != it) throw TeamFailure("not_found", 404, "Session not found.") }
        val session = store.session(actor.teamId, valid.sessionId)?.takeIf { it.ownerId == actor.member.id || actor.canReadTeam }
            ?: throw TeamFailure("not_found", 404, "Session not found.")
        if (session.ownerId != actor.member.id) throw TeamFailure("wait_session_owner_only", 403, "Only the session's owner asks for something on it.")
        // A wait for a time or an event asks nobody: the session's owner, whose session it wakes, is its assignee.
        if (valid.resolver != WaitResolver.HUMAN && valid.assigneeId != null && valid.assigneeId != session.ownerId)
            throw TeamFailure("invalid_assignee", 400, "A wait for a time or an event asks nobody: leave the assignee out.")
        val assignee = valid.assigneeId?.let { id ->
            store.member(actor.teamId, id)?.takeUnless { it.disabled } ?: throw TeamFailure("invalid_assignee", 400, "The assignee is not an active member.")
        } ?: store.member(actor.teamId, session.ownerId) ?: throw TeamFailure("invalid_assignee", 400, "The session's owner is not an active member.")
        val secret = if (valid.resolver == WaitResolver.EVENT) newSecret() else null
        val timer = WaitRules.dueAt(valid, now)?.let { due ->
            WaitTimer(due, valid.schedule?.source, valid.schedule?.onTimeout ?: if (valid.resolver == WaitResolver.EVENT) TimeoutAction.WAKE else null, valid.schedule?.resumeMessage, secret?.let(::hashSecret))
        }
        val wait = Wait(
            id = UUID.randomUUID().toString(), teamId = actor.teamId, source = WaitSource.WAIT, kind = valid.kind,
            resolver = valid.resolver, status = WaitStatus.OPEN, sessionId = session.id, sessionName = session.name, requestId = null,
            assigneeId = assignee.id, assigneeName = assignee.username, createdById = actor.member.id, title = valid.title, body = valid.body,
            refs = valid.refs, fields = valid.fields, actions = valid.actions, answer = null, createdAt = now, resolvedAt = null, timer = timer,
        )
        val change = store.create(actor, wait, idempotencyKey?.also { require(KEY.matches(it)) { "Use an idempotency key of 1 to 128 letters, digits or . _ : -." } }, WaitRules.MAX_OPEN_PER_SESSION)
        // The secret is shown by the call that made the wait, once; a replay of it has only the hash.
        return if (change.changed) change.copy(secret = secret) else change
    }

    /**
     * Answers a stored wait. A session's permission request is answered through the session (`cloud.permissions.v1`),
     * not here. The first answer wins: the store closes it atomically and a later one gets `409 wait_closed`.
     *
     * The answer wakes the session: it is queued there as a message ([WaitMessages]) once it is stored. That second
     * step is idempotent, so the same member sending the same answer again (after a failure of that step, which the
     * caller sees as an error) gets the wait back and the message queued, never a `wait_closed`.
     */
    suspend fun answer(actor: Principal, id: String, action: String, values: Map<String, kotlinx.serialization.json.JsonElement>): Wait {
        // An agent never answers its own wait: its owner is the default assignee, so the credential's scope is the guard.
        if (actor.waitsSessionOnly) throw TeamFailure("credential_scope", 403, "An execution credential does not answer waits.")
        actor.requireCloudWrite(); store.revalidate(actor)
        val wait = store.find(actor.teamId, id)?.takeIf { involved(actor, it) } ?: throw missing()
        if (wait.source != WaitSource.WAIT) throw TeamFailure("answer_through_session", 409, "Answer a permission request on its session.")
        if (wait.resolver != WaitResolver.HUMAN) throw TeamFailure("resolves_by_itself", 409, "This wait waits for a time or an event, not for a person: it cannot be answered. Cancel it instead.")
        if (wait.assigneeId != actor.member.id && !actor.administrator()) throw TeamFailure("wait_assignee_only", 403, "Only the person asked, or an administrator, answers.")
        if (wait.status != WaitStatus.OPEN) return repeated(actor, wait, action, values)
        WaitRules.checkAnswer(wait.fields, wait.actions, action, values)
        wait.sessionId?.let { sessions?.authorize(actor, it) }
        val change = store.answer(actor, id, WaitAnswer(action, values, actor.member.id, store.now()))
        if (!change.changed) return repeated(actor, change.wait, action, values)
        return wake(actor, change.wait)
    }

    /** The same member sending the same answer again: the message is queued again (idempotently); anything else is a lost race. */
    private suspend fun repeated(actor: Principal, wait: Wait, action: String, values: Map<String, kotlinx.serialization.json.JsonElement>): Wait {
        val given = wait.answer
        if (wait.status != WaitStatus.RESOLVED || given == null || given.authorId != actor.member.id || given.action != action || given.values != values) throw closed()
        return wake(actor, wait)
    }

    private suspend fun wake(actor: Principal, wait: Wait): Wait {
        val sessionId = wait.sessionId
        if (sessions != null && sessionId != null && TurnFailedWait.wakes(wait, wait.answer?.action.orEmpty())) sessions.deliver(actor, sessionId, WaitMessages.answered(wait, actor.member.username), WaitMessages.clientKey(wait.id))
        return wait
    }

    suspend fun cancel(actor: Principal, id: String): Wait {
        actor.requireWaitWrite(); store.revalidate(actor)
        val only = confined(actor)
        val wait = store.find(actor.teamId, id)?.takeIf { involved(actor, it) && (only == null || it.sessionId == only) } ?: throw missing()
        if (wait.source != WaitSource.WAIT) throw TeamFailure("cancel_through_session", 409, "Interrupt the session to withdraw a permission request.")
        if (wait.createdById != actor.member.id && wait.assigneeId != actor.member.id && !actor.administrator())
            throw TeamFailure("forbidden", 403, "Only who asked, the person asked or an administrator cancels a wait.")
        if (wait.status != WaitStatus.OPEN) throw closed()
        val change = store.cancel(actor, id, store.now())
        if (!change.changed) throw closed()
        return change.wait
    }

    // --- Waits that resolve by themselves -------------------------------------------------------------------------------------

    /** The wait's secret: 256 random bits, shown once in the event address. */
    private fun newSecret(): String = "wev_" + Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also { SecureRandom().nextBytes(it) })
    private fun hashSecret(secret: String): String = MessageDigest.getInstance("SHA-256").digest(secret.encodeToByteArray()).joinToString("") { "%02x".format(it) }

    /**
     * A caller reports the event an `event` wait waits for. The secret is the only proof: no credential, the same `404` for an
     * unknown wait, a wrong secret and a wait that is no longer listening. At most 30 calls a minute for a wait (per replica).
     * The first call wins against a timeout and against another call; the session is woken once.
     */
    suspend fun receiveEvent(waitId: String, secret: String?, body: ByteArray) {
        limiter.check(waitId)
        val missing = TeamFailure("not_found", 404, "Wait not found.")
        val target = store.eventTarget(waitId)
        // Hashed, then compared in constant time; an unknown wait is compared with a fixed hash so the work does not tell them apart.
        val presented = hashSecret(secret?.takeIf { it.length in 1..200 } ?: "")
        val matches = MessageDigest.isEqual(presented.encodeToByteArray(), (target?.tokenHash ?: NO_HASH).encodeToByteArray())
        if (target == null || target.tokenHash == null || !matches || !target.listening) throw missing
        if (body.size > WaitRules.MAX_EVENT_BYTES) throw TeamFailure("payload_too_large", 413, "The body is at most 16 KiB.")
        val event = parseEvent(body)
        val values = buildMap<String, JsonElement> {
            put("result", JsonPrimitive(event.result)); event.url?.let { put("url", JsonPrimitive(it)) }; event.note?.let { put("note", JsonPrimitive(it)) }
        }
        val change = store.resolve(waitId, ResolveCause.EVENT, WaitResolution(EVENT_RECEIVED, values, store.now()), openTurnFailed = false, wake = true) ?: throw missing
        if (!change.changed) throw missing
        deliver(change.wait)
    }

    private fun parseEvent(body: ByteArray): EventPayload {
        if (body.all { it.toInt().toChar().isWhitespace() }) return EventPayload("success", null, null)
        val json = try { Json.parseToJsonElement(body.decodeToString()) as? JsonObject } catch (_: Exception) { null }
            ?: throw TeamFailure("invalid_event", 400, "Post a JSON object like {\"result\": \"success\"}.")
        fun text(key: String): String? = (json[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?: if (json[key] == null || json[key] is JsonNull) null else throw TeamFailure("invalid_event", 400, "$key is a text.")
        val result = text("result") ?: "success"
        WaitRules.checkEvent(result, text("url"), text("note"))
        return EventPayload(result, text("url"), text("note"))
    }
    private class EventPayload(val result: String, val url: String?, val note: String?)

    /**
     * One pass of the scan the worker runs every few seconds: resolves the waits whose instant has come (a time reached, an
     * event wait that timed out) and wakes their sessions, then redelivers the wake messages a failure left owed. Each wait is one
     * conditional update, so replicas that scan together resolve and wake it once. No transaction spans the delivery.
     */
    suspend fun resolveDue(limit: Int = 50): Int {
        var resolved = 0
        for (due in store.dueWaits(limit)) {
            val timeout = due.resolver == WaitResolver.EVENT
            val action = if (timeout) TIMED_OUT else TIME_REACHED
            val turnFailed = timeout && due.onTimeout == TimeoutAction.TURN_FAILED
            val change = store.resolve(due.id, ResolveCause.DUE, WaitResolution(action, emptyMap(), store.now()), openTurnFailed = turnFailed, wake = !turnFailed) ?: continue
            if (!change.changed) continue
            resolved++
            if (!turnFailed) deliver(change.wait)
        }
        for (wait in store.pendingWakes(limit)) deliver(wait)
        return resolved
    }

    /** Queues the wake message of a wait the server resolved, as its session's owner would have written it, then clears the debt. Idempotent. */
    private suspend fun deliver(wait: Wait) {
        val sessionId = wait.sessionId ?: return
        if (sessions != null) {
            val owner = store.member(wait.teamId, wait.assigneeId)?.takeUnless { it.disabled }
            // An owner who can no longer be an author takes nothing: the wait stays recorded.
            if (owner != null) sessions.deliver(Principal(owner, SYSTEM_CREDENTIAL, wait.teamId), sessionId, WaitMessages.resolved(wait), WaitMessages.clientKey(wait.id))
        }
        store.wakeDelivered(wait.id)
    }

    companion object {
        const val TIME_REACHED = "time_reached"
        const val EVENT_RECEIVED = "event_received"
        const val TIMED_OUT = "timed_out"
        /** The id the owner's wake messages carry as their credential: nothing is signed in, the server writes them. */
        const val SYSTEM_CREDENTIAL = "waits-resolver"
        private val NO_HASH = "0".repeat(64)
        const val ALL = "all"
        const val MAX_PAGE = 100
        private val KEY = Regex("[A-Za-z0-9._:-]{1,128}")
    }
}
