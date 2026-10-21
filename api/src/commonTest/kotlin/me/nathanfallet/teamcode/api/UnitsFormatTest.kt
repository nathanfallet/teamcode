package me.nathanfallet.teamcode.api

import kotlin.test.*

class UnitsFormatTest {
    @Test fun rawBelowOneThousandThenOneDecimalWithASuffix() {
        assertEquals("0", formatUnits(0)); assertEquals("999", formatUnits(999)); assertEquals("1.0K", formatUnits(1_000))
        assertEquals("65.5K", formatUnits(65_536)); assertEquals("12.3M", formatUnits(12_345_678))
        assertEquals("1.0M", formatUnits(1_000_000)); assertEquals("1.0B", formatUnits(1_000_000_000))
        assertEquals("100.0B", formatUnits(100_000_000_000)); assertEquals("-1.5K", formatUnits(-1_500))
    }
    @Test fun roundingHalfUpMovesToTheNextScaleAtTheBoundary() {
        assertEquals("1.0K", formatUnits(1_049)); assertEquals("1.1K", formatUnits(1_050))
        assertEquals("999.9K", formatUnits(999_949)); assertEquals("1.0M", formatUnits(999_950))
        assertEquals("999.9M", formatUnits(999_949_999)); assertEquals("1.0B", formatUnits(999_950_000))
        assertEquals("1000.0B", formatUnits(999_999_999_999)); assertEquals("9223372036.9B", formatUnits(Long.MAX_VALUE))
        assertEquals("-9223372036.9B", formatUnits(Long.MIN_VALUE))
    }
}
