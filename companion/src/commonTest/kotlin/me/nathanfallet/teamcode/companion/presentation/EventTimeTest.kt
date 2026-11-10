package me.nathanfallet.teamcode.companion.presentation

import me.nathanfallet.teamcode.companion.presentation.screens.formatEventTime
import me.nathanfallet.teamcode.companion.presentation.screens.localEpochDay
import me.nathanfallet.teamcode.companion.presentation.screens.utcOffsetMinutes
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

class EventTimeTest {
    private val at = 1_793_221_200_000L // 2026-10-28 21:00 UTC

    init { utcOffsetMinutes = { 0 } }
    @AfterTest fun restoreZone() { utcOffsetMinutes = { 0 } }

    @Test fun timesAreShownInTheDevicesZone() {
        utcOffsetMinutes = { 120 } // Paris in summer time
        assertEquals("2026-10-28 23:00", formatEventTime(at, null))
        assertEquals("23:00", formatEventTime(at, localEpochDay(at)))
        // 22:30 UTC is already the next day in UTC+2: a day boundary follows the zone, not UTC.
        assertEquals(localEpochDay(at) + 1, localEpochDay(at + 5_400_000))
        utcOffsetMinutes = { -300 }
        assertEquals("16:00", formatEventTime(at, localEpochDay(at)))
    }

    @Test fun anEventOfTodayShowsTheTimeOfDayOnly() = assertEquals("21:00", formatEventTime(at, localEpochDay(at + 3_600_000)))

    @Test fun anEventOfAnotherDayShowsTheDateToo() {
        assertEquals("2026-10-28 21:00", formatEventTime(at, localEpochDay(at) + 1))
        assertEquals("2026-10-28 21:00", formatEventTime(at, localEpochDay(at) - 1))
    }

    @Test fun withoutAKnownDayTheDateIsShown() = assertEquals("2026-10-28 21:00", formatEventTime(at, null))

    @Test fun theDayChangesAtMidnight() {
        val midnight = localEpochDay(at) * 86_400_000L
        assertEquals("00:00", formatEventTime(midnight, localEpochDay(at)))
        assertEquals("2026-10-27 23:59", formatEventTime(midnight - 60_000, localEpochDay(at)))
    }
}
