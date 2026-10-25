package me.nathanfallet.teamcode.companion

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.SoftwareKeyboardController
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.emptyFlow
import me.nathanfallet.teamcode.companion.domain.models.LiveUpdate
import me.nathanfallet.teamcode.companion.domain.models.SessionState
import me.nathanfallet.teamcode.companion.domain.models.TranscriptEvent
import me.nathanfallet.teamcode.companion.domain.models.TranscriptPage
import me.nathanfallet.teamcode.companion.domain.repositories.TranscriptRepository
import me.nathanfallet.teamcode.companion.domain.usecases.auth.AccountGuard
import me.nathanfallet.teamcode.companion.domain.usecases.auth.RefreshAccountUseCase
import me.nathanfallet.teamcode.companion.domain.usecases.sessions.AnswerPermissionUseCase
import me.nathanfallet.teamcode.companion.domain.usecases.sessions.ControlSessionUseCase
import me.nathanfallet.teamcode.companion.domain.usecases.sessions.GetSessionUseCase
import me.nathanfallet.teamcode.companion.domain.usecases.sessions.SendMessageUseCase
import me.nathanfallet.teamcode.companion.domain.usecases.transcript.FollowTranscriptUseCase
import me.nathanfallet.teamcode.companion.domain.usecases.transcript.LoadTranscriptUseCase
import me.nathanfallet.teamcode.companion.presentation.navigation.SessionRoute
import me.nathanfallet.teamcode.companion.presentation.screens.SessionScreen
import me.nathanfallet.teamcode.companion.presentation.theme.TeamCodeTheme
import me.nathanfallet.teamcode.companion.presentation.viewmodels.SessionViewModel
import kotlin.test.*

/** A session's header on the desktop target: where it runs, an offline device worded plainly, no resume from here. */
@OptIn(ExperimentalTestApi::class, ExperimentalComposeUiApi::class)
class SessionScreenTest {
    private val transcripts = object : TranscriptRepository {
        override suspend fun page(accountId: String, sessionId: String, before: Long?) = TranscriptPage(emptyList(), nextBefore = null, lastSeq = 0)
        override fun follow(accountId: String, sessionId: String, after: Long): Flow<LiveUpdate<TranscriptEvent>> = emptyFlow()
    }

    private fun viewModel(sessions: FakeSessionRepository, transcripts: TranscriptRepository = this.transcripts): SessionViewModel {
        val features = FakeFeatures()
        val accounts = InMemoryAccountRepository(listOf(account("a1")))
        val guard = AccountGuard(RefreshAccountUseCase(FakeAuthRepository(), accounts, credentials(), FixedClock()))
        return SessionViewModel(
            SessionRoute("a1", "s1"), GetSessionUseCase(sessions, guard), LoadTranscriptUseCase(transcripts, features, guard), FollowTranscriptUseCase(transcripts, guard),
            SendMessageUseCase(sessions, features, guard, SequenceIds()), ControlSessionUseCase(sessions, guard, SequenceIds()), accounts,
            AnswerPermissionUseCase(sessions, features, guard, SequenceIds()),
        )
    }

    @Test fun aRemoteSessionWhoseDeviceWentQuietSaysSoAndOffersNoResume() = runComposeUiTest {
        val sessions = FakeSessionRepository().apply { byId["s1"] = cloudSession("s1", SessionState.PAUSED, host = laptop(online = false), problemCode = "host_offline") }
        val model = viewModel(sessions)
        setContent { TeamCodeTheme { SessionScreen(model, memberId = "m1", onBack = {}) } }
        waitUntil { model.uiState.value.session != null }
        onNodeWithTag("host-offline").assertExists()
        onNodeWithTag("problem").assertDoesNotExist()
        onNodeWithText("Remote · Ada's MacBook").assertExists()
        onNodeWithText("Paused on Ada's MacBook. A message asks it to resume.").assertExists()
        onNodeWithTag("dock-resume").assertDoesNotExist()
        onNodeWithTag("summary").performClick()
        onNodeWithText("macOS 15.1").assertExists()
        onNodeWithText("Asks before acting").assertExists()
        onNodeWithTag("session-menu").performClick()
        onNodeWithTag("resume").assertDoesNotExist()
    }

