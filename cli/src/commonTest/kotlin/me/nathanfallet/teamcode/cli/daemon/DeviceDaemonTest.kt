package me.nathanfallet.teamcode.cli.daemon

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import me.nathanfallet.teamcode.api.models.CloudAgentRequest
import me.nathanfallet.teamcode.api.models.CloudSessionKinds
import me.nathanfallet.teamcode.api.models.CreateCloudSessionRequest
import me.nathanfallet.teamcode.cli.CliUserException
import me.nathanfallet.teamcode.cli.config.configStore
import me.nathanfallet.teamcode.cli.remote.RemoteSessions
import me.nathanfallet.teamcode.cli.remote.awaitDaemonHost
import me.nathanfallet.teamcode.cli.remote.daemonDevice
import me.nathanfallet.teamcode.cli.remote.startThroughDaemon
import okio.FileSystem
import okio.Path
import kotlin.coroutines.ContinuationInterceptor
import kotlin.test.*

/** The daemon against a fake server: orders, refusals, supervision, pause on stop and resume on start (decision 5). */
class DeviceDaemonTest {
    internal class Fixture(val root: Path, val home: Path, val server: FakeDaemonServer) {
        // Not created beforehand: on Windows the private writes create it with the user as its owner, and refuse a directory
        // owned by someone else (an elevated runner's Administrators).
        private val config = root / "config"
        private val lock = configStore((config / "config.json").toString())
        val folders = FolderStore(config / "folders.json", lock)
        val records = RemoteSessions(config / "remote", lock)
        val state = DaemonSessions(config / "daemon-sessions.json", lock)
        val hosts = server.Hosts()
        val reports = mutableListOf<String>()
        var stop = false

        fun folder(name: String): LocalFolder {
            FileSystem.SYSTEM.createDirectories(home / name)
            return folders.add(folderPath((home / name).toString(), home, windows = false), null).first
        }

        fun daemon(version: String = "1.0.0") = DeviceDaemon(server.client(), folders, records, state, hosts, version, { reports += it }, { stop }, home,
            pollWaitMillis = 50, tickMillis = 10, stopWaitMillis = 5_000, retryMillis = 100)

        /** Runs a daemon until [stop], in [scope]. */
        fun start(scope: CoroutineScope, version: String = "1.0.0"): Job { stop = false; return scope.launch { daemon(version).run() } }
    }

    private fun TestScope.dispatcher() = coroutineContext[ContinuationInterceptor] as CoroutineDispatcher

    private fun test(block: suspend TestScope.(Fixture) -> Unit) = scratch { root, home ->
        runTest { block(Fixture(root, home, FakeDaemonServer(dispatcher()))) }
    }

    private suspend fun waitUntil(condition: () -> Boolean) = withTimeout(60_000) { while (!condition()) delay(5) }

    @Test fun anOrderIsAcceptedHostedPausedOnStopAndResumedOnTheNextStart() = test { f ->
        val folder = f.folder("project")
        f.server.queue("s1", folder.id, "project", permissionMode = "bypass")
        val first = f.start(this)
        waitUntil { f.hosts.started.size == 1 }
        assertEquals(Triple("s1", 1, "t1"), f.hosts.started.single(), "Hosted on the epoch its acceptance opened, with the session's team")
        assertEquals("publish ${folder.id}", f.server.events.first(), "The folders are published before anything else")
        assertEquals(listOf(DeviceFolderViewOf(folder.id, "project", "~${Path.DIRECTORY_SEPARATOR}project")), f.server.folders.map { DeviceFolderViewOf(it.id, it.name, it.label) })
        assertEquals((f.home / "project").toString(), f.records.load("s1")?.directory, "The session's local record, as remote start writes it")
        assertEquals(listOf(DaemonSession("s1", 1, DaemonSession.HOSTING)), f.state.read())
        assertEquals("1.0.0", f.server.versions.last())

        f.stop = true
        first.join()
        val stopping = f.server.events.dropWhile { it != "pause s1" }
        assertEquals(listOf("pause s1", "checkpoint s1", "offline"), stopping, "Paused, checkpointed by its host, then offline")
        assertEquals(listOf(DaemonSession("s1", 1, DaemonSession.PAUSED)), f.state.read())
        assertFalse(f.server.daemonOnline)

        val second = f.start(this, version = "2.0.0")
        waitUntil { f.hosts.started.size == 2 }
        assertEquals(Triple("s1", 2, "t1"), f.hosts.started.last())
        val restarted = f.server.events.dropWhile { it != "offline" }.drop(1).filter { !it.startsWith("publish") }
        assertEquals(listOf("resume s1", "host s1 2"), restarted, "Resumed by the new daemon, then hosted again from its queue")
        assertEquals("2.0.0", f.server.versions.last(), "The server sees the new version at its poll")
        f.stop = true
        second.join()
    }

    @Test fun ordersOutsideTheDaemonsFoldersAreRefusedWithAReason() = test { f ->
        val kept = f.folder("kept")
        val moved = f.folder("moved")
        FileSystem.SYSTEM.createDirectories(f.root / "elsewhere" / "moved")
        f.server.queue("unknown", "not-a-folder", "secret")
        f.server.queue("renamed", kept.id, "other-name")
        f.server.queue("agent", kept.id, "kept", agent = "unknown-agent")
        val linked = try {
            FileSystem.SYSTEM.deleteRecursively(f.home / "moved")
            FileSystem.SYSTEM.createSymlink(f.home / "moved", f.root / "elsewhere" / "moved")
            true
        } catch (_: Exception) { false } // No symbolic links for this user (Windows without the privilege).
        if (linked) f.server.queue("moved", moved.id, "moved")
        val daemon = f.start(this)
        waitUntil { f.server.events.count { it.startsWith("refuse") } == if (linked) 4 else 3 }
        f.stop = true
        daemon.join()
        assertTrue("refuse unknown folder_not_configured" in f.server.events, f.server.events.toString())
        assertTrue("refuse renamed folder_mismatch" in f.server.events)
        assertTrue("refuse agent agent_unsupported" in f.server.events)
        if (linked) assertTrue("refuse moved folder_moved" in f.server.events, "A folder replaced by a link pointing elsewhere is refused")
        assertTrue(f.hosts.started.isEmpty(), "Nothing refused is hosted")
        assertTrue(f.server.events.none { it.startsWith("host") })
    }

