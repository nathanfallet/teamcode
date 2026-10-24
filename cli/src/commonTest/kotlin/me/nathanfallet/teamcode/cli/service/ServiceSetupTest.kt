package me.nathanfallet.teamcode.cli.service

import com.github.ajalt.clikt.core.CliktError
import kotlinx.coroutines.test.runTest
import me.nathanfallet.teamcode.api.Serialization
import me.nathanfallet.teamcode.cli.platform.ServicePlatform
import me.nathanfallet.teamcode.cli.platform.hostOs
import me.nathanfallet.teamcode.cli.platform.secureRandomBytes
import me.nathanfallet.teamcode.cli.platform.writePrivateFile
import okio.Buffer
import okio.ByteString.Companion.toByteString
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import kotlin.test.*

/**
 * Decisions 10 and 11: the service step of `teamcode install`, `teamcode update` and the automatic update job. A missing
 * service is installed at login, a disabled or unloaded one repaired, a good one left alone; `--no-service`, the
 * persisted setting, a boot registration and a cloud runner are respected; no step asks for elevation.
 */
class ServiceSetupTest {
    private val fs = FileSystem.SYSTEM

    /** The OS service manager, with the registration file on disk and the rest of its state in memory. */
    private class FakeSystem(var enabled: Boolean = true, var loaded: Boolean = true, var running: Boolean = true, val failing: String? = null) : LoginServiceSystem {
        val calls = mutableListOf<String>()
        private fun call(name: String) { calls += name; if (name == failing) throw CliktError("The OS service manager rejected the operation.") }
        override suspend fun inspect(settings: ServiceSettings): LoginStartup { call("inspect"); return LoginStartup(ownsRegistration(settings), enabled, loaded, running) }
        override suspend fun register(settings: ServiceSettings) { call("register"); writeRegistration(settings) }
        override suspend fun enable(settings: ServiceSettings) { call("enable"); enabled = true }
        override suspend fun start(settings: ServiceSettings) { call("start"); loaded = true; running = true }
    }

    private class Scratch(val root: Path) {
        val home = root / "home"
        val config = (home / ".config" / "teamcode" / "config.json").toString()
        val launcher = (root / "install" / "bin" / "teamcode").toString()
    }

    private fun scratch(block: suspend (Scratch) -> Unit) = runTest {
        val root = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / ("teamcode-service-setup-" + secureRandomBytes(12).toByteString().hex())
        fs.createDirectories(root / "home")
        try { block(Scratch(fs.canonicalize(root))) } finally { if (fs.metadataOrNull(root) != null) fs.deleteRecursively(root) }
    }

    private fun platform(os: String, scratch: Scratch) = ServicePlatform(os, scratch.launcher,
        if (os == "windows") "S-1-5-21-123-456-789-1001" else "501", "fixture", scratch.home.toString())

    /** No boot identity check: the tests describe registrations of other systems than the one running them. */
    private fun read(config: String): ServiceSettings? = servicePath(config).toPath().takeIf { fs.exists(it) }?.let { path ->
        Serialization.json.decodeFromString(ServiceSettings.serializer(), fs.read(path) { readUtf8() })
    }

    private suspend fun setUp(scratch: Scratch, system: FakeSystem, os: String = "linux", optOut: String? = null, cloudRunner: Boolean = false) =
        setUpService(scratch.config, scratch.launcher, optOut, cloudRunner = { cloudRunner }, platform = { platform(os, scratch) },
            home = { scratch.home.toString() }, system = system, load = ::read)

    private fun save(settings: ServiceSettings) =
        writePrivateFile(servicePath(settings.config), Serialization.json.encodeToString(ServiceSettings.serializer(), settings))

    private fun installed(scratch: Scratch, os: String, atBoot: Boolean = false, executable: String = scratch.launcher): ServiceSettings {
        val platform = platform(os, scratch).copy(executable = executable)
        val settings = if (atBoot && os == "macos") newServiceSettings(scratch.config, 8789, executable, false, platform, scratch.home.toString()).let {
            ServiceSettings(it.id, it.port, it.localToken, it.executable, it.config, "/Library/LaunchDaemons/${it.label}.plist", "macos", "501", true, "fixture", scratch.home.toString())
        } else newServiceSettings(scratch.config, 8789, executable, atBoot, platform, scratch.home.toString())
        save(settings)
        if (!(atBoot && os == "macos")) writeRegistration(settings)
        return settings
    }

