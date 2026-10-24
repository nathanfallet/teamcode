package me.nathanfallet.teamcode.companion.desktop

import java.io.File
import kotlinx.coroutines.test.runTest
import me.nathanfallet.teamcode.companion.domain.services.AppUpdateException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DesktopAppUpdaterTest {
    /**
     * A fake CLI whose version is read from the `version` file (so that `update` can change it), recording each call.
     * [check], [update] and [ui] are the shell bodies of those commands.
     */
    private fun cli(
        directory: File,
        version: String = "1.2.0",
        check: String = "echo '{\"currentVersion\":\"1.2.0\",\"latestVersion\":\"1.3.0\",\"updateAvailable\":true}'",
        update: String = "echo 'Selected TeamCode 1.3.0.'; echo '1.3.0' > '${File(directory, "version").path}'",
        ui: String = "echo 'TeamCode Desktop installs version 1.3.0 once the running app has quit, then reopens.'",
    ): File {
        // Built-ins only: the CLI runs with the test's PATH, this directory alone.
        File(directory, "version").writeText(version + "\n")
        val script = File(directory, "teamcode")
        script.writeText(
            """
            |#!/bin/sh
            |echo "${'$'}*" >> '${File(directory, "calls").path}'
            |case "${'$'}1 ${'$'}2" in
            |  version*) read v < '${File(directory, "version").path}'; echo "TeamCode ${'$'}v" ;;
            |  "update --check") $check ;;
            |  "update ") $update ;;
            |  ui*) $ui ;;
            |  *) echo "Error: no such option ${'$'}2" >&2; exit 1 ;;
            |esac
            """.trimMargin() + "\n",
        )
        script.setExecutable(true)
        return script
    }

    private fun updater(directory: File, error: String? = null, quits: MutableList<Unit> = mutableListOf()): DesktopAppUpdater {
        val environment = DesktopEnvironment(mapOf("HOME" to File(directory, "home").path, "PATH" to directory.path), os = DesktopEnvironment.Os.current(), updateError = error)
        val processes = CliProcesses(environment)
        return DesktopAppUpdater(CliLocator(environment, processes), processes, environment, "1.2.0", processId = 4242, exit = { quits += Unit })
    }

    private fun calls(directory: File) = File(directory, "calls").readLines().filter { it != "version" }

    @Test fun theCheckIsTheClisSignedCheck() = withTemporaryDirectory { directory -> runTest {
        if (!posix) return@runTest
        cli(directory)
        assertEquals("1.3.0", updater(directory).latestVersion())
        assertEquals("1.2.0", updater(directory).cliVersion())
        assertEquals(listOf("update --check --json"), calls(directory))
    } }

    @Test fun noStableReleaseIsNoVersion() = withTemporaryDirectory { directory -> runTest {
        if (!posix) return@runTest
        cli(directory, check = "echo '{\"currentVersion\":\"1.2.0\",\"latestVersion\":null,\"updateAvailable\":false}'")
        assertNull(updater(directory).latestVersion())
    } }

    @Test fun aCliOlderThanJsonIsReadFromItsText() = withTemporaryDirectory { directory -> runTest {
        if (!posix) return@runTest
        cli(directory, check = "case \"$3\" in --json) echo 'Error: no such option --json' >&2; exit 1 ;; esac; " +
            "echo 'Current version: 1.2.0'; echo 'Verified release: 1.3.1 (abc)'; echo 'Update available: true'")
        assertEquals("1.3.1", updater(directory).latestVersion())
        assertEquals(listOf("update --check --json", "update --check"), calls(directory))
    } }

    @Test fun aFailedCheckThrowsForTheCallerToStaySilent() = withTemporaryDirectory { directory -> runTest {
        if (!posix) return@runTest
        assertFailsWith<AppUpdateException> { updater(directory).latestVersion() }
        cli(directory, check = "echo 'GitHub refused the request for now (release_rate_limited).' >&2; exit 1")
        assertFailsWith<AppUpdateException> { updater(directory).latestVersion() }
        cli(directory, check = "echo 'not json'")
        assertFailsWith<AppUpdateException> { updater(directory).latestVersion() }
    } }

    @Test fun theUpdateRunsUpdateThenTheAppsInstallationWithItsProcessId() = withTemporaryDirectory { directory -> runTest {
        if (!posix) return@runTest
        cli(directory)
        val lines = mutableListOf<String>()
        updater(directory).update { lines += it }
        assertEquals(listOf("update", "ui --yes --restart-app 4242"), calls(directory))
        assertEquals("Updating the TeamCode CLI 1.2.0…", lines.first())
        assertTrue("Selected TeamCode 1.3.0." in lines)
        // The app's installation runs on the CLI's new version.
        assertTrue("Installing TeamCode Desktop 1.3.0…" in lines)
        assertTrue(lines.last().startsWith("TeamCode Desktop installs version 1.3.0"))
    } }

    @Test fun aFailedCliUpdateStopsBeforeTheApp() = withTemporaryDirectory { directory -> runTest {
        if (!posix) return@runTest
        cli(directory, update = "echo 'Downloading…'; echo 'Error: This TeamCode CLI is not a managed installation (release_not_managed).' >&2; exit 1")
        val error = assertFailsWith<AppUpdateException> { updater(directory).update { } }
        assertEquals("This TeamCode CLI is not a managed installation (release_not_managed).", error.message)
        assertEquals(listOf("update"), calls(directory))
    } }

    @Test fun aFailedInstallationIsTheClisSentence() = withTemporaryDirectory { directory -> runTest {
        if (!posix) return@runTest
        cli(directory, ui = "echo 'The downloaded TeamCode Desktop is not signed by TeamCode publisher (desktop_signature_invalid).' >&2; exit 1")
        val error = assertFailsWith<AppUpdateException> { updater(directory).update { } }
        assertEquals("The downloaded TeamCode Desktop is not signed by TeamCode publisher (desktop_signature_invalid).", error.message)
    } }

    @Test fun aCliTooOldForTheRestartSaysWhatToRun() = withTemporaryDirectory { directory -> runTest {
        if (!posix) return@runTest
        cli(directory, update = "echo 'The managed installation is already up to date.'", ui = "echo 'Error: no such option --restart-app' >&2; exit 1")
        val error = assertFailsWith<AppUpdateException> { updater(directory).update { } }
        assertEquals("TeamCode CLI 1.2.0 cannot update this app by itself. Quit TeamCode, then run teamcode ui --yes in a terminal.", error.message)
    } }

    @Test fun withoutACliTheUpdateSaysHowToInstallIt() = withTemporaryDirectory { directory -> runTest {
        if (!posix) return@runTest
        val error = assertFailsWith<AppUpdateException> { updater(directory).update { } }
        assertTrue(error.message.startsWith("The TeamCode CLI is not installed"))
    } }

    @Test fun aFailureTheCliReopenedTheAppWithIsGivenOnce() = withTemporaryDirectory { directory ->
        val updater = updater(directory, error = "The Windows installer of TeamCode Desktop failed (desktop_msiexec_failed_1603).")
        assertEquals("The Windows installer of TeamCode Desktop failed (desktop_msiexec_failed_1603).", updater.takeStartupError())
        assertNull(updater.takeStartupError())
    }

    @Test fun theUpdateErrorArgumentIsRead() {
        val environment = DesktopEnvironment.detect(arrayOf("--config-path", "/tmp/c.json", "--update-error", "It failed\u0007."), mapOf("XDG_CONFIG_HOME" to "/tmp"))
        assertEquals("It failed.", environment.updateError)
        // The CLI's message spans lines: they stay apart as words.
        assertEquals("It failed. Run teamcode --debug ui.", DesktopEnvironment.detect(arrayOf("--update-error", "It failed.\nRun teamcode --debug ui."), mapOf("XDG_CONFIG_HOME" to "/tmp")).updateError)
        assertNull(DesktopEnvironment.detect(arrayOf(), mapOf("XDG_CONFIG_HOME" to "/tmp")).updateError)
    }

    @Test fun quitExitsTheApp() = withTemporaryDirectory { directory ->
        val quits = mutableListOf<Unit>()
        updater(directory, quits = quits).quit()
        assertEquals(1, quits.size)
    }
}
