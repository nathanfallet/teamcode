package me.nathanfallet.teamcode.domain

import me.nathanfallet.teamcode.domain.accounts.*
import me.nathanfallet.teamcode.domain.capacity.*
import me.nathanfallet.teamcode.domain.routing.RouteTarget
import me.nathanfallet.teamcode.domain.team.*
import kotlin.test.*

class ProgressivePacingTest {
    private val now = 1_000_000L
    private val reset = now + 3_600_000
    private val controller = PacingUseCase()

    /** Two complete windows; the five-hour one is projected to reach the threshold at [share] of the time left. */
    private fun windows(share: Double?, utilization: Double = .5): List<WindowPacing> = listOf(
        WindowPacing(AccountQuota("a", "five_hour", utilization, now, reset), if (share == null) QuotaForecast(null, 0.0, QuotaForecast.State.IDLE)
            else QuotaForecast(now + ((reset - now) * share).toLong(), 10.0, QuotaForecast.State.EXHAUSTS_FIRST)),
        WindowPacing(AccountQuota("a", "seven_day", .1, now, now + 86_400_000), QuotaForecast(now + 90_000_000, 1.0, QuotaForecast.State.RESET_FIRST)),
    )

    @Test fun slowDownOnlySpacesAdmissionsAsTheOvershootGrows() {
        val light = controller.advise(listOf(windows(.75)), 0, now)
        assertEquals(PacingAdvice(Pace.SLOW_DOWN, 0, 2_000, light.message), light)
        assertTrue("2 seconds" in light.message && "one request" !in light.message, light.message)
        assertEquals(0 to 5_000L, controller.advise(listOf(windows(.3)), 0, now).let { it.maxConcurrent to it.minIntervalMillis })
        assertEquals(0 to 10_000L, controller.advise(listOf(windows(.1)), 0, now).let { it.maxConcurrent to it.minIntervalMillis })
        assertEquals(SlowDownLevel.LIGHT, SlowDownLevel.of(.5))
        assertEquals(SlowDownLevel.MODERATE, SlowDownLevel.of(.25))
        assertEquals(SlowDownLevel.SEVERE, SlowDownLevel.of(.2499))
        assertEquals(SlowDownLevel.SEVERE, SlowDownLevel.of(Double.NaN))
        // An overshooting window without a known reset cannot be graded: it counts as severe, whatever the other window says.
        val unknown = windows(.75).let { listOf(it[0], WindowPacing(AccountQuota("a", "seven_day", .6, now, null), QuotaForecast(now + 1_000, 10.0, QuotaForecast.State.EXHAUSTS_FIRST))) }
        assertEquals(10_000L, controller.advise(listOf(unknown), 0, now).minIntervalMillis)
    }

    @Test fun pacingNeverLowersTheConcurrencyLimit() {
        assertEquals(2, controller.advise(listOf(windows(.9)), 2, now).maxConcurrent, "An explicit member, group or run limit is kept")
        val advice = (100 downTo 0).map { controller.advise(listOf(windows(it / 100.0)), 0, now) }
        advice.zipWithNext().forEach { (wider, narrower) -> assertTrue(PACING_STRICTNESS.compare(narrower, wider) >= 0, "$wider then $narrower") }
        assertTrue(advice.all { it.pace == Pace.SLOW_DOWN && it.maxConcurrent == UNLIMITED_CONCURRENCY && it.minIntervalMillis in 2_000..10_000 })
        val exhausted = listOf(WindowPacing(AccountQuota("a", "five_hour", .95, now, reset), QuotaForecast(now, null, QuotaForecast.State.EXHAUSTED)))
        val pause = controller.advise(listOf(exhausted), 0, now)
        assertEquals(Triple(Pace.PAUSE, UNLIMITED_CONCURRENCY, 30_000L), Triple(pause.pace, pause.maxConcurrent, pause.minIntervalMillis))
        assertEquals(4, controller.advise(listOf(exhausted), 4, now).maxConcurrent)
        val route = RoutePacingUseCase().advise(setOf(RouteTarget("anthropic", "claude-opus-test")), emptyList(), 0, now, allowanceAvailable = false)
        assertEquals(Triple(Pace.PAUSE, UNLIMITED_CONCURRENCY, 30_000L), Triple(route.pace, route.maxConcurrent, route.minIntervalMillis))
    }

