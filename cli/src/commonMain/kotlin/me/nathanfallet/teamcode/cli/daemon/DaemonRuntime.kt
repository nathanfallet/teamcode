package me.nathanfallet.teamcode.cli.daemon

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import me.nathanfallet.teamcode.api.BuildInfo
import me.nathanfallet.teamcode.cli.config.configPath
import me.nathanfallet.teamcode.cli.platform.environment
import me.nathanfallet.teamcode.cli.platform.servicePlatform
import me.nathanfallet.teamcode.cli.platform.setEnvironment
import me.nathanfallet.teamcode.cli.platform.startDetachedProcess
import me.nathanfallet.teamcode.cli.remote.RemoteSessions
import me.nathanfallet.teamcode.client.TeamCodeClient
import me.nathanfallet.teamcode.config.configSibling
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath

/** Held by the local service for its whole life: one daemon per configuration, so per device. */
internal fun daemonLockPath(config: String) = configSibling(config, "daemon.lock")

/**
 * The daemon inside the local service, until [stopRequested]: it waits for a server that starts sessions on devices
 * ([DAEMON_CAPABILITY]), then runs [DeviceDaemon] with hosts started from the service's stable [executable] (the
 * launcher of a managed installation, so a host started after an update runs the new version).
 */
internal suspend fun runDaemon(client: TeamCodeClient, config: String, executable: String, report: (String) -> Unit, stopRequested: () -> Boolean) {
    // The agent's process on Windows is not supported by the session host yet; the device never shows online there.
    if (servicePlatform().os == "windows") { report("Remote sessions are not hosted on Windows yet; the daemon stays offline."); return }
    if (insideCloudRunner()) { report(CLOUD_RUNNER_REFUSAL); return }
    // Folders, records and hosts all read the CLI's own configuration: a service for another one hosts nothing.
    if (config != configPath()) { report("This service uses another configuration than the CLI's ($config); the daemon stays offline."); return }
    var told = false
    while (!stopRequested()) {
        val features = try { client.capabilities().features }
            catch (error: CancellationException) { throw error }
            catch (_: Exception) { null }
        if (features != null && DAEMON_CAPABILITY in features) break
        if (features != null && !told) { report("The server does not start sessions on devices ($DAEMON_CAPABILITY); the daemon checks again every 10 minutes."); told = true }
        var waited = 0L
        val wait = if (features == null) 30_000L else 600_000L
        while (waited < wait && !stopRequested()) { delay(250); waited += 250 }
    }
    if (stopRequested()) return
    val store = FolderStore()
    agentSearchPath(environment("PATH"), try { store.read().searchPath } catch (_: Exception) { null }, homeDirectory())?.let { setEnvironment("PATH", it) }
    DeviceDaemon(client, store, RemoteSessions(), DaemonSessions(), launcher = { id, epoch, team, log ->
        startDetachedProcess(executable, listOf("remote", "host", id, "--accepted-epoch", epoch.toString(), "--team", team), log)
    }, version = BuildInfo.VERSION, report = report, stopRequested = stopRequested, home = homeDirectory()).run()
}

/**
 * The `PATH` the daemon's hosts start their agent with: the one saved by the last `teamcode folders add` (the member's
 * terminal), the service's own, then the usual install directories that exist (the native Claude Code installer,
 * npm, Bun, Volta, Homebrew). A service manager starts the daemon with a minimal `PATH` that rarely finds `claude` or
 * `codex`. Relative entries are dropped. Null when nothing changes.
 */
internal fun agentSearchPath(current: String?, saved: String?, home: Path?, exists: (String) -> Boolean = { FileSystem.SYSTEM.metadataOrNull(it.toPath())?.isDirectory == true }): String? {
    val usual = listOfNotNull(
        home?.let { "$it/.local/bin" }, home?.let { "$it/.claude/local" }, home?.let { "$it/.npm-global/bin" },
        home?.let { "$it/.bun/bin" }, home?.let { "$it/.volta/bin" }, "/opt/homebrew/bin", "/usr/local/bin",
    ).filter(exists)
    val entries = (saved.orEmpty().split(':') + current.orEmpty().split(':') + usual).filter { it.startsWith("/") }.distinct()
    val joined = entries.joinToString(":")
    return joined.takeIf { it.isNotEmpty() && it != current }
}
