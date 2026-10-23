package me.nathanfallet.teamcode.companion

import androidx.compose.runtime.getValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import me.nathanfallet.teamcode.companion.domain.models.AgentQuestion
import me.nathanfallet.teamcode.companion.domain.models.AgentQuestionOption
import me.nathanfallet.teamcode.companion.domain.models.LiveUpdate
import me.nathanfallet.teamcode.companion.domain.models.PermissionAnswer
import me.nathanfallet.teamcode.companion.domain.models.QuestionAnswer
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
import me.nathanfallet.teamcode.companion.presentation.screens.PermissionCardView
import me.nathanfallet.teamcode.companion.presentation.theme.TeamCodeTheme
import me.nathanfallet.teamcode.companion.presentation.viewmodels.SessionViewModel
import kotlin.test.*

/** The question card on the desktop target: one question at a time, Back keeping the choice, one Send for all. */
@OptIn(ExperimentalTestApi::class)
class PermissionCardTest {
    @Test fun aQuestionIsAnsweredOneStepAtATimeAndSentOnce() = runComposeUiTest {
        val questions = listOf(
            AgentQuestion(0, "Red or blue?", "Colour", listOf(AgentQuestionOption("Red", "A warm one"), AgentQuestionOption("Blue", null)), multiSelect = false),
            AgentQuestion(1, "Which sizes?", null, listOf(AgentQuestionOption("S", null), AgentQuestionOption("**M**", null)), multiSelect = true),
        )
        val live = Channel<LiveUpdate<TranscriptEvent>>(Channel.UNLIMITED)
        val transcripts = object : TranscriptRepository {
            override suspend fun page(accountId: String, sessionId: String, before: Long?) =
                TranscriptPage(listOf(TranscriptEvent.PermissionRequest(1, 1, "r1", "AskUserQuestion", true, questions, input = "{}")), null, 1)
            override fun follow(accountId: String, sessionId: String, after: Long): Flow<LiveUpdate<TranscriptEvent>> = live.receiveAsFlow()
        }
        val sessions = FakeSessionRepository().apply { byId["s1"] = cloudSession("s1") }
        val accounts = InMemoryAccountRepository(listOf(account("a1")))
        val store = credentials().also { kotlinx.coroutines.runBlocking { it.write("a1", "token") } }
        val guard = AccountGuard(RefreshAccountUseCase(FakeAuthRepository(), accounts, store, FixedClock()))
        val features = FakeFeatures()
        val model = SessionViewModel(
            SessionRoute("a1", "s1"), GetSessionUseCase(sessions, guard), LoadTranscriptUseCase(transcripts, features, guard), FollowTranscriptUseCase(transcripts, guard),
            SendMessageUseCase(sessions, features, guard, SequenceIds()), ControlSessionUseCase(sessions, guard, SequenceIds()), accounts,
            AnswerPermissionUseCase(sessions, features, guard, SequenceIds()),
        )
        setContent {
            TeamCodeTheme {
                val state by model.uiState.collectAsStateWithLifecycle()
                state.permission?.let { PermissionCardView(it, model) }
            }
        }
        model.start()
        waitUntil { model.uiState.value.permission != null }
        onNodeWithText("Red or blue?").assertExists()
        onNodeWithText("A warm one").assertExists()
        onNodeWithText("Colour").assertExists()
        onNodeWithText("1/2").assertExists()
        onNodeWithTag("permission-next").assertIsNotEnabled()
        onNodeWithTag("option-0-1").performClick()
        onNodeWithTag("option-0-1").assertIsSelected()
        onNodeWithTag("permission-next").assertIsEnabled().performClick()

        onNodeWithText("Which sizes?").assertExists()
        onNodeWithText("2/2").assertExists()
        // An option label is the agent's text, shown as is.
        onNodeWithText("**M**").assertExists()
        onNodeWithTag("option-1-0").performClick()
        onNodeWithTag("option-1-0").assertIsOn()
        onNodeWithTag("option-1-1").assertIsOff()
        onNodeWithTag("other-1").performClick()
        onNodeWithTag("other-text-1").performTextInput("XL")
        // Back keeps the first choice.
        onNodeWithTag("permission-back").performClick()
        onNodeWithTag("option-0-1").assertIsSelected()
        onNodeWithTag("permission-next").performClick()
        onNodeWithTag("permission-send").assertIsEnabled().performClick()
        waitUntil { sessions.answers.isNotEmpty() }
        assertEquals(PermissionAnswer("r1", allow = true, answers = listOf(QuestionAnswer(0, listOf(1)), QuestionAnswer(1, text = "S, XL"))), sessions.answers.single().first)
        waitUntil { onAllNodes(androidx.compose.ui.test.hasTestTag("permission-status")).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithText("Answer sent, waiting for the agent").assertExists()
        model.stop()
    }
}
