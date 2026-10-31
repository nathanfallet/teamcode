package me.nathanfallet.teamcode.companion

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.onAllNodesWithTag
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
import kotlinx.serialization.json.JsonPrimitive
import me.nathanfallet.teamcode.companion.domain.models.CompanionException
import me.nathanfallet.teamcode.companion.domain.models.FieldType
import me.nathanfallet.teamcode.companion.domain.models.LiveUpdate
import me.nathanfallet.teamcode.companion.domain.models.SessionChange
import me.nathanfallet.teamcode.companion.domain.models.Wait
import me.nathanfallet.teamcode.companion.domain.models.WaitAction
import me.nathanfallet.teamcode.companion.domain.models.WaitAnswer
import me.nathanfallet.teamcode.companion.domain.models.WaitField
import me.nathanfallet.teamcode.companion.domain.models.WaitKind
import me.nathanfallet.teamcode.companion.domain.models.WaitOption
import me.nathanfallet.teamcode.companion.domain.models.WaitPage
import me.nathanfallet.teamcode.companion.domain.models.WaitResolver
import me.nathanfallet.teamcode.companion.domain.models.WaitSource
import me.nathanfallet.teamcode.companion.domain.models.WaitStatus
import me.nathanfallet.teamcode.companion.domain.repositories.WaitRepository
import me.nathanfallet.teamcode.companion.domain.usecases.auth.AccountGuard
import me.nathanfallet.teamcode.companion.domain.usecases.auth.RefreshAccountUseCase
import me.nathanfallet.teamcode.companion.domain.usecases.sessions.SessionFeed
import me.nathanfallet.teamcode.companion.domain.usecases.waits.AnswerWaitUseCase
import me.nathanfallet.teamcode.companion.domain.usecases.waits.CancelWaitUseCase
import me.nathanfallet.teamcode.companion.domain.usecases.waits.LoadInProgressWaitsUseCase
import me.nathanfallet.teamcode.companion.domain.usecases.waits.FollowWaitsUseCase
import me.nathanfallet.teamcode.companion.domain.usecases.waits.LoadWaitsUseCase
import me.nathanfallet.teamcode.companion.presentation.screens.WaitsScreen
import me.nathanfallet.teamcode.companion.presentation.theme.TeamCodeTheme
import me.nathanfallet.teamcode.companion.presentation.viewmodels.WaitsViewModel
import kotlin.test.*

/** The waiting screen: every open wait of the member with its session, answered in place, the filters and pages, the states. */
@OptIn(ExperimentalTestApi::class)
class WaitsScreenTest {
    private class Waits : WaitRepository {
        val byStatus = mutableMapOf<WaitStatus, List<Wait>>()
        val answers = mutableListOf<String>()
        var pageSize = 50
        var failure: Exception? = null
        var timers = listOf<Wait>()
        val cancelled = mutableListOf<String>()
        override suspend fun inProgress(accountId: String): List<Wait> = timers
        override suspend fun cancel(accountId: String, waitId: String): Wait { cancelled += waitId; val gone = timers.first { it.id == waitId }; timers = timers - gone; return gone.copy(open = false, cancelled = true) }
        override suspend fun list(accountId: String, status: WaitStatus, before: String?): WaitPage {
            failure?.let { throw it }
            val all = byStatus[status].orEmpty()
            val rest = if (before == null) all else all.dropWhile { it.id != before }.drop(1)
            val page = rest.take(pageSize)
            return WaitPage(page, if (rest.size > page.size) page.last().id else null)
        }
        override suspend fun answer(accountId: String, waitId: String, action: String, values: Map<String, JsonElement>): Wait {
            answers += "$waitId $action ${values.keys}"
            val wait = byStatus.getValue(WaitStatus.OPEN).first { it.id == waitId }
            byStatus[WaitStatus.OPEN] = byStatus.getValue(WaitStatus.OPEN) - wait
            return wait.copy(open = false)
        }
    }

    private val feedback = WaitField("feedback", FieldType.TEXT, "Feedback")
    private val review = listOf(WaitAction("approve", "Approve"), WaitAction("request_changes", "Request changes", requires = listOf("feedback")))

