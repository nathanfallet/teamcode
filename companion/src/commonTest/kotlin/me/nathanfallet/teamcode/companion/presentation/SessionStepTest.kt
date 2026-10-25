package me.nathanfallet.teamcode.companion.presentation

import me.nathanfallet.teamcode.companion.presentation.screens.neighbourSession
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SessionStepTest {
    private val ids = listOf("a", "b", "c")

    @Test fun nextAndPreviousMoveOneRowFromTheOpenSession() {
        assertEquals("c", neighbourSession(ids, "b", 1))
        assertEquals("a", neighbourSession(ids, "b", -1))
    }

    @Test fun theEndsOfTheListStayWhereTheyAreInsteadOfWrapping() {
        assertNull(neighbourSession(ids, "c", 1))
        assertNull(neighbourSession(ids, "a", -1))
    }

    @Test fun withNoSessionOpenNextTakesTheFirstAndPreviousTheLast() {
        assertEquals("a", neighbourSession(ids, null, 1))
        assertEquals("c", neighbourSession(ids, null, -1))
    }

    @Test fun aSessionTheFilterHidesIsTreatedAsNoneOpen() {
        assertEquals("a", neighbourSession(ids, "gone", 1))
        assertEquals("c", neighbourSession(ids, "gone", -1))
    }

    @Test fun anEmptyListHasNoNeighbour() {
        assertNull(neighbourSession(emptyList(), null, 1))
        assertNull(neighbourSession(emptyList(), "a", -1))
    }
}