    @Test fun aDeviceWithoutTheServiceGetsItInstalledAndStartedAtLogin() = scratch { scratch ->
        for (os in listOf("macos", "linux", "windows")) {
            fs.deleteRecursively(scratch.home); fs.createDirectories(scratch.home)
            val system = FakeSystem(enabled = false, loaded = false, running = false)
            val setup = setUp(scratch, system, os)
            assertEquals(ServiceSetupOutcome.INSTALLED, setup.outcome, os)
            assertEquals(listOf("inspect", "register", "enable", "start", "inspect"), system.calls, os)
            val settings = assertNotNull(read(scratch.config))
            assertFalse(settings.atBoot, "Login startup by default ($os)")
            assertEquals(scratch.launcher, settings.executable, "The managed launcher, which follows updates")
            assertEquals(8789, settings.port)
            assertTrue(ownsRegistration(settings))
            // The registration of another OS than the host's keeps that OS's separator after the home directory.
            fun portable(path: Any) = path.toString().replace('\\', '/')
            when (os) {
                "macos" -> assertEquals(portable(scratch.home / "Library" / "LaunchAgents" / "${settings.label}.plist"), portable(settings.registration))
                "linux" -> assertEquals(portable(scratch.home / ".config" / "systemd" / "user" / "${settings.label}.service"), portable(settings.registration))
                else -> assertEquals(portable(scratch.home / ".config" / "teamcode" / "service-task.xml"), portable(settings.registration))
            }
            assertTrue("starts at login" in setup.line && "teamcode login" in setup.line, setup.line)
            assertFalse(setup.failed)
        }
    }

    @Test fun aDisabledOrUnloadedRegistrationIsRepaired() = scratch { scratch ->
        for (os in listOf("macos", "linux", "windows")) {
            val settings = installed(scratch, os)
            val disabled = FakeSystem(enabled = false)
            val repaired = setUp(scratch, disabled, os)
            assertEquals(ServiceSetupOutcome.REPAIRED, repaired.outcome, os)
            assertEquals(listOf("inspect", "enable", "inspect"), disabled.calls, "Enabled again, nothing else ($os)")
            assertTrue("enabled at login" in repaired.line, repaired.line)

            val unloaded = FakeSystem(loaded = false, running = false)
            assertEquals(ServiceSetupOutcome.REPAIRED, setUp(scratch, unloaded, os).outcome)
            assertEquals(listOf("inspect", "start", "inspect"), unloaded.calls, "Loaded and started ($os)")

            val stopped = FakeSystem(running = false)
            assertEquals(ServiceSetupOutcome.REPAIRED, setUp(scratch, stopped, os).outcome)
            assertEquals(listOf("inspect", "start", "inspect"), stopped.calls)

            fs.delete(settings.registration.toPath())
            val missing = FakeSystem(enabled = false, loaded = false, running = false)
            assertEquals(ServiceSetupOutcome.REPAIRED, setUp(scratch, missing, os).outcome)
            assertEquals(listOf("inspect", "register", "enable", "start", "inspect"), missing.calls, "A deleted registration is written again ($os)")
            assertEquals(settings.id, read(scratch.config)?.id, "The same service, not a second one")
            assertTrue(ownsRegistration(settings))
        }
    }

    @Test fun aGoodRegistrationIsLeftAlone() = scratch { scratch ->
        val settings = installed(scratch, "macos")
        val before = fs.read(servicePath(scratch.config).toPath()) { readByteArray() }
        val system = FakeSystem()
        repeat(2) {
            val setup = setUp(scratch, system, "macos")
            assertEquals(ServiceSetupOutcome.UNCHANGED, setup.outcome)
            assertTrue("nothing to change" in setup.line, setup.line)
        }
        assertEquals(listOf("inspect", "inspect"), system.calls, "Only inspected")
        assertContentEquals(before, fs.read(servicePath(scratch.config).toPath()) { readByteArray() })
        assertTrue(ownsRegistration(settings))
    }

