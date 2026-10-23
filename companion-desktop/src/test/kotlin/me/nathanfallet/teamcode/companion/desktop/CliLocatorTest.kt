package me.nathanfallet.teamcode.companion.desktop

import java.io.File
import kotlinx.coroutines.test.runTest
import kotlin.test.*

/** Finding the CLI where the installer puts it, its version, and what the app asks of it. */
class CliLocatorTest {
    @Test fun managedLocationsComeBeforeThePath() {
        val mac = DesktopEnvironment(mapOf("HOME" to "/Users/ada", "PATH" to "/usr/bin"), mapOf("PATH" to "/opt/homebrew/bin:/usr/bin"), os = DesktopEnvironment.Os.MACOS)
        assertEquals(listOf("/Users/ada/.local/bin/teamcode", "/Users/ada/Library/Application Support/TeamCode/bin/teamcode", "/usr/bin/teamcode", "/opt/homebrew/bin/teamcode"),
            CliLocator(mac, CliProcesses(mac)).candidates())
        val linux = DesktopEnvironment(mapOf("HOME" to "/home/ada", "XDG_DATA_HOME" to "/data", "PATH" to ""), os = DesktopEnvironment.Os.LINUX)
        assertEquals(listOf("/home/ada/.local/bin/teamcode", "/data/teamcode/bin/teamcode"), CliLocator(linux, CliProcesses(linux)).candidates())
    }

    @Test fun theVersionAndTheMinorVersionRule() {
        assertEquals("1.2.3", CliLocator.parseVersion("TeamCode 1.2.3\n"))
        assertEquals("0.4.0-alpha.1", CliLocator.parseVersion("TeamCode 0.4.0-alpha.1"))
        assertNull(CliLocator.parseVersion("something else"))
        assertTrue(CliLocator.compatible("1.2.0", "1.2.9"))
        assertTrue(CliLocator.compatible("1.3.0", "1.2.9"))
        assertTrue(CliLocator.compatible("2.0.0", "1.9.0"))
        assertFalse(CliLocator.compatible("1.1.9", "1.2.0"))
        assertFalse(CliLocator.compatible("unknown", "1.2.0"))
    }

    @Test fun theInstallCommandIsPinnedToTheAppsRelease() {
        assertEquals("curl -fsSL https://github.com/nathanfallet/teamcode/releases/download/1.0.0/install.sh | sh", CliLocator.installCommand(DesktopEnvironment.Os.MACOS, "1.0.0"))
        assertTrue(CliLocator.installCommand(DesktopEnvironment.Os.LINUX, "0.1.0-SNAPSHOT").contains("/releases/latest/download/install.sh"))
        assertTrue(CliLocator.installCommand(DesktopEnvironment.Os.WINDOWS, "1.0.0").contains("/releases/download/1.0.0/install.ps1"))
    }

    @Test fun theFirstCliThatAnswersIsUsedWithTheAppsConfiguration() = withTemporaryDirectory { directory -> runTest {
        if (!posix) return@runTest
        val bin = File(directory, "bin").apply { mkdirs() }
        fakeCli(bin, version = "1.4.2")
        val environment = DesktopEnvironment(mapOf("HOME" to File(directory, "home").path, "PATH" to bin.path), configOverride = "/cfg/teamcode/config.json", os = DesktopEnvironment.Os.current())
        val found = assertNotNull(CliLocator(environment, CliProcesses(environment)).find())
        assertEquals("1.4.2", found.version)
        // The CLI gets the XDG_CONFIG_HOME that makes it read the app's config.json.
        assertEquals("version | /cfg", File(bin, "calls").readText().trim())
    } }

    @Test fun noCliIsNull() = runTest {
        val environment = DesktopEnvironment(mapOf("HOME" to "/nonexistent", "PATH" to "/nonexistent/bin"))
        assertNull(CliLocator(environment, CliProcesses(environment)).find())
    }
}
