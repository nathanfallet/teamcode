package me.nathanfallet.teamcode.domain.capacity

data class PacingRecovery(val pace: Pace, val startedAtMillis: Long)
data class PacingMemory(val advice: PacingAdvice, val expiresAtMillis: Long, val recovery: PacingRecovery?)
data class PacingDecision(val advice: PacingAdvice, val recovery: PacingRecovery? = null)

/**
 * Persist the pending recovery so a replica restart cannot reset or skip stabilization. Tighter advice (a higher pace,
 * a stronger SLOW_DOWN level or fewer permitted requests, see [PACING_STRICTNESS]) applies at once; looser advice
 * must stay stable for [recoveryMillis], including between SLOW_DOWN levels.
 */
class PacingHysteresisUseCase(private val recoveryMillis: Long = 120_000) {
    fun decide(previous: PacingMemory?, candidate: PacingAdvice, now: Long): PacingDecision {
        if (previous == null || previous.expiresAtMillis <= now || previous.advice == candidate ||
            candidate.pace == Pace.UNKNOWN || PACING_STRICTNESS.compare(candidate, previous.advice) >= 0) return PacingDecision(candidate)
        val recovery = previous.recovery?.takeIf { it.pace == candidate.pace && it.startedAtMillis <= now }
            ?: PacingRecovery(candidate.pace, now)
        return if (now - recovery.startedAtMillis >= recoveryMillis) PacingDecision(candidate)
            else PacingDecision(previous.advice, recovery)
    }
}

/**
 * The recent peak of parallel requests in one directive scope, which sizes a SLOW_DOWN. A throttle lowers the very
 * parallelism it would be measured on, so while SLOW_DOWN or PAUSE applies the peak is held; otherwise it decays by one
 * request per controller pass, so a momentary gap between turns does not erase it. Bounded by [MAX_OBSERVED_CONCURRENCY].
 */
class ObservedConcurrencyUseCase {
    fun next(previous: Int?, previousPace: Pace?, active: Int): Int {
        val carried = (previous ?: 0).let { if (previousPace == Pace.SLOW_DOWN || previousPace == Pace.PAUSE) it else it - 1 }
        return maxOf(active, carried, 0).coerceAtMost(MAX_OBSERVED_CONCURRENCY)
    }
    companion object { const val MAX_OBSERVED_CONCURRENCY = 256 }
}
