package me.nathanfallet.teamcode.cli.update

import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath

/** The name users type, and the name of the link or PATH entry the installer adds. */
internal const val COMMAND_NAME = "teamcode"

/** Current user's PATH value from the Windows registry; [expandable] is REG_EXPAND_SZ, otherwise REG_SZ. */
internal data class UserPathValue(val value: String, val expandable: Boolean)

internal sealed interface CommandPathOutcome {
    val lines: List<String>
    /** The command is reachable by name (immediately on POSIX, from new terminals on Windows). */
    data class Ready(override val lines: List<String>) : CommandPathOutcome
    /** A file or link that TeamCode did not create occupies the name; nothing was touched. */
    data class Conflict(val path: Path) : CommandPathOutcome {
        override val lines = listOf(
            "Not linking $COMMAND_NAME: $path already exists and was not created by TeamCode, so it was left untouched.",
            "Remove or rename it and run the install command again, or invoke the launcher by its full path.")
    }
}

/**
 * Makes the stable launcher reachable by name without administrator rights and without touching shell profiles.
 * POSIX: a symbolic link `~/.local/bin/teamcode` to the stable launcher (never to a versioned payload, so updates
 * and rollback stay transparent). Windows: the launcher directory is appended to the current user's PATH in the
 * registry. Re-running changes nothing; a name owned by anything but this link is refused.
 */
internal class CommandPath(
    private val target: String,
    private val launcher: Path,
    private val env: (String) -> String?,
    private val fs: FileSystem = FileSystem.SYSTEM,
    private val readUserPath: () -> UserPathValue? = ::readWindowsUserPath,
    private val writeUserPath: (UserPathValue) -> Unit = ::writeWindowsUserPath,
) {
    fun ensure(): CommandPathOutcome = if (target == "windows-x64") ensureWindows() else ensurePosix()

    private fun value(name: String) = env(name)?.takeIf { it.isNotBlank() }

    private fun ensurePosix(): CommandPathOutcome {
        val home = value("HOME") ?: throw me.nathanfallet.teamcode.cli.CliOperationException("release_home_unavailable")
        val directory = "$home/.local/bin".toPath()
        val link = directory / COMMAND_NAME
        val existing = fs.metadataOrNull(link)
        if (existing == null) {
            fs.createDirectories(directory)
            fs.createSymlink(link, launcher)
        } else {
            val points = existing.symlinkTarget?.let { (if (it.isAbsolute) it else directory / it.toString()).normalized() }
            if (points != launcher.normalized()) return CommandPathOutcome.Conflict(link)
        }
        val lines = mutableListOf("Command: $link -> $launcher")
        val onPath = value("PATH")?.split(':')?.any { it.isNotEmpty() && it.trimEnd('/') == directory.toString() } == true
        if (onPath) lines += "$COMMAND_NAME is on your PATH."
        else {
            val shown = if (directory.toString().startsWith("$home/")) "\$HOME" + directory.toString().removePrefix(home) else directory.toString()
            lines += "$directory is not in your PATH, so '$COMMAND_NAME' will not be found yet. Add this line to your shell profile (for example ~/.zshrc or ~/.bashrc), then open a new terminal:"
            lines += "  export PATH=\"$shown:\$PATH\""
        }
        return CommandPathOutcome.Ready(lines)
    }

    private fun ensureWindows(): CommandPathOutcome {
        val directory = launcher.parent!!.toString()
        val current = readUserPath()
        val entries = current?.value?.split(';')?.filter { it.isNotEmpty() }.orEmpty()
        val lines = mutableListOf("Command: $launcher")
        if (entries.any { sameDirectory(expand(it), directory) }) lines += "$directory is already in your user PATH."
        else {
            writeUserPath(UserPathValue((entries + directory).joinToString(";"), current?.expandable ?: true))
            lines += "Added $directory to your user PATH. Open a new terminal to use '$COMMAND_NAME'."
        }
        return CommandPathOutcome.Ready(lines)
    }

    private fun expand(entry: String) = Regex("%([^%]+)%").replace(entry) { value(it.groupValues[1]) ?: it.value }
    private fun sameDirectory(left: String, right: String) =
        left.replace('/', '\\').trimEnd('\\').equals(right.replace('/', '\\').trimEnd('\\'), ignoreCase = true)
}
