package me.nathanfallet.teamcode.domain.capacity

import kotlin.math.roundToLong

/**
 * A stretch of time when, at the team's current burn rate, no usable account has quota left: from [startsAtMillis],
 * when the last one runs out, to [endsAtMillis], when the first reset (or the end of the projection) gives some back.
 */
data class CapacityGap(val startsAtMillis: Long, val endsAtMillis: Long) {
    /**
     * The share of the current burn rate that would just last until capacity comes back, within the pacing lookahead:
     * `(start - now) / (min(end, now + lookahead) - now)`. It shrinks as the gap gets longer or closer. The burn rate is
     * learned over the latest hour, so a gap a day or more away is graded as if it ended at the lookahead: it slows down
     * lightly at most, however long it lasts.
     */
    fun affordableShare(now: Long, lookaheadMillis: Long = PACING_LOOKAHEAD_MILLIS): Double {
        val end = minOf(endsAtMillis, now + lookaheadMillis)
        return if (end <= now) 1.0 else ((startsAtMillis - now).coerceAtLeast(0).toDouble() / (end - now)).coerceIn(0.0, 1.0)
    }
}

/** How far the latest-hour burn rate is trusted to grade a gap ([CapacityGap.affordableShare]): one day. */
const val PACING_LOOKAHEAD_MILLIS = 24 * 3_600_000L

/** How far ahead the projection looks: one seven-day window, the longest a subscription resets over. */
const val PROJECTION_HORIZON_MILLIS = 7 * 86_400_000L

/** The length of a provider window once it resets, when its name says it (five-hour and seven-day windows); otherwise unknown. */
fun windowPeriodMillis(window: String): Long? = when {
    window.endsWith("five_hour") -> 5 * 3_600_000L
    window.endsWith("seven_day") -> 7 * 86_400_000L
    else -> null
}

/**
 * Projects the team's current burn rate over the remaining capacity of every account it can use, to find the first
 * [CapacityGap]. The capacity is [AccountHeadroomUseCase]'s: [usable] accounts serve now, [exhausted] ones count for
 * nothing until their windows at the threshold reset. Disconnected, disabled, pending and cooling accounts are in
 * neither list, so they never count (the directive controller does not pass cooling accounts at all).
 *
 * The whole team load goes to one account at a time, the one whose next reset comes first (its quota is lost at that
 * reset if unused), and burns each of its windows at the team rate for that window ([ratesPerHour], a share of one
 * account's window per hour, by window name). An account serves while every known window has quota left, so its
 * five-hour and seven-day windows both bind. A window that resets refills to the threshold and resets again one
 * period later ([windowPeriodMillis]). A window absent from an account is unknown and never binds, so an account with
 * no known window never runs out: unknown is not empty.
 */
class ProjectCapacityUseCase(private val horizonMillis: Long = PROJECTION_HORIZON_MILLIS) {
    fun firstGap(
        usable: List<AccountHeadroom>, exhausted: List<AccountHeadroom>, ratesPerHour: Map<String, Double>, now: Long,
        pressureThreshold: Double = DEFAULT_QUOTA_PRESSURE_THRESHOLD,
    ): CapacityGap? {
        class Window(val name: String, var remaining: Double, var resetsAt: Long?) { val rate = ratesPerHour[name]?.takeIf { it.isFinite() && it > 0 } }
        class Account(val windows: List<Window>) {
            val serving: Boolean get() = windows.all { it.remaining > 0 }
            val nextReset: Long get() = windows.mapNotNull { it.resetsAt }.minOrNull() ?: Long.MAX_VALUE
        }
        val accounts = (usable + exhausted).map { account ->
            Account(account.windows.map { Window(it.window, it.remainingShare, it.resetsAtMillis?.takeIf { reset -> reset > now }) })
        }
        val end = now + horizonMillis
        var t = now
        var gapStart: Long? = null
        // Each step ends at a reset or when the serving account runs out, so it is bounded by the resets before the horizon.
        repeat(MAX_STEPS) {
            accounts.forEach { account -> account.windows.forEach { window ->
                while (window.resetsAt?.let { it <= t } == true) {
                    window.remaining = pressureThreshold
                    window.resetsAt = windowPeriodMillis(window.name)?.let { window.resetsAt!! + it }
                }
            } }
            val nextReset = accounts.flatMap { it.windows }.mapNotNull { it.resetsAt }.minOrNull()
            // Use first the quota lost soonest: the account whose next reset comes first.
            val serving = accounts.filter { it.serving }.minByOrNull { it.nextReset }
            if (serving == null) {
                val start = gapStart ?: t
                gapStart = start
                t = nextReset?.takeIf { it < end } ?: return CapacityGap(start, end)
                return@repeat
            }
            gapStart?.let { return CapacityGap(it, t) }
            val burning = serving.windows.filter { it.rate != null }
            if (burning.isEmpty()) return null
            val runsOut = burning.associateWith { t + (it.remaining / it.rate!! * HOUR_MILLIS).coerceAtMost((end - t + 1).toDouble()).roundToLong() }
            val step = minOf(runsOut.values.min(), nextReset ?: Long.MAX_VALUE, end)
            burning.forEach { window ->
                window.remaining = if (runsOut.getValue(window) <= step) 0.0 else window.remaining - window.rate!! * (step - t) / HOUR_MILLIS
            }
            t = step
            if (t >= end) return null
        }
        return null
    }
    private companion object {
        const val HOUR_MILLIS = 3_600_000.0
        const val MAX_STEPS = 100_000
    }
}
