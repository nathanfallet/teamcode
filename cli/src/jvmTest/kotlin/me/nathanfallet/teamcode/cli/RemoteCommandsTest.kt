package me.nathanfallet.teamcode.cli

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import me.nathanfallet.teamcode.api.Serialization
import me.nathanfallet.teamcode.api.models.*
import me.nathanfallet.teamcode.cli.cloud.line
import me.nathanfallet.teamcode.cli.config.configStore
import me.nathanfallet.teamcode.cli.platform.DetachedProcess
import me.nathanfallet.teamcode.cli.remote.*
import me.nathanfallet.teamcode.client.TeamCodeClient
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import kotlin.test.*

/** `teamcode remote resume|stop|list`, background hosts and the KIND/HOST columns, against a fake server. */
class RemoteCommandsTest {
    private val id = "8f3c0a2e-1111-4222-8333-944455556666"

    private fun session(epoch: Int = 1, online: Boolean = false, kind: String = CloudSessionKinds.REMOTE, state: String = "paused") = CloudSessionView(
        id = id, name = "paywall", ownerId = "m1", createdBy = CloudAttributionView("m1", "c1", "m1"), state = state, desiredState = "paused",
        generation = 1, currentEpoch = epoch, profile = CloudProfileRef("remote", 0), image = CloudImageView("remote"), agent = CloudAgentView("claude"),
        activity = CloudActivityView(0, 60), resume = CloudResumeView("kept", "kept"), workspaceDeletion = CloudWorkspaceDeletionView("not_applicable"),
        createdAt = 0, updatedAt = 0, kind = kind,
        host = if (kind == CloudSessionKinds.REMOTE) CloudSessionHostView("d1", "Work laptop", "macos", "15.1", "mon-projet", online, permissionMode = "ask") else null,
    )

