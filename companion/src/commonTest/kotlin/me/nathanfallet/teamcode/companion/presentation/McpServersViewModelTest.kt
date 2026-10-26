package me.nathanfallet.teamcode.companion.presentation

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import me.nathanfallet.teamcode.companion.FakeAuthRepository
import me.nathanfallet.teamcode.companion.FakeFeatures
import me.nathanfallet.teamcode.companion.FakeMcpServerRepository
import me.nathanfallet.teamcode.companion.FixedClock
import me.nathanfallet.teamcode.companion.InMemoryAccountRepository
import me.nathanfallet.teamcode.companion.account
import me.nathanfallet.teamcode.companion.credentials
import me.nathanfallet.teamcode.companion.domain.models.CompanionException
import me.nathanfallet.teamcode.companion.domain.usecases.auth.AccountGuard
import me.nathanfallet.teamcode.companion.domain.usecases.auth.RefreshAccountUseCase
import me.nathanfallet.teamcode.companion.domain.usecases.mcp.LoadMcpServersUseCase
import me.nathanfallet.teamcode.companion.mcpServer
import me.nathanfallet.teamcode.companion.presentation.navigation.McpServersRoute
import me.nathanfallet.teamcode.companion.presentation.viewmodels.McpServersViewModel
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class McpServersViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)
    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private val repository = FakeMcpServerRepository()
    private val features = FakeFeatures(setOf("mcp.gateway.v1"))

    private fun model(): McpServersViewModel {
        val accounts = InMemoryAccountRepository(listOf(account()))
        val guard = AccountGuard(RefreshAccountUseCase(FakeAuthRepository(), accounts, credentials(), FixedClock()))
        return McpServersViewModel(McpServersRoute("a1"), LoadMcpServersUseCase(repository, features, guard))
    }

    private fun TestScope.settle() = runCurrent()

    @Test fun theServersLoadOnceWithTheirState() = runTest(dispatcher) {
        val model = model()
        assertTrue(model.uiState.value.loading)
        assertNull(model.uiState.value.servers)
        settle()
        val state = model.uiState.value
        assertFalse(state.loading)
        assertNull(state.error)
        assertEquals(5, state.servers?.size)
        assertEquals(1, repository.calls.size)
    }

    @Test fun noServerIsAnEmptyList() = runTest(dispatcher) {
        repository.servers = emptyList()
        val model = model(); settle()
        assertTrue(model.uiState.value.empty)
    }

    @Test fun aFailureCanBeRetried() = runTest(dispatcher) {
        repository.failure = CompanionException.Unreachable()
        val model = model(); settle()
        assertNotNull(model.uiState.value.error)
        assertNull(model.uiState.value.servers)
        assertFalse(model.uiState.value.empty)
        repository.failure = null
        model.refresh(); settle()
        assertNull(model.uiState.value.error)
        assertEquals(5, model.uiState.value.servers?.size)
    }

    @Test fun aServerWithoutTheGatewayIsReportedAsNotOfferingIt() = runTest(dispatcher) {
        features.features = emptySet()
        val model = model(); settle()
        assertNotNull(model.uiState.value.error)
        assertTrue(repository.calls.isEmpty())
    }

    @Test fun refreshingKeepsTheServersShownAndTakesTheNewState() = runTest(dispatcher) {
        repository.servers = listOf(mcpServer("github", state = "starting"))
        val model = model(); settle()
        repository.servers = listOf(mcpServer("github", state = "ready"))
        model.refresh()
        assertTrue(model.uiState.value.loading)
        assertEquals("starting", model.uiState.value.servers?.single()?.state)
        settle()
        assertEquals("ready", model.uiState.value.servers?.single()?.state)
    }

    @Test fun aRefreshWhileReadingDoesNotAskTwice() = runTest(dispatcher) {
        val model = model()
        model.refresh(); model.refresh(); settle()
        assertEquals(1, repository.calls.size)
    }
}
