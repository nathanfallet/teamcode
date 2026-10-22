package me.nathanfallet.teamcode.cli.update

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import me.nathanfallet.teamcode.api.Serialization
import me.nathanfallet.teamcode.cli.platform.secureRandomBytes
import me.nathanfallet.teamcode.cli.platform.writePrivateFile
import okio.ByteString.Companion.decodeBase64
import okio.ByteString.Companion.toByteString
import okio.FileSystem
import kotlin.test.*

/** Native flock/LockFileEx semantics: shared activity leases coexist within one test process, unlike JVM file locks. */
class DeferredActivationTest {
    private val fs = FileSystem.SYSTEM
    private val config = (FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "teamcode-config" / "config.json").toString()

    private class FakeService(var serving: String) : ManagedServiceControl {
        val events = mutableListOf<String>()
        var running = true
        var failStartOn: String? = null
        override suspend fun running() = running
        override suspend fun serving(version: String) = serving == version
        override suspend fun stop() { events += "stop" }
        override suspend fun start() { events += "start" }
        override suspend fun await(version: String) {
            if (version == failStartOn) { events += "failed $version"; error("fixture startup failure") }
            serving = version; events += "serving $version"
        }
    }

    private fun bytes(value: String) = requireNotNull(value.decodeBase64()).toByteArray()
    private suspend fun scratch(block: suspend (InstallationStore, InstalledVersion, InstalledVersion) -> Unit) {
        val root = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / ("teamcode-deferred é " + secureRandomBytes(12).toByteString().hex())
        val store = InstallationStore(InstallationPaths(root, "linux-x64"), verifier = VerifyReleaseManifest(listOf(ReleaseFixtures.firstKey)))
        try {
            store.initialize()
            val first = stage(store, newer = false)
            val second = stage(store, newer = true)
            store.ensureLauncher(first)
            block(store, first, second)
        } finally { if (fs.metadataOrNull(root) != null) fs.deleteRecursively(root) }
    }
    /** Idempotent, like the installer: garbage collection may remove a staged payload that was never selected. */
    private suspend fun stage(store: InstallationStore, newer: Boolean) =
        store.stage(bytes(if (newer) ReleaseFixtures.newerPayload else ReleaseFixtures.validPayload),
            bytes(if (newer) ReleaseFixtures.newerEnvelope else ReleaseFixtures.validEnvelope)) { fs.write(it) { writeUtf8(ReleaseFixtures.binaryText) } }
    private fun controls(service: FakeService): ServiceControls = { path -> if (path == config) service else null }
    private suspend fun select(store: InstallationStore, version: InstalledVersion, service: FakeService?, rollback: Boolean = false) =
        store.writerLock().use {
            stage(store, newer = version.version != stage(store, newer = false).version)
            activateInstallation(store, version, rollback, service?.let { ActivationService(config, it) },
                service?.let(::controls) ?: { null }, waitMillis = 1_000)
        }
    /** What `teamcode run` holds for its whole lifetime: the shared activity lease plus its session record. */
    private fun session(store: InstallationStore, version: InstalledVersion): AutoCloseable {
        val activity = store.lock(exclusive = false)
        return AgentInstallationLease(store, activity, store.openSession(version.manifestSha256))
    }

    @Test fun updateWithAnOpenSessionLeaseSelectsNowAndDefersTheServiceRestart() = runTest {
        scratch { store, first, second ->
            val service = FakeService(first.version)
            assertEquals(ServiceOutcome.RESTARTED, select(store, first, service).service)
            service.events.clear()
            val launcher = fs.read(store.paths.launcher) { readByteArray() }
            val lease = session(store, first)
            val activation = select(store, second, service)
            assertEquals(ServiceOutcome.DEFERRED, activation.service)
            assertEquals(InstallationState(current = second, previous = first), store.state(), "New launches must use the new payload at once")
            assertEquals(emptyList(), service.events, "The service an active session depends on must keep running")
            assertEquals(mapOf(first.manifestSha256 to 1), activation.sessions)
            assertEquals(first, activation.serviceVersion)
            assertEquals(first, store.pendingServiceRestart()?.version)
            // The session's own payload stays exactly where it started; the launcher is never rewritten.
            store.validate(first)
            assertTrue(fs.metadata(store.paths.binary(first)).isRegularFile)
            assertContentEquals(launcher, fs.read(store.paths.launcher) { readByteArray() })
            assertEquals(first, store.launcherVersion())
            val lines = activationReport(activation, store)
            assertTrue(lines.any { "1 running agent session keeps running on ${first.version}" in it }, lines.toString())
            assertTrue(lines.any { "keeps running ${first.version} and restarts on ${second.version} when they end" in it }, lines.toString())

            // Another session start (or any update attempt) cannot restart the service under a live lease.
            assertEquals(PendingRestart.WAITING, applyPendingServiceRestart(store, controls(service)) { fail(it) })
            assertEquals(emptyList(), service.events)

            lease.close()
            assertEquals(emptyMap(), store.liveSessions())
            assertEquals(PendingRestart.RESTARTED, applyPendingServiceRestart(store, controls(service)) { })
            assertEquals(listOf("stop", "start", "serving ${second.version}"), service.events)
            assertNull(store.pendingServiceRestart())
            assertEquals(second, store.serviceRecord()?.version)
            assertNull(store.journal())
            assertEquals(PendingRestart.NONE, applyPendingServiceRestart(store, controls(service)) { fail(it) })
        }
    }

