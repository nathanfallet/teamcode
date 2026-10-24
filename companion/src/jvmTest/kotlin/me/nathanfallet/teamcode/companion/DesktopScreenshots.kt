package me.nathanfallet.teamcode.companion

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import com.russhwolf.settings.MapSettings
import com.russhwolf.settings.Settings
import java.io.File
import javax.imageio.ImageIO
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import me.nathanfallet.teamcode.companion.di.appModules
import me.nathanfallet.teamcode.companion.domain.models.Account
import me.nathanfallet.teamcode.companion.domain.models.ArtifactPage
import me.nathanfallet.teamcode.companion.domain.models.LiveUpdate
import me.nathanfallet.teamcode.companion.domain.models.SessionGroup
import me.nathanfallet.teamcode.companion.domain.models.SessionPage
import me.nathanfallet.teamcode.companion.domain.models.SessionState
import me.nathanfallet.teamcode.companion.domain.models.ThemePreference
import me.nathanfallet.teamcode.companion.domain.models.TranscriptEvent
import me.nathanfallet.teamcode.companion.domain.models.TranscriptPage
import me.nathanfallet.teamcode.companion.domain.models.TurnPhase
import me.nathanfallet.teamcode.companion.domain.repositories.AccountRepository
import me.nathanfallet.teamcode.companion.domain.repositories.ArtifactRepository
import me.nathanfallet.teamcode.companion.domain.repositories.AuthRepository
import me.nathanfallet.teamcode.companion.domain.repositories.DeviceRepository
import me.nathanfallet.teamcode.companion.domain.repositories.FeaturesRepository
import me.nathanfallet.teamcode.companion.domain.repositories.PreferencesRepository
import me.nathanfallet.teamcode.companion.domain.repositories.SessionFileRepository
import me.nathanfallet.teamcode.companion.domain.repositories.SessionRepository
import me.nathanfallet.teamcode.companion.domain.repositories.SubscriptionAccountRepository
import me.nathanfallet.teamcode.companion.domain.repositories.TranscriptRepository
import me.nathanfallet.teamcode.companion.domain.repositories.TransferRepository
import me.nathanfallet.teamcode.companion.domain.services.Clock
import me.nathanfallet.teamcode.companion.domain.services.CredentialStore
import me.nathanfallet.teamcode.companion.domain.services.InstallationIdentity
import me.nathanfallet.teamcode.companion.domain.services.PlatformInfo
import me.nathanfallet.teamcode.companion.domain.services.SecureRandom
import me.nathanfallet.teamcode.companion.infrastructure.system.JavaSecureRandom
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import kotlin.test.Test

/**
 * Captures of the whole app (App(), real screens and navigation) on demo data at several window sizes, for the pull
 * request's before/after. Opt-in: runs only when TEAMCODE_SCREENSHOTS names an output folder, so CI skips it.
 * `TEAMCODE_SCREENSHOTS=/tmp/shots ./gradlew :companion:jvmTest --tests '*DesktopScreenshots*'`
 */
@OptIn(ExperimentalTestApi::class)
class DesktopScreenshots {
    private val sizes = listOf(
        "phone-390" to (390 to 844),
        "tablet-split-600" to (600 to 900),
        "tablet-portrait-820" to (820 to 1180),
        "desktop-800" to (800 to 700),
        "desktop-1200" to (1200 to 800),
        "desktop-1600" to (1600 to 1000),
    )

    @Test fun capture() {
        val output = System.getenv("TEAMCODE_SCREENSHOTS")?.takeIf { it.isNotBlank() }?.let(::File) ?: return
        output.mkdirs()
        val only = System.getenv("TEAMCODE_SCREENSHOTS_ONLY")?.split(',')?.toSet()
        for ((name, size) in sizes) {
            if (only != null && name !in only) continue
            for (theme in listOf(ThemePreference.DARK, ThemePreference.LIGHT)) {
                if (theme == ThemePreference.LIGHT && name != "desktop-1200") continue
                val suffix = if (theme == ThemePreference.LIGHT) "-light" else ""
                scene(size.first, size.second, theme) {
                    shot(output, "$name$suffix-1-home")
                    clickText("Fix the flaky login test")
                    mainClock.advanceTimeBy(1_000); waitForIdle()
                    shot(output, "$name$suffix-2-session")
                }
            }
        }
    }