    private fun wait(id: String, title: String, session: String, kind: WaitKind = WaitKind.HUMAN_REQUEST, createdAt: Long = 5, source: WaitSource = WaitSource.WAIT) = Wait(
        id, source, kind, true, "s-$id", session, if (source == WaitSource.SESSION) "req-$id" else null, "m1", title, "Details of $title.", emptyList(),
        if (source == WaitSource.WAIT) listOf(feedback) else emptyList(), if (source == WaitSource.WAIT) review else emptyList(), createdAt,
    )

    private class Screen(val model: WaitsViewModel, val waits: Waits, val feed: Channel<LiveUpdate<SessionChange>>, val opened: MutableList<String>)

    private fun ComposeUiTest.screen(configure: Waits.() -> Unit = {}, now: Long = 1_000_000L): Screen {
        val accounts = InMemoryAccountRepository(listOf(account("a1")))
        val store = credentials().also { kotlinx.coroutines.runBlocking { it.write("a1", "token") } }
        val guard = AccountGuard(RefreshAccountUseCase(FakeAuthRepository(), accounts, store, FixedClock()))
        val sessions = FakeSessionRepository()
        val feed = Channel<LiveUpdate<SessionChange>>(Channel.UNLIMITED)
        sessions.changes = feed.receiveAsFlow()
        val features = FakeFeatures().also { it.features += "waits.v1" }
        val shared = SessionFeed(sessions, features, CoroutineScope(SupervisorJob() + Dispatchers.Default))
        val waits = Waits().apply(configure)
        val model = WaitsViewModel(accounts, LoadWaitsUseCase(waits, features, guard), AnswerWaitUseCase(waits, guard), FollowWaitsUseCase(shared, features, guard), LoadInProgressWaitsUseCase(waits, features, guard), CancelWaitUseCase(waits, guard))
        model.start()
        val opened = mutableListOf<String>()
        setContent { TeamCodeTheme { WaitsScreen(model, onOpenSession = { account, session -> opened += "$account/$session" }, clock = FixedClock(now)) } }
        feed.trySend(LiveUpdate.Opened(false))
        return Screen(model, waits, feed, opened)
    }

    @Test fun everyOpenWaitShowsWithItsSessionKindAndAgeAndIsAnsweredInPlace() = runComposeUiTest {
        val screen = screen({
            byStatus[WaitStatus.OPEN] = listOf(
                wait("s9", "Run the migration?", "Audit webhooks", WaitKind.PERMISSION, createdAt = 940_000, source = WaitSource.SESSION),
                wait("w1", "Review the plan", "Fix the build", createdAt = 100_000),
            )
        })
        waitUntil { screen.model.uiState.value.loaded }
        onNodeWithTag("waits-badge").assertContentDescriptionEquals("2 waiting for you")
        hasTagged("wait-session-s9", "in Audit webhooks")
        hasTagged("wait-session-w1", "in Fix the build")
        hasTagged("wait-kind-s9", "Needs approval")
        hasTagged("wait-kind-w1", "Asks you")
        hasTagged("wait-age-s9", "1 min ago")
        hasTagged("wait-age-w1", "15 min ago")
        // A session's own request is answered in the session; an agent's request is answered here.
        onNodeWithTag("wait-s9").assertExists()
        // Both cards open their session; only the agent's request has fields and actions to answer here.
        onAllNodesWithTag("wait-open").assertCountEquals(2)

        onNodeWithTag("wait-action-request_changes").performClick()
        waitUntil { screen.model.uiState.value.drafts["w1"]?.problem != null }
        onNodeWithTag("wait-problem").assertExists()
        assertEquals(emptyList(), screen.waits.answers)
        onNodeWithTag("wait-field-feedback").performTextInput("Split step 2")
        onNodeWithTag("wait-action-request_changes").performClick()
        waitUntil { screen.waits.answers.isNotEmpty() && screen.model.uiState.value.waits.size == 1 }
        assertEquals(listOf("w1 request_changes [feedback]"), screen.waits.answers)
        onNodeWithTag("waits-badge").assertContentDescriptionEquals("1 waiting for you")
    }

