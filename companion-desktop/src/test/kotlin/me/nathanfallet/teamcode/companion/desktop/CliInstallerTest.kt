package me.nathanfallet.teamcode.companion.desktop

import com.russhwolf.settings.MapSettings
import java.io.File
import kotlinx.coroutines.test.runTest
import me.nathanfallet.teamcode.client.ReleaseDownloadException
import me.nathanfallet.teamcode.companion.domain.services.LocalAvailability
import me.nathanfallet.teamcode.companion.domain.services.LocalHostException
import me.nathanfallet.teamcode.companion.infrastructure.settings.SettingsAccountRepository
import kotlin.test.*

/** "Install the TeamCode CLI": the pinned script runs only with the hash the app was built with. */
class CliInstallerTest {
    private val script = "echo \"installing into \$XDG_CONFIG_HOME\"\necho 'Installed TeamCode 1.2.3.'\n".encodeToByteArray()
    private val environment = DesktopEnvironment(mapOf("HOME" to "/Users/ada", "PATH" to "/usr/bin:/bin"), mapOf("XDG_CONFIG_HOME" to "/Users/ada/.xdg"))
    private val downloads = mutableListOf<Pair<String, String>>()

    private fun installer(bytes: ByteArray = script, expected: String? = CliInstaller.sha256(script), version: String = "1.2.3", env: DesktopEnvironment = environment) =
        CliInstaller(env, version, expected, { v, name -> downloads += v to name; bytes })

    @Test fun theScriptOfTheAppsVersionRunsWhenItsHashMatchesAndItsOutputIsShown() = runTest {
        if (!posix) return@runTest
        val lines = mutableListOf<String>()
        installer().install { lines += it }
        assertEquals(listOf("1.2.3" to "install.sh"), downloads)
        // The CLI it installs reads the same config.json as the app: the shell's XDG_CONFIG_HOME is passed on.
        assertEquals(listOf("installing into /Users/ada/.xdg", "Installed TeamCode 1.2.3."), lines)
    }

    @Test fun aScriptWithAnotherHashIsNeverRun() = runTest {
        val marker = File.createTempFile("teamcode-installer", ".marker").apply { delete() }
        val tampered = "touch '${marker.path}'\n".encodeToByteArray()
        val error = assertFailsWith<LocalHostException> { installer(bytes = tampered).install { } }
        assertTrue("Nothing was run" in error.message)
        assertFalse(marker.exists())
    }

    @Test fun aFailingScriptOrDownloadIsExplained() = runTest {
        if (!posix) return@runTest
        val failing = "echo 'TeamCode release operation failed (release_http_404).' >&2\nexit 3\n".encodeToByteArray()
        val lines = mutableListOf<String>()
        val error = assertFailsWith<LocalHostException> { installer(failing, CliInstaller.sha256(failing)).install { lines += it } }
        assertEquals("The installer stopped with exit code 3: see its output above.", error.message)
        assertEquals(listOf("TeamCode release operation failed (release_http_404)."), lines)
        val offline = CliInstaller(environment, "1.2.3", CliInstaller.sha256(script), { _, _ -> throw ReleaseDownloadException("release_dns_failure", "UnknownHostException: api.github.com") })
        val message = assertFailsWith<LocalHostException> { offline.install { } }.message
        assertTrue("(release_dns_failure)" in message, message)
        assertTrue("Cause: UnknownHostException: api.github.com." in message, message)
        val refused = CliInstaller(environment, "1.2.3", CliInstaller.sha256(script), { _, _ -> throw ReleaseDownloadException("release_http_404") })
        assertFalse("Cause:" in assertFailsWith<LocalHostException> { refused.install { } }.message)
    }

    @Test fun aBuildWithoutTheHashOnlyShowsTheCommand() = runTest {
        assertFalse(installer(expected = null).available)
        assertTrue(installer().available)
        assertFailsWith<LocalHostException> { installer(expected = null).install { } }
        assertEquals(emptyList(), downloads)
    }

    @Test fun windowsRunsThePowerShellScriptAsAFile() = runTest {
        val windows = DesktopEnvironment(mapOf("LOCALAPPDATA" to "C:\\Users\\ada\\AppData\\Local"), os = DesktopEnvironment.Os.WINDOWS)
        assertEquals(listOf("powershell.exe", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-File", "C:\\t\\install.ps1"),
            installer(env = windows).command("C:\\t\\install.ps1"))
        assertEquals(listOf("/bin/sh", "/t/install.sh"), installer().command("/t/install.sh"))
    }

    @Test fun theSheetOffersInstallOnlyWhenTheAppCanRunIt() = withTemporaryDirectory { directory -> runTest {
        val env = DesktopEnvironment(mapOf("HOME" to File(directory, "home").path, "PATH" to directory.path), os = DesktopEnvironment.Os.current())
        val processes = CliProcesses(env)
        fun host(installer: CliInstaller?) = DesktopLocalHost(CliLocator(env, processes), processes, SettingsAccountRepository(MapSettings()), env, "1.2.3", installer = installer, picker = { null })
        assertEquals(true, (host(installer()).availability("a1") as LocalAvailability.CliMissing).canInstall)
        assertEquals(false, (host(installer(expected = null)).availability("a1") as LocalAvailability.CliMissing).canInstall)
        assertEquals(false, (host(null).availability("a1") as LocalAvailability.CliMissing).canInstall)
        assertFailsWith<LocalHostException> { host(null).installCli { } }
    } }

    @Test fun theFolderOfTeamcodeUiIsGivenOnce() = withTemporaryDirectory { directory ->
        val env = DesktopEnvironment(mapOf("HOME" to "/Users/ada"), initialDirectory = directory.path)
        val processes = CliProcesses(env)
        val host = DesktopLocalHost(CliLocator(env, processes), processes, SettingsAccountRepository(MapSettings()), env, "1.2.3", picker = { null })
        assertEquals(directory.path, host.takeInitialDirectory())
        assertNull(host.takeInitialDirectory())
    }
}
