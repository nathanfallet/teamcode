package me.nathanfallet.teamcode.companion.infrastructure.repositories

import me.nathanfallet.teamcode.api.models.McpServerView
import me.nathanfallet.teamcode.companion.domain.models.McpServer
import me.nathanfallet.teamcode.companion.domain.models.McpTransport
import me.nathanfallet.teamcode.companion.domain.repositories.McpServerRepository

/** The member's MCP servers, from `GET /api/v1/mcp/servers` (the typed client's `mcpServers()`, as the CLI reads them). One request. */
class RemoteMcpServerRepository(private val calls: AccountCalls) : McpServerRepository {
    override suspend fun servers(accountId: String): List<McpServer> = calls.withAccount(accountId) { client -> client.mcpServers().map { it.domain() } }
}

internal fun McpServerView.domain() = McpServer(
    name = name,
    transport = when (transport) {
        "http" -> McpTransport.HTTP
        "stdio" -> McpTransport.STDIO
        else -> McpTransport.UNKNOWN
    },
    state = state,
    failure = failure,
)
