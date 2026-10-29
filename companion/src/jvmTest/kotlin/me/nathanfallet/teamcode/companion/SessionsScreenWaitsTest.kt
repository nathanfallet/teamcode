package me.nathanfallet.teamcode.companion

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
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
import me.nathanfallet.teamcode.companion.domain.models.WaitSource
import me.nathanfallet.teamcode.companion.domain.repositories.WaitRepository
import me.nathanfallet.teamcode.companion.domain.usecases.auth.AccountGuard
import me.nathanfallet.teamcode.companion.domain.usecases.auth.RefreshAccountUseCase
import me.nathanfallet.teamcode.companion.domain.usecases.sessions.FollowSessionListUseCase
import me.nathanfallet.teamcode.companion.domain.usecases.sessions.GetSessionUseCase
import me.nathanfallet.teamcode.companion.domain.usecases.sessions.LoadSessionsUseCase
import me.nathanfallet.teamcode.companion.domain.usecases.sessions.SessionFeed
import me.nathanfallet.teamcode.companion.domain.usecases.waits.AnswerWaitUseCase
import me.nathanfallet.teamcode.companion.domain.usecases.waits.FollowWaitsUseCase
import me.nathanfallet.teamcode.companion.domain.usecases.waits.LoadWaitsUseCase
import me.nathanfallet.teamcode.companion.presentation.screens.SessionsScreen
import me.nathanfallet.teamcode.companion.presentation.theme.TeamCodeTheme
import me.nathanfallet.teamcode.companion.presentation.viewmodels.HomeViewModel
import me.nathanfallet.teamcode.companion.presentation.viewmodels.SessionsViewModel
import me.nathanfallet.teamcode.companion.presentation.viewmodels.WaitsViewModel
import kotlin.test.*

/** "Waiting for you" above the sessions: shown from the server, kept current by the feed, answered in place. */
@OptIn(ExperimentalTestApi::class)
class SessionsScreenWaitsTest {
    private class Waits : WaitRepository {
        var open = emptyList<Wait>()
        val answers = mutableListOf<String>()
        override suspend fun open(accountId: String, before: String?) = WaitPage(open, null)
        override suspend fun answer(accountId: String, waitId: String, action: String, values: Map<String, JsonElement>): Wait {
            answers += "$waitId $action ${values.keys}"
            open = open.filterNot { it.id == waitId }
            return open.firstOrNull() ?: Wait(waitId, WaitSource.WAIT, WaitKind.HUMAN_REQUEST, false, "s1", null, null, "m1", "x", null, emptyList(), emptyList(), emptyList(), 1)
        }
    }

    private val review = Wait(
        "w1", WaitSource.WAIT, WaitKind.HUMAN_REQUEST, true, "s1", "Fix the build", null, "m1", "Review the plan", "Plan attached.", emptyList(),
        listOf(WaitField("feedback", FieldType.TEXT, "Feedback")),
        listOf(WaitAction("approve", "Approve"), WaitAction("request_changes", "Request changes", requires = listOf("feedback"))), 5,
    )

    @Test fun aWaitAppearsWhenTheFeedSaysSoIsFilledAndAnsweredInPlace() = runComposeUiTest {
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
        val list = SessionsViewModel(accounts, LoadSessionsUseCase(sessions, features, guard), GetSessionUseCase(sessions, guard), FollowSessionListUseCase(shared, features, guard))
        val waits = Waits()
        val waitsViewModel = WaitsViewModel(accounts, LoadWaitsUseCase(waits, features, guard), AnswerWaitUseCase(waits, guard), FollowWaitsUseCase(shared, features, guard))
        var openedSession: String? = null
        setContent {
            TeamCodeTheme {
                SessionsScreen(
                    onSignInAgain = {}, onOpenSession = { _, _ -> }, viewModel = HomeViewModel(accounts, refresh), sessionsViewModel = list,
                    waitsViewModel = waitsViewModel, onOpenSessionById = { _, id -> openedSession = id }, clock = FixedClock(),
                )
            }
        }
        feed.trySend(LiveUpdate.Opened(false))
        waitUntil { list.uiState.value.list?.sessions?.isNotEmpty() == true && waitsViewModel.uiState.value.loaded }
        onNodeWithTag("waits-title").assertDoesNotExist()

        // A wait asked after the screen opened shows by itself, from the feed.
        waits.open = listOf(review)
        feed.trySend(LiveUpdate.Item(SessionChange(1, SessionChange.WAIT_CREATED, "s1", "m1", 6_000, null, null, waitId = "w1")))
        waitUntil { waitsViewModel.uiState.value.waits.isNotEmpty() }
        onNodeWithTag("waits-title").assertExists()
        onNodeWithText("Review the plan").assertExists()
        onNodeWithTag("wait-open").performClick()
        assertEquals("s1", openedSession)

        // Requesting changes needs the feedback: nothing is sent until it is there.
        onNodeWithTag("wait-action-request_changes").performClick()
        waitUntil { waitsViewModel.uiState.value.drafts["w1"]?.problem != null }
        onNodeWithTag("wait-problem").assertExists()
        assertEquals(emptyList(), waits.answers)
        onNodeWithTag("wait-field-feedback").performTextInput("Split step 2")
        onNodeWithTag("wait-action-request_changes").performClick()
        waitUntil { waits.answers.isNotEmpty() && waitsViewModel.uiState.value.waits.isEmpty() }
        assertEquals(listOf("w1 request_changes [feedback]"), waits.answers)
        onNodeWithTag("waits-title").assertDoesNotExist()
    }
}
