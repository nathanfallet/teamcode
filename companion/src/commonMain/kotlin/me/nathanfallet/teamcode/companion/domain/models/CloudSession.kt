package me.nathanfallet.teamcode.companion.domain.models

/** The server's session states; [UNKNOWN] for a state a newer server added. */
enum class SessionState(val wire: String, val group: SessionGroup) {
    CREATING("creating", SessionGroup.ACTIVE),
    PREPARING("preparing", SessionGroup.ACTIVE),
    READY("ready", SessionGroup.ACTIVE),
    ACTIVE("active", SessionGroup.ACTIVE),
    PAUSING("pausing", SessionGroup.ACTIVE),
    RESUMING("resuming", SessionGroup.ACTIVE),
    TRANSFERRING("transferring", SessionGroup.ACTIVE),
    PAUSED("paused", SessionGroup.PAUSED),
    TERMINATED("terminated", SessionGroup.ENDED),
    FAILED("failed", SessionGroup.ENDED),
    UNKNOWN("unknown", SessionGroup.ACTIVE);

    companion object {
        fun of(wire: String?): SessionState = entries.firstOrNull { it.wire == wire && it != UNKNOWN } ?: UNKNOWN
    }
}

/** The list's filters: running or about to, paused, ended. */
enum class SessionGroup {
    ACTIVE, PAUSED, ENDED;

    /** The states the server is asked for; [UNKNOWN][SessionState.UNKNOWN] is never sent. */
    val states: List<SessionState> get() = SessionState.entries.filter { it.group == this && it != SessionState.UNKNOWN }
}

/** Whose sessions the list shows: the member's own, or the whole team's (administrators). */
enum class SessionScope { MINE, TEAM }

/** A cloud session as the screens need it; nothing of it is secret. */
data class CloudSession(
    val id: String,
    val name: String,
    val ownerId: String,
    val state: SessionState,
    /** The server's open-ended progress detail (`pulling_image`, ...), shown as is when present. */
    val phase: String?,
    val agentKind: String,
    val model: String?,
    val effort: String?,
    val profileId: String,
    val repositories: List<String>,
    val lastActivityAt: Long,
    val createdAt: Long,
    val updatedAt: Long,
    /** The stable code of the failure or of what blocks a resume, when there is one. */
    val problemCode: String?,
)

data class SessionPage(val sessions: List<CloudSession>, val nextBefore: String?)

data class Profile(val id: String, val name: String, val agentKind: String)

/** One change from the caller-wide feed about a session; [state] is set when the event carries it. */
data class SessionChange(
    val seq: Long,
    val type: String,
    val sessionId: String,
    val memberId: String,
    val at: Long,
    val state: SessionState?,
    /** For `cloud.turn`: `started`, `completed`, `interrupted` or `failed`. */
    val turnPhase: String?,
)
