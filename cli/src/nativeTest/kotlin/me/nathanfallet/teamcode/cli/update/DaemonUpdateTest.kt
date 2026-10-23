package me.nathanfallet.teamcode.cli.update

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import me.nathanfallet.teamcode.cli.daemon.DeviceDaemonTest
import me.nathanfallet.teamcode.cli.daemon.FakeDaemonServer
import me.nathanfallet.teamcode.cli.platform.secureRandomBytes
import okio.ByteString.Companion.decodeBase64
import okio.ByteString.Companion.toByteString
import okio.FileSystem
import kotlin.coroutines.ContinuationInterceptor
import kotlin.test.*

/**
 * Decision 12: `teamcode update` while the daemon hosts sessions. Its hosts hold no installation lease, so the service
 * restarts at once: the daemon pauses its sessions, the new version starts and resumes them. A failed start restores the
 * previous version, which resumes them; a local session through the proxy still defers the restart.
 */
class DaemonUpdateTest {
    private val fs = FileSystem.SYSTEM
    private val config = (FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "teamcode-config" / "config.json").toString()

    /** The local service as the OS runs it: a daemon on the selected payload's version, stopped and started by the update. */
    private class DaemonService(private val scope: CoroutineScope, private val store: InstallationStore, private val daemon: DeviceDaemonTest.Fixture) : ManagedServiceControl {
        val events = mutableListOf<String>()
        var failStartOn: String? = null
        private var job: Job? = null
        private var serving: String? = null
        override suspend fun running() = job?.isActive == true
        override suspend fun serving(version: String) = running() && serving == version
        override suspend fun stop() { events += "stop"; daemon.stop = true; job?.join(); job = null; serving = null }
        override suspend fun start() {
            val version = requireNotNull(store.state()).current.version
            events += "start $version"
            if (version == failStartOn) return // This payload does not start.
            job = daemon.start(scope, version); serving = version
        }
        override suspend fun await(version: String) {
            if (serving != version) { events += "failed $version"; error("fixture startup failure") }
            events += "serving $version"
        }
    }

    private fun bytes(value: String) = requireNotNull(value.decodeBase64()).toByteArray()
    private suspend fun stage(store: InstallationStore, newer: Boolean) =
        store.stage(bytes(if (newer) ReleaseFixtures.newerPayload else ReleaseFixtures.validPayload),
            bytes(if (newer) ReleaseFixtures.newerEnvelope else ReleaseFixtures.validEnvelope)) { fs.write(it) { writeUtf8(ReleaseFixtures.binaryText) } }
    private suspend fun select(store: InstallationStore, version: InstalledVersion, service: DaemonService) = store.writerLock().use {
        stage(store, newer = version.version != stage(store, newer = false).version)
        activateInstallation(store, version, false, ActivationService(config, service), { path -> if (path == config) service else null }, waitMillis = 1_000)
    }
    private fun TestScope.dispatcher() = coroutineContext[ContinuationInterceptor] as CoroutineDispatcher
    private suspend fun waitUntil(condition: () -> Boolean) = withTimeout(60_000) { while (!condition()) delay(5) }

    private fun scenario(block: suspend TestScope.(InstallationStore, InstalledVersion, InstalledVersion, DeviceDaemonTest.Fixture, DaemonService) -> Unit) = runTest {
        val root = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / ("teamcode-daemon-update-" + secureRandomBytes(12).toByteString().hex())
        fs.createDirectories(root / "home" / "project")
        try {
            val canonical = fs.canonicalize(root)
            val store = InstallationStore(InstallationPaths(canonical / "install", "linux-x64"), verifier = VerifyReleaseManifest(listOf(ReleaseFixtures.firstKey)))
            store.initialize()
            val first = stage(store, newer = false)
            val second = stage(store, newer = true)
            store.ensureLauncher(first)
            val daemon = DeviceDaemonTest.Fixture(canonical, canonical / "home", FakeDaemonServer(dispatcher()))
            val service = DaemonService(this, store, daemon)
            select(store, first, service)
            val folder = daemon.folder("project")
            daemon.server.queue("s1", folder.id, "project")
            waitUntil { daemon.hosts.started.size == 1 }
            daemon.server.events.clear(); service.events.clear()
            block(store, first, second, daemon, service)
            daemon.stop = true
            service.stop()
        } finally { if (fs.metadataOrNull(root) != null) fs.deleteRecursively(root) }
    }

    @Test fun anUpdateWhileTheDaemonHostsASessionPausesRestartsOnTheNewVersionAndResumes() = scenario { store, _, second, daemon, service ->
        val activation = select(store, second, service)
        assertEquals(ServiceOutcome.RESTARTED, activation.service, "A daemon's hosted session does not defer the restart")
        assertEquals(emptyMap(), activation.sessions, "Nor counts as an agent session of the old payload")
        assertEquals(listOf("stop", "start ${second.version}", "serving ${second.version}"), service.events)
        waitUntil { daemon.hosts.started.size == 2 }
        assertEquals(listOf("pause s1", "checkpoint s1", "offline", "resume s1", "host s1 2"), daemon.server.events.filter { !it.startsWith("publish") })
        assertEquals(second.version, daemon.server.versions.last(), "The server sees the daemon come back with its new version")
        assertNull(store.pendingServiceRestart())
    }

    @Test fun aNewVersionThatDoesNotStartIsRolledBackAndTheSessionResumesOnTheOldOne() = scenario { store, first, second, daemon, service ->
        service.failStartOn = second.version
        assertFails { select(store, second, service) }
        assertEquals(first, store.state()?.current, "The previous version is selected again")
        assertNull(store.journal())
        assertEquals(listOf("stop", "start ${second.version}", "failed ${second.version}", "stop", "start ${first.version}", "serving ${first.version}"), service.events)
        waitUntil { daemon.hosts.started.size == 2 }
        assertEquals(listOf("pause s1", "checkpoint s1", "offline", "resume s1", "host s1 2"), daemon.server.events.filter { !it.startsWith("publish") })
        assertEquals(first.version, daemon.server.versions.last())
    }

    @Test fun aLocalSessionThroughTheProxyStillDefersTheRestart() = scenario { store, first, second, daemon, service ->
        // What `teamcode run` holds for its whole lifetime.
        val lease = AgentInstallationLease(store, store.lock(exclusive = false), store.openSession(first.manifestSha256))
        val activation = select(store, second, service)
        assertEquals(ServiceOutcome.DEFERRED, activation.service)
        assertEquals(emptyList(), service.events, "The service the local session depends on keeps running")
        delay(200)
        assertTrue(daemon.server.events.none { it.startsWith("pause") }, "The daemon's session keeps running too")
        lease.close()
        assertEquals(PendingRestart.RESTARTED, applyPendingServiceRestart(store, { path -> if (path == config) service else null }) { })
        waitUntil { daemon.hosts.started.size == 2 }
        assertEquals(listOf("pause s1", "checkpoint s1", "offline", "resume s1", "host s1 2"), daemon.server.events.filter { !it.startsWith("publish") })
        assertEquals(second.version, daemon.server.versions.last())
    }
}
