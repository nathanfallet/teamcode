package me.nathanfallet.teamcode.cli.ui

import me.nathanfallet.teamcode.api.models.ReleaseFormat
import me.nathanfallet.teamcode.api.models.ReleaseVersion
import okio.FileSystem
import okio.IOException
import okio.Path
import okio.Path.Companion.toPath
import okio.buffer
import okio.use

/** An installed TeamCode Desktop: its folder (the `.app` on macOS), its version (null when unreadable) and its launcher. */
internal data class DesktopInstallation(val root: Path, val version: String?, val executable: Path) {
    /** Whether this app can be used with a CLI of [cliVersion]: the same version or a newer one. */
    fun usableWith(cliVersion: String): Boolean = version != null && ReleaseVersion.parse(version) >= ReleaseVersion.parse(cliVersion)
}

/**
 * Where TeamCode Desktop is installed on each system, in the order `teamcode ui` looks:
 * - macOS: `~/Applications/TeamCode.app` (where it installs it), then `/Applications/TeamCode.app` (the disk image);
 * - Windows: `%LOCALAPPDATA%\Programs\TeamCode` (the per-user installer);
 * - Linux: `$XDG_DATA_HOME/teamcode-desktop/current` (`~/.local/share/teamcode-desktop/current`), then the `.deb`'s
 *   `/opt/teamcode`. The folder is not inside the CLI's own `~/.local/share/teamcode`, which the CLI installer owns.
 * Each app carries its version in `teamcode-desktop-version`, in its resources folder (written by the build).
 */
internal class DesktopLocations(private val os: String, private val env: (String) -> String?, private val fs: FileSystem = FileSystem.SYSTEM) {
    private val home: Path? = (env("HOME") ?: env("USERPROFILE"))?.toPath()?.takeIf { it.isAbsolute }

    /** macOS: the folder the app is installed in. */
    val applications: Path? get() = home?.resolve("Applications")

    /** Linux: the folder holding one folder per version and the `current` link. */
    val linuxRoot: Path? get() = (env("XDG_DATA_HOME")?.toPath()?.takeIf { it.isAbsolute } ?: home?.resolve(".local")?.resolve("share"))?.resolve("teamcode-desktop")

    /** Linux: the menu entry `teamcode ui` writes. */
    val linuxDesktopEntry: Path? get() = (env("XDG_DATA_HOME")?.toPath()?.takeIf { it.isAbsolute } ?: home?.resolve(".local")?.resolve("share"))
        ?.resolve("applications")?.resolve("teamcode-desktop.desktop")

    /** Windows: the per-user installer's folder. */
    val windowsRoot: Path? get() = env("LOCALAPPDATA")?.toPath()?.takeIf { it.isAbsolute }?.resolve("Programs")?.resolve("TeamCode")

    fun candidates(): List<Path> = when (os) {
        "macos" -> listOfNotNull(applications?.resolve(APP), "/Applications".toPath().resolve(APP))
        "windows" -> listOfNotNull(windowsRoot)
        else -> listOfNotNull(linuxRoot?.resolve("current"), "/opt/teamcode".toPath())
    }

    fun versionFile(root: Path): Path = when (os) {
        "macos" -> root / "Contents" / "app" / "resources" / VERSION_FILE
        "windows" -> root / "app" / "resources" / VERSION_FILE
        else -> root / "lib" / "app" / "resources" / VERSION_FILE
    }

    fun executable(root: Path): Path = when (os) {
        "macos" -> root / "Contents" / "MacOS" / "TeamCode"
        "windows" -> root / "TeamCode.exe"
        else -> root / "bin" / "TeamCode"
    }

    /** The installation at [root], when its launcher exists. */
    fun at(root: Path): DesktopInstallation? {
        val executable = executable(root)
        if (fs.metadataOrNull(executable)?.isRegularFile != true) return null
        return DesktopInstallation(root, readVersion(versionFile(root)), executable)
    }

    /** Every installed app, in [candidates] order. */
    fun installed(): List<DesktopInstallation> = candidates().mapNotNull(::at)

    private fun readVersion(file: Path): String? = try {
        val metadata = fs.metadataOrNull(file)
        if (metadata?.isRegularFile != true || (metadata.size ?: 0) > 128) null
        else fs.source(file).buffer().use { it.readUtf8() }.trim().takeIf(ReleaseFormat::validVersion)
    } catch (_: IOException) { null }

    companion object {
        const val APP = "TeamCode.app"
        const val VERSION_FILE = "teamcode-desktop-version"
    }
}

/**
 * How the app is started: on macOS through `open` (Launch Services, so the Dock and the menu bar treat it as an app),
 * elsewhere its launcher directly, detached from the terminal. [arguments] go to the app.
 */
internal fun desktopLaunchCommand(os: String, installation: DesktopInstallation, arguments: List<String>): List<String> =
    if (os == "macos") listOf("open", installation.root.toString(), "--args") + arguments
    else listOf(installation.executable.toString()) + arguments

/** The menu entry of the app on Linux, pointing at the `current` link so that an update needs no new entry. */
internal fun linuxDesktopEntry(root: Path): String {
    val current = root / "current"
    require(current.toString().none { it.isISOControl() })
    fun quoted(path: Path) = "\"" + path.toString().replace("\\", "\\\\").replace("\"", "\\\"").replace("`", "\\`").replace("$", "\\$") + "\""
    return listOf(
        "[Desktop Entry]",
        "Type=Application",
        "Name=TeamCode",
        "Comment=Follow and drive your team's agent sessions",
        "Exec=${quoted(current / "bin" / "TeamCode")}",
        "Icon=${current / "lib" / "TeamCode.png"}",
        "Categories=Development;",
        "Terminal=false",
        "",
    ).joinToString("\n")
}
