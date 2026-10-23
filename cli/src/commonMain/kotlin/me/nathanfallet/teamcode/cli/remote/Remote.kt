package me.nathanfallet.teamcode.cli.remote

import com.github.ajalt.clikt.command.SuspendingCliktCommand
import com.github.ajalt.clikt.command.SuspendingNoOpCliktCommand
import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.default
import com.github.ajalt.clikt.parameters.options.*
import com.github.ajalt.clikt.parameters.types.choice
import kotlin.uuid.Uuid
import kotlinx.serialization.Serializable
import me.nathanfallet.teamcode.api.Serialization
import me.nathanfallet.teamcode.api.models.*
import me.nathanfallet.teamcode.cli.CliUserException
import me.nathanfallet.teamcode.cli.SystemExit
import me.nathanfallet.teamcode.cli.cloud.parseLabels
import me.nathanfallet.teamcode.cli.cloud.agent.FileAgentStateStore
import me.nathanfallet.teamcode.cli.config.configPath
import me.nathanfallet.teamcode.cli.loadConfig
import me.nathanfallet.teamcode.cli.platform.environment
import me.nathanfallet.teamcode.cli.platform.servicePlatform
import me.nathanfallet.teamcode.cli.platform.writePrivateFile
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
    init { subcommands(RemoteStart(), RemoteHost()) }
}

internal const val REMOTE_CAPABILITY = "cloud.sessions.remote.v1"

private class RemoteStart : SuspendingCliktCommand(name = "start") {
    override fun help(context: Context) = "Start a session in DIRECTORY (the current one by default) and host it here until it pauses. " +
        "Ctrl-C pauses it: the agent finishes its turn, the host checkpoints and exits; teamcode remote host SESSION resumes it."
    private val directory by argument("DIRECTORY", help = "The directory the agent works in.").default(".")
    private val agent by option("--agent", help = "The agent to run.").choice("claude", "codex").default("claude")
    private val name by option("--name", help = "The session's name; the directory's name by default.")
    private val model by option("--model")
    private val effort by option("--effort")
    private val labels by option("--label", help = "key=value; repeatable.").multiple()
    private val message by option("--message", help = "First message for the agent.")
    private val bypass by option("--bypass-permissions", help = "Let the agent act on your files without asking: Claude Code skips its permission " +
        "checks and Codex leaves its sandbox. By default Claude Code's requests come to your team's clients and Codex only writes inside DIRECTORY.").flag()

    override suspend fun run() {
        val path = remoteDirectory(directory)
        val config = loadConfig()
        val code = sdk(config).use { client ->
            requireRemote(client)
            if (bypass) echo("Warning: the agent acts on your files without asking for permission.", err = true)
            val session = client.createCloudSession(CreateCloudSessionRequest(
                name = name ?: path.name, kind = CloudSessionKinds.REMOTE, agent = CloudAgentRequest(model, effort, agent), labels = parseLabels(labels),
                directoryName = path.name, permissionMode = if (bypass) RemotePermissionModes.BYPASS else RemotePermissionModes.ASK, initialMessage = message,
            ), idempotencyKey = Uuid.random().toString())
            val local = RemoteSessionRecord(session.id, path.toString(), agent)
            RemoteSessions().save(local)
            echo("Remote session ${session.id} on this device, in ${path}.")
            echo("Follow it from the TeamCode app or with teamcode cloud attach ${session.id}. Ctrl-C pauses it; teamcode remote host ${session.id} resumes it.", err = true)
            hostRemoteSession(client, local, report = { echo(it, err = true) })
        }
        if (code != 0) SystemExit.exit(code)
    }
}

private class RemoteHost : SuspendingCliktCommand(name = "host") {
    override fun help(context: Context) = "Host a remote session of this device again (after a pause, a restart or a lost connection), until it pauses."
    private val session by argument("SESSION")

    override suspend fun run() {
        val local = RemoteSessions().load(session)
            ?: throw CliUserException("This device has no record of session $session: a remote session runs only on the device it was started on.")
        val code = sdk(loadConfig()).use { client ->
            requireRemote(client)
            hostRemoteSession(client, local, report = { echo(it, err = true) })
        }
        if (code != 0) SystemExit.exit(code)
    }
}

private suspend fun requireRemote(client: me.nathanfallet.teamcode.client.TeamCodeClient) {
    if (REMOTE_CAPABILITY !in client.capabilities().features)
        throw CliktError("This server does not run remote sessions (cloud.sessions.remote.v1): update it, or ask an administrator to turn cloud sessions on.")
}

/**
 * What this device keeps about one of its remote sessions, never sent to the server: the directory's full path (which
 * holds the user name) and the agent. The agent's conversation id is kept beside it, so a resume continues the conversation.
 */
@Serializable
internal data class RemoteSessionRecord(val sessionId: String, val directory: String, val agent: String)

/** `remote/<session>.json` beside `config.json`, written privately. */
internal class RemoteSessions(private val root: Path = requireNotNull(configPath().toPath().parent) / "remote") {
    fun save(record: RemoteSessionRecord) {
        require(SESSION_ID.matches(record.sessionId))
        FileSystem.SYSTEM.createDirectories(root)
        writePrivateFile((root / "${record.sessionId}.json").toString(), Serialization.json.encodeToString(record))
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

    companion object { private val SESSION_ID = Regex("[a-zA-Z0-9-]{1,36}") }
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
