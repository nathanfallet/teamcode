package me.nathanfallet.teamcode.companion.presentation.screens

private const val DAY_MILLIS = 86_400_000L

/** The offset of the device's time zone from UTC, in minutes, at an instant in epoch milliseconds (daylight saving included). */
expect fun deviceUtcOffsetMinutes(epochMillis: Long): Int

/** The zone every dated line is shown in: the device's. Tests replace it to be independent of the machine they run on. */
internal var utcOffsetMinutes: (Long) -> Int = ::deviceUtcOffsetMinutes

/** An instant shifted so that its UTC fields are the wall-clock fields of the device's time zone. */
private fun local(millis: Long): Long = millis + utcOffsetMinutes(millis) * 60_000L

/** The day of an instant in epoch milliseconds in the device's time zone, counted in days since 1970-01-01. */
fun localEpochDay(millis: Long): Long = local(millis).floorDiv(DAY_MILLIS)

private fun two(n: Long) = n.toString().padStart(2, '0')

/** `2026-10-28` for a day counted since 1970-01-01 (Howard Hinnant's civil-from-days algorithm). */
private fun civilDate(days: Long): String {
    val z = days + 719_468
    val era = z.floorDiv(146_097L)
    val dayOfEra = z - era * 146_097
    val yearOfEra = (dayOfEra - dayOfEra / 1460 + dayOfEra / 36_524 - dayOfEra / 146_096) / 365
    val dayOfYear = dayOfEra - (365 * yearOfEra + yearOfEra / 4 - yearOfEra / 100)
    val mp = (5 * dayOfYear + 2) / 153
    val day = dayOfYear - (153 * mp + 2) / 5 + 1
    val month = if (mp < 10) mp + 3 else mp - 9
    val year = yearOfEra + era * 400 + if (month <= 2) 1 else 0
    return "$year-${two(month)}-${two(day)}"
}

/** `21:00` for an instant in epoch milliseconds, in the device's time zone. */
private fun clock(millis: Long): String {
    val inDay = local(millis).floorDiv(60_000L).mod(1440L)
    return "${two(inDay / 60)}:${two(inDay % 60)}"
}

/** `2026-10-28 21:00` for an instant in epoch milliseconds, in the device's time zone. */
fun formatLocal(millis: Long): String = "${civilDate(localEpochDay(millis))} ${clock(millis)}"

/**
 * When an event of the conversation happened, from the event's own server time, in the device's time zone: `21:00` on
 * the day [today] (a [localEpochDay]), `2026-10-28 21:00` on another day, and always with the date when [today] is
 * unknown. The one rule for every dated line of the conversation.
 */
fun formatEventTime(at: Long, today: Long?): String = if (today != null && localEpochDay(at) == today) clock(at) else formatLocal(at)
