package me.nathanfallet.teamcode.cli.update

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import me.nathanfallet.teamcode.cli.CliOperationException
import me.nathanfallet.teamcode.cli.platform.ServicePlatform
import me.nathanfallet.teamcode.cli.platform.secureRandomBytes
import me.nathanfallet.teamcode.client.DownloadedReleaseMetadata
import okio.ByteString.Companion.decodeBase64
import okio.ByteString.Companion.toByteString
import okio.FileSystem
import kotlin.test.*

class AutomaticUpdateTest {
    private val fs = FileSystem.SYSTEM
    private val verifier = VerifyReleaseManifest(listOf(ReleaseFixtures.firstKey))
    private fun release(): ReleaseCheck {
        val metadata = DownloadedReleaseMetadata(requireNotNull(ReleaseFixtures.newerPayload.decodeBase64()).toByteArray(),
            requireNotNull(ReleaseFixtures.newerEnvelope.decodeBase64()).toByteArray())
        return ReleaseCheck("1.0.0", verifier.verify(metadata.manifest, metadata.signature), metadata)
    }
    private suspend fun scratch(block: suspend (InstallationPaths, AutomaticUpdateStore, ServicePlatform, AutomaticUpdateSettings) -> Unit) {
        val root = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / ("teamcode-automatic é " + secureRandomBytes(12).toByteString().hex())
        val paths = InstallationPaths(root, "linux-x64")
        try {
            InstallationStore(paths).initialize()
            val platform = ServicePlatform("linux", paths.launcher.toString(), "1000")
            val settings = automaticUpdateSettings(paths, (root / "configuration/teamcode/config.json").toString(), platform, root.toString())
            block(paths, AutomaticUpdateStore(paths), platform, settings)
        } finally { if (fs.exists(root)) fs.deleteRecursively(root) }
    }
    private fun installed(candidate: ReleaseCheck): InstalledVersion {
        val asset = requireNotNull(candidate.release.manifest.assets["linux-x64"])
        return InstalledVersion(candidate.release.manifest.version, "linux-x64", candidate.metadata.manifest.toByteString().sha256().hex(), asset.sha256, asset.size)
    }

    @Test fun disabledRootAndEnvironmentOverrideDoNoNetworkOrWorkerWork() = runTest {
        scratch { paths, policy, platform, settings ->
            assertEquals(fs.canonicalize(paths.root), policy.paths.root)
            assertEquals((policy.paths.root / "bin/teamcode").toString(), settings.executable)
            suspend fun attempt(owner: ServicePlatform = platform, disabled: Boolean = false) = AutomaticUpdateRunner(policy, owner, disabled).run(
                fetch = { error("No automatic opt-in") }, install = { _, _ -> error("No automatic installation") })
            assertNull(attempt())
            policy.save(settings)
            assertNull(attempt())
            policy.save(settings.copy(enabled = true))
            assertNull(attempt(platform.copy(userId = "0")))
            assertNull(attempt(disabled = true))
            assertFailsWith<IllegalArgumentException> { attempt(platform.copy(userId = "1001")) }
            assertFalse(fs.exists(paths.root / "automatic-update-worker.lock"))
            assertNull(policy.result())
        }
    }

    @Test fun newerStableReleaseUsesStoredConfigurationAndBusyActivationCanRetry() = runTest {
        scratch { _, policy, platform, settings ->
            policy.save(settings.copy(enabled = true))
            var calls = 0
            val worker = AutomaticUpdateRunner(policy, platform, now = { 123L })
            val deferred = assertNotNull(worker.run({ release() }) { _, _ -> throw CliOperationException("release_installation_busy") })
            assertEquals("DEFERRED", deferred.outcome)
            assertEquals("release_installation_busy", deferred.diagnostic)
            val updated = assertNotNull(worker.run({ release() }) { candidate, actual ->
                calls++; assertEquals(settings.configuration, actual.configuration); installed(candidate)
            })
            assertEquals("UPDATED", updated.outcome); assertEquals(1, calls)
            assertEquals(updated, AutomaticUpdateStore(policy.paths).result())
            assertFalse(fs.read(policy.paths.root / "automatic-update-result.json") { readUtf8() }.contains("accessToken"))
        }
    }

