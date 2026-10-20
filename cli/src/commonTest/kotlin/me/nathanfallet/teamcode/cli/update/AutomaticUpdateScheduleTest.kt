package me.nathanfallet.teamcode.cli.update

import kotlinx.coroutines.test.runTest
import me.nathanfallet.teamcode.cli.platform.ServicePlatform
import me.nathanfallet.teamcode.cli.platform.secureRandomBytes
import okio.ByteString.Companion.toByteString
import okio.FileSystem
import okio.Path.Companion.toPath
import kotlin.test.*

class AutomaticUpdateScheduleTest {
    @Test fun repeatedDisableAndUninstallResolveCustomUnitsWithoutStoppingTheWorker() = runTest {
        val fs = FileSystem.SYSTEM
        val root = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / ("teamcode-systemd-" + secureRandomBytes(12).toByteString().hex())
        try {
            val paths = InstallationPaths(root, "linux-x64")
            InstallationStore(paths).initialize()
            val settings = automaticUpdateSettings(paths, (root / "custom-config/teamcode/config.json").toString(),
                ServicePlatform("linux", paths.launcher.toString(), "1000"), root.toString())
            // The user's manager links custom-path definitions into its own search
            // directory. Disabling removes that link as well as enablement links.
            val available = mutableSetOf<String>()
            var enabled = false
            var runningTimer = false
            var workerRunning = true
            val schedule = AutomaticUpdateScheduleImpl { executable, arguments, _ ->
                assertEquals("/usr/bin/systemctl", executable)
                assertEquals("--user", arguments.first())
                val operation = arguments[1]
                val unit = arguments.last().toPath().name
                when (operation) {
                    "link" -> { available.add(unit); 0 }
                    "enable" -> { available.add(unit); enabled = true; runningTimer = true; 0 }
                    "daemon-reload" -> 0
                    "disable" -> if (unit !in available) 1 else {
                        available.remove(unit)
                        if (unit.endsWith(".timer")) { enabled = false; runningTimer = false }
                        if (unit.endsWith(".service") && "--now" in arguments) workerRunning = false
                        0
                    }
                    else -> error("Unexpected systemd operation")
                }
            }
            schedule.install(settings)
            assertTrue(enabled && runningTimer)
            repeat(2) {
                schedule.disable(settings)
                assertFalse(enabled || runningTimer)
                assertTrue(workerRunning)
                assertTrue(schedule.owns(settings))
            }
            schedule.uninstall(settings)
            assertFalse(enabled || runningTimer)
            assertTrue(workerRunning)
            assertTrue(available.isEmpty())
            assertTrue(automaticUpdateDefinitions(settings).keys.none(fs::exists))
        } finally { if (fs.exists(root)) fs.deleteRecursively(root) }
    }
}
