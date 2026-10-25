package me.nathanfallet.teamcode.cli.remote

import com.github.ajalt.clikt.command.SuspendingCliktCommand
import com.github.ajalt.clikt.command.SuspendingNoOpCliktCommand
import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.default
import com.github.ajalt.clikt.parameters.options.*
import com.github.ajalt.clikt.parameters.types.choice
import com.github.ajalt.clikt.parameters.types.int
import com.github.ajalt.clikt.parameters.types.long
import me.nathanfallet.teamcode.cli.daemon.homeDirectory
import me.nathanfallet.teamcode.cli.daemon.refuseFolder
import me.nathanfallet.teamcode.cli.daemon.ClaudeTranscripts
import kotlin.uuid.Uuid
import kotlinx.serialization.Serializable
import me.nathanfallet.teamcode.api.Serialization
import me.nathanfallet.teamcode.api.models.*
import me.nathanfallet.teamcode.cli.CliUserException
import me.nathanfallet.teamcode.cli.SystemExit
import me.nathanfallet.teamcode.cli.cloud.parseLabels
import me.nathanfallet.teamcode.cli.cloud.agent.FileAgentStateStore
import me.nathanfallet.teamcode.cli.config.CliPrivateFiles
import me.nathanfallet.teamcode.cli.config.configPath
import me.nathanfallet.teamcode.cli.config.configStore
import me.nathanfallet.teamcode.config.ConfigStore
import me.nathanfallet.teamcode.config.configSibling
import me.nathanfallet.teamcode.cli.loadConfig
import me.nathanfallet.teamcode.cli.platform.environment
import me.nathanfallet.teamcode.cli.platform.servicePlatform
import me.nathanfallet.teamcode.cli.sdk
import me.nathanfallet.teamcode.client.TeamCodeException
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath

/**
 * `teamcode remote`: sessions that run on this device, in a directory of yours, and that the team's clients (the
 * dashboard, the mobile and desktop apps, `teamcode cloud attach`) follow and drive like cloud sessions. The agent runs
 * here through the same runner as a cloud pod, with your own login; only the directory's name reaches the server.
 */
class Remote : SuspendingNoOpCliktCommand(name = "remote") {
    override fun help(context: Context) = "Run sessions on this device that your team's clients follow and drive like cloud sessions."
    init { subcommands(RemoteStart(), RemoteList(), RemoteResume(), RemoteStop(), RemoteHost(), RemoteResumable()) }
}

internal const val REMOTE_CAPABILITY = "cloud.sessions.remote.v1"

private const val DETACH_HELP = "Host the session in the background and return: it keeps running after the terminal closes. " +
    "Its log (no prompt, answer or credential) is beside config.json, in remote/."
private const val ATTACH_HELP = "Host the session in the background and attach this terminal to it (as teamcode attach); /detach leaves it running."

private class RemoteStart : SuspendingCliktCommand(name = "start") {
    override fun help(context: Context) = "Start a session in DIRECTORY (the current one by default) and host it on this device. When this device's daemon runs " +
        "(teamcode service), it hosts the session and DIRECTORY becomes one of its folders (teamcode folders); this terminal attaches to the session " +
        "unless --detach. Otherwise the command hosts it: in the foreground (the default), Ctrl-C pauses it: the agent finishes its turn, " +
        "the host checkpoints and exits; teamcode remote resume SESSION resumes it."
    private val directory by argument("DIRECTORY", help = "The directory the agent works in.").default(".")
    private val agent by option("--agent", help = "The agent to run.").choice("claude", "codex").default("claude")
    private val name by option("--name", help = "The session's name; the directory's name by default.")
    private val model by option("--model")
    private val effort by option("--effort")
    private val labels by option("--label", help = "key=value; repeatable.").multiple()
    private val message by option("--message", help = "First message for the agent.")
    private val bypass by option("--bypass-permissions", help = "Let the agent act on your files without asking: Claude Code skips its permission " +
        "checks and Codex leaves its sandbox. By default Claude Code's requests come to your team's clients and Codex only writes inside DIRECTORY.").flag()
    private val detach by option("--detach", help = DETACH_HELP).flag()
    private val attach by option("--attach", help = ATTACH_HELP).flag()
    private val json by option("--json", help = "Print the created session as JSON on standard output (messages go to standard error).").flag()
    private val resume by option("--resume", help = "Continue one of DIRECTORY's Claude Code conversations on this device (an id from teamcode remote resumable), " +
        "through this device's daemon: the session starts from a copy (--fork-session) and the conversation stays as it was.")

