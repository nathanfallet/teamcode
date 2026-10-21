package me.nathanfallet.teamcode.domain.capacity

import me.nathanfallet.teamcode.domain.routing.TokenUsage
import me.nathanfallet.teamcode.domain.team.TeamFailure

/**
 * What the provider reported for one dispatched request, recorded once when it settles. [tokens] is null when the
 * stream ended without terminal usage: the request is counted as unmetered, never as zero tokens.
 */
data class MeteredUsage(val provider: String, val model: String, val tokens: TokenUsage?)

/**
 * Raw provider tokens of one member, run, provider and model on one UTC day. Token counts are the provider's own,
 * never converted: [input] excludes cached input, [cacheCreation] and [cacheRead] are the cache write and read
 * input, [output] includes reasoning. [unmeteredRequests] settled without usage and are absent from the token sums.
 * [apiEquivalentMicros] prices the four sums with the team's [ApiPriceTable] for that day, null when no row covers the model.
 */
data class TokenUsageRow(
    val dayNumber: Long, val memberId: String, val username: String, val runId: String?, val provider: String, val model: String,
    val requests: Long, val unmeteredRequests: Long, val input: Long, val cacheCreation: Long, val cacheRead: Long, val output: Long,
    val apiEquivalentMicros: Long? = null,
)
data class TokenUsagePage(val rows: List<TokenUsageRow>, val nextAfter: String?)

/** Inclusive UTC day numbers (milliseconds since the epoch divided by one day). [memberId] null with [all] false is the caller. */
data class TokenUsageQuery(val fromDay: Long, val toDay: Long, val memberId: String? = null, val all: Boolean = false, val after: TokenUsageCursor? = null)

/** Keyset position after the last returned row, in the ledger's (day, member, run, provider, model) order. */
data class TokenUsageCursor(val dayNumber: Long, val memberId: String, val runKey: String, val provider: String, val model: String) {
    fun encode() = "$dayNumber:$memberId:$runKey:$provider:$model"
    companion object {
        private val ID = Regex("[0-9a-f-]{36}")
        private val NAME = Regex("[a-zA-Z0-9_.-]{1,128}")
        fun decode(value: String): TokenUsageCursor {
            val parts = value.split(':')
            val failure = TeamFailure("invalid_cursor", 400, "Use the nextAfter value of the previous page.")
            if (parts.size != 5) throw failure
            val day = parts[0].toLongOrNull()?.takeIf { it >= 0 } ?: throw failure
            if (!ID.matches(parts[1]) || (parts[2].isNotEmpty() && !ID.matches(parts[2])) || !NAME.matches(parts[3]) || !NAME.matches(parts[4])) throw failure
            return TokenUsageCursor(day, parts[1], parts[2], parts[3], parts[4])
        }
    }
}

object TokenUsageLedger {
    const val PAGE_SIZE = 500
    /** At most a year and a day per query; pages keep each response bounded. */
    const val MAX_SPAN_DAYS = 366
    fun validate(query: TokenUsageQuery): TokenUsageQuery {
        if (query.fromDay < 0 || query.toDay < query.fromDay) throw TeamFailure("invalid_window", 400, "Use a start day on or before the end day.")
        if (query.toDay - query.fromDay + 1 > MAX_SPAN_DAYS) throw TeamFailure("invalid_window", 400, "Ask for at most $MAX_SPAN_DAYS days at a time.")
        if (query.all && query.memberId != null) throw TeamFailure("invalid_usage_query", 400, "Use either all=true or member, not both.")
        return query
    }
}
