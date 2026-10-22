package me.nathanfallet.teamcode.companion.presentation

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import me.nathanfallet.teamcode.companion.FakeAuthRepository
import me.nathanfallet.teamcode.companion.FakeFeatures
import me.nathanfallet.teamcode.companion.FakeSessionRepository
import me.nathanfallet.teamcode.companion.FixedClock
import me.nathanfallet.teamcode.companion.InMemoryAccountRepository
import me.nathanfallet.teamcode.companion.SequenceIds
import me.nathanfallet.teamcode.companion.cloudSession
import me.nathanfallet.teamcode.companion.credentials
import me.nathanfallet.teamcode.companion.domain.models.CompanionException
import me.nathanfallet.teamcode.companion.domain.models.Profile
import me.nathanfallet.teamcode.companion.domain.usecases.auth.AccountGuard
import me.nathanfallet.teamcode.companion.domain.usecases.auth.RefreshAccountUseCase
import me.nathanfallet.teamcode.companion.domain.usecases.sessions.CreateSessionUseCase
import me.nathanfallet.teamcode.companion.domain.usecases.sessions.LoadProfilesUseCase
import me.nathanfallet.teamcode.companion.presentation.navigation.NewSessionRoute
import me.nathanfallet.teamcode.companion.presentation.viewmodels.NewSessionViewModel
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class NewSessionViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)
    @AfterTest fun tearDown() = Dispatchers.resetMain()

    @Test fun aRetryAfterAFailureReusesTheKeyAndAnEditDrawsANewOne() = runTest(dispatcher) {
        val sessions = FakeSessionRepository().apply { profileList = listOf(Profile("p1", "Default", "claude")) }
        val features = FakeFeatures()
        val guard = AccountGuard(RefreshAccountUseCase(FakeAuthRepository(), InMemoryAccountRepository(), credentials(), FixedClock()))
        val model = NewSessionViewModel(NewSessionRoute("a1"), LoadProfilesUseCase(sessions, features, guard), CreateSessionUseCase(sessions, features, guard), SequenceIds())
        advanceUntilIdle()
        // The only profile is chosen; the server without the first-message capability hides the field.
        assertEquals("p1", model.uiState.value.profileId)
        assertFalse(model.uiState.value.acceptsMessage)
        model.onName("Fix"); model.onMessage("ignored")
        sessions.createResult = { throw CompanionException.Unreachable() }
        model.create(); advanceUntilIdle()
        assertNotNull(model.uiState.value.error)
        sessions.createResult = { cloudSession("new") }
        model.create(); advanceUntilIdle()
        val creates = sessions.calls.filter { it.startsWith("create") }
        assertEquals(2, creates.size); assertEquals(creates[0], creates[1])
        assertEquals("new", model.uiState.value.created?.id)
        assertFalse(model.uiState.value.canCreate)
    }
}
