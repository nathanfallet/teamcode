package me.nathanfallet.teamcode.domain.cloud

/** Normalized transcript event types; nothing else is stored. Thinking blocks have no type and never reach the server. */
enum class CloudEventType(val wire: String) {
    USER_MESSAGE("message.user"), AGENT_MESSAGE("message.agent"), TOOL_CALL("tool.call"), TOOL_RESULT("tool.result"),
    TURN("turn"), STATUS("status"), ERROR("error"),
    /** Written by the server when an input is consumed, cancelled or rejected. */
    INPUT("input");
    companion object {
        fun parse(value: String) = entries.singleOrNull { it.wire == value }
        /** Types a runner may upload; `input` events are the server's own. */
        val fromRunner = entries.toSet() - INPUT
    }
}

/** [data] is a bounded, secret-masked JSON object, encoded by the presentation layer. */
data class CloudEvent(val sessionId: String, val seq: Long, val type: CloudEventType, val at: Long, val epoch: Int, val data: String)
/** Lifecycle hints the presentation layer reads from an event: a turn starting or ending, the conversation's resumability. */
enum class TurnMarker { STARTED, ENDED }

/** An event from the runner: [sequence] is the runner's counter within its epoch, used to deduplicate retries. */
data class RunnerEvent(val sequence: Long, val type: CloudEventType, val data: String, val turn: TurnMarker? = null, val conversation: ConversationResume? = null)
data class CloudEventPage(val events: List<CloudEvent>, val firstSeq: Long?, val lastSeq: Long, val nextAfter: Long?, val nextBefore: Long?)

enum class CloudInputKind(val wire: String) { MESSAGE("message"), INTERRUPT("interrupt") }
enum class CloudInputStatus(val wire: String) {
    QUEUED("queued"), DELIVERED("delivered"), CONSUMED("consumed"), CANCELLED("cancelled"), REJECTED("rejected");
    val final get() = this == CONSUMED || this == CANCELLED || this == REJECTED
    companion object { fun parse(value: String) = entries.single { it.wire == value } }
}

data class CloudInput(
    val id: String,
    val teamId: String,
    val sessionId: String,
    val kind: CloudInputKind,
    val status: CloudInputStatus,
    val authorId: String,
    val credentialId: String,
    val text: String?,
    val wake: Boolean,
    val woke: Boolean = false,
    /** The idempotency key (header or `clientMessageId`), scoped to the session and author. */
    val clientKey: String?,
    val requestDigest: String?,
    val createdAt: Long,
    val deliveredAt: Long? = null,
    val deliveredEpoch: Int? = null,
    /** Deliveries so far; an input delivered to an execution that ended unconsumed is queued again, at most [CloudConversationRules.MAX_ATTEMPTS] times. */
    val attempts: Int = 0,
    val consumedAt: Long? = null,
    val finishedAt: Long? = null,
    val reason: String? = null,
    val eventSeq: Long? = null,
)
data class CloudInputPage(val inputs: List<CloudInput>, val nextBefore: String?)

/** What a new input needs from the store, all checked and written in one transaction locked on the session row. */
data class NewInput(
    val input: CloudInput,
    /** Refuse with `409 stale_view` when the session has events after this sequence. */
    val expectedLastEventSeq: Long?,
)
sealed interface InputOutcome {
    val input: CloudInput
    data class Accepted(override val input: CloudInput) : InputOutcome
    data class Replayed(override val input: CloudInput) : InputOutcome
}

data class CloudAttachment(
    val id: String,
    val teamId: String,
    val sessionId: String,
    val memberId: String,
    val credentialId: String,
    val permissions: Set<CloudPermission>,
    val createdAt: Long,
    val leaseUntil: Long,
)

enum class CloudPermission(val wire: String) {
    CREATE("create"), ATTACH("attach"), MESSAGE("message"), TRANSCRIPT("transcript");
    companion object { fun parse(value: String) = entries.singleOrNull { it.wire == value } }
}
enum class CloudAccessScope(val wire: String) {
    /** The member's own sessions, and creating sessions they will own. */
    OWN("own"),
    /** Every session of the team. `create` has no team scope: creating for someone else is an administrator delegation. */
    TEAM("team");
    companion object { fun parse(value: String) = entries.singleOrNull { it.wire == value } }
}
sealed interface CloudAccessSubject {
    val wire: String
    data object Everyone : CloudAccessSubject { override val wire = "everyone" }
    data class MemberSubject(val memberId: String) : CloudAccessSubject { override val wire = "member:$memberId" }
    data class GroupSubject(val groupId: String) : CloudAccessSubject { override val wire = "group:$groupId" }
    companion object {
        private val id = Regex("[a-zA-Z0-9-]{1,36}")
        fun parse(value: String): CloudAccessSubject? = when {
            value == "everyone" -> Everyone
            value.startsWith("member:") -> value.removePrefix("member:").takeIf(id::matches)?.let(::MemberSubject)
            value.startsWith("group:") -> value.removePrefix("group:").takeIf(id::matches)?.let(::GroupSubject)
            else -> null
        }
    }
}
data class CloudAccessRule(val subject: CloudAccessSubject, val permissions: Set<CloudPermission>, val scope: CloudAccessScope)
data class CloudAccessPolicy(val version: Int, val rules: List<CloudAccessRule>, val updatedAt: Long? = null, val updatedBy: String? = null) {
    companion object {
        /** The policy of a team that never saved one: every member may create and use their own sessions. */
        val DEFAULT = CloudAccessPolicy(0, listOf(CloudAccessRule(CloudAccessSubject.Everyone, CloudPermission.entries.toSet(), CloudAccessScope.OWN)))
    }
}
