package me.nathanfallet.teamcode.cli.service

import com.github.ajalt.clikt.command.*
import com.github.ajalt.clikt.core.*
import com.github.ajalt.clikt.parameters.options.*
import com.github.ajalt.clikt.parameters.types.int
import kotlinx.coroutines.*
import me.nathanfallet.teamcode.api.Serialization
import me.nathanfallet.teamcode.cli.config.*
import me.nathanfallet.teamcode.cli.platform.*
import me.nathanfallet.teamcode.cli.sdk
import me.nathanfallet.teamcode.client.TeamCodeException
import okio.FileSystem
import okio.Path.Companion.toPath

class Service : SuspendingNoOpCliktCommand(name = "service") {
    override fun help(context: Context) = "Manage automatic local proxy startup at login or, explicitly, at boot."
    init { subcommands(Install(), Print(), Manage("start"), Manage("stop"), Manage("restart"), Manage("uninstall"), Status(), Logs(), Serve()) }
}

private fun settings(config: String, port: Int, executable: String?, atBoot: Boolean = false): ServiceSettings {
    val platform = servicePlatform()
    if (platform.userId == "0") throw CliktError("Run service installation as your user; TeamCode requests OS authorization only where needed.")
    val home = environment("HOME") ?: environment("USERPROFILE") ?: throw CliktError("Cannot locate the user home directory.")
    val id = randomHex(16)
    val label = "me.nathanfallet.teamcode.$id"
    val registration = when (platform.os) {
        "macos" -> if (atBoot) "/Library/LaunchDaemons/$label.plist" else "$home/Library/LaunchAgents/$label.plist"
        "windows" -> config.toPath().parent!!.resolve("service-task.xml").toString()
        else -> "${config.toPath().parent!!.parent}/systemd/user/$label.service"
    }
    return ServiceSettings(id, port, randomHex(32), executable ?: me.nathanfallet.teamcode.cli.update.managedInstallation()?.paths?.launcher?.toString() ?: platform.executable, config, registration, platform.os, platform.userId, atBoot, platform.userName, platform.userHome)
}

private class Print : SuspendingCliktCommand(name = "print") {
    private val atBoot by option("--at-boot", "--system", help = "Start before login; requires OS authorization and, on Windows, your Windows account password.").flag()
    private val port by option("--port").int().default(8789)
    private val binary by option("--binary", help = "Stable absolute path to the native TeamCode executable.")
    override suspend fun run() {
        val selected = loadService(configPath()) ?: settings(configPath(), port, binary, atBoot)
        if (atBoot && !selected.atBoot) throw CliktError("Uninstall before switching startup modes.")
        echo(serviceDefinition(selected))
    }
}

private class Install : SuspendingCliktCommand(name = "install") {
    private val atBoot by option("--at-boot", "--system", help = "Start before login; requires OS authorization and, on Windows, your Windows account password.").flag()
    private val port by option("--port").int()
    private val binary by option("--binary", help = "Stable absolute path; defaults to the running executable.")
    override suspend fun run() {
        val config = configPath()
        if (configStore(config).load() == null) throw CliktError("Run teamcode login before installing a service.")
        val existing = loadService(config)
        val selected = existing ?: settings(config, port ?: 8789, binary, atBoot)
        if (atBoot && !selected.atBoot) throw CliktError("Uninstall before switching from login to boot startup.")
        if (port != null && port != selected.port) throw CliktError("Uninstall before changing the service port.")
        if (binary != null && binary != selected.executable) throw CliktError("Uninstall before changing the service executable.")
        if (FileSystem.SYSTEM.metadataOrNull(selected.executable.toPath())?.isRegularFile != true) throw CliktError("The executable must exist at a stable absolute path.")
        if (existing == null) writePrivateFile(servicePath(config), Serialization.json.encodeToString(ServiceSettings.serializer(), selected))
        if (selected.atBoot) echo(when (selected.os) {
            "macos" -> "Installing a LaunchDaemon running as your user. macOS may request administrator authorization."
            "windows" -> "Boot startup needs an elevated terminal and your Windows account password in a masked OS prompt. TeamCode does not store it."
            else -> "Enabling systemd user lingering for boot startup. OS authorization may be requested; lingering is retained after uninstall."
        })
        installServiceRegistration(selected)
        if (selected.atBoot && selected.os == "linux") enableLinuxBootStartup(selected)
        if (selected.os == "linux") {
            manager(selected, "daemon-reload")
            manager(selected, "enable")
        }
        start(selected)
        echo("Installed service ${selected.label}; it starts automatically at ${if (selected.atBoot) "boot" else "login"}. Local port: ${selected.port}.")
        echo("Use teamcode service status to check local and upstream availability separately.")
    }
}

