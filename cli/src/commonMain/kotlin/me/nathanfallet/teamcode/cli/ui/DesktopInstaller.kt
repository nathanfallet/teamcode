package me.nathanfallet.teamcode.cli.ui

import kotlinx.coroutines.withTimeoutOrNull
import me.nathanfallet.teamcode.api.models.DesktopReleaseFormat
import me.nathanfallet.teamcode.api.models.ReleaseAsset
import me.nathanfallet.teamcode.api.models.ReleaseFormat
import me.nathanfallet.teamcode.cli.CliOperationException
import me.nathanfallet.teamcode.cli.CliUserException
import me.nathanfallet.teamcode.cli.artifacts.ensurePrivateDirectory
import me.nathanfallet.teamcode.cli.platform.secureRandomBytes
import me.nathanfallet.teamcode.cli.platform.writePrivateFile
import me.nathanfallet.teamcode.cli.update.VerifiedRelease
import me.nathanfallet.teamcode.cli.update.VerifyReleaseManifest
import me.nathanfallet.teamcode.cli.update.readReleaseFile
import me.nathanfallet.teamcode.client.DownloadedReleaseMetadata
import me.nathanfallet.teamcode.client.ReleaseClient
import okio.Buffer
import okio.FileSystem
import okio.IOException
import okio.Path
import okio.Path.Companion.toPath
import okio.buffer
import okio.use
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/** Where the desktop packages come from: the GitHub release of a version, or an offline directory (`--bundle`). */
internal interface DesktopSource {
    suspend fun metadata(version: String): DownloadedReleaseMetadata
    /** Writes exactly [asset]'s size of bytes to [destination], telling [progress] how many so far; their hash is checked by the caller. */
    suspend fun download(version: String, target: String, asset: ReleaseAsset, destination: Path, progress: (Long) -> Unit = {})
}

internal class OnlineDesktopSource(private val client: ReleaseClient, private val fs: FileSystem = FileSystem.SYSTEM) : DesktopSource {
    override suspend fun metadata(version: String) = client.desktopMetadata(version)
    override suspend fun download(version: String, target: String, asset: ReleaseAsset, destination: Path, progress: (Long) -> Unit) {
        var written = 0L
        fs.sink(destination, mustCreate = true).buffer().use { sink ->
            client.desktopPackage(version, target, asset.size) { data, size -> sink.write(data, 0, size); written += size; progress(written) }
        }
    }
}

/** `desktop-manifest.json`, `desktop-manifest.sig` and the package, in one directory (release acceptance, offline use). */
internal class BundleDesktopSource(private val directory: Path, private val fs: FileSystem = FileSystem.SYSTEM) : DesktopSource {
    override suspend fun metadata(version: String) = DownloadedReleaseMetadata(
        readReleaseFile(directory / DesktopReleaseFormat.MANIFEST, ReleaseFormat.MAX_MANIFEST_BYTES, fs),
        readReleaseFile(directory / DesktopReleaseFormat.SIGNATURE, ReleaseFormat.MAX_SIGNATURE_BYTES, fs),
    )
    override suspend fun download(version: String, target: String, asset: ReleaseAsset, destination: Path, progress: (Long) -> Unit) {
        val source = directory / asset.fileName
        val metadata = fs.metadataOrNull(source)
        if (metadata?.isRegularFile != true || metadata.symlinkTarget != null || metadata.size != asset.size) throw CliOperationException("desktop_package_invalid")
        fs.copy(source, destination)
        progress(asset.size)
    }
}

/** A desktop release, authenticated, and the package of this system in it. */
internal class DesktopRelease(val release: VerifiedRelease, val target: String, val asset: ReleaseAsset) {
    val version: String get() = release.manifest.version
}

/** A package of [version] downloaded, checked and unpacked next to where it goes, in [directory]: what [DesktopInstaller.activate] puts in place. */
internal class StagedDesktop(val version: String, val directory: Path)

