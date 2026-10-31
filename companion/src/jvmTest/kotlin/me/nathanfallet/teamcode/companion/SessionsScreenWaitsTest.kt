package me.nathanfallet.teamcode.companion

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.serialization.json.JsonElement
import me.nathanfallet.teamcode.companion.domain.models.LiveUpdate
import me.nathanfallet.teamcode.companion.domain.models.SessionChange
import me.nathanfallet.teamcode.companion.domain.models.SessionPage
import me.nathanfallet.teamcode.companion.domain.models.SessionState
import me.nathanfallet.teamcode.companion.domain.models.Wait
import me.nathanfallet.teamcode.companion.domain.models.WaitAction
import me.nathanfallet.teamcode.companion.domain.models.WaitField
import me.nathanfallet.teamcode.companion.domain.models.FieldType
import me.nathanfallet.teamcode.companion.domain.models.WaitKind
import me.nathanfallet.teamcode.companion.domain.models.WaitPage
import me.nathanfallet.teamcode.companion.domain.models.WaitStatus
import me.nathanfallet.teamcode.companion.domain.models.WaitSource
import me.nathanfallet.teamcode.companion.domain.repositories.WaitRepository
import me.nathanfallet.teamcode.companion.domain.usecases.auth.AccountGuard
import me.nathanfallet.teamcode.companion.domain.usecases.auth.RefreshAccountUseCase
import me.nathanfallet.teamcode.companion.domain.usecases.sessions.FollowSessionListUseCase
import me.nathanfallet.teamcode.companion.domain.usecases.sessions.GetSessionUseCase
import me.nathanfallet.teamcode.companion.domain.usecases.sessions.LoadSessionsUseCase
import me.nathanfallet.teamcode.companion.domain.usecases.sessions.SessionFeed
import me.nathanfallet.teamcode.companion.domain.usecases.waits.AnswerWaitUseCase
import me.nathanfallet.teamcode.companion.domain.usecases.waits.CancelWaitUseCase
import me.nathanfallet.teamcode.companion.domain.usecases.waits.LoadInProgressWaitsUseCase
import me.nathanfallet.teamcode.companion.domain.usecases.waits.FollowWaitsUseCase
import me.nathanfallet.teamcode.companion.domain.usecases.waits.LoadWaitsUseCase
import me.nathanfallet.teamcode.companion.presentation.screens.SessionsScreen
import me.nathanfallet.teamcode.companion.presentation.theme.TeamCodeTheme
import me.nathanfallet.teamcode.companion.presentation.viewmodels.HomeViewModel
import me.nathanfallet.teamcode.companion.presentation.viewmodels.SessionsViewModel
import me.nathanfallet.teamcode.companion.presentation.viewmodels.WaitsViewModel
import kotlin.test.*

/** The sessions tab's reminder of what waits: counted by the feed, hidden when nothing waits, opening the waits screen. */
@OptIn(ExperimentalTestApi::class)
class SessionsScreenWaitsTest {
    private class Waits : WaitRepository {
        var open = emptyList<Wait>()
        override suspend fun list(accountId: String, status: WaitStatus, before: String?) = WaitPage(if (status == WaitStatus.OPEN) open else emptyList(), null)
        override suspend fun answer(accountId: String, waitId: String, action: String, values: Map<String, JsonElement>): Wait = error("The banner answers nothing")
    }

    private fun wait(id: String, title: String) = Wait(
        id, WaitSource.WAIT, WaitKind.HUMAN_REQUEST, true, "s1", "Fix the build", null, "m1", title, "Plan attached.", emptyList(),
        listOf(WaitField("feedback", FieldType.TEXT, "Feedback")), listOf(WaitAction("approve", "Approve")), 5,
    )