    @Test fun onAPhoneTheDetailsCaretStaysBesideALongStateRow() = runComposeUiTest {
        // The stream never answers, so the row shows its longest state, "CONNECTING", beside the remote chip.
        val connecting = object : TranscriptRepository by transcripts {
            override fun follow(accountId: String, sessionId: String, after: Long): Flow<LiveUpdate<TranscriptEvent>> = flow { awaitCancellation() }
        }
        val sessions = FakeSessionRepository().apply { byId["s1"] = cloudSession("s1", SessionState.ACTIVE, host = laptop(name = "TeamCode CLI")) }
        val model = viewModel(sessions, connecting)
        setContent { TeamCodeTheme { Box(Modifier.size(390.dp, 700.dp)) { SessionScreen(model, memberId = "m1", onBack = {}) } } }
        waitUntil { model.uiState.value.session != null }
        waitForIdle()
        // The toggle is the caret alone: no "Details" label to squeeze, but it still names itself to screen readers.
        onNodeWithText("Details", useUnmergedTree = true).assertDoesNotExist()
        val caret = onNodeWithContentDescription("Details", useUnmergedTree = true).getBoundsInRoot()
        val chip = onNodeWithText("Remote · TeamCode CLI", useUnmergedTree = true).getBoundsInRoot()
        assertTrue((caret.right - caret.left).value >= 14f, "The caret is ${caret.right - caret.left} wide")
        assertTrue(onNodeWithTag("summary").getBoundsInRoot().right.value <= 390f)
        assertTrue((chip.bottom - chip.top).value < 40f, "The remote chip wrapped: ${chip.bottom - chip.top} tall")
    }

    @Test fun aSessionWaitingForItsDeviceSaysSoInWords() = runComposeUiTest {
        val sessions = FakeSessionRepository().apply { byId["s1"] = cloudSession("s1", SessionState.CREATING, host = laptop(online = false)).copy(phase = "waiting_host") }
        val model = viewModel(sessions)
        setContent { TeamCodeTheme { SessionScreen(model, memberId = "m1", onBack = {}) } }
        waitUntil { model.uiState.value.session != null }
        onNodeWithTag("waiting-host").assertExists()
        onNodeWithText("Waiting for Ada's MacBook to start the session", substring = true).assertExists()
        onNodeWithTag("waiting-host-s1", useUnmergedTree = true).assertExists()
        onNodeWithTag("problem").assertDoesNotExist()
        onNodeWithTag("summary").performClick()
        // The server's phase code is worded, never shown raw.
        onNodeWithText("waiting for the device").assertExists()
        onNodeWithText("waiting_host").assertDoesNotExist()
    }

    @Test fun aSessionTheDeviceRefusedSaysWhyInsteadOfACode() = runComposeUiTest {
        val sessions = FakeSessionRepository().apply {
            byId["s1"] = cloudSession("s1", SessionState.TERMINATED, host = laptop(online = false), problemCode = "host_refused").copy(refusalReason = "folder_missing")
        }
        val model = viewModel(sessions)
        setContent { TeamCodeTheme { SessionScreen(model, memberId = "m1", onBack = {}) } }
        waitUntil { model.uiState.value.session != null }
        onNodeWithTag("host-refused").assertExists()
        onNodeWithText("Ada's MacBook refused to start this session: the folder no longer exists on the device.").assertExists()
        onNodeWithTag("host-refused-s1", useUnmergedTree = true).assertExists()
        onNodeWithTag("problem").assertDoesNotExist()
        onNodeWithTag("waiting-host").assertDoesNotExist()
    }

    @Test fun endingASessionIsAskedFirstAndOnlyOfferedForASessionThatHasNotEnded() = runComposeUiTest {
        val sessions = FakeSessionRepository().apply { byId["s1"] = cloudSession("s1", SessionState.PAUSED) }
        val model = viewModel(sessions)
        setContent { TeamCodeTheme { SessionScreen(model, memberId = "m1", onBack = {}) } }
        waitUntil { model.uiState.value.session != null && model.uiState.value.canControl }
        onNodeWithTag("session-menu").performClick()
        onNodeWithTag("end").performClick()
        // Nothing is sent before the confirmation; cancelling keeps the session.
        onNodeWithTag("end-confirm").assertExists()
        assertTrue(sessions.calls.none { it.startsWith("end") })
        onNodeWithText("Cancel").performClick()
        onNodeWithTag("end-confirm").assertDoesNotExist()
        onNodeWithTag("session-menu").performClick()
        onNodeWithTag("end").performClick()
        onNodeWithTag("end-confirm-yes").performClick()
        waitUntil { sessions.calls.contains("end s1") }
        onNodeWithTag("end-confirm").assertDoesNotExist()
    }

