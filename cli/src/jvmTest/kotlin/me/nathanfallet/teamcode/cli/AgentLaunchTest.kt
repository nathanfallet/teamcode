package me.nathanfallet.teamcode.cli

import kotlinx.serialization.json.*
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
}
