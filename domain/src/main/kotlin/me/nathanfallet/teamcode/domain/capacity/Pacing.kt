package me.nathanfallet.teamcode.domain.capacity

enum class Pace { UNKNOWN, NORMAL, SLOW_DOWN, CAN_INCREASE, PAUSE }
data class WindowPacing(val quota: AccountQuota, val forecast: QuotaForecast)
data class PacingAdvice(val pace: Pace, val maxConcurrent: Int, val minIntervalMillis: Long, val message: String)
data class AgentDirective(val id: String, val memberId: String, val advice: PacingAdvice, val createdAtMillis: Long, val expiresAtMillis: Long)

class ForecastHistoryUseCase {
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
        return WindowPacing(latest, ForecastQuotaUseCase().forecast(QuotaObservation(latest.utilization, latest.observedAtMillis, latest.resetsAtMillis), rate, now))
    }
}

/**
 * How far a SLOW_DOWN projection overshoots, from the *affordable share*: the fraction of the current burn rate that
 * would reach the pressure threshold exactly at reset, `(projected threshold time - now) / (reset - now)`. A window
 * projected to cross the threshold just before its reset affords almost all of its current rate; one projected to
 * cross it now affords none. Halving parallelism roughly halves the burn, so each level divides the recently observed
 * parallelism by the factor that brings that burn back under the threshold, and spaces admissions more as the
 * overshoot grows:
 *
 * | Level    | Affordable share | Concurrent requests            | Minimum interval |
 * |----------|------------------|--------------------------------|------------------|
 * | LIGHT    | at least 1/2     | observed parallelism / 2 (≥ 1) | 2 s              |
 * | MODERATE | 1/4 to 1/2       | observed parallelism / 4 (≥ 1) | 5 s              |
 * | SEVERE   | under 1/4        | 1                              | 10 s             |
 *
 * PAUSE (a window already at the threshold) stays the hard bound: one request and thirty seconds.
 */
enum class SlowDownLevel(val minimumAffordableShare: Double, val divisor: Int?, val intervalMillis: Long) {
    LIGHT(.5, 2, 2_000), MODERATE(.25, 4, 5_000), SEVERE(0.0, null, 10_000);
    /** Requests this level permits at once; never zero, since a zero limit would mean no limit at all. */
    fun concurrency(observed: Int): Int = divisor?.let { maxOf(1, (observed.coerceAtLeast(0) + it - 1) / it) } ?: 1
    companion object {
        fun of(affordableShare: Double): SlowDownLevel =
            if (!affordableShare.isFinite()) SEVERE else entries.first { affordableShare >= it.minimumAffordableShare }
    }
}

/** The share of the current burn rate that still fits before reset, for a window projected to exhaust first. */
fun WindowPacing.affordableShare(now: Long): Double? {
    if (forecast.state != QuotaForecast.State.EXHAUSTS_FIRST) return null
    val eta = forecast.reachesThresholdAtMillis ?: return null
    val reset = quota.resetsAtMillis ?: return null
    if (reset <= now) return null
    return ((eta - now).coerceAtLeast(0).toDouble() / (reset - now)).coerceIn(0.0, 1.0)
}

/** Tightening first: a higher pace, then a longer interval, then fewer permitted requests (zero is unlimited). */
val PACING_STRICTNESS: Comparator<PacingAdvice> = compareBy<PacingAdvice> { when (it.pace) {
    Pace.CAN_INCREASE -> 0; Pace.NORMAL -> 1; Pace.UNKNOWN -> 2; Pace.SLOW_DOWN -> 3; Pace.PAUSE -> 4
} }.thenBy { it.minIntervalMillis }.thenByDescending { if (it.maxConcurrent <= 0) Int.MAX_VALUE else it.maxConcurrent }

