package me.nathanfallet.teamcode.companion.domain

import me.nathanfallet.teamcode.companion.domain.usecases.updates.UpdatePolicy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class UpdatePolicyTest {
    @Test fun aNewerReleaseIsOffered() {
        assertEquals("1.3.0", UpdatePolicy.target("1.2.0", "1.2.0", "1.3.0"))
        // SemVer precedence, not text: 1.10 is newer than 1.9.
        assertEquals("1.10.0", UpdatePolicy.target("1.9.0", "1.9.0", "1.10.0"))
    }

    @Test fun nothingIsOfferedWhenBothAreUpToDate() {
        assertNull(UpdatePolicy.target("1.3.0", "1.3.0", "1.3.0"))
        assertNull(UpdatePolicy.target("1.3.0", "1.3.0", "1.2.0"))
        assertNull(UpdatePolicy.target("1.3.0", "1.3.0", null))
    }

    @Test fun aCliAheadOfTheAppBringsTheAppToTheClisVersion() {
        // The automatic job updated the CLI, the app stayed behind: `teamcode ui` installs the CLI's version.
        assertEquals("1.3.0", UpdatePolicy.target("1.2.0", "1.3.0", "1.3.0"))
        assertEquals("1.4.0-beta.1", UpdatePolicy.target("1.2.0", "1.4.0-beta.1", "1.3.0"))
        assertEquals("1.3.0", UpdatePolicy.target("1.2.0", "1.3.0", null))
    }

    @Test fun aCliBehindTheLatestReleaseIsOfferedEvenWithACurrentApp() {
        assertEquals("1.3.0", UpdatePolicy.target("1.3.0", "1.2.0", "1.3.0"))
    }

    @Test fun nothingBelowTheAppsOwnVersionIsOffered() {
        // An app newer than the CLI and the latest release: no banner offering an older version.
        assertNull(UpdatePolicy.target("1.4.0", "1.2.0", "1.3.0"))
        assertNull(UpdatePolicy.target("1.4.0", "1.3.0", null))
        // A prerelease sorts below its release, as in SemVer.
        assertEquals("1.4.0", UpdatePolicy.target("1.4.0-rc.1", "1.4.0-rc.1", "1.4.0"))
        assertNull(UpdatePolicy.target("1.4.0", "1.4.0", "1.4.0-rc.2"))
    }

    @Test fun nothingIsOfferedWithoutACliOrForADevelopmentBuild() {
        assertNull(UpdatePolicy.target("1.2.0", null, "1.3.0"))
        assertNull(UpdatePolicy.target("0.1.0-SNAPSHOT", "1.2.0", "1.3.0"))
        assertNull(UpdatePolicy.target("not a version", "1.2.0", "1.3.0"))
        assertNull(UpdatePolicy.target("1.2.0", "garbage", "1.3.0"))
        // An invalid latest version is ignored, never offered.
        assertNull(UpdatePolicy.target("1.2.0", "1.2.0", "v1.3.0"))
    }

    @Test fun checksAreAtMostHourlyAcrossRestarts() {
        val hour = UpdatePolicy.MIN_CHECK_INTERVAL_MILLIS
        assertEquals(0, UpdatePolicy.nextCheckDelay(10_000_000, null))
        assertEquals(hour - 60_000, UpdatePolicy.nextCheckDelay(10_000_000 + 60_000, 10_000_000))
        assertEquals(0, UpdatePolicy.nextCheckDelay(10_000_000 + hour, 10_000_000))
        // A clock set back makes the last attempt look like the future: check now.
        assertEquals(0, UpdatePolicy.nextCheckDelay(9_000_000, 10_000_000))
        // At most 3 requests per check, at most one check per hour: far under GitHub's 60 per hour.
        assertEquals(true, 3 * (60 * 60_000L / hour) <= 60)
    }
}
