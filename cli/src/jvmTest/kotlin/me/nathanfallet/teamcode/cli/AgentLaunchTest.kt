package me.nathanfallet.teamcode.cli

import kotlinx.serialization.json.*
import me.nathanfallet.teamcode.cli.launch.ARTIFACTS_INSTRUCTIONS
import me.nathanfallet.teamcode.cli.launch.ArtifactsMcp
import me.nathanfallet.teamcode.cli.launch.agentLaunch
import kotlin.test.*

class AgentLaunchTest {
    private val token = "synthetic-local-token-long-enough-for-tests"

    @Test fun `claude receives one MCP server per team server through the local relay`() {
        val launch = agentLaunch("claude", listOf("--model", "opus", "--", "prompt"), 4242, token, mcpServers = listOf("tracker", "docs.v2"))
        val index = launch.arguments.indexOf("--mcp-config")
        assertEquals(listOf("--model", "opus"), launch.arguments.take(index), "The caller's options come first")
        assertEquals(listOf("--", "prompt"), launch.arguments.drop(index + 2), "A variadic --mcp-config must not swallow the prompt")
        val servers = Json.parseToJsonElement(launch.arguments[index + 1]).jsonObject["mcpServers"]!!.jsonObject
        assertEquals(setOf("teamcode-tracker", "teamcode-docs_v2"), servers.keys)
        val tracker = servers["teamcode-tracker"]!!.jsonObject
        assertEquals("http://127.0.0.1:4242/mcp/tracker", tracker["url"]!!.jsonPrimitive.content)
        assertEquals("http://127.0.0.1:4242/mcp/docs.v2", servers["teamcode-docs_v2"]!!.jsonObject["url"]!!.jsonPrimitive.content)
        assertEquals("Bearer \${TEAMCODE_MCP_TOKEN}", tracker["headers"]!!.jsonObject["Authorization"]!!.jsonPrimitive.content)
        assertFalse(launch.arguments.any { it.contains(token) }, "The local token is passed through the environment only")
        assertEquals(token, launch.environment["TEAMCODE_MCP_TOKEN"])
    }

    @Test fun `codex receives one MCP server override per team server`() {
        val launch = agentLaunch("codex", listOf("exec", "--", "prompt"), 4242, token, mcpServers = listOf("tracker", "wiki"))
        val overrides = launch.arguments.zipWithNext().filter { it.first == "-c" }.map { it.second }
        assertTrue("mcp_servers.teamcode-tracker={url=\"http://127.0.0.1:4242/mcp/tracker\",bearer_token_env_var=\"TEAMCODE_LOCAL_TOKEN\"}" in overrides, overrides.toString())
        assertTrue("mcp_servers.teamcode-wiki={url=\"http://127.0.0.1:4242/mcp/wiki\",bearer_token_env_var=\"TEAMCODE_LOCAL_TOKEN\"}" in overrides, overrides.toString())
        assertEquals(listOf("--", "prompt"), launch.arguments.takeLast(2))
        assertFalse(launch.arguments.any { it.contains(token) })
    }

    @Test fun `no assignment adds no MCP configuration and colliding labels are skipped`() {
        val claude = agentLaunch("claude", listOf("-p", "hi"), 4242, token)
        assertEquals(listOf("-p", "hi"), claude.arguments)
        assertNull(claude.environment["TEAMCODE_MCP_TOKEN"])
        assertTrue(claude.environment.containsKey("TEAMCODE_MCP_TOKEN"), "An inherited value is removed")
        val codex = agentLaunch("codex", emptyList(), 4242, token, mcpServers = listOf("a.b", "a_b", "wiki"))
        val servers = codex.arguments.filter { it.startsWith("mcp_servers.") }
        assertEquals(listOf("mcp_servers.teamcode-wiki"), servers.map { it.substringBefore('=') }, "Two servers that map to one label are both left out")
        assertFailsWith<IllegalArgumentException> { agentLaunch("codex", emptyList(), 4242, token, mcpServers = listOf("../admin")) }
    }

    @Test fun `claude logged in with claude ai keeps its login and sends the capability in a custom header`() {
        // Any environment credential, ANTHROPIC_AUTH_TOKEN included, puts Claude Code in API mode: "claude.ai
        // connectors are disabled because ANTHROPIC_API_KEY or another auth source is set". TeamClaude sets only
        // ANTHROPIC_BASE_URL and lets Claude Code keep its subscription login; so does this launch.
        val launch = agentLaunch("claude", listOf("--dangerously-skip-permissions"), 4242, token, claudeLogin = true)
        assertEquals("http://127.0.0.1:4242", launch.environment["ANTHROPIC_BASE_URL"], "The capability stays out of the displayed base URL")
        assertEquals("x-teamcode-local-token: $token", launch.environment["ANTHROPIC_CUSTOM_HEADERS"])
        for (name in listOf("ANTHROPIC_AUTH_TOKEN", "ANTHROPIC_API_KEY", "CLAUDE_CODE_OAUTH_TOKEN", "CLAUDE_CODE_USE_BEDROCK", "CLAUDE_CODE_USE_VERTEX", "CLAUDE_CODE_USE_FOUNDRY"))
            assertTrue(launch.environment.containsKey(name) && launch.environment[name] == null, "An inherited $name is removed")
        val inherited = "x-project: alpha\nX-TeamCode-Local-Token: stale\n\nx-team: beta"
        assertEquals("x-project: alpha\nx-team: beta\nx-teamcode-local-token: $token",
            agentLaunch("claude", emptyList(), 4242, token, claudeLogin = true, inheritedHeaders = inherited).environment["ANTHROPIC_CUSTOM_HEADERS"],
            "The caller's own headers are kept; a stale capability is replaced")
        assertEquals(inherited, agentLaunch("claude", emptyList(), 4242, token, inheritedHeaders = inherited).environment["ANTHROPIC_CUSTOM_HEADERS"],
            "Without a login to keep, custom headers are left alone")
    }

