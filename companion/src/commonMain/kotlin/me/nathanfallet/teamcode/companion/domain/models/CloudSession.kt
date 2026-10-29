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

/** What a state belongs to: running or about to, paused, ended. */
enum class SessionGroup {
    ACTIVE, PAUSED, ENDED;

    /** The states the server is asked for; [UNKNOWN][SessionState.UNKNOWN] is never sent. */
    val states: List<SessionState> get() = SessionState.entries.filter { it.group == this && it != SessionState.UNKNOWN }
}

/** The list's status filter: every session, the ones still usable (active and paused together), or the ended ones. */
enum class SessionFilter(val groups: Set<SessionGroup>) {
    ALL(SessionGroup.entries.toSet()), OPEN(setOf(SessionGroup.ACTIVE, SessionGroup.PAUSED)), ENDED(setOf(SessionGroup.ENDED));

    /** The states the server is asked for. */
    val states: List<SessionState> get() = groups.flatMap { it.states }

    operator fun contains(state: SessionState): Boolean = state.group in groups
}

/** Whose sessions the list shows: the member's own, or the whole team's (administrators). */
enum class SessionScope { MINE, TEAM }

/** Where a session runs: a pod the server started, or a member's device (`cloud.sessions.remote.v1`). */
enum class SessionKind(val wire: String) {
    CLOUD("cloud"), REMOTE("remote");

    companion object {
        /** A server without remote sessions sends no kind: its sessions are cloud sessions. */
        fun of(wire: String?): SessionKind = if (wire == REMOTE.wire) REMOTE else CLOUD
    }
}

/**
 * The device a remote session runs on, as the server describes it now. [removed]: the device was removed, so the
 * session has no host any more. [online]: the host runs the session now. [directoryName] is the folder's name only.
 */
data class SessionHost(
    val deviceName: String?,
    val platform: String?,
    val osVersion: String?,
    val directoryName: String?,
    val online: Boolean,
    val removed: Boolean,
    /** [ASK] or [BYPASS]. */
    val permissionMode: String?,
) {
    companion object {
        /** Claude Code's permission requests come to the team's clients; Codex stays in its sandbox. */
        const val ASK = "ask"
        const val BYPASS = "bypass"
    }
}

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
    val kind: SessionKind = SessionKind.CLOUD,
    /** Where a remote session runs; null for a cloud session. */
    val host: SessionHost? = null,
    /**
     * Why the device's daemon refused to start the session ([problemCode] `host_refused`): its stable reason code
     * (`folder_missing`, `agent_unsupported`, ...), null when it did not give a readable one.
     */
    val refusalReason: String? = null,
    /** What the agent waits for from a person (`cloud.sessions.waiting.v1`); null when nothing waits. */
    val waiting: SessionWaiting? = null,
) {
    val remote: Boolean get() = kind == SessionKind.REMOTE

    /** A remote session paused because its device stopped answering (the server's `host_offline`). */
    val hostOffline: Boolean get() = remote && problemCode == HOST_OFFLINE

    /**
     * A remote session that waits for its device to start it (`phase = waiting_host`): a new one the device's daemon has
     * not picked up yet, or one woken by a message. It starts by itself once the device answers.
     */
    val waitingForHost: Boolean get() = remote && phase == WAITING_HOST && state.group == SessionGroup.ACTIVE

    /** The device's daemon refused to start the session (`host_refused`): it ended without running. */
    val hostRefused: Boolean get() = remote && problemCode == HOST_REFUSED

    companion object {
        const val HOST_OFFLINE = "host_offline"
        const val WAITING_HOST = "waiting_host"
        const val HOST_REFUSED = "host_refused"
    }
}

data class SessionPage(val sessions: List<CloudSession>, val nextBefore: String?)

data class Profile(val id: String, val name: String, val agentKind: String)

/**
 * The agent waits for a person: [reason] is `permission` (a tool use to approve) or `question`; [requestId] the request
 * to answer, [since] server time in milliseconds.
 */
data class SessionWaiting(val reason: String, val requestId: String?, val since: Long) {
    companion object {
        const val PERMISSION = "permission"
        const val QUESTION = "question"
    }
}

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
    /** Null when the event does not say (a server without remote sessions). */
    val kind: SessionKind? = null,
    /** For `cloud.session.waiting`: `permission` or `question`. */
    val waitingReason: String? = null,
    /** For `cloud.session.waiting` and `cloud.session.answered`: the request concerned, when the event names it. */
    val requestId: String? = null,
) {
    companion object {
        const val WAITING = "cloud.session.waiting"
        const val ANSWERED = "cloud.session.answered"
        const val TURN = "cloud.turn"
    }
}