    @Test fun theOptOutIsRespected() = scratch { scratch ->
        val system = FakeSystem(enabled = false, loaded = false, running = false)
        val flag = setUp(scratch, system, optOut = "--no-service")
        assertEquals(ServiceSetupOutcome.OPTED_OUT, flag.outcome)
        assertTrue("--no-service" in flag.line)

        assertTrue(automaticServiceSetup(scratch.config), "On by default")
        setAutomaticServiceSetup(scratch.config, false)
        assertFalse(automaticServiceSetup(scratch.config))
        assertEquals(ServiceSetupOutcome.OPTED_OUT, setUp(scratch, system).outcome)
        assertTrue(system.calls.isEmpty(), "Nothing inspected or changed")
        assertNull(read(scratch.config), "No service written")

        setAutomaticServiceSetup(scratch.config, true)
        assertEquals(ServiceSetupOutcome.INSTALLED, setUp(scratch, system).outcome)
    }

    @Test fun aBootRegistrationIsRespectedAsItIs() = scratch { scratch ->
        for (os in listOf("macos", "linux", "windows")) {
            installed(scratch, os, atBoot = true)
            val system = FakeSystem(enabled = false, loaded = false, running = false)
            val setup = setUp(scratch, system, os)
            assertEquals(ServiceSetupOutcome.AT_BOOT, setup.outcome, os)
            assertTrue(system.calls.isEmpty(), "Not inspected, not switched to login, never elevated ($os)")
            assertTrue(assertNotNull(read(scratch.config)).atBoot)
        }
    }

    @Test fun aServiceOfAnotherExecutableIsKept() = scratch { scratch ->
        installed(scratch, "linux", executable = (scratch.root / "checkout" / "teamcode").toString())
        val system = FakeSystem(enabled = false)
        assertEquals(ServiceSetupOutcome.OTHER_EXECUTABLE, setUp(scratch, system).outcome)
        assertTrue(system.calls.isEmpty())
    }

    @Test fun aCloudRunnerIsANoOp() = scratch { scratch ->
        val system = FakeSystem(enabled = false, loaded = false, running = false)
        val setup = setUpService(scratch.config, scratch.launcher, null, cloudRunner = { true }, platform = { error("not read") },
            home = { error("not read") }, system = system, load = { error("not read") })
        assertEquals(ServiceSetupOutcome.CLOUD_RUNNER, setup.outcome)
        assertTrue(system.calls.isEmpty())
        assertNull(fs.metadataOrNull(scratch.home / ".config"), "Nothing written")
    }

    @Test fun aCliOutsideAManagedInstallationLeavesTheServiceAlone() = scratch { scratch ->
        val system = FakeSystem(enabled = false, loaded = false, running = false)
        val setup = setUpService(scratch.config, null, null, cloudRunner = { false }, platform = { error("not read") },
            home = { error("not read") }, system = system, load = { error("not read") })
        assertEquals(ServiceSetupOutcome.UNMANAGED, setup.outcome)
        assertFalse(setup.failed)
        assertTrue("teamcode service install" in setup.line)
        assertTrue(system.calls.isEmpty())
        assertNull(read(scratch.config), "No service written")
        // The opt-out and the cloud runner still come first.
        assertEquals(ServiceSetupOutcome.OPTED_OUT, setUpService(scratch.config, null, "--no-service", cloudRunner = { false },
            platform = { error("not read") }, home = { error("not read") }, system = system).outcome)
        assertEquals(ServiceSetupOutcome.CLOUD_RUNNER, setUpService(scratch.config, null, null, cloudRunner = { true },
            platform = { error("not read") }, home = { error("not read") }, system = system).outcome)
    }

