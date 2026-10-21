package me.nathanfallet.teamcode.domain

import me.nathanfallet.teamcode.domain.accounts.*
import me.nathanfallet.teamcode.domain.capacity.*
import me.nathanfallet.teamcode.domain.routing.RouteTarget
import kotlin.test.*

class RoutePacingTest {
    private val now = 180_000L
    private val opus = RouteTarget("anthropic", "claude-opus-test")
    private val astra = RouteTarget("codex", "gpt-6-astra")
    private fun account(provider: String, values: List<Double>, priority: Int = 0, model: String? = null): AccountPacing {
        val record = AccountRecord("$provider-$priority", AccountIdentity("team", provider, "identity", "org"), "Test", 1, false, null, priority, AccountState.READY)
        val windows = listOf("five_hour", "seven_day").map { name ->
            ForecastHistoryUseCase().evaluate(values.mapIndexed { index, used ->
                AccountQuota(record.id, if (model == null) name else "model:$model:$name", used, now - 120_000 + index * 60_000, now + 3_600_000)
            }, now)!!
        }
        return AccountPacing(record, windows)
    }
    @Test fun forecastsOnlyTheActiveProviderAndModel() {
        val controller = RoutePacingUseCase()
        val busy = account("anthropic", listOf(.1, .2, .3), 5)
        val idle = account("codex", listOf(.1, .1, .1))
        assertEquals(Pace.SLOW_DOWN, controller.advise(setOf(opus), listOf(busy, idle), 4, now).pace)
        assertEquals(Pace.UNKNOWN, controller.advise(emptySet(), listOf(idle), 4, now).pace)
        assertEquals(Pace.UNKNOWN, controller.advise(setOf(opus), listOf(idle), 4, now).pace)
        val lowerPriority = account("anthropic", listOf(.1, .1, .1))
        assertEquals(Pace.SLOW_DOWN, controller.advise(setOf(opus), listOf(busy, lowerPriority), 4, now).pace)
        val exhausted = account("anthropic", listOf(1.0, 1.0, 1.0), 5)
        assertEquals(Pace.CAN_INCREASE, controller.advise(setOf(opus), listOf(exhausted, lowerPriority), 4, now).pace)
        assertEquals(16, controller.advise(setOf(opus), listOf(lowerPriority), 16, now).maxConcurrent)
        assertEquals(Pace.PAUSE, controller.advise(setOf(opus), listOf(lowerPriority), 4, now, allowanceAvailable = false).pace)
        val fable = account("anthropic", listOf(.99, .99, .99), model = "fable")
        val mixed = lowerPriority.copy(windows = lowerPriority.windows + fable.windows)
        assertEquals(Pace.CAN_INCREASE, controller.advise(setOf(opus), listOf(mixed), 4, now).pace)
        assertEquals(Pace.PAUSE, controller.advise(setOf(RouteTarget("anthropic", "claude-fable-test")), listOf(mixed), 4, now).pace)
        assertEquals(Pace.SLOW_DOWN, controller.advise(setOf(opus, astra), listOf(busy, idle), 4, now).pace)
        assertEquals(Pace.UNKNOWN, controller.advise(setOf(opus, astra), listOf(lowerPriority), 4, now).pace)
    }
    @Test fun everyAccountTheScopeCanUseCountsWhateverItsPoolOrPriority() {
        val controller = RoutePacingUseCase()
        fun window(id: String, name: String, used: Double, rate: Double, resetIn: Long) =
            WindowPacing(AccountQuota(id, name, used, now, now + resetIn), QuotaForecast(null, rate * 100, if (rate == 0.0) QuotaForecast.State.IDLE else QuotaForecast.State.RESET_FIRST))
        fun account(id: String, pool: String, priority: Int, fiveHour: Double, rate: Double, resetIn: Long) = AccountPacing(
            AccountRecord(id, AccountIdentity("team", "anthropic", id, "org"), id, 1, false, pool, priority, AccountState.READY),
            listOf(window(id, "five_hour", fiveHour, rate, resetIn), window(id, "seven_day", .1, 0.0, 6 * 86_400_000L)))
        // The home account empties in 30 minutes and resets in an hour: alone, a gap.
        val home = account("home", "home", 0, .9, .1, 3_600_000)
        assertEquals(Pace.SLOW_DOWN, controller.advise(setOf(opus), listOf(home), 0, now).pace)
        // An idle overflow account of lower priority bridges it: no slowdown.
        val reserve = account("reserve", "reserve", 10, .1, 0.0, 4 * 3_600_000L)
        val advice = controller.advise(setOf(opus), listOf(home, reserve), 0, now)
        assertNotEquals(Pace.SLOW_DOWN, advice.pace)
        assertEquals(0L, advice.minIntervalMillis)
    }
    @Test fun recoveryMustStayStableAndPressureOrMissingDataTakesEffectImmediately() {
        val useCase = PacingHysteresisUseCase()
        fun advice(pace: Pace) = PacingAdvice(pace, if (pace == Pace.SLOW_DOWN) 1 else 4, 0, pace.name)
        fun memory(pace: Pace, recovery: PacingRecovery? = null) = PacingMemory(advice(pace), now + 1_000_000, recovery)
        val slow = advice(Pace.SLOW_DOWN)
        assertEquals(slow, useCase.decide(memory(Pace.CAN_INCREASE), slow, now).advice)
        val first = useCase.decide(memory(Pace.SLOW_DOWN), advice(Pace.CAN_INCREASE), now)
        assertEquals(Pace.SLOW_DOWN, first.advice.pace)
        assertEquals(PacingRecovery(Pace.CAN_INCREASE, now), first.recovery)
        assertEquals(Pace.SLOW_DOWN, useCase.decide(memory(Pace.SLOW_DOWN, first.recovery), advice(Pace.CAN_INCREASE), now + 119_999).advice.pace)
        assertEquals(Pace.CAN_INCREASE, useCase.decide(memory(Pace.SLOW_DOWN, first.recovery), advice(Pace.CAN_INCREASE), now + 120_000).advice.pace)
        val interrupted = useCase.decide(memory(Pace.SLOW_DOWN, first.recovery), slow, now + 100_000)
        assertNull(interrupted.recovery)
        assertEquals(now + 110_000, useCase.decide(memory(Pace.SLOW_DOWN), advice(Pace.CAN_INCREASE), now + 110_000).recovery!!.startedAtMillis)
        assertEquals(Pace.UNKNOWN, useCase.decide(memory(Pace.CAN_INCREASE), advice(Pace.UNKNOWN), now).advice.pace)
        assertEquals(Pace.CAN_INCREASE, useCase.decide(null, advice(Pace.CAN_INCREASE), now).advice.pace)
        assertEquals(Pace.CAN_INCREASE, useCase.decide(memory(Pace.SLOW_DOWN).copy(expiresAtMillis = now), advice(Pace.CAN_INCREASE), now).advice.pace)
    }
    @Test fun knownExhaustionDoesNotRequireLearningARateAndTinyRatesCannotOverflowEta() {
        val forecast = ForecastQuotaUseCase()
        val observation = QuotaObservation(.99, now, now + 3_600_000)
        assertEquals(QuotaForecast.State.EXHAUSTED, forecast.forecast(observation, null, now).state)
        assertEquals(QuotaForecast.State.STALE, forecast.forecast(observation, null, now + 400_000).state)
        val tiny = forecast.forecast(observation.copy(utilization = .1), Double.MIN_VALUE, now)
        assertEquals(Long.MAX_VALUE, tiny.reachesThresholdAtMillis)
        assertEquals(QuotaForecast.State.RESET_FIRST, tiny.state)
    }
    @Test fun knownProviderLimitOverridesModelNameHeuristicsForAdmissionAndAdvice() {
        val idle = account("codex", listOf(.1, .1, .1))
        val cap = account("codex", listOf(1.0, 1.0, 1.0), model = "base_model_inference")
        val mixed = idle.copy(windows = idle.windows + cap.windows,
            modelLimits = mapOf("gpt-reserve" to "base_model_inference", "gpt-base_model_inference" to "premium"))
        val quota = cap.windows.last().quota
        assertTrue(quota.appliesTo("gpt-reserve", "base_model_inference"))
        assertFalse(quota.appliesTo("gpt-base_model_inference", "premium"))
        assertTrue(idle.windows.last().quota.appliesTo("gpt-reserve", "base_model_inference"))
        val controller = RoutePacingUseCase()
        assertEquals(Pace.PAUSE, controller.advise(setOf(RouteTarget("codex", "gpt-reserve")), listOf(mixed), 4, now).pace)
        assertEquals(Pace.CAN_INCREASE, controller.advise(setOf(RouteTarget("codex", "gpt-base_model_inference")), listOf(mixed), 4, now).pace)
        assertEquals(Pace.CAN_INCREASE, controller.advise(setOf(astra), listOf(mixed), 4, now).pace)
    }

}
