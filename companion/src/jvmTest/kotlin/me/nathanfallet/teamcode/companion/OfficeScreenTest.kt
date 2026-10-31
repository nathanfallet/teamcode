package me.nathanfallet.teamcode.companion

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.moveTo
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.toAwtImage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import me.nathanfallet.teamcode.companion.domain.models.CloudSession
import me.nathanfallet.teamcode.companion.domain.models.LiveUpdate
import me.nathanfallet.teamcode.companion.domain.models.SessionChange
import me.nathanfallet.teamcode.companion.domain.models.SessionPage
import me.nathanfallet.teamcode.companion.domain.models.SessionState
import me.nathanfallet.teamcode.companion.domain.models.SessionWaiting
import me.nathanfallet.teamcode.companion.domain.models.ThemePreference
import me.nathanfallet.teamcode.companion.domain.models.TranscriptEvent
import me.nathanfallet.teamcode.companion.domain.models.TranscriptPage
import me.nathanfallet.teamcode.companion.domain.repositories.TranscriptRepository
import me.nathanfallet.teamcode.companion.domain.usecases.auth.AccountGuard
import me.nathanfallet.teamcode.companion.domain.usecases.auth.RefreshAccountUseCase
import me.nathanfallet.teamcode.companion.domain.usecases.sessions.ControlSessionUseCase
import me.nathanfallet.teamcode.companion.domain.usecases.sessions.FollowSessionListUseCase
import me.nathanfallet.teamcode.companion.domain.usecases.sessions.GetSessionUseCase
import me.nathanfallet.teamcode.companion.domain.usecases.sessions.LoadSessionsUseCase
import me.nathanfallet.teamcode.companion.domain.usecases.sessions.SessionFeed
import me.nathanfallet.teamcode.companion.domain.usecases.transcript.LoadTranscriptUseCase
import me.nathanfallet.teamcode.companion.presentation.screens.OfficeScreen
import me.nathanfallet.teamcode.companion.presentation.theme.TeamCodeTheme
import me.nathanfallet.teamcode.companion.presentation.viewmodels.SessionsViewModel
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The office: the agents of the shared session list, their moods, the card of a hover or a tap, and the way into a session. */
@OptIn(ExperimentalTestApi::class)
class OfficeScreenTest {
    /** Six sessions in every mood the open list can show, in two rooms. */
    private val team = listOf(
        cloudSession("c1", SessionState.ACTIVE, createdAt = 6_000),
        cloudSession("c2", SessionState.READY, createdAt = 5_000),
        cloudSession("c3", SessionState.ACTIVE, createdAt = 4_000).copy(waiting = SessionWaiting("permission", "r1", 3_000)),
        cloudSession("r1", SessionState.ACTIVE, createdAt = 3_000, host = laptop()),
        cloudSession("r2", SessionState.PAUSED, createdAt = 2_000, host = laptop(online = false), problemCode = "host_offline"),
        cloudSession("r3", SessionState.CREATING, createdAt = 1_000, host = laptop()),
    )

    private class Office(sessions: List<CloudSession>) {
        val feed = Channel<LiveUpdate<SessionChange>>(Channel.UNLIMITED)
        val reads = mutableListOf<String>()
        private val accounts = InMemoryAccountRepository(listOf(account("a1")))
        private val store = credentials().also { kotlinx.coroutines.runBlocking { it.write("a1", "token") } }
        private val repository = FakeSessionRepository().apply {
            changes = feed.receiveAsFlow()
            pages = { _, _ -> SessionPage(sessions, null) }
        }
        private val features = FakeFeatures(setOf("cloud.sessions.v1", "cloud.events.v1", "cloud.transcript.v1", "cloud.sessions.remote.v1"))
        private val guard = AccountGuard(RefreshAccountUseCase(FakeAuthRepository(), accounts, store, FixedClock()))
        val list = SessionsViewModel(
            accounts, LoadSessionsUseCase(repository, features, guard), GetSessionUseCase(repository, guard),
            FollowSessionListUseCase(SessionFeed(repository, features, CoroutineScope(SupervisorJob() + Dispatchers.Default)), features, guard),
            ControlSessionUseCase(repository, guard, SequenceIds(), features),
        )
        private val transcripts = object : TranscriptRepository {
            override suspend fun page(accountId: String, sessionId: String, before: Long?): TranscriptPage {
                reads += sessionId
                return TranscriptPage(listOf(TranscriptEvent.ToolCall(1, 1, "t1", "Edit", "{}", "Foo.kt", null)), null, 1)
            }
            override fun follow(accountId: String, sessionId: String, after: Long): Flow<LiveUpdate<TranscriptEvent>> = kotlinx.coroutines.flow.emptyFlow()
        }
        val load = LoadTranscriptUseCase(transcripts, features, guard)
    }

