package me.nathanfallet.teamcode.cli.ui

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

/** Where the desktop packages come from: the GitHub release of a version, or an offline directory (`--bundle`). */
internal interface DesktopSource {
    suspend fun metadata(version: String): DownloadedReleaseMetadata
    /** Writes exactly [asset]'s size of bytes to [destination]; their hash is checked by the caller. */
    suspend fun download(version: String, target: String, asset: ReleaseAsset, destination: Path)
}

internal class OnlineDesktopSource(private val client: ReleaseClient, private val fs: FileSystem = FileSystem.SYSTEM) : DesktopSource {
    override suspend fun metadata(version: String) = client.desktopMetadata(version)
    override suspend fun download(version: String, target: String, asset: ReleaseAsset, destination: Path) {
        fs.sink(destination, mustCreate = true).buffer().use { sink ->
            client.desktopPackage(version, target, asset.size) { data, size -> sink.write(data, 0, size) }
        }
    }
}

/** `desktop-manifest.json`, `desktop-manifest.sig` and the package, in one directory (release acceptance, offline use). */
internal class BundleDesktopSource(private val directory: Path, private val fs: FileSystem = FileSystem.SYSTEM) : DesktopSource {
    override suspend fun metadata(version: String) = DownloadedReleaseMetadata(
        readReleaseFile(directory / DesktopReleaseFormat.MANIFEST, ReleaseFormat.MAX_MANIFEST_BYTES, fs),
        readReleaseFile(directory / DesktopReleaseFormat.SIGNATURE, ReleaseFormat.MAX_SIGNATURE_BYTES, fs),
    )
    override suspend fun download(version: String, target: String, asset: ReleaseAsset, destination: Path) {
        val source = directory / asset.fileName
        val metadata = fs.metadataOrNull(source)
        if (metadata?.isRegularFile != true || metadata.symlinkTarget != null || metadata.size != asset.size) throw CliOperationException("desktop_package_invalid")
        fs.copy(source, destination)
    }
}

/** A desktop release, authenticated, and the package of this system in it. */
internal class DesktopRelease(val release: VerifiedRelease, val target: String, val asset: ReleaseAsset) {
    val version: String get() = release.manifest.version
}

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
        if (os == "macos" && macosTeamId == null) throw CliUserException(
            "This build of TeamCode cannot check who signed the desktop app, so it does not install it on macOS. " +
                "Download TeamCode Desktop from https://github.com/nathanfallet/teamcode/releases/tag/${desktop.version}.",
        )
        val staging = temporary / "teamcode-desktop-${randomName()}"
        if (!ensurePrivateDirectory(staging.toString())) throw CliOperationException("desktop_staging_unavailable")
        try {
            val file = staging / desktop.asset.fileName
            report("Downloading TeamCode Desktop ${desktop.version} (${megabytes(desktop.asset.size)})…")
            source.download(desktop.version, desktop.target, desktop.asset, file)
            // Exact length and SHA-256 from the signed manifest, before the package is opened.
            desktop.release.verifyBinary(file, desktop.target, fs)
            return when (os) {
                "macos" -> installMacos(file, desktop.version)
                "windows" -> installWindows(file, desktop.version)
                else -> installLinux(file, desktop.version)
            }
        } finally {
            try { fs.deleteRecursively(staging) } catch (_: IOException) { }
        }
    }

    private suspend fun installMacos(zip: Path, version: String): DesktopInstallation {
        val applications = locations.applications ?: throw CliOperationException("desktop_home_unknown")
        fs.createDirectories(applications)
        val unpacked = applications / ".teamcode-desktop-${randomName()}"
        if (!ensurePrivateDirectory(unpacked.toString())) throw CliOperationException("desktop_staging_unavailable")
        try {
            if (run("ditto", listOf("-x", "-k", zip.toString(), unpacked.toString())) != 0) throw CliOperationException("desktop_extract_failed")
            val app = unpacked / DesktopLocations.APP
            if (fs.list(unpacked) != listOf(app) || fs.metadataOrNull(app)?.isDirectory != true) throw CliOperationException("desktop_package_layout")
            if (run("codesign", listOf("--verify", "--deep", "--strict", "-R", "=${developerIdRequirement(requireNotNull(macosTeamId))}", app.toString())) != 0)
                throw CliOperationException("desktop_signature_invalid")
            expectVersion(app, version)
            val destination = applications / DesktopLocations.APP
            if (fs.metadataOrNull(destination) != null) fs.atomicMove(destination, unpacked / "previous.app")
            fs.atomicMove(app, destination)
            return installed(destination, version)
        } finally {
            try { fs.deleteRecursively(unpacked) } catch (_: IOException) { }
        }
    }

    private suspend fun installWindows(msi: Path, version: String): DesktopInstallation {
        val root = locations.windowsRoot ?: throw CliOperationException("desktop_home_unknown")
        // Per-user package: no administrator rights; basic progress window; 3010 = installed, restart suggested.
        val code = run("msiexec.exe", listOf("/i", msi.toString(), "/qb", "/norestart", "MSIINSTALLPERUSER=1"))
        if (code != 0 && code != 3010) throw CliOperationException("desktop_msiexec_failed_${code.toUInt()}")
        return installed(root, version)
    }

    private suspend fun installLinux(archive: Path, version: String): DesktopInstallation {
        val root = locations.linuxRoot ?: throw CliOperationException("desktop_home_unknown")
        fs.createDirectories(root)
        val unpacked = root / ".staging-${randomName()}"
        if (!ensurePrivateDirectory(unpacked.toString())) throw CliOperationException("desktop_staging_unavailable")
        try {
            if (run("tar", listOf("-xzf", archive.toString(), "-C", unpacked.toString(), "--no-same-owner")) != 0) throw CliOperationException("desktop_extract_failed")
            val app = unpacked / "TeamCode"
            if (fs.list(unpacked) != listOf(app) || fs.metadataOrNull(locations.executable(app))?.isRegularFile != true) throw CliOperationException("desktop_package_layout")
            expectVersion(app, version)
            val destination = root / version
            if (fs.metadataOrNull(destination) != null) fs.atomicMove(destination, unpacked / "previous")
            fs.atomicMove(app, destination)
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
        } finally {
            try { fs.deleteRecursively(unpacked) } catch (_: IOException) { }
        }
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
    }
}
