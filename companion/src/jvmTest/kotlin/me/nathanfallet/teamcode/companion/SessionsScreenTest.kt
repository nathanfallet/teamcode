package me.nathanfallet.teamcode.companion

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import me.nathanfallet.teamcode.companion.domain.models.LiveUpdate
import me.nathanfallet.teamcode.companion.domain.models.SessionChange
import me.nathanfallet.teamcode.companion.domain.models.SessionGroup
import me.nathanfallet.teamcode.companion.domain.models.SessionPage
import me.nathanfallet.teamcode.companion.domain.models.SessionState
import me.nathanfallet.teamcode.companion.domain.usecases.auth.AccountGuard
import me.nathanfallet.teamcode.companion.domain.usecases.auth.RefreshAccountUseCase
import me.nathanfallet.teamcode.companion.domain.usecases.sessions.FollowSessionListUseCase
import me.nathanfallet.teamcode.companion.domain.usecases.sessions.GetSessionUseCase
import me.nathanfallet.teamcode.companion.domain.usecases.sessions.LoadSessionsUseCase
import me.nathanfallet.teamcode.companion.presentation.screens.HomeScreen
import me.nathanfallet.teamcode.companion.presentation.theme.TeamCodeTheme
import me.nathanfallet.teamcode.companion.presentation.viewmodels.HomeViewModel
import me.nathanfallet.teamcode.companion.presentation.viewmodels.SessionsViewModel
import kotlin.test.*

/** The sessions list on the desktop target: rows from the server, a finished turn marked live, filters reloading. */
@OptIn(ExperimentalTestApi::class)
class SessionsScreenTest {
    @Test fun rowsShowAndAFinishedTurnIsMarkedUntilTheSessionIsOpened() = runComposeUiTest {
        val accounts = InMemoryAccountRepository(listOf(account("a1")))
        // A stored token: the home screen's refresh keeps the account signed in.
        val store = credentials().also { kotlinx.coroutines.runBlocking { it.write("a1", "token") } }
        val auth = FakeAuthRepository()
        val sessions = FakeSessionRepository()
        val feed = Channel<LiveUpdate<SessionChange>>(Channel.UNLIMITED)
        sessions.changes = feed.receiveAsFlow()
        sessions.pages = { group, _ -> SessionPage(if (group == SessionGroup.ACTIVE) listOf(cloudSession("s1", SessionState.ACTIVE)) else emptyList(), null) }
        val features = FakeFeatures()
        val refresh = RefreshAccountUseCase(auth, accounts, store, FixedClock())
        val guard = AccountGuard(refresh)
        val list = SessionsViewModel(accounts, LoadSessionsUseCase(sessions, features, guard), GetSessionUseCase(sessions, guard), FollowSessionListUseCase(sessions, features, guard))
        var opened: String? = null
        setContent {
            TeamCodeTheme {
                HomeScreen(onOpenSettings = {}, onSignInAgain = {}, onOpenSession = { _, s -> opened = s.id }, viewModel = HomeViewModel(accounts, refresh), sessionsViewModel = list, clock = FixedClock())
            }
        }
        feed.trySend(LiveUpdate.Opened(false))
        waitUntil { list.uiState.value.list?.sessions?.isNotEmpty() == true }
        onNodeWithText("Session s1").assertExists()
        onNodeWithText("Working").assertExists()

        feed.trySend(LiveUpdate.Item(SessionChange(1, "cloud.turn", "s1", "m1", 2_000, null, "completed")))
        waitUntil { list.uiState.value.list?.finishedTurns?.contains("s1") == true }
        onNodeWithTag("finished-s1", useUnmergedTree = true).assertExists()
        onNodeWithText("Ready").assertExists()
        onNodeWithTag("session-s1").performClick()
        assertEquals("s1", opened)
        waitForIdle()
        onNodeWithTag("finished-s1", useUnmergedTree = true).assertDoesNotExist()

        onNodeWithTag("group-paused").performClick()
        waitUntil { sessions.calls.lastOrNull() == "list MINE PAUSED null" && !list.uiState.value.loading }
        onNodeWithText("No paused session.").assertExists()
        // A member sees no team filter.
        onNodeWithTag("scope-team").assertDoesNotExist()
    }
}
