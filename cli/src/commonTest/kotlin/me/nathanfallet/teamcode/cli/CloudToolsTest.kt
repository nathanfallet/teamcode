package me.nathanfallet.teamcode.cli

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import me.nathanfallet.teamcode.api.models.*
import me.nathanfallet.teamcode.cli.cloud.agent.*
import me.nathanfallet.teamcode.cli.platform.AgentProcess
import okio.ByteString.Companion.encodeUtf8
import okio.ByteString.Companion.toByteString
import okio.FileSystem
import okio.Path
import kotlin.random.Random
import kotlin.test.*

class CloudToolsTest {
    private val fs = FileSystem.SYSTEM
    private fun temporary(): Path = FileSystem.SYSTEM_TEMPORARY_DIRECTORY.resolve("tc-tools-" + Random.nextLong().toString(16).removePrefix("-")).also { fs.createDirectories(it) }

    private class Skill(val name: String, val version: String, val files: Map<String, Pair<String, String>>) {
        fun entries() = files.map { (path, file) -> SkillDigest.Entry(path, file.second, file.first.encodeUtf8().size, file.first.encodeUtf8().sha256().hex()) }
        val digest get() = SkillDigest.of(entries())
        fun manifest() = EffectiveSkillView(name, version, digest, files.values.sumOf { it.first.length }.toLong(), files.size)
        fun artifact() = SkillArtifactView(name, version, digest, entries().zip(files.values).map { (entry, file) ->
            SkillArtifactFile(entry.path, entry.mode, entry.size, entry.sha256, file.first.encodeUtf8().base64())
        })
    }
    private fun skill(name: String, version: String = "1", body: String = "# $name") = Skill(name, version, mapOf("SKILL.md" to (body to "file"), "scripts/run.sh" to ("echo hi" to "executable")))

    /** The installer behind a pair of lines, as the supervisor exposes it. */
    private class InstallerProcess(val installer: SkillInstaller) : AgentProcess {
        val replies = ArrayDeque<String>()
        val received = mutableListOf<SkillMessage>()
        override suspend fun writeLine(line: String): Boolean {
            val message = SkillMessage.decode(line) ?: return true
            received += message
            installer.handle(message).forEach { replies += it.encode() }
            return true
        }
        override suspend fun readLine(maxLine: Int) = replies.removeFirstOrNull()
        override fun closeInput() = Unit
        override fun exitCode(): Int? = null
        override suspend fun stop(graceMillis: Long) = 0
    }

    private fun sync(root: Path, skills: List<Skill>, downloads: MutableList<String> = mutableListOf(), process: (InstallerProcess) -> Unit = {}, tamper: (SkillArtifactView) -> SkillArtifactView = { it }): suspend () -> Unit {
        val logs = mutableListOf<String>()
        return {
            val installer = SkillInstaller(root, makeExecutable = {})
            SkillSync({ skills.map { it.manifest() } }, { entry -> downloads += entry.name; tamper(skills.first { it.name == entry.name }.artifact()) },
                { InstallerProcess(installer).also(process) }, logs::add).sync("claude")
        }
    }

    @Test fun digestFollowsTheCatalogFormula() {
        val entries = listOf(SkillDigest.Entry("b.txt", "file", 1, "ab"), SkillDigest.Entry("SKILL.md", "executable", 2, "cd"))
        assertEquals("sha256:" + "SKILL.md\u0000executable\u00002\u0000cd\nb.txt\u0000file\u00001\u0000ab\n".encodeUtf8().sha256().hex(), SkillDigest.of(entries))
    }

    @Test fun installsOnlyWhatChangedAndRemovesWhatIsNoLongerDelivered() = runTest {
        val root = temporary().resolve("home/.claude/skills")
        val review = skill("review"); val deploy = skill("deploy")
        val downloads = mutableListOf<String>()
        sync(root, listOf(review, deploy), downloads)()
        assertEquals(listOf("deploy", "review"), downloads.sorted())
        assertEquals("# review", fs.read(root.resolve("review/SKILL.md")) { readUtf8() })
        assertEquals("echo hi", fs.read(root.resolve("deploy/scripts/run.sh")) { readUtf8() })
        assertTrue(fs.exists(root.resolve("review/.teamcode-skill")))
        assertFalse(fs.exists(root.parent!!.resolve(".teamcode-skills-staging")), "Staging is cleaned")

        // Same delivery again: nothing is downloaded. A new pinned version of one skill: only that one.
        downloads.clear()
        sync(root, listOf(review, deploy), downloads)()
        assertTrue(downloads.isEmpty())
        val newer = skill("review", "2", "# review two")
        sync(root, listOf(newer, deploy), downloads)()
        assertEquals(listOf("review"), downloads)
        assertEquals("# review two", fs.read(root.resolve("review/SKILL.md")) { readUtf8() })

        // The member's decision was revoked, or the owner changed: the delivered copy goes, nothing else does.
        fs.createDirectories(root.resolve("personal")); fs.write(root.resolve("personal/SKILL.md")) { writeUtf8("mine") }
        sync(root, listOf(deploy))()
        assertFalse(fs.exists(root.resolve("review")))
        assertTrue(fs.exists(root.resolve("deploy")) && fs.exists(root.resolve("personal/SKILL.md")))
        sync(root, emptyList())()
        assertFalse(fs.exists(root.resolve("deploy")))
        assertEquals("mine", fs.read(root.resolve("personal/SKILL.md")) { readUtf8() }, "Personal skills are preserved")
        fs.deleteRecursively(root.parent!!.parent!!)
    }

