package me.nathanfallet.teamcode.companion

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import me.nathanfallet.teamcode.companion.domain.models.NotificationKind
import me.nathanfallet.teamcode.companion.domain.models.PushTestOutcome
import me.nathanfallet.teamcode.companion.domain.usecases.auth.AccountGuard
import me.nathanfallet.teamcode.companion.domain.usecases.auth.RefreshAccountUseCase
import me.nathanfallet.teamcode.companion.domain.usecases.notifications.LoadNotificationPreferencesUseCase
import me.nathanfallet.teamcode.companion.domain.usecases.notifications.SendTestPushUseCase
import me.nathanfallet.teamcode.companion.domain.usecases.notifications.SetNotificationPreferenceUseCase
import me.nathanfallet.teamcode.companion.presentation.navigation.NotificationPreferencesRoute
import me.nathanfallet.teamcode.companion.presentation.screens.NotificationPreferencesScreen
import me.nathanfallet.teamcode.companion.presentation.theme.TeamCodeTheme
import me.nathanfallet.teamcode.companion.presentation.viewmodels.NotificationPreferencesViewModel
import kotlin.test.*

/** The notifications screen on the desktop target: one switch per kind, a tap saves it, and the test reports its result. */
@OptIn(ExperimentalTestApi::class)
class NotificationPreferencesScreenTest {
    private val repository = FakeNotificationPreferencesRepository()

    private fun model(): NotificationPreferencesViewModel {
        val accounts = InMemoryAccountRepository(listOf(account()))
        val guard = AccountGuard(RefreshAccountUseCase(FakeAuthRepository(), accounts, credentials(), FixedClock()))
        val features = FakeFeatures(setOf("notifications.v1", "notifications.push.v1", "notifications.push.test.v1"))
        return NotificationPreferencesViewModel(NotificationPreferencesRoute("a1"), LoadNotificationPreferencesUseCase(repository, accounts, features, guard),
            SetNotificationPreferenceUseCase(repository, guard), SendTestPushUseCase(repository, guard))
    }

    @Test fun aKindIsTurnedOffAndTheTestIsSent() = runComposeUiTest {
        val model = model()
        setContent { TeamCodeTheme { NotificationPreferencesScreen(model, onBack = {}) } }
        waitUntil { model.uiState.value.switches != null }
        for (kind in NotificationKind.entries) onNodeWithTag("notifications-switch-${kind.wire}").assertIsOn()
        onNodeWithTag("notifications-switch-turn_completed").performClick()
        waitUntil { model.uiState.value.saving.isEmpty() && repository.stored[NotificationKind.TURN_COMPLETED] == false }
        onNodeWithTag("notifications-switch-turn_completed").assertIsOff()
        onNodeWithTag("notifications-switch-waiting").assertIsOn()

        repository.outcome = PushTestOutcome.Sent(2, 2)
        onNodeWithTag("notifications-test").performClick()
        waitUntil { model.uiState.value.test != null }
        onNode(hasText("Sent to 2 phone(s). It shows in a few seconds.")).assertExists()
    }
}
