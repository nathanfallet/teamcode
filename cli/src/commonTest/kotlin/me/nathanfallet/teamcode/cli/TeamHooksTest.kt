package me.nathanfallet.teamcode.cli

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import me.nathanfallet.teamcode.api.models.*
import me.nathanfallet.teamcode.cli.cloud.agent.*
import me.nathanfallet.teamcode.cli.hooks.*
import me.nathanfallet.teamcode.cli.launch.codexGuidance
import me.nathanfallet.teamcode.cli.launch.sessionHooks
import me.nathanfallet.teamcode.cli.platform.AgentProcess
import me.nathanfallet.teamcode.cli.platform.ServicePlatform
import okio.ByteString.Companion.encodeUtf8
import okio.FileSystem
import okio.Path
import kotlin.random.Random
import kotlin.test.*

class TeamHooksTest {
    private val fs = FileSystem.SYSTEM
    private fun temporary(): Path = FileSystem.SYSTEM_TEMPORARY_DIRECTORY.resolve("tc-hooks-" + Random.nextLong().toString(16).removePrefix("-")).also { fs.createDirectories(it) }

    private class Fixture(val name: String, val version: String, val definition: HookDefinitionView, val files: Map<String, Pair<String, String>>) {
        fun entries() = files.map { (path, file) -> SkillDigest.Entry(path, file.second, file.first.encodeUtf8().size, file.first.encodeUtf8().sha256().hex()) }
        val digest get() = HookDigest.of(definition, entries())
        fun manifest() = EffectiveHookView(name, version, digest, definition, files.values.sumOf { it.first.length }.toLong(), files.size)
        fun artifact() = HookArtifactView(name, version, digest, definition, entries().zip(files.values).map { (entry, file) ->
            SkillArtifactFile(entry.path, entry.mode, entry.size, entry.sha256, file.first.encodeUtf8().base64())
        })
    }
    private val stop = HookDefinitionView(listOf("Stop", "SubagentStop"), command = "bin/guard.sh", timeoutSeconds = 30)
    private fun fixture(name: String = "stop-guard", version: String = "1", body: String = "exit 0", definition: HookDefinitionView = stop) =
        Fixture(name, version, definition, mapOf("bin/guard.sh" to ("#!/bin/sh\n$body\n" to "executable"), "README.md" to ("Guard" to "file")))

    @Test fun theClientDigestIsTheServerFormula() {
        // Same vector as the server's HookCatalogTest.
        val sha = "abc".encodeUtf8().sha256().hex()
        val line = "hook\u0000Stop,SubagentStop\u0000\u0000bin/check.sh\u000030\nbin/check.sh\u0000executable\u00003\u0000$sha\n"
        val definition = HookDefinitionView(listOf("SubagentStop", "Stop"), command = "bin/check.sh", timeoutSeconds = 30)
        assertEquals("sha256:" + line.encodeUtf8().sha256().hex(), HookDigest.of(definition, listOf(SkillDigest.Entry("bin/check.sh", "executable", 3, sha))))
    }

    @Test fun downloadsAreVerifiedAgainstTheManifestBeforeAnythingIsWritten() {
        val hook = fixture()
        assertNull(HookDigest.problem(hook.manifest(), hook.artifact()))
        val artifact = hook.artifact()
        val tampered = artifact.copy(files = artifact.files.map { if (it.path == "bin/guard.sh") SkillArtifactFile(it.path, it.mode, it.size, it.sha256, "#!/bin/sh\nrm -rf\n".encodeUtf8().base64()) else it })
        assertEquals("content", HookDigest.problem(hook.manifest(), tampered))
        assertEquals("mismatch", HookDigest.problem(hook.manifest(), artifact.copy(definition = stop.copy(timeoutSeconds = 600))))
        // A server answering a changed definition under the same digest is refused too.
        val retimed = hook.manifest().copy(definition = stop.copy(timeoutSeconds = 600))
        assertEquals("digest", HookDigest.problem(retimed, artifact.copy(definition = retimed.definition)))
        val notExecutable = fixture().let { Fixture(it.name, it.version, it.definition, it.files.mapValues { (path, file) -> if (path == "bin/guard.sh") file.first to "file" else file }) }
        assertEquals("definition", HookDigest.problem(notExecutable.manifest(), notExecutable.artifact()))
        assertFalse(HookDigest.validDefinition(stop.copy(command = "../guard.sh")))
        assertFalse(HookDigest.validDefinition(stop.copy(events = listOf("OnEverything"))))
    }

