package me.nathanfallet.teamcode.cli.daemon

import com.github.ajalt.clikt.command.SuspendingCliktCommand
import com.github.ajalt.clikt.command.SuspendingNoOpCliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import me.nathanfallet.teamcode.api.Serialization
import me.nathanfallet.teamcode.api.models.DeviceFolderView
import me.nathanfallet.teamcode.cli.CliUserException
import me.nathanfallet.teamcode.cli.config.CliPrivateFiles
import me.nathanfallet.teamcode.cli.config.configPath
import me.nathanfallet.teamcode.cli.config.configStore
import me.nathanfallet.teamcode.cli.platform.environment
import me.nathanfallet.teamcode.cli.remote.describe
import me.nathanfallet.teamcode.cli.remote.refuseDirectory
import me.nathanfallet.teamcode.cli.sdk
import me.nathanfallet.teamcode.cli.service.randomHex
import me.nathanfallet.teamcode.client.TeamCodeClient
import me.nathanfallet.teamcode.client.TeamCodeException
import me.nathanfallet.teamcode.config.ConfigStore
import me.nathanfallet.teamcode.config.configSibling
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath

/** `teamcode folders`: the folders of this device where the member's other clients may start a remote session. */
class Folders : SuspendingNoOpCliktCommand(name = "folders") {
    override fun help(context: Context) = "Choose the folders of this device where your other devices and apps may start remote sessions, hosted by this device's daemon."
    init { subcommands(FoldersAdd(), FoldersList(), FoldersRemove()) }
}

private class FoldersAdd : SuspendingCliktCommand(name = "add") {
    override fun help(context: Context) = "Allow remote sessions in PATH, started from any of your devices. Your home directory as a whole, " +
        "a directory that contains it and system directories are refused; a symbolic link is resolved first."
    private val path by argument("PATH")
    override suspend fun run() {
        val store = FolderStore()
        val (folder, added) = store.add(folderPath(path), environment("PATH"))
        echo(if (added) "Added ${folder.path} (${folder.id})." else "${folder.path} is already one of this device's folders (${folder.id}).")
        publishFromCommand(store) { echo(it, err = true) }
    }
}

private class FoldersList : SuspendingCliktCommand(name = "list") {
    override fun help(context: Context) = "List this device's folders: id and path (missing when it no longer exists)."
    private val json by option("--json").flag()
    override suspend fun run() {
        val folders = FolderStore().read().folders
        if (json) { echo(Serialization.json.encodeToString(folders)); return }
        if (folders.isEmpty()) echo("No folder yet; add one with teamcode folders add PATH.", err = true)
        for (folder in folders) {
            val missing = FileSystem.SYSTEM.metadataOrNull(folder.path.toPath())?.isDirectory != true
            echo(listOfNotNull(folder.id, folder.path, if (missing) "missing" else null).joinToString("\t"))
        }
    }
}

private class FoldersRemove : SuspendingCliktCommand(name = "remove") {
    override fun help(context: Context) = "Stop offering a folder (by id or path). Sessions already running there are not stopped."
    private val folder by argument("FOLDER", help = "The folder's id or path.")
    override suspend fun run() {
        val store = FolderStore()
        val removed = store.remove(folder) ?: throw CliUserException("$folder is not one of this device's folders (teamcode folders list).")
        echo("Removed ${removed.path} (${removed.id}).")
        publishFromCommand(store) { echo(it, err = true) }
    }
}

/** `folders add` and `remove` publish at once when they can; the daemon publishes any change anyway. */
private suspend fun publishFromCommand(store: FolderStore, report: (String) -> Unit) {
    val config = try { configStore().load() } catch (_: CliUserException) { null } ?: run {
        report("Not signed in: this device's daemon publishes its folders once you sign in with teamcode login."); return
    }
    try {
        sdk(config).use { client ->
            if (DAEMON_CAPABILITY !in client.capabilities().features) { report("This server does not start sessions on devices yet ($DAEMON_CAPABILITY)."); return }
            publishFolders(client, store.read())
        }
    } catch (error: CancellationException) { throw error }
    catch (error: TeamCodeException) { report("The folders could not be published now (${error.describe()}); this device's daemon publishes them when it runs.") }
    catch (_: Exception) { report("The folders could not be published now; this device's daemon publishes them when it runs.") }
}

