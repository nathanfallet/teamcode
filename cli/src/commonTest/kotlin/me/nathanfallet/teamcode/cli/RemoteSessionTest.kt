package me.nathanfallet.teamcode.cli

import kotlinx.serialization.json.*
import me.nathanfallet.teamcode.api.models.RemotePermissionModes
import me.nathanfallet.teamcode.cli.cloud.agent.*
import me.nathanfallet.teamcode.cli.daemon.isWindows
import me.nathanfallet.teamcode.cli.platform.pathStatus
import me.nathanfallet.teamcode.cli.remote.RemoteSessionRecord
import me.nathanfallet.teamcode.cli.remote.RemoteSessions
import me.nathanfallet.teamcode.cli.remote.refuseDirectory
import okio.FileSystem
import okio.Path.Companion.toPath
import kotlin.test.*

class RemoteSessionTest {
    private val claude = AgentSettings("claude", "2.1", "opus", null, permissionRelay = true)
    private val codex = AgentSettings("codex", "0.163", "gpt-5", null)

    @Test fun askKeepsClaudeChecksAndCodexSandbox() {
        val asked = ClaudeStreamJsonAdapter(claude.copy(permissionMode = RemotePermissionModes.ASK)).command(null).arguments
        assertFalse("--dangerously-skip-permissions" in asked, "Claude Code keeps its permission checks")
        assertEquals(listOf("--permission-prompt-tool", "stdio"), asked.subList(asked.indexOf("--permission-prompt-tool"), asked.indexOf("--permission-prompt-tool") + 2),
            "Its requests go to the team's clients")
        assertTrue("--dangerously-skip-permissions" in ClaudeStreamJsonAdapter(claude.copy(permissionMode = RemotePermissionModes.BYPASS)).command(null).arguments)
        assertTrue("--dangerously-skip-permissions" in ClaudeStreamJsonAdapter(claude).command(null).arguments, "A cloud pod is its own sandbox")

        fun sandbox(settings: AgentSettings): Pair<String, String> {
            val adapter = CodexAppServerAdapter(settings, "1.0")
            val thread = adapter.startup(null, "/home/me/project").map { Json.parseToJsonElement(it).jsonObject }.last()["params"]!!.jsonObject
            return adapter.command(null).arguments.last() to (thread["sandbox"] as JsonPrimitive).content
        }
        assertEquals("sandbox_mode=\"workspace-write\"" to "workspace-write", sandbox(codex.copy(permissionMode = RemotePermissionModes.ASK)))
        assertEquals("sandbox_mode=\"danger-full-access\"" to "danger-full-access", sandbox(codex.copy(permissionMode = RemotePermissionModes.BYPASS)))
        assertEquals("sandbox_mode=\"danger-full-access\"" to "danger-full-access", sandbox(codex))
    }

    @Test fun refusesRootsHomesAndSystemDirectories() {
        val home = "/Users/me".toPath()
        refuseDirectory("/Users/me/project".toPath(), home, windows = false)
        refuseDirectory("/Users/me/usr".toPath(), home, windows = false)
        refuseDirectory("/usrlocal".toPath(), home, windows = false)
        for (path in listOf("/", "/Users/me", "/usr", "/usr/local/bin", "/etc", "/System/Library", "/private/etc/ssh"))
            assertFailsWith<CliUserException>(path) { refuseDirectory(path.toPath(), home, windows = false) }
        val windowsHome = "C:\\Users\\me".toPath()
        refuseDirectory("C:\\Users\\me\\project".toPath(), windowsHome, windows = true)
        for (path in listOf("C:\\", "c:\\users\\ME", "C:\\Windows", "c:\\windows\\System32", "C:\\Program Files\\Git"))
            assertFailsWith<CliUserException>(path) { refuseDirectory(path.toPath(), windowsHome, windows = true) }
    }

    @Test fun recordsStayOnThisDeviceAndRejectOddIds() {
        val root = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "teamcode-remote-${kotlin.random.Random.nextLong().toULong()}"
        try {
            val sessions = RemoteSessions(root)
            val record = RemoteSessionRecord("8f3c0a2e-1111-4222-8333-944455556666", "/Users/me/project", "claude")
            sessions.save(record)
            assertEquals(record, sessions.load(record.sessionId))
            assertNull(sessions.load("0a1b2c3d-1111-4222-8333-944455556666"), "No record of another session")
            assertNull(sessions.load("../config"), "Never a path outside the records")
            assertFailsWith<IllegalArgumentException> { sessions.save(record.copy(sessionId = "../x")) }
        } finally { FileSystem.SYSTEM.deleteRecursively(root, mustExist = false) }
    }

    @Test fun aConversationWrittenBeforeItsRecordKeepsTheRecordsPrivate() {
        // A daemon continuing a device's conversation writes it before the record; on Windows a directory created without
        // the private writes is refused by them afterwards.
        val root = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "teamcode-remote-${kotlin.random.Random.nextLong().toULong()}" / "remote"
        try {
            val sessions = RemoteSessions(root)
            val id = "8f3c0a2e-1111-4222-8333-944455556666"
            sessions.conversation(id).write(AgentState("claude", null, "0000000a-aaaa-4bbb-8ccc-00000000000a", fork = true))
            sessions.save(RemoteSessionRecord(id, "/Users/me/project", "claude"))
            assertEquals(AgentState("claude", null, "0000000a-aaaa-4bbb-8ccc-00000000000a", fork = true), sessions.conversation(id).read())
            assertEquals(id, sessions.load(id)?.sessionId)
            if (!isWindows()) {
                assertEquals(0x1C0, pathStatus(root.toString())!!.mode, "The records' directory is 0700")
                assertEquals(0x180, pathStatus((root / "$id.conversation.json").toString())!!.mode, "The conversation is 0600")
            }
        } finally { FileSystem.SYSTEM.deleteRecursively(root.parent!!, mustExist = false) }
    }
}
