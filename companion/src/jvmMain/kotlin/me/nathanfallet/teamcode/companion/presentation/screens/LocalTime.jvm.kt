package me.nathanfallet.teamcode.companion.presentation.screens

actual fun deviceUtcOffsetMinutes(epochMillis: Long): Int = java.util.TimeZone.getDefault().getOffset(epochMillis) / 60_000
