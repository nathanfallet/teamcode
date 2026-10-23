package me.nathanfallet.teamcode.companion

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import me.nathanfallet.teamcode.companion.domain.repositories.Features
import me.nathanfallet.teamcode.companion.domain.services.LocalAvailability
import me.nathanfallet.teamcode.companion.domain.services.LocalHost
import me.nathanfallet.teamcode.companion.domain.services.LocalSessionRequest
import me.nathanfallet.teamcode.companion.domain.usecases.auth.AccountGuard
import me.nathanfallet.teamcode.companion.domain.usecases.auth.RefreshAccountUseCase
import me.nathanfallet.teamcode.companion.domain.usecases.sessions.SendMessageUseCase
import me.nathanfallet.teamcode.companion.domain.usecases.sessions.StartLocalSessionUseCase
import me.nathanfallet.teamcode.companion.presentation.navigation.NewSessionRoute
import me.nathanfallet.teamcode.companion.presentation.screens.LocalSessionSheet
import me.nathanfallet.teamcode.companion.presentation.screens.NewSessionChooserSheet
import me.nathanfallet.teamcode.companion.presentation.theme.TeamCodeTheme
import me.nathanfallet.teamcode.companion.presentation.viewmodels.LocalSessionViewModel
import kotlin.test.*

/** "New session on this device" on the desktop target: the choice, what the CLI needs first, the form. */
@OptIn(ExperimentalTestApi::class)
class LocalSessionSheetTest {
    private class Host(var availability: LocalAvailability) : LocalHost {
        val started = mutableListOf<LocalSessionRequest>()
        override suspend fun availability(accountId: String) = availability
        override suspend fun pickDirectory() = "/Users/ada/git/paywall"
        override suspend fun start(accountId: String, request: LocalSessionRequest) = "s1".also { started += request }
    }

    private fun model(host: Host): LocalSessionViewModel {
        val features = FakeFeatures(FakeFeatures().features + Features.REMOTE_SESSIONS)
        val guard = AccountGuard(RefreshAccountUseCase(FakeAuthRepository(), InMemoryAccountRepository(), credentials(), FixedClock()))
        return LocalSessionViewModel(NewSessionRoute("cli"), StartLocalSessionUseCase(host, features, SendMessageUseCase(FakeSessionRepository(), features, guard, SequenceIds())), host)
    }

    @Test fun theChoiceOpensEitherForm() = runComposeUiTest {
        var choice = ""
        setContent { TeamCodeTheme { NewSessionChooserSheet(onHere = { choice = "here" }, onCloud = { choice = "cloud" }, onDismiss = {}) } }
        onNodeWithText("On this device").assertExists()
        onNodeWithTag("new-session-cloud").performClick()
        assertEquals("cloud", choice)
        onNodeWithTag("new-session-here").performClick()
        assertEquals("here", choice)
    }

    @Test fun aMissingCliShowsTheCommandAndNoForm() = runComposeUiTest {
        val host = Host(LocalAvailability.CliMissing("curl -fsSL https://example/install.sh | sh"))
        val viewModel = model(host)
        setContent { TeamCodeTheme { LocalSessionSheet(viewModel, onDismiss = {}, onStarted = {}) } }
        waitUntil { viewModel.uiState.value.availability != null }
        onNodeWithTag("local-command").assertTextContains("curl -fsSL https://example/install.sh | sh")
        onNodeWithTag("local-start").assertDoesNotExist()
        host.availability = LocalAvailability.Ready
        onNodeWithTag("local-check-again").performClick()
        waitUntil { viewModel.uiState.value.availability == LocalAvailability.Ready }
        onNodeWithTag("local-start").assertIsNotEnabled()
    }

    @Test fun aFolderThenStartOpensTheSessionAndBypassWarns() = runComposeUiTest {
        val host = Host(LocalAvailability.Ready)
        val viewModel = model(host)
        var opened: String? = null
        setContent { TeamCodeTheme { LocalSessionSheet(viewModel, onDismiss = {}, onStarted = { opened = it }) } }
        waitUntil { viewModel.uiState.value.availability != null }
        onNodeWithTag("local-choose-folder").performClick()
        waitUntil { viewModel.uiState.value.directory.isNotEmpty() }
        onNodeWithTag("local-directory").assertTextContains("/Users/ada/git/paywall")
        onNodeWithTag("local-bypass-warning").assertDoesNotExist()
        onNodeWithTag("local-bypass").performClick()
        onNodeWithTag("local-bypass-warning").assertExists()
        onNodeWithTag("local-start").assertIsEnabled().performClick()
        waitUntil { opened != null }
        assertEquals("s1", opened)
        assertEquals("paywall", host.started.single().name)
        assertTrue(host.started.single().bypassPermissions)
    }
}