    @Test fun anEndedSessionHasNoEndEntry() = runComposeUiTest {
        val sessions = FakeSessionRepository().apply { byId["s1"] = cloudSession("s1", SessionState.TERMINATED) }
        val model = viewModel(sessions)
        setContent { TeamCodeTheme { SessionScreen(model, memberId = "m1", onBack = {}) } }
        waitUntil { model.uiState.value.session != null }
        onNodeWithTag("session-menu").performClick()
        onNodeWithTag("session-artifacts").assertExists()
        onNodeWithTag("end").assertDoesNotExist()
    }

    @Test fun aPausedCloudSessionStillResumesFromHere() = runComposeUiTest {
        val sessions = FakeSessionRepository().apply { byId["s1"] = cloudSession("s1", SessionState.PAUSED) }
        val model = viewModel(sessions)
        setContent { TeamCodeTheme { SessionScreen(model, memberId = "m1", onBack = {}) } }
        waitUntil { model.uiState.value.session != null }
        onNodeWithTag("dock-resume").assertExists()
        onNodeWithTag("host-offline").assertDoesNotExist()
        onNodeWithTag("summary").performClick()
        onNodeWithText("Cloud").assertExists()
    }

    @Test fun tappingOutsideTheComposerClearsItsFocusAndClosesTheKeyboard() = runComposeUiTest {
        val sessions = FakeSessionRepository().apply { byId["s1"] = cloudSession("s1", SessionState.PAUSED) }
        val model = viewModel(sessions)
        var hidden = 0
        val keyboard = object : SoftwareKeyboardController {
            override fun show() {}
            override fun hide() { hidden++ }
        }
        setContent { CompositionLocalProvider(LocalSoftwareKeyboardController provides keyboard) { TeamCodeTheme { SessionScreen(model, memberId = "m1", onBack = {}) } } }
        waitUntil { model.uiState.value.session != null }
        onNodeWithTag("composer").performClick()
        onNodeWithTag("composer").assertIsFocused()
        assertEquals(0, hidden)
        onNodeWithTag("transcript").performClick()
        onNodeWithTag("composer").assertIsNotFocused()
        assertEquals(1, hidden)
    }

    @Test fun aButtonTappedWhileTypingStillRunsAndDoesNotCloseTheKeyboard() = runComposeUiTest {
        val sessions = FakeSessionRepository().apply { byId["s1"] = cloudSession("s1", SessionState.PAUSED) }
        val model = viewModel(sessions)
        var hidden = 0
        val keyboard = object : SoftwareKeyboardController {
            override fun show() {}
            override fun hide() { hidden++ }
        }
        setContent { CompositionLocalProvider(LocalSoftwareKeyboardController provides keyboard) { TeamCodeTheme { SessionScreen(model, memberId = "m1", onBack = {}) } } }
        waitUntil { model.uiState.value.session != null }
        onNodeWithTag("composer").performClick()
        onNodeWithTag("summary").performClick()
        onNodeWithText("Cloud").assertExists()
        assertEquals(0, hidden)
    }

    /** A history of [count] agent messages (sequence 1..count) served 50 at a time, newest page first, like the server. */
    private fun pagedHistory(count: Int, tools: Boolean = false, text: (Int) -> String = { "Message $it" }) = object : TranscriptRepository {
        private val all: List<TranscriptEvent> = (1..count).map { seq ->
            if (tools && seq % 5 != 0) TranscriptEvent.ToolCall(seq.toLong(), 0, "t$seq", "Bash", "{}", "step $seq", null)
            else TranscriptEvent.AgentMessage(seq.toLong(), 0, text(seq), null)
        }
        override suspend fun page(accountId: String, sessionId: String, before: Long?): TranscriptPage {
            val older = all.filter { before == null || it.seq < before }
            val events = older.takeLast(50)
            return TranscriptPage(events, nextBefore = events.first().seq.takeIf { it > 1 }, lastSeq = count.toLong())
        }
        override fun follow(accountId: String, sessionId: String, after: Long): Flow<LiveUpdate<TranscriptEvent>> = emptyFlow()
    }

