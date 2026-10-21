package me.nathanfallet.teamcode.domain.capacity

/** An observation is one account/window; percentages from different plans are never summed. */
data class QuotaObservation(val utilization: Double, val observedAtMillis: Long, val resetsAtMillis: Long?)
data class QuotaForecast(val reachesThresholdAtMillis: Long?, val pointsPerHour: Double?, val state: State) {
    enum class State { UNKNOWN, STALE, EXHAUSTED, IDLE, RESET_FIRST, EXHAUSTS_FIRST }
}

class ForecastQuotaUseCase(private val maximumAgeMillis: Long = MAX_QUOTA_OBSERVATION_AGE_MILLIS) {
    fun forecast(observation: QuotaObservation?, ratePerHour: Double?, nowMillis: Long, threshold: Double = DEFAULT_QUOTA_PRESSURE_THRESHOLD): QuotaForecast {
        require(threshold.isFinite() && threshold > 0 && threshold <= 1)
        if (observation == null ||
            !observation.utilization.isFinite() || observation.utilization !in 0.0..1.0) {
            return QuotaForecast(null, null, QuotaForecast.State.UNKNOWN)
        }
        if (observation.observedAtMillis > nowMillis || observation.resetsAtMillis?.let { it <= nowMillis } == true ||
            nowMillis - observation.observedAtMillis > maximumAgeMillis) {
            return QuotaForecast(null, null, QuotaForecast.State.STALE)
        }
        val validRate = ratePerHour?.takeIf { observation.resetsAtMillis != null && it.isFinite() && it >= 0 }
        if (observation.utilization >= threshold) return QuotaForecast(nowMillis, validRate?.times(100), QuotaForecast.State.EXHAUSTED)
        // A missing epoch can hide a reset between samples. Never promise spare capacity or an ETA.
        if (observation.resetsAtMillis == null) return QuotaForecast(null, null, QuotaForecast.State.UNKNOWN)
        if (validRate == null) return QuotaForecast(null, null, QuotaForecast.State.UNKNOWN)
        if (validRate == 0.0) return QuotaForecast(null, 0.0, QuotaForecast.State.IDLE)
        val duration = ((threshold - observation.utilization) / validRate * 3_600_000).toLong()
        val eta = if (observation.observedAtMillis > 0 && duration > Long.MAX_VALUE - observation.observedAtMillis) Long.MAX_VALUE else observation.observedAtMillis + duration
        return QuotaForecast(eta, validRate * 100,
            if (eta < observation.resetsAtMillis) QuotaForecast.State.EXHAUSTS_FIRST else QuotaForecast.State.RESET_FIRST)
    }
}