    @Test fun onlyVersionsTheMemberApprovedAreInstalledAndANewVersionAsksAgain() = runTest {
        // Like teamHooksRoot(), the hooks directory does not exist yet: the first private write creates it owner-only.
        // A directory created beforehand by another API is refused on Windows when its owner is not the user (an
        // elevated token makes Administrators the owner of new directories).
        val root = temporary() / "hooks"
        val approvals = HookApprovals(root / "approvals.json")
        var hooks = listOf(fixture(), fixture("audit", definition = stop.copy(events = listOf("PreToolUse"), matcher = "Bash")), fixture("noisy"))
        val downloads = mutableListOf<String>()
        val executables = mutableListOf<Path>()
        val sync = TeamHookSync({ hooks.map { it.manifest() } }, { h -> downloads += h.name; hooks.first { it.name == h.name }.artifact() },
            approvals, LocalHookInstaller(root / "installed", makeExecutable = { executables += it }))

        // Nothing approved: nothing downloaded or installed, everything listed as awaiting.
        val none = sync.plan()
        assertEquals(emptyList(), none.installed); assertEquals(listOf("audit", "noisy", "stop-guard"), none.awaiting.map { it.name })
        assertEquals(emptyList(), downloads)

        approvals.record("stop-guard", HookApproval(hooks[0].digest, approved = true))
        approvals.record("noisy", HookApproval(hooks[2].digest, approved = false))
        val plan = sync.plan()
        val installed = plan.installed.single()
        assertEquals("stop-guard", installed.name); assertEquals(1, plan.refused); assertEquals(listOf("audit"), plan.awaiting.map { it.name })
        assertEquals("#!/bin/sh\nexit 0\n", fs.read(with(okio.Path.Companion) { installed.command.toPath() }) { readUtf8() })
        assertEquals(listOf("bin/guard.sh"), executables.map { it.relativeTo(root / "installed" / ".staging").segments.drop(1).joinToString("/") }, "Only the executable files, before the move")
        // Installed once: the next launch reuses it without downloading.
        sync.plan()
        assertEquals(listOf("stop-guard"), downloads)
        // A file changed after installation is not reused: the version is downloaded, verified and put back.
        val command = with(okio.Path.Companion) { installed.command.toPath() }
        fs.write(command) { writeUtf8("#!/bin/sh\nexit 0 # edited\n") }
        assertEquals(installed.command, sync.plan().installed.single().command)
        assertEquals(listOf("stop-guard", "stop-guard"), downloads)
        assertEquals("#!/bin/sh\nexit 0\n", fs.read(command) { readUtf8() })
        sync.plan()
        assertEquals(2, downloads.size, "Intact again: reused without downloading")

        // A new version needs a new approval; the approved one keeps its own directory.
        hooks = listOf(fixture(version = "2", body = "exit 2")) + hooks.drop(1)
        val next = sync.plan()
        assertEquals(emptyList(), next.installed); assertEquals(listOf("audit", "stop-guard"), next.awaiting.map { it.name })
        assertTrue(fs.exists(with(okio.Path.Companion) { installed.command.toPath() }))

        // A download that fails verification is left out, never installed.
        approvals.record("stop-guard", HookApproval(hooks[0].digest, approved = true))
        val failing = TeamHookSync({ hooks.map { it.manifest() } }, { h -> hooks.first { it.name == h.name }.artifact().let { a -> a.copy(files = a.files.reversed().map { f -> SkillArtifactFile(f.path, f.mode, f.size, "0".repeat(64), f.contentBase64) }) } },
            approvals, LocalHookInstaller(root / "installed", makeExecutable = {}))
        assertEquals(listOf("stop-guard"), failing.plan().failed)
        assertFalse(fs.exists(root / "installed" / "stop-guard" / hooks[0].digest.removePrefix("sha256:")))
    }