/** Chooses the least-constrained eligible account, respecting every window on that account. */
class PacingUseCase {
    /**
     * [maximum] is the applicable concurrency limit, zero when none applies. [observedConcurrency] is the scope's
     * recent peak of parallel requests, used only to size a SLOW_DOWN.
     */
    fun advise(accounts: List<List<WindowPacing>>, maximum: Int, now: Long, observedConcurrency: Int = 0): PacingAdvice {
        require(maximum >= 0 && observedConcurrency >= 0)
        if (accounts.isEmpty()) return advice(Pace.UNKNOWN, maximum)
        val states = accounts.map { windows ->
            val pace = when {
                windows.any { it.forecast.state == QuotaForecast.State.EXHAUSTED } -> Pace.PAUSE
                !windows.map { it.quota.window }.containsAll(listOf("five_hour", "seven_day")) -> Pace.UNKNOWN
                windows.any { it.forecast.state in setOf(QuotaForecast.State.UNKNOWN, QuotaForecast.State.STALE) } -> Pace.UNKNOWN
                windows.any { it.forecast.state == QuotaForecast.State.EXHAUSTS_FIRST } -> Pace.SLOW_DOWN
                windows.all { it.quota.utilization < .5 && it.forecast.state in setOf(QuotaForecast.State.IDLE, QuotaForecast.State.RESET_FIRST) } -> Pace.CAN_INCREASE
                else -> Pace.NORMAL
            }
            // The most pressured overshooting window of an account bounds what that account can absorb. An overshooting
            // window whose share cannot be computed counts as fully pressured (SEVERE), the conservative former behavior.
            pace to (windows.filter { it.forecast.state == QuotaForecast.State.EXHAUSTS_FIRST }
                .minOfOrNull { it.affordableShare(now) ?: 0.0 } ?: 0.0)
        }
        val selected = states.minWith(compareBy<Pair<Pace, Double>> { when (it.first) {
            Pace.CAN_INCREASE -> 0; Pace.NORMAL -> 1; Pace.SLOW_DOWN -> 2; Pace.UNKNOWN -> 3; Pace.PAUSE -> 4
        } }.thenByDescending { it.second })
        return advice(selected.first, maximum, SlowDownLevel.of(selected.second), observedConcurrency)
    }
    internal fun advice(pace: Pace, maximum: Int, level: SlowDownLevel = SlowDownLevel.SEVERE, observedConcurrency: Int = 0): PacingAdvice = when (pace) {
        Pace.SLOW_DOWN -> {
            val permitted = level.concurrency(observedConcurrency).let { if (maximum > 0) minOf(it, maximum) else it }
            describe(pace, permitted, level.intervalMillis)
        }
        Pace.PAUSE -> describe(pace, 1, 30_000)
        else -> describe(pace, maximum, 0)
    }
    /** The same advice under a tighter concurrency ceiling (zero keeps the advice's own). */
    internal fun withCeiling(advice: PacingAdvice, ceiling: Int): PacingAdvice =
        describe(advice.pace, concurrencyCeiling(listOf(advice.maxConcurrent, ceiling)), advice.minIntervalMillis)
    private fun describe(pace: Pace, maximum: Int, interval: Long) = PacingAdvice(pace, maximum, interval, when (pace) {
        Pace.UNKNOWN -> "Quota observations are missing or stale. Keep current useful work; do not increase parallelism based on this signal."
        Pace.NORMAL -> "Consumption is within the observed capacity trajectory. Continue useful work within your team limits."
        Pace.SLOW_DOWN -> "The current burn rate is projected to reach a quota before reset. Reduce optional parallel work and " +
            (if (maximum == 1) "use one request at a time" else "keep at most $maximum requests in flight") +
            "; the gateway spaces admissions by ${interval / 1000} seconds."
        Pace.CAN_INCREASE -> "Fresh observations show spare capacity. If useful work is already queued, you may increase parallelism" +
            (if (maximum > 0) " up to $maximum" else "") + ". Do not invent work to consume quota."
        Pace.PAUSE -> "Eligible accounts are close to their observed quota threshold. Pause optional work until capacity recovers."
    })
}

data class DirectiveDelivery(val directive: AgentDirective, val claimId: String)

interface DirectiveService {
    /** Informational controller output, including when automatic delivery is disabled. */
    suspend fun preview(actor: me.nathanfallet.teamcode.domain.team.Principal): AgentDirective? = current(actor)
    suspend fun current(actor: me.nathanfallet.teamcode.domain.team.Principal, sessionId: String? = null): AgentDirective?
    suspend fun claim(actor: me.nathanfallet.teamcode.domain.team.Principal, sessionId: String, stage: String): DirectiveDelivery?
    suspend fun acknowledge(actor: me.nathanfallet.teamcode.domain.team.Principal, id: String, stage: String, sessionId: String? = null, claimId: String? = null)
}
