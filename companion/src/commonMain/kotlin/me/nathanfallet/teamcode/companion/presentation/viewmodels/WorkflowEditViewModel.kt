package me.nathanfallet.teamcode.companion.presentation.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import me.nathanfallet.teamcode.companion.domain.models.CompanionException
import me.nathanfallet.teamcode.companion.domain.models.DefinitionNode
import me.nathanfallet.teamcode.companion.domain.models.EditableWorkflow
import me.nathanfallet.teamcode.companion.domain.models.Profile
import me.nathanfallet.teamcode.companion.domain.models.StepKind
import me.nathanfallet.teamcode.companion.domain.models.StoredWorkflowDraft
import me.nathanfallet.teamcode.companion.domain.models.TeamMember
import me.nathanfallet.teamcode.companion.domain.models.Workflow
import me.nathanfallet.teamcode.companion.domain.models.WorkflowDefinition
import me.nathanfallet.teamcode.companion.domain.repositories.WorkflowDraftRepository
import me.nathanfallet.teamcode.companion.domain.usecases.sessions.LoadProfilesUseCase
import me.nathanfallet.teamcode.companion.domain.usecases.transfers.LoadRecipientsUseCase
import me.nathanfallet.teamcode.companion.domain.usecases.workflows.InvalidWorkflow
import me.nathanfallet.teamcode.companion.domain.usecases.workflows.LoadWorkflowCatalogUseCase
import me.nathanfallet.teamcode.companion.domain.usecases.workflows.LoadWorkflowDefinitionUseCase
import me.nathanfallet.teamcode.companion.domain.usecases.workflows.LoadWorkflowSettingsUseCase
import me.nathanfallet.teamcode.companion.domain.usecases.workflows.SaveWorkflowUseCase
import me.nathanfallet.teamcode.companion.domain.usecases.workflows.WorkflowChecks
import me.nathanfallet.teamcode.companion.domain.usecases.workflows.WorkflowEdits
import me.nathanfallet.teamcode.companion.domain.usecases.workflows.WorkflowProblem
import me.nathanfallet.teamcode.companion.presentation.navigation.WorkflowEditRoute
import me.nathanfallet.teamcode.companion.resources.Res
import me.nathanfallet.teamcode.companion.resources.workflow_edit_fix_problems

data class WorkflowEditUiState(
    val definition: WorkflowDefinition = WorkflowEdits.blank(),
    /** Reading the definition being changed (or copied), the saved workflows and the profiles. */
    val loading: Boolean = true,
    /** The failure of reading the definition: the form cannot be used without it. */
    val loadError: Failure? = null,
    /** The other saved workflows by id for the sub-workflow checks; null when they could not be read (the server still checks). */
    val saved: Map<String, Workflow>? = null,
    val problems: List<WorkflowProblem> = emptyList(),
    val saving: Boolean = false,
    val error: Failure? = null,
    /** The id of the workflow once saved: the screen leaves. */
    val savedId: String? = null,
    /** The version saved elsewhere meanwhile (`409 workflow_conflict`): reload, or keep editing as a copy. */
    val conflict: Boolean = false,
    /** Edits from the device's drafts were put back. */
    val restored: Boolean = false,
    /** Changing a saved workflow (else creating one). */
    val editingId: String? = null,
    val baseVersion: Int? = null,
    val profiles: List<Profile> = emptyList(),
    /** The members a human step can ask; null when the server does not list them for this member (an ID is typed). */
    val members: List<TeamMember>? = null,
    /** The caller may not edit workflows (the team's rule). */
    val notAllowed: Boolean = false,
) {
    val canSave: Boolean get() = !loading && loadError == null && !saving && !conflict && !notAllowed && problems.isEmpty() && savedId == null
    fun problemsOf(nodeId: String?) = problems.filter { it.nodeId == nodeId }
}

/**
 * The definition editor: the whole definition as a draft that is checked at every change by the same rules as the server (a
 * problem is shown against its node, Save stays off while any is left), kept on the device (a closed screen or a killed app
 * costs nothing), and saved against the version it started from. A save over a newer version is refused by the server; the member
 * then reloads (losing the edits) or keeps them as a new workflow.
 */