    @Test fun approvingInstallsFirstThenRecordsInTheSameNewHooksDirectory() = runTest {
        // The order of `teamcode hooks approve`: verify and install, then record. The hooks directory does not exist yet.
        val root = temporary() / "hooks"
        val hook = fixture()
        val approvals = HookApprovals(root / "approvals.json")
        val sync = TeamHookSync({ listOf(hook.manifest()) }, { hook.artifact() }, approvals, LocalHookInstaller(root / "installed", makeExecutable = {}))
        sync.ensure(hook.manifest())
        approvals.record(hook.name, HookApproval(hook.digest, approved = true))
        assertEquals(mapOf(hook.name to HookApproval(hook.digest, approved = true)), approvals.load())
        assertEquals("stop-guard", sync.plan().installed.single().name)
    }

    private fun installed(name: String, definition: HookDefinitionView) = InstalledHook(name, "1", "sha256:" + "0".repeat(64), definition, "/home/me/.config/teamcode/hooks/installed/$name/x/bin/it's.sh")

    @Test fun claudeGetsGuidanceAndTeamHooksInOnePluginWithQuotedCommands() {
        val guard = installed("stop-guard", stop)
        val notify = installed("notify", HookDefinitionView(listOf("Notification"), command = "bin/it's.sh", timeoutSeconds = 5))
        val audit = installed("audit", HookDefinitionView(listOf("PreToolUse"), matcher = "Bash", command = "bin/it's.sh"))
        val hooks = sessionHooks(41234, listOf(guard, notify, audit))["hooks"]!!.jsonObject
        assertEquals(setOf("PreToolUse", "UserPromptSubmit", "Stop", "SubagentStop", "Notification"), hooks.keys)
        val pre = hooks["PreToolUse"]!!.jsonArray
        assertEquals("http", pre[0].jsonObject["hooks"]!!.jsonArray[0].jsonObject["type"]!!.jsonPrimitive.content, "Guidance stays first")
        assertEquals("Bash", pre[1].jsonObject["matcher"]!!.jsonPrimitive.content)
        val command = hooks["Stop"]!!.jsonArray.single().jsonObject["hooks"]!!.jsonArray.single().jsonObject
        assertEquals("'/home/me/.config/teamcode/hooks/installed/stop-guard/x/bin/it'\"'\"'s.sh'", command["command"]!!.jsonPrimitive.content)
        assertEquals(30, command["timeout"]!!.jsonPrimitive.int)
        // Without guidance, only the team hooks.
        assertEquals(setOf("Stop", "SubagentStop"), sessionHooks(null, listOf(guard))["hooks"]!!.jsonObject.keys)
    }