    @Test fun loadingEarlierEventsKeepsTheMessageBeingReadWhereItWas() = runComposeUiTest {
        val sessions = FakeSessionRepository().apply { byId["s1"] = cloudSession("s1", SessionState.PAUSED) }
        val model = viewModel(sessions, pagedHistory(150))
        setContent { TeamCodeTheme { Box(Modifier.size(900.dp, 700.dp)) { SessionScreen(model, memberId = "m1", onBack = {}) } } }
        waitUntil { model.uiState.value.loaded }
        waitForIdle()
        // The member scrolls up by hand (that is what stops the list following the newest message), to the top of the
        // loaded page: "Message 101" is its first message, right under the button.
        onNodeWithTag("transcript").performTouchInput { swipeDown() }
        waitForIdle()
        onNodeWithTag("transcript").performScrollToIndex(0)
        waitForIdle()
        val listTop = onNodeWithTag("transcript").getBoundsInRoot().top
        val before = onNodeWithText("Message 101", useUnmergedTree = true).getBoundsInRoot().top
        onNodeWithText("Load earlier events").performClick()
        waitUntil { model.uiState.value.transcript.events.size == 100 }
        waitForIdle()
        // Same message, nearly the same place: it moves up under the edge the button was at, not fifty messages away.
        val after = onNodeWithText("Message 101", useUnmergedTree = true).getBoundsInRoot().top
        assertTrue(after.value in listTop.value..before.value + 1f, "Message 101 moved from $before to $after (list top $listTop)")
    }

    @Test fun scrollingUpMovesTheTranscriptWithTheFingerAsEarlierRowsComeIn() = runComposeUiTest {
        val sessions = FakeSessionRepository().apply { byId["s1"] = cloudSession("s1", SessionState.PAUSED) }
        // Messages of several paragraphs: the rows that come in at the top have never been composed.
        val model = viewModel(sessions, pagedHistory(50) { seq -> "Message $seq\n\n" + (1..4).joinToString("\n\n") { "Paragraph $it of message $seq." } })
        setContent { TeamCodeTheme { Box(Modifier.size(600.dp, 700.dp)) { SessionScreen(model, memberId = "m1", onBack = {}) } } }
        waitUntil { model.uiState.value.loaded }
        waitForIdle()
        // Where the message being read is, in pixels like the finger; null once it left the screen.
        fun reading() = onAllNodesWithText("Message 48", useUnmergedTree = true).fetchSemanticsNodes().firstOrNull()?.positionInRoot?.y
        // A slow drag: past the touch slop first, then steps that each bring earlier rows in at the top.
        onNodeWithTag("transcript").performTouchInput { down(Offset(centerX, top + 100f)); moveBy(Offset(0f, 60f)) }
        settle()
        var last = assertNotNull(reading(), "Message 48 is not on screen")
        repeat(4) { step ->
            onNodeWithTag("transcript").performTouchInput { moveBy(Offset(0f, 60f)) }
            settle()
            val now = reading()
            // The message follows the finger, 60 px each time, whatever height the rows coming in above it turn out to have.
            assertTrue(now != null && kotlin.math.abs(now - last - 60f) <= 1f, "Step ${step + 1}: the finger moved 60 px and the message went from $last to $now")
            last = now
        }
        onNodeWithTag("transcript").performTouchInput { up() }
    }

    /** Lets anything started off the main thread come back and be laid out. */
    private fun androidx.compose.ui.test.ComposeUiTest.settle() {
        repeat(3) { Thread.sleep(50); waitForIdle() }
    }

    private fun subagentTranscript(stoppable: Boolean) = object : TranscriptRepository {
        private fun agent(seq: Long, id: String, description: String, background: Boolean = false) = TranscriptEvent.ToolCall(seq, seq * 1_000, id, "Agent",
            """{"description":"$description","prompt":"Look","subagent_type":"Explore"${if (background) ""","run_in_background":true""" else ""}}""", description, null)
        override suspend fun page(accountId: String, sessionId: String, before: Long?) = TranscriptPage(listOf(
            TranscriptEvent.Turn(1, 1_000, me.nathanfallet.teamcode.companion.domain.models.TurnPhase.STARTED, null, null),
            TranscriptEvent.UserMessage(2, 2_000, "Review the repository", "i1", "m1"),
            agent(3, "a1", "Map the modules"),
            TranscriptEvent.AgentMessage(4, 4_000, "Inside the subagent", "a1"),
            TranscriptEvent.ToolResult(5, 5_000, "a1", "Three modules", false, null, null),
            agent(6, "b1", "Watch the build", background = true),
            TranscriptEvent.ToolResult(7, 7_000, "b1", "Launched", false, null, null),
            TranscriptEvent.AgentMessage(8, 8_000, "On the main thread", null),
            TranscriptEvent.Tasks(9, 9_000, listOf(me.nathanfallet.teamcode.companion.domain.models.BackgroundTask("t1", "local_agent", "Watch the build", "Explore", "b1", 6_000)), stoppable),
        ), nextBefore = null, lastSeq = 9)
        override fun follow(accountId: String, sessionId: String, after: Long): Flow<LiveUpdate<TranscriptEvent>> = emptyFlow()
    }