    @Test fun theLeastPressuredAccountIsChosen() {
        assertEquals(SlowDownLevel.LIGHT.intervalMillis, controller.advise(listOf(windows(.1), windows(.8)), 0, now).minIntervalMillis)
        // An account whose forecast is not known yet is not under pressure: it must not let another account's throttle win.
        val unlearned = listOf(WindowPacing(AccountQuota("b", "five_hour", .07, now, reset), QuotaForecast(null, null, QuotaForecast.State.UNKNOWN)),
            WindowPacing(AccountQuota("b", "seven_day", .13, now, now + 86_400_000), QuotaForecast(null, null, QuotaForecast.State.UNKNOWN)))
        val advice = controller.advise(listOf(windows(.1), unlearned), 0, now)
        assertEquals(Triple(Pace.UNKNOWN, 0, 0L), Triple(advice.pace, advice.maxConcurrent, advice.minIntervalMillis))
        // An unknown account without clear headroom (stale data at 90%) cannot switch a real slowdown off.
        val stale = unlearned.map { it.copy(quota = it.quota.copy(utilization = .9)) }
        assertEquals(Pace.SLOW_DOWN, controller.advise(listOf(windows(.1), stale), 0, now).pace)
    }

    /**
     * Replays the 2026-10-21 production state: a seven-day window at 13% whose last-hour rate reaches 95% in about
     * 55 hours, six days before its reset, was graded as an overshoot and throttled the whole team to one request every
     * ten seconds. A projection beyond the pacing horizon is not pressure.
     */
    @Test fun aWeeklyProjectionBeyondTheHorizonIsNotPressure() {
        val hour = 3_600_000L
        val weeklyReset = now + 158 * hour
        fun account(id: String, fiveHour: Double, fiveHourEta: Long?, weekly: Double, weeklyEta: Long) = listOf(
            WindowPacing(AccountQuota(id, "five_hour", fiveHour, now, now + 4 * hour),
                if (fiveHourEta == null) QuotaForecast(null, 0.0, QuotaForecast.State.IDLE) else QuotaForecast(fiveHourEta, 20.0, QuotaForecast.State.EXHAUSTS_FIRST)),
            WindowPacing(AccountQuota(id, "seven_day", weekly, now, weeklyReset), QuotaForecast(weeklyEta, 1.5, QuotaForecast.State.EXHAUSTS_FIRST)),
        )
        val healthy = account("nathan", .07, null, .13, now + 55 * hour)
        val busy = account("lyes-4", .79, now + 43 * 60_000, .82, now + 107 * 60_000)
        assertFalse(healthy[1].overshoots(now), "55 hours away is beyond the horizon")
        assertTrue(busy[1].overshoots(now))
        assertEquals(Pace.NORMAL, controller.advise(listOf(healthy), 0, now).pace)
        assertEquals(Pace.NORMAL, controller.advise(listOf(busy, healthy), 0, now).pace, "One pressured account does not slow the pool")
        // Within the horizon, the share is measured against the horizon, not against a reset days away.
        assertEquals(107.0 / 300, busy[1].affordableShare(now)!!, 1e-9)
        val slow = controller.advise(listOf(busy), 0, now)
        assertEquals(Triple(Pace.SLOW_DOWN, 0, SlowDownLevel.SEVERE.intervalMillis), Triple(slow.pace, slow.maxConcurrent, slow.minIntervalMillis))
    }

    @Test fun unpressuredPacesNeverDelayAndAZeroLimitStaysUnlimited() {
        val normal = controller.advise(listOf(windows(null, utilization = .7)), 0, now)
        assertEquals(Triple(Pace.NORMAL, 0, 0L), Triple(normal.pace, normal.maxConcurrent, normal.minIntervalMillis))
        val spare = controller.advise(listOf(windows(null, utilization = .1)), 0, now)
        assertEquals(Pace.CAN_INCREASE, spare.pace); assertEquals(0L, spare.minIntervalMillis)
        assertFalse("up to 0" in spare.message, spare.message)
        assertEquals(0L, controller.advise(emptyList(), 0, now).minIntervalMillis)
        assertFailsWith<IllegalArgumentException> { controller.advise(emptyList(), -1, now) }
    }

