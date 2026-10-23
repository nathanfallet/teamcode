package me.nathanfallet.teamcode.companion.presentation

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
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
        settle()
        // The only profile is chosen; the server without the first-message capability hides the field.
        assertEquals("p1", model.uiState.value.profileId)
        assertFalse(model.uiState.value.acceptsMessage)
        model.onName("Fix"); model.onMessage("ignored")
        // While the profiles load again, their answer may change what is sent: no creation until it is in.
        model.reload()
        assertFalse(model.uiState.value.canCreate)
        settle()
        assertTrue(model.uiState.value.canCreate)
        sessions.createResult = { throw CompanionException.Unreachable() }
        model.create(); settle()
        assertNotNull(model.uiState.value.error)
        // A trailing space changes nothing sent: the retry keeps the key, so the server returns what it may have created.
        model.onName("Fix ")
        model.create(); settle()
        var creates = sessions.calls.filter { it.startsWith("create") }
        assertEquals(2, creates.size); assertEquals(creates[0], creates[1])
        // Another name is another session: a new key.
        model.onName("Fix it")
        sessions.createResult = { cloudSession("new") }
        model.create(); settle()
        creates = sessions.calls.filter { it.startsWith("create") }
        assertEquals(3, creates.size); assertNotEquals(creates[1].substringAfterLast(' '), creates[2].substringAfterLast(' '))
        assertEquals("new", model.uiState.value.created?.id)
        assertFalse(model.uiState.value.canCreate)
    }
}

/** Runs what is due within a second of virtual time; the screens' periodic refreshes never let the scheduler go idle. */
@OptIn(ExperimentalCoroutinesApi::class)
private fun TestScope.settle() { advanceTimeBy(1_000); runCurrent() }
