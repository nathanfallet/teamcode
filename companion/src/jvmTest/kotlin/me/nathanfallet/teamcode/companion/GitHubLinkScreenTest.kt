package me.nathanfallet.teamcode.companion

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.runtime.CompositionLocalProvider
import me.nathanfallet.teamcode.companion.domain.models.GitHubLinkState
import me.nathanfallet.teamcode.companion.domain.usecases.auth.AccountGuard
import me.nathanfallet.teamcode.companion.domain.usecases.auth.RefreshAccountUseCase
import me.nathanfallet.teamcode.companion.domain.usecases.github.LoadGitHubLinkUseCase
import me.nathanfallet.teamcode.companion.domain.usecases.github.PollGitHubLinkUseCase
import me.nathanfallet.teamcode.companion.domain.usecases.github.StartGitHubLinkUseCase
import me.nathanfallet.teamcode.companion.domain.usecases.github.UnlinkGitHubUseCase
import me.nathanfallet.teamcode.companion.presentation.navigation.GitHubLinkRoute
import me.nathanfallet.teamcode.companion.presentation.screens.GitHubLinkScreen
import me.nathanfallet.teamcode.companion.presentation.theme.TeamCodeTheme
import me.nathanfallet.teamcode.companion.presentation.viewmodels.GitHubLinkViewModel
import kotlin.test.*

/** The GitHub screen on the desktop target: the linked login, and the device flow's code copied with GitHub's page opened. */
@OptIn(ExperimentalTestApi::class)
class GitHubLinkScreenTest {
    private val repository = FakeGitHubLinkRepository()

    private fun model(): GitHubLinkViewModel {
        val accounts = InMemoryAccountRepository(listOf(account()))
        val guard = AccountGuard(RefreshAccountUseCase(FakeAuthRepository(), accounts, credentials(), FixedClock()))
        return GitHubLinkViewModel(GitHubLinkRoute("a1"), LoadGitHubLinkUseCase(repository, accounts, guard), StartGitHubLinkUseCase(repository, guard),
            PollGitHubLinkUseCase(repository, guard), UnlinkGitHubUseCase(repository, guard))
    }

    @Test fun theLinkedAccountIsShown() = runComposeUiTest {
        repository.link = gitHubLink()
        val model = model()
        setContent { TeamCodeTheme { GitHubLinkScreen(model, onBack = {}) } }
        waitUntil { !model.uiState.value.loading }
        onNodeWithTag("github-login").assertExists()
        onNodeWithTag("github-unlink").assertExists()
        onNodeWithTag("github-start").assertExists()
        repository.link.let { assertEquals(GitHubLinkState.LINKED, it.state) }
    }

    @Test fun linkingCopiesTheCodeAndOpensGitHub() = runComposeUiTest {
        val opened = mutableListOf<String>()
        var copied: String? = null
        val uriHandler = object : UriHandler { override fun openUri(uri: String) { opened += uri } }
        val clipboard = object : ClipboardManager {
            override fun getText() = copied?.let(::AnnotatedString)
            override fun setText(annotatedString: AnnotatedString) { copied = annotatedString.text }
        }
        val model = model()
        setContent {
            TeamCodeTheme {
                CompositionLocalProvider(LocalUriHandler provides uriHandler, LocalClipboardManager provides clipboard) { GitHubLinkScreen(model, onBack = {}) }
            }
        }
        waitUntil { !model.uiState.value.loading }
        onNodeWithTag("github-not-linked").assertExists()
        onNodeWithTag("github-unlink").assertDoesNotExist()
        onNodeWithTag("github-start").performClick()
        waitUntil { model.uiState.value.step?.opened == true }
        onNodeWithTag("github-code").assertExists()
        assertEquals("ABCD-1234", copied)
        assertEquals(listOf("https://github.com/login/device"), opened)
        // Opened once; "Open GitHub again" is there if the browser did not come up.
        onNodeWithTag("github-open").performClick()
        assertEquals(2, opened.size)
        onNodeWithTag("github-cancel").performClick()
        waitUntil { model.uiState.value.step == null }
        onNodeWithTag("github-start").assertExists()
    }
}
