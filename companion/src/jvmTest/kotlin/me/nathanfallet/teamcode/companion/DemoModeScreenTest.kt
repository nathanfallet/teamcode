package me.nathanfallet.teamcode.companion

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import com.russhwolf.settings.MapSettings
import com.russhwolf.settings.Settings
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import me.nathanfallet.teamcode.companion.demo.DemoContent
import me.nathanfallet.teamcode.companion.demo.StartDemoUseCase
import me.nathanfallet.teamcode.companion.di.appModules
import me.nathanfallet.teamcode.companion.domain.repositories.AccountRepository
import me.nathanfallet.teamcode.companion.domain.services.CredentialStore
import me.nathanfallet.teamcode.companion.domain.services.PlatformInfo
import me.nathanfallet.teamcode.companion.domain.services.SecureRandom
import me.nathanfallet.teamcode.companion.domain.usecases.auth.InspectServerUseCase
import me.nathanfallet.teamcode.companion.infrastructure.system.JavaSecureRandom
import me.nathanfallet.teamcode.companion.presentation.components.DemoBanner
import me.nathanfallet.teamcode.companion.presentation.screens.AddServerScreen
import me.nathanfallet.teamcode.companion.presentation.theme.TeamCodeTheme
import me.nathanfallet.teamcode.companion.presentation.viewmodels.AddServerViewModel
import org.koin.core.Koin
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import kotlin.test.*

/**
 * The built-in demo in the whole app (App(), real screens and navigation, the app's own modules): "Try the demo" on
 * the sign-in screen, the banner on the demo account only, a message answered by the scripted reply, and no network.
 */
@OptIn(ExperimentalTestApi::class)
class DemoModeScreenTest {
    @Test fun tryTheDemoOpensTheAppOnTheDemoWithItsBannerAndAnswersAMessage() {
        val engine = CountingEngine()
        demoApp(engine) { koin ->
            // The first screen of a device without accounts offers the demo.
            onNodeWithTag("try-demo").performSemanticsAction(SemanticsActions.OnClick)
            settle()
            assertEquals(DemoContent.ACCOUNT_ID, koin.get<AccountRepository>().activeAccountId.value)
            onNodeWithTag("demo-banner").assertExists()
            onAllNodesWithText("Fix the flaky login test").onFirst().performSemanticsAction(SemanticsActions.OnClick)
            settle()
            onNodeWithTag("composer").performTextInput("Can you add a dark mode?")
            onNodeWithTag("send").performClick()
            settle()
            onAllNodesWithText("Can you add a dark mode?").onFirst().assertExists()
            onAllNodesWithText("This is the TeamCode demo", substring = true).onFirst().assertExists()
            onNodeWithTag("demo-banner").assertExists()
        }
        assertEquals(0, engine.clients)
    }

    @Test fun onAPhoneTheSessionsListOpensOnTheNewestSession() {
        // The list first shows its end row (no account yet), then the sessions arrive above it: it stays at the top.
        demoApp(CountingEngine(), width = 390, height = 844) {
            onNodeWithTag("try-demo").performSemanticsAction(SemanticsActions.OnClick)
            settle()
            onAllNodesWithText("Fix the flaky login test").onFirst().assertIsDisplayed()
        }
    }

    @Test fun theDemoShowsTheWaitingScreenFromTheSidebarAndAnAnswerUpdatesTheBadgeLive() {
        demoApp(CountingEngine()) {
            onNodeWithTag("try-demo").performSemanticsAction(SemanticsActions.OnClick)
            settle()
            // Five of the demo's waits are open: the sidebar's row counts them, and the screen lists them with their sessions.
            onNodeWithTag("sidebar-waits-badge", useUnmergedTree = true).assertContentDescriptionEquals("5 waiting for you")
            onNodeWithTag("waits-banner").assertExists()
            onNodeWithTag("sidebar-waits").performSemanticsAction(SemanticsActions.OnClick)
            settle()
            onNodeWithTag("waits-badge").assertContentDescriptionEquals("5 waiting for you")
            onNodeWithTag("wait-w1").assertExists()
            onNodeWithTag("wait-w2").assertExists()
            onNodeWithTag("waits-banner").assertDoesNotExist()
            onNodeWithTag("wait-field-feedback").performTextInput("Go ahead")
            onNodeWithTag("wait-action-approve").performSemanticsAction(SemanticsActions.OnClick)
            settle()
            onNodeWithTag("wait-w1").assertDoesNotExist()
            onNodeWithTag("sidebar-waits-badge", useUnmergedTree = true).assertContentDescriptionEquals("4 waiting for you")
            // What was answered shows under its filter, with the action and what was given.
            onNodeWithTag("waits-filter-answered").performSemanticsAction(SemanticsActions.OnClick)
            settle()
            onNodeWithTag("wait-w1").assertExists()
            onNodeWithText("Feedback: Go ahead").assertExists()
        }
    }

