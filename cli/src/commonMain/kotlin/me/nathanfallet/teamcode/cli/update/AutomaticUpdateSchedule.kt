package me.nathanfallet.teamcode.cli.update

import kotlinx.coroutines.withTimeout
import me.nathanfallet.teamcode.cli.CliOperationException
import me.nathanfallet.teamcode.cli.platform.environment
import me.nathanfallet.teamcode.cli.platform.runInteractiveProcess
import okio.FileSystem
import okio.Path.Companion.toPath

/** OS registration is separate from the proxy, so stopping that proxy cannot kill its updater. */
internal interface AutomaticUpdateSchedule {
    fun owns(settings: AutomaticUpdateSettings): Boolean
    suspend fun install(settings: AutomaticUpdateSettings)
    suspend fun start(settings: AutomaticUpdateSettings)
    suspend fun disable(settings: AutomaticUpdateSettings)
    suspend fun uninstall(settings: AutomaticUpdateSettings)
}

/** Manage only exact recorded user-job definitions; never stop an in-flight update. */
internal class AutomaticUpdateScheduleImpl(
    private val execute: suspend (String, List<String>, Map<String, String?>) -> Int = { executable, arguments, overrides ->
        runInteractiveProcess(executable, arguments, overrides, quiet = true)
    },
) : AutomaticUpdateSchedule {
    private val fs = FileSystem.SYSTEM
    override fun owns(settings: AutomaticUpdateSettings): Boolean = automaticUpdateDefinitions(settings).all { (path, bytes) ->
        val meta = fs.metadataOrNull(path) ?: return@all false
        if (!meta.isRegularFile || meta.symlinkTarget != null || meta.size != bytes.size.toLong() ||
            !fs.read(path) { readByteArray() }.contentEquals(bytes)) throw CliOperationException("automatic_registration_modified")
        true
    }
    private fun requireOwned(settings: AutomaticUpdateSettings) {
        if (!owns(settings)) throw CliOperationException("automatic_registration_missing")
    }
    override suspend fun install(settings: AutomaticUpdateSettings) {
        // Validate every existing file before writing any missing one.
        automaticUpdateDefinitions(settings).forEach { (path, bytes) ->
            val meta = fs.metadataOrNull(path)
            if (meta != null && (!meta.isRegularFile || meta.symlinkTarget != null || meta.size != bytes.size.toLong() ||
                !fs.read(path) { readByteArray() }.contentEquals(bytes))) throw CliOperationException("automatic_registration_modified")
        }
        automaticUpdateDefinitions(settings).forEach { (path, bytes) ->
            require(fs.metadataOrNull(path.parent!!)?.symlinkTarget == null)
            fs.createDirectories(path.parent!!)
            if (!fs.exists(path)) fs.write(path, mustCreate = true) { write(bytes) }
        }
        when (settings.os) {
            "macos" -> {
                if (launch(settings, "print", check = false) != 0) command("/bin/launchctl", listOf("bootstrap", "gui/${settings.userId}", settings.registration))
                launch(settings, "enable")
            }
            "linux" -> {
                system(listOf("link", settings.registration.toPath().parent!!.resolve("${settings.label}.service").toString()))
                system(listOf("daemon-reload")); system(listOf("enable", "--now", settings.registration))
            }
            else -> {
                when (windowsStatus(settings)) { 0 -> Unit; 3 -> windows(settings, listOf("/Create", "/TN", settings.label, "/XML", settings.registration)); else -> throw CliOperationException("automatic_manager_unavailable") }
                windows(settings, listOf("/Change", "/TN", settings.label, "/Enable"))
            }
        }
    }
    override suspend fun start(settings: AutomaticUpdateSettings) {
        requireOwned(settings)
        when (settings.os) {
            "macos" -> launch(settings, "kickstart") // No -k: never kill an existing worker.
            "linux" -> system(listOf("start", "--no-block", "${settings.label}.service"))
            else -> windows(settings, listOf("/Run", "/TN", settings.label))
        }
    }
    override suspend fun disable(settings: AutomaticUpdateSettings) {
        requireOwned(settings)
        when (settings.os) {
            "macos" -> launch(settings, "disable")
            "linux" -> {
                // disable removes links for definitions outside the manager's
                // search path. Restore only our verified timer before repeated
                // off/uninstall calls; linking does not enable or start it.
                system(listOf("link", settings.registration))
                system(listOf("disable", "--now", "${settings.label}.timer"))
            }
            else -> when (windowsStatus(settings)) { 0 -> windows(settings, listOf("/Change", "/TN", settings.label, "/Disable")); 3 -> Unit; else -> throw CliOperationException("automatic_manager_unavailable") }
        }
    }
    override suspend fun uninstall(settings: AutomaticUpdateSettings) {
        requireOwned(settings)
        disable(settings)
        when (settings.os) {
            "macos" -> if (launch(settings, "print", check = false) == 0) launch(settings, "bootout")
            "linux" -> system(listOf("disable", "${settings.label}.service"))
            else -> when (windowsStatus(settings)) { 0 -> windows(settings, listOf("/Delete", "/TN", settings.label, "/F")); 3 -> Unit; else -> throw CliOperationException("automatic_manager_unavailable") }
        }
        automaticUpdateDefinitions(settings).keys.forEach { fs.delete(it) }
        if (settings.os == "linux") system(listOf("daemon-reload"))
    }

    private suspend fun command(executable: String, args: List<String>, check: Boolean = true, env: Map<String, String?> = emptyMap()): Int {
        val result = withTimeout(15_000) { execute(executable, args, env) }
        if (check && result != 0) throw CliOperationException("automatic_manager_rejected")
        return result
    }
    private suspend fun launch(settings: AutomaticUpdateSettings, action: String, check: Boolean = true) =
        command("/bin/launchctl", listOf(action, "gui/${settings.userId}/${settings.label}"), check)
    private suspend fun system(args: List<String>) = command("/usr/bin/systemctl", listOf("--user") + args)
    private suspend fun windows(settings: AutomaticUpdateSettings, args: List<String>) =
        command(requireNotNull(environment("SystemRoot")) + "\\System32\\schtasks.exe", args)
    private suspend fun windowsStatus(settings: AutomaticUpdateSettings): Int {
        val system = requireNotNull(environment("SystemRoot")) + "\\System32"
        val script = """
            ${'$'}ErrorActionPreference = 'Stop'
            try {
                ${'$'}scheduler = New-Object -ComObject 'Schedule.Service'; ${'$'}scheduler.Connect()
                ${'$'}task = ${'$'}scheduler.GetFolder('\').GetTasks(1) | Where-Object { ${'$'}_.Name -eq ${'$'}env:TEAMCODE_AUTOMATIC_TASK }
                if (-not ${'$'}task) { exit 3 }; exit 0
            } catch { exit 2 }
        """.trimIndent()
        return command("$system\\WindowsPowerShell\\v1.0\\powershell.exe", listOf("-NoLogo", "-NoProfile", "-NonInteractive", "-Command", script), false,
            mapOf("TEAMCODE_AUTOMATIC_TASK" to settings.label, "PSModulePath" to "$system\\WindowsPowerShell\\v1.0\\Modules"))
    }
}