    private fun client(handler: (path: String) -> Pair<HttpStatusCode, String>) = TeamCodeClient("https://team.example", { "synthetic-token" }) { configure ->
        HttpClient(MockEngine { request ->
            val (status, body) = handler(request.url.encodedPath)
            respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))
        }) { configure(this) }
    }

    private inline fun <T> withRoot(block: (Path, RemoteSessions) -> T): T {
        val root = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "teamcode-remote-${kotlin.random.Random.nextLong().toULong()}"
        try { return block(root, RemoteSessions(root / "remote", configStore((root / "config.json").toString()))) }
        finally { FileSystem.SYSTEM.deleteRecursively(root, mustExist = false) }
    }

    @Test fun aHostWaitsForAPausingSessionThenTakesIt() = runTest {
        var calls = 0
        val host = CloudHostView("t1", 3, session(epoch = 3))
        val reports = mutableListOf<String>()
        client { calls++; if (calls < 3) HttpStatusCode.Conflict to """{"error":{"type":"session_pausing","message":"pausing"}}"""
            else HttpStatusCode.OK to Serialization.json.encodeToString(host) }.use {
            assertEquals(3, takeSession(it, id, { r -> reports += r }, retryMillis = 1).epoch)
        }
        assertEquals(3, calls)
        assertEquals(1, reports.size, "Said once that it waits")

        calls = 0
        client { calls++; HttpStatusCode.Conflict to """{"error":{"type":"remote_host_mismatch","message":"elsewhere"}}""" }.use {
            val error = assertFailsWith<com.github.ajalt.clikt.core.CliktError> { takeSession(it, id, { }, retryMillis = 1) }
            assertTrue("remote_host_mismatch" in error.message.orEmpty())
        }
        assertEquals(1, calls, "Any other refusal is final")
    }

    @Test fun aBackgroundHostRunsTheHostCommandAndWaitsUntilItIsOnline() = runTest {
        withRoot { _, sessions ->
            var polls = 0
            var started: Pair<String, List<String>>? = null
            val reports = mutableListOf<String>()
            client { polls++; HttpStatusCode.OK to Serialization.json.encodeToString(session(epoch = if (polls < 2) 1 else 2, online = polls >= 2)) }.use { client ->
                run {
                    startBackgroundHost(client, RemoteSessionRecord(id, "/Users/me/project", "claude"), previousEpoch = 1, report = { reports += it },
                        start = { command, arguments, log ->
                            assertTrue(FileSystem.SYSTEM.exists(log.toPath()), "The log exists before the child opens it")
                            started = command to arguments
                            object : DetachedProcess { override fun exitCode(): Int? = null }
                        }, executable = { "/opt/teamcode" }, pollMillis = 1, timeoutMillis = 1_000, sessions = sessions)
                }
            }
            assertEquals("/opt/teamcode" to listOf("remote", "host", id), started)
            assertEquals(2, polls, "The epoch it had before is not the background host's")
            assertTrue(reports.single().startsWith("The host runs in the background"), reports.toString())
        }
    }

    @Test fun aBackgroundHostThatExitsFirstNamesItsLog() = runTest {
        withRoot { _, sessions ->
            client { HttpStatusCode.OK to Serialization.json.encodeToString(session()) }.use { client ->
                val error = assertFailsWith<CliUserException> {
                    run {
                        startBackgroundHost(client, RemoteSessionRecord(id, "/Users/me/project", "claude"), 1, { },
                            start = { _, _, _ -> object : DetachedProcess { override fun exitCode() = 2 } }, executable = { "/opt/teamcode" },
                            pollMillis = 1, timeoutMillis = 1_000, sessions = sessions)
                    }
                }
                assertTrue("exit code 2" in error.message && "$id.log" in error.message, error.message)
            }
        }
    }

    @Test fun recordsArePrunedOnlyForEndedSessionsAndLogsStayBounded() = withRoot { root, sessions ->
        val kept = RemoteSessionRecord(id, "/Users/me/project", "claude")
        val ended = RemoteSessionRecord("0a1b2c3d-1111-4222-8333-944455556666", "/Users/me/old", "codex")
        sessions.save(kept); sessions.save(ended)
        val log = sessions.log(ended.sessionId).toPath()
        FileSystem.SYSTEM.write(log) { writeUtf8("x".repeat(10)) }
        assertEquals(log.toString(), sessions.log(ended.sessionId), "A small log is kept")
        assertEquals(10, FileSystem.SYSTEM.metadata(log).size)
        FileSystem.SYSTEM.write(log) { writeUtf8("x".repeat((RemoteSessions.MAX_LOG_BYTES + 1).toInt())) }
        sessions.log(ended.sessionId)
        assertEquals(0, FileSystem.SYSTEM.metadata(log).size, "A log past 1 MiB starts again")
        sessions.prune(setOf(ended.sessionId, "../config"))
        assertNull(sessions.load(ended.sessionId))
        assertFalse(FileSystem.SYSTEM.exists(log))
        assertEquals(kept, sessions.load(kept.sessionId))
        assertTrue(FileSystem.SYSTEM.exists(root / "config.lock"), "Written under config.lock")
    }

    @Test fun rowsSayTheKindAndWhereASessionRuns() {
        val remote = session(online = true)
        assertEquals(listOf(id, "paused", "paywall", "claude", "online", "/Users/me/project"),
            remoteLine(remote, RemoteSessionRecord(id, "/Users/me/project", "claude"), showDevice = false).split('\t'))
        assertEquals(listOf(id, "paused", "paywall", "claude", "online", "Work laptop", "mon-projet"), remoteLine(remote, null, showDevice = true).split('\t'),
            "Without a record of this device, only the directory's name")
        assertEquals(listOf("remote", "on Work laptop (macos), online"), remote.line().split('\t').takeLast(2))
        assertEquals(listOf("cloud", "profile remote"), session(kind = CloudSessionKinds.CLOUD).line().split('\t').takeLast(2))
        assertTrue(hosted(session(epoch = 2, online = true), previousEpoch = 1))
        assertFalse(hosted(session(epoch = 1, online = true), previousEpoch = 1), "Still the previous host")
        assertFalse(hosted(session(epoch = 2, online = false), previousEpoch = 1))
    }
}