    @Test fun aFailureIsReportedWithTheCommandToRunAndNeverThrown() = scratch { scratch ->
        val setup = setUp(scratch, FakeSystem(enabled = false, loaded = false, running = false, failing = "enable"))
        assertEquals(ServiceSetupOutcome.FAILED, setup.outcome)
        assertTrue(setup.failed)
        assertTrue("teamcode service install" in setup.line && "rejected" in setup.line, setup.line)
        // The next run picks up where it stopped: the same service, enabled and started.
        val again = FakeSystem(enabled = false, loaded = false, running = false)
        assertEquals(ServiceSetupOutcome.REPAIRED, setUp(scratch, again).outcome)
        assertEquals(listOf("inspect", "enable", "start", "inspect"), again.calls)

        val root = setUpService(scratch.config, scratch.launcher, null, cloudRunner = { false },
            platform = { platform("linux", scratch).copy(userId = "0") }, home = { scratch.home.toString() }, system = FakeSystem(), load = ::read)
        assertEquals(ServiceSetupOutcome.FAILED, root.outcome)
        assertTrue("root" in root.line)
    }

    @Test fun noStepOfTheLoginSetupAsksForElevation() = scratch { scratch ->
        for (os in listOf("macos", "linux", "windows")) {
            val settings = newServiceSettings(scratch.config, 8789, scratch.launcher, false, platform(os, scratch), scratch.home.toString())
            for (action in listOf("status", "enabled", "running", "active", "daemon-reload", "enable", "load", "start")) {
                if (os == "macos" && action in setOf("active", "daemon-reload")) continue
                if (os == "linux" && action in setOf("active", "load")) continue
                if (os == "windows" && action == "daemon-reload") continue
                val call = serviceManagerCall(settings, action, quiet = true, systemRoot = "C:\\Windows")
                val words = listOf(call.invocation.command) + call.invocation.arguments
                assertFalse(words.any { "sudo" in it || "runas" in it.lowercase() || "-Verb" in it }, "$os $action: $words")
                assertFalse(words.any { "RegisterTask" in it || "Read-Host" in it || "loginctl" in it }, "$os $action asks for no password or linger")
                when (os) {
                    "macos" -> assertTrue(words.any { "gui/501" in it }, "The user's own launchd domain: $words")
                    "linux" -> assertEquals("--user", call.invocation.arguments.first(), "The systemd user manager")
                }
            }
        }
        // A boot registration's privileged operations stay explicit and are never part of this setup.
        val boot = newServiceSettings(scratch.config, 8789, scratch.launcher, false, platform("macos", scratch), scratch.home.toString()).let {
            ServiceSettings(it.id, 8789, it.localToken, it.executable, it.config, "/Library/LaunchDaemons/${it.label}.plist", "macos", "501", true, "fixture", scratch.home.toString())
        }
        assertEquals("/usr/bin/sudo", serviceManagerCall(boot, "start").invocation.command)
        assertEquals("/bin/sh", serviceManagerCall(boot, "running").invocation.command, "Queries are never elevated")
    }

