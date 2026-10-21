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

    private val hour = 3_600_000L

    /** One account, both windows; the five-hour one burns at [rate] per hour from [utilization]. */
    private fun account(id: String = "a", utilization: Double = .5, rate: Double = 0.0) = AccountPacing(
        AccountRecord(id, AccountIdentity("team", "anthropic", id, "org"), id, 1, false, null, 0, AccountState.READY), listOf(
            WindowPacing(AccountQuota(id, "five_hour", utilization, now, reset), QuotaForecast(null, rate * 100, if (rate == 0.0) QuotaForecast.State.IDLE else QuotaForecast.State.RESET_FIRST)),
            WindowPacing(AccountQuota(id, "seven_day", .1, now, now + 86_400_000), QuotaForecast(null, 0.0, QuotaForecast.State.IDLE)),
        ))

    @Test fun pacingNeverLowersTheConcurrencyLimit() {
        assertEquals(2, controller.advise(listOf(account(rate = 5.0)), 2, now).maxConcurrent, "An explicit member, group or run limit is kept")
        val advice = (1..100).map { controller.advise(listOf(account(rate = it / 10.0)), 0, now) }
        advice.zipWithNext().forEach { (wider, narrower) -> assertTrue(PACING_STRICTNESS.compare(narrower, wider) >= 0, "$wider then $narrower") }
        assertTrue(advice.filter { it.pace == Pace.SLOW_DOWN }.all { it.maxConcurrent == UNLIMITED_CONCURRENCY && it.minIntervalMillis in 2_000..10_000 })
        assertEquals(setOf(2_000L, 5_000L, 10_000L), advice.filter { it.pace == Pace.SLOW_DOWN }.map { it.minIntervalMillis }.toSet())
        val pause = controller.advise(listOf(account(utilization = .95)), 0, now)
        assertEquals(Triple(Pace.PAUSE, UNLIMITED_CONCURRENCY, 30_000L), Triple(pause.pace, pause.maxConcurrent, pause.minIntervalMillis))
        assertEquals(4, controller.advise(listOf(account(utilization = .95)), 4, now).maxConcurrent)
        val route = RoutePacingUseCase().advise(setOf(RouteTarget("anthropic", "claude-opus-test")), emptyList(), 0, now, allowanceAvailable = false)
        assertEquals(Triple(Pace.PAUSE, UNLIMITED_CONCURRENCY, 30_000L), Triple(route.pace, route.maxConcurrent, route.minIntervalMillis))
    }

    @Test fun routesWithoutAMemberLimitHaveNoConcurrencyCeiling() {
        val route = RoutePacingUseCase()
        val opus = RouteTarget("anthropic", "claude-opus-test")
        val wide = listOf("a", "b", "c").map { account(it, utilization = .3) }
        val idle = route.advise(setOf(opus), wide, 0, now)
        assertEquals(Triple(Pace.CAN_INCREASE, 0, 0L), Triple(idle.pace, idle.maxConcurrent, idle.minIntervalMillis))
        assertEquals(0, route.advise(setOf(opus), wide.take(1), 0, now).maxConcurrent, "A single account sets no ceiling either")
        // Three accounts with 0.25 left each last 0.75 / 1.0 = 45 minutes before a reset in one hour: a light slowdown.
        val slow = route.advise(setOf(opus), listOf("a", "b", "c").map { account(it, utilization = .7, rate = 1.0 / 3) }, 0, now)
        assertEquals(Triple(Pace.SLOW_DOWN, 0, 2_000L), Triple(slow.pace, slow.maxConcurrent, slow.minIntervalMillis))
        assertEquals(2, route.advise(setOf(opus), listOf("a", "b", "c").map { account(it, utilization = .7, rate = 1.0 / 3) }, 2, now).maxConcurrent)
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
