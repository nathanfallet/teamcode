package me.nathanfallet.teamcode.companion.domain.repositories

import me.nathanfallet.teamcode.companion.domain.models.McpServer

/**
 * The MCP servers assigned to the member on an account's server (read-only: declaring one is an administrator's action
 * in the web dashboard). Failures are [me.nathanfallet.teamcode.companion.domain.models.CompanionException]s.
 */
interface McpServerRepository {
    suspend fun servers(accountId: String): List<McpServer>
}
