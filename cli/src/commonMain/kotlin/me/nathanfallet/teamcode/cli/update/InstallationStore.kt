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

/**
 * The payload the managed OS service was last started from. It differs from `current.json` only while a selection waits
 * for the last agent session to end before the service restarts. Older launchers ignore this file.
 */
@Serializable
internal data class ServiceRecord(val schemaVersion: Int = 1, val serviceConfig: String, val version: InstalledVersion) {
    init {
        require(schemaVersion == 1)
        require(serviceConfig.toPath().isAbsolute && serviceConfig.none { it.code < 32 || it.code == 127 })
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
    val serviceRecord = this.root / "service.json"
    /** One exclusively locked file per running agent session, named after the payload that runs it. */
    val sessions = this.root / "sessions"
    val launcherDirectory = this.root / "bin"
    val launcher = launcherDirectory / executableName
    val launcherRecord = launcherDirectory / "launcher.json"
    val versions = this.root / "versions"
    fun directory(version: InstalledVersion): Path = versions / version.manifestSha256
    fun binary(version: InstalledVersion): Path = directory(version) / executableName
}

/**
 * Private version store. Hold the writer lock for staging/publication, plus the activity lock for activation: exclusive to
 * restart the service or recover, shared for a selection made while agent sessions run (its restart is deferred). Versions
 * and launcher files are immutable after publication; only current.json and service.json change atomically. Payloads are
 * collected only while no session runs, so Windows never replaces an executing image and a live old process keeps its files.
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

    fun serviceRecord(): ServiceRecord? = if (fs.metadataOrNull(paths.serviceRecord) == null) null else
        decode<ServiceRecord>(paths.serviceRecord).also { if (it.version.target != paths.target) throw failure("release_installation_invalid") }

    fun saveServiceRecord(record: ServiceRecord) {
        writePrivateFile(paths.serviceRecord.toString(), Serialization.json.encodeToString(ServiceRecord.serializer(), record))
        flushInstallationDirectory(paths.root.toString())
    }

    fun clearServiceRecord() {
        if (fs.metadataOrNull(paths.serviceRecord) == null) return
        fs.delete(paths.serviceRecord)
        flushInstallationDirectory(paths.root.toString())
    }

    /** The service still runs an older payload than the selection: it restarts once no agent session holds a lease. */
    fun pendingServiceRestart(): ServiceRecord? {
        val record = serviceRecord() ?: return null
        return record.takeIf { it.version != state()?.current }
    }

    /**
     * Register a running agent session of the payload [manifestSha256]. The file is created and locked in one call, so a
     * counter never observes it unlocked while its session is alive; closing the lease removes it.
     */
    fun openSession(manifestSha256: String): InstallationLock {
        require(Regex("[0-9a-f]{64}").matches(manifestSha256))
        privateDirectory(paths.sessions)
        val path = paths.sessions / "$manifestSha256-${randomSuffix()}.lock"
        val lock = acquireInstallationLock(path.toString(), exclusive = true) ?: throw failure("release_session_conflict")
        return object : InstallationLock {
            private var open = true
            override fun close() {
                if (!open) return
                open = false
                lock.close()
                try { fs.delete(path, mustExist = false) } catch (_: Exception) { /* Removed later by garbage collection. */ }
            }
        }
    }

    /** Live agent sessions per payload manifest digest. Stale files of crashed sessions are not counted. */
    fun liveSessions(): Map<String, Int> {
        if (fs.metadataOrNull(paths.sessions) == null) return emptyMap()
        val counts = mutableMapOf<String, Int>()
        for (file in fs.list(paths.sessions)) {
            val match = SESSION_FILE.matchEntire(file.name) ?: continue
            val probe = try { acquireInstallationLock(file.toString(), exclusive = true) } catch (_: Exception) { continue }
            if (probe == null) counts[match.groupValues[1]] = (counts[match.groupValues[1]] ?: 0) + 1 else probe.close()
        }
        return counts
    }

