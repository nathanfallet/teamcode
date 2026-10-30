package me.nathanfallet.teamcode.companion.presentation

import me.nathanfallet.teamcode.companion.presentation.screens.formatEventTime
import me.nathanfallet.teamcode.companion.presentation.screens.utcEpochDay
import kotlin.test.Test
import kotlin.test.assertEquals

class EventTimeTest {
    private val at = 1_793_221_200_000L // 2026-10-28 21:00 UTC

    @Test fun anEventOfTodayShowsTheTimeOfDayOnly() = assertEquals("21:00 UTC", formatEventTime(at, utcEpochDay(at + 3_600_000)))

    @Test fun anEventOfAnotherDayShowsTheDateToo() {
        assertEquals("2026-10-28 21:00 UTC", formatEventTime(at, utcEpochDay(at) + 1))
        assertEquals("2026-10-28 21:00 UTC", formatEventTime(at, utcEpochDay(at) - 1))
    }

    @Test fun withoutAKnownDayTheDateIsShown() = assertEquals("2026-10-28 21:00 UTC", formatEventTime(at, null))

    @Test fun theDayChangesAtMidnightUtc() {
        val midnight = utcEpochDay(at) * 86_400_000L
        assertEquals("00:00 UTC", formatEventTime(midnight, utcEpochDay(at)))
        assertEquals("2026-10-27 23:59 UTC", formatEventTime(midnight - 60_000, utcEpochDay(at)))
    }
}
