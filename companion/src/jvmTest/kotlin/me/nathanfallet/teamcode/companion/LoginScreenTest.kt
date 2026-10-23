package me.nathanfallet.teamcode.companion

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import me.nathanfallet.teamcode.companion.domain.models.LoginSession
import me.nathanfallet.teamcode.companion.domain.usecases.auth.ChangePasswordUseCase
import me.nathanfallet.teamcode.companion.domain.usecases.auth.InspectServerUseCase
import me.nathanfallet.teamcode.companion.domain.usecases.auth.SignInUseCase
import me.nathanfallet.teamcode.companion.presentation.navigation.LoginRoute
import me.nathanfallet.teamcode.companion.presentation.screens.LoginScreen
import me.nathanfallet.teamcode.companion.presentation.theme.TeamCodeTheme
import me.nathanfallet.teamcode.companion.presentation.viewmodels.LoginViewModel
import kotlin.test.*

/** The sign-in form on the desktop target, with fake server answers: origin shown, password change, signed in. */
@OptIn(ExperimentalTestApi::class)
class LoginScreenTest {
    @Test fun aTemporaryPasswordLeadsToTheNewPasswordFormThenSignsIn() = runComposeUiTest {
        val auth = FakeAuthRepository()
        auth.sessions.clear()
        auth.sessions += LoginSession("temporary", 2_000_000L, member(passwordChangeRequired = true))
        auth.sessions += LoginSession("token", 2_000_000L, member())
        val accounts = InMemoryAccountRepository()
        val store = credentials()
        val signIn = SignInUseCase(auth, accounts, store, FixedInstallation, SequenceIds())
        val viewModel = LoginViewModel(LoginRoute("https://team.example"), InspectServerUseCase(auth), signIn, ChangePasswordUseCase(auth, signIn), FakePlatform)
        var signedIn = false
        setContent { TeamCodeTheme { LoginScreen(LoginRoute("https://team.example"), onBack = {}, onSignedIn = { signedIn = true }, viewModel = viewModel) } }

        onNodeWithText("TeamCode server").assertExists()
        onNodeWithText("https://team.example").assertExists()
        onNodeWithTag("submit").assertIsNotEnabled()
        onNodeWithTag("username").performTextInput("ada")
        onNodeWithTag("password").performTextInput("temporary-password")
        onNodeWithTag("submit").assertIsEnabled().performClick()
        waitUntil { viewModel.uiState.value.passwordChange != null }

        onNodeWithTag("new-password").performTextInput("a new long password")
        onNodeWithTag("confirm-password").performTextInput("a different password")
        onNodeWithTag("submit").performClick()
        waitUntil { viewModel.uiState.value.error != null }
        onNodeWithTag("error").assertExists()
        assertTrue(accounts.accounts.value.isEmpty())

        viewModel.onConfirmationChange("a new long password")
        onNodeWithTag("submit").performClick()
        waitUntil { signedIn }
        assertEquals("ada", accounts.accounts.value.single().username)
        assertEquals("0.7.0", accounts.accounts.value.single().serverVersion)
        assertEquals("", viewModel.uiState.value.password)
    }

    @Test fun anUnreachableServerOffersARetry() = runComposeUiTest {
        val auth = object : me.nathanfallet.teamcode.companion.domain.repositories.AuthRepository by FakeAuthRepository() {
            override suspend fun discover(origin: String) = throw me.nathanfallet.teamcode.companion.domain.models.CompanionException.Unreachable()
        }
        val signIn = SignInUseCase(auth, InMemoryAccountRepository(), credentials(), FixedInstallation, SequenceIds())
        val viewModel = LoginViewModel(LoginRoute("https://team.example"), InspectServerUseCase(auth), signIn, ChangePasswordUseCase(auth, signIn), FakePlatform)
        setContent { TeamCodeTheme { LoginScreen(LoginRoute("https://team.example"), onBack = {}, onSignedIn = {}, viewModel = viewModel) } }
        onNodeWithText("The server could not be reached.").assertExists()
        onNodeWithText("Retry").assertExists()
        onNodeWithTag("password").assertDoesNotExist()
    }
}
