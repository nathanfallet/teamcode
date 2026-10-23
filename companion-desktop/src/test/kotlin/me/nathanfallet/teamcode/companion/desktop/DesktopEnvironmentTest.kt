package me.nathanfallet.teamcode.companion.desktop

import java.io.File
import kotlin.test.*

/** The app and the CLI read the same config.json, whatever started the app. */
class DesktopEnvironmentTest {
    @Test fun theLoginShellsValuesAreReadBetweenMarkersOnly() {
        val output = "Welcome!\n__teamcode_environment__/Users/ada/.xdg\n__teamcode_environment__/opt/bin:/usr/bin\n"
        assertEquals(mapOf("XDG_CONFIG_HOME" to "/Users/ada/.xdg", "PATH" to "/opt/bin:/usr/bin"), DesktopEnvironment.parseLoginShell(output))
        assertEquals(mapOf("PATH" to "/usr/bin"), DesktopEnvironment.parseLoginShell("__teamcode_environment__\n__teamcode_environment__/usr/bin\n"))
        assertEquals(emptyMap(), DesktopEnvironment.parseLoginShell("__teamcode_environment__relative\n"))
    }

    @Test fun anAppOpenedFromTheFinderUsesTheShellsConfigurationAndPassesItOn() {
        val environment = DesktopEnvironment(mapOf("HOME" to "/Users/ada", "PATH" to "/usr/bin"), mapOf("XDG_CONFIG_HOME" to "/Users/ada/.xdg", "PATH" to "/opt/bin:/usr/bin"))
        assertEquals("/Users/ada/.xdg/teamcode/config.json", environment.configPath)
        assertEquals(listOf("/usr/bin", "/opt/bin"), environment.path)
        assertEquals("/Users/ada/.xdg", environment.cliVariables()["XDG_CONFIG_HOME"])
        assertEquals(listOf("/usr/bin", "/opt/bin").joinToString(File.pathSeparator), environment.cliVariables()["PATH"])
    }

    @Test fun aPathGivenByTeamcodeUiWins() {
        val environment = DesktopEnvironment(mapOf("HOME" to "/Users/ada"), configOverride = "/elsewhere/teamcode/config.json")
        assertEquals("/elsewhere/teamcode/config.json", environment.configPath)
        assertEquals("/elsewhere", environment.cliVariables()["XDG_CONFIG_HOME"])
        assertEquals("/Users/ada/.config/teamcode/config.json", DesktopEnvironment(mapOf("HOME" to "/Users/ada")).configPath)
        assertNull(DesktopEnvironment(mapOf("HOME" to "/Users/ada")).cliVariables()["XDG_CONFIG_HOME"])
    }

    @Test fun theLoginShellIsRead() {
        if (!posix) return
        val values = DesktopEnvironment.readLoginShell("/bin/sh")
        assertTrue(values["PATH"].orEmpty().isNotEmpty())
    }

    @Test fun teamcodeUiGivesTheConfigurationAndAnExistingFolder() = withTemporaryDirectory { directory ->
        val process = mapOf("HOME" to "/Users/ada", "XDG_CONFIG_HOME" to "/Users/ada/.xdg")
        val opened = DesktopEnvironment.detect(arrayOf("--config-path", "/c/teamcode/config.json", "--directory", directory.absolutePath), process)
        assertEquals("/c/teamcode/config.json", opened.configPath)
        assertEquals(directory.absolutePath, opened.initialDirectory)
        assertNull(DesktopEnvironment.detect(arrayOf("--directory", File(directory, "missing").absolutePath), process).initialDirectory)
        assertNull(DesktopEnvironment.detect(arrayOf("--directory", "relative"), process).initialDirectory)
        assertNull(DesktopEnvironment.detect(arrayOf("--directory"), process).initialDirectory)
    }
}
