package me.nathanfallet.teamcode.infrastructure.mcp

import me.nathanfallet.teamcode.domain.mcp.McpLimits
import me.nathanfallet.teamcode.domain.mcp.McpOutcome
import me.nathanfallet.teamcode.domain.mcp.McpUpstream
import me.nathanfallet.teamcode.domain.mcp.McpUpstreamFailure
import me.nathanfallet.teamcode.domain.variants.EffectiveMcpServer

/**
 * Routes each operation by the server's transport: an HTTPS endpoint to [http], a hosted command to [host], the
 * server's MCP host, or a failure when it has none (resolution already withholds hosted commands then).
 */
class TransportMcpUpstream(private val http: McpUpstream, private val host: McpUpstream?) : McpUpstream {
    override val hostsCommands get() = host != null

    override suspend fun listTools(server: EffectiveMcpServer, limits: McpLimits) = of(server).listTools(server, limits)

    override suspend fun callTool(server: EffectiveMcpServer, tool: String, arguments: String?, limits: McpLimits) =
        of(server).callTool(server, tool, arguments, limits)

    override fun warm(servers: List<EffectiveMcpServer>) { host?.warm(servers.filter { it.hosted }) }

    private fun of(server: EffectiveMcpServer) = if (!server.hosted) http
        else host ?: throw McpUpstreamFailure(McpOutcome.UPSTREAM_ERROR, "No MCP host is configured on this server, so it cannot run a hosted command.")
}
