package me.nathanfallet.teamcode.domain

import me.nathanfallet.teamcode.domain.capacity.*
import kotlin.test.*

class ForecastContinuityTest {
    private val now = 2_000_000L
    private fun sample(age: Long, utilization: Double) = AccountQuota("account", "five_hour", utilization, now - age, now + 3_600_000)

    @Test fun aFreshReadingAfterAnUnobservedGapCannotInheritALearnedBurnRate() {
        val value = ForecastHistoryUseCase().evaluate(listOf(sample(600_000, .1), sample(540_000, .2), sample(0, .3)), now)!!
        assertEquals(QuotaForecast.State.UNKNOWN, value.forecast.state)
        assertNull(value.forecast.pointsPerHour)
        assertNull(value.forecast.reachesThresholdAtMillis)
    }

    @Test fun aContinuousRecentSegmentLearnsWithoutAveragingAcrossTheEarlierGap() {
        val value = ForecastHistoryUseCase().evaluate(listOf(sample(900_000, .1), sample(840_000, .2), sample(120_000, .3), sample(60_000, .4), sample(0, .5)), now)!!
        assertEquals(600.0, assertNotNull(value.forecast.pointsPerHour, "The recent continuous segment must learn its own rate"), .00001)
        assertEquals(QuotaForecast.State.EXHAUSTS_FIRST, value.forecast.state)
    }

    @Test fun aLowerReadingStartsANewLearningSegmentWithoutPoisoningTheNextHour() {
        val value = ForecastHistoryUseCase().evaluate(listOf(sample(180_000, .8), sample(120_000, .1), sample(60_000, .2), sample(0, .3)), now)!!
        assertEquals(600.0, assertNotNull(value.forecast.pointsPerHour, "Clean samples after a decrease must start learning again"), .00001)
        assertEquals(QuotaForecast.State.EXHAUSTS_FIRST, value.forecast.state)
    }

    @Test fun chartSegmentsBreakOnResetGapAndDecreaseAndIgnoreFutureSamples() {
        val values = listOf(sample(900_000, .1), sample(840_000, .2), sample(480_000, .3),
            sample(420_000, .1), sample(360_000, .2).copy(resetsAtMillis = now + 7_200_000),
            sample(300_000, .3).copy(resetsAtMillis = now + 7_200_000), sample(-1, .9))
        assertEquals(listOf(2, 1, 1, 2), ForecastHistoryUseCase().segments(values, now).map { it.size })
    }

    @Test fun snapshotsKeepFutureAndStaleCurrentReadingsConservative() {
        val builder = BuildForecastSeriesUseCase()
        val history = listOf(sample(120_000, .1), sample(60_000, .2), sample(0, .3))
        val future = builder(sample(-1, .4), history, now)
        assertEquals(QuotaForecast.State.STALE, future.forecast.state)
        assertNull(future.forecast.reachesThresholdAtMillis)
        val stale = builder(sample(3_700_000, .3), emptyList(), now)
        assertEquals(QuotaForecast.State.STALE, stale.forecast.state)
        assertTrue(stale.segments.isEmpty())
    }
}
