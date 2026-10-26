package me.nathanfallet.teamcode.companion

import me.nathanfallet.teamcode.companion.domain.models.McpServer
import me.nathanfallet.teamcode.companion.domain.models.McpTransport
import me.nathanfallet.teamcode.companion.domain.repositories.McpServerRepository

fun mcpServer(name: String, transport: McpTransport = McpTransport.STDIO, state: String? = "ready", failure: String? = null) = McpServer(name, transport, state, failure)

/** The five servers of the screens: a ready and a starting command, a failed one, one in backoff, an HTTPS one. */
fun demoMcpServers() = listOf(
    mcpServer("github", state = "ready"),
    mcpServer("playwright", state = "starting"),
    mcpServer("postgres", state = "failed", failure = "start_failed"),
    mcpServer("filesystem", state = "backoff", failure = "crashed"),
    mcpServer("docs", McpTransport.HTTP, state = null),
)

/** MCP servers answered by [servers] (or failing with [failure]); every call is recorded in [calls]. */
class FakeMcpServerRepository : McpServerRepository {
    val calls = mutableListOf<String>()
    var servers: List<McpServer> = demoMcpServers()
    var failure: Exception? = null

    override suspend fun servers(accountId: String): List<McpServer> {
        calls += accountId
        failure?.let { throw it }
        return servers
    }
}