    @Test fun `claude without its own login receives the local token as a bearer`() {
        // Without a claude.ai login there is nothing to keep. Interactive Claude Code asks before using an
        // ANTHROPIC_API_KEY (default "No"); ANTHROPIC_AUTH_TOKEN is used without a prompt.
        val launch = agentLaunch("claude", listOf("--dangerously-skip-permissions"), 4242, token)
        assertEquals(token, launch.environment["ANTHROPIC_AUTH_TOKEN"])
        assertEquals("http://127.0.0.1:4242", launch.environment["ANTHROPIC_BASE_URL"])
        for (name in listOf("ANTHROPIC_API_KEY", "CLAUDE_CODE_OAUTH_TOKEN", "CLAUDE_CODE_USE_BEDROCK", "CLAUDE_CODE_USE_VERTEX", "CLAUDE_CODE_USE_FOUNDRY"))
            assertTrue(launch.environment.containsKey(name) && launch.environment[name] == null, "An inherited $name is removed")
    }

    @Test fun claudeStartsTheArtifactServerAsStdioWithTheTokenOnlyInItsEnvironment() {
        val run = "11111111-2222-4333-8444-555555555555"
        val launch = agentLaunch("claude", listOf("--", "prompt"), 4242, token, runId = run, mcpServers = listOf("artifacts"),
            artifacts = ArtifactsMcp("/opt/teamcode/teamcode", "/work/repo", "/tmp/teamcode-artifacts", listOf("/work/repo/.home")))
        val index = launch.arguments.indexOf("--mcp-config")
        val servers = Json.parseToJsonElement(launch.arguments[index + 1]).jsonObject["mcpServers"]!!.jsonObject
        assertEquals(setOf("teamcode-artifacts", "teamcode_artifacts"), servers.keys, "A team server named artifacts keeps its own label")
        val server = servers["teamcode_artifacts"]!!.jsonObject
        assertEquals("stdio", server["type"]!!.jsonPrimitive.content); assertEquals("/opt/teamcode/teamcode", server["command"]!!.jsonPrimitive.content)
        assertEquals(listOf("mcp", "artifacts", "--root", "/work/repo", "--scratch", "/tmp/teamcode-artifacts", "--run", run, "--refuse", "/work/repo/.home"),
            server["args"]!!.jsonArray.map { it.jsonPrimitive.content })
        val env = server["env"]!!.jsonObject
        assertEquals("http://127.0.0.1:4242", env["TEAMCODE_ARTIFACTS_URL"]!!.jsonPrimitive.content)
        assertEquals("TEAMCODE_ARTIFACTS_TOKEN", env["TEAMCODE_ARTIFACTS_TOKEN_VAR"]!!.jsonPrimitive.content, "The server reads only this launch's token")
        assertEquals("\${TEAMCODE_MCP_TOKEN}", env["TEAMCODE_ARTIFACTS_TOKEN"]!!.jsonPrimitive.content)
        assertFalse(launch.arguments.any { it.contains(token) }, "The local token is on no command line")
        assertEquals(token, launch.environment["TEAMCODE_MCP_TOKEN"])
    }

    @Test fun codexStartsTheArtifactServerWithTheTokenVariableNamedNotGiven() {
        val launch = agentLaunch("codex", listOf("exec", "--", "prompt"), 4242, token, artifacts = ArtifactsMcp("C:\\Tools\\team\"code.exe", "C:\\repo"))
        val overrides = launch.arguments.zipWithNext().filter { it.first == "-c" }.map { it.second }
        val server = overrides.single { it.startsWith("mcp_servers.teamcode_artifacts=") }
        assertEquals("mcp_servers.teamcode_artifacts={command=\"C:\\\\Tools\\\\team\\\"code.exe\",args=[\"mcp\",\"artifacts\",\"--root\",\"C:\\\\repo\"]," +
            "env={TEAMCODE_ARTIFACTS_URL=\"http://127.0.0.1:4242\",TEAMCODE_ARTIFACTS_TOKEN_VAR=\"TEAMCODE_LOCAL_TOKEN\"},env_vars=[\"TEAMCODE_LOCAL_TOKEN\"]}", server)
        assertFalse(launch.arguments.any { it.contains(token) })
        assertEquals(listOf("--", "prompt"), launch.arguments.takeLast(2))
    }

