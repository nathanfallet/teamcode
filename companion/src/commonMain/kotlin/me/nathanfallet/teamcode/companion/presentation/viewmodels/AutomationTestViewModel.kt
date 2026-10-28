package me.nathanfallet.teamcode.companion.presentation.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import me.nathanfallet.teamcode.companion.domain.models.Automation
import me.nathanfallet.teamcode.companion.domain.models.AutomationPreview
import me.nathanfallet.teamcode.companion.domain.models.AutomationRun
import me.nathanfallet.teamcode.companion.domain.models.TriggerKind
import me.nathanfallet.teamcode.companion.domain.repositories.AccountRepository
import me.nathanfallet.teamcode.companion.domain.services.IdGenerator
import me.nathanfallet.teamcode.companion.domain.usecases.automations.GetAutomationUseCase
import me.nathanfallet.teamcode.companion.domain.usecases.automations.PreviewAutomationUseCase
import me.nathanfallet.teamcode.companion.domain.usecases.automations.TestAutomationUseCase
import me.nathanfallet.teamcode.companion.presentation.navigation.AutomationTestRoute

data class AutomationTestUiState(
    val automation: Automation? = null,
    val loading: Boolean = true,
    val error: Failure? = null,
    /** The payload of a webhook test, pre-filled with the saved sample; unused by a cron automation. */
    val payload: String = "{}",
    val previewing: Boolean = false,
    val preview: AutomationPreview? = null,
    val previewError: Failure? = null,
    val running: Boolean = false,
    val run: AutomationRun? = null,
    val runError: Failure? = null,
    /** Only the owner starts a real test; an administrator previews. */
    val canRun: Boolean = false,
) {
    val webhook: Boolean get() = automation?.trigger?.kind == TriggerKind.WEBHOOK
}

/**
 * The test of one saved automation: a payload (webhook), a preview of the prompt it would render, and a real run (a
 * real session on the owner's quota) whose status is read again on demand, with its session.
 */
class AutomationTestViewModel(
    private val route: AutomationTestRoute,
    private val get: GetAutomationUseCase,
    private val previewing: PreviewAutomationUseCase,
    private val testing: TestAutomationUseCase,
    private val ids: IdGenerator,
    accounts: AccountRepository,
) : ViewModel() {
    private val memberId = accounts.accounts.value.firstOrNull { it.id == route.accountId }?.memberId.orEmpty()
    private val state = MutableStateFlow(AutomationTestUiState())
    val uiState: StateFlow<AutomationTestUiState> = state.asStateFlow()
    /** Kept while a retry may replay the same run; drawn again once a run was accepted. */
    private var key = ids.newId()

    init { load() }

    fun load() {
        state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            try {
                val automation = get(route.accountId, route.automationId)
                state.update { it.copy(automation = automation, loading = false, canRun = automation.ownedBy(memberId), payload = automation.definition.samplePayload ?: "{}") }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                state.update { it.copy(loading = false, error = error.userMessage()) }
            }
        }
    }

    fun setPayload(payload: String) = state.update { it.copy(payload = payload, preview = null, previewError = null) }

    /** Renders the prompt with the payload: no run, no session, no quota. */
    fun preview() {
        val automation = state.value.automation ?: return
        if (state.value.previewing) return
        state.update { it.copy(previewing = true, preview = null, previewError = null) }
        viewModelScope.launch {
            try {
                val result = previewing(route.accountId, automation, state.value.payload)
                state.update { it.copy(previewing = false, preview = result) }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                state.update { it.copy(previewing = false, previewError = error.userMessage()) }
            }
        }
    }

    /** Starts the test. A retry after a failure keeps the key, so it never starts a second session. */
    fun run() {
        val automation = state.value.automation ?: return
        if (state.value.running || !state.value.canRun) return
        state.update { it.copy(running = true, run = null, runError = null) }
        viewModelScope.launch {
            try {
                val started = testing(route.accountId, automation, state.value.payload, key)
                key = ids.newId()
                state.update { it.copy(running = false, run = started) }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                state.update { it.copy(running = false, runError = error.userMessage()) }
            }
        }
    }

    /** Reads the run's status again. */
    fun refreshRun() {
        val run = state.value.run ?: return
        viewModelScope.launch {
            try {
                val fresh = testing.refresh(route.accountId, run)
                state.update { it.copy(run = fresh) }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                state.update { it.copy(runError = error.userMessage()) }
            }
        }
    }
}
