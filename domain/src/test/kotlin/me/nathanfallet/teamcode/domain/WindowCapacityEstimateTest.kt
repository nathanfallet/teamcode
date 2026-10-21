package me.nathanfallet.teamcode.domain

import me.nathanfallet.teamcode.domain.capacity.*
import kotlin.test.*

/** A window's full capacity in API-equivalent, learned from pairs of utilization readings and the priced tokens between them. */
class WindowCapacityEstimateTest {
    private val hour = 3_600_000L
    private val start = 500_000 * hour
    private val now = start + 10 * hour
    private val reset = start + 100 * hour
    private fun sample(hoursIn: Int, utilization: Double, resetsAt: Long? = reset) = QuotaSample(utilization, start + hoursIn * hour, resetsAt)
    /** 600,000 micro-dollars ($0.60) settled in each hour from [start]. */
    private fun hours(count: Int, micros: Long = 600_000, unpricedAt: Int? = null) =
        (0 until count).map { HourCost(start / hour + it, micros, unpriced = it == unpricedAt) }
    private fun estimate(history: List<QuotaSample>, costs: List<HourCost>) = EstimateWindowCapacityUseCase().estimate("seven_day", history, costs, now)

    @Test fun eachRiseOfFivePointsOrMoreIsOneSampleAndTheMedianIsKept() {
        // +6 points per hour for $0.60: the full window is worth 0.6 / 0.06 = $10.
        val result = estimate(listOf(sample(0, .10), sample(1, .16), sample(2, .22), sample(3, .28)), hours(3))
        assertEquals(3, result.samples)
        assertEquals(10_000_000, result.fullWindowMicros)
    }

    @Test fun fewerThanThreeSamplesGiveNoEstimate() {
        val result = estimate(listOf(sample(0, .10), sample(1, .16), sample(2, .22)), hours(2))
        assertEquals(2, result.samples)
        assertNull(result.fullWindowMicros)
    }

    @Test fun smallRisesAccumulateUntilFivePoints() {
        // Two hours of +3 points make one sample of 6 points for $1.20: the full window is worth 1.2 / 0.06 = $20.
        val history = (0..6).map { sample(it, .10 + .03 * it) }
        val result = estimate(history, hours(6))
        assertEquals(3, result.samples)
        assertEquals(20_000_000, result.fullWindowMicros)
    }

    @Test fun pairsNeverSpanAResetOrADecrease() {
        val other = reset + 168 * hour
        val history = listOf(sample(0, .10), sample(1, .16, other), sample(2, .22, other), sample(3, .05, other), sample(4, .11, other),
            sample(5, .17, other), sample(6, .23, other))
        // The lone reading of the first period pairs with nothing; after the decrease the anchor restarts at 5%.
        // Samples: 1→2, 3→4, 4→5 and 5→6, each 6 points for $0.60.
        val result = estimate(history, hours(7))
        assertEquals(4, result.samples)
        assertEquals(10_000_000, result.fullWindowMicros)
    }

    @Test fun readingsWithoutAKnownResetArePairedWithNothing() {
        assertEquals(0, estimate(listOf(sample(0, .10, null), sample(1, .16, null), sample(2, .22, null), sample(3, .28, null)), hours(3)).samples)
    }

    @Test fun aPairWithUnpricedOrNoTrafficIsNoSample() {
        val history = listOf(sample(0, .10), sample(1, .16), sample(2, .22), sample(3, .28), sample(4, .34))
        // Hour 1 holds an unpriced model: the pair (1→2) is skipped. Hour 3 has no traffic at all (usage outside TeamCode).
        val costs = hours(4, unpricedAt = 1).filterNot { it.hourNumber == start / hour + 3 }
        assertEquals(2, estimate(history, costs).samples)
    }

    @Test fun resetsThatJitterWithinAMinuteShareAPeriod() {
        val history = (0..3).map { sample(it, .10 + .06 * it, reset + it * 7_000L) }
        assertEquals(3, estimate(history, hours(3)).samples)
    }

    @Test fun aPairSpansAtLeastAnHour() {
        // +6 points every 20 minutes: a pair closes only once an hour has passed (18 points for $0.60: a $3.33 window).
        val history = (0..9).map { QuotaSample(.10 + .06 * it, start + it * hour / 3, reset) }
        val result = estimate(history, hours(3))
        assertEquals(3, result.samples)
        assertEquals(3_333_333, result.fullWindowMicros)
    }

    @Test fun partialHoursArePricedInProportion() {
        // Readings at :30: each pair covers half of two hours, so $0.60 again per six points.
        val history = (0..3).map { QuotaSample(.10 + .06 * it, start + it * hour + hour / 2, reset) }
        val result = estimate(history, hours(4))
        assertEquals(3, result.samples)
        assertEquals(10_000_000, result.fullWindowMicros)
    }
}
