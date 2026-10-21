package me.nathanfallet.teamcode.domain.team

import kotlinx.coroutines.delay
import me.nathanfallet.teamcode.domain.cloud.CloudSessionState

/** What an event is about; decides which of the caller's rights applies to it. */
enum class TeamEventSubject(val wire: String) { SESSION("session"), RUN("run") }

/**
 * The types of the caller-wide feed. A new kind of event (a session waiting for a human, say) is one entry here and
 * one `publishTeamEvent` call where it happens: the journal, the stream and the audience rules need no other change.
 * The wire name is the SSE `event:` field and is stored, so it never changes once released.
 */
enum class TeamEventType(val wire: String, val subject: TeamEventSubject) {
    SESSION_CREATED("cloud.session.created", TeamEventSubject.SESSION),
    /** The session reached `paused`: no pod, workspace kept. */
    SESSION_PAUSED("cloud.session.paused", TeamEventSubject.SESSION),
    /** A message asked a paused session to wake up. */
    SESSION_WOKEN("cloud.session.woken", TeamEventSubject.SESSION),
    /** A resuming session is `ready` again. */
    SESSION_RESUMED("cloud.session.resumed", TeamEventSubject.SESSION),
    /** The session reached `terminated` or `failed`. */
    SESSION_ENDED("cloud.session.ended", TeamEventSubject.SESSION),
    /** `{phase: started | completed | interrupted | failed}` of the session's agent. */
    CLOUD_TURN("cloud.turn", TeamEventSubject.SESSION),
    RUN_STARTED("run.started", TeamEventSubject.RUN),
    /** No heartbeat for [AgentRunService.HEARTBEAT_TTL_MILLIS]; a later heartbeat and another silence emit it again. */
    RUN_STALE("run.stale", TeamEventSubject.RUN),
    RUN_ENDED("run.ended", TeamEventSubject.RUN);

    companion object {
        fun parse(wire: String) = entries.singleOrNull { it.wire == wire }
    }
}

/** [data] is a JSON object, bounded by construction: identifiers and short labels, never transcript text or credentials. */
data class TeamEvent(val seq: Long, val type: TeamEventType, val subjectId: String, val ownerId: String, val at: Long, val data: String)

/** One read: the visible events after a cursor, and how far the cursor may advance (invisible events are skipped). */
data class TeamEventPage(val events: List<TeamEvent>, val through: Long, val more: Boolean)

/**
 * What a credential may see. A subject is seen when its right is held, for the caller's own sessions and runs, or for
 * the whole team when the matching `team…` flag is set.
 */
data class TeamEventAudience(val memberId: String, val sessions: Boolean, val runs: Boolean, val teamSessions: Boolean, val teamRuns: Boolean)

/** Sessions of the whole team: what a cloud administrator or a `team:read` key reads (see [canReadTeamCloud]). */
val Principal.readsTeamSessions: Boolean get() = canReadTeamCloud

/** Runs of the whole team: an interactive administrator or a `team:read` key (what `GET /runs?all=true` allows). */
val Principal.readsTeamRuns: Boolean get() = canReadTeam

/**
 * The audience of a stream opened by this credential. Cloud sessions need `cloud:read` (or an interactive login);
 * runs are added for an interactive login or a `usage:read` or `team:read` key.
 */
fun Principal.teamEventAudience(): TeamEventAudience {
    requireCloudRead()
    val runs = serviceScopes == null || ServiceScope.USAGE_READ in serviceScopes || ServiceScope.TEAM_READ in serviceScopes
    return TeamEventAudience(member.id, sessions = true, runs = runs, teamSessions = readsTeamSessions, teamRuns = runs && readsTeamRuns)
}

/** The journal of the feed: one ordered, gap-free sequence per team, written in the transaction that changes the state. */
interface TeamEventStore {
    /** Fails with 401 when the principal was revoked, expired, disabled or changed role. */
    suspend fun revalidate(actor: Principal)
    /** The newest sequence number of the team, 0 when it has none. */
    suspend fun head(teamId: String): Long
    /** The oldest retained sequence number, null when the journal is empty. */
    suspend fun oldest(teamId: String): Long?
    /** Events with `after < seq`, ascending, visible to [audience] and, when [types] is set, of those types. */
    suspend fun read(teamId: String, audience: TeamEventAudience, after: Long, types: Set<TeamEventType>?, limit: Int): TeamEventPage
    /** Deletes events older than [TeamEventService.RETENTION_MILLIS] (bounded); returns how many. */
    suspend fun purge(limit: Int): Int
}

