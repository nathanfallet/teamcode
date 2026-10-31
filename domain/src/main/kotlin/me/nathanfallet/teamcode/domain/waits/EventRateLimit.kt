package me.nathanfallet.teamcode.domain.waits

import java.util.concurrent.ConcurrentHashMap
import me.nathanfallet.teamcode.domain.team.TeamFailure

/**
 * At most [perMinute] calls a minute to one event address, as for an automation's webhook (30). The count is in memory, per
 * replica and bounded to [maxKeys] addresses, so a flood of unknown wait identifiers costs no write and no row.
 */
class EventRateLimit(private val perMinute: Int = 30, private val windowMillis: Long = 60_000, private val maxKeys: Int = 10_000, private val clock: () -> Long = System::currentTimeMillis) {
    private class Window(val start: Long, var count: Int)
    private val windows = ConcurrentHashMap<String, Window>()

    fun check(key: String) {
        val now = clock()
        if (windows.size >= maxKeys && !windows.containsKey(key)) {
            windows.entries.removeIf { now - it.value.start >= windowMillis }
            if (windows.size >= maxKeys) windows.clear()
        }
        val window = windows.compute(key) { _, current -> if (current == null || now - current.start >= windowMillis) Window(now, 1) else current.also { it.count++ } }!!
        if (window.count > perMinute) throw TeamFailure("wait_event_rate_limit", 429, "This address receives at most $perMinute calls a minute.", retryAfterSeconds = 60)
    }
}