    @Test fun aPersonalSkillWithTheSameNameIsNeverOverwritten() = runTest {
        val root = temporary().resolve("skills")
        fs.createDirectories(root.resolve("review")); fs.write(root.resolve("review/SKILL.md")) { writeUtf8("mine") }
        val downloads = mutableListOf<String>()
        sync(root, listOf(skill("review")), downloads)()
        assertTrue(downloads.isEmpty(), "A conflicting name is not even downloaded")
        assertEquals("mine", fs.read(root.resolve("review/SKILL.md")) { readUtf8() })
        assertFalse(fs.exists(root.resolve("review/.teamcode-skill")))
        fs.deleteRecursively(root.parent!!)
    }

    @Test fun largeFilesStreamInChunksAndAreVerifiedAgain() = runTest {
        val root = temporary().resolve("skills")
        val big = "x".repeat(SkillMessage.CHUNK_BYTES * 2 + 17)
        val bundle = Skill("big", "1", mapOf("SKILL.md" to ("# big" to "file"), "data/big.txt" to (big to "file")))
        var process: InstallerProcess? = null
        sync(root, listOf(bundle), process = { process = it })()
        assertEquals(big, fs.read(root.resolve("big/data/big.txt")) { readUtf8() })
        assertEquals(3, process!!.received.count { it.path == "data/big.txt" })
        assertTrue(process!!.received.filter { it.path == "data/big.txt" }.all { (it.data!!.length) < 400_000 }, "Each line stays small")
        fs.deleteRecursively(root.parent!!)
    }

    @Test fun aCorruptDownloadNeverReachesTheInstaller() = runTest {
        val root = temporary().resolve("skills")
        var process: InstallerProcess? = null
        sync(root, listOf(skill("review")), process = { process = it }, tamper = { artifact ->
            SkillArtifactView(artifact.name, artifact.version, artifact.digest, artifact.files.map { SkillArtifactFile(it.path, it.mode, it.size, it.sha256, "evil-content-of-another-size".encodeUtf8().base64()) })
        })()
        assertTrue(process!!.received.none { it.t == SkillMessage.BEGIN || it.t == SkillMessage.FILE })
        assertFalse(fs.exists(root.resolve("review")))
        fs.deleteRecursively(root.parent!!)
    }

    @Test fun theInstallerChecksPathsAndDigestsItself() = runTest {
        val root = temporary().resolve("skills")
        val good = skill("review")
        val installer = SkillInstaller(root, makeExecutable = {})
        installer.handle(SkillMessage(SkillMessage.MANIFEST, skills = listOf(SkillMessage.Item("review", "1", good.digest))))
        installer.handle(SkillMessage(SkillMessage.BEGIN, name = "review"))
        installer.handle(SkillMessage(SkillMessage.FILE, path = "../escape", mode = "file", data = "eA==", status = SkillInstaller.LAST))
        val result = installer.handle(SkillMessage(SkillMessage.END))
        assertEquals(SkillMessage.FAILED, result.single { it.t == SkillMessage.RESULT }.status)
        assertFalse(fs.exists(root.resolve("review")) || fs.exists(root.parent!!.resolve("escape")))

        // A skill the manifest never named, and a payload that does not match the promised digest, install nothing.
        val second = SkillInstaller(root, makeExecutable = {})
        second.handle(SkillMessage(SkillMessage.MANIFEST, skills = listOf(SkillMessage.Item("review", "1", good.digest))))
        second.handle(SkillMessage(SkillMessage.BEGIN, name = "other"))
        second.handle(SkillMessage(SkillMessage.BEGIN, name = "review"))
        second.handle(SkillMessage(SkillMessage.FILE, path = "SKILL.md", mode = "file", data = "eA==", status = SkillInstaller.LAST))
        second.handle(SkillMessage(SkillMessage.COMMIT, name = "review", digest = good.digest))
        assertEquals(SkillMessage.FAILED, second.handle(SkillMessage(SkillMessage.END)).single { it.t == SkillMessage.RESULT }.status)
        assertFalse(fs.exists(root.resolve("review")))
        fs.deleteRecursively(root.parent!!)
    }

