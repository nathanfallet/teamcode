package me.nathanfallet.teamcode.companion.presentation.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import me.nathanfallet.teamcode.companion.domain.models.NotificationKind
import me.nathanfallet.teamcode.companion.domain.models.PushTestOutcome
import me.nathanfallet.teamcode.companion.domain.usecases.notifications.LoadNotificationPreferencesUseCase
import me.nathanfallet.teamcode.companion.domain.usecases.notifications.SendTestPushUseCase
import me.nathanfallet.teamcode.companion.domain.usecases.notifications.SetNotificationPreferenceUseCase
import me.nathanfallet.teamcode.companion.presentation.navigation.NotificationPreferencesRoute

data class NotificationPreferencesUiState(
    val loading: Boolean = true,
    /** Null until the server answered. */
    val switches: Map<NotificationKind, Boolean>? = null,
    val canTest: Boolean = false,
    /** The switches being saved: each is shown in its new position and not tappable until the server answered. */
    val saving: Set<NotificationKind> = emptySet(),
    val testing: Boolean = false,
    val test: PushTestOutcome? = null,
    val error: Failure? = null,
)

/**
 * The member's push switches on one TeamCode account's server, one per kind, and the test push. A switch flips at once
 * and goes back if the server refuses; the server's answer is what stays shown.
 */
class NotificationPreferencesViewModel(
    private val route: NotificationPreferencesRoute,
    private val load: LoadNotificationPreferencesUseCase,
    private val set: SetNotificationPreferenceUseCase,
    private val sendTest: SendTestPushUseCase,
) : ViewModel() {
    private val state = MutableStateFlow(NotificationPreferencesUiState())
    val uiState: StateFlow<NotificationPreferencesUiState> = state.asStateFlow()

    init { refresh() }

    fun refresh() {
        state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            try {
                val preferences = load(route.accountId)
                state.update { it.copy(loading = false, switches = preferences.switches, canTest = preferences.canTest) }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                state.update { it.copy(loading = false, error = error.userMessage()) }
            }
        }
    }

    fun toggle(kind: NotificationKind, enabled: Boolean) {
        val current = state.value
        val before = current.switches?.get(kind) ?: return
        if (kind in current.saving || before == enabled) return
        state.update { it.copy(switches = it.switches?.plus(kind to enabled), saving = it.saving + kind, error = null) }
        viewModelScope.launch {
            try {
                val saved = set(route.accountId, kind, enabled)
                state.update { it.copy(switches = saved, saving = it.saving - kind) }
            } catch (error: CancellationException) {
                state.update { it.copy(switches = it.switches?.plus(kind to before), saving = it.saving - kind) }
                throw error
            } catch (error: Exception) {
                state.update { it.copy(switches = it.switches?.plus(kind to before), saving = it.saving - kind, error = error.userMessage()) }
            }
        }
    }

    fun test() {
        if (state.value.testing || !state.value.canTest) return
        state.update { it.copy(testing = true, test = null, error = null) }
        viewModelScope.launch {
            try {
                val outcome = sendTest(route.accountId)
                state.update { it.copy(testing = false, test = outcome) }
            } catch (error: CancellationException) {
                state.update { it.copy(testing = false) }
                throw error
            } catch (error: Exception) {
                state.update { it.copy(testing = false, error = error.userMessage()) }
            }
        }
    }
}