    override suspend fun run() {
        if (detach && attach) throw UsageError("Use --detach or --attach, not both.")
        resume?.let {
            if (agent != "claude") throw UsageError("--resume continues a Claude Code conversation: use it with --agent claude.")
            if (!ResumableSessions.ID.matches(it)) throw UsageError("--resume takes a Claude Code session id (a lowercase UUID).")
        }
        val path = remoteDirectory(directory)
        val config = loadConfig()
        val code = sdk(config).use { client ->
            val features = requireRemote(client)
            if (bypass) echo("Warning: the agent acts on your files without asking for permission.", err = true)
            val request = CreateCloudSessionRequest(
                name = name ?: path.name, kind = CloudSessionKinds.REMOTE, agent = CloudAgentRequest(model, effort, agent), labels = parseLabels(labels),
                directoryName = path.name, permissionMode = if (bypass) RemotePermissionModes.BYPASS else RemotePermissionModes.ASK, initialMessage = message,
            )
            val windows = servicePlatform().os == "windows"
            // Continuing a conversation goes through the daemon only: no host of this command forks it.
            val resumeDevice = resume?.let {
                when (val target = localResumeTarget(client, features, windows)) {
                    is LocalResume.Ready -> target.deviceId
                    is LocalResume.Unavailable -> throw CliUserException(localResumeRefusal(target.reason))
                }
            }
            (resumeDevice ?: daemonDevice(client, features, windows))?.let { device ->
                val report: (String) -> Unit = { echo(it, err = true) }
                val session = startThroughDaemon(client, device, path, request, agent, homeDirectory(), windows, environment("PATH"), report, resumeSessionId = resume)
                if (json) echo(Serialization.json.encodeToString(session))
                echo("Remote session ${session.id} on this device, in ${path}; this device's daemon hosts it.", err = true)
                awaitDaemonHost(client, session.id, session.currentEpoch, report)
                if (detach) {
                    report("Follow it with teamcode attach ${session.id}; teamcode remote stop ${session.id} pauses it.")
                    return@use 0
                }
                attachSession(client, session.id, { echo(it) }, report)
            }
            val session = client.createCloudSession(request, idempotencyKey = Uuid.random().toString())
            val local = RemoteSessionRecord(session.id, path.toString(), agent)
            RemoteSessions().save(local)
            if (json) echo(Serialization.json.encodeToString(session))
            echo("Remote session ${session.id} on this device, in ${path}.", err = true)
            host(client, local, session, detach, attach, { echo(it) }) { echo(it, err = true) }
        }
        if (code != 0) SystemExit.exit(code)
    }
}

private class RemoteResume : SuspendingCliktCommand(name = "resume") {
    override fun help(context: Context) = "Resume a remote session of this device (paused, woken by a message, or left by a lost connection) and host it here. " +
        "A remote session resumes only on the device it was started on."
    private val session by argument("SESSION")
    private val detach by option("--detach", help = DETACH_HELP).flag()
    private val attach by option("--attach", help = ATTACH_HELP).flag()

    override suspend fun run() {
        if (detach && attach) throw UsageError("Use --detach or --attach, not both.")
        val local = RemoteSessions().load(session) ?: throw notRecorded(session)
        val code = sdk(loadConfig()).use { client ->
            val features = requireRemote(client)
            val current = client.cloudSession(session)
            // A session started through the daemon is the daemon's to host: resuming puts it back in its queue.
            if (current.host?.folderId != null && daemonDevice(client, features, servicePlatform().os == "windows") != null &&
                !(current.host?.online == true && current.desiredState == "running")) {
                val report: (String) -> Unit = { echo(it, err = true) }
                if (current.desiredState != "running") client.resumeCloudSession(session)
                awaitDaemonHost(client, session, current.currentEpoch, report)
                return@use if (attach) attachSession(client, session, { echo(it) }, report) else 0
            }
            if (current.host?.online == true && current.desiredState == "running") {
                echo("Session $session is already hosted on this device.", err = true)
                if (attach) attachSession(client, session, { echo(it) }) { echo(it, err = true) } else 0
            } else host(client, local, current, detach, attach, { echo(it) }) { echo(it, err = true) }
        }
        if (code != 0) SystemExit.exit(code)
    }
}