    @Test fun whatTheServerWatchesShowsInProgressBelowWhatWaitsForTheMemberAndCanBeCancelled() = runComposeUiTest {
        val screen = screen({
            byStatus[WaitStatus.OPEN] = listOf(wait("w1", "Review the plan", "Fix the build", createdAt = 100_000))
            timers = listOf(
                wait("t1", "Check the nightly deploy", "Fix the build", WaitKind.TIMED).copy(resolver = WaitResolver.TIME, dueAt = 1_000_000 + 3 * 3_600_000, fields = emptyList(), actions = emptyList()),
                wait("t2", "CI of the PR", "Audit webhooks", WaitKind.TIMED).copy(resolver = WaitResolver.EVENT, timeoutAt = 1_000_000 + 20 * 3_600_000, onTimeout = "turn_failed", fields = emptyList(), actions = emptyList()),
            )
        })
        waitUntil { screen.model.uiState.value.loaded && screen.model.uiState.value.inProgress.size == 2 }
        onNodeWithTag("waits-badge").assertContentDescriptionEquals("1 waiting for you")
        onNodeWithTag("waits-in-progress-title").assertExists()
        onNodeWithTag("in-progress-t1").assertExists(); onNodeWithTag("in-progress-t2").assertExists()
        hasTagged("in-progress-kind-t1", "Timer"); hasTagged("in-progress-kind-t2", "Webhook")
        onNodeWithTag("in-progress-when-t1").assertTextContains("Resumes", substring = true)
        onNodeWithTag("in-progress-when-t2").assertTextContains("Waits for a webhook, times out", substring = true); onNodeWithTag("in-progress-when-t2").assertTextContains("then asks you", substring = true)
        onNodeWithTag("wait-t1").assertDoesNotExist()
        onNodeWithTag("in-progress-open-t1").performClick()
        assertEquals(listOf("a1/s-t1"), screen.opened)
        onNodeWithTag("in-progress-cancel-t1").performClick()
        waitUntil { screen.model.uiState.value.inProgress.size == 1 }
        assertEquals(listOf("t1"), screen.waits.cancelled)
        onNodeWithTag("in-progress-t1").assertDoesNotExist()
        // The section belongs to the open list: the answered one does not carry it.
        onNodeWithTag("waits-filter-answered").performClick()
        onNodeWithTag("waits-in-progress").assertDoesNotExist()
    }

    @Test fun theSessionIsOpenedFromItsCard() = runComposeUiTest {
        val screen = screen({ byStatus[WaitStatus.OPEN] = listOf(wait("s9", "Run the migration?", "Audit webhooks", WaitKind.PERMISSION, source = WaitSource.SESSION)) })
        waitUntil { screen.model.uiState.value.loaded }
        onNodeWithTag("wait-open").performClick()
        assertEquals(listOf("a1/s-s9"), screen.opened)
    }

    @Test fun aWaitAppearsAndLeavesLiveWithoutAnyPolling() = runComposeUiTest {
        val screen = screen()
        waitUntil { screen.model.uiState.value.loaded }
        // Nothing waits: the empty state says so.
        onNodeWithTag("waits-empty").assertExists()
        onNodeWithText("Nothing waits for you").assertExists()
        onNodeWithTag("waits-badge").assertDoesNotExist()

        screen.waits.byStatus[WaitStatus.OPEN] = listOf(wait("w1", "Review the plan", "Fix the build"))
        screen.feed.trySend(LiveUpdate.Item(SessionChange(1, SessionChange.WAIT_CREATED, "s-w1", "m1", 6_000, null, null, waitId = "w1")))
        waitUntil { screen.model.uiState.value.waits.isNotEmpty() }
        onNodeWithTag("wait-w1").assertExists()
        onNodeWithTag("waits-empty").assertDoesNotExist()

        screen.waits.byStatus[WaitStatus.OPEN] = emptyList()
        screen.feed.trySend(LiveUpdate.Item(SessionChange(2, SessionChange.WAIT_CANCELLED, "s-w1", "m1", 7_000, null, null, waitId = "w1")))
        waitUntil { screen.model.uiState.value.waits.isEmpty() }
        onNodeWithTag("wait-w1").assertDoesNotExist()
        onNodeWithTag("waits-empty").assertExists()
    }