    private fun androidx.compose.ui.test.ComposeUiTest.openPanel() {
        if (onAllNodesWithTagExists("subagent-panel")) return
        onNodeWithTag("running-badge").performClick()
        waitUntil { onAllNodesWithTagExists("subagent-panel") }
    }

    private fun androidx.compose.ui.test.ComposeUiTest.onAllNodesWithTagExists(tag: String) =
        onAllNodes(androidx.compose.ui.test.hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()

    @Test fun noSubagentNoBadgeNorPanel() = runComposeUiTest {
        val sessions = FakeSessionRepository().apply { byId["s1"] = cloudSession("s1", SessionState.READY) }
        val model = viewModel(sessions)
        setContent { TeamCodeTheme { SessionScreen(model, memberId = "m1", onBack = {}) } }
        waitUntil { model.uiState.value.session != null }
        onNodeWithTag("running-badge").assertDoesNotExist()
        onNodeWithTag("subagent-panel").assertDoesNotExist()
    }

    @Test fun aSubagentOpensInItsOwnThreadAndTheMainThreadKeepsItsCard() = runComposeUiTest {
        val sessions = FakeSessionRepository().apply { byId["s1"] = cloudSession("s1", SessionState.READY) }
        val model = viewModel(sessions, subagentTranscript(stoppable = true))
        setContent { TeamCodeTheme { SessionScreen(model, memberId = "m1", onBack = {}) } }
        waitUntil { onAllNodesWithTagExists("subagent-card-a1") }
        onNodeWithTag("running-badge").assertExists()
        onNodeWithText("Inside the subagent").assertDoesNotExist()
        onNodeWithTag("subagent-card-a1").performClick()
        waitUntil { onAllNodesWithTagExists("thread-bar") }
        onNodeWithText("Inside the subagent").assertExists()
        onNodeWithText("On the main thread").assertDoesNotExist()
        onNodeWithTag("thread-back").performClick()
        waitUntil { !onAllNodesWithTagExists("thread-bar") }
        onNodeWithText("On the main thread").assertExists()
    }

    @Test fun stoppingABackgroundSubagentAsksFirst() = runComposeUiTest {
        val sessions = FakeSessionRepository().apply { byId["s1"] = cloudSession("s1", SessionState.READY) }
        val model = viewModel(sessions, subagentTranscript(stoppable = true))
        setContent { TeamCodeTheme { SessionScreen(model, memberId = "m1", onBack = {}) } }
        waitUntil { onAllNodesWithTagExists("running-badge") }
        openPanel()
        onNodeWithTag("stop-t1").performClick()
        onNodeWithTag("stop-confirm").assertExists()
        onNodeWithTag("stop-cancel").performClick()
        onNodeWithTag("stop-confirm").assertDoesNotExist()
        assertTrue(sessions.calls.none { it.startsWith("stopTask") }, "Cancelled: nothing sent")
        onNodeWithTag("stop-t1").performClick()
        onNodeWithTag("stop-confirm-yes").performClick()
        waitUntil { sessions.calls.any { it.startsWith("stopTask s1 t1") } }
    }

    @Test fun withoutStopSupportThePanelIsReadOnlyAndSaysSo() = runComposeUiTest {
        val sessions = FakeSessionRepository().apply { byId["s1"] = cloudSession("s1", SessionState.READY) }
        val model = viewModel(sessions, subagentTranscript(stoppable = false))
        setContent { TeamCodeTheme { SessionScreen(model, memberId = "m1", onBack = {}) } }
        waitUntil { onAllNodesWithTagExists("running-badge") }
        openPanel()
        onNodeWithTag("stop-t1").assertDoesNotExist()
        onNodeWithTag("panel-note").assertExists()
    }
}