    /** Registrations written before the daemon (and before #124) are recognized as they are, so they migrate in place. */
    @Test fun registrationsFromBeforeTheDaemonAreKeptAndRecognized() = scratch { scratch ->
        val id = "c".repeat(32)
        val label = "me.nathanfallet.teamcode.$id"
        val linux = """
            [Unit]
            Description=TeamCode local authenticated proxy
            StartLimitIntervalSec=0

            [Service]
            Type=exec
            ExecStart="${scratch.launcher}" "service" "serve" "--config" "${scratch.config}"
            Restart=on-failure
            RestartSec=30
            TimeoutStopSec=660
            UMask=0077
            NoNewPrivileges=yes
            StandardOutput=null
            StandardError=null

            [Install]
            WantedBy=default.target
        """.trimIndent() + "\n"
        val macos = """
            <?xml version="1.0" encoding="UTF-8"?>
            <!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
            <plist version="1.0"><dict>
            <key>Label</key><string>$label</string>
            <key>ProgramArguments</key><array><string>${scratch.launcher}</string><string>service</string><string>serve</string><string>--config</string><string>${scratch.config}</string></array>
            <key>RunAtLoad</key><true/>
            <key>KeepAlive</key><dict><key>SuccessfulExit</key><false/></dict>
            <key>ThrottleInterval</key><integer>30</integer>
            <key>ExitTimeOut</key><integer>660</integer>
            <key>ProcessType</key><string>Standard</string>
            <key>StandardOutPath</key><string>/dev/null</string>
            <key>StandardErrorPath</key><string>/dev/null</string>
            </dict></plist>
        """.trimIndent() + "\n"
        val windows = """
            <?xml version="1.0" encoding="UTF-16"?>
            <Task version="1.4" xmlns="http://schemas.microsoft.com/windows/2004/02/mit/task">
            <RegistrationInfo><Description>TeamCode local authenticated proxy</Description></RegistrationInfo>
            <Triggers><LogonTrigger><Enabled>true</Enabled><UserId>S-1-5-21-123-456-789-1001</UserId></LogonTrigger></Triggers>
            <Principals><Principal id="TeamCodeUser"><UserId>S-1-5-21-123-456-789-1001</UserId><LogonType>InteractiveToken</LogonType><RunLevel>LeastPrivilege</RunLevel></Principal></Principals>
            <Settings>
            <MultipleInstancesPolicy>IgnoreNew</MultipleInstancesPolicy>
            <DisallowStartIfOnBatteries>false</DisallowStartIfOnBatteries>
            <StopIfGoingOnBatteries>false</StopIfGoingOnBatteries>
            <AllowHardTerminate>false</AllowHardTerminate>
            <StartWhenAvailable>true</StartWhenAvailable>
            <RunOnlyIfNetworkAvailable>false</RunOnlyIfNetworkAvailable>
            <AllowStartOnDemand>true</AllowStartOnDemand><Enabled>true</Enabled><Hidden>false</Hidden>
            <ExecutionTimeLimit>PT0S</ExecutionTimeLimit>
            <RestartOnFailure><Interval>PT1M</Interval><Count>255</Count></RestartOnFailure>
            </Settings>
            <Actions Context="TeamCodeUser"><Exec><Command>${scratch.launcher}</Command>
            <Arguments>&quot;service&quot; &quot;serve&quot; &quot;--config&quot; &quot;${scratch.config}&quot;</Arguments></Exec></Actions>
            </Task>
        """.trimIndent() + "\n"
        // A Windows host's paths (drive, backslashes) are not what a Linux or macOS registration of that time held.
        val cases = listOf("linux" to linux, "macos" to macos, "windows" to windows).filter { hostOs() != "windows" || it.first == "windows" }
        for ((os, text) in cases) {
            val registration = when (os) {
                "linux" -> scratch.home / ".config" / "systemd" / "user" / "$label.service"
                "macos" -> scratch.home / "Library" / "LaunchAgents" / "$label.plist"
                else -> scratch.home / ".config" / "teamcode" / "service-task.xml"
            }
            val bytes = if (os == "windows") Buffer().apply { writeShortLe(0xfeff); text.forEach { writeShortLe(it.code) } }.readByteArray() else text.encodeToByteArray()
            // The settings of that time: no atBoot, userName or userHome fields. Written first, as `service install` does:
            // the private writer creates the config directory itself, and on Windows refuses one another owner created.
            val userId = if (os == "windows") "S-1-5-21-123-456-789-1001" else "501"
            writePrivateFile(servicePath(scratch.config), """{"id":"$id","port":8789,"localToken":"${"d".repeat(64)}","executable":${jsonString(scratch.launcher)},""" +
                """"config":${jsonString(scratch.config)},"registration":${jsonString(registration.toString())},"os":"$os","userId":"$userId"}""")
            fs.createDirectories(registration.parent!!)
            fs.write(registration) { write(bytes) }
            val system = FakeSystem()
            val setup = setUp(scratch, system, os)
            assertEquals(ServiceSetupOutcome.UNCHANGED, setup.outcome, "$os: ${setup.line}")
            assertContentEquals(bytes, fs.read(registration) { readByteArray() }, "$os registration untouched")
            assertEquals(id, read(scratch.config)?.id)

            // Disabled since, it is repaired in place: same id, same file.
            val disabled = FakeSystem(enabled = false, loaded = false, running = false)
            assertEquals(ServiceSetupOutcome.REPAIRED, setUp(scratch, disabled, os).outcome)
            assertEquals(listOf("inspect", "enable", "start", "inspect"), disabled.calls)
            assertContentEquals(bytes, fs.read(registration) { readByteArray() })
            fs.delete(registration)
        }
    }

    private fun jsonString(value: String) = Serialization.json.encodeToString(kotlinx.serialization.serializer<String>(), value)
}
