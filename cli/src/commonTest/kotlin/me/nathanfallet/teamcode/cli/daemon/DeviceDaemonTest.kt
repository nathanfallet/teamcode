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
import me.nathanfallet.teamcode.api.models.DaemonFeatures
import me.nathanfallet.teamcode.api.models.DaemonListingAnswerRequest
import me.nathanfallet.teamcode.api.models.DaemonListingView
import me.nathanfallet.teamcode.cli.cloud.agent.AgentState
import me.nathanfallet.teamcode.cli.CliUserException
import me.nathanfallet.teamcode.cli.config.configStore
import me.nathanfallet.teamcode.cli.remote.RemoteSessionRecord
import me.nathanfallet.teamcode.cli.remote.RemoteSessions
import me.nathanfallet.teamcode.cli.remote.daemonOnline
import me.nathanfallet.teamcode.cli.remote.localHostWaker
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

        var lostCheckMillis = 30_000L

        /** This device's Claude Code projects, in the scratch directory. */
        val claude = root / "claude-projects"
        val transcripts = ClaudeTranscripts(claude, windows = false)

        /** A Claude Code conversation that started in [directory]. */
        fun conversation(directory: Path, id: String, prompt: String = "Fix the build", cwd: String = directory.toString()) {
            val project = claude / ClaudeTranscripts.projectName(directory.toString())
            FileSystem.SYSTEM.createDirectories(project)
            FileSystem.SYSTEM.write(project / "$id.jsonl") {
                writeUtf8("""{"type":"user","cwd":"$cwd","message":{"role":"user","content":"$prompt"}}""" + "\n")
            }
        }

        fun daemon(version: String = "1.0.0") = DeviceDaemon(server.client(), folders, records, state, hosts, version, { reports += it }, { stop }, home,
            transcripts = transcripts, pollWaitMillis = 50, tickMillis = 10, stopWaitMillis = 5_000, retryMillis = 100, lostCheckMillis = lostCheckMillis)

        /** Runs a daemon until [stop], in [scope]. */
        fun start(scope: CoroutineScope, version: String = "1.0.0"): Job { stop = false; return scope.launch { daemon(version).run() } }
    }

    private fun TestScope.dispatcher() = coroutineContext[ContinuationInterceptor] as CoroutineDispatcher

    private fun test(block: suspend TestScope.(Fixture) -> Unit) = scratch { root, home ->
        runTest { block(Fixture(root, home, FakeDaemonServer(dispatcher()))) }
    }

    private suspend fun waitUntil(condition: () -> Boolean) = withTimeout(60_000) { while (!condition()) delay(5) }

    @Test fun aMemberWaitingForAFoldersConversationsGetsOnlyThatFolders() = test { f ->
        val folder = f.folder("project")
        val other = f.folder("other")
        f.conversation(f.home / "project", ID_A, "Fix the build")
        f.conversation(f.home / "other", ID_B, "Elsewhere")
        f.server.listings += DaemonListingView("r1", folder.id, 20)
        f.server.listings += DaemonListingView("r2", "unknown", 20)
        f.server.listings += DaemonListingView("r3", other.id, 1, before = 0)
        val job = f.start(this)
        waitUntil { f.server.answers.size == 3 }
        assertEquals(listOf(DaemonFeatures.RESUME), f.server.features.first(), "The daemon announces what it can do")
        val answer = f.server.answers.getValue("r1")
        assertEquals(listOf(ID_A to "Fix the build"), answer.sessions.map { it.id to it.title })
        assertNull(answer.refused)
        assertEquals(DaemonListingAnswerRequest(refused = RefusalReasons.FOLDER_NOT_CONFIGURED), f.server.answers.getValue("r2"))
        assertEquals(emptyList(), f.server.answers.getValue("r3").sessions, "Nothing older than the cursor")
        assertTrue(f.reports.none { "Fix the build" in it }, "A title is never written in the daemon's log")
        f.stop = true
        job.join()
    }

    @Test fun anOrderContinuingAConversationIsCheckedThenForkedByItsHost() = test { f ->
        val folder = f.folder("project")
        f.folder("other")
        f.conversation(f.home / "project", ID_A)
        f.conversation(f.home / "other", ID_B)
        f.server.queue("s1", folder.id, "project", resume = ID_A)
        f.server.queue("s2", folder.id, "project", resume = ID_B)
        f.server.queue("s3", folder.id, "project", agent = "codex", resume = ID_A)
        f.server.queue("s4", folder.id, "project", resume = "not-a-uuid")
        val job = f.start(this)
        waitUntil { f.hosts.started.size == 1 && f.server.events.count { it.startsWith("refuse") } == 3 }
        assertEquals("s1", f.hosts.started.single().first)
        assertEquals(AgentState("claude", null, ID_A, fork = true), f.records.conversation("s1").read(), "The host forks the device's conversation at its first start")
        assertTrue("refuse s2 ${RefusalReasons.RESUME_NOT_FOUND}" in f.server.events, "Another folder's conversation is refused")
        assertTrue("refuse s3 ${RefusalReasons.RESUME_NOT_FOUND}" in f.server.events, "Codex continues no Claude Code conversation")
        assertTrue("refuse s4 ${RefusalReasons.RESUME_NOT_FOUND}" in f.server.events)
        assertNull(f.records.conversation("s2").read())

        // Once hosted, a later order of the session (here a lost host) never goes back to the device's conversation.
        f.records.conversation("s1").write(AgentState("claude", null, "forked-1"))
        f.stop = true
        job.join()
        val again = f.start(this)
        waitUntil { f.hosts.started.size == 2 }
        assertEquals(AgentState("claude", null, "forked-1"), f.records.conversation("s1").read())
        f.stop = true
        again.join()
    }

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

    @Test fun aHostThatStopsAnsweringIsReplacedOnceTheServerPausesItsSession() = test { f ->
        f.lostCheckMillis = 20
        val folder = f.folder("project")
        f.server.queue("silent", folder.id, "project")
        f.server.queue("owner", folder.id, "project")
        val daemon = f.start(this)
        waitUntil { f.hosts.started.size == 2 }
        // Both hosts hang. The server pauses one for its lost host; the owner pauses the other, whose host is gone too.
        f.hosts.hang += listOf("silent", "owner")
        f.server.loseHost("silent")
        f.server.sessions.getValue("owner").apply { state = "paused"; desiredState = "paused"; phase = null; online = false }
        waitUntil { f.hosts.started.size == 3 }
        assertEquals(Triple("silent", 2, "t1"), f.hosts.started.last(), "A new epoch, which fences the silent host")
        assertTrue("resume silent" in f.server.events)
        assertTrue(f.reports.any { "session silent stopped answering; resuming it" in it }, f.reports.toString())
        f.hosts.hang.clear()
        f.stop = true
        daemon.join()
        assertFalse("resume owner" in f.server.events, "Paused by its owner: not resumed")
    }

    @Test fun aSilentHostIsReplacedAtMostThreeTimesInTheWindow() = test { f ->
        f.lostCheckMillis = 20
        val folder = f.folder("project")
        f.server.queue("s1", folder.id, "project")
        f.hosts.hang += "s1"
        val daemon = f.start(this)
        for (count in 1..3) {
            waitUntil { f.hosts.started.size == count }
            f.server.loseHost("s1")
        }
        waitUntil { f.reports.any { "stopped answering; not starting it again" in it } }
        f.hosts.hang.clear()
        f.stop = true
        daemon.join()
        assertEquals(3, f.hosts.started.size)
        assertEquals(2, f.server.events.count { it == "resume s1" })
        assertTrue(f.state.read().isEmpty())
    }

    @Test fun aTerminalWakesADaemonSessionOnlyWhileTheDaemonIsOffline() = test { f ->
        val folder = f.folder("project")
        f.server.queue("s1", folder.id, "project")
        f.records.save(RemoteSessionRecord("s1", (f.home / "project").toString(), "claude"))
        val client = f.server.client()
        assertFalse(daemonOnline(client, windows = false), "Never polled: offline")
        val online = suspend { daemonOnline(client, windows = false) }
        assertNotNull(localHostWaker(client, "s1", {}, f.records, online), "A stopped daemon takes nothing: the terminal hosts it")
        f.server.daemonOnline = true
        assertTrue(daemonOnline(client, windows = false))
        assertFalse(daemonOnline(client, windows = true), "No daemon hosts sessions on Windows")
        assertNull(localHostWaker(client, "s1", {}, f.records, online), "The daemon's queue wakes it")
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

    private companion object {
        const val ID_A = "0000000a-aaaa-4bbb-8ccc-00000000000a"
        const val ID_B = "0000000b-aaaa-4bbb-8ccc-00000000000b"
    }
}
