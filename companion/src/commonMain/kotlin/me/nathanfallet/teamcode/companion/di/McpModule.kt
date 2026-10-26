package me.nathanfallet.teamcode.companion.di

import me.nathanfallet.teamcode.companion.domain.repositories.McpServerRepository
import me.nathanfallet.teamcode.companion.domain.usecases.mcp.LoadMcpServersUseCase
import me.nathanfallet.teamcode.companion.domain.usecases.mcp.McpServersAvailabilityUseCase
import me.nathanfallet.teamcode.companion.infrastructure.repositories.RemoteMcpServerRepository
import me.nathanfallet.teamcode.companion.presentation.navigation.McpServersRoute
import me.nathanfallet.teamcode.companion.presentation.viewmodels.McpServersViewModel
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

/** The MCP servers assigned to the member (`mcp.gateway.v1`), read-only. */
val mcpModule = module {
    single<McpServerRepository> { RemoteMcpServerRepository(get()) }
    single { McpServersAvailabilityUseCase(get(), get()) }
    single { LoadMcpServersUseCase(get(), get(), get()) }
    viewModel { (route: McpServersRoute) -> McpServersViewModel(route, get()) }
}