    @Test fun theBannerCountsWhatWaitsFromTheFeedAndOpensTheWaitsScreen() = runComposeUiTest {
        val accounts = InMemoryAccountRepository(listOf(account("a1")))
        val store = credentials().also { kotlinx.coroutines.runBlocking { it.write("a1", "token") } }
        val refresh = RefreshAccountUseCase(FakeAuthRepository(), accounts, store, FixedClock())
        val guard = AccountGuard(refresh)
        val sessions = FakeSessionRepository()
        val feed = Channel<LiveUpdate<SessionChange>>(Channel.UNLIMITED)
        sessions.changes = feed.receiveAsFlow()
        sessions.pages = { _, _ -> SessionPage(listOf(cloudSession("s1", SessionState.ACTIVE)), null) }
        val features = FakeFeatures().also { it.features += "waits.v1" }
        val shared = SessionFeed(sessions, features, CoroutineScope(SupervisorJob() + Dispatchers.Default))
        val list = SessionsViewModel(accounts, LoadSessionsUseCase(sessions, features, guard), GetSessionUseCase(sessions, guard), FollowSessionListUseCase(shared, features, guard), me.nathanfallet.teamcode.companion.domain.usecases.sessions.ControlSessionUseCase(sessions, guard, SequenceIds(), features))
        val waits = Waits()
        val waitsViewModel = WaitsViewModel(accounts, LoadWaitsUseCase(waits, features, guard), AnswerWaitUseCase(waits, guard), FollowWaitsUseCase(shared, features, guard), LoadInProgressWaitsUseCase(waits, features, guard), CancelWaitUseCase(waits, guard))
        // The signed-in shell follows the waits; the sessions list only shows them.
        waitsViewModel.start()
        var opened: String? = null
        setContent {
            TeamCodeTheme {
                SessionsScreen(
                    onSignInAgain = {}, onOpenSession = { _, _ -> }, viewModel = HomeViewModel(accounts, refresh), sessionsViewModel = list,
                    waitsViewModel = waitsViewModel, onOpenWaits = { opened = it.id }, clock = FixedClock(),
                )
            }
        }
        feed.trySend(LiveUpdate.Opened(false))
        waitUntil { list.uiState.value.list?.sessions?.isNotEmpty() == true && waitsViewModel.uiState.value.loaded }
        // Nothing waits: no banner.
        onNodeWithTag("waits-banner").assertDoesNotExist()

        // A wait asked after the screen opened shows by itself, from the feed: a count and the newest title, not the whole card.
        waits.open = listOf(wait("w1", "Review the plan"))
        feed.trySend(LiveUpdate.Item(SessionChange(1, SessionChange.WAIT_CREATED, "s1", "m1", 6_000, null, null, waitId = "w1")))
        waitUntil { waitsViewModel.uiState.value.waits.isNotEmpty() }
        onNodeWithTag("waits-banner").assertExists()
        onNodeWithTag("waits-banner-count", useUnmergedTree = true).assertTextEquals("1 waiting for you")
        onNodeWithTag("waits-banner-title", useUnmergedTree = true).assertTextEquals("Review the plan")
        onNodeWithTag("wait-action-approve").assertDoesNotExist()

        waits.open = listOf(wait("w2", "Pick a store"), wait("w1", "Review the plan"))
        feed.trySend(LiveUpdate.Item(SessionChange(2, SessionChange.WAIT_CREATED, "s1", "m1", 7_000, null, null, waitId = "w2")))
        waitUntil { waitsViewModel.uiState.value.waits.size == 2 }
        onNodeWithTag("waits-banner-count", useUnmergedTree = true).assertTextEquals("2 waiting for you")
        onNodeWithTag("waits-banner-title", useUnmergedTree = true).assertTextEquals("Pick a store")

        onNodeWithTag("waits-banner").performClick()
        assertEquals("a1", opened)

        // Answered or withdrawn elsewhere: the banner goes away.
        waits.open = emptyList()
        feed.trySend(LiveUpdate.Item(SessionChange(3, SessionChange.WAIT_RESOLVED, "s1", "m1", 8_000, null, null, waitId = "w1")))
        waitUntil { waitsViewModel.uiState.value.waits.isEmpty() }
        onNodeWithTag("waits-banner").assertDoesNotExist()
    }
}