internal const val DAEMON_CAPABILITY = "cloud.sessions.remote.daemon.v1"

/** Replaces this device's published folders with [file]'s: the folders' ids, names and labels, never their paths. */
internal suspend fun publishFolders(client: TeamCodeClient, file: FoldersFile, home: Path? = homeDirectory()) {
    client.publishDeviceFolders(published(file, home))
}

internal fun published(file: FoldersFile, home: Path?): List<DeviceFolderView> =
    file.folders.map { DeviceFolderView(it.id, it.path.toPath().name, folderLabel(it.path, home)) }

/** How the owner recognizes a folder in the apps: `~/git/teamcode` under the home directory, the path otherwise. */
internal fun folderLabel(path: String, home: Path?): String {
    val root = home?.toString()?.trimEnd('/', '\\') ?: return path
    return if (path.startsWith("$root/") || path.startsWith("$root\\")) "~" + path.substring(root.length) else path
}

/** A folder this device offers: [id] is the key the server and the apps know it by, [path] stays on this device. */
@Serializable
internal data class LocalFolder(val id: String, val path: String)

/**
 * `folders.json` beside `config.json`. [searchPath] is the `PATH` of the terminal that last added a folder: the daemon
 * runs under the OS service manager, whose `PATH` rarely holds the agents' directories (`~/.local/bin`, nvm, Homebrew).
 */
@Serializable
internal data class FoldersFile(val folders: List<LocalFolder> = emptyList(), val searchPath: String? = null)

/** The folders file, written privately and under `config.lock` like the remote sessions' records. */
internal class FolderStore(
    val file: Path = configSibling(configPath(), "folders.json").toPath(),
    private val lock: ConfigStore = configStore(),
    private val windows: Boolean = isWindows(),
) {
    /** Windows accepts both separators: `C:/git/app` and `C:\git\app` are one folder, with one id. */
    private fun key(path: String) = if (windows) path.replace('/', '\\').trimEnd('\\') else path

    fun read(): FoldersFile {
        val metadata = FileSystem.SYSTEM.metadataOrNull(file) ?: return FoldersFile()
        if (metadata.symlinkTarget != null || !metadata.isRegularFile || (metadata.size ?: 0) > MAX_BYTES)
            throw CliUserException("$file must be a small regular file, not a symbolic link.")
        return try { Serialization.json.decodeFromString<FoldersFile>(FileSystem.SYSTEM.read(file) { readUtf8() }) }
            catch (_: IllegalArgumentException) { throw CliUserException("Cannot read $file; fix or remove it, then add your folders again.") }
    }

    /** Adds [path] (already checked by [folderPath]) with a new id, or finds it; true when it was added. */
    fun add(path: Path, searchPath: String?): Pair<LocalFolder, Boolean> = lock.locked {
        val current = read()
        val existing = current.folders.firstOrNull { key(it.path) == key(path.toString()) }
        val folder = existing ?: LocalFolder(randomHex(8), path.toString())
        if (existing == null && current.folders.size >= MAX_FOLDERS) throw CliUserException("A device offers at most $MAX_FOLDERS folders; remove one first.")
        write(FoldersFile(if (existing == null) current.folders + folder else current.folders, searchPath?.takeIf { it.isNotBlank() } ?: current.searchPath))
        folder to (existing == null)
    }

    /** Removes the folder whose id or path is [value] (a path is resolved like `add` does, when it still exists). */
    fun remove(value: String): LocalFolder? = lock.locked {
        val current = read()
        val canonical = try { FileSystem.SYSTEM.canonicalize(value.toPath()).toString() } catch (_: Exception) { value }
        val folder = current.folders.firstOrNull { it.id == value || key(it.path) == key(value) || key(it.path) == key(canonical) } ?: return@locked null
        write(current.copy(folders = current.folders - folder))
        folder
    }

    private fun write(content: FoldersFile) = CliPrivateFiles.write(file.toString(), Serialization.json.encodeToString(content))

    companion object {
        const val MAX_FOLDERS = 64
        private const val MAX_BYTES = 262_144L
    }
}

/**
 * The folder [value] names, resolved (`..` and symbolic links): it must be a directory, and neither the file system's
 * root, nor the home directory, nor a directory that contains the home directory, nor a system directory.
 */
