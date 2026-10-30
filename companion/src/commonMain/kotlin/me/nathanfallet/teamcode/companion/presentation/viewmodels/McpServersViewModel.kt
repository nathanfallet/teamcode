package me.nathanfallet.teamcode.companion.presentation.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import me.nathanfallet.teamcode.companion.domain.models.McpServer
import me.nathanfallet.teamcode.companion.domain.usecases.mcp.LoadMcpServersUseCase
import me.nathanfallet.teamcode.companion.presentation.navigation.McpServersRoute

data class McpServersUiState(
    val loading: Boolean = true,
    /** Null until the server answered. */
    val servers: List<McpServer>? = null,
    val error: Failure? = null,
) {
    val empty: Boolean get() = servers?.isEmpty() == true && !loading
}

/** The MCP servers assigned to the member on one TeamCode account's server, with their state. Read-only. */
class McpServersViewModel(
    private val route: McpServersRoute,
    private val load: LoadMcpServersUseCase,
) : ViewModel() {
    private val state = MutableStateFlow(McpServersUiState())
    val uiState: StateFlow<McpServersUiState> = state.asStateFlow()

    private var reading: Job? = null

    init { refresh() }

    /** Reads the list again; the servers already shown stay while it loads, and after a failure. */
    fun refresh() {
        if (reading?.isActive == true) return
        state.update { it.copy(loading = true, error = null) }
        reading = viewModelScope.launch {
            try {
                val servers = load(route.accountId)
                state.update { it.copy(loading = false, servers = servers) }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                state.update { it.copy(loading = false, error = error.userMessage()) }
            }
        }
    }
}