class WorkflowEditViewModel(
    private val route: WorkflowEditRoute,
    private val loadDefinition: LoadWorkflowDefinitionUseCase,
    private val loadCatalog: LoadWorkflowCatalogUseCase,
    private val loadSettings: LoadWorkflowSettingsUseCase,
    private val loadProfiles: LoadProfilesUseCase,
    private val loadMembers: LoadRecipientsUseCase,
    private val save: SaveWorkflowUseCase,
    private val drafts: WorkflowDraftRepository,
) : ViewModel() {
    private val state = MutableStateFlow(WorkflowEditUiState(editingId = route.workflowId))
    val uiState: StateFlow<WorkflowEditUiState> = state.asStateFlow()
    /** What the form is compared with to know whether anything was changed (a draft equal to it is not kept). */
    private var baseline: WorkflowDefinition = WorkflowEdits.blank()

    /** Changing a saved workflow rather than creating one. */
    val isEdit: Boolean get() = state.value.editingId != null

    private val key: String get() = state.value.editingId ?: WorkflowDraftRepository.NEW

    init { load() }

    fun load() {
        state.update { it.copy(loading = true, loadError = null, error = null) }
        viewModelScope.launch {
            try {
                val catalog = try { loadCatalog(route.accountId).associateBy { it.id } } catch (error: CancellationException) { throw error } catch (_: Exception) { null }
                val options = try { loadProfiles(route.accountId).profiles } catch (error: CancellationException) { throw error } catch (_: Exception) { emptyList() }
                val members = try { loadMembers(route.accountId, "") } catch (error: CancellationException) { throw error } catch (_: Exception) { null }
                val allowed = try { loadSettings(route.accountId).canEdit } catch (error: CancellationException) { throw error } catch (_: Exception) { true }
                val source: EditableWorkflow? = (route.workflowId ?: route.copyOf)?.let { loadDefinition(route.accountId, it) }
                val start = when {
                    route.workflowId != null -> source!!.definition
                    source != null -> WorkflowEdits.copyOf(source.definition, route.copySuffix)
                    else -> WorkflowEdits.blank()
                }
                baseline = start
                val stored = drafts.load(route.accountId, key)
                val restored = stored != null && stored.definition != start
                val definition = if (restored) stored!!.definition else start
                if (stored != null && !restored) drafts.clear(route.accountId, key)
                val base = if (route.workflowId == null) null else if (restored) stored!!.baseVersion ?: source?.version else source?.version
                state.update {
                    it.copy(
                        definition = definition, loading = false, saved = catalog, profiles = options, members = members, restored = restored, baseVersion = base,
                        notAllowed = !allowed, problems = check(definition, catalog, it.editingId),
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                state.update { it.copy(loading = false, loadError = error.userMessage()) }
            }
        }
    }

    private fun check(definition: WorkflowDefinition, saved: Map<String, Workflow>?, editingId: String?) = WorkflowChecks.check(definition, saved, editingId)

    /** Changes the draft, checks it again and keeps it on the device. */
    fun edit(change: (WorkflowDefinition) -> WorkflowDefinition) {
        state.update { current ->
            val definition = change(current.definition)
            current.copy(definition = definition, problems = check(definition, current.saved, current.editingId), error = null)
        }
        persist()
    }

    fun editNode(id: String, change: (DefinitionNode) -> DefinitionNode) = edit { WorkflowEdits.update(it, id, change) }

    private fun persist() {
        val current = state.value
        if (current.loading || current.savedId != null) return
        if (current.definition == baseline) drafts.clear(route.accountId, key)
        else drafts.save(route.accountId, key, StoredWorkflowDraft(current.baseVersion, current.definition))
    }

    /** Puts the saved definition back and forgets the edits on the device. */
    fun discardDraft() {
        drafts.clear(route.accountId, key)
        state.update { it.copy(restored = false) }
        load()
    }

    fun save() {
        val current = state.value
        if (!current.canSave) return
        state.update { it.copy(saving = true, error = null) }
        viewModelScope.launch {
            try {
                val id = current.editingId
                val saved = if (id == null) save.create(route.accountId, current.definition, current.saved)
                else save.update(route.accountId, id, current.definition, current.baseVersion ?: 0, current.saved)
                drafts.clear(route.accountId, key)
                state.update { it.copy(saving = false, savedId = saved.id) }
            } catch (error: CancellationException) {
                throw error
            } catch (error: InvalidWorkflow) {
                state.update { it.copy(saving = false, problems = error.problems, error = Failure("Fix what is marked, then save.", Res.string.workflow_edit_fix_problems)) }
            } catch (error: CompanionException.Server) {
                if (error.statusCode == 409 && error.code == "workflow_conflict") state.update { it.copy(saving = false, conflict = true) }
                else state.update { it.copy(saving = false, error = error.userMessage()) }
            } catch (error: Exception) {
                state.update { it.copy(saving = false, error = error.userMessage()) }
            }
        }
    }

    /** After a conflict: the version saved elsewhere replaces the draft (the member's edits are lost). */
    fun reloadAfterConflict() {
        drafts.clear(route.accountId, key)
        state.update { it.copy(conflict = false, restored = false) }
        load()
    }

    /** After a conflict: the edits stay, as a new workflow (named like the old one with the copy suffix); the other person's version is left alone. */
    fun keepAsCopy() {
        val current = state.value
        val old = current.editingId
        val copy = WorkflowEdits.copyOf(current.definition, route.copySuffix)
        if (old != null) drafts.clear(route.accountId, old)
        state.update { it.copy(editingId = null, baseVersion = null, conflict = false, definition = copy, problems = check(copy, it.saved, null), restored = false) }
        baseline = WorkflowEdits.blank()
        persist()
    }

    /** Whether the draft has edits that were not saved. */
    val dirty: Boolean get() = state.value.definition != baseline
}