/**
 * `teamcode remote resumable`: the Claude Code conversations of DIRECTORY on this device that `remote start --resume` can
 * continue, read here with the daemon's code; nothing of them reaches the server. `--json` is what the desktop app reads.
 */
private class RemoteResumable : SuspendingCliktCommand(name = "resumable") {
    override fun help(context: Context) = "List the Claude Code conversations of DIRECTORY (the current one by default) on this device, the most recent " +
        "first, that teamcode remote start --resume can continue through this device's daemon: id, last activity, messages, title."
    private val directory by argument("DIRECTORY", help = "The directory the conversations started in.").default(".")
    private val limit by option("--limit", help = "At most this many (1 to ${ResumableSessions.MAX_LIMIT}).").int().default(ResumableSessions.DEFAULT_LIMIT)
    private val before by option("--before", help = "Only those last active before this time (milliseconds), the nextBefore of the previous page.").long()
    private val json by option("--json", help = "Print the page as JSON on standard output, with the reason when this device cannot continue one.").flag()

    override suspend fun run() {
        if (limit !in 1..ResumableSessions.MAX_LIMIT) throw UsageError("--limit is between 1 and ${ResumableSessions.MAX_LIMIT}.")
        if (before?.let { it < 0 } == true) throw UsageError("--before is a time in milliseconds.")
        val path = remoteDirectory(directory)
        val windows = servicePlatform().os == "windows"
        refuseFolder(path, homeDirectory(), windows)
        val view = sdk(loadConfig()).use { client ->
            localResumables(ClaudeTranscripts.local(), localResumeTarget(client, requireRemote(client), windows), path, limit, before)
        }
        if (json) { echo(Serialization.json.encodeToString(view)); return }
        view.unavailable?.let { throw CliUserException(localResumeRefusal(it)) }
        view.sessions.forEach { echo(listOf(it.id, it.lastActiveAt.toString(), "${it.turns} msg", it.title).joinToString("\t")) }
        if (view.sessions.isEmpty()) echo("No Claude Code conversation of $path on this device.", err = true)
        view.nextBefore?.let { echo("Older ones: --before $it", err = true) }
    }
}

/** `teamcode remote host`: the host itself, in the foreground; `--detach`, `--attach` and the device's daemon start it in the background. */
private class RemoteHost : SuspendingCliktCommand(name = "host") {
    override fun help(context: Context) = "Host a remote session of this device in the foreground until it pauses (what teamcode remote resume --detach runs in the background)."
    private val session by argument("SESSION")
    private val acceptedEpoch by option("--accepted-epoch", hidden = true, help = "The epoch this device's daemon opened for the session.").int()
    private val team by option("--team", hidden = true, help = "The session's team, with --accepted-epoch.")

    override suspend fun run() {
        if ((acceptedEpoch == null) != (team == null)) throw UsageError("Use --accepted-epoch and --team together.")
        val local = RemoteSessions().load(session) ?: throw notRecorded(session)
        val accepted = acceptedEpoch?.let { epoch -> AcceptedHost(requireNotNull(team).also { if (!TEAM_ID.matches(it)) throw UsageError("Invalid --team.") }, epoch) }
        val code = sdk(loadConfig()).use { client ->
            requireRemote(client)
            hostRemoteSession(client, local, report = { echo(it, err = true) }, accepted = accepted)
        }
        if (code != 0) SystemExit.exit(code)
    }
}

private class RemoteStop : SuspendingCliktCommand(name = "stop") {
    override fun help(context: Context) = "Pause a remote session from any of your devices: the agent finishes its turn and the background work it still runs " +
        "(subagents, background commands; up to 10 minutes, then it is stopped), then its host checkpoints and exits. " +
        "Nothing in its directory is touched; teamcode remote resume SESSION continues it on its device."
    private val session by argument("SESSION")
    private val force by option("--force", help = "Do not wait for the agent's turn or background work to finish.").flag()

    override suspend fun run() {
        val paused = sdk(loadConfig()).use { client ->
            requireRemote(client)
            val current = client.cloudSession(session)
            if (current.kind != CloudSessionKinds.REMOTE) throw CliUserException("Session $session is a cloud session; use teamcode cloud pause.")
            client.pauseCloudSession(session, force)
        }
        echo("Session $session: ${paused.state}" + (paused.phase?.let { " ($it)" } ?: "") + ", pausing" +
            if (paused.host?.online == true) (if (force) "; its host stops the agent and its background work now, checkpoints and exits."
                else "; the agent finishes its turn and its background work (up to 10 minutes), then its host checkpoints and exits.") else ".")
    }
}

