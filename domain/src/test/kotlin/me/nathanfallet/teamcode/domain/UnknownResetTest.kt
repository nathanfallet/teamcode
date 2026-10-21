package me.nathanfallet.teamcode.domain

import me.nathanfallet.teamcode.domain.capacity.*
import kotlin.test.*

class UnknownResetTest {
    @Test fun unknownResetNeverPromisesHeadroomButFreshExhaustionRemainsActionable() {
        val now = 1_000_000L
        val history = ForecastHistoryUseCase()
        fun window(used: Double) = history.evaluate((0..2).map { index ->
            AccountQuota("account", "five_hour", used, now - 120_000 + index * 60_000, null)
        }, now)!!
        val spare = window(.1)
        assertEquals(QuotaForecast.State.UNKNOWN, spare.forecast.state)
        assertNull(spare.forecast.reachesThresholdAtMillis)
        assertNull(spare.forecast.pointsPerHour)
        assertEquals(Pace.UNKNOWN, PacingUseCase().advise(listOf(listOf(spare)), 2, now).pace)
        val full = window(.99)
        assertEquals(QuotaForecast.State.EXHAUSTED, full.forecast.state)
        assertNull(full.forecast.pointsPerHour)
        // A missing weekly window cannot negate a fresh, exhausted session window.
        assertEquals(Pace.PAUSE, PacingUseCase().advise(listOf(listOf(full)), 2, now).pace)
        val stale = history.evaluate(listOf(full.quota), now + MAX_QUOTA_OBSERVATION_AGE_MILLIS + 1)!!
        assertEquals(QuotaForecast.State.STALE, stale.forecast.state)
        assertEquals(Pace.UNKNOWN, PacingUseCase().advise(listOf(listOf(stale)), 2, now + MAX_QUOTA_OBSERVATION_AGE_MILLIS + 1).pace)
    }

    @Test fun currentWindowsUseKnownResetOrBoundedUnknownFreshness() {
        val now = 1_000_000L
        val quota = AccountQuota("account", "five_hour", .99, now, null)
        assertTrue(quota.isCurrent(now))
        assertTrue(quota.isCurrent(now + MAX_QUOTA_OBSERVATION_AGE_MILLIS))
        assertFalse(quota.isCurrent(now + MAX_QUOTA_OBSERVATION_AGE_MILLIS + 1))
        assertFalse(quota.isCurrent(now - 1))
        assertTrue(quota.copy(resetsAtMillis = now + 3_600_000).isCurrent(now + 600_000))
        assertFalse(quota.copy(resetsAtMillis = now + 600_000).isCurrent(now + 600_000))
    }
}