    private fun ComposeUiTest.show(office: Office, width: Int = 800, onOpen: (String) -> Unit = {}, onNew: () -> Unit = {}) {
        setContent {
            LaunchedEffect(Unit) { office.list.start() }
            TeamCodeTheme(ThemePreference.LIGHT) {
                Box(Modifier.size(width.dp, 640.dp)) {
                    OfficeScreen(office.list, onOpenSession = { _, id -> onOpen(id) }, onOpenArtifacts = {}, onNewSession = { onNew() }, clock = FixedClock(), loadTranscript = office.load)
                }
            }
        }
        office.feed.trySend(LiveUpdate.Opened(false))
        waitUntil { office.list.uiState.value.list?.sessions?.isNotEmpty() == true }
        waitForIdle()
    }

    /** Where the agent at the first desk of the cloud room is: the office is drawn at the largest whole scale its canvas fits (the team has two rows of desks). */
    private fun ComposeUiTest.firstCloudDesk(@Suppress("UNUSED_PARAMETER") width: Int = 0): Offset {
        val bounds = onNodeWithTag("office-canvas").getBoundsInRoot()
        val w = bounds.right.value - bounds.left.value
        val h = bounds.bottom.value - bounds.top.value
        val plan = me.nathanfallet.teamcode.companion.presentation.office.OfficePlan(3, 3)
        val fit = minOf(w / 320f, h / plan.height)
        val scale = if (fit >= 1f) fit.toInt().toFloat() else fit
        val left = ((w - 320 * scale) / 2).toInt()
        val top = ((h - plan.height * scale) / 2).toInt()
        return Offset(left + 222f * scale, top + 70f * scale)
    }

    @Test fun theChipsCountEveryMoodOfTheTeam() = runComposeUiTest {
        show(Office(team))
        onNodeWithText("All 6").assertExists()
        onNodeWithText("Working 2").assertExists()
        onNodeWithText("Needs you 1").assertExists()
        onNodeWithText("Idle 1").assertExists()
        onNodeWithText("Paused 1").assertExists()
        onNodeWithText("Starting 1").assertExists()
    }

    @Test fun theCanvasIsDescribedForScreenReaders() = runComposeUiTest {
        show(Office(team))
        onNodeWithTag("office-canvas").assertContentDescriptionEquals("Office with 6 agents, 1 waiting for you")
    }

    @Test fun everyAgentHasItsNameplate() = runComposeUiTest {
        show(Office(team))
        for (id in listOf("c1", "c2", "c3", "r1", "r2", "r3")) onNodeWithText("Session $id").assertExists()
    }

    @Test fun aTapShowsTheCardAndTheButtonOpensTheSession() = runComposeUiTest {
        var opened: String? = null
        show(Office(team), onOpen = { opened = it })
        onNodeWithTag("office-canvas").performTouchInput { click(firstCloudDesk(800)) }
        waitForIdle()
        onNodeWithTag("office-preview").assertExists()
        assertNull(opened)
        onNodeWithTag("office-open").performClick()
        assertEquals("c1", opened)
    }

    @Test fun aClickWithTheMouseOpensTheSessionAtOnce() = runComposeUiTest {
        var opened: String? = null
        show(Office(team), onOpen = { opened = it })
        onNodeWithTag("office-canvas").performMouseInput { moveTo(firstCloudDesk(800)); click(firstCloudDesk(800)) }
        waitForIdle()
        assertEquals("c1", opened)
    }