    @Test fun rootsFollowEachAgentsOwnLayout() {
        assertEquals("/h/.claude/skills", SkillPaths.root("claude", "/h", null).toString())
        assertEquals("/h/.codex/skills", SkillPaths.root("codex", "/h", null).toString())
        assertEquals("/c/skills", SkillPaths.root("codex", "/h", "/c").toString())
    }

    // The agent host connects the owner's MCP servers to the runner's proxy and prepares tools first.

    private class Process : AgentProcess {
        val written = mutableListOf<String>()
        val output = Channel<String?>(Channel.UNLIMITED)
        override suspend fun writeLine(line: String): Boolean { written += line; return true }
        override suspend fun readLine(maxLine: Int) = output.receive()
        override fun closeInput() = Unit
        override fun exitCode(): Int? = null
        override suspend fun stop(graceMillis: Long): Int { output.trySend(null); return 0 }
    }
    private class Channel1(val kind: String) : AgentChannel {
        val inputs = Channel<List<RunnerInputView>>(Channel.UNLIMITED)
        override suspend fun agent() = RunnerAgentView(kind, "1", "m", null, "none")
        override suspend fun events(events: List<RunnerEventView>) = RunnerEventsAckView(events.last().sequence, events.size.toLong())
        override suspend fun inputs(waitMillis: Long) = inputs.receive()
        override suspend fun acknowledge(acks: List<RunnerInputAckView>) = Unit
    }
    private class Memory : AgentStateStore {
        override fun read(): AgentState? = null
        override fun write(state: AgentState) = Unit
        override fun clear() = Unit
    }
    private suspend fun TestScope.started(kind: String, endpoint: AgentInferenceEndpoint?, tools: AgentTools): Pair<AgentCommand, Map<String, String?>>? {
        val channel = Channel1(kind)
        val starts = mutableListOf<Pair<AgentCommand, Map<String, String?>>>()
        val host = AgentHost(channel, { command, environment, _ -> starts += command to environment; Process() }, Memory(), "/workspace", "1.0",
            now = { testScheduler.currentTime }, inference = { endpoint }, tools = tools)
        val job = launch { host.run() }
        channel.inputs.send(listOf(RunnerInputView("in-1", "message", "Go", 1)))
        repeat(200) { if (starts.isEmpty()) delay(50) }
        job.cancel()
        return starts.firstOrNull()
    }

    @Test fun teamMcpServersAreConnectedThroughTheProxyForBothAgents() = runTest {
        val local = "l".repeat(64)
        val calls = mutableListOf<String>()
        val tools = object : AgentTools { override suspend fun prepare(agent: String): List<String> { calls += agent; return listOf("tracker", "docs.v2") } }
        val (claude, claudeEnvironment) = assertNotNull(started("claude", AgentInferenceEndpoint(41234, local), tools))
        val config = Json.parseToJsonElement(claude.arguments[claude.arguments.indexOf("--mcp-config") + 1]).jsonObject["mcpServers"]!!.jsonObject
        assertEquals("http://127.0.0.1:41234/mcp/tracker", config["teamcode-tracker"]!!.jsonObject["url"]!!.jsonPrimitive.content)
        assertEquals("http://127.0.0.1:41234/mcp/docs.v2", config["teamcode-docs_v2"]!!.jsonObject["url"]!!.jsonPrimitive.content)
        assertEquals(local, claudeEnvironment["TEAMCODE_MCP_TOKEN"], "The agent holds only the proxy's local token")
        assertFalse(claude.arguments.joinToString().contains(local), "The token is not on the command line")

        val (codex, codexEnvironment) = assertNotNull(started("codex", AgentInferenceEndpoint(41234, local), tools))
        assertTrue(codex.arguments.any { "mcp_servers.teamcode-tracker={url=\"http://127.0.0.1:41234/mcp/tracker\"" in it }, codex.arguments.toString())
        assertEquals(local, codexEnvironment["TEAMCODE_LOCAL_TOKEN"])
        assertEquals(listOf("claude", "codex"), calls)
    }

    @Test fun noServersNoMcpConfigAndNoToolsWithoutTheProxy() = runTest {
        val local = "l".repeat(64)
        var prepared = 0
        val none = object : AgentTools { override suspend fun prepare(agent: String): List<String> { prepared++; return emptyList() } }
        val (command, environment) = assertNotNull(started("claude", AgentInferenceEndpoint(41234, local), none))
        assertFalse("--mcp-config" in command.arguments); assertNull(environment["TEAMCODE_MCP_TOKEN"])
        assertEquals(1, prepared)
        // A server without inference credentials: the agent keeps its own configuration, and tools are not prepared.
        assertNotNull(started("claude", null, none))
        assertEquals(1, prepared)
    }
}
