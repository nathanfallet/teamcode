package me.nathanfallet.teamcode.domain.waits

import kotlinx.serialization.json.JsonElement
import me.nathanfallet.teamcode.domain.team.Member
import me.nathanfallet.teamcode.domain.team.Principal

enum class WaitKind(val wire: String) {
    PERMISSION("permission"), QUESTION("question"), HUMAN_REQUEST("human_request"), TURN_FAILED("turn_failed"),
    /** A question a workflow run asks at a human node: its answer moves the run on, it does not wake the session. */
    WORKFLOW("workflow"),
    /** A wait for a time or an event: the server resolves it and wakes the session; nobody is asked. */
    TIMED("timed");
    companion object { fun parse(value: String) = entries.singleOrNull { it.wire == value } }
}
enum class WaitResolver(val wire: String) {
    HUMAN("human"), TIME("time"), EVENT("event");
    companion object { fun parse(value: String) = entries.singleOrNull { it.wire == value } }
}
enum class EventSource(val wire: String) {
    WEBHOOK("webhook");
    companion object { fun parse(value: String) = entries.singleOrNull { it.wire == value } }
}
enum class TimeoutAction(val wire: String) {
    WAKE("wake"), TURN_FAILED("turn_failed");
    companion object { fun parse(value: String) = entries.singleOrNull { it.wire == value } }
}
enum class WaitStatus(val wire: String) {
    OPEN("open"), RESOLVED("resolved"), CANCELLED("cancelled");
    companion object { fun parse(value: String) = entries.singleOrNull { it.wire == value } }
}
enum class WaitSource(val wire: String) { WAIT("wait"), SESSION("session") }
enum class FieldType(val wire: String) {
    TEXT("text"), CHOICE("choice"), CONFIRM("confirm"), NUMBER("number"), DATE("date"), SECRET("secret"), FILE("file"), INFO("info");
    companion object { fun parse(value: String) = entries.singleOrNull { it.wire == value } }
}

data class WaitOption(val value: String, val label: String, val description: String? = null)
data class WaitField(
    val id: String, val type: FieldType, val label: String, val help: String? = null, val required: Boolean = false,
    val default: JsonElement? = null, val options: List<WaitOption> = emptyList(), val multiple: Boolean = false,
    val allowOther: Boolean = false, val maxLength: Int? = null,
)
data class WaitAction(val id: String, val label: String, val requires: List<String> = emptyList(), val style: String? = null)
data class WaitRef(val kind: String, val id: String? = null, val url: String? = null, val title: String? = null)
data class WaitAnswer(val action: String, val values: Map<String, JsonElement>, val authorId: String, val answeredAt: Long)

/** What a member or an agent asks for; [WaitService.create] checks it and the store assigns the identity. */
/**
 * What a request says about a wait that resolves by itself: the instant ([at], epoch milliseconds, or [afterSeconds] from now) of
 * a `time` wait, or the [source], [timeoutSeconds] and [onTimeout] of an `event` wait. [resumeMessage] joins the wake message.
 */
data class WaitSchedule(
    val at: Long? = null, val afterSeconds: Long? = null, val source: EventSource? = null, val timeoutSeconds: Long? = null,
    val onTimeout: TimeoutAction? = null, val resumeMessage: String? = null,
)

/**
 * What a stored wait that resolves by itself carries: [dueAt] is the instant of a `time` wait, or the timeout of an `event`
 * wait. [tokenHash] (SHA-256, hex) of an event wait's secret never leaves the server.
 */
data class WaitTimer(
    val dueAt: Long, val eventSource: EventSource? = null, val onTimeout: TimeoutAction? = null, val resumeMessage: String? = null,
    val tokenHash: String? = null,
)

data class WaitDefinition(
    val sessionId: String, val title: String, val body: String? = null, val assigneeId: String? = null,
    val refs: List<WaitRef> = emptyList(), val fields: List<WaitField> = emptyList(), val actions: List<WaitAction> = emptyList(),
    val resolver: WaitResolver = WaitResolver.HUMAN,
    /** Set by the server for its own waits; a request makes a `human_request`, or a `timed` wait for a time or an event. */
    val kind: WaitKind = WaitKind.HUMAN_REQUEST,
    val schedule: WaitSchedule? = null,
)

/**
 * A wait. [source] `SESSION` is a cloud session's open permission request or question seen as a wait: it has no row of
 * its own, [requestId] names it, and it is answered through the session's permission route.
 */
data class Wait(
    val id: String, val teamId: String, val source: WaitSource, val kind: WaitKind, val resolver: WaitResolver, val status: WaitStatus,
    val sessionId: String?, val sessionName: String?, val requestId: String?, val assigneeId: String, val assigneeName: String?,
    val createdById: String?, val title: String, val body: String?, val refs: List<WaitRef>, val fields: List<WaitField>,
    val actions: List<WaitAction>, val answer: WaitAnswer?, val createdAt: Long, val resolvedAt: Long?,
    val timer: WaitTimer? = null,
)

