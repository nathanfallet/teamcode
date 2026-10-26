package me.nathanfallet.teamcode.companion

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import me.nathanfallet.teamcode.companion.domain.models.CompanionException
import me.nathanfallet.teamcode.companion.domain.usecases.auth.AccountGuard
import me.nathanfallet.teamcode.companion.domain.usecases.auth.RefreshAccountUseCase
import me.nathanfallet.teamcode.companion.domain.usecases.mcp.LoadMcpServersUseCase
import me.nathanfallet.teamcode.companion.presentation.navigation.McpServersRoute
import me.nathanfallet.teamcode.companion.presentation.screens.McpServersScreen
import me.nathanfallet.teamcode.companion.presentation.theme.TeamCodeTheme
import me.nathanfallet.teamcode.companion.presentation.viewmodels.McpServersViewModel
import kotlin.test.*

/** The MCP servers screen on the desktop target, on demo data: the list with each state, the empty state and a failure. */
@OptIn(ExperimentalTestApi::class)
class McpServersScreenTest {
    private val repository = FakeMcpServerRepository()

    private fun model(): McpServersViewModel {
        val accounts = InMemoryAccountRepository(listOf(account()))
        val guard = AccountGuard(RefreshAccountUseCase(FakeAuthRepository(), accounts, credentials(), FixedClock()))
        return McpServersViewModel(McpServersRoute("a1"), LoadMcpServersUseCase(repository, FakeFeatures(setOf("mcp.gateway.v1")), guard))
    }

    /** The node tagged [tag] says [text], on itself (a text) or on its child (a chip). */
    private fun ComposeUiTest.assertTagged(tag: String, text: String) {
        onNode(hasTestTag(tag) and (hasText(text) or hasAnyDescendant(hasText(text)))).assertExists()
    }

    @Test fun eachServerShowsItsTypeAndItsState() = runComposeUiTest {
        val model = model()
        setContent { TeamCodeTheme { McpServersScreen(model, onBack = {}) } }
        waitUntil { !model.uiState.value.loading }
        assertTagged("mcp-type-github", "Hosted command")
        assertTagged("mcp-state-github", "Ready")
        assertTagged("mcp-state-playwright", "Starting")
        assertTagged("mcp-state-postgres", "Unavailable: did not start")
        assertTagged("mcp-state-filesystem", "Unavailable: stopped unexpectedly")
        assertTagged("mcp-type-docs", "Remote (HTTPS)")
        // The gateway does not track an HTTPS server: no state at all.
        onNodeWithTag("mcp-state-docs").assertDoesNotExist()
        onNodeWithText("teamcode-github").assertExists()
        onNodeWithTag("mcp-admin-note").assertTextEquals("Administrators declare MCP servers and see their logs in the web dashboard.")
        onNodeWithTag("mcp-empty").assertDoesNotExist()
    }

    @Test fun noServerAssignedShowsTheEmptyState() = runComposeUiTest {
        repository.servers = emptyList()
        val model = model()
        setContent { TeamCodeTheme { McpServersScreen(model, onBack = {}) } }
        waitUntil { !model.uiState.value.loading }
        onNodeWithTag("mcp-empty").assertExists()
        onNodeWithText("No MCP server is assigned to you.").assertExists()
    }

    @Test fun aFailureCanBeRetried() = runComposeUiTest {
        repository.failure = CompanionException.Unreachable()
        val model = model()
        setContent { TeamCodeTheme { McpServersScreen(model, onBack = {}) } }
        waitUntil { !model.uiState.value.loading }
        onNodeWithTag("mcp-error").assertExists()
        onNodeWithTag("mcp-empty").assertDoesNotExist()
        repository.failure = null
        onNodeWithTag("mcp-retry").performClick()
        waitUntil { model.uiState.value.servers != null }
        onNodeWithTag("mcp-error").assertDoesNotExist()
        assertTagged("mcp-state-github", "Ready")
    }
}