    @Test fun codexGetsTeamHooksOnTheEventsItSupportsMergedWithGuidance() {
        val platform = ServicePlatform("macos", "/usr/local/bin/teamcode", "501")
        val guard = installed("stop-guard", stop)
        val notify = installed("notify", HookDefinitionView(listOf("Notification"), command = "bin/it's.sh"))
        val audit = installed("audit", HookDefinitionView(listOf("PreToolUse"), matcher = "Bash", command = "bin/it's.sh"))
        val only = codexGuidance(platform, "/home/me/.config/teamcode/config.json", listOf(guard, notify), guidance = false).arguments
        assertEquals(listOf("-c", "-c"), only.filterIndexed { i, _ -> i % 2 == 0 })
        assertEquals(listOf("hooks.Stop", "hooks.SubagentStop"), only.filterIndexed { i, _ -> i % 2 == 1 }.map { it.substringBefore('=') })
        assertTrue(only.none { "Notification" in it }, "Codex has no Notification hook")
        val merged = codexGuidance(platform, "/home/me/.config/teamcode/config.json", listOf(audit)).arguments
        val pre = merged[merged.indexOfFirst { it.startsWith("hooks.PreToolUse=") }]
        assertEquals(2, Regex("type=\"command\"").findAll(pre).count(), "Guidance and the team hook share the event's array")
        assertTrue("matcher=\"Bash\"" in pre)
        // Guidance alone is unchanged.
        assertEquals(listOf("hooks.UserPromptSubmit", "hooks.PreToolUse"),
            codexGuidance(platform, "/home/me/.config/teamcode/config.json").arguments.filterIndexed { i, _ -> i % 2 == 1 }.map { it.substringBefore('=') })
    }

    @Test fun activityHooksShareTheGuidanceHookAndNameTheLaunch() {
        val run = "11111111-2222-3333-4444-555555555555"
        val guard = installed("stop-guard", stop)
        val hooks = sessionHooks(41234, listOf(guard), run)["hooks"]!!.jsonObject
        assertEquals(setOf("PreToolUse", "UserPromptSubmit", "Stop", "SubagentStop", "Notification", "PostToolUse", "PostToolUseFailure"), hooks.keys)
        for (event in listOf("PreToolUse", "UserPromptSubmit", "Stop", "Notification", "PostToolUse", "PostToolUseFailure")) {
            val http = hooks[event]!!.jsonArray[0].jsonObject["hooks"]!!.jsonArray[0].jsonObject
            assertEquals("http", http["type"]!!.jsonPrimitive.content)
            assertEquals(run, http["headers"]!!.jsonObject["X-TeamCode-Run"]!!.jsonPrimitive.content)
        }
        // The team Stop hook follows TeamCode's report in the same event.
        assertEquals(listOf("http", "command"), hooks["Stop"]!!.jsonArray.map { it.jsonObject["hooks"]!!.jsonArray[0].jsonObject["type"]!!.jsonPrimitive.content })
        // Without a launch, or without guidance, no activity hook and no header.
        assertEquals(setOf("PreToolUse", "UserPromptSubmit", "Stop", "SubagentStop"), sessionHooks(41234, listOf(guard))["hooks"]!!.jsonObject.keys)
        assertTrue("X-TeamCode-Run" !in sessionHooks(41234, emptyList())["hooks"].toString())
        assertEquals(setOf("Stop", "SubagentStop"), sessionHooks(null, listOf(guard), run)["hooks"]!!.jsonObject.keys)
        assertFailsWith<IllegalArgumentException> { sessionHooks(41234, emptyList(), "not-a-uuid") }
    }

    @Test fun codexReportsActivityFromTheGuidanceCommandOnStop() {
        val platform = ServicePlatform("macos", "/usr/local/bin/teamcode", "501")
        val arguments = codexGuidance(platform, "/home/me/.config/teamcode/config.json", activity = true).arguments
        assertEquals(listOf("hooks.UserPromptSubmit", "hooks.PreToolUse", "hooks.Stop"), arguments.filterIndexed { i, _ -> i % 2 == 1 }.map { it.substringBefore('=') })
        val stop = arguments[arguments.indexOfFirst { it.startsWith("hooks.Stop=") }]
        assertTrue("agents" in stop && "hook" in stop && "--config" in stop)
        assertEquals("1", codexGuidance(platform, "/home/me/.config/teamcode/config.json", activity = true).environment["TEAMCODE_ACTIVITY"])
        assertNull(codexGuidance(platform, "/home/me/.config/teamcode/config.json").environment["TEAMCODE_ACTIVITY"])
        // No Stop override unless the server records activity; never on its own without guidance.
        assertEquals(2, codexGuidance(platform, "/home/me/.config/teamcode/config.json").arguments.size / 2)
        assertTrue(codexGuidance(platform, "/home/me/.config/teamcode/config.json", guidance = false, activity = true).arguments.isEmpty())
    }