    @Test fun withoutSessionsTheServiceRestartsDuringTheUpdate() = runTest {
        scratch { store, first, second ->
            val service = FakeService(first.version)
            select(store, first, service)
            service.events.clear()
            val activation = select(store, second, service)
            assertEquals(ServiceOutcome.RESTARTED, activation.service)
            assertEquals(listOf("stop", "start", "serving ${second.version}"), service.events)
            assertNull(store.pendingServiceRestart())
            assertTrue(activationReport(activation, store).any { "service restarted on ${second.version}" in it })
        }
    }

    @Test fun aSessionThatEndsAfterTheBusyCheckIsCoveredByTheUpdateItself() = runTest {
        scratch { store, first, second ->
            val service = FakeService(first.version)
            select(store, first, service)
            service.events.clear()
            // Only a launcher recovery holds the exclusive lock while the update starts; it releases it shortly after.
            val recovery = store.lock(exclusive = true)
            val activation = coroutineScope {
                val update = async { select(store, second, service) }
                delay(300)
                recovery.close()
                update.await()
            }
            assertEquals(ServiceOutcome.RESTARTED, activation.service)
            assertEquals(second.version, service.serving)
            assertNull(store.pendingServiceRestart())
        }
    }

    @Test fun aCrashBetweenSelectionAndRestartIsRecoveredByTheNextLaunch() = runTest {
        scratch { store, first, second ->
            val service = FakeService(first.version)
            select(store, first, service)
            service.events.clear()
            // Crash inside the deferred selection: its journal never stopped the service and restores the old selection.
            store.lock(exclusive = false).use {
                writePrivateFile(store.paths.journal.toString(), Serialization.json.encodeToString(InstallationJournal.serializer(),
                    InstallationJournal(before = InstallationState(current = first), after = InstallationState(current = second, previous = first))))
                fs.write(store.paths.state) { writeUtf8(Serialization.json.encodeToString(InstallationState.serializer(), InstallationState(current = second, previous = first))) }
            }
            store.lock(exclusive = true).use { assertTrue(store.recoverInterrupted { recoverInstallationService(store, it) }) }
            assertEquals(first, store.state()?.current)
            assertNull(store.pendingServiceRestart(), "Restoring the old selection leaves the running service consistent")

            // Crash after the selection committed and before the restart: the next launch finds the pending record.
            store.lock(exclusive = false).use { assertEquals(ServiceOutcome.DEFERRED, select(store, second, service).service) }
            assertEquals(first, store.pendingServiceRestart()?.version)
            assertEquals(PendingRestart.RESTARTED, applyPendingServiceRestart(store, controls(service)) { })
            assertEquals(second.version, service.serving)

            // Crash during the restart itself: the journal restores the payload the service ran, as an older launcher would.
            select(store, first, service, rollback = true)
            store.lock(exclusive = false).use { select(store, second, service) }
            val record = requireNotNull(store.pendingServiceRestart())
            store.writerLock().use {
                store.lock(exclusive = true).use {
                    assertFailsWith<IllegalStateException> {
                        store.restartService(record, prepare = {}, start = { error("simulated process death") }, recover = { })
                    }
                }
            }
            assertEquals(record.version, store.state()?.current, "A failed deferred restart selects the payload the service runs")
            assertNull(store.journal())
            assertNull(store.pendingServiceRestart())
        }
    }