    @Test fun theFiltersShowAnsweredWaitsWithWhatWasGivenAndCancelledOnes() = runComposeUiTest {
        val pick = WaitField("store", FieldType.CHOICE, "Store", options = listOf(WaitOption("ios", "App Store")))
        val answered = wait("w2", "Which store first?", "Ship it").copy(open = false, fields = listOf(pick, WaitField("ok", FieldType.CONFIRM, "Sure?")), resolvedAt = 960_000,
            answer = WaitAnswer("approve", mapOf("store" to JsonPrimitive("ios"), "ok" to JsonPrimitive(true)), "m1", 960_000))
        val cancelled = wait("w3", "Pick a layout", "Search page").copy(open = false, cancelled = true, resolvedAt = 700_000)
        val screen = screen({
            byStatus[WaitStatus.OPEN] = listOf(wait("w1", "Review the plan", "Fix the build"))
            byStatus[WaitStatus.RESOLVED] = listOf(answered)
            byStatus[WaitStatus.CANCELLED] = listOf(cancelled)
        })
        waitUntil { screen.model.uiState.value.loaded }
        onNodeWithTag("waits-filter-answered").performClick()
        waitUntil { screen.model.uiState.value.historyLoaded }
        onNodeWithTag("wait-w1").assertDoesNotExist()
        hasTagged("wait-status-w2", "Answered")
        onNodeWithTag("wait-action-approve").assertDoesNotExist()
        onNodeWithText("Answer: Approve").assertExists()
        onNodeWithText("Store: App Store").assertExists()
        onNodeWithText("Sure?: Yes").assertExists()
        // The time of an event on the day shown is the time of day, in UTC (the shared rule).
        onNodeWithTag("wait-outcome-w2").assertExists()
        onNodeWithText("Answered 00:16 UTC").assertExists()
        // The badge still counts the open waits.
        onNodeWithTag("waits-badge").assertContentDescriptionEquals("1 waiting for you")

        onNodeWithTag("waits-filter-cancelled").performClick()
        waitUntil { screen.model.uiState.value.historyLoaded && screen.model.uiState.value.history.firstOrNull()?.id == "w3" }
        hasTagged("wait-status-w3", "Cancelled")
        onNodeWithText("Cancelled 00:11 UTC").assertExists()

        onNodeWithTag("waits-filter-open").performClick()
        onNodeWithTag("wait-w1").assertExists()
    }

    private fun ComposeUiTest.hasTagged(tag: String, text: String) {
        onNode(hasTestTag(tag) and (hasText(text) or hasAnyDescendant(hasText(text))), useUnmergedTree = true).assertExists()
    }

    @Test fun morePagesLoadAsTheListEndsAndTheBadgeCountsThemAll() = runComposeUiTest {
        val screen = screen({
            pageSize = 2
            byStatus[WaitStatus.OPEN] = (5 downTo 1).map { wait("w$it", "Review $it", "Session $it") }
        })
        waitUntil { screen.model.uiState.value.loaded }
        // The first page holds two; the list is short, so its end is in view and the next pages follow by themselves (keyset: after the last id).
        waitUntil { screen.model.uiState.value.waits.size == 5 }
        assertEquals(listOf("w5", "w4", "w3", "w2", "w1"), screen.model.uiState.value.waits.map { it.id })
        assertNull(screen.model.uiState.value.nextBefore)
        onNodeWithTag("waits-badge").assertContentDescriptionEquals("5 waiting for you")
    }

    @Test fun aFailedLoadShowsAnErrorStateThatRetries() = runComposeUiTest {
        val screen = screen({ failure = CompanionException.Unreachable() })
        waitUntil { screen.model.uiState.value.error }
        onNodeWithTag("waits-error-state").assertExists()
        screen.waits.failure = null
        screen.waits.byStatus[WaitStatus.OPEN] = listOf(wait("w1", "Review the plan", "Fix the build"))
        onNodeWithTag("waits-retry").performClick()
        waitUntil { screen.model.uiState.value.waits.isNotEmpty() }
        onNodeWithTag("waits-error-state").assertDoesNotExist()
        onNodeWithTag("wait-w1").assertExists()
    }
}