private suspend fun manager(settings: ServiceSettings, action: String, checkResult: Boolean = true, quiet: Boolean = false): Int {
    val command: String
    val args: List<String>
    if (settings.os == "windows") {
        val system = requireNotNull(environment("SystemRoot")) + "\\System32"
        if (settings.atBoot && action == "load") {
            command = "$system\\WindowsPowerShell\\v1.0\\powershell.exe"
            args = listOf("-NoLogo", "-NoProfile", "-Command", windowsBootRegistrationScript())
        } else if (action in setOf("status", "active")) {
            command = "$system\\WindowsPowerShell\\v1.0\\powershell.exe"
            val script = """
                ${'$'}ErrorActionPreference = 'Stop'
                try {
                    ${'$'}scheduler = New-Object -ComObject 'Schedule.Service'
                    ${'$'}scheduler.Connect()
                    ${'$'}task = ${'$'}scheduler.GetFolder('\').GetTasks(1) | Where-Object { ${'$'}_.Name -eq ${'$'}env:TEAMCODE_SERVICE_TASK }
                    if (-not ${'$'}task) { exit 3 }
                    ${if (settings.atBoot) windowsBootTaskCheck() else ""}
                    if (${'$'}env:TEAMCODE_SERVICE_OPERATION -eq 'active' -and ${'$'}task.GetInstances(0).Count -eq 0) { exit 3 }
                    exit 0
                } catch { exit 2 }
            """.trimIndent()
            args = listOf("-NoLogo", "-NoProfile", "-NonInteractive", "-Command", script)
        } else {
            command = "$system\\schtasks.exe"
            args = when (action) {
                "load" -> listOf("/Create", "/TN", settings.label, "/XML", settings.registration)
                "enable" -> listOf("/Change", "/TN", settings.label, "/Enable")
                "disable" -> listOf("/Change", "/TN", settings.label, "/Disable")
                "start" -> listOf("/Run", "/TN", settings.label)
                "delete" -> listOf("/Delete", "/TN", settings.label, "/F")
                else -> error("Unsupported Task Scheduler operation.")
            }
        }
    } else if (settings.os == "macos") {
        command = "/bin/launchctl"
        val domain = if (settings.atBoot) "system" else "gui/${settings.userId}"
        val target = "$domain/${settings.label}"
        args = when (action) {
            "status" -> listOf("print", target)
            "start" -> listOf("kickstart", target)
            "load" -> listOf("bootstrap", domain, settings.registration)
            "stop" -> listOf("bootout", target)
            else -> error("Unsupported launchd operation.")
        }
    } else {
        command = "/usr/bin/systemctl"
        args = listOf("--user") + if (action == "daemon-reload") listOf(action)
            else listOf(action, if (action == "enable") settings.registration else "${settings.label}.service")
    }
    val invocation = if (settings.atBoot && settings.os == "macos" && action != "status")
        ServiceManagerInvocation("/usr/bin/sudo", (if (quiet) listOf("-n") else emptyList()) + command + args)
        else ServiceManagerInvocation(command, args)
    val result = withTimeout(if (action in setOf("stop", "restart")) 670_000L else if (settings.atBoot && !quiet) 180_000L else 15_000L) {
        runInteractiveProcess(invocation.command, invocation.arguments, if (settings.os == "windows") mapOf(
            "TEAMCODE_SERVICE_TASK" to settings.label, "TEAMCODE_SERVICE_OPERATION" to action,
            "TEAMCODE_SERVICE_REGISTRATION" to settings.registration, "TEAMCODE_SERVICE_USER" to settings.userId,
            "TEAMCODE_SERVICE_EXECUTABLE" to settings.executable,
            "TEAMCODE_SERVICE_ARGUMENTS" to listOf("service", "serve", "--config", settings.config).joinToString(" ", transform = ::quoteWindowsArgument),
            // A caller running PowerShell 7 may export modules incompatible with Windows PowerShell 5.1.
            "PSModulePath" to "${requireNotNull(environment("SystemRoot"))}\\System32\\WindowsPowerShell\\v1.0\\Modules",
        ) else emptyMap(), quiet = quiet || action in setOf("status", "active"))
    }
    if (checkResult && result != 0) throw CliktError("The OS service manager rejected the operation.")
    return result
}

private suspend fun start(settings: ServiceSettings, quiet: Boolean = false) {
    if (!ownsRegistration(settings)) throw CliktError("The service registration is missing. Run service install.")
    if (settings.os == "windows") {
        when (manager(settings, "status", false)) {
            3 -> manager(settings, "load", quiet = quiet)
            0 -> Unit
            else -> error("Cannot query Windows Task Scheduler.")
        }
        FileSystem.SYSTEM.delete(serviceStopPath(settings.config).toPath(), mustExist = false)
        manager(settings, "enable", quiet = quiet)
        manager(settings, "start", quiet = quiet)
        return
    }
    if (settings.os == "macos" && manager(settings, "status", false) != 0) manager(settings, "load", quiet = quiet)
    else manager(settings, "start", quiet = quiet)
}