    @Test fun hoveringAnAgentShowsItsCardAndReadsWhatItDoes() = runComposeUiTest {
        val office = Office(team)
        show(office)
        onNodeWithTag("office-canvas").performMouseInput { moveTo(firstCloudDesk(800)) }
        waitForIdle()
        onNodeWithTag("office-card").assertExists()
        onNodeWithText("Cloud pod").assertExists()
        // The newest events are read once the pointer rests: one session, not the team.
        mainClock.advanceTimeBy(600)
        waitUntil { office.reads.isNotEmpty() }
        assertEquals(listOf("c1"), office.reads)
        waitUntil { runCatching { onNodeWithText("Edit · Foo.kt").assertExists() }.isSuccess }
    }

    @Test fun movingOffAnAgentHidesItsCard() = runComposeUiTest {
        show(Office(team))
        onNodeWithTag("office-canvas").performMouseInput { moveTo(firstCloudDesk(800)); moveTo(Offset(5f, 600f)) }
        waitForIdle()
        onNodeWithTag("office-card").assertDoesNotExist()
    }

    @Test fun aFilterKeepsTheOtherAgentsFromBeingPicked() = runComposeUiTest {
        show(Office(team))
        onNodeWithTag("office-filter-idle").performClick()
        waitForIdle()
        onNodeWithTag("office-canvas").performTouchInput { click(firstCloudDesk(800)) }
        waitForIdle()
        onNodeWithTag("office-preview").assertDoesNotExist()
        // The filter is a toggle: the same chip again brings everyone back.
        onNodeWithTag("office-filter-idle").performClick()
        onNodeWithTag("office-canvas").performTouchInput { click(firstCloudDesk(800)) }
        waitForIdle()
        onNodeWithTag("office-preview").assertExists()
    }

    @Test fun anEmptyOfficeOffersANewSession() = runComposeUiTest {
        var created = false
        setContent {
            val office = Office(emptyList())
            LaunchedEffect(Unit) { office.list.start() }
            TeamCodeTheme(ThemePreference.LIGHT) {
                Box(Modifier.size(800.dp, 640.dp)) {
                    OfficeScreen(office.list, onOpenSession = { _, _ -> }, onOpenArtifacts = {}, onNewSession = { created = true }, clock = FixedClock(), loadTranscript = office.load)
                }
            }
            LaunchedEffect(Unit) { office.feed.trySend(LiveUpdate.Opened(false)) }
        }
        waitUntil { runCatching { onNodeWithTag("office-empty").assertExists() }.isSuccess }
        onNodeWithText("New session").performClick()
        assertTrue(created)
    }

    /** Captures of the office, opt-in like the desktop's: `TEAMCODE_SCREENSHOTS=/tmp/shots`. */
    @Test fun capture() {
        val output = System.getenv("TEAMCODE_SCREENSHOTS")?.takeIf { it.isNotBlank() }?.let(::File) ?: return
        output.mkdirs()
        for ((theme, name) in listOf(ThemePreference.LIGHT to "light", ThemePreference.DARK to "dark")) runDesktopComposeUiTest(1100, 760) {
            val office = Office(team)
            setContent {
                LaunchedEffect(Unit) { office.list.start() }
                TeamCodeTheme(theme) {
                    Box(Modifier.fillMaxSize()) {
                        OfficeScreen(office.list, onOpenSession = { _, _ -> }, onOpenArtifacts = {}, onNewSession = {}, clock = FixedClock(), loadTranscript = office.load)
                    }
                }
            }
            office.feed.trySend(LiveUpdate.Opened(false))
            waitUntil { office.list.uiState.value.list?.sessions?.isNotEmpty() == true }
            mainClock.advanceTimeBy(1_500)
            waitForIdle()
            ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(output, "office-$name.png"))
            onNodeWithTag("office-canvas").performMouseInput { moveTo(firstCloudDesk(1100)) }
            mainClock.advanceTimeBy(800)
            waitForIdle()
            ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(output, "office-$name-hover.png"))
        }
    }
}
