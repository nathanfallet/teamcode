package me.nathanfallet.teamcode.companion

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import me.nathanfallet.teamcode.companion.domain.models.Artifact
import me.nathanfallet.teamcode.companion.domain.models.ArtifactPage
import me.nathanfallet.teamcode.companion.domain.models.ArtifactScope
import me.nathanfallet.teamcode.companion.domain.repositories.Features
import me.nathanfallet.teamcode.companion.domain.usecases.artifacts.LoadArtifactsUseCase
import me.nathanfallet.teamcode.companion.domain.usecases.auth.AccountGuard
import me.nathanfallet.teamcode.companion.domain.usecases.auth.RefreshAccountUseCase
import me.nathanfallet.teamcode.companion.presentation.navigation.ArtifactsRoute
import me.nathanfallet.teamcode.companion.presentation.screens.ArtifactsScreen
import me.nathanfallet.teamcode.companion.presentation.theme.TeamCodeTheme
import me.nathanfallet.teamcode.companion.presentation.viewmodels.ArtifactsViewModel
import me.nathanfallet.teamcode.companion.domain.services.Clock
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test

/** The gallery on the desktop target: where the list stands when its rows arrive or are replaced. */
@OptIn(ExperimentalTestApi::class)
class ArtifactsScreenTest {
    private val repository = FakeArtifactRepository()

    /** A network's answer takes a moment: the list is first drawn with no row. */
    private val slow = object : me.nathanfallet.teamcode.companion.domain.repositories.ArtifactRepository by repository {
        override suspend fun artifacts(accountId: String, scope: ArtifactScope, sessionId: String?, before: String?): ArtifactPage {
            kotlinx.coroutines.delay(300)
            return repository.artifacts(accountId, scope, sessionId, before)
        }
    }

    private fun artifact(n: Int, prefix: String = "Artifact") = Artifact(
        "00000000-0000-0000-0000-%012d".format(n), "https://team.example/artifact/$n", "$prefix ${"%02d".format(n)}", "", "", "private", "Ada", 1, "api", null, 1_000L - n,
    )

    private fun model(): ArtifactsViewModel {
        val accounts = InMemoryAccountRepository(listOf(account()))
        val guard = AccountGuard(RefreshAccountUseCase(FakeAuthRepository(), accounts, credentials(), FixedClock()))
        return ArtifactsViewModel(ArtifactsRoute("a1"), LoadArtifactsUseCase(slow, FakeFeatures(setOf(Features.ARTIFACTS)), guard), accounts)
    }

    /** The view model works on the app's main dispatcher, which waitUntil would block: let it run between frames. */
    private fun ComposeUiTest.awaitLoaded(model: ArtifactsViewModel) {
        repeat(40) {
            if (!model.uiState.value.loading) return@repeat
            Thread.sleep(100); mainClock.advanceTimeBy(100); waitForIdle()
        }
        mainClock.advanceTimeBy(500); waitForIdle()
    }

    // The screen reads the app's clock from the global Koin, which the other screen tests start and stop the same way.
    @BeforeTest fun startKoinWithAClock() { startKoin { modules(module { single<Clock> { FixedClock() } }) } }
    @AfterTest fun stopKoinAgain() { stopKoin() }

    private fun ComposeUiTest.show(model: ArtifactsViewModel) = setContent {
        TeamCodeTheme { ArtifactsScreen(model, forSession = false, onBack = null, onOpen = {}, onOpenLink = { false }) }
    }

    @Test fun theListOpensOnItsNewestRowNotAtTheEndOfTheFirstPage() = runComposeUiTest {
        repository.pages = { _, _ -> ArtifactPage((1..20).map { artifact(it) }, null) }
        val model = model()
        show(model)
        awaitLoaded(model)
        onNodeWithText("Artifact 01").assertIsDisplayed()
    }

    @Test fun switchingToTheTeamShowsItsNewestRowToo() = runComposeUiTest {
        repository.pages = { scope, _ -> ArtifactPage((1..20).map { artifact(it, if (scope == ArtifactScope.TEAM) "Team" else "Mine") }, null) }
        val model = model()
        show(model)
        awaitLoaded(model)
        onNodeWithTag("artifacts").performScrollToIndex(12)
        waitForIdle()
        model.setScope(if (model.uiState.value.scope == ArtifactScope.TEAM) ArtifactScope.MINE else ArtifactScope.TEAM)
        awaitLoaded(model)
        onNodeWithText((if (model.uiState.value.scope == ArtifactScope.TEAM) "Team" else "Mine") + " 01").assertIsDisplayed()
    }
}