    @Test fun withoutTheArtifactServerNothingIsAdded() {
        val claude = agentLaunch("claude", listOf("-p", "hi"), 4242, token)
        assertFalse(claude.arguments.any { "teamcode_artifacts" in it }); assertNull(claude.environment["TEAMCODE_MCP_TOKEN"])
        val codex = agentLaunch("codex", listOf("exec"), 4242, token)
        assertFalse(codex.arguments.any { "teamcode_artifacts" in it })
        val own = listOf("--append-system-prompt", "mine", "--disallowedTools", "Bash", "-p", "hi")
        assertEquals(own, agentLaunch("claude", own, 4242, token).arguments, "The caller's own options are untouched")
        assertEquals(own, agentLaunch("claude", own, 4242, token, mcpServers = listOf("wiki")).arguments.filterNot { it == "--mcp-config" || it.startsWith("{") },
            "A team server alone does not turn off the native artifact tools")
        assertFalse(codex.arguments.any { it.startsWith("developer_instructions=") })
    }

    private val artifactServer = ArtifactsMcp("/opt/teamcode/teamcode", "/work/repo")

    @Test fun claudeWithTheArtifactServerDeniesItsNativeArtifactToolsAndIsToldToPublishThroughTeamCode() {
        // Measured on Claude Code 2.1.309: --disallowedTools Bash prompt swallows the prompt (variadic), while
        // --disallowedTools=Bash prompt keeps it; repeated --disallowedTools add up.
        val launch = agentLaunch("claude", listOf("-p", "publish a page"), 4242, token, artifacts = artifactServer)
        assertEquals(listOf("-p", "publish a page", "--append-system-prompt", ARTIFACTS_INSTRUCTIONS,
            "--disallowedTools=Artifact,ArtifactComments,ArtifactData,ArtifactCheck", "--mcp-config"), launch.arguments.dropLast(1),
            "TeamCode's options come after the caller's and none of them is followed by a value it could swallow")
        assertTrue("`teamcode_artifacts`" in ARTIFACTS_INSTRUCTIONS && "/artifact/<id>" in ARTIFACTS_INSTRUCTIONS)
        val delimited = agentLaunch("claude", listOf("--model", "opus", "--", "a prompt"), 4242, token, artifacts = artifactServer)
        assertEquals(listOf("--", "a prompt"), delimited.arguments.takeLast(2), "The prompt after -- stays last")
        assertEquals(listOf("--model", "opus", "--append-system-prompt"), delimited.arguments.take(3))
    }

    @Test fun claudeKeepsTheCallersOwnPromptAndDeniedTools() {
        // Claude Code keeps only the last --append-system-prompt (measured on 2.1.309), so the caller's text is merged.
        val launch = agentLaunch("claude", listOf("--append-system-prompt", "first", "--disallowedTools", "Bash", "--append-system-prompt=Be brief.",
            "--append-system-prompt-file", "notes.txt", "--", "--append-system-prompt", "literal"), 4242, token, artifacts = artifactServer)
        assertEquals(listOf("--disallowedTools", "Bash", "--append-system-prompt-file", "notes.txt", "--append-system-prompt",
            "Be brief.\n\n$ARTIFACTS_INSTRUCTIONS", "--disallowedTools=Artifact,ArtifactComments,ArtifactData,ArtifactCheck", "--mcp-config"),
            launch.arguments.take(8), "The caller's denied tools and prompt file are kept, and their last appended prompt comes first")
        assertEquals(listOf("--", "--append-system-prompt", "literal"), launch.arguments.takeLast(3), "Arguments after -- are not options")
    }

    @Test fun codexWithTheArtifactServerReceivesTheInstructionUnlessTheCallerSetsTheirOwn() {
        val launch = agentLaunch("codex", listOf("exec", "--", "prompt"), 4242, token, artifacts = artifactServer)
        val overrides = launch.arguments.zipWithNext().filter { it.first == "-c" }.map { it.second }
        val instructions = overrides.single { it.startsWith("developer_instructions=") }
        assertEquals("developer_instructions=\"" + ARTIFACTS_INSTRUCTIONS.replace("\"", "\\\"") + "\"", instructions)
        assertEquals(listOf("--", "prompt"), launch.arguments.takeLast(2))
        for (own in listOf(listOf("-c", "developer_instructions=\"mine\""), listOf("--config=developer_instructions='mine'"), listOf("-cdeveloper_instructions=\"x\"")))
            assertFalse(agentLaunch("codex", listOf("exec") + own, 4242, token, artifacts = artifactServer).arguments.any { it.startsWith("developer_instructions=") && "TeamCode" in it }, own.toString())
        assertFalse(agentLaunch("codex", listOf("exec"), 4242, token, artifacts = artifactServer, codexInstructions = false).arguments.any { it.startsWith("developer_instructions=") },
            "Instructions from the user's config.toml are not replaced")
    }
}