internal fun folderPath(value: String, home: Path? = homeDirectory(), windows: Boolean = isWindows()): Path {
    val path = try { FileSystem.SYSTEM.canonicalize(value.toPath()) } catch (_: Exception) { throw CliUserException("$value does not exist.") }
    if (FileSystem.SYSTEM.metadataOrNull(path)?.isDirectory != true) throw CliUserException("$path is not a directory.")
    refuseFolder(path, home, windows)
    return path
}

internal fun refuseFolder(path: Path, home: Path?, windows: Boolean) {
    refuseDirectory(path, home, windows)
    if (home != null && contains(path, home, windows)) throw CliUserException("Choose a project directory: $path contains your home directory.")
}

/** Whether [inner] lies strictly inside [outer]. */
private fun contains(outer: Path, inner: Path, windows: Boolean): Boolean {
    val root = outer.toString().trimEnd('/', '\\')
    val text = inner.toString()
    fun starts(prefix: String) = if (windows) text.lowercase().startsWith(prefix.lowercase()) else text.startsWith(prefix)
    return starts("$root/") || starts("$root\\")
}

/** Why the daemon refuses an order, sent to the server as the refusal's reason code. */
internal object RefusalReasons {
    /** The folder id is not in `folders.json` (removed meanwhile, or published by another configuration). */
    const val FOLDER_NOT_CONFIGURED = "folder_not_configured"
    /** The folder no longer exists or is no longer a directory. */
    const val FOLDER_MISSING = "folder_missing"
    /** The folder's path now resolves elsewhere: it became, or goes through, a symbolic link. */
    const val FOLDER_MOVED = "folder_moved"
    /** The folder is the home directory, contains it, or is a system directory (a hand-edited `folders.json`). */
    const val FOLDER_REFUSED = "folder_refused"
    /** The order's directory name is not the folder's. */
    const val FOLDER_MISMATCH = "folder_mismatch"
    /** An agent this version cannot run. */
    const val AGENT_UNSUPPORTED = "agent_unsupported"
    /** The host kept stopping before it was ready. */
    const val HOST_FAILED = "host_failed"
    /** The conversation to continue is not one of the folder's Claude Code conversations on this device (any more). */
    const val RESUME_NOT_FOUND = "resume_not_found"
}

internal sealed interface OrderCheck {
    data class Accepted(val directory: String) : OrderCheck
    data class Refused(val reason: String) : OrderCheck
}

/**
 * The daemon's own check of an order, whatever the server already checked: the folder must still be in [file], resolve
 * to the very path it was added with (a folder replaced by a symbolic link pointing elsewhere is refused), still be a
 * directory the rules of [folderPath] accept, and carry the order's directory name.
 */
internal fun checkOrder(folderId: String, directoryName: String, agent: String, file: FoldersFile, home: Path?, windows: Boolean): OrderCheck {
    if (agent !in SUPPORTED_AGENTS) return OrderCheck.Refused(RefusalReasons.AGENT_UNSUPPORTED)
    val folder = file.folders.firstOrNull { it.id == folderId } ?: return OrderCheck.Refused(RefusalReasons.FOLDER_NOT_CONFIGURED)
    val stored = folder.path.toPath()
    val resolved = try { FileSystem.SYSTEM.canonicalize(stored) } catch (_: Exception) { return OrderCheck.Refused(RefusalReasons.FOLDER_MISSING) }
    if (FileSystem.SYSTEM.metadataOrNull(resolved)?.isDirectory != true) return OrderCheck.Refused(RefusalReasons.FOLDER_MISSING)
    if (resolved.toString() != stored.toString()) return OrderCheck.Refused(RefusalReasons.FOLDER_MOVED)
    try { refuseFolder(resolved, home, windows) } catch (_: CliUserException) { return OrderCheck.Refused(RefusalReasons.FOLDER_REFUSED) }
    if (resolved.name != directoryName) return OrderCheck.Refused(RefusalReasons.FOLDER_MISMATCH)
    return OrderCheck.Accepted(resolved.toString())
}

private val SUPPORTED_AGENTS = setOf("claude", "codex")

internal fun homeDirectory(): Path? = (environment("HOME") ?: environment("USERPROFILE"))?.let { runCatching { FileSystem.SYSTEM.canonicalize(it.toPath()) }.getOrNull() }

internal fun isWindows(): Boolean = Path.DIRECTORY_SEPARATOR == "\\"
