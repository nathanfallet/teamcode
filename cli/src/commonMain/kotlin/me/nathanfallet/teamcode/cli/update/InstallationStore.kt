package me.nathanfallet.teamcode.cli.update

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import me.nathanfallet.teamcode.api.Serialization
import me.nathanfallet.teamcode.api.models.ReleaseFormat
import me.nathanfallet.teamcode.cli.CliOperationException
import me.nathanfallet.teamcode.cli.platform.createPrivateFile
import me.nathanfallet.teamcode.cli.platform.secureRandomBytes
import me.nathanfallet.teamcode.cli.platform.writePrivateFile
import okio.*
import okio.ByteString.Companion.toByteString
import okio.Path.Companion.toPath
import okio.use

/** Local attestations are written only after publisher authentication and exact binary verification. */
@Serializable
internal data class InstalledVersion(val version: String, val target: String, val manifestSha256: String, val binarySha256: String, val size: Long) {
    init {
        require(ReleaseFormat.validVersion(version) && target in ReleaseFormat.targets)
        require(Regex("[0-9a-f]{64}").matches(manifestSha256) && Regex("[0-9a-f]{64}").matches(binarySha256))
        require(size in 1..ReleaseFormat.MAX_BINARY_BYTES)
    }
}

@Serializable
internal data class InstallationState(val schemaVersion: Int = 1, val current: InstalledVersion, val previous: InstalledVersion? = null) {
    init { require(schemaVersion == 1 && (previous == null || previous.target == current.target)) }
}

@Serializable
internal data class InstallationJournal(
    val schemaVersion: Int = 1,
    val before: InstallationState?,
    val after: InstallationState,
    val serviceConfig: String? = null,
    val restartService: Boolean = false,
) {
    init {
        require(schemaVersion == 1 && (!restartService || serviceConfig != null))
        require(serviceConfig == null || (serviceConfig.toPath().isAbsolute && serviceConfig.none { it.code < 32 || it.code == 127 }))
    }
}

internal class InstallationPaths(root: Path, val target: String) {
    val root = root.toString().toPath(normalize = true).also { require(it.isAbsolute && it.toString().none { c -> c.code < 32 || c.code == 127 }) }
    init { require(target in ReleaseFormat.targets) }
    val executableName = if (target == "windows-x64") "teamcode.exe" else "teamcode"
    val state = this.root / "current.json"
    val journal = this.root / "transaction.json"
    val lock = this.root / "installation.lock"
    val writerLock = this.root / "writer.lock"
    val launcherDirectory = this.root / "bin"
    val launcher = launcherDirectory / executableName
    val launcherRecord = launcherDirectory / "launcher.json"
    val versions = this.root / "versions"
    fun directory(version: InstalledVersion): Path = versions / version.manifestSha256
    fun binary(version: InstalledVersion): Path = directory(version) / executableName
}

/**
 * Private version store. Hold the writer lock for staging/publication and additionally the exclusive activity lock for activation/recovery. Versions and
 * launcher files are immutable after publication; only current.json changes atomically. Old payloads
 * are retained, so Windows never replaces an executing image and a live old process keeps its files.
 */
internal class InstallationStore(val paths: InstallationPaths, private val fs: FileSystem = FileSystem.SYSTEM, private val verifier: VerifyReleaseManifest = VerifyReleaseManifest()) {
    fun initialize() { privateDirectory(paths.root); privateDirectory(paths.versions) }
    fun writerLock(): InstallationLock = acquireInstallationLock(paths.writerLock.toString(), exclusive = true)
        ?: throw CliOperationException("release_writer_busy")
    fun lock(exclusive: Boolean): InstallationLock =
        acquireInstallationLock(paths.lock.toString(), exclusive) ?: throw CliOperationException("release_installation_busy")

    fun state(): InstallationState? {
        if (fs.metadataOrNull(paths.state) == null) return null
        return decode<InstallationState>(paths.state).also {
            if (it.current.target != paths.target || it.previous?.target?.let { target -> target != paths.target } == true)
                throw failure("release_installation_invalid")
        }
    }

    fun launcherVersion(): InstalledVersion? = if (fs.metadataOrNull(paths.launcherRecord) == null) null else
        decode<InstalledVersion>(paths.launcherRecord).also { if (it.target != paths.target) throw failure("release_installation_invalid") }