    /** The published version name of an installed payload, for messages only; never trusted for selection. */
    fun versionName(manifestSha256: String): String? = try {
        val bytes = readReleaseFile(paths.versions / manifestSha256 / "manifest.json", ReleaseFormat.MAX_MANIFEST_BYTES, fs)
        Serialization.json.decodeFromString(me.nathanfallet.teamcode.api.models.ReleaseManifest.serializer(), bytes.decodeToString())
            .version.takeIf(ReleaseFormat::validVersion)
    } catch (_: Exception) { null }

    /**
     * Restart the service recorded in [record] on the current selection. Caller holds the writer lock and the exclusive
     * activity lock, so no agent session depends on the service. The journal makes a crash at any point recoverable, by
     * this or an older launcher: recovery selects the payload the service was running and restarts it there.
     */
    suspend fun restartService(record: ServiceRecord, prepare: suspend () -> Unit, start: suspend () -> Unit, recover: suspend (InstalledVersion) -> Unit) {
        if (journal() != null) throw failure("release_recovery_required")
        val after = state() ?: throw failure("release_installation_missing")
        validate(after.current)
        val fallback = if (record.version == after.current) after else try {
            validate(record.version)
            InstallationState(current = record.version, previous = after.current)
        } catch (_: CliOperationException) { after }
        writePrivateFile(paths.journal.toString(), Serialization.json.encodeToString(InstallationJournal.serializer(),
            InstallationJournal(before = fallback, after = after, serviceConfig = record.serviceConfig, restartService = true)))
        flushInstallationDirectory(paths.root.toString())
        try {
            prepare()
            start()
            fs.delete(paths.journal)
            flushInstallationDirectory(paths.root.toString())
            saveServiceRecord(record.copy(version = after.current))
        } catch (error: CancellationException) {
            withContext(NonCancellable) {
                try { restore(fallback) { recover(fallback.current) }; fs.delete(paths.journal) }
                catch (recovery: CancellationException) { error.addSuppressed(recovery) }
                catch (_: Exception) { error.addSuppressed(failure("release_service_recovery_failed")) }
            }
            throw error
        } catch (error: Exception) {
            withContext(NonCancellable) { restore(fallback) { recover(fallback.current) }; fs.delete(paths.journal) }
            throw error
        }
    }

    /**
     * Delete payloads that are neither selected, the rollback target nor the service's payload. Caller holds the writer
     * lock and the exclusive activity lock: no agent session of any version is running, so no session executes a payload
     * removed here. A directory is first renamed aside, so a failure never leaves a half-deleted `versions/` entry
     * (Windows refuses to rename a directory whose executable still runs; that payload is then simply kept).
     */
    fun collectGarbage(): List<String> {
        if (journal() != null) return emptyList()
        val selected = state() ?: return emptyList()
        val retained = setOfNotNull(selected.current.manifestSha256, selected.previous?.manifestSha256,
            serviceRecord()?.version?.manifestSha256)
        val removed = mutableListOf<String>()
        for (directory in fs.list(paths.versions)) {
            if (!Regex("[0-9a-f]{64}").matches(directory.name) || directory.name in retained) continue
            val trash = paths.root / (".trash-" + randomSuffix())
            try { fs.atomicMove(directory, trash) } catch (_: Exception) { continue }
            removed += directory.name
        }
        if (removed.isNotEmpty()) flushInstallationDirectory(paths.versions.toString())
        for (entry in fs.list(paths.root)) if (entry.name.startsWith(".trash-")) {
            try { fs.deleteRecursively(entry) } catch (_: Exception) { /* Retried by the next collection. */ }
        }
        if (fs.metadataOrNull(paths.sessions) != null) for (file in fs.list(paths.sessions)) {
            if (SESSION_FILE.matches(file.name)) try { fs.delete(file) } catch (_: Exception) { /* Still open: kept. */ }
        }
        return removed
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

    private companion object { val SESSION_FILE = Regex("([0-9a-f]{64})-[0-9a-f]{32}\\.lock") }
}
