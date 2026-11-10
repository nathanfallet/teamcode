package me.nathanfallet.teamcode.companion.presentation

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import me.nathanfallet.teamcode.companion.FakeAuthRepository
import me.nathanfallet.teamcode.companion.FakeFeatures
import me.nathanfallet.teamcode.companion.FakeNotificationPreferencesRepository
import me.nathanfallet.teamcode.companion.FixedClock
import me.nathanfallet.teamcode.companion.InMemoryAccountRepository
import me.nathanfallet.teamcode.companion.account
import me.nathanfallet.teamcode.companion.credentials
import me.nathanfallet.teamcode.companion.domain.models.Account
import me.nathanfallet.teamcode.companion.domain.models.CompanionException
import me.nathanfallet.teamcode.companion.domain.models.NotificationKind
import me.nathanfallet.teamcode.companion.domain.models.PushTestOutcome
import me.nathanfallet.teamcode.companion.domain.usecases.auth.AccountGuard
import me.nathanfallet.teamcode.companion.domain.usecases.auth.RefreshAccountUseCase
import me.nathanfallet.teamcode.companion.domain.usecases.notifications.LoadNotificationPreferencesUseCase
import me.nathanfallet.teamcode.companion.domain.usecases.notifications.NotificationPreferencesAvailabilityUseCase
import me.nathanfallet.teamcode.companion.domain.usecases.notifications.SendTestPushUseCase
import me.nathanfallet.teamcode.companion.domain.usecases.notifications.SetNotificationPreferenceUseCase
import me.nathanfallet.teamcode.companion.presentation.navigation.NotificationPreferencesRoute
import me.nathanfallet.teamcode.companion.presentation.viewmodels.NotificationPreferencesViewModel
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class NotificationPreferencesViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)
    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private val repository = FakeNotificationPreferencesRepository()
    private val features = FakeFeatures(setOf("notifications.v1", "notifications.push.v1", "notifications.push.test.v1"))

    private fun model(account: Account = account()): NotificationPreferencesViewModel {
        val accounts = InMemoryAccountRepository(listOf(account))
        val guard = AccountGuard(RefreshAccountUseCase(FakeAuthRepository(), accounts, credentials(), FixedClock()))
        return NotificationPreferencesViewModel(NotificationPreferencesRoute(account.id), LoadNotificationPreferencesUseCase(repository, accounts, features, guard),
            SetNotificationPreferenceUseCase(repository, guard), SendTestPushUseCase(repository, guard))
    }

    @Test fun everyKindIsListedOnUntilTurnedOff() = runTest(dispatcher) {
        repository.stored[NotificationKind.TURN_COMPLETED] = false
        val model = model(); runCurrent()
        val state = model.uiState.value
        assertFalse(state.loading)
        assertEquals(NotificationKind.entries.toSet(), state.switches?.keys)
        assertEquals(false, state.switches?.get(NotificationKind.TURN_COMPLETED))
        assertEquals(true, state.switches?.get(NotificationKind.WAITING))
        assertTrue(state.canTest)
    }

    @Test fun turningOffAKindSavesItForTheMember() = runTest(dispatcher) {
        val model = model(); runCurrent()
        model.toggle(NotificationKind.TURN_COMPLETED, false)
        // Shown at once, not tappable until the server answered.
        assertEquals(false, model.uiState.value.switches?.get(NotificationKind.TURN_COMPLETED))
        assertTrue(NotificationKind.TURN_COMPLETED in model.uiState.value.saving)
        runCurrent()
        assertTrue(model.uiState.value.saving.isEmpty())
        assertEquals(false, repository.stored[NotificationKind.TURN_COMPLETED])
        assertEquals(true, repository.stored[NotificationKind.WAITING], "the other kinds stay on")
        assertEquals("set a1 turn_completed false", repository.calls.last())
    }

    @Test fun aRefusedSwitchGoesBack() = runTest(dispatcher) {
        val model = model(); runCurrent()
        repository.failure = CompanionException.Unreachable()
        model.toggle(NotificationKind.WAITING, false)
        runCurrent()
        assertEquals(true, model.uiState.value.switches?.get(NotificationKind.WAITING))
        assertNotNull(model.uiState.value.error)
    }

    @Test fun theTestReportsWhatTheServerSaid() = runTest(dispatcher) {
        val model = model(); runCurrent()
        model.test(); runCurrent()
        assertEquals(PushTestOutcome.Sent(1, 1), model.uiState.value.test)
        repository.outcome = PushTestOutcome.NoPhone
        model.test(); runCurrent()
        assertEquals(PushTestOutcome.NoPhone, model.uiState.value.test)
    }

    @Test fun aMemberOfAnOlderServerHasNoTestButAnAdministratorDoes() = runTest(dispatcher) {
        features.features = setOf("notifications.v1", "notifications.push.v1")
        val member = model(); runCurrent()
        assertFalse(member.uiState.value.canTest)
        member.test(); runCurrent()
        assertFalse(repository.calls.any { it.startsWith("test") })
        val admin = model(account().copy(role = "ADMIN")); runCurrent()
        assertTrue(admin.uiState.value.canTest)
    }

    @Test fun theScreenIsOfferedOnlyWithThePushChannel() = runTest(dispatcher) {
        val accounts = InMemoryAccountRepository(listOf(account(), account("a2", signedIn = false)))
        val availability = NotificationPreferencesAvailabilityUseCase(accounts, features)
        assertTrue(availability.available("a1"))
        assertFalse(availability.available("a2"))
        features.features = setOf("notifications.v1")
        assertFalse(availability.available("a1"))
        val model = model(); runCurrent()
        assertNotNull(model.uiState.value.error)
        assertTrue(repository.calls.isEmpty())
    }
}
