package me.nathanfallet.teamcode.domain.automations

import java.time.DateTimeException
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.BitSet
import me.nathanfallet.teamcode.domain.team.TeamFailure

/**
 * A five-field cron expression (minute, hour, day of month, month, day of week) read in an IANA zone. When both the
 * day of month and the day of week are restricted, either matches. A local time in a spring gap resolves to the first
 * valid time after it; a local time repeated in autumn is one slot, at its first occurrence.
 */
class CronSchedule private constructor(
    private val minutes: BitSet, private val hours: BitSet, private val days: BitSet, private val months: BitSet, private val weekdays: BitSet,
    private val daysRestricted: Boolean, private val weekdaysRestricted: Boolean, val zone: ZoneId,
) {
    /** The first slot strictly after [after] (epoch milliseconds), or null when none comes within [HORIZON_DAYS]. */
    fun next(after: Long): Long? {
        val start = java.time.Instant.ofEpochMilli(after).atZone(zone).toLocalDate()
        for (offset in 0 until HORIZON_DAYS) {
            val found = slotsOf(start.plusDays(offset.toLong())).filter { it > after }.minOrNull() ?: continue
            // A gap can move the last slots of a day past the first ones of the next.
            return minOf(found, slotsOf(start.plusDays(offset + 1L)).filter { it > after }.minOrNull() ?: found)
        }
        return null
    }

    fun upcoming(after: Long, count: Int): List<Long> = generateSequence(next(after)) { next(it) }.take(count).toList()

    /** The latest slot in `[notBefore, at]`, or null: the days of the window only, plus one on each side for clock changes. */
    fun latest(at: Long, notBefore: Long): Long? {
        if (notBefore > at) return null
        val first = java.time.Instant.ofEpochMilli(notBefore).atZone(zone).toLocalDate().minusDays(1)
        val last = java.time.Instant.ofEpochMilli(at).atZone(zone).toLocalDate().plusDays(1)
        return generateSequence(last) { it.minusDays(1) }.takeWhile { !it.isBefore(first) }
            .firstNotNullOfOrNull { date -> slotsOf(date).filter { it in notBefore..at }.maxOrNull() }
    }

    private fun slotsOf(date: LocalDate): List<Long> {
        if (!months[date.monthValue] || !dayMatches(date)) return emptyList()
        val slots = mutableListOf<Long>()
        var hour = hours.nextSetBit(0)
        while (hour >= 0) {
            var minute = minutes.nextSetBit(0)
            while (minute >= 0) {
                slots += ZonedDateTime.ofLocal(LocalDateTime.of(date.year, date.month, date.dayOfMonth, hour, minute), zone, null).toInstant().toEpochMilli()
                minute = minutes.nextSetBit(minute + 1)
            }
            hour = hours.nextSetBit(hour + 1)
        }
        return slots.distinct()
    }

    private fun dayMatches(date: LocalDate): Boolean {
        val day = days[date.dayOfMonth]
        val weekday = weekdays[date.dayOfWeek.value % 7]
        return if (daysRestricted && weekdaysRestricted) day || weekday else day && weekday
    }

    companion object {
        const val MIN_INTERVAL_MILLIS = 5 * 60_000L
        const val MAX_LENGTH = 120
        /** Eight years: a 29 February on a given weekday still has a slot. */
        const val HORIZON_DAYS = 8 * 366
        private val MONTHS = listOf("JAN", "FEB", "MAR", "APR", "MAY", "JUN", "JUL", "AUG", "SEP", "OCT", "NOV", "DEC")
        private val WEEKDAYS = listOf("SUN", "MON", "TUE", "WED", "THU", "FRI", "SAT")

        fun invalid(message: String) = TeamFailure("invalid_automation", 400, message)

        /**
         * Parses [expression] in [timezone] and, when [check] (on save), checks that it has a slot and that two slots are
         * at least five minutes apart; the scheduler, which reads saved definitions, skips that check.
         */
        fun parse(expression: String, timezone: String, now: Long = System.currentTimeMillis(), check: Boolean = true): CronSchedule {
            val zone = zone(timezone)
            if (expression.length > MAX_LENGTH || expression.any { it.isISOControl() }) throw invalid("Use a cron expression of at most $MAX_LENGTH characters.")
            val fields = expression.trim().split(Regex("\\s+"))
            if (fields.size != 5 || fields.any { it.isEmpty() }) throw invalid("Use a cron expression of five fields: minute, hour, day of month, month and day of week.")
            val weekdays = field(fields[4], 0, 7, WEEKDAYS, "day of week").also { if (it[7]) { it.set(0); it.clear(7) } }
            val schedule = CronSchedule(field(fields[0], 0, 59, null, "minute"), field(fields[1], 0, 23, null, "hour"), field(fields[2], 1, 31, null, "day of month"),
                field(fields[3], 1, 12, MONTHS, "month"), weekdays, !fields[2].startsWith("*"), !fields[4].startsWith("*"), zone)
            if (!check) return schedule
            val upcoming = schedule.upcoming(now, 64)
            if (upcoming.isEmpty()) throw invalid("This cron expression never matches a date.")
            if (upcoming.zipWithNext().any { (a, b) -> b - a < MIN_INTERVAL_MILLIS }) throw invalid("Leave at least five minutes between two runs.")
            return schedule
        }

        fun zone(timezone: String): ZoneId {
            if (timezone.isBlank() || timezone.length > 64 || !Regex("[A-Za-z][A-Za-z0-9_+/-]*").matches(timezone)) throw invalid("Use an IANA timezone such as Europe/Paris.")
            return try { ZoneId.of(timezone) } catch (_: DateTimeException) { throw invalid("Use an IANA timezone such as Europe/Paris.") }
        }

        private fun field(text: String, min: Int, max: Int, names: List<String>?, label: String): BitSet {
            val bits = BitSet(max + 1)
            fun value(token: String): Int {
                val named = names?.indexOf(token.uppercase())?.takeIf { it >= 0 }?.let { it + min }
                val number = named ?: token.toIntOrNull()?.takeIf { token.all(Char::isDigit) } ?: throw invalid("Invalid $label in the cron expression.")
                if (number !in min..max) throw invalid("The $label must be between $min and $max.")
                return number
            }
            for (part in text.split(',')) {
                val (range, stepText) = part.split('/').let { if (it.size > 2) throw invalid("Invalid $label in the cron expression.") else it[0] to it.getOrNull(1) }
                val step = stepText?.let { it.toIntOrNull()?.takeIf { s -> s in 1..max.coerceAtLeast(1) } ?: throw invalid("Invalid step in the $label of the cron expression.") } ?: 1
                val (from, to) = when {
                    range == "*" -> min to max
                    '-' in range -> range.split('-').let { if (it.size != 2) throw invalid("Invalid $label in the cron expression.") else value(it[0]) to value(it[1]) }
                    else -> value(range).let { it to (if (stepText != null) max else it) }
                }
                if (from > to) throw invalid("A range of the $label goes backwards.")
                for (v in from..to step step) bits.set(v)
            }
            return bits
        }
    }
}
