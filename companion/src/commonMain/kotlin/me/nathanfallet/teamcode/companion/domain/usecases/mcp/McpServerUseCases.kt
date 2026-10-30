package me.nathanfallet.teamcode.companion.domain.usecases.mcp

import kotlinx.coroutines.CancellationException
import me.nathanfallet.teamcode.companion.domain.models.CompanionException
import me.nathanfallet.teamcode.companion.domain.models.McpServer
import me.nathanfallet.teamcode.companion.domain.repositories.AccountRepository
import me.nathanfallet.teamcode.companion.domain.repositories.Features
import me.nathanfallet.teamcode.companion.domain.repositories.FeaturesRepository
import me.nathanfallet.teamcode.companion.domain.repositories.McpServerRepository
import me.nathanfallet.teamcode.companion.domain.usecases.auth.AccountGuard

/** Whether the MCP servers screen is offered: a signed-in account on a server that announces `mcp.gateway.v1`. */
class McpServersAvailabilityUseCase(private val accounts: AccountRepository, private val features: FeaturesRepository) {
    suspend fun available(accountId: String): Boolean {
        val account = accounts.accounts.value.firstOrNull { it.id == accountId } ?: return false
        if (!account.signedIn) return false
        return try {
            Features.MCP_GATEWAY in features.features(accountId)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            // Unknown for now (offline): hidden rather than offered and refused.
            false
        }
    }
}

/** The member's MCP servers, by name; a server without the gateway is [CompanionException.Unsupported]. */
class LoadMcpServersUseCase(private val repository: McpServerRepository, private val features: FeaturesRepository, private val guard: AccountGuard) {
    suspend operator fun invoke(accountId: String): List<McpServer> = guard(accountId) {
        if (Features.MCP_GATEWAY !in features.features(accountId, refresh = true)) throw CompanionException.Unsupported(Features.MCP_GATEWAY)
        repository.servers(accountId).sortedBy { it.name.lowercase() }
    }
}