private class RemoteList : SuspendingCliktCommand(name = "list") {
    override fun help(context: Context) = "List the remote sessions this device hosts (--all-devices: on every device of yours), newest first: id, state, name, agent, host and directory."
    private val allDevices by option("--all-devices", help = "Also list the remote sessions of your other devices.").flag()
    private val json by option("--json").flag()

    override suspend fun run() {
        val sessions = RemoteSessions()
        sdk(loadConfig()).use { client ->
            requireRemote(client)
            val device = client.devices().firstOrNull { it.current }?.id
            val listed = mutableListOf<CloudSessionView>()
            var before: String? = null
            do {
                val page = client.cloudSessions(states = emptyList(), before = before, limit = 50, kinds = listOf(CloudSessionKinds.REMOTE))
                listed += page.sessions
                before = page.nextBefore
            } while (before != null && listed.size < MAX_LISTED)
            // A terminated session never runs again: its record (path, conversation id, log) is dropped.
            sessions.prune(listed.filter { it.state == "terminated" }.map { it.id }.toSet())
            val shown = listed.filter { it.state != "terminated" && (allDevices || (device != null && it.host?.deviceId == device)) }
            if (json) echo(Serialization.json.encodeToString(shown))
            else shown.forEach { echo(remoteLine(it, sessions.load(it.id), showDevice = allDevices)) }
            if (!json && shown.isEmpty()) echo(if (allDevices) "No remote session." else "No remote session on this device; start one with teamcode remote start.", err = true)
            if (before != null) echo("Showing the newest $MAX_LISTED remote sessions.", err = true)
        }
    }
    private companion object { const val MAX_LISTED = 500 }
}

/** One `teamcode remote list` row; the directory's full path comes from this device's record, never from the server. */
internal fun remoteLine(session: CloudSessionView, record: RemoteSessionRecord?, showDevice: Boolean): String = listOfNotNull(
    session.id, session.state + (session.phase?.let { " ($it)" } ?: ""), session.name, session.agent.kind,
    if (session.host?.online == true) "online" else "offline",
    session.host?.deviceName?.takeIf { showDevice } ?: if (showDevice) "removed device" else null,
    record?.directory ?: session.host?.directoryName ?: "-",
).joinToString("\t")

private fun notRecorded(session: String) =
    CliUserException("This device has no record of session $session: a remote session runs only on the device it was started on.")

/**
 * Hosts [record] the way the command asked: in the foreground until the session pauses (the exit code of the host),
 * or in the background, then attached to this terminal with [attach].
 */
private suspend fun host(client: me.nathanfallet.teamcode.client.TeamCodeClient, record: RemoteSessionRecord, session: CloudSessionView,
    detach: Boolean, attach: Boolean, out: (String) -> Unit, report: (String) -> Unit): Int {
    if (!detach && !attach) {
        report("Follow it from the TeamCode app or with teamcode attach ${record.sessionId}. Ctrl-C pauses it; teamcode remote resume ${record.sessionId} resumes it.")
        return hostRemoteSession(client, record, report = report)
    }
    startBackgroundHost(client, record, session.currentEpoch, report)
    if (!attach) {
        report("Hosted in the background. Follow it with teamcode attach ${record.sessionId}; teamcode remote stop ${record.sessionId} pauses it.")
        return 0
    }
    attachSession(client, record.sessionId, out, report)
}

/** The server's features, once it runs remote sessions. */
private suspend fun requireRemote(client: me.nathanfallet.teamcode.client.TeamCodeClient): List<String> {
    val features = client.capabilities().features
    if (REMOTE_CAPABILITY !in features)
        throw CliktError("This server does not run remote sessions (cloud.sessions.remote.v1): update it, or ask an administrator to turn cloud sessions on.")
    return features
}

private val TEAM_ID = Regex("[a-zA-Z0-9_-]{1,64}")

/**
 * What this device keeps about one of its remote sessions, never sent to the server: the directory's full path (which
 * holds the user name) and the agent. The agent's conversation id is kept beside it, so a resume continues the conversation.
 */
@Serializable
internal data class RemoteSessionRecord(val sessionId: String, val directory: String, val agent: String)

/**
 * `remote/<session>.json` beside `config.json`, written privately and under `config.lock` (shared with login, logout and
 * the desktop app), with the agent's conversation id (`<session>.conversation.json`) and the background host's log
 * (`<session>.log`).
 */