    @Test fun absentOlderAndPrereleaseCandidatesNeverInstall() = runTest {
        scratch { _, policy, platform, settings ->
            policy.save(settings.copy(enabled = true))
            val original = release()
            val prerelease = original.copy(release = VerifiedRelease(original.release.manifest.copy(version = "9.0.0-beta.1", tag = "9.0.0-beta.1"), original.release.keyId))
            for (candidate in listOf(null, original.copy(currentVersion = "99.0.0"), prerelease)) {
                val result = assertNotNull(AutomaticUpdateRunner(policy, platform).run({ candidate }) { _, _ -> error("Not a newer stable release") })
                assertEquals("NO_UPDATE", result.outcome)
            }
        }
    }

    @Test fun workerLockPreventsDuplicateWorkAndCancellationPropagatesWithoutSuccess() = runTest {
        scratch { _, policy, platform, settings ->
            policy.save(settings.copy(enabled = true))
            val worker = AutomaticUpdateRunner(policy, platform)
            assertFailsWith<CancellationException> {
                worker.run({
                    val competing = assertNotNull(worker.run({ error("Worker is busy") }) { _, _ -> error("Worker is busy") })
                    assertEquals("automatic_worker_busy", competing.diagnostic)
                    assertEquals("STARTED", policy.result()?.outcome)
                    release()
                }) { _, _ -> throw CancellationException("fixture") }
            }
            assertEquals("STARTED", policy.result()?.outcome, "Interrupted work must not be reported as updated")
            val failed = assertNotNull(worker.run({ release() }) { _, _ -> error("DO_NOT_LOG_REMOTE_BODY") })
            assertEquals("FAILED", failed.outcome)
            assertEquals("automatic_update_failed", failed.diagnostic)
            assertFalse(fs.read(policy.paths.root / "automatic-update-result.json") { readUtf8() }.contains("DO_NOT_LOG_REMOTE_BODY"))
            assertEquals("UPDATED", worker.run({ release() }) { candidate, _ -> installed(candidate) }?.outcome)
        }
    }

    @Test fun registrationSetupFailsClosedAndOffDoesNotStopAnInflightWorker() = runTest {
        scratch { _, policy, platform, settings ->
            val scheduler = FakeSchedule(policy)
            val manager = AutomaticUpdateManager(policy, scheduler, platform) { settings }
            scheduler.cancelInstall = true
            assertFailsWith<CancellationException> { manager.configure("on") }
            assertFalse(assertNotNull(policy.settings()).enabled)
            scheduler.cancelInstall = false
            assertTrue(assertNotNull(manager.configure("on")).enabled)
            val worker = assertNotNull(acquireInstallationLock(policy.workerLock.toString(), exclusive = true))
            worker.use {
                assertFalse(assertNotNull(manager.configure("off")).enabled)
                assertEquals(0, scheduler.removed)
                assertEquals("automatic_worker_busy", assertFailsWith<CliOperationException> { manager.configure("uninstall") }.code)
            }
            assertNull(manager.configure("uninstall")); assertNull(policy.settings())
            assertEquals(1, scheduler.removed)
        }
    }
}

private class FakeSchedule(private val policy: AutomaticUpdateStore) : AutomaticUpdateSchedule {
    var cancelInstall = false
    var removed = 0
    override fun owns(settings: AutomaticUpdateSettings) = true
    override suspend fun install(settings: AutomaticUpdateSettings) {
        assertFalse(assertNotNull(policy.settings()).enabled)
        if (cancelInstall) throw CancellationException("fixture")
    }
    override suspend fun start(settings: AutomaticUpdateSettings) { assertTrue(assertNotNull(policy.settings()).enabled) }
    override suspend fun disable(settings: AutomaticUpdateSettings) { assertFalse(assertNotNull(policy.settings()).enabled) }
    override suspend fun uninstall(settings: AutomaticUpdateSettings) { assertFalse(assertNotNull(policy.settings()).enabled); removed++ }
}