/** An opened stream: authorized and with a valid cursor, so the HTTP response can still be a JSON error. */
class TeamEventFeed internal constructor(
    private val principal: Principal,
    private val audience: TeamEventAudience,
    private val store: TeamEventStore,
    private val types: Set<TeamEventType>?,
    private var cursor: Long,
    /** Set when events after the client's cursor were purged. */
    val oldestRetained: Long?,
    private val sleep: suspend (Long) -> Unit,
) {
    /**
     * Calls [emit] for each visible event in order, [idle] about every [TeamEventService.KEEPALIVE_MILLIS] of silence.
     * Never returns: it ends by cancellation (the client left) or by a [TeamFailure] when the credential stopped
     * being valid, checked every [TeamEventService.RECHECK_MILLIS].
     */
    suspend fun follow(emit: suspend (List<TeamEvent>) -> Unit, idle: suspend () -> Unit): Nothing {
        var quiet = 0L
        var sinceCheck = 0L
        while (true) {
            val page = store.read(principal.teamId, audience, cursor, types, TeamEventService.PAGE)
            if (page.events.isNotEmpty()) { emit(page.events); quiet = 0 }
            cursor = maxOf(cursor, page.through)
            if (page.more) continue
            if (quiet >= TeamEventService.KEEPALIVE_MILLIS) { idle(); quiet = 0 }
            sleep(TeamEventService.POLL_MILLIS)
            quiet += TeamEventService.POLL_MILLIS
            sinceCheck += TeamEventService.POLL_MILLIS
            if (sinceCheck >= TeamEventService.RECHECK_MILLIS) { store.revalidate(principal); sinceCheck = 0 }
        }
    }
}

class TeamEventService(private val store: TeamEventStore, private val sleep: suspend (Long) -> Unit = { delay(it) }) {
    /**
     * Opens the feed after [after] (the reconnecting client's `Last-Event-ID`); null starts at the newest event, so a
     * first connection sees only what happens next, and 0 starts at the oldest retained one.
     */
    suspend fun open(principal: Principal, after: Long?, types: Set<TeamEventType>?): TeamEventFeed {
        val audience = principal.teamEventAudience()
        if (types != null && types.none { audience.allows(it) }) throw TeamFailure("credential_scope", 403, "This credential does not see any of the requested event types.")
        store.revalidate(principal)
        val head = store.head(principal.teamId)
        if (after != null && after < 0) throw TeamFailure("invalid_cursor", 400, "Use a non-negative event sequence.")
        if (after != null && after > head) throw TeamFailure("invalid_cursor", 400, "This cursor is ahead of the team's events. Reconnect without Last-Event-ID.")
        val oldest = store.oldest(principal.teamId)
        // Events between the client's cursor and the oldest retained one were purged: say so instead of skipping silently.
        // When every event of the team was purged, the history restarts after the head.
        val purged = if (after == null || after <= 0) null else if (oldest == null) head + 1 else if (after < oldest - 1) oldest else null
        return TeamEventFeed(principal, audience, store, types, after ?: head, purged, sleep)
    }

    companion object {
        const val PAGE = 100
        const val POLL_MILLIS = 1_000L
        const val KEEPALIVE_MILLIS = 15_000L
        const val RECHECK_MILLIS = 15_000L
        const val RETENTION_MILLIS = 7 * 24 * 3_600_000L
    }
}

fun TeamEventAudience.allows(type: TeamEventType) = if (type.subject == TeamEventSubject.SESSION) sessions else runs
/** Whether an event of [type] owned by [owner] is visible. */
fun TeamEventAudience.sees(type: TeamEventType, owner: String) = allows(type) &&
    (owner == memberId || if (type.subject == TeamEventSubject.SESSION) teamSessions else teamRuns)

/** Which feed events a cloud session change produces; pure, so the transitions are tested without a database. */
object TeamEventRules {
    /**
     * The events of a session moving from [before] to [after], with the audit [action] of the write (for example
     * `cloud.session.woken`). A same-state write produces an event only for a wake.
     */
    fun session(before: CloudSessionState, after: CloudSessionState, action: String?): List<TeamEventType> {
        return buildList {
            if (action == "cloud.session.woken") add(TeamEventType.SESSION_WOKEN)
            if (before == after) return@buildList
            when {
                after == CloudSessionState.PAUSED && before != CloudSessionState.TRANSFERRING -> add(TeamEventType.SESSION_PAUSED)
                before == CloudSessionState.RESUMING && after == CloudSessionState.READY -> add(TeamEventType.SESSION_RESUMED)
                after.terminal -> add(TeamEventType.SESSION_ENDED)
            }
        }
    }
}
