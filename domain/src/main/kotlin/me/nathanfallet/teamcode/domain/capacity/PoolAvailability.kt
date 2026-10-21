package me.nathanfallet.teamcode.domain.capacity

import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import me.nathanfallet.teamcode.domain.team.TeamFailure

/** Waits these refusals announce. A longer wait is not retried by clients: the user reads when it unblocks instead. */
object RetryWindows {
    const val DAY_MILLIS = 86_400_000L
    /** Longest wait a client is still asked to retry by itself. */
    const val MAX_AUTOMATIC_RETRY_SECONDS = 120L
    const val SHORT_RETRY_SECONDS = 5L

    fun secondsUntil(now: Long, at: Long): Long = maxOf(1L, (at - now + 999) / 1000)
    /** Daily budgets are keyed by UTC day (`now / DAY`), so they reset at the next UTC midnight. */
    fun nextUtcDay(now: Long): Long = (now / DAY_MILLIS + 1) * DAY_MILLIS
    fun clock(at: Long): String = DateTimeFormatter.ofPattern("HH:mm 'UTC'").withZone(ZoneOffset.UTC).format(Instant.ofEpochMilli(at))
    fun duration(seconds: Long): String = when {
        seconds < 60 -> "${seconds}s"
        seconds < 3600 -> "${(seconds + 59) / 60} min"
        else -> { val minutes = (seconds + 59) / 60; if (minutes % 60 == 0L) "${minutes / 60} h" else "${minutes / 60} h ${minutes % 60} min" }
    }
    /** "at 14:05 UTC (in 2 h 10 min)" */
    fun at(now: Long, at: Long): String = "at ${clock(at)} (in ${duration(secondsUntil(now, at))})"

    /**
     * A daily budget that cannot cover the request. Budgets count in-flight reservations at their estimate: while
     * requests are running ([heldByRunningRequests]), settling them can free units, so the refusal stays retryable.
     * Otherwise it is blocked until the next UTC day and clients must not retry into it.
     */
    fun dailyBudget(code: String, budget: String, remedy: String, now: Long, heldByRunningRequests: Boolean = false): TeamFailure {
        val reset = nextUtcDay(now)
        if (heldByRunningRequests) return TeamFailure(code, 429, "$budget is fully reserved by requests still running. " +
            "Retry when they finish: they usually cost less than reserved. Otherwise it resets ${at(now, reset)}. $remedy",
            retryAfterSeconds = HELD_BUDGET_RETRY_SECONDS)
        return TeamFailure(code, 429, "$budget is used up for today. It resets ${at(now, reset)}. $remedy",
            retryAfterSeconds = secondsUntil(now, reset), retryable = false)
    }
    const val HELD_BUDGET_RETRY_SECONDS = 30L
}

/**
 * Why no account of the member's authorized pools could take a request: counts per blocking cause, never account or
 * member names. [nextAvailableAt] is the earliest known time a waiting account (cooldown or quota) serves again.
 */
data class PoolStanding(
    val provider: String,
    val pendingHandover: Int = 0,
    val reconnectRequired: Int = 0,
    val disabled: Int = 0,
    val coolingDown: Int = 0,
    val quotaExhausted: Int = 0,
    val nextAvailableAt: Long? = null,
) {
    val accounts: Int get() = pendingHandover + reconnectRequired + disabled + coolingDown + quotaExhausted
    /** Only accounts that become usable without anyone acting. */
    val waiting: Int get() = coolingDown + quotaExhausted
}

/** Turns a [PoolStanding] into the refusal the agent shows: the cause, what to do, and when to retry. */
class DescribePoolUnavailableUseCase {
    fun failure(standing: PoolStanding, now: Long): TeamFailure {
        val provider = when (standing.provider) { "anthropic", "claude" -> "Claude"; "codex" -> "Codex"; else -> standing.provider }
        if (standing.accounts == 0) return TeamFailure(CODE, 503,
            "No $provider account is connected to your TeamCode pool. An administrator must add one with " +
                "`teamcode accounts add` and assign it to your pool.", retryable = false)
        val causes = buildList {
            if (standing.pendingHandover > 0) add("${accounts(standing.pendingHandover)} waiting for a handover: an administrator " +
                "must confirm it with Handover on the dashboard Accounts page, or with `teamcode accounts handover <id> --confirm`.")
            if (standing.reconnectRequired > 0) add("${accounts(standing.reconnectRequired)} disconnected: an administrator must " +
                "reconnect ${if (standing.reconnectRequired == 1) "it" else "them"} with `teamcode accounts add`.")
            if (standing.disabled > 0) add("${accounts(standing.disabled)} disabled by an administrator.")
            if (standing.quotaExhausted > 0) add("${accounts(standing.quotaExhausted)} at ${if (standing.quotaExhausted == 1) "its" else "their"} subscription limit.")
            if (standing.coolingDown > 0) add("${accounts(standing.coolingDown)} paused after a provider rate limit or refusal.")
        }
        val summary = "No $provider account in your TeamCode pool is usable. ${causes.joinToString(" ")}"
        if (standing.waiting == 0) return TeamFailure(CODE, 503, "$summary Retrying will not help until this is fixed.", retryable = false)
        val retryAfter = standing.nextAvailableAt?.takeIf { it > now }?.let { RetryWindows.secondsUntil(now, it) }
            ?: RetryWindows.SHORT_RETRY_SECONDS
        val retry = if (retryAfter <= RetryWindows.SHORT_RETRY_SECONDS) "Retry in a few seconds."
            else "The next account should be usable ${RetryWindows.at(now, now + retryAfter * 1000)}."
        return TeamFailure(CODE, 429, "$summary $retry", retryAfterSeconds = retryAfter,
            retryable = retryAfter <= RetryWindows.MAX_AUTOMATIC_RETRY_SECONDS)
    }

    private fun accounts(count: Int) = if (count == 1) "1 account is" else "$count accounts are"

    companion object { const val CODE = "pool_unavailable" }
}