    @Test fun routesWithoutAMemberLimitHaveNoConcurrencyCeiling() {
        val route = RoutePacingUseCase()
        val opus = RouteTarget("anthropic", "claude-opus-test")
        fun account(id: String, windows: List<WindowPacing>) = AccountPacing(
            AccountRecord(id, AccountIdentity("team", "anthropic", id, "org"), id, 1, false, null, 0, AccountState.READY), windows)
        val wide = listOf("a", "b", "c").map { account(it, windows(null, utilization = .7)) }
        val normal = route.advise(setOf(opus), wide, 0, now)
        assertEquals(Triple(Pace.NORMAL, 0, 0L), Triple(normal.pace, normal.maxConcurrent, normal.minIntervalMillis))
        assertEquals(0, route.advise(setOf(opus), wide.take(1), 0, now).maxConcurrent, "A single account sets no ceiling either")
        val slow = route.advise(setOf(opus), listOf("a", "b", "c").map { account(it, windows(.7)) }, 0, now)
        assertEquals(Triple(Pace.SLOW_DOWN, 0, 2_000L), Triple(slow.pace, slow.maxConcurrent, slow.minIntervalMillis))
        assertEquals(2, route.advise(setOf(opus), listOf("a", "b", "c").map { account(it, windows(.7)) }, 2, now).maxConcurrent)
    }

    @Test fun easingBetweenSlowDownLevelsWaitsLikeAnyOtherRecovery() {
        val hysteresis = PacingHysteresisUseCase()
        val severe = controller.advise(listOf(windows(.1)), 0, now)
        val light = controller.advise(listOf(windows(.8)), 0, now)
        fun memory(advice: PacingAdvice, recovery: PacingRecovery? = null) = PacingMemory(advice, now + 1_000_000, recovery)
        assertEquals(severe, hysteresis.decide(memory(light), severe, now).advice, "A stronger level applies at once")
        val pending = hysteresis.decide(memory(severe), light, now)
        assertEquals(severe, pending.advice)
        assertEquals(severe, hysteresis.decide(memory(severe, pending.recovery), light, now + 119_999).advice)
        assertEquals(light, hysteresis.decide(memory(severe, pending.recovery), light, now + 120_000).advice)
        val moderate = controller.advise(listOf(windows(.3)), 0, now)
        val flapped = hysteresis.decide(memory(severe, pending.recovery), moderate, now + 60_000)
        assertEquals(severe, flapped.advice)
        assertEquals(now + 60_000, flapped.recovery!!.startedAtMillis, "Another SLOW_DOWN level restarts the recovery")
        assertEquals(severe, hysteresis.decide(memory(severe, flapped.recovery), moderate, now + 120_000).advice)
    }

    @Test fun theObservedPeakIsHeldUnderAThrottleAndDecaysOtherwise() {
        val observed = ObservedConcurrencyUseCase()
        val normal = PacingAdvice(Pace.NORMAL, 0, 0, "")
        val slow = PacingAdvice(Pace.SLOW_DOWN, 3, 2_000, "")
        assertEquals(5, observed.next(null, null, 5))
        assertEquals(4, observed.next(5, normal, 0), "A gap between turns only decays the peak")
        assertEquals(6, observed.next(5, normal, 6))
        assertEquals(6, observed.next(6, slow, 3), "A binding throttle cannot shrink its own baseline")
        assertEquals(5, observed.next(6, slow, 1), "Demand below the throttle's cap decays the peak")
        assertEquals(5, observed.next(5, PacingAdvice(Pace.PAUSE, 1, 30_000, ""), 1))
        assertEquals(5, observed.next(6, PacingAdvice(Pace.SLOW_DOWN, 0, 2_000, ""), 2), "Without a cap nothing is binding: the peak decays")
        assertEquals(0, observed.next(null, null, -3))
        assertEquals(ObservedConcurrencyUseCase.MAX_OBSERVED_CONCURRENCY, observed.next(null, null, 10_000))
    }

    @Test fun zeroMeansNoLimitWhenCombiningMemberGroupAndRunCeilings() {
        assertEquals(0, MemberPolicy("m").maxConcurrent, "Members are unlimited by default")
        assertEquals(0, concurrencyCeiling(listOf(0, 0)))
        assertEquals(3, concurrencyCeiling(listOf(0, 5, 3)))
        val group = GroupAllowance(TeamGroup("g", "G", 0, 4), false, GroupUsage(0, 0))
        val open = GroupAllowance(TeamGroup("o", "Open"), false, GroupUsage(0, 0))
        assertEquals(4, CheckGroupAdmissionUseCase().maximum(listOf(group, open), 0), "A group limit applies to an unlimited member")
        assertEquals(0, CheckGroupAdmissionUseCase().maximum(listOf(open), 0))
        assertEquals(2, CheckGroupAdmissionUseCase().maximum(listOf(group), 2))
        val runs = CheckRunPacingUseCase()
        assertEquals(0, runs.limits(emptyList(), 0).maxConcurrent)
        assertEquals(1, runs.limits(listOf(RunPacingPolicy("r", RunPolicy(maxConcurrent = 1), 0)), 0).maxConcurrent)
    }
}