internal class RemoteSessions(
    private val root: Path = configSibling(configPath(), "remote").toPath(),
    private val lock: ConfigStore = configStore(),
) {
    fun save(record: RemoteSessionRecord) {
        require(SESSION_ID.matches(record.sessionId))
        lock.locked { CliPrivateFiles.write((root / "${record.sessionId}.json").toString(), Serialization.json.encodeToString(record)) }
    }
    fun load(sessionId: String): RemoteSessionRecord? {
        if (!SESSION_ID.matches(sessionId)) return null
        val file = root / "$sessionId.json"
        return try {
            if (!FileSystem.SYSTEM.exists(file)) null
            else Serialization.json.decodeFromString<RemoteSessionRecord>(FileSystem.SYSTEM.read(file) { readUtf8() }).takeIf { it.sessionId == sessionId }
        } catch (_: Exception) { null }
    }
    fun conversation(sessionId: String) = FileAgentStateStore((root / "$sessionId.conversation.json").toString())

    /** The background host's log, created privately when missing and emptied once it passes [MAX_LOG_BYTES]. */
    fun log(sessionId: String): String {
        require(SESSION_ID.matches(sessionId))
        val file = root / "$sessionId.log"
        val size = FileSystem.SYSTEM.metadataOrNull(file)?.size
        if (size == null || size > MAX_LOG_BYTES) lock.locked { CliPrivateFiles.write(file.toString(), "") }
        return file.toString()
    }

    /** Removes the records of [ended] sessions (with their conversation id and log); records of other sessions stay. */
    fun prune(ended: Set<String>) {
        val stale = ended.filter { SESSION_ID.matches(it) && FileSystem.SYSTEM.exists(root / "$it.json") }
        if (stale.isEmpty()) return
        lock.locked {
            for (id in stale) for (suffix in listOf(".json", ".conversation.json", ".log")) FileSystem.SYSTEM.delete(root / "$id$suffix", mustExist = false)
        }
    }

    companion object {
        private val SESSION_ID = Regex("[a-zA-Z0-9-]{1,36}")
        const val MAX_LOG_BYTES = 1_048_576L
    }
}

/**
 * The directory a remote session works in: it must exist, and it may be neither the file system's root, nor the home
 * directory itself, nor a system directory. Only its last segment is ever sent to the server.
 */
internal fun remoteDirectory(value: String, home: String? = environment("HOME") ?: environment("USERPROFILE"), windows: Boolean = servicePlatform().os == "windows"): Path {
    val path = try { FileSystem.SYSTEM.canonicalize(value.toPath()) } catch (_: Exception) { throw CliUserException("$value does not exist.") }
    if (FileSystem.SYSTEM.metadataOrNull(path)?.isDirectory != true) throw CliUserException("$path is not a directory.")
    refuseDirectory(path, home?.let { runCatching { FileSystem.SYSTEM.canonicalize(it.toPath()) }.getOrNull() }, windows)
    return path
}

internal fun refuseDirectory(path: Path, home: Path?, windows: Boolean) {
    fun same(a: String, b: String) = if (windows) a.equals(b, ignoreCase = true) else a == b
    val text = path.toString().trimEnd('/', '\\')
    if (path.parent == null || path.name.isEmpty() || (home != null && same(text, home.toString().trimEnd('/', '\\'))))
        throw CliUserException("Choose a project directory: a session may not run in $path itself.")
    val system = if (windows) WINDOWS_SYSTEM else POSIX_SYSTEM
    if (system.any { root -> same(text, root) || (if (windows) text.lowercase().startsWith(root.lowercase() + "\\") else text.startsWith("$root/")) })
        throw CliUserException("Choose a project directory: $path is a system directory.")
}

private val POSIX_SYSTEM = listOf("/bin", "/boot", "/dev", "/etc", "/lib", "/lib64", "/proc", "/sbin", "/sys", "/usr", "/System", "/Library", "/private/etc", "/private/var/db")
private val WINDOWS_SYSTEM = listOf("C:\\Windows", "C:\\Program Files", "C:\\Program Files (x86)", "C:\\ProgramData")

/** A refusal the host prints as is (its code and the server's message), never a request or response body. */
internal fun TeamCodeException.describe() = (code?.let { "$it: " } ?: "HTTP $statusCode: ") + (message ?: "")
