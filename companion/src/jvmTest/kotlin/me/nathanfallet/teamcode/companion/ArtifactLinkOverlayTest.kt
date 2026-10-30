package me.nathanfallet.teamcode.companion

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.saveable.SaverScope
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.flow
import me.nathanfallet.teamcode.companion.domain.models.LiveUpdate
import me.nathanfallet.teamcode.companion.domain.models.TranscriptEvent
import me.nathanfallet.teamcode.companion.domain.models.TranscriptPage
import me.nathanfallet.teamcode.companion.domain.repositories.ArtifactRepository
import me.nathanfallet.teamcode.companion.domain.repositories.TranscriptRepository
import me.nathanfallet.teamcode.companion.presentation.components.LocalWideLayout
import me.nathanfallet.teamcode.companion.presentation.navigation.ArtifactRoute
import me.nathanfallet.teamcode.companion.presentation.navigation.TeamCodeNavHost
import me.nathanfallet.teamcode.companion.presentation.screens.ArtifactOverlayTarget
import me.nathanfallet.teamcode.companion.presentation.theme.TeamCodeTheme
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import kotlin.test.*

/**
 * A link in a conversation, end to end on demo data: an artifact of the session's own server opens over the
 * conversation (a phone's whole screen, a dialog on a wide window), every other link goes to the browser after its
 * URL is shown, and an artifact the member cannot read says so with its viewer page offered in the browser.
 */
@OptIn(ExperimentalTestApi::class)
class ArtifactLinkOverlayTest {
    @Test fun anArtifactLinkOfTheSessionsServerOpensOverTheConversation() = both { opened ->
        clickLink("Read the latency report")
        onNodeWithTag("artifact-overlay").assertExists()
        onNodeWithTag("link-confirm").assertDoesNotExist()
        // The version from the link, read through the account's API; the desktop shows the title and the browser button.
        onNodeWithText("Version 2", substring = true).assertExists()
        onNodeWithTag("artifact-open-desktop").assertExists()
        // The conversation is still underneath, and the close button brings it back.
        onNodeWithTag("artifact-close").performClick()
        settle()
        onNodeWithTag("artifact-overlay").assertDoesNotExist()
        assertEquals(emptyList(), opened)
        // Escape (the desktop's back) closes it too.
        clickLink("Read the latency report")
        onNodeWithTag("artifact-overlay").assertExists()
        onNodeWithTag("artifact-overlay").performKeyInput { pressKey(Key.Escape) }
        settle()
        onNodeWithTag("artifact-overlay").assertDoesNotExist()
    }

    @Test fun anyOtherLinkGoesToTheBrowserAfterItsUrlIsShown() = both { opened ->
        for ((label, url) in listOf("Release notes" to "https://example.org/notes", "Other server" to "https://teamcode.example.org/artifact/$ID",
            "Content file" to "https://code.acme.dev/artifact/$ID/tk_1/index.html")) {
            clickLink(label)
            onNodeWithTag("artifact-overlay").assertDoesNotExist()
            onNodeWithTag("link-confirm").assertExists()
            onNodeWithText(url).assertExists()
            onNodeWithTag("link-open").performClick()
            settle()
        }
        assertEquals(listOf("https://example.org/notes", "https://teamcode.example.org/artifact/$ID", "https://code.acme.dev/artifact/$ID/tk_1/index.html"), opened)
    }

    @Test fun anArtifactTheMemberCannotReadSaysSoAndOffersTheBrowser() = both { opened ->
        clickLink("Hidden plan")
        onNodeWithTag("artifact-overlay").assertExists()
        onNodeWithText("This no longer exists, or you can no longer see it.").assertExists()
        onNodeWithText("This artifact did not open").assertExists()
        onNodeWithTag("artifact-fallback-browser").performClick()
        settle()
        // The viewer page built from the account's origin and the parsed id, never the clicked text.
        assertEquals(listOf("https://code.acme.dev/artifact/$HIDDEN"), opened)
    }

