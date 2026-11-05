package me.nathanfallet.teamcode.companion

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import me.nathanfallet.teamcode.companion.demo.DemoContent
import me.nathanfallet.teamcode.companion.domain.models.LiveUpdate
import me.nathanfallet.teamcode.companion.domain.models.SessionChange
import me.nathanfallet.teamcode.companion.domain.models.SessionTodo
import me.nathanfallet.teamcode.companion.domain.models.ThemePreference
import me.nathanfallet.teamcode.companion.domain.models.TodoHookEvent
import me.nathanfallet.teamcode.companion.domain.models.TodoItemState
import me.nathanfallet.teamcode.companion.domain.repositories.FeaturesRepository
import me.nathanfallet.teamcode.companion.domain.repositories.SessionRepository
import me.nathanfallet.teamcode.companion.domain.repositories.TodoRepository
import me.nathanfallet.teamcode.companion.domain.usecases.todos.TodoShortcuts
import org.koin.dsl.module
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The session screen on demo data with a todo: [list] is what the server answers, [feed] what its stream says. */
@OptIn(ExperimentalTestApi::class)
internal class TodoWorld(initial: SessionTodo? = DemoContent.todo("s0", DEMO_NOW)) {
    val list = AtomicReference(initial)
    val feed = Channel<LiveUpdate<SessionChange>>(Channel.UNLIMITED).also { it.trySend(LiveUpdate.Opened(false)) }
    val sessions = demoSessions().apply { changes = feed.receiveAsFlow() }

    fun module() = module {
        single<FeaturesRepository> { FakeFeatures(setOf("cloud.sessions.v1", "cloud.transcript.v1", "cloud.messages.v1", "cloud.events.v1", "cloud.permissions.v1", "artifacts.v1", "todos.v1", "todos.live.v1")) }
        single<SessionRepository> { sessions }
        single<TodoRepository> { object : TodoRepository { override suspend fun todo(accountId: String, sessionId: String) = list.get() } }
    }

    /** The agent finished the item under way and began the next one, as the server's `cloud.todo` event announces. */
    fun advance() {
        val before = list.get()!!
        val doing = before.items.indexOfFirst { it.state == TodoItemState.DOING }
        val after = before.copy(items = before.items.mapIndexed { i, item -> when (i) { doing -> item.copy(state = TodoItemState.DONE); doing + 1 -> item.copy(state = TodoItemState.DOING); else -> item } }, updatedAt = DEMO_NOW + 60_000)
        list.set(after)
        feed.trySend(LiveUpdate.Item(SessionChange(1, SessionChange.TODO, "s0", "m1", DEMO_NOW + 60_000, null, null, todoDone = after.done, todoTotal = after.total)))
    }
}

@OptIn(ExperimentalTestApi::class)
internal fun DesktopComposeUiTest.openFlakySession() {
    onAllNodesWithText("Fix the flaky login test").onFirst().performSemanticsAction(SemanticsActions.OnClick)
    mainClock.advanceTimeBy(1_000); waitForIdle()
}

@OptIn(ExperimentalTestApi::class)
internal fun DesktopComposeUiTest.tapTag(tag: String) {
    onAllNodesWithTag(tag).onFirst().performSemanticsAction(SemanticsActions.OnClick)
    mainClock.advanceTimeBy(1_000); waitForIdle()
}

/** The stream and the reads run on real threads: waits for [condition] in real time, moving the clock as it goes. */
@OptIn(ExperimentalTestApi::class)
internal fun DesktopComposeUiTest.settleUntil(condition: () -> Boolean) {
    var waited = 0
    while (!condition() && waited++ < 50) { Thread.sleep(100); mainClock.advanceTimeBy(250); waitForIdle() }
}

@OptIn(ExperimentalTestApi::class)
internal fun DesktopComposeUiTest.counterIs(text: String) = onAllNodesWithTag("todo-counter").fetchSemanticsNodes().firstOrNull()
    ?.config?.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.Text)?.joinToString("") { part -> part.text } == text

@OptIn(ExperimentalTestApi::class)
internal fun DesktopComposeUiTest.exists(tag: String) = onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()

/** The Todo view of a session's panel, on the desktop target: counter, items, live update, shortcuts. */
@OptIn(ExperimentalTestApi::class)
class TodoPanelScreenTest {
    private val scenes = DesktopScreenshots()