    /** The installer behind a pair of lines, as the supervisor exposes it. */
    private class InstallerProcess(val installer: SkillInstaller) : AgentProcess {
        val replies = ArrayDeque<String>()
        var command: List<String> = emptyList()
        override suspend fun writeLine(line: String): Boolean { SkillMessage.decode(line)?.let { installer.handle(it).forEach { reply -> replies += reply.encode() } }; return true }
        override suspend fun readLine(maxLine: Int) = replies.removeFirstOrNull()
        override fun closeInput() = Unit
        override fun exitCode(): Int? = null
        override suspend fun stop(graceMillis: Long) = 0
    }

    @Test fun cloudSessionsInstallVerifiedHooksForClaudeCodeOnly() = runTest {
        val home = temporary()
        val root = home / ".teamcode/hooks"
        var hooks = listOf(fixture(), fixture("notify", definition = stop.copy(events = listOf("Notification"))))
        val commands = mutableListOf<List<String>>()
        val logs = mutableListOf<String>()
        fun sync() = CloudHookSync({ hooks.map { it.manifest() } }, { h -> hooks.first { it.name == h.name }.artifact() },
            { command -> commands += command; InstallerProcess(SkillInstaller(root, makeExecutable = {}, entryFile = null, markerName = SkillInstaller.HOOK_MARKER, alwaysReplace = true)) }, logs::add)
        assertEquals(emptyList(), sync().sync("codex"), "Codex sessions run no team hook")
        assertEquals(emptyList(), commands)
        val installed = sync().sync("claude")
        assertEquals(listOf("notify", "stop-guard"), installed.map { it.name })
        assertEquals(listOf("cloud", "install-hooks", "--agent", "claude"), commands.single())
        commands.clear()
        assertEquals("#!/bin/sh\nexit 0\n", fs.read(root / "stop-guard/bin/guard.sh") { readUtf8() })
        // The agent can write its home: each start puts back the verified files instead of trusting the marker.
        fs.write(root / "stop-guard/bin/guard.sh") { writeUtf8("#!/bin/sh\nexit 0 # edited\n") }
        assertEquals(listOf("notify", "stop-guard"), sync().sync("claude").map { it.name })
        assertEquals("#!/bin/sh\nexit 0\n", fs.read(root / "stop-guard/bin/guard.sh") { readUtf8() })
        assertTrue(logs.none { "stop-guard" in it || "notify" in it }, "The pod log carries counts only")
        val settings = Json.parseToJsonElement(assertNotNull(cloudHookSettings(installed))).jsonObject["hooks"]!!.jsonObject
        assertEquals(setOf("Stop", "SubagentStop", "Notification"), settings.keys)
        assertEquals("\"\$HOME\"/'.teamcode/hooks/stop-guard/bin/guard.sh'",
            settings["Stop"]!!.jsonArray.single().jsonObject["hooks"]!!.jsonArray.single().jsonObject["command"]!!.jsonPrimitive.content)
        // A tampered download is refused; a hook no longer delivered is removed from the agent's home.
        hooks = listOf(fixture())
        val tampered = CloudHookSync({ hooks.map { it.manifest() } }, { h -> hooks.first().artifact().let { a -> a.copy(definition = a.definition.copy(timeoutSeconds = 1)) } },
            { InstallerProcess(SkillInstaller(root, makeExecutable = {}, entryFile = null, markerName = SkillInstaller.HOOK_MARKER)) }, logs::add)
        assertEquals(emptyList(), tampered.sync("claude"))
        assertFalse(fs.exists(root / "notify"))
        assertNull(cloudHookSettings(emptyList()))
    }
}
