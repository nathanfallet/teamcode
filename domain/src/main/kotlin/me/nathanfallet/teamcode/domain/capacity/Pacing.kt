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

/**
 * How far a SLOW_DOWN projection overshoots, from the *affordable share*: the fraction of the current burn rate that
 * would reach the pressure threshold exactly at the end of the pacing horizon, `(projected threshold time - now) /
 * (min(reset, now + horizon) - now)`. A window projected to cross the threshold just before that end affords almost all
 * of its current rate; one projected to cross it now affords none. Pacing never caps parallelism: each level only spaces
 * admissions, more as the overshoot grows:
 *
 * | Level    | Affordable share | Minimum interval |
 * |----------|------------------|------------------|
 * | LIGHT    | at least 1/2     | 2 s              |
 * | MODERATE | 1/4 to 1/2       | 5 s              |
 * | SEVERE   | under 1/4        | 10 s             |
 *
 * PAUSE (a window already at the threshold) spaces admissions by thirty seconds.
 */
enum class SlowDownLevel(val minimumAffordableShare: Double, val intervalMillis: Long) {
    LIGHT(.5, 2_000), MODERATE(.25, 5_000), SEVERE(0.0, 10_000);
    companion object {
        fun of(affordableShare: Double): SlowDownLevel =
            if (!affordableShare.isFinite()) SEVERE else entries.first { affordableShare >= it.minimumAffordableShare }
    }
}

/**
 * How far ahead a projection can pressure an account. The burn rate is learned over the latest hour; extrapolating it
 * across a seven-day window would assume that hour repeats around the clock for days, which flags every account in
 * active use. Five hours matches the shortest subscription window.
 */
const val PACING_HORIZON_MILLIS = 5 * 3_600_000L

/** A window projected to reach the threshold before its reset and within [PACING_HORIZON_MILLIS]; an unknown ETA counts. */
fun WindowPacing.overshoots(now: Long): Boolean = forecast.state == QuotaForecast.State.EXHAUSTS_FIRST &&
    (forecast.reachesThresholdAtMillis ?: now) < now + PACING_HORIZON_MILLIS

/** The share of the current burn rate that still fits before the horizon ends, for a window that [overshoots]. */
fun WindowPacing.affordableShare(now: Long): Double? {
    if (!overshoots(now)) return null
    val eta = forecast.reachesThresholdAtMillis ?: return null
    val reset = quota.resetsAtMillis ?: return null
    if (reset <= now) return null
    val end = minOf(reset, now + PACING_HORIZON_MILLIS)
    return ((eta - now).coerceAtLeast(0).toDouble() / (end - now)).coerceIn(0.0, 1.0)
}

/** Tightening first: a higher pace, then a longer interval, then fewer permitted requests (zero is unlimited). */
val PACING_STRICTNESS: Comparator<PacingAdvice> = compareBy<PacingAdvice> { when (it.pace) {
    Pace.CAN_INCREASE -> 0; Pace.NORMAL -> 1; Pace.UNKNOWN -> 2; Pace.SLOW_DOWN -> 3; Pace.PAUSE -> 4
} }.thenBy { it.minIntervalMillis }.thenByDescending { if (it.maxConcurrent <= 0) Int.MAX_VALUE else it.maxConcurrent }

/** Chooses the least-constrained eligible account, respecting every window on that account. */
class PacingUseCase {
    /** [maximum] is the applicable member, group or run concurrency limit, zero when none applies; pacing never lowers it. */
    fun advise(accounts: List<List<WindowPacing>>, maximum: Int, now: Long): PacingAdvice {
        require(maximum >= 0)
        if (accounts.isEmpty()) return advice(Pace.UNKNOWN, maximum)
        val states = accounts.map { windows ->
            val pace = when {
                windows.any { it.forecast.state == QuotaForecast.State.EXHAUSTED } -> Pace.PAUSE
                !windows.map { it.quota.window }.containsAll(listOf("five_hour", "seven_day")) -> Pace.UNKNOWN
                windows.any { it.forecast.state in setOf(QuotaForecast.State.UNKNOWN, QuotaForecast.State.STALE) } -> Pace.UNKNOWN
                windows.any { it.overshoots(now) } -> Pace.SLOW_DOWN
                windows.all { it.quota.utilization < .5 && it.forecast.state in setOf(QuotaForecast.State.IDLE, QuotaForecast.State.RESET_FIRST) } -> Pace.CAN_INCREASE
                else -> Pace.NORMAL
            }
            // The most pressured overshooting window of an account bounds what that account can absorb. An overshooting
            // window whose share cannot be computed counts as fully pressured (SEVERE), the conservative former behavior.
            pace to (windows.filter { it.overshoots(now) }.minOfOrNull { it.affordableShare(now) ?: 0.0 } ?: 0.0)
        }
        // An account whose forecast is unknown but whose every window still has clear headroom (under half used) is not
        // under pressure: it must not let another account's throttle win. Any other unknown account stays conservative.
        val selected = states.withIndex().minWith(compareBy<IndexedValue<Pair<Pace, Double>>> { (index, state) -> when (state.first) {
            Pace.CAN_INCREASE -> 0; Pace.NORMAL -> 1
            Pace.UNKNOWN -> if (accounts[index].isNotEmpty() && accounts[index].all { it.quota.utilization < .5 }) 2 else 4
            Pace.SLOW_DOWN -> 3; Pace.PAUSE -> 5
        } }.thenByDescending { it.value.second }).value
        return advice(selected.first, maximum, SlowDownLevel.of(selected.second))
    }
    /** Pacing only spaces admissions; the concurrency stays the member, group or run limit (zero is unlimited). */
    internal fun advice(pace: Pace, maximum: Int, level: SlowDownLevel = SlowDownLevel.SEVERE): PacingAdvice = when (pace) {
        Pace.SLOW_DOWN -> describe(pace, maximum, level.intervalMillis)
        Pace.PAUSE -> describe(pace, maximum, PAUSE_INTERVAL_MILLIS)
        else -> describe(pace, maximum, 0)
    }
    /** The same advice under a tighter concurrency ceiling (zero keeps the advice's own). */
    internal fun withCeiling(advice: PacingAdvice, ceiling: Int): PacingAdvice =
        describe(advice.pace, concurrencyCeiling(listOf(advice.maxConcurrent, ceiling)), advice.minIntervalMillis)
    companion object { const val PAUSE_INTERVAL_MILLIS = 30_000L }
    private fun describe(pace: Pace, maximum: Int, interval: Long) = PacingAdvice(pace, maximum, interval, when (pace) {
        Pace.UNKNOWN -> "Quota observations are missing or stale. Keep current useful work; do not increase parallelism based on this signal."
        Pace.NORMAL -> "Consumption is within the observed capacity trajectory. Continue useful work within your team limits."
        Pace.SLOW_DOWN -> "The current burn rate is projected to reach a quota within a few hours. Reduce optional work; " +
            "the gateway spaces admissions by ${interval / 1000} seconds."
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
