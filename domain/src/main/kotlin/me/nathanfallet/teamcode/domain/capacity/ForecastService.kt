package me.nathanfallet.teamcode.domain.capacity

import me.nathanfallet.teamcode.domain.team.Principal

/** Public administration metadata only; credentials and provider identities are never selected. */
data class ForecastAccount(val id: String, val provider: String, val displayName: String, val state: String, val disabled: Boolean, val poolId: String?, val email: String? = null)

/** An observed window, its disconnected history segments and the controller's window estimator. */
data class ForecastSeries(val quota: AccountQuota, val forecast: QuotaForecast, val segments: List<List<AccountQuota>>) {
    val learningSamples: Int get() = segments.lastOrNull()?.size ?: 0
    val learningSpanMillis: Long get() = segments.lastOrNull()?.let { it.last().observedAtMillis - it.first().observedAtMillis } ?: 0
}

/** A bounded point-in-time administrative view, never an aggregate of subscription percentages. */
data class ForecastSnapshot(val generatedAtMillis: Long, val accounts: List<ForecastAccount>, val account: ForecastAccount?, val windows: List<ForecastSeries>, val truncated: Boolean, val pressureThreshold: Double = QUOTA_PRESSURE_THRESHOLD)

/** The implementation revalidates current interactive administrator authority and team ownership. */
interface ForecastService {
    suspend fun load(actor: Principal, accountId: String? = null): ForecastSnapshot
}

/** Join the current observation with its retained history without inventing missing samples. */
class BuildForecastSeriesUseCase {
    operator fun invoke(current: AccountQuota, history: List<AccountQuota>, now: Long): ForecastSeries {
        val samples = history.filter { it.accountId == current.accountId && it.window == current.window && it.observedAtMillis < current.observedAtMillis } + current
        val useCase = ForecastHistoryUseCase()
        val baseline = ForecastQuotaUseCase().forecast(QuotaObservation(current.utilization, current.observedAtMillis, current.resetsAtMillis), null, now)
        val forecast = if (baseline.state == QuotaForecast.State.STALE) baseline else useCase.evaluate(samples, now)?.forecast ?: baseline
        return ForecastSeries(current, forecast, useCase.segments(samples, now))
    }
}