    @Test fun aFailedDeferredRestartRestoresTheServicePayloadAndReportsIt() = runTest {
        scratch { store, first, second ->
            val service = FakeService(first.version)
            select(store, first, service)
            store.lock(exclusive = false).use { select(store, second, service) }
            service.events.clear()
            service.failStartOn = second.version
            val messages = mutableListOf<String>()
            assertEquals(PendingRestart.WAITING, applyPendingServiceRestart(store, controls(service)) { messages += it })
            assertEquals(listOf("stop", "start", "failed ${second.version}", "stop", "start", "serving ${first.version}"), service.events)
            assertEquals(InstallationState(current = first, previous = second), store.state())
            assertNull(store.journal())
            assertTrue(messages.any { "could not restart" in it }, messages.toString())
        }
    }

    @Test fun aStoppedOrAlreadyRestartedServiceIsNotRestartedAgain() = runTest {
        scratch { store, first, second ->
            val service = FakeService(first.version)
            select(store, first, service)
            store.lock(exclusive = false).use { select(store, second, service) }
            service.events.clear()
            service.serving = second.version // e.g. teamcode service restart while the sessions ran
            assertEquals(PendingRestart.RESTARTED, applyPendingServiceRestart(store, controls(service)) { })
            assertEquals(emptyList(), service.events)
            assertEquals(second, store.serviceRecord()?.version)
            select(store, first, service, rollback = true)
            store.lock(exclusive = false).use { select(store, second, service) }
            service.events.clear()
            service.running = false
            assertEquals(PendingRestart.RESTARTED, applyPendingServiceRestart(store, controls(service)) { })
            assertEquals(emptyList(), service.events, "A stopped service starts on the selection whenever it starts again")
            assertNull(store.serviceRecord())
        }
    }

    @Test fun aRollbackWhileSessionsRunToTheServicePayloadNeedsNoRestart() = runTest {
        scratch { store, first, second ->
            val service = FakeService(first.version)
            select(store, first, service)
            session(store, first).use {
                assertEquals(ServiceOutcome.DEFERRED, select(store, second, service).service)
                service.events.clear()
                assertEquals(ServiceOutcome.NONE, select(store, first, service, rollback = true).service)
                assertNull(store.pendingServiceRestart())
                assertEquals(ServiceOutcome.NONE, select(store, first, service).service, "Reselecting the current payload changes nothing")
                assertEquals(emptyList(), service.events)
            }
        }
    }

    @Test fun garbageCollectionNeverRemovesAPayloadWhileASessionHoldsItsLease() = runTest {
        scratch { store, first, second ->
            val service = FakeService(first.version)
            select(store, first, service)
            // An older payload that is neither selected, the rollback target nor the service's payload.
            val stale = store.paths.versions / "a".repeat(64)
            fs.createDirectory(stale)
            fs.write(stale / store.paths.executableName) { writeUtf8("old") }
            val lease = session(store, first)
            select(store, second, service)
            assertTrue(fs.metadataOrNull(stale) != null, "Collection must wait until no agent session runs")
            lease.close()
            assertEquals(PendingRestart.RESTARTED, applyPendingServiceRestart(store, controls(service)) { })
            assertNull(fs.metadataOrNull(stale))
            store.validate(first); store.validate(second)
            assertTrue(fs.list(store.paths.root).none { it.name.startsWith(".trash-") })
            assertTrue(fs.list(store.paths.sessions).none { it.name.endsWith(".lock") })
        }
    }

    @Test fun theServicePayloadIsRetainedUntilItsRestart() = runTest {
        scratch { store, first, second ->
            val service = FakeService(first.version)
            select(store, first, service)
            store.lock(exclusive = false).use { select(store, second, service) }
            // The service still runs a payload that is neither selected nor the rollback target.
            val running = InstalledVersion("0.0.1", "linux-x64", "b".repeat(64), "c".repeat(64), 3)
            fs.createDirectory(store.paths.directory(running))
            store.saveServiceRecord(ServiceRecord(serviceConfig = config, version = running))
            val unused = store.paths.versions / "a".repeat(64)
            fs.createDirectory(unused)
            store.writerLock().use { store.lock(exclusive = true).use { assertEquals(listOf("a".repeat(64)), store.collectGarbage()) } }
            assertTrue(fs.metadataOrNull(store.paths.directory(running)) != null)
            store.validate(first); store.validate(second)
        }
    }

    @Test fun staleSessionFilesAreNotCounted() = runTest {
        scratch { store, first, _ ->
            val live = store.openSession(first.manifestSha256)
            fs.createDirectories(store.paths.sessions)
            writePrivateFile((store.paths.sessions / "${first.manifestSha256}-${"0".repeat(32)}.lock").toString(), "")
            assertEquals(mapOf(first.manifestSha256 to 1), store.liveSessions())
            live.close()
            assertEquals(emptyMap(), store.liveSessions())
            assertFailsWith<IllegalArgumentException> { store.openSession("../escape") }
        }
    }
}
