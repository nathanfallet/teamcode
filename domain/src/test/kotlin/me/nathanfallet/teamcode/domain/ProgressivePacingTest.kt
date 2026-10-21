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

    @Test fun slowDownFollowsTheProjectedOvershoot() {
        val light = controller.advise(listOf(windows(.75)), 0, now, observedConcurrency = 6)
        assertEquals(PacingAdvice(Pace.SLOW_DOWN, 3, 2_000, light.message), light)
        assertTrue("at most 3 requests" in light.message && "one request" !in light.message, light.message)
        val moderate = controller.advise(listOf(windows(.3)), 0, now, observedConcurrency = 6)
        assertEquals(2 to 5_000L, moderate.maxConcurrent to moderate.minIntervalMillis)
        val severe = controller.advise(listOf(windows(.1)), 0, now, observedConcurrency = 6)
        assertEquals(1 to 10_000L, severe.maxConcurrent to severe.minIntervalMillis)
        assertTrue("one request at a time" in severe.message, severe.message)
        assertEquals(SlowDownLevel.LIGHT, SlowDownLevel.of(.5))
        assertEquals(SlowDownLevel.MODERATE, SlowDownLevel.of(.25))
        assertEquals(SlowDownLevel.SEVERE, SlowDownLevel.of(.2499))
        assertEquals(SlowDownLevel.SEVERE, SlowDownLevel.of(Double.NaN))
        // An overshooting window without a known reset cannot be graded: it counts as severe, whatever the other window says.
        val unknown = windows(.75).let { listOf(it[0], WindowPacing(AccountQuota("a", "seven_day", .6, now, null), QuotaForecast(now + 1_000, 10.0, QuotaForecast.State.EXHAUSTS_FIRST))) }
        assertEquals(1 to 10_000L, controller.advise(listOf(unknown), 0, now, observedConcurrency = 6).let { it.maxConcurrent to it.minIntervalMillis })
    }

    @Test fun slowDownIsBoundedAndNeverLoosensAsTheOvershootGrows() {
        assertEquals(2, controller.advise(listOf(windows(.9)), 2, now, observedConcurrency = 10).maxConcurrent, "The applicable limit still bounds it")
        assertEquals(1, controller.advise(listOf(windows(.9)), 0, now, observedConcurrency = 0).maxConcurrent, "A slowdown always permits one request")
        assertEquals(1, controller.advise(listOf(windows(.9)), 0, now, observedConcurrency = 1).maxConcurrent)
        val advice = (100 downTo 0).map { controller.advise(listOf(windows(it / 100.0)), 0, now, observedConcurrency = 16) }
        advice.zipWithNext().forEach { (wider, narrower) -> assertTrue(PACING_STRICTNESS.compare(narrower, wider) >= 0, "$wider then $narrower") }
        assertTrue(advice.all { it.pace == Pace.SLOW_DOWN && it.maxConcurrent in 1..8 && it.minIntervalMillis in 2_000..10_000 })
        assertEquals(setOf(8, 4, 1), advice.map { it.maxConcurrent }.toSet())
    }

    @Test fun theLeastPressuredSlowAccountIsChosenAndPauseStaysTheHardBound() {
        assertEquals(SlowDownLevel.LIGHT.intervalMillis, controller.advise(listOf(windows(.1), windows(.8)), 0, now, observedConcurrency = 4).minIntervalMillis)
        val exhausted = listOf(WindowPacing(AccountQuota("a", "five_hour", .95, now, reset), QuotaForecast(now, null, QuotaForecast.State.EXHAUSTED)))
        val pause = controller.advise(listOf(exhausted), 0, now, observedConcurrency = 20)
        assertEquals(Triple(Pace.PAUSE, 1, 30_000L), Triple(pause.pace, pause.maxConcurrent, pause.minIntervalMillis))
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
        val slow = route.advise(setOf(opus), listOf("a", "b", "c").map { account(it, windows(.7)) }, 0, now, observedConcurrency = 5)
        assertEquals(Triple(Pace.SLOW_DOWN, 3, 2_000L), Triple(slow.pace, slow.maxConcurrent, slow.minIntervalMillis))
        assertEquals(2, route.advise(setOf(opus), listOf("a", "b", "c").map { account(it, windows(.7)) }, 2, now, observedConcurrency = 5).maxConcurrent)
    }

    @Test fun easingBetweenSlowDownLevelsWaitsLikeAnyOtherRecovery() {
        val hysteresis = PacingHysteresisUseCase()
        val severe = controller.advise(listOf(windows(.1)), 0, now, observedConcurrency = 6)
        val light = controller.advise(listOf(windows(.8)), 0, now, observedConcurrency = 6)
        val narrowLight = controller.advise(listOf(windows(.8)), 0, now, observedConcurrency = 4)
        fun memory(advice: PacingAdvice, recovery: PacingRecovery? = null) = PacingMemory(advice, now + 1_000_000, recovery)
        assertEquals(severe, hysteresis.decide(memory(light), severe, now).advice, "A stronger level applies at once")
        assertEquals(narrowLight, hysteresis.decide(memory(light), narrowLight, now).advice, "Fewer requests apply at once")
        val pending = hysteresis.decide(memory(severe), light, now)
        assertEquals(severe, pending.advice)
        assertEquals(severe, hysteresis.decide(memory(severe, pending.recovery), light, now + 119_999).advice)
        assertEquals(light, hysteresis.decide(memory(severe, pending.recovery), light, now + 120_000).advice)
        assertEquals(narrowLight, hysteresis.decide(memory(narrowLight), light, now).advice, "More requests wait for stability")
        val moderate = controller.advise(listOf(windows(.3)), 0, now, observedConcurrency = 6)
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
