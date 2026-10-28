package me.nathanfallet.teamcode.companion.presentation.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import me.nathanfallet.teamcode.companion.domain.models.AutomationScope
import me.nathanfallet.teamcode.companion.domain.models.AutomationSummary
import me.nathanfallet.teamcode.companion.domain.repositories.AccountRepository
import me.nathanfallet.teamcode.companion.domain.usecases.automations.AutomationOwned
import me.nathanfallet.teamcode.companion.domain.usecases.automations.DeleteAutomationUseCase
import me.nathanfallet.teamcode.companion.domain.usecases.automations.LoadAutomationsUseCase
import me.nathanfallet.teamcode.companion.domain.usecases.automations.SetAutomationEnabledUseCase
import me.nathanfallet.teamcode.companion.presentation.navigation.AutomationsRoute

data class AutomationsUiState(
    val scope: AutomationScope = AutomationScope.MINE,
    /** Null until the server answered: a server without automations never gets there, so the tab or section stays hidden. */
    val automations: List<AutomationSummary>? = null,
    val nextBefore: String? = null,
    val loading: Boolean = true,
    val loadingMore: Boolean = false,
    val error: Failure? = null,
    /** The automations being armed, paused or deleted. */
    val busy: Set<String> = emptySet(),
    /** The failure of the last toggle or deletion, kept apart from the list's. */
    val actionError: Failure? = null,
    /** The account's role: the team's automations are an administrator's filter. */
    val administrator: Boolean = false,
    val memberId: String = "",
) {
    val empty: Boolean get() = automations?.isEmpty() == true && !loading
    /** Whether the member may arm or pause [automation] from the list: arm is the owner's, pause the owner's or an administrator's. */
    fun canToggle(automation: AutomationSummary) = automation.ownedBy(memberId) || (administrator && automation.enabled)
}

/** The member's automations (or the team's, for an administrator), newest first, a page at a time; arming and pausing from the list. */
class AutomationsViewModel(
    private val route: AutomationsRoute,
    private val load: LoadAutomationsUseCase,
    private val setEnabled: SetAutomationEnabledUseCase,
    private val delete: DeleteAutomationUseCase,
    accounts: AccountRepository,
) : ViewModel() {
    private val account = accounts.accounts.value.firstOrNull { it.id == route.accountId }
    private val state = MutableStateFlow(AutomationsUiState(administrator = account?.isAdministrator == true, memberId = account?.memberId.orEmpty()))
    val uiState: StateFlow<AutomationsUiState> = state.asStateFlow()
    private var reading: Job? = null

    init { reload() }

    fun setScope(scope: AutomationScope) {
        if (scope == state.value.scope || (scope == AutomationScope.TEAM && !state.value.administrator)) return
        state.update { it.copy(scope = scope, automations = null, nextBefore = null, error = null) }
        reload()
    }

    /** Reads the first page again; what was shown stays while it loads and after a failure. */
    fun reload() {
        reading?.cancel()
        val scope = state.value.scope
        state.update { it.copy(loading = true, loadingMore = false, error = null) }
        reading = viewModelScope.launch { page(scope, before = null) }
    }

    fun loadMore() {
        val before = state.value.nextBefore ?: return
        if (reading?.isActive == true) return
        val scope = state.value.scope
        state.update { it.copy(loadingMore = true, error = null) }
        reading = viewModelScope.launch { page(scope, before) }
    }

    private suspend fun page(scope: AutomationScope, before: String?) {
        try {
            val page = load(route.accountId, scope, before)
            state.update { current ->
                if (current.scope != scope) return@update current
                val known = if (before == null) emptySet() else current.automations.orEmpty().map { it.id }.toSet()
                val rows = if (before == null) page.automations else current.automations.orEmpty() + page.automations.filter { it.id !in known }
                current.copy(automations = rows, nextBefore = page.nextBefore, loading = false, loadingMore = false)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            state.update { it.copy(loading = false, loadingMore = false, error = error.userMessage()) }
        }
    }

    /** Arms a paused automation or pauses an armed one; the row takes the server's answer. */
    fun toggle(automation: AutomationSummary) {
        if (automation.id in state.value.busy) return
        state.update { it.copy(busy = it.busy + automation.id, actionError = null) }
        viewModelScope.launch {
            try {
                val updated = setEnabled(route.accountId, AutomationOwned(automation.id, automation.ownerId), enabled = !automation.enabled)
                state.update { current ->
                    current.copy(busy = current.busy - automation.id, automations = current.automations?.map {
                        if (it.id == automation.id) it.copy(enabled = updated.enabled, disabledReason = updated.disabledReason, nextRunAt = updated.nextRunAt, version = updated.version) else it
                    })
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                state.update { it.copy(busy = it.busy - automation.id, actionError = error.userMessage()) }
            }
        }
    }

    fun remove(automation: AutomationSummary) {
        if (automation.id in state.value.busy) return
        state.update { it.copy(busy = it.busy + automation.id, actionError = null) }
        viewModelScope.launch {
            try {
                delete(route.accountId, automation.id)
                state.update { current -> current.copy(busy = current.busy - automation.id, automations = current.automations?.filterNot { it.id == automation.id }) }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                state.update { it.copy(busy = it.busy - automation.id, actionError = error.userMessage()) }
            }
        }
    }
}