    @Test fun aHostThatDiesIsStartedAgainThenGivenUpAfterThreeStarts() = test { f ->
        val folder = f.folder("project")
        f.server.queue("s1", folder.id, "project")
        val daemon = f.start(this)
        waitUntil { f.hosts.started.size == 1 }
        f.hosts.crash += "s1"
        waitUntil { f.hosts.started.size == 2 }
        assertEquals(2, f.hosts.started.last().second, "A new epoch for the new host")
        f.hosts.crash += "s1"
        waitUntil { f.hosts.started.size == 3 }
        f.hosts.crash += "s1"
        waitUntil { f.reports.any { "not starting it again" in it } }
        assertEquals(3, f.hosts.started.size, "At most three starts in the window")
        assertTrue(f.state.read().isEmpty())
        // Back in the queue later (its epoch was lost before it was ready): refused instead of looping.
        f.server.sessions.getValue("s1").apply { phase = "waiting_host"; state = "preparing" }
        waitUntil { "refuse s1 host_failed" in f.server.events }
        f.stop = true
        daemon.join()
    }

    @Test fun aCrashedDaemonsSessionResumesOnlyWhenTheServerPausedItForItsLostHost() = test { f ->
        val folder = f.folder("project")
        f.server.queue("lost", folder.id, "project")
        f.server.queue("user", folder.id, "project")
        f.server.loseHost("lost")
        f.server.sessions.getValue("user").apply { state = "paused"; desiredState = "paused"; phase = null }
        f.state.put(DaemonSession("lost", 1, DaemonSession.HOSTING))
        f.state.put(DaemonSession("user", 1, DaemonSession.HOSTING))
        val daemon = f.start(this)
        waitUntil { f.hosts.started.isNotEmpty() }
        f.stop = true
        daemon.join()
        assertTrue("resume lost" in f.server.events)
        assertFalse("resume user" in f.server.events, "Paused by its owner meanwhile: not resumed")
        assertEquals(listOf("lost"), f.hosts.started.map { it.first })
    }

    @Test fun remoteStartGoesThroughTheDaemonWithTheDevicesId() = test { f ->
        val client = f.server.client()
        assertNull(daemonDevice(client, listOf(DAEMON_CAPABILITY), windows = false), "No daemon online: remote start hosts the session itself")
        val daemon = f.start(this)
        waitUntil { f.server.daemonOnline }
        assertNull(daemonDevice(client, emptyList(), windows = false), "A server without the capability")
        assertNull(daemonDevice(client, listOf(DAEMON_CAPABILITY), windows = true), "Not hosted on Windows")
        val device = assertNotNull(daemonDevice(client, listOf(DAEMON_CAPABILITY), windows = false))
        FileSystem.SYSTEM.createDirectories(f.home / "app")
        val request = CreateCloudSessionRequest(name = "app", kind = CloudSessionKinds.REMOTE, agent = CloudAgentRequest(null, null, "codex"),
            directoryName = "app", permissionMode = "ask")
        val reports = mutableListOf<String>()
        val session = startThroughDaemon(client, device, f.home / "app", request, "codex", f.home, false, "/opt/tools/bin:relative", { reports += it },
            folders = f.folders, sessions = f.records)
        val folder = f.folders.read().folders.single()
        assertEquals((f.home / "app").toString(), folder.path, "The directory became one of the device's folders")
        assertEquals("/opt/tools/bin:relative", f.folders.read().searchPath)
        assertTrue(reports.single().startsWith("Added "), reports.toString())
        val created = f.server.createdRequests.single()
        assertEquals("d1", created.deviceId)
        assertEquals(folder.id, created.folderId)
        assertNull(created.directoryName, "The folder's name is the directory's")
        assertEquals((f.home / "app").toString(), f.records.load(session.id)?.directory)
        awaitDaemonHost(client, session.id, session.currentEpoch, { reports += it }, pollMillis = 10)
        assertEquals("This device's daemon hosts it.", reports.last())
        assertEquals(session.id, f.hosts.started.single().first)

        // The same directory again: the same folder, not a second one.
        startThroughDaemon(client, device, f.home / "app", request, "codex", f.home, false, null, { reports += it }, folders = f.folders, sessions = f.records)
        assertEquals(1, f.folders.read().folders.size)
        assertEquals("/opt/tools/bin:relative", f.folders.read().searchPath, "Kept when the terminal has no PATH")
        assertFailsWith<CliUserException> { startThroughDaemon(client, device, f.home, request, "codex", f.home, false, null, { }, folders = f.folders, sessions = f.records) }
        f.stop = true
        daemon.join()
    }

    @Test fun aRefusedDaemonSessionIsAnErrorForRemoteStart() = test { f ->
        val client = f.server.client()
        f.server.queue("gone", "not-a-folder", "x")
        val daemon = f.start(this)
        val error = assertFailsWith<CliUserException> { awaitDaemonHost(client, "gone", 0, { }, pollMillis = 10) }
        assertTrue("folder_not_configured" in error.message, error.message)
        f.stop = true
        daemon.join()
    }

    private data class DeviceFolderViewOf(val id: String, val name: String, val label: String?)
}