    @Test fun theOverlayIsKeptAcrossARotation() {
        val scope = SaverScope { true }
        val target = ArtifactOverlayTarget(ArtifactRoute("a1", ID, 2), "https://code.acme.dev/artifact/$ID?v=2")
        with(ArtifactOverlayTarget.Saver) {
            assertEquals(target, restore(scope.save(target)!!))
            assertEquals(target.copy(route = target.route.copy(version = null)), restore(scope.save(target.copy(route = target.route.copy(version = null)))!!))
            assertNull(restore(scope.save(null)!!))
        }
    }

    /** [block] on a phone and on a desktop window, in the demo session whose transcript holds the links. */
    private fun both(block: DesktopComposeUiTest.(opened: List<String>) -> Unit) {
        for ((width, height, wide) in listOf(Triple(390, 844, false), Triple(1200, 800, true))) {
            val opened = mutableListOf<String>()
            startKoin { modules(demoModules(artifacts = artifacts(), transcript = linkTranscript())) }
            try {
                runDesktopComposeUiTest(width, height) {
                    val owner = object : ViewModelStoreOwner { override val viewModelStore = ViewModelStore() }
                    val browser = object : UriHandler { override fun openUri(uri: String) { opened += uri } }
                    setContent { Shell(owner, browser, wide) }
                    settle()
                    onAllNodesWithText("Fix the flaky login test").onFirst().performSemanticsAction(SemanticsActions.OnClick)
                    settle()
                    block(opened)
                }
            } finally {
                stopKoin()
            }
        }
    }

    @Composable
    private fun Shell(owner: ViewModelStoreOwner, browser: UriHandler, wide: Boolean) {
        CompositionLocalProvider(LocalViewModelStoreOwner provides owner, LocalUriHandler provides browser, LocalWideLayout provides wide) {
            TeamCodeTheme { TeamCodeNavHost() }
        }
    }

    /** A click on the link at the start of the agent message that is only that link. */
    private fun DesktopComposeUiTest.clickLink(label: String) {
        onAllNodesWithText(label, substring = true).onFirst().performMouseInput { click(Offset(8f, centerY)) }
        settle()
    }

    private fun DesktopComposeUiTest.settle() {
        mainClock.advanceTimeBy(1_000)
        waitForIdle()
    }

    /** The demo artifacts and the linked one, its versions dated a few hours before the demo's clock. */
    internal fun artifacts(): ArtifactRepository {
        val demo = demoArtifacts().apply { byId[ID] = artifact(ID, "Q3 latency report", version = 3, updatedAt = DEMO_NOW - 3_600_000) }
        return object : ArtifactRepository by demo {
            override suspend fun versions(accountId: String, artifactId: String) =
                demo.versions(accountId, artifactId).map { it.copy(createdAt = DEMO_NOW - (4 - it.number) * 7_200_000L) }
        }
    }

    internal fun linkTranscript() = object : TranscriptRepository {
        override suspend fun page(accountId: String, sessionId: String, before: Long?): TranscriptPage {
            val links = listOf(
                "[Read the latency report](https://code.acme.dev/artifact/$ID?v=2)", "[Release notes](https://example.org/notes)",
                "[Other server](https://teamcode.example.org/artifact/$ID)", "[Content file](https://code.acme.dev/artifact/$ID/tk_1/index.html)",
                "[Hidden plan](https://code.acme.dev/artifact/$HIDDEN)",
            )
            val events = links.mapIndexed { index, text -> TranscriptEvent.AgentMessage(index + 1L, DEMO_NOW - 60_000 + index, text, null) }
            return TranscriptPage(events, null, links.size.toLong())
        }
        override fun follow(accountId: String, sessionId: String, after: Long) = flow<LiveUpdate<TranscriptEvent>> { emit(LiveUpdate.Opened(false)); awaitCancellation() }
    }

    internal companion object {
        const val ID = "0b5c7c1e-9f0a-4d2b-8e5f-3a1c2d4e6f80"
        const val HIDDEN = "7d1e2f30-4a5b-4c6d-8e9f-0a1b2c3d4e5f"
    }
}