/** The part of a cloud session a wait needs: who owns it and what to call it. */
data class WaitSession(val id: String, val teamId: String, val ownerId: String, val name: String?)

data class WaitPage(val waits: List<Wait>, val nextBefore: String?)

/** One of the page's reads: [assignee] null for the whole team, [status] null for any. */
/** [sessionId]: only the waits of that session, whoever they are asked of (then no session request is added). */
data class WaitQuery(
    val assignee: String?, val status: WaitStatus?, val before: String?, val limit: Int, val sessionId: String? = null,
    /** Only these resolvers; the human waits when the request names none (what "Waiting for you" shows). Null: every resolver. */
    val resolvers: Set<WaitResolver>? = setOf(WaitResolver.HUMAN),
)

/**
 * Result of the store's atomic create, answer or cancel: the wait as it is after, and whether this call changed it.
 * [secret] is the event wait's secret, set by the creation that made it and nowhere else.
 */
data class WaitChange(val wait: Wait, val changed: Boolean, val secret: String? = null)

/** What the server records when it resolves a wait itself: the system answer's [action] and [values], and when. */
data class WaitResolution(val action: String, val values: Map<String, JsonElement>, val at: Long)

/** An open wait that is due, as the scan reads it. */
data class DueWait(val id: String, val teamId: String, val resolver: WaitResolver, val onTimeout: TimeoutAction?)

/** What an event address needs to know before it trusts the caller: the stored hash of the secret, and whether the wait still listens. */
data class EventTarget(val id: String, val tokenHash: String?, val listening: Boolean)

/** Who or what closes a wait the server resolves: the clock (the wait must be due) or an event (the wait must be an open `event` wait). */
enum class ResolveCause { DUE, EVENT }

interface WaitStore {
    suspend fun now(): Long
    suspend fun revalidate(actor: Principal)
    suspend fun member(teamId: String, memberId: String): Member?
    /** The cloud session an execution credential (`tcex_`) was issued for, or null for any other credential. */
    suspend fun executionSession(tokenId: String): String?
    suspend fun session(teamId: String, sessionId: String): WaitSession?
    /**
     * Stores [wait] under the session's lock. The same [idempotencyKey] from the same member returns the first wait
     * (changed = false). Fails `409 wait_limit` past [limit] open waits for the session.
     */
    suspend fun create(actor: Principal, wait: Wait, idempotencyKey: String?, limit: Int): WaitChange
    suspend fun find(teamId: String, id: String): Wait?
    /**
     * In one transaction: stored waits by keyset (newest first), and, on the first page only (`before` null), the open
     * permission requests of the assignee's sessions, ahead of them. The latter exist only while open.
     */
    suspend fun list(teamId: String, query: WaitQuery): WaitPage
    /** Closes an open wait atomically; [WaitChange.changed] is false when it was not open (the current state is returned). Audited `wait.answered`. */
    suspend fun answer(actor: Principal, id: String, answer: WaitAnswer): WaitChange
    /** Same for a cancellation. Audited `wait.cancelled`. */
    suspend fun cancel(actor: Principal, id: String, at: Long): WaitChange

    /** The stored hash of an event wait's secret and whether it still listens; null for an unknown wait or one that waits for a person. */
    suspend fun eventTarget(id: String): EventTarget?
    /** Open waits whose instant has come (`status = open and due_at <= now`, database clock), oldest first, at most [limit]. */
    suspend fun dueWaits(limit: Int): List<DueWait>
    /**
     * Closes a wait the server resolves, in one transaction like an answer (status, system answer, audit, feed event), by one
     * conditional update: of two replicas, or of an event and a timeout, exactly one changes the row ([WaitChange.changed]).
     * [openTurnFailed] also opens the owner's `turn_failed` wait; [wake] marks the session's wake message as owed until
     * [wakeDelivered].
     */
    suspend fun resolve(id: String, cause: ResolveCause, resolution: WaitResolution, openTurnFailed: Boolean, wake: Boolean): WaitChange?
    /** Resolved waits whose wake message may not have been queued (a failure after [resolve]), oldest first. */
    suspend fun pendingWakes(limit: Int): List<Wait>
    suspend fun wakeDelivered(id: String)
}

/**
 * The cloud sessions waits talk back to: an answer is queued as a message of the session, which wakes it. Implemented
 * on the conversation service in the composition root; the domain of waits does not know how a session is reached.
 */
interface WaitSessions {
    /** Refuses before anything is stored when [actor] may not instruct the session (the owner-only rule of a session holding its owner's GitHub token). */
    suspend fun authorize(actor: Principal, sessionId: String)
    /** Queues [text] on the session as a message written by [actor] and wakes it; idempotent per [clientKey]. A session that has ended takes nothing. */
    suspend fun deliver(actor: Principal, sessionId: String, text: String, clientKey: String)
}