    @Test fun onAPhoneTheWaitingTabCountsTheWaitsAndTheSessionsBannerOpensIt() {
        demoApp(CountingEngine(), width = 390, height = 844) {
            onNodeWithTag("try-demo").performSemanticsAction(SemanticsActions.OnClick)
            settle()
            onNodeWithTag("tab-badge-waits", useUnmergedTree = true).assertContentDescriptionEquals("5 waiting for you")
            onNodeWithTag("waits-banner").performSemanticsAction(SemanticsActions.OnClick)
            settle()
            onNodeWithTag("wait-w1").assertExists()
            onNodeWithTag("waits-badge").assertExists()
            // The badge stays while another tab shows.
            onNodeWithTag("tab-sessions").performSemanticsAction(SemanticsActions.OnClick)
            settle()
            onNodeWithTag("tab-badge-waits", useUnmergedTree = true).assertContentDescriptionEquals("5 waiting for you")
        }
    }

    @Test fun theBannerShowsForTheDemoAccountOnly() = runComposeUiTest {
        var account by mutableStateOf(account("a1", origin = "https://team.example"))
        val opened = mutableListOf<String>()
        val browser = object : UriHandler { override fun openUri(uri: String) { opened += uri } }
        setContent { CompositionLocalProvider(LocalUriHandler provides browser) { TeamCodeTheme { DemoBanner(account) } } }
        onNodeWithTag("demo-banner").assertDoesNotExist()
        account = DemoContent.account("Bench")
        waitForIdle()
        onNodeWithTag("demo-banner").assertExists()
        onNodeWithText("You're trying the demo").assertExists()
        // The link goes through the app's external link handler, to the project's page.
        onNodeWithTag("demo-github").performClick()
        assertEquals(listOf(DemoContent.REPOSITORY_URL), opened)
    }

    @Test fun theSignInScreenOffersTheDemoOnlyWithoutAccounts() = runComposeUiTest {
        val accounts = InMemoryAccountRepository(listOf(account("a1")))
        // The demo-aware accounts are the app's; with an account already there, nothing is offered either.
        val model = AddServerViewModel(InspectServerUseCase(FakeAuthRepository()), accounts, StartDemoUseCase(accounts, FakePlatform))
        setContent { TeamCodeTheme { AddServerScreen(canGoBack = true, onBack = {}, onContinue = {}, viewModel = model) } }
        onNodeWithTag("check").assertExists()
        onNodeWithTag("try-demo").assertDoesNotExist()
    }

    @Test fun signingOutOfTheDemoGoesBackToTheSignInScreen() {
        demoApp(CountingEngine()) { koin ->
            onNodeWithTag("try-demo").performSemanticsAction(SemanticsActions.OnClick)
            settle()
            // Settings, in the wide layout's sidebar: the GitHub link of the demo, then signing out.
            runOnUiThread { me.nathanfallet.teamcode.companion.presentation.navigation.AppCommands.send(me.nathanfallet.teamcode.companion.presentation.navigation.AppCommand.SHOW_ACCOUNT) }
            settle()
            onNodeWithTag("demo-repository").assertExists()
            onAllNodesWithTag("sign-out").onFirst().performSemanticsAction(SemanticsActions.OnClick)
            settle()
            assertTrue(koin.get<AccountRepository>().accounts.value.isEmpty())
            onNodeWithTag("try-demo").assertExists()
            onNodeWithTag("demo-banner").assertDoesNotExist()
        }
    }

    private fun DesktopComposeUiTest.settle() {
        repeat(3) { mainClock.advanceTimeBy(1_000); waitForIdle() }
    }
}

/** Every HTTP client the app would create: the demo must create none. */
internal class CountingEngine : HttpClientEngineFactory<MockEngineConfig> {
    var clients = 0
    override fun create(block: MockEngineConfig.() -> Unit): HttpClientEngine {
        clients++
        return MockEngine(MockEngineConfig().apply { addHandler { error("No network in the demo") } })
    }
}

/** The app on its own modules (not the demo data of [demoModules]), on a fresh device: no account, empty preferences. */
@OptIn(ExperimentalTestApi::class)
internal fun demoApp(engine: HttpClientEngineFactory<*>, width: Int = 1200, height: Int = 800, density: Float = 1f, block: DesktopComposeUiTest.(Koin) -> Unit) {
    val koin = startKoin {
        modules(appModules(module {
            single<Settings> { MapSettings() }
            single<CredentialStore> { credentials() }
            single<HttpClientEngineFactory<*>> { engine }
            single<PlatformInfo> { FakePlatform }
            single<SecureRandom> { JavaSecureRandom }
        }))
    }.koin
    try {
        runDesktopComposeUiTest((width * density).toInt(), (height * density).toInt()) {
            val owner = object : ViewModelStoreOwner { override val viewModelStore = ViewModelStore() }
            setContent {
                CompositionLocalProvider(LocalViewModelStoreOwner provides owner, androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(density)) {
                    TeamCodeTheme { App() }
                }
            }
            mainClock.advanceTimeBy(1_000); waitForIdle()
            block(koin)
            mainClock.advanceTimeBy(2_000); waitForIdle()
        }
    } finally {
        stopKoin()
    }
}
