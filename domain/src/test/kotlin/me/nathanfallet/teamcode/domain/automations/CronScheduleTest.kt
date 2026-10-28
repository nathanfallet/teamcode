package me.nathanfallet.teamcode.domain.automations

import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import me.nathanfallet.teamcode.domain.team.TeamFailure
import kotlin.test.*

class CronScheduleTest {
    private val paris = ZoneId.of("Europe/Paris")
    private fun at(text: String, zone: ZoneId = paris) = ZonedDateTime.of(java.time.LocalDateTime.parse(text), zone).toInstant().toEpochMilli()
    private fun local(millis: Long, zone: ZoneId = paris) = Instant.ofEpochMilli(millis).atZone(zone).toLocalDateTime().toString()

    @Test fun theNextSlotIsReadInTheAutomationTimezone() {
        val schedule = CronSchedule.parse("0 21 * * *", "Europe/Paris")
        assertEquals("2026-10-28T21:00", local(schedule.next(at("2026-10-28T20:59"))!!))
        assertEquals("2026-10-29T21:00", local(schedule.next(at("2026-10-28T21:00"))!!), "strictly after")
        assertEquals(at("2026-10-28T20:00", ZoneId.of("UTC")), schedule.next(at("2026-10-28T12:00")), "21:00 in Paris is 20:00 UTC in winter")
    }

    @Test fun fieldsAcceptListsRangesStepsAndNames() {
        val weekdays = CronSchedule.parse("*/30 9-17/4 * JAN-MAR,dec MON-FRI", "UTC")
        val utc = ZoneId.of("UTC")
        assertEquals(listOf("2026-12-01T09:00", "2026-12-01T09:30", "2026-12-01T13:00"),
            weekdays.upcoming(at("2026-11-30T23:00", utc), 3).map { local(it, utc) })
        assertEquals("2026-11-01T00:00", local(CronSchedule.parse("0 0 * * 7", "UTC").next(at("2026-10-31T12:00", utc))!!, utc), "7 is Sunday too")
        assertEquals("2026-11-01T00:00", local(CronSchedule.parse("0 0 * * SUN", "UTC").next(at("2026-10-31T12:00", utc))!!, utc))
    }

    @Test fun aRestrictedDayOfMonthAndDayOfWeekMatchEither() {
        val utc = ZoneId.of("UTC")
        // The 13th, or any Friday: 2026-11-06 is a Friday, before the 13th.
        assertEquals("2026-11-06T08:00", local(CronSchedule.parse("0 8 13 * FRI", "UTC").next(at("2026-11-01T00:00", utc))!!, utc))
        assertEquals("2026-11-13T08:00", local(CronSchedule.parse("0 8 13 * *", "UTC").next(at("2026-11-01T00:00", utc))!!, utc))
    }

    @Test fun aSlotInTheSpringGapRunsAtTheFirstValidTimeAfterIt() {
        // 2027-03-28: Paris jumps from 02:00 to 03:00.
        val schedule = CronSchedule.parse("30 2 * * *", "Europe/Paris")
        assertEquals("2027-03-28T03:30", local(schedule.next(at("2027-03-28T00:00"))!!))
        assertEquals("2027-03-29T02:30", local(schedule.next(schedule.next(at("2027-03-28T00:00"))!!)!!))
    }

    @Test fun aRepeatedAutumnHourRunsOnce() {
        // 2026-10-25: Paris goes back from 03:00 to 02:00, so 02:30 happens twice.
        val schedule = CronSchedule.parse("30 2 * * *", "Europe/Paris")
        val first = schedule.next(at("2026-10-25T00:00"))!!
        assertEquals("2026-10-25T02:30", local(first))
        assertEquals("2026-10-26T02:30", local(schedule.next(first)!!), "the second 02:30 is not a slot")
    }

    @Test fun theLatestSlotOfAWindowIsTheOneCaughtUp() {
        val schedule = CronSchedule.parse("0 * * * *", "UTC")
        val utc = ZoneId.of("UTC")
        val latest = schedule.latest(at("2026-10-28T15:40", utc), notBefore = at("2026-10-28T12:30", utc))
        assertEquals("2026-10-28T15:00", local(latest!!, utc))
        assertNull(schedule.latest(at("2026-10-28T15:40", utc), notBefore = at("2026-10-28T15:01", utc)), "no slot in the window")
        assertEquals(at("2026-10-28T15:00", utc), schedule.latest(at("2026-10-28T15:00", utc), notBefore = at("2026-10-28T15:00", utc)), "both ends included")
    }

    @Test fun theLatestSlotOfADayLongWindowCrossesMidnightAndTheClockChange() {
        val frequent = CronSchedule.parse("*/5 * * * *", "Europe/Paris", at("2026-10-24T00:00"))
        assertEquals("2026-10-25T09:10", local(frequent.latest(at("2026-10-25T09:12"), notBefore = at("2026-10-24T09:12"))!!))
        val night = CronSchedule.parse("30 2 * * *", "Europe/Paris", at("2026-10-24T00:00"))
        // 2026-10-25: 02:30 happens twice in Paris; only the first one (the default offset of `at`) is a slot.
        assertEquals(at("2026-10-25T02:30"), night.latest(at("2026-10-25T05:00"), notBefore = at("2026-10-24T05:00")))
        assertEquals("2026-10-26T02:30", local(night.latest(at("2026-10-26T23:00"), notBefore = at("2026-10-25T23:00"))!!))
    }

    @Test fun invalidExpressionsAreRefused() {
        for (expression in listOf("", "* * * *", "* * * * * *", "60 * * * *", "* 24 * * *", "* * 0 * *", "* * * 13 *", "* * * * 8", "*/0 * * * *", "5-1 * * * *", "a * * * *", "0 21 * * *" + " ".repeat(200)))
            assertEquals("invalid_automation", assertFailsWith<TeamFailure>(expression) { CronSchedule.parse(expression, "UTC") }.code, expression)
        assertEquals("invalid_automation", assertFailsWith<TeamFailure> { CronSchedule.parse("0 21 * * *", "Mars/Olympus") }.code)
        assertEquals("invalid_automation", assertFailsWith<TeamFailure> { CronSchedule.parse("0 21 * * *", "") }.code, "the timezone is required")
        assertEquals("invalid_automation", assertFailsWith<TeamFailure> { CronSchedule.parse("0 0 31 2 *", "UTC") }.code, "never matches")
    }

    @Test fun twoSlotsAreAtLeastFiveMinutesApart() {
        for (expression in listOf("* * * * *", "*/4 * * * *", "0,3 * * * *", "58,1 * * * *"))
            assertEquals("invalid_automation", assertFailsWith<TeamFailure>(expression) { CronSchedule.parse(expression, "UTC") }.code, expression)
        CronSchedule.parse("*/5 * * * *", "UTC")
        CronSchedule.parse("0,5 9 * * *", "UTC")
    }
}