    private fun scene(width: Int, height: Int, theme: ThemePreference, block: DesktopComposeUiTest.() -> Unit) {
        val koin = startKoin { modules(appModules(demoModule()) + demoOverrides()) }.koin
        koin.get<PreferencesRepository>().setTheme(theme)
        try {
            // Rendered at 2x: the window is twice the size in pixels and the content sees a density of 2.
            runDesktopComposeUiTest(width * 2, height * 2) {
                val owner = object : ViewModelStoreOwner { override val viewModelStore = ViewModelStore() }
                setContent { CompositionLocalProvider(LocalViewModelStoreOwner provides owner, LocalDensity provides Density(2f)) { App() } }
                mainClock.advanceTimeBy(1_000); waitForIdle()
                try { block() } catch (error: Throwable) { error.printStackTrace(); throw error }
                // Back to the first screen before the scene closes: a pushed entry still entering fails the teardown.
                mainClock.advanceTimeBy(2_000)
                waitForIdle()
            }
        } finally {
            stopKoin()
        }
    }

    private fun DesktopComposeUiTest.clickText(text: String): SemanticsNodeInteraction =
        onAllNodesWithText(text).onFirst().performSemanticsAction(SemanticsActions.OnClick)

    private fun DesktopComposeUiTest.shot(output: File, name: String) {
        waitForIdle()
        ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(output, "$name.png"))
    }

    private fun demoModule() = module {
        single<Settings> { MapSettings() }
        single<CredentialStore> { credentials().also { store -> runBlocking { demoAccounts.forEach { store.write(it.id, "token") } } } }
        single<io.ktor.client.engine.HttpClientEngineFactory<*>> { io.ktor.client.engine.java.Java }
        single<PlatformInfo> { FakePlatform }
        single<SecureRandom> { JavaSecureRandom }
    }

    private fun demoOverrides() = module {
        single<AccountRepository> { InMemoryAccountRepository(demoAccounts) }
        single<AuthRepository> { FakeAuthRepository() }
        single<InstallationIdentity> { FixedInstallation }
        single<Clock> { FixedClock(NOW) }
        single<FeaturesRepository> { FakeFeatures(setOf("cloud.sessions.v1", "cloud.transcript.v1", "cloud.messages.v1", "cloud.events.v1", "cloud.permissions.v1", "cloud.sessions.remote.v1")) }
        single<SessionRepository> { demoSessions() }
        single<TranscriptRepository> { demoTranscript() }
        single<ArtifactRepository> { demoArtifacts() }
        single<SessionFileRepository> { FakeFileRepository() }
        single<DeviceRepository> { FakeDeviceRepository() }
        single<TransferRepository> { FakeTransferRepository() }
        single<SubscriptionAccountRepository> { FakeSubscriptionAccountRepository() }
    }

    private val demoAccounts = listOf(
        account("a1", origin = "https://code.acme.dev", username = "ada", expiresAt = Long.MAX_VALUE),
        account("a2", origin = "https://teamcode.example.org", memberId = "m9", username = "ada.lovelace", expiresAt = Long.MAX_VALUE),
    )

    private fun demoSessions() = FakeSessionRepository().apply {
        val minute = 60_000L
        val names = listOf(
            "Fix the flaky login test" to SessionState.ACTIVE, "Migrate billing to the new API" to SessionState.READY,
            "Review the release checklist" to SessionState.READY, "Speed up the CI cache" to SessionState.ACTIVE,
            "Write the onboarding guide" to SessionState.READY, "Upgrade Kotlin to 2.4" to SessionState.PREPARING,
            "Audit the payment webhooks" to SessionState.READY, "Prototype the search page" to SessionState.READY,
        )
        val active = names.mapIndexed { index, (name, state) ->
            cloudSession("s$index", state, createdAt = NOW - (index * 17 + 2) * minute, host = if (index == 3) laptop() else null)
                .copy(name = name, repositories = listOf("acme/web", "acme/api", "acme/infra")[index % 3].let { listOf(it) }, effort = if (index % 2 == 0) "high" else null)
        }
        active.forEach { byId[it.id] = it }
        pages = { group, _ ->
            when (group) {
                SessionGroup.ACTIVE -> SessionPage(active, null)
                SessionGroup.PAUSED -> SessionPage(listOf(cloudSession("p1", SessionState.PAUSED, createdAt = NOW - 300 * minute).copy(name = "Refactor the settings screen")), null)
                SessionGroup.ENDED -> SessionPage(emptyList(), null)
            }
        }
        changes = flow { emit(LiveUpdate.Opened(false)); awaitCancellation() }
    }

    private fun demoTranscript() = object : TranscriptRepository {
        override suspend fun page(accountId: String, sessionId: String, before: Long?): TranscriptPage {
            val at = NOW - 600_000
            val events = listOf(
                TranscriptEvent.UserMessage(1, at, "The login test fails about once in ten runs on CI. Can you find out why and fix it?", "in-1", "m1"),
                TranscriptEvent.AgentMessage(2, at + 5_000, "I'll start by reading the test and running it in a loop to reproduce the failure.", null),
                TranscriptEvent.ToolCall(3, at + 6_000, "t1", "Bash", "{\n  \"command\": \"./gradlew :web:test --tests LoginTest --rerun\"\n}", "./gradlew :web:test --tests LoginTest --rerun", null),
                TranscriptEvent.ToolResult(4, at + 40_000, "t1", "BUILD SUCCESSFUL in 31s", false, 0, null),
                TranscriptEvent.ToolCall(5, at + 41_000, "t2", "Read", "{\n  \"file_path\": \"web/src/test/LoginTest.kt\"\n}", "web/src/test/LoginTest.kt", null),
                TranscriptEvent.ToolResult(6, at + 42_000, "t2", "class LoginTest { ... }", false, null, null),
                TranscriptEvent.AgentMessage(7, at + 60_000,
                    "Found it: the test reads the session cookie before the redirect completes. **The fix** waits for the redirect, then asserts:\n\n" +
                        "```kotlin\nwaitUntil { page.url.endsWith(\"/home\") }\nassertNotNull(cookies[\"session\"])\n```\n\nI ran it 50 times locally without a failure.", null),
                TranscriptEvent.Turn(8, at + 61_000, TurnPhase.entries.first { it.wire == "completed" }, 61_000, 6),
                TranscriptEvent.UserMessage(9, at + 120_000, "Great, open a pull request with that change.", "in-2", "m1"),
            )
            return TranscriptPage(events, null, 9)
        }
        override fun follow(accountId: String, sessionId: String, after: Long) = flow<LiveUpdate<TranscriptEvent>> { emit(LiveUpdate.Opened(false)); awaitCancellation() }
    }

    private fun demoArtifacts() = FakeArtifactRepository().apply {
        val titles = listOf("Q3 latency report", "Login flakiness analysis", "Release 2.4 checklist", "Billing API migration plan", "CI cache benchmark",
            "Onboarding guide draft", "Webhook audit findings", "Search page prototype")
        val list = titles.mapIndexed { index, title ->
            artifact("r$index", title, version = index % 3 + 1, updatedAt = NOW - (index * 45 + 5) * 60_000L).copy(description = "Published by a session: $title.")
        }
        list.forEach { byId[it.id] = it }
        pages = { _, _ -> ArtifactPage(list, null) }
    }

    private companion object {
        const val NOW = 1_800_000_000_000L
    }
}

