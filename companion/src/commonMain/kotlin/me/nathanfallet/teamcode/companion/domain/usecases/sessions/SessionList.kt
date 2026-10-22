package me.nathanfallet.teamcode.companion.domain.usecases.sessions

import me.nathanfallet.teamcode.companion.domain.models.CloudSession
import me.nathanfallet.teamcode.companion.domain.models.SessionChange
import me.nathanfallet.teamcode.companion.domain.models.SessionGroup
import me.nathanfallet.teamcode.companion.domain.models.SessionPage
import me.nathanfallet.teamcode.companion.domain.models.SessionScope
import me.nathanfallet.teamcode.companion.domain.models.SessionState

/**
 * The sessions one list shows (one [scope], one [group]), newest first as the server orders them, and the sessions
 * with a turn finished since they were last opened. Pure: every change returns a new list.
 */
data class SessionList(
    val scope: SessionScope,
    val group: SessionGroup,
    val memberId: String,
    val sessions: List<CloudSession> = emptyList(),
    /** Null once every page is loaded. */
    val nextBefore: String? = null,
    val finishedTurns: Set<String> = emptySet(),
    /** Sessions read and found outside this list (another group, or past the loaded pages): their turns are not read again. */
    val outside: Set<String> = emptySet(),
) {
    fun loaded(page: SessionPage): SessionList = copy(sessions = page.sessions.filter(::belongs).sortedWith(NEWEST_FIRST), nextBefore = page.nextBefore)

    /**
     * The first page read again (a reload, a gap, a poll): it replaces the rows it covers, and the rows of the pages
     * loaded after it stay, so a refresh never collapses a list scrolled further down.
     */
    fun refreshed(page: SessionPage): SessionList {
        val fresh = loaded(page)
        // The oldest session of the page: the rows after it come from the pages loaded later.
        val boundary = page.sessions.maxWithOrNull(NEWEST_FIRST)
        if (page.nextBefore == null || boundary == null) return fresh
        val deeper = sessions.filter { NEWEST_FIRST.compare(it, boundary) > 0 }
        if (deeper.isEmpty()) return fresh
        return fresh.copy(sessions = fresh.sessions + deeper, nextBefore = nextBefore)
    }

    /** Rows whose state changes without a feed event (creation, a pause or resume in progress): read them again now and then. */
    val unsettled: List<String> get() = sessions.filter { it.state in UNSETTLED }.map { it.id }

    fun appended(page: SessionPage): SessionList {
        val known = sessions.map { it.id }.toSet()
        return copy(sessions = (sessions + page.sessions.filter { it.id !in known && belongs(it) }).sortedWith(NEWEST_FIRST), nextBefore = page.nextBefore,
            outside = outside - page.sessions.map { it.id }.toSet())
    }

    /**
     * Applies a feed event. Returns the new list and, when the event is about a session the list does not hold but
     * might, its id to read with `GET /cloud/sessions/{id}` (then [upserted]).
     */
    fun applied(change: SessionChange): Pair<SessionList, String?> {
        if (scope == SessionScope.MINE && change.memberId != memberId) return this to null
        val current = sessions.firstOrNull { it.id == change.sessionId }
        val finished = change.turnPhase != null && change.turnPhase != "started"
        val marks = if (finished) finishedTurns + change.sessionId else finishedTurns
        if (current == null) {
            // A turn runs only in an active session; a session already read and found elsewhere is read again only when
            // an event says its state changed.
            val mayBelong = if (change.state == null) group == SessionGroup.ACTIVE && change.sessionId !in outside else change.state.group == group
            return copy(finishedTurns = marks) to change.sessionId.takeIf { mayBelong }
        }
        // A turn of a session the list still shows as preparing, pausing...: its state moved without an event, read it.
        if (change.state == null && current.state in UNSETTLED) return copy(finishedTurns = marks) to change.sessionId
        val state = change.state ?: when {
            change.turnPhase == "started" && current.state == SessionState.READY -> SessionState.ACTIVE
            finished && current.state == SessionState.ACTIVE -> SessionState.READY
            else -> current.state
        }
        val activity = if (change.turnPhase != null) maxOf(current.lastActivityAt, change.at) else current.lastActivityAt
        val updated = current.copy(state = state, lastActivityAt = activity, updatedAt = maxOf(current.updatedAt, change.at))
        return copy(finishedTurns = marks).upserted(updated) to null
    }

    /** Puts [session] in place (or in order), or removes it when it no longer belongs to this list. */
    fun upserted(session: CloudSession): SessionList {
        val others = sessions.filterNot { it.id == session.id }
        if (!belongs(session)) return copy(sessions = others, outside = remembered(session.id))
        // Older than the last loaded session while pages remain: it will come with the next page.
        val last = others.lastOrNull()
        if (nextBefore != null && last != null && NEWEST_FIRST.compare(session, last) > 0) return copy(sessions = others, outside = remembered(session.id))
        return copy(sessions = (others + session).sortedWith(NEWEST_FIRST), outside = outside - session.id)
    }

    private fun remembered(id: String) = (if (outside.size >= MAX_OUTSIDE) outside.drop(outside.size - MAX_OUTSIDE + 1).toSet() else outside) + id

    /** [sessionId] cannot be read (gone, or hidden from this member): it leaves the list and its turns are not read again. */
    fun forgotten(sessionId: String): SessionList = copy(sessions = sessions.filterNot { it.id == sessionId }, outside = remembered(sessionId))

    fun opened(sessionId: String): SessionList = copy(finishedTurns = finishedTurns - sessionId)

    private fun belongs(session: CloudSession) = session.state.group == group && (scope == SessionScope.TEAM || session.ownerId == memberId)

    private companion object {
        val NEWEST_FIRST = compareByDescending<CloudSession> { it.createdAt }.thenByDescending { it.id }
        val SETTLED = setOf(SessionState.READY, SessionState.ACTIVE, SessionState.PAUSED, SessionState.TERMINATED, SessionState.FAILED)
        /** A state this version does not know is not read again and again: it may never settle into one it knows. */
        val UNSETTLED = SessionState.entries.toSet() - SETTLED - SessionState.UNKNOWN
        const val MAX_OUTSIDE = 500
    }
}
