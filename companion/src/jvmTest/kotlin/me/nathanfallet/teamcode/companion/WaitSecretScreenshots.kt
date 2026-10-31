package me.nathanfallet.teamcode.companion

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performSemanticsAction
import java.io.File
import me.nathanfallet.teamcode.companion.demo.DemoContent
import me.nathanfallet.teamcode.companion.domain.models.ThemePreference
import me.nathanfallet.teamcode.companion.domain.models.Wait
import me.nathanfallet.teamcode.companion.domain.models.WaitPage
import me.nathanfallet.teamcode.companion.domain.models.WaitResolver
import me.nathanfallet.teamcode.companion.domain.models.WaitStatus
import me.nathanfallet.teamcode.companion.domain.repositories.FeaturesRepository
import me.nathanfallet.teamcode.companion.domain.repositories.SessionRepository
import me.nathanfallet.teamcode.companion.domain.repositories.WaitRepository
import org.koin.dsl.module
import kotlin.test.Test

/**
 * "Waiting for you" with a secret field: the masked input with a typed value, and the answered wait that only says the secret was provided, on demo data and the real screens. Opt-in: runs only when TEAMCODE_SCREENSHOTS names an output folder.
 * `TEAMCODE_SCREENSHOTS=/tmp/shots ./gradlew :companion:jvmTest --tests '*WaitSecretScreenshots*'`
 */
@OptIn(ExperimentalTestApi::class)
class WaitSecretScreenshots {
    private val scenes = DesktopScreenshots()

    private fun waits() = module {
        single<FeaturesRepository> { FakeFeatures(setOf("cloud.sessions.v1", "cloud.transcript.v1", "cloud.messages.v1", "cloud.events.v1", "cloud.permissions.v1", "artifacts.v1", "waits.v1")) }
        single<SessionRepository> { demoSessions().apply { profileList = DemoContent.profiles } }
        single<WaitRepository> {
            val all = DemoContent.waits(DEMO_NOW)
            val sent = all.first { it.id == "w10" }.copy(open = false, answer = me.nathanfallet.teamcode.companion.domain.models.WaitAnswer("done", mapOf("store_password" to kotlinx.serialization.json.JsonPrimitive("provided")), answeredAt = DEMO_NOW - 60_000))
            object : WaitRepository {
                override suspend fun list(accountId: String, status: WaitStatus, before: String?) =
                    WaitPage((all.filter { it.status == status && it.resolver == WaitResolver.HUMAN && it.id != "w10" || status == WaitStatus.OPEN && it.id == "w10" } + if (status == WaitStatus.RESOLVED) listOf(sent) else emptyList()).filter { it.status == status || it.id == "w10" && status == WaitStatus.OPEN }, null)
                override suspend fun answer(accountId: String, waitId: String, action: String, values: Map<String, kotlinx.serialization.json.JsonElement>): Wait = error("not used")
                override suspend fun inProgress(accountId: String) = all.filter { it.open && it.resolver != WaitResolver.HUMAN }.sortedBy { it.dueOrTimeout }
            }
        }
    }

    @Test fun capture() {
        val output = System.getenv("TEAMCODE_SCREENSHOTS")?.takeIf { it.isNotBlank() }?.let(::File) ?: return
        output.mkdirs()
        for ((size, window) in listOf("phone" to (390 to 844), "desktop" to (1200 to 800))) for ((theme, name) in listOf(ThemePreference.DARK to "dark", ThemePreference.LIGHT to "light")) {
            with(scenes) {
                scene(window.first, window.second, theme, listOf(waits())) {
                    openWaits()
                    onNodeWithTag("waits").performScrollToNode(hasTestTag("wait-w10"))
                    onNodeWithTag("wait-field-store_password").performTextInput("correct horse battery staple")
                    mainClock.advanceTimeBy(500); waitForIdle()
                    shot(output, "$size-$name-1-masked")
                    onNodeWithTag("waits-filter-answered").performSemanticsAction(SemanticsActions.OnClick)
                    mainClock.advanceTimeBy(1_000); waitForIdle()
                    shot(output, "$size-$name-2-provided")
                }
            }
        }
    }

    private fun DesktopComposeUiTest.openWaits() {
        var waited = 0
        fun present(tag: String) = onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
        while (!present("tab-waits") && !present("sidebar-waits") && waited++ < 20) { Thread.sleep(100); mainClock.advanceTimeBy(250); waitForIdle() }
        onAllNodesWithTag(if (present("tab-waits")) "tab-waits" else "sidebar-waits").onFirst().performSemanticsAction(SemanticsActions.OnClick)
        waited = 0
        while (!present("wait-w1") && waited++ < 20) { Thread.sleep(100); mainClock.advanceTimeBy(250); waitForIdle() }
        mainClock.advanceTimeBy(1_000); waitForIdle()
    }
}
