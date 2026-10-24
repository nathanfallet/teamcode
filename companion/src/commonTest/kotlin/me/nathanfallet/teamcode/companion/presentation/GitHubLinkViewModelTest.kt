package me.nathanfallet.teamcode.companion.presentation

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import me.nathanfallet.teamcode.companion.FakeAuthRepository
import me.nathanfallet.teamcode.companion.FakeGitHubLinkRepository
import me.nathanfallet.teamcode.companion.FixedClock
import me.nathanfallet.teamcode.companion.InMemoryAccountRepository
import me.nathanfallet.teamcode.companion.account
import me.nathanfallet.teamcode.companion.credentials
import me.nathanfallet.teamcode.companion.domain.models.CompanionException
import me.nathanfallet.teamcode.companion.domain.models.GitHubLinkState
import me.nathanfallet.teamcode.companion.domain.models.GitHubPoll
import me.nathanfallet.teamcode.companion.domain.usecases.auth.AccountGuard
import me.nathanfallet.teamcode.companion.domain.usecases.auth.RefreshAccountUseCase
import me.nathanfallet.teamcode.companion.domain.usecases.github.LoadGitHubLinkUseCase
import me.nathanfallet.teamcode.companion.domain.usecases.github.PollGitHubLinkUseCase
import me.nathanfallet.teamcode.companion.domain.usecases.github.StartGitHubLinkUseCase
import me.nathanfallet.teamcode.companion.domain.usecases.github.UnlinkGitHubUseCase
import me.nathanfallet.teamcode.companion.gitHubLink
import me.nathanfallet.teamcode.companion.presentation.navigation.GitHubLinkRoute
import me.nathanfallet.teamcode.companion.presentation.viewmodels.GitHubLinkEnd
import me.nathanfallet.teamcode.companion.presentation.viewmodels.GitHubLinkViewModel
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class GitHubLinkViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)
    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private val repository = FakeGitHubLinkRepository()
    private lateinit var model: GitHubLinkViewModel

    private fun model(accounts: InMemoryAccountRepository = InMemoryAccountRepository(listOf(account()))): GitHubLinkViewModel {
        val guard = AccountGuard(RefreshAccountUseCase(FakeAuthRepository(), accounts, credentials(), FixedClock()))
        return GitHubLinkViewModel(GitHubLinkRoute("a1"), LoadGitHubLinkUseCase(repository, accounts, guard), StartGitHubLinkUseCase(repository, guard),
            PollGitHubLinkUseCase(repository, guard), UnlinkGitHubUseCase(repository, guard))
    }

    private fun TestScope.settle() = runCurrent()

    @Test fun theLinkIsReadWhenTheServerOffersIt() = runTest(dispatcher) {
        repository.link = gitHubLink()
        model = model(); settle()
        assertEquals(listOf("offered", "link"), repository.calls)
        val state = model.uiState.value
        assertEquals("octocat", state.link?.login)
        assertTrue(state.canLink && state.canUnlink)
    }

    @Test fun aServerWithoutGitHubLinksShowsNoLinkAndCannotStart() = runTest(dispatcher) {
        repository.offered = false
        model = model(); settle()
        assertEquals(listOf("offered"), repository.calls)
        assertFalse(model.uiState.value.offered)
        model.startLinking(); settle()
        assertNull(model.uiState.value.step)
        assertTrue(repository.calls.none { it == "start" })
    }

    @Test fun withoutTheTeamsAppNothingCanBeLinked() = runTest(dispatcher) {
        repository.link = gitHubLink(GitHubLinkState.NOT_LINKED, login = null, appConfigured = false)
        model = model(); settle()
        assertFalse(model.uiState.value.canLink)
        model.startLinking(); settle()
        assertTrue(repository.calls.none { it == "start" })
    }

    @Test fun theDeviceFlowIsPolledAtTheServersIntervalUntilLinked() = runTest(dispatcher) {
        repository.polls = listOf({ GitHubPoll.Pending(null) }, { GitHubPoll.Pending(10) }, { GitHubPoll.Linked(gitHubLink()) })
        model = model(); settle()
        model.startLinking(); settle()
        val step = model.uiState.value.step!!
        assertEquals("ABCD-1234", step.code)
        assertEquals("https://github.com/login/device", step.verificationUri)
        assertFalse(step.opened)
        model.browserOpened()
        assertTrue(model.uiState.value.step!!.opened)
        advanceTimeBy(4_999); settle()
        assertTrue(repository.calls.none { it.startsWith("poll") }, "Nothing is polled before the first interval")
        advanceTimeBy(1); settle()
        assertEquals(1, repository.calls.count { it.startsWith("poll") })
        advanceTimeBy(5_000); settle()
        assertEquals(2, repository.calls.count { it.startsWith("poll") })
        // GitHub asked to slow down to 10 s.
        advanceTimeBy(9_999); settle()
        assertEquals(2, repository.calls.count { it.startsWith("poll") })
        advanceTimeBy(1); settle()
        val state = model.uiState.value
        assertEquals(3, repository.calls.count { it.startsWith("poll") })
        assertNull(state.step)
        assertEquals("octocat", state.link?.login)
        assertEquals("octocat", state.justLinked)
        assertTrue(state.canUnlink)
    }

    @Test fun anExpiredOrDeniedFlowEndsWithItsReasonAndCanStartAgain() = runTest(dispatcher) {
        repository.polls = listOf({ GitHubPoll.Expired })
        model = model(); settle()
        model.startLinking(); advanceTimeBy(5_000); settle()
        assertEquals(GitHubLinkEnd.EXPIRED, model.uiState.value.ended)
        assertNull(model.uiState.value.step)
        repository.polls = listOf({ GitHubPoll.Denied })
        model.startLinking(); settle()
        assertNull(model.uiState.value.ended, "A new attempt clears the last ending")
        advanceTimeBy(5_000); settle()
        assertEquals(GitHubLinkEnd.DENIED, model.uiState.value.ended)
    }

    @Test fun cancellingStopsThePolling() = runTest(dispatcher) {
        model = model(); settle()
        model.startLinking(); settle()
        model.cancelLinking()
        advanceTimeBy(60_000); settle()
        assertNull(model.uiState.value.step)
        assertTrue(repository.calls.none { it.startsWith("poll") })
    }

    @Test fun aRefusedStartKeepsItsReasonAndReadsTheLinkAgain() = runTest(dispatcher) {
        repository.startFailure = CompanionException.GitHubRefused(CompanionException.GitHubRefused.DEVICE_FLOW_DISABLED)
        model = model(); settle()
        model.startLinking(); settle()
        val state = model.uiState.value
        assertNull(state.step)
        assertContains(state.error!!, "device flow")
        assertEquals(listOf("offered", "link", "start", "offered", "link"), repository.calls)
    }

    @Test fun aFewNetworkFailuresAreWaitedOutButNotMany() = runTest(dispatcher) {
        var failures = 0
        repository.polls = listOf({ failures++; throw CompanionException.Unreachable() })
        model = model(); settle()
        model.startLinking(); settle()
        advanceTimeBy(5_000); settle()
        advanceTimeBy(5_000); settle()
        assertNotNull(model.uiState.value.step, "Two failures in a row are waited out")
        advanceTimeBy(5_000); settle()
        assertEquals(3, failures)
        assertNull(model.uiState.value.step)
        assertEquals("The server could not be reached.", model.uiState.value.error)
    }

    @Test fun anotherFailureEndsTheFlowAtOnce() = runTest(dispatcher) {
        repository.polls = listOf({ throw CompanionException.Server(502, "github_unreachable") })
        model = model(); settle()
        model.startLinking(); advanceTimeBy(5_000); settle()
        assertNull(model.uiState.value.step)
        assertContains(model.uiState.value.error!!, "github_unreachable")
        assertEquals(1, repository.calls.count { it.startsWith("poll") })
    }

    @Test fun unlinkingRemovesTheLinkAndReadsItAgain() = runTest(dispatcher) {
        repository.link = gitHubLink()
        model = model(); settle()
        model.unlinkAccount(); settle()
        assertEquals("unlink", repository.calls.dropLast(2).last())
        val state = model.uiState.value
        assertEquals(GitHubLinkState.NOT_LINKED, state.link?.state)
        assertFalse(state.canUnlink)
        assertFalse(state.unlinking)
    }

    @Test fun aSignedOutAccountCannotLoad() = runTest(dispatcher) {
        model = model(InMemoryAccountRepository(listOf(account(signedIn = false)))); settle()
        assertTrue(repository.calls.isEmpty())
        assertNotNull(model.uiState.value.error)
    }
}
