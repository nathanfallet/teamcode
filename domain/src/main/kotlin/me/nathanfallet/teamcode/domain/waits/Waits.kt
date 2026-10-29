package me.nathanfallet.teamcode.domain.waits

import kotlinx.serialization.json.JsonElement
import me.nathanfallet.teamcode.domain.team.Member
import me.nathanfallet.teamcode.domain.team.Principal

enum class WaitKind(val wire: String) {
    PERMISSION("permission"), QUESTION("question"), HUMAN_REQUEST("human_request"), TURN_FAILED("turn_failed");
    companion object { fun parse(value: String) = entries.singleOrNull { it.wire == value } }
}
enum class WaitResolver(val wire: String) {
    HUMAN("human"), TIME("time"), EVENT("event");
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
data class WaitDefinition(
    val sessionId: String, val title: String, val body: String? = null, val assigneeId: String? = null,
    val refs: List<WaitRef> = emptyList(), val fields: List<WaitField> = emptyList(), val actions: List<WaitAction> = emptyList(),
    val resolver: WaitResolver = WaitResolver.HUMAN,
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
)

/** The part of a cloud session a wait needs: who owns it and what to call it. */
data class WaitSession(val id: String, val teamId: String, val ownerId: String, val name: String?)

data class WaitPage(val waits: List<Wait>, val nextBefore: String?)

/** One of the page's reads: [assignee] null for the whole team, [status] null for any. */
/** [sessionId]: only the waits of that session, whoever they are asked of (then no session request is added). */
data class WaitQuery(val assignee: String?, val status: WaitStatus?, val before: String?, val limit: Int, val sessionId: String? = null)

/** Result of the store's atomic create, answer or cancel: the wait as it is after, and whether this call changed it. */
data class WaitChange(val wait: Wait, val changed: Boolean)

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
