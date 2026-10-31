package me.nathanfallet.teamcode.companion.domain

import me.nathanfallet.teamcode.companion.cloudSession
import me.nathanfallet.teamcode.companion.domain.models.SessionState
import me.nathanfallet.teamcode.companion.domain.models.SessionWaiting
import me.nathanfallet.teamcode.companion.domain.usecases.office.OfficeMood
import me.nathanfallet.teamcode.companion.domain.usecases.office.OfficeScene
import me.nathanfallet.teamcode.companion.domain.usecases.office.OfficeZone
import me.nathanfallet.teamcode.companion.domain.usecases.office.assignSeats
import me.nathanfallet.teamcode.companion.laptop
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** What a session looks like in the office: its mood, its room, its desk. */
class OfficeSceneTest {
    private fun mood(state: SessionState, finished: Boolean = false, waiting: Boolean = false, problem: String? = null) =
        OfficeScene.mood(cloudSession("s", state, problemCode = problem).copy(waiting = if (waiting) SessionWaiting("permission", "r1", 1) else null), finished)

    @Test fun aRunningTurnWorks() = assertEquals(OfficeMood.WORKING, mood(SessionState.ACTIVE))

    @Test fun aReadySessionIsIdleUntilItsTurnIsRead() {
        assertEquals(OfficeMood.IDLE, mood(SessionState.READY))
        assertEquals(OfficeMood.DONE, mood(SessionState.READY, finished = true))
    }

    @Test fun anAgentThatWaitsForAPersonIsWaitingWhateverElseItDoes() {
        assertEquals(OfficeMood.WAITING, mood(SessionState.ACTIVE, waiting = true))
        assertEquals(OfficeMood.WAITING, mood(SessionState.READY, finished = true, waiting = true))
    }

    @Test fun nothingWaitsInASessionThatCannotRunATurn() = assertEquals(OfficeMood.PAUSED, mood(SessionState.PAUSED, waiting = true))

    @Test fun pausedAndFailedAndStartingSessionsShowTheirState() {
        assertEquals(OfficeMood.PAUSED, mood(SessionState.PAUSED))
        assertEquals(OfficeMood.PAUSED, mood(SessionState.PAUSING))
        assertEquals(OfficeMood.ERROR, mood(SessionState.FAILED))
        for (state in listOf(SessionState.CREATING, SessionState.PREPARING, SessionState.RESUMING, SessionState.TRANSFERRING)) assertEquals(OfficeMood.STARTING, mood(state))
    }

    @Test fun aSessionWaitingForItsDeviceIsStarting() {
        val session = cloudSession("s", SessionState.ACTIVE, host = laptop()).copy(phase = "waiting_host")
        assertEquals(OfficeMood.STARTING, OfficeScene.mood(session, finished = false))
    }

    @Test fun aStateThisVersionDoesNotKnowIsIdle() = assertEquals(OfficeMood.IDLE, mood(SessionState.UNKNOWN))

    @Test fun remoteSessionsSitInTheDevicesRoomAndTheOthersInTheCloud() {
        val agents = OfficeScene.agents(listOf(cloudSession("a"), cloudSession("b", host = laptop())), emptySet())
        assertEquals(listOf(OfficeZone.CLOUD, OfficeZone.REMOTE), agents.map { it.zone })
    }

    @Test fun anEndedSessionHasNoDesk() {
        val agents = OfficeScene.agents(listOf(cloudSession("a", SessionState.TERMINATED), cloudSession("b", SessionState.FAILED)), emptySet())
        assertEquals(listOf("b"), agents.map { it.id })
    }

    @Test fun theFinishedMarkOfASessionMakesOnlyItsOwnAgentDone() {
        val agents = OfficeScene.agents(listOf(cloudSession("a"), cloudSession("b")), setOf("b"))
        assertEquals(listOf(OfficeMood.IDLE, OfficeMood.DONE), agents.map { it.mood })
        assertEquals(mapOf(OfficeMood.IDLE to 1, OfficeMood.DONE to 1), OfficeScene.counts(agents))
    }

    @Test fun eachIdTakesTheLowestFreeSeat() = assertEquals(mapOf("a" to 0, "b" to 1, "c" to 2), assignSeats(emptyMap(), listOf("a", "b", "c"), 6))

    @Test fun aDeskStaysItsAgentsWhenAnotherOneLeaves() {
        val first = assignSeats(emptyMap(), listOf("a", "b", "c"), 6)
        val second = assignSeats(first, listOf("a", "c"), 6)
        assertEquals(0, second["a"])
        assertEquals(2, second["c"])
        // The freed desk goes to the next one to come, not to someone sitting elsewhere.
        assertEquals(1, assignSeats(second, listOf("a", "c", "d"), 6)["d"])
    }

    @Test fun idsBeyondTheCapacityHaveNoSeat() {
        val seats = assignSeats(emptyMap(), listOf("a", "b", "c"), 2)
        assertEquals(setOf("a", "b"), seats.keys)
        assertFalse("c" in seats)
    }

    @Test fun aSeatBeyondTheCapacityIsGivenUp() {
        val seats = assignSeats(mapOf("a" to 5, "b" to 0), listOf("a", "b"), 3)
        assertEquals(0, seats["b"])
        assertTrue(seats.getValue("a") in 0 until 3)
        assertEquals(2, seats.values.toSet().size)
    }
}