    @Test fun theHeaderCountsTheTodoAndTheViewListsItsItemsAndFollowsTheStreamLive() {
        val world = TodoWorld()
        scenes.scene(1200, 800, ThemePreference.DARK, listOf(world.module())) {
            openFlakySession()
            settleUntil { exists("todo-badge") }
            onNodeWithTag("todo-badge").assertTextContains("4 / 9")
            // A side panel at this width: opened on the Todo view from the counter.
            if (!exists("todo-view")) tapTag("todo-badge")
            onNodeWithTag("todo-counter").assertTextEquals("4 / 9")
            assertTrue(exists("todo-reference"))
            assertTrue(exists("todo-item-pr") && exists("todo-item-release"), "every point, in order")
            assertTrue(exists("todo-added-second-assertion"), "added along the way")
            assertTrue(exists("todo-unverified-reproduce"), "an attested point is marked not verified")
            assertTrue(exists("todo-proof-fix"), "the last proof of a done point")

            // The agent moves on: the stream says so, the list is read again, the counter and the header follow.
            world.advance()
            settleUntil { counterIs("5 / 9") }
            onNodeWithTag("todo-counter").assertTextEquals("5 / 9")
            onNodeWithTag("todo-badge").assertTextContains("5 / 9")
        }
    }

    @Test fun theShortcutsSendOrdinaryMessagesToTheSession() {
        val world = TodoWorld()
        scenes.scene(1200, 800, ThemePreference.DARK, listOf(world.module())) {
            openFlakySession()
            settleUntil { exists("todo-badge") }
            if (!exists("todo-view")) tapTag("todo-badge")
            tapTag("todo-verify")
            tapTag("todo-add")
            onNodeWithTag("todo-add-field").performTextInput("Update the changelog")
            mainClock.advanceTimeBy(500); waitForIdle()
            tapTag("todo-add-send")
            val sent = world.sessions.calls.filter { it.startsWith("send s0 ") }
            assertEquals(2, sent.size, sent.toString())
            assertTrue(sent[0].startsWith("send s0 ${TodoShortcuts.VERIFY} "))
            assertTrue(sent[1].startsWith("send s0 ${TodoShortcuts.add("Update the changelog")} "))
        }
    }

    @Test fun aSessionWithoutATodoShowsNoCounterAndAHookErrorIsListed() {
        val none = TodoWorld(initial = null)
        scenes.scene(1200, 800, ThemePreference.DARK, listOf(none.module())) {
            openFlakySession()
            Thread.sleep(500); mainClock.advanceTimeBy(500); waitForIdle()
            assertTrue(!exists("todo-badge"), "no list, no counter")
        }
        val broken = TodoWorld(DemoContent.todo("s0", DEMO_NOW)!!.let { it.copy(hookEvents = listOf(TodoHookEvent(DEMO_NOW - 60_000, "hook_error", "The relay did not answer."))) })
        scenes.scene(1200, 800, ThemePreference.DARK, listOf(broken.module())) {
            openFlakySession()
            settleUntil { exists("todo-badge") }
            if (!exists("todo-view")) tapTag("todo-badge")
            assertTrue(exists("todo-hook-errors"), "a broken hook shows instead of passing unnoticed")
        }
    }
}

/**
 * Captures of the Todo view at phone, tablet and desktop sizes, with a todo updated live during a turn (before, then after the
 * agent finishes a point). Opt-in: `TEAMCODE_SCREENSHOTS=/tmp/shots ./gradlew :companion:jvmTest --tests '*TodoScreenshots*'`
 */
@OptIn(ExperimentalTestApi::class)
class TodoScreenshots {
    private val scenes = DesktopScreenshots()
    private val sizes = listOf("phone-390" to (390 to 844), "tablet-820" to (820 to 1180), "desktop-1200" to (1200 to 800), "desktop-1600" to (1600 to 1000))

    @Test fun capture() {
        val output = System.getenv("TEAMCODE_SCREENSHOTS")?.takeIf { it.isNotBlank() }?.let(::File) ?: return
        output.mkdirs()
        for ((name, size) in sizes) for (theme in listOf(ThemePreference.DARK, ThemePreference.LIGHT)) {
            if (theme == ThemePreference.LIGHT && name != "desktop-1200") continue
            val suffix = if (theme == ThemePreference.LIGHT) "-light" else ""
            val world = TodoWorld()
            with(scenes) {
                scene(size.first, size.second, theme, listOf(world.module())) {
                    openFlakySession()
                    settleUntil { exists("todo-badge") }
                    shot(output, "$name$suffix-1-session")
                    if (!exists("todo-view")) tapTag("todo-badge")
                    shot(output, "$name$suffix-2-todo-4-of-9")
                    // The agent finishes a point during a turn: the stream announces it and the view follows, with no reload.
                    world.advance()
                    settleUntil { counterIs("5 / 9") }
                    shot(output, "$name$suffix-3-todo-5-of-9-live")
                }
            }
        }
    }
}
