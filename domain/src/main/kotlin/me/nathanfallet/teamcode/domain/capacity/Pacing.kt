package me.nathanfallet.teamcode.domain.capacity

enum class Pace { UNKNOWN, NORMAL, SLOW_DOWN, CAN_INCREASE, PAUSE }
data class WindowPacing(val quota: AccountQuota, val forecast: QuotaForecast)
data class PacingAdvice(val pace: Pace, val maxConcurrent: Int, val minIntervalMillis: Long, val message: String)
data class AgentDirective(val id: String, val memberId: String, val advice: PacingAdvice, val createdAtMillis: Long, val expiresAtMillis: Long)

class ForecastHistoryUseCase(private val threshold: Double = DEFAULT_QUOTA_PRESSURE_THRESHOLD) {
    /** Curves and rate learning share the same reset, gap and monotonicity boundaries. */
    fun segments(history: List<AccountQuota>, now: Long): List<List<AccountQuota>> {
        val sorted = history.filter { it.observedAtMillis in maxOf(0, now - 3_600_000)..now && it.utilization.isFinite() && it.utilization in 0.0..1.0 }
            .sortedBy { it.observedAtMillis }
        val latest = sorted.lastOrNull() ?: return emptyList()
        val output = mutableListOf<MutableList<AccountQuota>>()
        sorted.filter { it.accountId == latest.accountId && it.window == latest.window }.forEach { sample ->
            val previous = output.lastOrNull()?.lastOrNull()
            if (previous == null || sample.resetsAtMillis != previous.resetsAtMillis || sample.utilization < previous.utilization ||
                sample.observedAtMillis - previous.observedAtMillis !in 1..MAX_QUOTA_OBSERVATION_AGE_MILLIS) output.add(mutableListOf())
            output.last().add(sample)
        }
        return output
    }
    fun evaluate(history: List<AccountQuota>, now: Long): WindowPacing? {
        val sorted = history.filter { it.observedAtMillis <= now }.sortedBy { it.observedAtMillis }
        val latest = sorted.lastOrNull() ?: return null
        val sameWindow = segments(sorted, now).lastOrNull().orEmpty()
        var rate: Double? = null
        if (latest.resetsAtMillis != null && sameWindow.size >= 3 && sameWindow.last().observedAtMillis - sameWindow.first().observedAtMillis >= 60_000) {
            var weighted = 0.0
            var weight = 0.0
            var monotonic = true
            sameWindow.zipWithNext().forEach { (a, b) ->
                val elapsed = b.observedAtMillis - a.observedAtMillis
                if (b.utilization < a.utilization) monotonic = false
                if (elapsed > 0) { weighted += b.utilization - a.utilization; weight += elapsed }
            }
            if (monotonic && weight > 0) rate = weighted / weight * 3_600_000
        }
        return WindowPacing(latest, ForecastQuotaUseCase().forecast(QuotaObservation(latest.utilization, latest.observedAtMillis, latest.resetsAtMillis), rate, now, threshold))
    }
}

/** Chooses the least-constrained eligible account, respecting every window on that account. */
class PacingUseCase {
    fun advise(accounts: List<List<WindowPacing>>, maximum: Int): PacingAdvice {
        require(maximum in 1..32)
        if (accounts.isEmpty()) return advice(Pace.UNKNOWN, maximum)
        val states = accounts.map { windows ->
            when {
                windows.any { it.forecast.state == QuotaForecast.State.EXHAUSTED } -> Pace.PAUSE
                !windows.map { it.quota.window }.containsAll(listOf("five_hour", "seven_day")) -> Pace.UNKNOWN
                windows.any { it.forecast.state in setOf(QuotaForecast.State.UNKNOWN, QuotaForecast.State.STALE) } -> Pace.UNKNOWN
                windows.any { it.forecast.state == QuotaForecast.State.EXHAUSTS_FIRST } -> Pace.SLOW_DOWN
                windows.all { it.quota.utilization < .5 && it.forecast.state in setOf(QuotaForecast.State.IDLE, QuotaForecast.State.RESET_FIRST) } -> Pace.CAN_INCREASE
                else -> Pace.NORMAL
            }
        }
        val selected = states.minBy { when (it) { Pace.CAN_INCREASE -> 0; Pace.NORMAL -> 1; Pace.SLOW_DOWN -> 2; Pace.UNKNOWN -> 3; Pace.PAUSE -> 4 } }
        return advice(selected, maximum)
    }
    internal fun advice(pace: Pace, maximum: Int) = when (pace) {
        Pace.UNKNOWN -> PacingAdvice(pace, maximum, 0, "Quota observations are missing or stale. Keep current useful work; do not increase parallelism based on this signal.")
        Pace.NORMAL -> PacingAdvice(pace, maximum, 0, "Consumption is within the observed capacity trajectory. Continue useful work within your team limits.")
        Pace.SLOW_DOWN -> PacingAdvice(pace, 1, 10_000, "The current burn rate is projected to reach a quota before reset. Reduce optional parallel work and use one request at a time; the gateway enforces a short admission delay.")
        Pace.CAN_INCREASE -> PacingAdvice(pace, maximum, 0, "Fresh observations show spare capacity. If useful work is already queued, you may increase parallelism up to $maximum. Do not invent work to consume quota.")
        Pace.PAUSE -> PacingAdvice(pace, 1, 30_000, "Eligible accounts are close to their observed quota threshold. Pause optional work until capacity recovers.")
    }
}

data class DirectiveDelivery(val directive: AgentDirective, val claimId: String)

interface DirectiveService {
    /** Informational controller output, including when automatic delivery is disabled. */
    suspend fun preview(actor: me.nathanfallet.teamcode.domain.team.Principal): AgentDirective? = current(actor)
    suspend fun current(actor: me.nathanfallet.teamcode.domain.team.Principal, sessionId: String? = null): AgentDirective?
    suspend fun claim(actor: me.nathanfallet.teamcode.domain.team.Principal, sessionId: String, stage: String): DirectiveDelivery?
    suspend fun acknowledge(actor: me.nathanfallet.teamcode.domain.team.Principal, id: String, stage: String, sessionId: String? = null, claimId: String? = null)
}
