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
import me.nathanfallet.teamcode.companion.FakePlatform
import me.nathanfallet.teamcode.companion.demo.StartDemoUseCase
import me.nathanfallet.teamcode.companion.InMemoryAccountRepository
import me.nathanfallet.teamcode.companion.domain.usecases.auth.InspectServerUseCase
import me.nathanfallet.teamcode.companion.presentation.viewmodels.AddServerViewModel
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class AddServerViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)
    @AfterTest fun tearDown() = Dispatchers.resetMain()

    @Test fun theTypedAddressIsTrimmedBeforeItIsCheckedAndKeptTrimmed() = runTest(dispatcher) {
        val auth = FakeAuthRepository()
        val model = InMemoryAccountRepository().let { AddServerViewModel(InspectServerUseCase(auth), it, StartDemoUseCase(it, FakePlatform)) }
        // A phone keyboard's autocompletion adds a space, a paste a line break or an invisible character.
        model.onOriginChange(" ​https://team.example \n")
        model.check(); settle()
        assertEquals("https://team.example", model.uiState.value.origin)
        assertEquals("https://team.example", model.uiState.value.server?.origin)
        assertNull(model.uiState.value.error)
        assertEquals("discover https://team.example", auth.calls.single())
    }

    @Test fun anAddressOfSpacesOnlyIsNotChecked() = runTest(dispatcher) {
        val auth = FakeAuthRepository()
        val model = InMemoryAccountRepository().let { AddServerViewModel(InspectServerUseCase(auth), it, StartDemoUseCase(it, FakePlatform)) }
        model.onOriginChange(" \n\t")
        model.check(); settle()
        assertFalse(model.uiState.value.checking)
        assertTrue(auth.calls.isEmpty())
    }
}

private fun TestScope.settle() = runCurrent()
