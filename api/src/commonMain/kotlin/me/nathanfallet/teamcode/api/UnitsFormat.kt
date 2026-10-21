package me.nathanfallet.teamcode.api

private val scales = listOf(1_000L to "K", 1_000_000L to "M", 1_000_000_000L to "B")

/**
 * How every human-facing surface shows credits, tokens and other unit counts: the raw value under 1,000, otherwise one
 * decimal and a K, M or B suffix, rounded half up (65,536 → "65.5K", 12,345,678 → "12.3M"). A value that rounds to 1,000
 * of a scale moves to the next one (999,950 → "1.0M", never "1000.0K"); past B it stays in B. JSON keeps raw values.
 */
fun formatUnits(value: Long): String {
    if (value < 0) return "-" + formatUnits(if (value == Long.MIN_VALUE) Long.MAX_VALUE else -value)
    if (value < 1_000) return value.toString()
    var index = scales.indexOfLast { value >= it.first }
    while (true) {
        val step = scales[index].first / 10
        val tenths = value / step + if (value % step * 2 >= step) 1 else 0
        if (tenths >= 10_000 && index < scales.lastIndex) { index++; continue }
        return "${tenths / 10}.${tenths % 10}${scales[index].second}"
    }
}
