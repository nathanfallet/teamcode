package me.nathanfallet.teamcode.companion

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.SoftwareKeyboardController
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import kotlinx.coroutines.flow.Flow
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

    private fun viewModel(sessions: FakeSessionRepository): SessionViewModel {
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
}