    fun journal(): InstallationJournal? = if (fs.metadataOrNull(paths.journal) == null) null else
        decode<InstallationJournal>(paths.journal).also {
            if (it.after.current.target != paths.target || it.before?.current?.target?.let { target -> target != paths.target } == true)
                throw failure("release_installation_invalid")
        }

    /** Caller holds the exclusive OS lock, proving that a previous updater no longer owns it. */
    suspend fun recoverInterrupted(recover: suspend (InstallationJournal) -> Unit = {}): Boolean {
        val pending = journal() ?: return false
        val selected = state()
        if (selected != pending.before && selected != pending.after) throw failure("release_recovery_conflict")
        pending.before?.current?.let(::validate)
        restore(pending.before) { recover(pending) }
        fs.delete(paths.journal)
        flushInstallationDirectory(paths.root.toString())
        return true
    }

    /** The callback writes only into a freshly created, private empty staging executable. */
    suspend fun stage(manifest: ByteArray, signature: ByteArray, write: suspend (Path) -> Unit): InstalledVersion {
        if (manifest.size !in 1..ReleaseFormat.MAX_MANIFEST_BYTES || signature.size !in 1..ReleaseFormat.MAX_SIGNATURE_BYTES)
            throw failure("release_metadata_size")
        val payload = manifest.copyOf()
        val envelope = signature.copyOf()
        val verified = verifier.verify(payload, envelope)
        val asset = verified.manifest.assets[paths.target] ?: throw failure("release_target_missing")
        val version = InstalledVersion(verified.manifest.version, paths.target, payload.toByteString().sha256().hex(), asset.sha256, asset.size)
        val published = paths.directory(version)
        if (fs.metadataOrNull(published) != null) {
            validate(version)
            return version
        }
        val temporary = paths.root / (".staging-" + randomSuffix())
        privateDirectory(temporary)
        try {
            val binary = temporary / paths.executableName
            if (!createPrivateFile(binary.toString(), "")) throw failure("release_staging_conflict")
            write(binary)
            verified.verifyBinary(binary, paths.target, fs)
            sealInstallationExecutable(binary.toString())
            writePrivateFile((temporary / "manifest.json").toString(), payload.decodeToString(throwOnInvalidSequence = true))
            writePrivateFile((temporary / "manifest.sig").toString(), envelope.decodeToString(throwOnInvalidSequence = true))
            flushInstallationDirectory(temporary.toString())
            fs.atomicMove(temporary, published)
            flushInstallationDirectory(paths.versions.toString())
            validate(version)
            return version
        } finally { if (fs.metadataOrNull(temporary) != null) fs.deleteRecursively(temporary) }
    }

    /** Publication is atomic as a directory, with its immutable original-version attestation. */
    fun ensureLauncher(version: InstalledVersion) {
        val existing = launcherVersion()
        if (existing != null) { validateFile(paths.launcher, existing); return }
        if (fs.metadataOrNull(paths.launcherDirectory) != null) throw failure("release_launcher_conflict")
        val temporary = paths.root / (".launcher-" + randomSuffix())
        privateDirectory(temporary)
        try {
            val executable = temporary / paths.executableName
            if (!createPrivateFile(executable.toString(), "")) throw failure("release_launcher_conflict")
            fs.source(paths.binary(version)).use { source -> fs.sink(executable).buffer().use { it.writeAll(source) } }
            validateFile(executable, version)
            sealInstallationExecutable(executable.toString())
            writePrivateFile((temporary / "launcher.json").toString(), Serialization.json.encodeToString(InstalledVersion.serializer(), version))
            flushInstallationDirectory(temporary.toString())
            fs.atomicMove(temporary, paths.launcherDirectory)
            flushInstallationDirectory(paths.root.toString())
        } finally { if (fs.metadataOrNull(temporary) != null) fs.deleteRecursively(temporary) }
    }

    /** Hash local attestations on every selection; a root rotation need not rewrite the stable launcher. */
    fun validate(version: InstalledVersion) {
        if (version.target != paths.target) throw failure("release_target_unsupported")
        regularDirectory(paths.directory(version))
        val bytes = readReleaseFile(paths.directory(version) / "manifest.json", ReleaseFormat.MAX_MANIFEST_BYTES, fs)
        if (bytes.toByteString().sha256().hex() != version.manifestSha256) throw failure("release_installed_metadata_invalid")
        validateFile(paths.binary(version), version)
    }

