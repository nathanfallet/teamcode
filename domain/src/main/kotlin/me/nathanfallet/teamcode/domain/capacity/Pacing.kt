package me.nathanfallet.teamcode.domain.capacity

import me.nathanfallet.teamcode.domain.accounts.AccountRecord

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
 * How hard a SLOW_DOWN spaces admissions, from the [CapacityGap]'s *affordable share*: the share of the current burn
 * rate that would last until capacity comes back, `(gap start - now) / (gap end - now)`. A longer gap, or a closer
 * one, affords less. Pacing never caps parallelism: each level only spaces admissions, more as the share shrinks:
 *
 * | Level    | Affordable share | Minimum interval |
 * |----------|------------------|------------------|
 * | LIGHT    | at least 1/2     | 2 s              |
 * | MODERATE | 1/4 to 1/2       | 5 s              |
 * | SEVERE   | under 1/4        | 10 s             |
 *
 * PAUSE (no usable account left) spaces admissions by thirty seconds.
 */
enum class SlowDownLevel(val minimumAffordableShare: Double, val intervalMillis: Long) {
    LIGHT(.5, 2_000), MODERATE(.25, 5_000), SEVERE(0.0, 10_000);
    companion object {
        fun of(affordableShare: Double): SlowDownLevel =
            if (!affordableShare.isFinite()) SEVERE else entries.first { affordableShare >= it.minimumAffordableShare }
    }
}

/** Tightening first: a higher pace, then a longer interval, then fewer permitted requests (zero is unlimited). */
val PACING_STRICTNESS: Comparator<PacingAdvice> = compareBy<PacingAdvice> { when (it.pace) {
    Pace.CAN_INCREASE -> 0; Pace.NORMAL -> 1; Pace.UNKNOWN -> 2; Pace.SLOW_DOWN -> 3; Pace.PAUSE -> 4
} }.thenBy { it.minIntervalMillis }.thenByDescending { if (it.maxConcurrent <= 0) Int.MAX_VALUE else it.maxConcurrent }

/**
 * Paces one route target from the capacity of every account it can use, taken together. The goal is to never run
 * out of quota entirely: as long as some account has quota, work goes on at full speed. The gateway slows down only
 * when, at the team's current burn rate, every usable account is projected to be empty before a reset gives some
 * capacity back (a [CapacityGap]), and only spaces admissions, more as the gap gets longer or closer ([SlowDownLevel]).
 *
 * - No usable account at all (every one exhausted, disconnected, disabled or cooling): PAUSE.
 * - A gap ahead: SLOW_DOWN, graded by the gap's affordable share.
 * - No gap, but an account without a known five-hour or seven-day window, or no known team rate for one of them:
 *   UNKNOWN (no spacing, no encouragement either).
 * - No gap even at twice the current rate: CAN_INCREASE. Otherwise NORMAL.
 *
 * The team rate for a window is the sum of the learned rates of that window over [accounts] (see
 * [ForecastHistoryUseCase]), as a share of one account's window per hour: accounts of one provider are taken as
 * comparable. The capacity is [AccountHeadroomUseCase]'s, for [model] when given.
 */
class PacingUseCase(
    private val headroom: AccountHeadroomUseCase = AccountHeadroomUseCase(),
    private val projection: ProjectCapacityUseCase = ProjectCapacityUseCase(),
) {
    /** [maximum] is the applicable member, group or run concurrency limit, zero when none applies; pacing never lowers it. */
    fun advise(
        accounts: List<AccountPacing>, maximum: Int, now: Long, pressureThreshold: Double = DEFAULT_QUOTA_PRESSURE_THRESHOLD,
        model: String? = null,
    ): PacingAdvice {
        require(maximum >= 0)
        if (accounts.isEmpty()) return advice(Pace.UNKNOWN, maximum)
        val observations = accounts.map { account -> PoolAccountObservation(account.account, 0, account.windows.map { it.quota }) }
        val limits = accounts.associate { it.account.id to it.modelLimits }
        val meteredLimit: (AccountRecord) -> String? = { record -> model?.let { limits[record.id]?.get(it.lowercase()) } }
        val usable = headroom.usable(observations, now, pressureThreshold, model, meteredLimit)
        if (usable.isEmpty()) return advice(Pace.PAUSE, maximum)
        val exhausted = headroom.exhausted(observations, now, pressureThreshold, model, meteredLimit)
        val rates = accounts.flatMap { it.windows }.filter { it.forecast.pointsPerHour != null }
            .groupBy { it.quota.window }.mapValues { (_, windows) -> windows.sumOf { it.forecast.pointsPerHour!! / 100 } }
        val gap = projection.firstGap(usable, exhausted, rates, now, pressureThreshold)
        if (gap != null) return advice(Pace.SLOW_DOWN, maximum, SlowDownLevel.of(gap.affordableShare(now)))
        val known = SHARED_WINDOWS.all { it in rates } && usable.all { account -> SHARED_WINDOWS.all { account.window(it) != null } }
        if (!known) return advice(Pace.UNKNOWN, maximum)
        val doubled = projection.firstGap(usable, exhausted, rates.mapValues { it.value * 2 }, now, pressureThreshold)
        return advice(if (doubled == null) Pace.CAN_INCREASE else Pace.NORMAL, maximum)
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
    companion object {
        const val PAUSE_INTERVAL_MILLIS = 30_000L
        private val SHARED_WINDOWS = listOf("five_hour", "seven_day")
    }
    private fun describe(pace: Pace, maximum: Int, interval: Long) = PacingAdvice(pace, maximum, interval, when (pace) {
        Pace.UNKNOWN -> "Quota observations are missing or stale. Keep current useful work; do not increase parallelism based on this signal."
        Pace.NORMAL -> "Consumption is within the observed capacity trajectory. Continue useful work within your team limits."
        Pace.SLOW_DOWN -> "At the current burn rate, every usable account is projected to run out of quota before one resets. Reduce optional work; " +
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
