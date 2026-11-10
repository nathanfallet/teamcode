package me.nathanfallet.teamcode.companion.presentation.screens

import platform.Foundation.NSDate
import platform.Foundation.NSTimeZone
import platform.Foundation.dateWithTimeIntervalSince1970
import platform.Foundation.localTimeZone

actual fun deviceUtcOffsetMinutes(epochMillis: Long): Int =
    (NSTimeZone.localTimeZone.secondsFromGMTForDate(NSDate.dateWithTimeIntervalSince1970(epochMillis / 1000.0)) / 60).toInt()
