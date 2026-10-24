package me.nathanfallet.teamcode.companion.presentation

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import me.nathanfallet.teamcode.companion.FakeAuthRepository
import me.nathanfallet.teamcode.companion.FakeSubscriptionAccountRepository
import me.nathanfallet.teamcode.companion.FixedClock
import me.nathanfallet.teamcode.companion.InMemoryAccountRepository
import me.nathanfallet.teamcode.companion.account
import me.nathanfallet.teamcode.companion.admin
import me.nathanfallet.teamcode.companion.credentials
import me.nathanfallet.teamcode.companion.domain.models.CompanionException
import me.nathanfallet.teamcode.companion.domain.models.EnrollmentAccess
import me.nathanfallet.teamcode.companion.domain.usecases.auth.AccountGuard
import me.nathanfallet.teamcode.companion.domain.usecases.auth.RefreshAccountUseCase
import me.nathanfallet.teamcode.companion.domain.usecases.subscriptions.CompleteSubscriptionSignInUseCase
import me.nathanfallet.teamcode.companion.domain.usecases.subscriptions.LoadSubscriptionAccountsUseCase
import me.nathanfallet.teamcode.companion.domain.usecases.subscriptions.StartSubscriptionSignInUseCase
import me.nathanfallet.teamcode.companion.presentation.navigation.SubscriptionAccountsRoute
import me.nathanfallet.teamcode.companion.presentation.viewmodels.AddAccountStep
import me.nathanfallet.teamcode.companion.presentation.viewmodels.SubscriptionAccountsViewModel
import me.nathanfallet.teamcode.companion.subscription
import me.nathanfallet.teamcode.companion.subscriptionTypes
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class SubscriptionAccountsViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)
    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private val repository = FakeSubscriptionAccountRepository()

    private fun model(accounts: InMemoryAccountRepository = InMemoryAccountRepository(listOf(admin()))): SubscriptionAccountsViewModel {
        val guard = AccountGuard(RefreshAccountUseCase(FakeAuthRepository(), accounts, credentials(), FixedClock()))
        return SubscriptionAccountsViewModel(SubscriptionAccountsRoute("a1"), LoadSubscriptionAccountsUseCase(repository, accounts, guard),
            StartSubscriptionSignInUseCase(repository, guard), CompleteSubscriptionSignInUseCase(guard))
    }

    private val signIn get() = model.uiState.value.step as AddAccountStep.SignIn
    private lateinit var model: SubscriptionAccountsViewModel

    @Test fun theCheckComesFirstThenAnAdministratorSeesThePool() = runTest(dispatcher) {
        model = model(); settle()
        assertEquals(listOf("access", "accounts"), repository.calls)
        val state = model.uiState.value
        assertEquals(EnrollmentAccess.ALLOWED, state.access)
        assertEquals(listOf("acc1"), state.accounts?.map { it.id })
        assertTrue(state.canAdd)
    }

    @Test fun aMemberDoesNotAskForThePool() = runTest(dispatcher) {
        model = model(InMemoryAccountRepository(listOf(account()))); settle()
        assertEquals(listOf("access"), repository.calls)
        assertNull(model.uiState.value.accounts)
        assertTrue(model.uiState.value.canAdd)
    }

    @Test fun aRefusedMemberCannotStartAdding() = runTest(dispatcher) {
        repository.access = EnrollmentAccess.ADMINISTRATORS_ONLY
        model = model(InMemoryAccountRepository(listOf(account()))); settle()
        assertFalse(model.uiState.value.canAdd)
        model.startAdding()
        assertNull(model.uiState.value.step)
    }

    @Test fun theApiKeyIsListedButCannotBeChosen() = runTest(dispatcher) {
        model = model(); settle()
        model.startAdding()
        assertEquals(AddAccountStep.ChooseType, model.uiState.value.step)
        assertEquals(subscriptionTypes, model.uiState.value.types)
        model.choose(subscriptionTypes.last()); settle()
        assertEquals(AddAccountStep.ChooseType, model.uiState.value.step)
        assertTrue(repository.calls.none { it.startsWith("start") })
    }

    @Test fun aPastedCodeAddsTheAccountAndTheListIsReadAgain() = runTest(dispatcher) {
        model = model(); settle()
        model.startAdding()
        model.choose(subscriptionTypes.first()); settle()
        // The enrollment check is asked again before any browser opens.
        assertEquals(listOf("access", "accounts", "access", "start anthropic-subscription 1"), repository.calls)
        assertEquals("https://provider.example/authorize?attempt=1", signIn.signIn.url)
        assertFalse(signIn.opened)
        model.browserOpened()
        assertTrue(signIn.opened)
        assertFalse(signIn.canSubmit)
        model.onPaste("  code#state ")
        repository.pool = listOf(subscription(), subscription("acc2"))
        model.submit(); settle()
        assertEquals(listOf("code#state"), repository.pastes)
        val state = model.uiState.value
        assertNull(state.step)
        assertEquals("acc2", state.added?.account?.id)
        assertTrue(state.added!!.created)
        assertEquals(listOf("acc1", "acc2"), state.accounts?.map { it.id })
        assertEquals("accounts", repository.calls.last())
    }

    @Test fun aWrongPasteCanBeCorrectedWithTheSameAttempt() = runTest(dispatcher) {
        repository.completion = { throw CompanionException.ProviderSignIn(restart = false, "Paste the complete code#state shown by Claude.") }
        model = model(); settle()
        model.startAdding(); model.choose(subscriptionTypes.first()); settle()
        model.onPaste("nope"); model.submit(); settle()
        assertEquals("Paste the complete code#state shown by Claude.", signIn.error?.text)
        assertFalse(signIn.spent)
        assertEquals("nope", signIn.paste)
        model.onPaste("better")
        assertNull(signIn.error)
        assertTrue(signIn.canSubmit)
    }

    @Test fun aSpentAttemptOffersOnlyANewOne() = runTest(dispatcher) {
        repository.completion = { throw CompanionException.ProviderSignIn(restart = true, "Claude sign-in failed. Start a new sign-in attempt.") }
        model = model(); settle()
        model.startAdding(); model.choose(subscriptionTypes[1]); settle()
        model.onPaste("expired"); model.submit(); settle()
        assertTrue(signIn.spent)
        assertEquals("", signIn.paste, "A spent code is not kept")
        assertFalse(signIn.canSubmit)
        model.choose(signIn.signIn.type); settle()
        assertEquals("https://provider.example/authorize?attempt=2", signIn.signIn.url)
        assertFalse(signIn.spent)
        assertNull(signIn.error)
    }

    @Test fun aRefusalOfTheServerEndsTheFlowAndKeepsItsReason() = runTest(dispatcher) {
        repository.completion = { throw CompanionException.EnrollmentRefused("account_in_use") }
        model = model(); settle()
        model.startAdding(); model.choose(subscriptionTypes.first()); settle()
        model.onPaste("code#state"); model.submit(); settle()
        assertTrue(signIn.spent)
        assertContains(signIn.error!!.text, "already in the pool")
        // The setting changed since the screen loaded: starting again is refused before any sign-in, and read again.
        repository.access = EnrollmentAccess.ADMINISTRATORS_ONLY
        model.choose(signIn.signIn.type); settle()
        val state = model.uiState.value
        assertNull(state.step)
        assertContains(state.error!!.text, "Only administrators")
        assertEquals(EnrollmentAccess.ADMINISTRATORS_ONLY, state.access)
        assertEquals(1, repository.attempts)
    }

    @Test fun cancellingForgetsTheAttempt() = runTest(dispatcher) {
        model = model(); settle()
        model.startAdding(); model.choose(subscriptionTypes.first()); settle()
        model.onPaste("code#state")
        model.cancelAdding()
        assertNull(model.uiState.value.step)
        model.submit(); settle()
        assertTrue(repository.pastes.isEmpty())
    }

    private fun TestScope.settle() = runCurrent()
}
