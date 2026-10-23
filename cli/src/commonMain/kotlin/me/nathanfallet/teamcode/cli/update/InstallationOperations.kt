package me.nathanfallet.teamcode.cli.update

import io.ktor.client.HttpClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeout
import me.nathanfallet.teamcode.api.models.ReleaseFormat
import me.nathanfallet.teamcode.api.models.ReleaseVersion
import me.nathanfallet.teamcode.cli.CliOperationException
import me.nathanfallet.teamcode.cli.config.configStore
import me.nathanfallet.teamcode.cli.config.configPath
import me.nathanfallet.teamcode.cli.platform.runInteractiveProcess
import me.nathanfallet.teamcode.cli.sdk
import me.nathanfallet.teamcode.client.DownloadedReleaseMetadata
import me.nathanfallet.teamcode.client.ReleaseClient
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import okio.buffer
import okio.use

internal class InstallationOperations(private val store: InstallationStore, private val configuration: String = configPath(),
    private val report: (String) -> Unit = {}) {
    private val fs = FileSystem.SYSTEM
    private val verifier = VerifyReleaseManifest()

    suspend fun install(version: String?, bundle: String?, allowDowngrade: Boolean, approvedMetadata: DownloadedReleaseMetadata? = null): Activation? {
        require(approvedMetadata == null || (version != null && bundle == null && !allowDowngrade))
        store.initialize()
        store.writerLock().use {
            recoverBeforeWriting()
            ReleaseClient { configure -> HttpClient { configure(this) } }.use { transport ->
                val candidate = if (bundle == null) version ?: transport.latestVersion()
                    ?: throw CliOperationException("release_no_stable_version") else version
                val directory = bundle?.toPath()
                val metadata = approvedMetadata?.let { DownloadedReleaseMetadata(it.manifest.copyOf(), it.signature.copyOf()) }
                    ?: if (directory == null) transport.metadata(requireNotNull(candidate)) else DownloadedReleaseMetadata(
                    readReleaseFile(directory / "manifest.json", ReleaseFormat.MAX_MANIFEST_BYTES),
                    readReleaseFile(directory / "manifest.sig", ReleaseFormat.MAX_SIGNATURE_BYTES))
                val verified = verifier.verify(metadata.manifest, metadata.signature)
                if (candidate != null && verified.manifest.version != candidate) throw CliOperationException("release_version_mismatch")
                val before = store.state()
                if (!allowDowngrade && before != null && ReleaseVersion.parse(verified.manifest.version) <= ReleaseVersion.parse(before.current.version)) {
                    applyPendingRestart()
                    return null
                }
                val apiVersion = serverApiVersion()
                val asset = verified.manifest.assets[store.paths.target] ?: throw CliOperationException("release_target_missing")
                val selected = store.stage(metadata.manifest, metadata.signature) { destination ->
                    fs.sink(destination).buffer().use { sink ->
                        if (directory == null) transport.binary(verified.manifest.version, store.paths.target, asset.size) { bytes, count -> sink.write(bytes, 0, count) }
                        else {
                            val source = directory / asset.fileName
                            val info = fs.metadataOrNull(source) ?: throw CliOperationException("release_file_unreadable")
                            if (!info.isRegularFile || info.symlinkTarget != null || info.size != asset.size) throw CliOperationException("release_binary_invalid")
                            fs.source(source).use { input ->
                                val buffer = okio.Buffer()
                                var count = 0L
                                while (true) {
                                    val read = input.read(buffer, minOf(8192L, asset.size - count + 1))
                                    if (read < 0) break
                                    count += read
                                    if (count > asset.size) throw CliOperationException("release_binary_invalid")
                                    sink.write(buffer, read)
                                }
                            }
                        }
                    }
                }
                probe(selected, apiVersion)
                store.ensureLauncher(selected)
                return activate(selected, rollback = false)
            }
        }
    }

    suspend fun rollback(): Activation {
        store.writerLock().use {
            recoverBeforeWriting()
            val selected = store.state()?.previous ?: throw CliOperationException("release_rollback_unavailable")
            store.validate(selected)
            // Previous payloads were already accepted for API v1; rollback remains available offline.
            probe(selected, 1)
            return activate(selected, rollback = true)
        }
    }

    /** Caller holds the writer lock, so no other installer can own a pending journal. */
    private suspend fun recoverBeforeWriting() {
        store.journal()?.let { pending ->
            // A selection-only journal never stopped the service; agent sessions may keep their shared leases meanwhile.
            if (pending.restartService) store.lock(exclusive = true).use { store.recoverInterrupted { recoverInstallationService(store, it) } }
            else awaitInstallationLock(store, exclusive = false).use { store.recoverInterrupted() }
        }
    }

    /** Nothing new to select: still apply a restart an earlier selection deferred, if its sessions have ended. */
    private suspend fun applyPendingRestart() {
        reportingRestartFailure(store, report) { applyPendingServiceRestartLocked(store, systemServiceControls(store), report) }
    }

    private suspend fun activate(version: InstalledVersion, rollback: Boolean): Activation {
        val service = installationService(store, firstInstall = store.state() == null, configuration = configuration)
            ?.takeIf { it.running }?.let { ActivationService(it.settings.config, SystemServiceControl(it.settings)) }
        return activateInstallation(store, version, rollback, service, systemServiceControls(store), report = report)
    }

    private suspend fun serverApiVersion(): Int {
        val config = configStore(configuration).load() ?: return 1
        return try { sdk(config).use { it.capabilities().apiVersion } }
        catch (error: CancellationException) { throw error }
        catch (_: Exception) { throw CliOperationException("release_server_unavailable") }
    }

    private suspend fun probe(version: InstalledVersion, apiVersion: Int) {
        store.validate(version)
        val code = withTimeout(10_000) {
            runInteractiveProcess(store.paths.binary(version).toString(),
                listOf("--teamcode-installation-probe", version.version, apiVersion.toString()), emptyMap(), quiet = true)
        }
        if (code != 0) throw CliOperationException("release_candidate_incompatible")
    }
}