/**
 * Installs TeamCode Desktop of the CLI's own version, as the current user, without administrator rights:
 * 1. the signed `desktop-manifest.json` of that version is authenticated with the publisher keys compiled into the CLI
 *    (those of `teamcode verify-release`), and must name that version;
 * 2. the package of this system is downloaded to a private temporary folder, then its exact length and SHA-256 are
 *    checked against the manifest before anything opens it;
 * 3. macOS: the zipped `.app` is extracted next to `~/Applications/TeamCode.app`, its Developer ID signature is checked
 *    (`codesign --verify --deep --strict` against the expected Team ID), then it replaces the previous app;
 *    Windows: `msiexec` installs the per-user package; Linux: the archive becomes a versioned folder, `current` points
 *    at it and a menu entry is written. Each checks that the installed app reports the expected version.
 * Nothing is run from the package before step 2 (and on macOS before the signature check); a failure leaves the
 * previously installed app in place.
 *
 * [stage] does all of it that leaves the installed app alone (every download, check and extraction), [activate] the rest:
 * on macOS and Linux two renames, on Windows `msiexec`. The update banner stages while the app still runs and shows
 * the progress, and activates once it has quit; [install] does both at once.
 */
internal class DesktopInstaller(
    private val os: String,
    private val target: String,
    private val locations: DesktopLocations,
    private val source: DesktopSource,
    private val temporary: Path,
    private val run: suspend (String, List<String>) -> Int,
    private val verifier: VerifyReleaseManifest = VerifyReleaseManifest(),
    private val macosTeamId: String? = DesktopPublisher.MACOS_TEAM_ID,
    private val fs: FileSystem = FileSystem.SYSTEM,
    private val report: (String) -> Unit = {},
    private val quietWindows: Boolean = true,
    private val windowsTimeout: Duration = WINDOWS_INSTALL_TIMEOUT,
) {
    /** The authenticated release of [version] and this system's package in it. */
    suspend fun release(version: String): DesktopRelease {
        val metadata = source.metadata(version)
        val release = verifier.verifyDesktop(metadata.manifest, metadata.signature)
        if (release.manifest.version != version) throw CliOperationException("release_version_mismatch")
        val asset = release.manifest.assets[target] ?: throw CliUserException("TeamCode Desktop $version has no package for $target.")
        return DesktopRelease(release, target, asset)
    }

    suspend fun install(desktop: DesktopRelease): DesktopInstallation {
        val staged = stage(desktop)
        try { return activate(staged) } finally { discard(staged) }
    }

    /**
     * Downloads the package, checks its length and SHA-256, and on macOS and Linux unpacks it next to the installed app
     * with its signature and version checked; Windows keeps the checked package for `msiexec`. [progress] gets the share
     * downloaded, by tens of percent. The installed app is not touched; a failure leaves nothing behind.
     */
    suspend fun stage(desktop: DesktopRelease, progress: (Int) -> Unit = {}): StagedDesktop {
        if (os == "macos" && macosTeamId == null) throw CliUserException(
            "This build of TeamCode cannot check who signed the desktop app, so it does not install it on macOS. " +
                "Download TeamCode Desktop from https://github.com/nathanfallet/teamcode/releases/tag/${desktop.version}.",
        )
        val download = temporary / "$WINDOWS_STAGE${randomName()}"
        if (!ensurePrivateDirectory(download.toString())) throw CliOperationException("desktop_staging_unavailable")
        var kept = false
        try {
            val file = download / desktop.asset.fileName
            report("Downloading TeamCode Desktop ${desktop.version} (${megabytes(desktop.asset.size)})…")
            var shown = 0
            source.download(desktop.version, desktop.target, desktop.asset, file) { written ->
                val percent = if (desktop.asset.size > 0) (written.coerceAtMost(desktop.asset.size) * 10 / desktop.asset.size).toInt() * 10 else 100
                if (percent > shown) { shown = percent; progress(percent) }
            }
            // Exact length and SHA-256 from the signed manifest, before the package is opened.
            desktop.release.verifyBinary(file, desktop.target, fs)
            return when (os) {
                "macos" -> stageMacos(file, desktop.version)
                "windows" -> StagedDesktop(desktop.version, download).also { kept = true }
                else -> stageLinux(file, desktop.version)
            }
        } finally {
            if (!kept) try { fs.deleteRecursively(download) } catch (_: IOException) { }
        }
    }

    /** Puts [staged] in place of the installed app (which must not be running) and returns it as installed. */
    suspend fun activate(staged: StagedDesktop): DesktopInstallation = when (os) {
        "macos" -> activateMacos(staged)
        "windows" -> activateWindows(staged)
        else -> activateLinux(staged)
    }

    /** Removes what [stage] left: the stage itself and, once activated, the previous app moved into it. */
    fun discard(staged: StagedDesktop) {
        try { fs.deleteRecursively(staged.directory) } catch (_: IOException) { }
    }

    /**
     * The stage of [version] that another run of this CLI left in [directory] (the update banner's step hands it to the
     * run that activates it), or null when it is not one: a folder of the current user only, where [stage] puts them and
     * named as it names them, holding this system's package of [version].
     */
    fun staged(directory: Path, version: String): StagedDesktop? {
        val (parent, prefix) = when (os) {
            "macos" -> locations.applications to MACOS_STAGE
            "windows" -> temporary to WINDOWS_STAGE
            else -> locations.linuxRoot to LINUX_STAGE
        }
        if (parent == null || directory.parent != parent || !directory.name.startsWith(prefix) || !STAGE_NAME.matches(directory.name.removePrefix(prefix))) return null
        val folder = fs.metadataOrNull(directory)
        if (folder?.isDirectory != true || folder.symlinkTarget != null || !ensurePrivateDirectory(directory.toString())) return null
        val complete = when (os) {
            "macos" -> locations.at(directory / DesktopLocations.APP)?.version == version
            "windows" -> fs.metadataOrNull(directory / DesktopReleaseFormat.fileName(version, target))?.let { it.isRegularFile && it.symlinkTarget == null } == true
            else -> locations.at(directory / LINUX_APP)?.version == version
        }
        return if (complete) StagedDesktop(version, directory) else null
    }

    private suspend fun stageMacos(zip: Path, version: String): StagedDesktop {
        val applications = locations.applications ?: throw CliOperationException("desktop_home_unknown")
        fs.createDirectories(applications)
        // Next to the app it replaces, on the same volume, so that putting it in place is a rename.
        val unpacked = applications / "$MACOS_STAGE${randomName()}"
        if (!ensurePrivateDirectory(unpacked.toString())) throw CliOperationException("desktop_staging_unavailable")
        try {
            if (run("ditto", listOf("-x", "-k", zip.toString(), unpacked.toString())) != 0) throw CliOperationException("desktop_extract_failed")
            val app = unpacked / DesktopLocations.APP
            if (fs.list(unpacked) != listOf(app) || fs.metadataOrNull(app)?.isDirectory != true) throw CliOperationException("desktop_package_layout")
            report("Checking the signature of TeamCode Desktop $version…")
            if (run("codesign", listOf("--verify", "--deep", "--strict", "-R", "=${developerIdRequirement(requireNotNull(macosTeamId))}", app.toString())) != 0)
                throw CliOperationException("desktop_signature_invalid")
            expectVersion(app, version)
            return StagedDesktop(version, unpacked)
        } catch (error: Throwable) {
            try { fs.deleteRecursively(unpacked) } catch (_: IOException) { }
            throw error
        }
    }

    private fun activateMacos(staged: StagedDesktop): DesktopInstallation {
        val applications = locations.applications ?: throw CliOperationException("desktop_home_unknown")
        val destination = applications / DesktopLocations.APP
        if (fs.metadataOrNull(destination) != null) fs.atomicMove(destination, staged.directory / "previous.app")
        fs.atomicMove(staged.directory / DesktopLocations.APP, destination)
        return installed(destination, staged.version)
    }

    private suspend fun activateWindows(staged: StagedDesktop): DesktopInstallation {
        val root = locations.windowsRoot ?: throw CliOperationException("desktop_home_unknown")
        val msi = staged.directory / DesktopReleaseFormat.fileName(staged.version, target)
        // Per-user package: no administrator rights; 3010 = installed, restart suggested. Without a person to watch
        // (--yes, or no console) the installer shows no window, which would wait for a click nobody can give. Its
        // verbose log is kept next to the staging folder, and its wait is bounded.
        val log = temporary / "teamcode-desktop-msiexec.log"
        val code = withTimeoutOrNull(windowsTimeout) { run("msiexec.exe", msiexecArguments(msi, log, quiet = quietWindows)) }
        if (code == null) {
            report("The Windows installer did not finish within ${windowsTimeout.inWholeMinutes} minutes; its log: $log")
            throw CliOperationException("desktop_msiexec_timeout")
        }
        if (code != 0 && code != 3010) {
            report("The Windows installer exited with code ${code.toUInt()}; its log: $log")
            throw CliOperationException("desktop_msiexec_failed_${code.toUInt()}")
        }
        return installed(root, staged.version)
    }

    private suspend fun stageLinux(archive: Path, version: String): StagedDesktop {
        val root = locations.linuxRoot ?: throw CliOperationException("desktop_home_unknown")
        fs.createDirectories(root)
        val unpacked = root / "$LINUX_STAGE${randomName()}"
        if (!ensurePrivateDirectory(unpacked.toString())) throw CliOperationException("desktop_staging_unavailable")
        try {
            if (run("tar", listOf("-xzf", archive.toString(), "-C", unpacked.toString(), "--no-same-owner")) != 0) throw CliOperationException("desktop_extract_failed")
            val app = unpacked / LINUX_APP
            if (fs.list(unpacked) != listOf(app) || fs.metadataOrNull(locations.executable(app))?.isRegularFile != true) throw CliOperationException("desktop_package_layout")
            expectVersion(app, version)
            return StagedDesktop(version, unpacked)
        } catch (error: Throwable) {
            try { fs.deleteRecursively(unpacked) } catch (_: IOException) { }
            throw error
        }
    }

    private fun activateLinux(staged: StagedDesktop): DesktopInstallation {
        val root = locations.linuxRoot ?: throw CliOperationException("desktop_home_unknown")
        val version = staged.version
        val destination = root / version
        if (fs.metadataOrNull(destination) != null) fs.atomicMove(destination, staged.directory / "previous")
        fs.atomicMove(staged.directory / LINUX_APP, destination)
        val current = root / "current"
        val previous = fs.metadataOrNull(current)?.symlinkTarget?.name
        // A new link renamed over the old one: `current` always names a complete version.
        val link = root / ".current-${randomName()}"
        fs.createSymlink(link, version.toPath())
        fs.atomicMove(link, current)
        for (entry in fs.list(root)) if (entry.name != "current" && entry.name != version && entry.name != previous && ReleaseFormat.validVersion(entry.name))
            try { fs.deleteRecursively(entry) } catch (_: IOException) { }
        locations.linuxDesktopEntry?.let { entry ->
            entry.parent?.let(fs::createDirectories)
            writePrivateFile(entry.toString(), linuxDesktopEntry(root))
        }
        return installed(current, version)
    }

    private fun expectVersion(root: Path, version: String) {
        if (locations.at(root)?.version != version) throw CliOperationException("desktop_version_mismatch")
    }

    private fun installed(root: Path, version: String): DesktopInstallation =
        locations.at(root)?.takeIf { it.version == version } ?: throw CliOperationException("desktop_version_mismatch")

    companion object {
        /** A Developer ID Application signature of [teamId], the one notarized apps outside the App Store carry. */
        fun developerIdRequirement(teamId: String): String {
            require(Regex("[A-Z0-9]{10}").matches(teamId))
            return "anchor apple generic and certificate 1[field.1.2.840.113635.100.6.2.6] exists and " +
                "certificate leaf[field.1.2.840.113635.100.6.1.13] exists and certificate leaf[subject.OU] = \"$teamId\""
        }

        fun megabytes(bytes: Long): String = if (bytes < 1_000_000) "less than 1 MB" else "about ${(bytes + 500_000) / 1_000_000} MB"

        private fun randomName() = Buffer().write(secureRandomBytes(8)).readByteString().hex()

        /** Where [stage] unpacks: `~/Applications/.teamcode-desktop-<16 hex>`, `teamcode-desktop/.staging-<16 hex>`, and the temporary folder's `teamcode-desktop-<16 hex>`. */
        private const val MACOS_STAGE = ".teamcode-desktop-"
        private const val LINUX_STAGE = ".staging-"
        private const val WINDOWS_STAGE = "teamcode-desktop-"
        private val STAGE_NAME = Regex("[0-9a-f]{16}")
        private const val LINUX_APP = "TeamCode"
    }
}

internal val WINDOWS_INSTALL_TIMEOUT = 10.minutes

/** msiexec's arguments: per-user, no restart, a verbose log, and no window unless a person is there to use it. */
internal fun msiexecArguments(msi: Path, log: Path, quiet: Boolean): List<String> =
    listOf("/i", msi.toString(), if (quiet) "/qn" else "/qb", "/norestart", "/l*v", log.toString(), "MSIINSTALLPERUSER=1")