private suspend fun stop(settings: ServiceSettings, quiet: Boolean = false) {
    if (!ownsRegistration(settings)) throw CliktError("The service registration is missing; refusing to stop an unknown service.")
    if (settings.os == "windows") {
        when (manager(settings, "status", false)) {
            3 -> return
            0 -> Unit
            else -> error("Cannot query Windows Task Scheduler.")
        }
        manager(settings, "disable", quiet = quiet)
        writePrivateFile(serviceStopPath(settings.config), settings.id)
        withTimeout(660_000) {
            while (true) when (manager(settings, "active", false)) {
                3 -> break
                0 -> delay(500)
                else -> error("Cannot verify that the Windows task has stopped.")
            }
        }
        return
    }
    if (settings.os != "macos" || manager(settings, "status", false) == 0) manager(settings, "stop", quiet = quiet)
    // bootout can return while the old process is still draining. Do not race its replacement.
    if (settings.os == "macos") withTimeout(660_000) {
        while (manager(settings, "status", false) == 0) delay(250)
    }
}

private class Manage(private val operation: String) : SuspendingCliktCommand(name = operation) {
    override suspend fun run() {
        val selected = loadService(configPath())
        if (selected == null) {
            if (operation != "uninstall" && operation != "stop") throw CliktError("Run teamcode service install first.")
            echo("No TeamCode service is installed for this configuration.")
            return
        }
        when (operation) {
            "start" -> start(selected)
            "stop" -> stop(selected)
            "restart" -> { echo("Draining the local proxy before restarting."); stop(selected); start(selected) }
            "uninstall" -> {
                if (!ownsRegistration(selected)) throw CliktError("The registration is missing. Repair it with service install before uninstalling.")
                stop(selected)
                if (selected.os == "linux") manager(selected, "disable")
                if (selected.os == "windows") {
                    when (manager(selected, "status", false)) {
                        0 -> manager(selected, "delete")
                        3 -> Unit
                        else -> error("Cannot query Windows Task Scheduler.")
                    }
                    FileSystem.SYSTEM.delete(serviceStopPath(selected.config).toPath(), mustExist = false)
                }
                removeServiceRegistration(selected)
                if (selected.os == "linux") manager(selected, "daemon-reload")
                FileSystem.SYSTEM.delete(servicePath(selected.config).toPath())
                echo("Removed the TeamCode service. Saved login and logs are preserved.")
            }
        }
    }
}

private class Status : SuspendingCliktCommand(name = "status") {
    override suspend fun run() {
        val selected = loadService(configPath())
        echo("Installed: ${selected != null && ownsRegistration(selected)}")
        if (selected != null) echo("Startup: ${if (selected.atBoot) "boot (before login)" else "user login"}")
        val config = configStore().load()
        echo("Running: ${selected != null && config != null && serviceResponds(selected, config.serverUrl)}")
        if (config == null) { echo("Authenticated: no saved login"); return }
        try {
            sdk(config).use { it.me() }
            echo("Connected: true | Authenticated: true")
        } catch (error: CancellationException) { throw error }
        catch (error: TeamCodeException) { echo("Connected: true | Authenticated: ${if (error.statusCode == 401) "false" else "unverified (HTTP ${error.statusCode})"}") }
        catch (_: Exception) { echo("Connected: false | Authenticated: unverified (server unreachable)") }
    }
}

private class Logs : SuspendingCliktCommand(name = "logs") {
    override suspend fun run() {
        val path = serviceLogPath(configPath()).toPath()
        val meta = FileSystem.SYSTEM.metadataOrNull(path)
        if (meta == null) { echo("No service logs yet."); return }
        require(meta.symlinkTarget == null && meta.isRegularFile && (meta.size ?: 0) <= 65_536)
        echo(FileSystem.SYSTEM.read(path) { readUtf8() })
    }
}

private class Serve : SuspendingCliktCommand(name = "serve") {
    private val config by option("--config", help = "Absolute saved configuration path, supplied by the service manager.").required()
    override suspend fun run() {
        require(config.toPath().isAbsolute)
        serve(requireNotNull(loadService(config)) { "Service settings are missing." })
    }
}


// Automatic installation recovery runs before arbitrary CLI commands, including JSON/version output.
internal suspend fun startManagedService(settings: ServiceSettings) = start(settings, quiet = true)
internal suspend fun stopManagedService(settings: ServiceSettings) = stop(settings, quiet = true)
internal suspend fun managedServiceRunning(settings: ServiceSettings): Boolean =
    manager(settings, if (settings.os == "windows") "active" else "status", false) == 0
