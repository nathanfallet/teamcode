package me.nathanfallet.teamcode.api

import me.nathanfallet.teamcode.api.models.ReleaseVersion
import kotlin.test.*

class ReleaseVersionTest {
    @Test fun semanticVersionPrecedenceIncludesEveryPrereleaseRule() {
        val ordered = listOf("1.0.0-alpha", "1.0.0-alpha.1", "1.0.0-alpha.beta", "1.0.0-beta", "1.0.0-beta.2", "1.0.0-beta.11", "1.0.0-rc.1", "1.0.0", "1.0.1", "1.1.0", "2.0.0")
        for ((index, left) in ordered.withIndex()) for ((otherIndex, right) in ordered.withIndex()) {
            assertEquals(index.compareTo(otherIndex), ReleaseVersion.parse(left).compareTo(ReleaseVersion.parse(right)).coerceIn(-1, 1), "$left / $right")
        }
        assertEquals(ReleaseVersion.parse("1.0.0"), ReleaseVersion.parse("1.0.0"))
        assertTrue(ReleaseVersion.parse("1.0.0-Z") < ReleaseVersion.parse("1.0.0-a"))
        assertTrue(ReleaseVersion.parse("1.0.0-123") < ReleaseVersion.parse("1.0.0-A"))
    }

    @Test fun numericComponentsNeverOverflowOrUseLexicalNumberOrdering() {
        val huge = "9".repeat(60)
        assertTrue(ReleaseVersion.parse("$huge.0.0") > ReleaseVersion.parse("10.0.0"))
        assertTrue(ReleaseVersion.parse("1.$huge.0") > ReleaseVersion.parse("1.99.999"))
        assertTrue(ReleaseVersion.parse("1.0.0-$huge") > ReleaseVersion.parse("1.0.0-99"))
        assertTrue(ReleaseVersion.parse("1.0.0-10") > ReleaseVersion.parse("1.0.0-9"))
    }

    @Test fun unsafeAndAmbiguousVersionsNeverReachComparisonOrUrls() {
        for (value in listOf("", "latest", "v1.2.3", "1.2.3+build", "1.2.3-01", "01.0.0", "1.2", "../escape", "1.2.3/asset", "1.2.3\n", "9".repeat(81)))
            assertFailsWith<IllegalArgumentException>(value) { ReleaseVersion.parse(value) }
    }
}
