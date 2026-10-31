package me.nathanfallet.teamcode.companion.domain.usecases.office

import me.nathanfallet.teamcode.companion.domain.models.CloudSession
import me.nathanfallet.teamcode.companion.domain.models.SessionKind
import me.nathanfallet.teamcode.companion.domain.models.SessionState

/** What an agent of the office is doing, as its session says it: the desk animation and the pill of its card. */
enum class OfficeMood {
    /** A turn is running. */
    WORKING,
    /** The agent waits for a person: a tool use to approve, or a question. */
    WAITING,
    /** The session failed. */
    ERROR,
    /** A turn finished since the session was last opened. */
    DONE,
    /** Ready for the next message. */
    IDLE,
    /** Paused, or its device stopped answering. */
    PAUSED,
    /** Being created, resumed or transferred, or waiting for its device to start it. */
    STARTING,
}

/** The two places a session runs, the two rooms of the office. */
enum class OfficeZone { REMOTE, CLOUD }

/** One session of the office with its [mood] and the room it sits in. */
data class OfficeAgent(val session: CloudSession, val mood: OfficeMood, val zone: OfficeZone) {
    val id: String get() = session.id
}

/** Which sessions the office shows and how it reads their state. Pure. */
object OfficeScene {
    /** The mood of [session]; [finished]: a turn ended in it since it was last opened. */
    fun mood(session: CloudSession, finished: Boolean): OfficeMood = when {
        session.state == SessionState.FAILED -> OfficeMood.ERROR
        // The server clears `waiting` once the turn ends, but only a session that can run a turn waits for a person.
        session.waiting != null && session.state in RUNNABLE -> OfficeMood.WAITING
        session.state == SessionState.PAUSED || session.state == SessionState.PAUSING -> OfficeMood.PAUSED
        session.state in STARTING || session.waitingForHost -> OfficeMood.STARTING
        session.state == SessionState.ACTIVE -> OfficeMood.WORKING
        session.state == SessionState.READY -> if (finished) OfficeMood.DONE else OfficeMood.IDLE
        else -> OfficeMood.IDLE
    }

    /** The agents of [sessions] in their order: an ended session has no desk. */
    fun agents(sessions: List<CloudSession>, finishedTurns: Set<String>): List<OfficeAgent> = sessions
        .filter { it.state != SessionState.TERMINATED }
        .map { OfficeAgent(it, mood(it, it.id in finishedTurns), if (it.kind == SessionKind.REMOTE) OfficeZone.REMOTE else OfficeZone.CLOUD) }

    /** How many agents are in each mood. */
    fun counts(agents: List<OfficeAgent>): Map<OfficeMood, Int> = agents.groupingBy { it.mood }.eachCount()

    private val RUNNABLE = setOf(SessionState.READY, SessionState.ACTIVE)
    private val STARTING = setOf(SessionState.CREATING, SessionState.PREPARING, SessionState.RESUMING, SessionState.TRANSFERRING)
}

/**
 * Gives each id a seat from 0 until [capacity] and keeps it: an id that had a seat keeps it while it is still there, a new
 * one takes the lowest free seat, so a desk never changes hands because another session ended. Ids that find no seat have none.
 */
fun assignSeats(previous: Map<String, Int>, ids: List<String>, capacity: Int): Map<String, Int> {
    val seats = LinkedHashMap<String, Int>()
    val taken = HashSet<Int>()
    for (id in ids) {
        val seat = previous[id]
        if (seat != null && seat in 0 until capacity && taken.add(seat)) seats[id] = seat
    }
    var next = 0
    for (id in ids) {
        if (id in seats) continue
        while (next in taken) next++
        if (next >= capacity) break
        seats[id] = next
        taken.add(next)
    }
    return seats
}
