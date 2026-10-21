package me.nathanfallet.teamcode.domain

import me.nathanfallet.teamcode.domain.accounts.*
import me.nathanfallet.teamcode.domain.capacity.*
import me.nathanfallet.teamcode.domain.routing.RouteTarget
import me.nathanfallet.teamcode.domain.team.TeamFailure
import kotlin.test.*

class QuotaPressureSettingTest {
    private val now = 180_000L

    @Test fun defaultsToNinetyFivePercentAndRefusesOutOfRangeValues() {
        assertEquals(95, PacingSettings().quotaPressurePercent)
        assertEquals(.95, PacingSettings().quotaPressureThreshold)
        assertEquals(.95, DEFAULT_QUOTA_PRESSURE_THRESHOLD)
        assertEquals(.5, PacingSettings(quotaPressurePercent = 50).quotaPressureThreshold)
        assertEquals(1.0, PacingSettings(quotaPressurePercent = 100).quotaPressureThreshold)
        for (invalid in listOf(-1, 0, 49, 101, 1000)) {
            assertEquals("invalid_quota_pressure", assertFailsWith<TeamFailure> { PacingSettings(quotaPressurePercent = invalid) }.code)
        }
    }

    @Test fun forecastTreatsTheConfiguredThresholdAsExhaustion() {
        val observation = QuotaObservation(.85, now, now + 3_600_000)
        assertEquals(QuotaForecast.State.IDLE, ForecastQuotaUseCase().forecast(observation, 0.0, now).state)
        assertEquals(QuotaForecast.State.EXHAUSTED, ForecastQuotaUseCase().forecast(observation, 0.0, now, .8).state)
        val series = BuildForecastSeriesUseCase(.8)(AccountQuota("a", "five_hour", .85, now, now + 3_600_000), emptyList(), now)
        assertEquals(QuotaForecast.State.EXHAUSTED, series.forecast.state)
    }

    @Test fun pacingPausesAtTheConfiguredThresholdOnly() {
        val record = AccountRecord("anthropic-0", AccountIdentity("team", "anthropic", "identity", "org"), "Test", 1, false, null, 0, AccountState.READY)
        fun pacing(threshold: Double) = ForecastHistoryUseCase(threshold)
        fun account(threshold: Double) = AccountPacing(record, listOf("five_hour", "seven_day").map { window ->
            pacing(threshold).evaluate(listOf(.85, .85, .85).mapIndexed { index, used ->
                AccountQuota(record.id, window, used, now - 120_000 + index * 60_000, now + 3_600_000)
            }, now)!!
        })
        val target = RouteTarget("anthropic", "claude-opus-test")
        assertNotEquals(Pace.PAUSE, RoutePacingUseCase().advise(setOf(target), listOf(account(.95)), 4, now).pace)
        assertEquals(Pace.PAUSE, RoutePacingUseCase(pressureThreshold = .8).advise(setOf(target), listOf(account(.8)), 4, now).pace)
    }
}
