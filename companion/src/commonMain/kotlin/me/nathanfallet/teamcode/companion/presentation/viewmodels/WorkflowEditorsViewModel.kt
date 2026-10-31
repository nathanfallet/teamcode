package me.nathanfallet.teamcode.companion.presentation.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import me.nathanfallet.teamcode.companion.domain.models.EditorMode
import me.nathanfallet.teamcode.companion.domain.models.TeamMember
import me.nathanfallet.teamcode.companion.domain.models.WorkflowEditors
import me.nathanfallet.teamcode.companion.domain.repositories.AccountRepository
import me.nathanfallet.teamcode.companion.domain.usecases.transfers.LoadRecipientsUseCase
import me.nathanfallet.teamcode.companion.domain.usecases.workflows.LoadWorkflowSettingsUseCase
import me.nathanfallet.teamcode.companion.domain.usecases.workflows.SaveWorkflowEditorsUseCase
import me.nathanfallet.teamcode.companion.presentation.navigation.WorkflowsRoute

data class WorkflowEditorsUiState(
    /** Only an administrator sees and changes the rule. */
    val administrator: Boolean = false,
    val loading: Boolean = false,
    val loaded: Boolean = false,
    val mode: EditorMode = EditorMode.ADMINS,
    val memberIds: Set<String> = emptySet(),
    /** The team's members; null when the server did not list them (the chosen members are shown by ID). */
    val members: List<TeamMember>? = null,
    /** What the server has: the card shows Save only for a change. */
    val savedMode: EditorMode = EditorMode.ADMINS,
    val savedMemberIds: Set<String> = emptySet(),
    val saving: Boolean = false,
    val error: Failure? = null,
) {
    val changed: Boolean get() = mode != savedMode || (mode == EditorMode.MEMBERS && memberIds != savedMemberIds)
}

/** "Who can edit workflows": the team's rule, read and changed by an administrator (`GET/PUT /workflows/settings`). */
class WorkflowEditorsViewModel(
    private val route: WorkflowsRoute,
    private val loadSettings: LoadWorkflowSettingsUseCase,
    private val saveEditors: SaveWorkflowEditorsUseCase,
    private val loadMembers: LoadRecipientsUseCase,
    accounts: AccountRepository,
) : ViewModel() {
    private val state = MutableStateFlow(WorkflowEditorsUiState(administrator = accounts.accounts.value.firstOrNull { it.id == route.accountId }?.isAdministrator == true))
    val uiState: StateFlow<WorkflowEditorsUiState> = state.asStateFlow()

    init { if (state.value.administrator) load() }

    fun load() {
        state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            try {
                val settings = loadSettings(route.accountId)
                val members = try { loadMembers(route.accountId, "") } catch (error: CancellationException) { throw error } catch (_: Exception) { null }
                state.update {
                    it.copy(loading = false, loaded = true, mode = settings.editors.mode, memberIds = settings.editors.memberIds.toSet(), members = members,
                        savedMode = settings.editors.mode, savedMemberIds = settings.editors.memberIds.toSet())
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                state.update { it.copy(loading = false, error = error.userMessage()) }
            }
        }
    }

    fun setMode(mode: EditorMode) = state.update { it.copy(mode = mode, error = null) }
    fun toggleMember(id: String) = state.update { it.copy(memberIds = if (id in it.memberIds) it.memberIds - id else it.memberIds + id, error = null) }

    fun save() {
        val current = state.value
        if (current.saving || !current.changed) return
        state.update { it.copy(saving = true, error = null) }
        viewModelScope.launch {
            try {
                val saved = saveEditors(route.accountId, WorkflowEditors(current.mode, if (current.mode == EditorMode.MEMBERS) current.memberIds.toList() else emptyList()))
                state.update { it.copy(saving = false, savedMode = saved.editors.mode, savedMemberIds = saved.editors.memberIds.toSet(), mode = saved.editors.mode, memberIds = saved.editors.memberIds.toSet()) }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                state.update { it.copy(saving = false, error = error.userMessage()) }
            }
        }
    }
}