    /** If the new payload/service cannot start, restore the exact old selection and recovery hook. */
    suspend fun activate(version: InstalledVersion, rollback: Boolean = false,
        serviceConfig: String? = null, restartService: Boolean = false,
        prepare: suspend () -> Unit = {}, start: suspend () -> Unit = {}, recover: suspend () -> Unit = {}) {
        if (journal() != null) throw failure("release_recovery_required")
        validate(version)
        val before = state()
        before?.current?.let(::validate)
        if (before?.current == version) return
        if (rollback && before?.previous != version) throw failure("release_rollback_unavailable")
        val after = InstallationState(current = version, previous = before?.current)
        writePrivateFile(paths.journal.toString(), Serialization.json.encodeToString(InstallationJournal.serializer(),
            InstallationJournal(before = before, after = after, serviceConfig = serviceConfig, restartService = restartService)))
        flushInstallationDirectory(paths.root.toString())
        try {
            prepare()
            save(after)
            start()
            fs.delete(paths.journal)
            flushInstallationDirectory(paths.root.toString())
        } catch (error: CancellationException) {
            withContext(NonCancellable) {
                try { restore(before, recover); fs.delete(paths.journal) }
                catch (recovery: CancellationException) { error.addSuppressed(recovery) }
                catch (_: Exception) { error.addSuppressed(failure("release_service_recovery_failed")) }
            }
            throw error
        } catch (error: Exception) {
            withContext(NonCancellable) { restore(before, recover); fs.delete(paths.journal) }
            throw error
        }
    }

    private suspend fun restore(before: InstallationState?, recover: suspend () -> Unit) {
        if (before == null) fs.delete(paths.state, mustExist = false) else save(before)
        try { recover() } catch (error: CancellationException) { throw error }
        catch (_: Exception) { throw failure("release_service_recovery_failed") }
    }

    private fun save(state: InstallationState) {
        writePrivateFile(paths.state.toString(), Serialization.json.encodeToString(InstallationState.serializer(), state))
        flushInstallationDirectory(paths.root.toString())
    }
    private inline fun <reified T> decode(path: Path): T = try {
        regular(path)
        Serialization.json.decodeFromString<T>(readReleaseFile(path, 8192, fs).decodeToString(throwOnInvalidSequence = true))
    } catch (error: CliOperationException) { throw error }
    catch (_: Exception) { throw failure("release_installation_invalid") }

    private fun validateFile(path: Path, version: InstalledVersion) {
        regular(path)
        if (fs.metadata(path).size != version.size) throw failure("release_binary_invalid")
        fs.source(path).use { source ->
            val hash = HashingSource.sha256(source)
            val buffer = Buffer()
            var total = 0L
            while (true) {
                val count = hash.read(buffer, minOf(8192L, version.size - total + 1))
                if (count < 0) break
                total += count; buffer.skip(count)
                if (total > version.size) throw failure("release_binary_invalid")
            }
            if (total != version.size || hash.hash.hex() != version.binarySha256) throw failure("release_binary_invalid")
        }
    }
    private fun regular(path: Path) {
        val value = fs.metadataOrNull(path) ?: throw failure("release_file_unreadable")
        if (!value.isRegularFile || value.symlinkTarget != null) throw failure("release_file_unreadable")
        validatePrivateInstallationPath(path.toString(), directory = false)
    }
    private fun regularDirectory(path: Path) {
        val value = fs.metadataOrNull(path) ?: throw failure("release_installation_invalid")
        if (!value.isDirectory || value.symlinkTarget != null) throw failure("release_installation_invalid")
        validatePrivateInstallationPath(path.toString(), directory = true)
    }
    private fun privateDirectory(path: Path) {
        fs.metadataOrNull(path)?.let {
            if (!it.isDirectory || it.symlinkTarget != null) throw failure("release_installation_invalid")
            val marker = path / ".directory"
            if (fs.metadataOrNull(marker) == null && fs.list(path).isNotEmpty()) throw failure("release_directory_not_owned")
            if (fs.metadataOrNull(marker) != null) {
                validatePrivateInstallationPath(path.toString(), directory = true)
                regular(marker)
                if (readReleaseFile(marker, 128, fs).decodeToString() != "TeamCode private installation directory\n")
                    throw failure("release_directory_not_owned")
            }
        }
        writePrivateFile((path / ".directory").toString(), "TeamCode private installation directory\n")
        regularDirectory(path)
    }
    private fun randomSuffix() = secureRandomBytes(16).toByteString().hex()
    private fun failure(code: String) = CliOperationException(code)
}
