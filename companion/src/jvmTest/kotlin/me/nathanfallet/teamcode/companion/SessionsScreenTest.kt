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
import me.nathanfallet.teamcode.companion.domain.models.SessionFilter
import me.nathanfallet.teamcode.companion.domain.models.SessionPage
import me.nathanfallet.teamcode.companion.domain.models.SessionState
import me.nathanfallet.teamcode.companion.domain.usecases.auth.AccountGuard
import me.nathanfallet.teamcode.companion.domain.usecases.auth.RefreshAccountUseCase
import me.nathanfallet.teamcode.companion.domain.usecases.sessions.FollowSessionListUseCase
import me.nathanfallet.teamcode.companion.domain.usecases.sessions.GetSessionUseCase
import me.nathanfallet.teamcode.companion.domain.usecases.sessions.LoadSessionsUseCase
import me.nathanfallet.teamcode.companion.presentation.screens.SessionsScreen
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
        sessions.pages = { filter, _ -> SessionPage(if (filter != SessionFilter.ENDED) listOf(cloudSession("s1", SessionState.ACTIVE)) else emptyList(), null) }
        val features = FakeFeatures()
        val refresh = RefreshAccountUseCase(auth, accounts, store, FixedClock())
        val guard = AccountGuard(refresh)
        val list = SessionsViewModel(accounts, LoadSessionsUseCase(sessions, features, guard), GetSessionUseCase(sessions, guard), FollowSessionListUseCase(sessions, features, guard))
        var opened: String? = null
        setContent {
            TeamCodeTheme {
                SessionsScreen(onSignInAgain = {}, onOpenSession = { _, s -> opened = s.id }, viewModel = HomeViewModel(accounts, refresh), sessionsViewModel = list, clock = FixedClock())
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

        // The bar of states and the cloud / remote bar are gone: one pill next to the "+" button picks the filter.
        onNodeWithTag("group-paused").assertDoesNotExist()
        onNodeWithTag("kind-remote").assertDoesNotExist()
        onNodeWithTag("filter").performClick()
        onNodeWithTag("filter-all").assertExists()
        onNodeWithTag("filter-open").assertExists()
        onNodeWithTag("filter-ended").performClick()
        waitUntil { sessions.calls.lastOrNull() == "list MINE ENDED null" && !list.uiState.value.loading }
        onNodeWithText("No ended session").assertExists()
        // A member sees no team filter.
        onNodeWithTag("scope-team").assertDoesNotExist()
    }

    @Test fun anAdministratorChoosesTheTeamFromTheScopePill() = runComposeUiTest {
        val accounts = InMemoryAccountRepository(listOf(account("a1").copy(role = "ADMIN")))
        val store = credentials().also { kotlinx.coroutines.runBlocking { it.write("a1", "token") } }
        val auth = FakeAuthRepository().apply { meResult = { member(role = "ADMIN") } }
        val sessions = FakeSessionRepository()
        val feed = Channel<LiveUpdate<SessionChange>>(Channel.UNLIMITED)
        sessions.changes = feed.receiveAsFlow()
        sessions.pages = { _, _ -> SessionPage(emptyList(), null) }
        val features = FakeFeatures()
        val refresh = RefreshAccountUseCase(auth, accounts, store, FixedClock())
        val guard = AccountGuard(refresh)
        val list = SessionsViewModel(accounts, LoadSessionsUseCase(sessions, features, guard), GetSessionUseCase(sessions, guard), FollowSessionListUseCase(sessions, features, guard))
        var newSession = false
        setContent {
            TeamCodeTheme {
                SessionsScreen(onSignInAgain = {}, onOpenSession = { _, _ -> }, onNewSession = { newSession = true }, viewModel = HomeViewModel(accounts, refresh), sessionsViewModel = list, clock = FixedClock())
            }
        }
        feed.trySend(LiveUpdate.Opened(false))
        waitUntil { list.uiState.value.list != null && !list.uiState.value.loading }
        // The empty active list offers a new session, like the + button.
        onNodeWithTag("sessions-empty").assertExists()
        onNodeWithText("No active session").assertExists()
        onNodeWithTag("new-session").performClick()
        assertTrue(newSession)

        onNodeWithTag("scope").assertExists().performClick()
        onNodeWithTag("scope-team").performClick()
        waitUntil { sessions.calls.lastOrNull() == "list TEAM OPEN null" && !list.uiState.value.loading }
        onNodeWithText("Team").assertExists()
    }

    @Test fun withRemoteSessionsRowsSayWhereTheyRun() = runComposeUiTest {
        val accounts = InMemoryAccountRepository(listOf(account("a1")))
        val store = credentials().also { kotlinx.coroutines.runBlocking { it.write("a1", "token") } }
        val auth = FakeAuthRepository()
        val sessions = FakeSessionRepository()
        val feed = Channel<LiveUpdate<SessionChange>>(Channel.UNLIMITED)
        sessions.changes = feed.receiveAsFlow()
        sessions.pages = { _, _ ->
            SessionPage(listOf(cloudSession("r1", SessionState.PAUSED, createdAt = 2_000, host = laptop(online = false), problemCode = "host_offline"),
                cloudSession("c1", SessionState.PAUSED)), null)
        }
        val features = FakeFeatures(setOf("cloud.sessions.v1", "cloud.events.v1", "cloud.sessions.remote.v1"))
        val refresh = RefreshAccountUseCase(auth, accounts, store, FixedClock())
        val guard = AccountGuard(refresh)
        val list = SessionsViewModel(accounts, LoadSessionsUseCase(sessions, features, guard), GetSessionUseCase(sessions, guard), FollowSessionListUseCase(sessions, features, guard))
        setContent {
            TeamCodeTheme {
                SessionsScreen(onSignInAgain = {}, onOpenSession = { _, _ -> }, viewModel = HomeViewModel(accounts, refresh), sessionsViewModel = list, clock = FixedClock())
            }
        }
        feed.trySend(LiveUpdate.Opened(false))
        waitUntil { list.uiState.value.remoteSessions && !list.uiState.value.loading }
        waitUntil { list.uiState.value.list?.sessions?.isNotEmpty() == true }
        onNodeWithText("Remote · Ada's MacBook").assertExists()
        onNodeWithTag("offline-r1", useUnmergedTree = true).assertExists()
        onNodeWithTag("where-c1", useUnmergedTree = true).assertExists()
    }

    @Test fun anOlderServerShowsNoKind() = runComposeUiTest {
        val accounts = InMemoryAccountRepository(listOf(account("a1")))
        val store = credentials().also { kotlinx.coroutines.runBlocking { it.write("a1", "token") } }
        val sessions = FakeSessionRepository()
        val feed = Channel<LiveUpdate<SessionChange>>(Channel.UNLIMITED)
        sessions.changes = feed.receiveAsFlow()
        sessions.pages = { _, _ -> SessionPage(listOf(cloudSession("c1", SessionState.ACTIVE)), null) }
        val features = FakeFeatures()
        val refresh = RefreshAccountUseCase(FakeAuthRepository(), accounts, store, FixedClock())
        val guard = AccountGuard(refresh)
        val list = SessionsViewModel(accounts, LoadSessionsUseCase(sessions, features, guard), GetSessionUseCase(sessions, guard), FollowSessionListUseCase(sessions, features, guard))
        setContent {
            TeamCodeTheme {
                SessionsScreen(onSignInAgain = {}, onOpenSession = { _, _ -> }, viewModel = HomeViewModel(accounts, refresh), sessionsViewModel = list, clock = FixedClock())
            }
        }
        feed.trySend(LiveUpdate.Opened(false))
        waitUntil { list.uiState.value.list?.sessions?.isNotEmpty() == true }
        onNodeWithText("Session c1").assertExists()
        onNodeWithTag("where-c1", useUnmergedTree = true).assertDoesNotExist()
        assertEquals(listOf("feed", "list MINE OPEN null"), sessions.calls)
    }
}
