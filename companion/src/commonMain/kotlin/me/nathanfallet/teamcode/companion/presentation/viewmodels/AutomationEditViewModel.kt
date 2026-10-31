package me.nathanfallet.teamcode.companion.presentation.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import me.nathanfallet.teamcode.companion.domain.models.ActionKind
import me.nathanfallet.teamcode.companion.domain.models.Automation
import me.nathanfallet.teamcode.companion.domain.models.CompanionException
import me.nathanfallet.teamcode.companion.domain.models.AutomationAction
import me.nathanfallet.teamcode.companion.domain.models.AutomationDefinition
import me.nathanfallet.teamcode.companion.domain.models.AutomationPreview
import me.nathanfallet.teamcode.companion.domain.models.AutomationTrigger
import me.nathanfallet.teamcode.companion.domain.models.AutomationVariable
import me.nathanfallet.teamcode.companion.domain.models.Profile
import me.nathanfallet.teamcode.companion.domain.models.TriggerKind
import me.nathanfallet.teamcode.companion.domain.models.Workflow
import me.nathanfallet.teamcode.companion.domain.usecases.workflows.LoadWorkflowsUseCase
import me.nathanfallet.teamcode.companion.domain.usecases.automations.AutomationSecrets
import me.nathanfallet.teamcode.companion.domain.usecases.automations.GetAutomationUseCase
import me.nathanfallet.teamcode.companion.domain.usecases.automations.PreviewAutomationUseCase
import me.nathanfallet.teamcode.companion.domain.usecases.automations.SaveAutomationUseCase
import me.nathanfallet.teamcode.companion.domain.usecases.sessions.LoadProfilesUseCase
import me.nathanfallet.teamcode.companion.presentation.navigation.AutomationEditRoute

data class AutomationFormState(
    val name: String = "",
    val kind: TriggerKind = TriggerKind.CRON,
    val cron: String = "0 9 * * *",
    val timezone: String = "UTC",
    /** `once` runs the latest missed slot after an outage, `skip` runs nothing. */
    val catchup: String = "once",
    /** Kept as the server has it (no field edits it), so a change of the form does not reset it. */
    val catchupGraceMinutes: Int? = null,
    val profileId: String? = null,
    /** What a run starts: a cloud session (the prompt) or a workflow run ([workflowId] and [inputs]). */
    val action: ActionKind = ActionKind.CLOUD,
    val workflowId: String? = null,
    /** The text of each workflow input, by input id. */
    val inputs: Map<String, String> = emptyMap(),
    val model: String = "",
    val effort: String = "",
    val maxRuntime: String = "60",
    val prompt: String = "",
    val variables: List<AutomationVariable> = emptyList(),
    val samplePayload: String = "",
    val overlap: String = "skip",
    val maxRunsPerDay: String = "24",
)

data class AutomationEditUiState(
    val form: AutomationFormState = AutomationFormState(),
    val profiles: List<Profile> = emptyList(),
    /** The saved workflows a workflow action can start; empty when the server has none (or no workflows at all): the choice is then not offered. */
    val workflows: List<Workflow> = emptyList(),
    /** Reading the automation being changed, and the profiles. */
    val loading: Boolean = true,
    /** The failure of reading the profiles or the automation: the form cannot be used without them. */
    val loadError: Failure? = null,
    val saving: Boolean = false,
    val error: Failure? = null,
    val previewing: Boolean = false,
    val preview: AutomationPreview? = null,
    val previewError: Failure? = null,
    /** The id of the automation once saved: the screen leaves for its page. */
    val savedId: String? = null,
    /** Set when changing an automation of someone else: only the owner changes it. */
    val notOwner: Boolean = false,
)

/** The create and edit form: trigger (cron with a zone, or webhook with its variables), cloud action on a profile, prompt, limits, and a preview of the prompt. */
class AutomationEditViewModel(
    private val route: AutomationEditRoute,
    private val get: GetAutomationUseCase,
    private val loadProfiles: LoadProfilesUseCase,
    private val loadWorkflows: LoadWorkflowsUseCase,
    private val save: SaveAutomationUseCase,
    private val previewing: PreviewAutomationUseCase,
    private val secrets: AutomationSecrets,
    private val accounts: me.nathanfallet.teamcode.companion.domain.repositories.AccountRepository,
) : ViewModel() {
    private val state = MutableStateFlow(AutomationEditUiState())
    val uiState: StateFlow<AutomationEditUiState> = state.asStateFlow()
    private var original: Automation? = null

    /** Changing an existing automation rather than creating one. */
    val isEdit: Boolean get() = route.automationId != null

    init { load() }

    private companion object { const val MAX_WORKFLOW_PAGES = 5 }

    fun load() {
        state.update { it.copy(loading = true, loadError = null) }
        viewModelScope.launch {
            try {
                val options = loadProfiles(route.accountId)
                val existing = route.automationId?.let { get(route.accountId, it) }
                original = existing
                // A trigger kind a newer server added cannot be edited here: it would be saved as another kind.
                if (existing?.trigger?.kind == TriggerKind.UNKNOWN) throw CompanionException.Unsupported("trigger")
                val workflows = savedWorkflows()
                val owner = existing == null || existing.ownedBy(accounts.accounts.value.firstOrNull { it.id == route.accountId }?.memberId.orEmpty())
                state.update { current ->
                    current.copy(
                        profiles = options.profiles, workflows = workflows, loading = false, notOwner = !owner,
                        form = existing?.let { formOf(it.definition) } ?: current.form.copy(profileId = current.form.profileId ?: options.profiles.singleOrNull()?.id),
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                state.update { it.copy(loading = false, loadError = error.userMessage()) }
            }
        }
    }

    /** The saved workflows, a few pages at most. A server without workflows, or a failure, offers none: the cloud action still works. */
    private suspend fun savedWorkflows(): List<Workflow> {
        val all = mutableListOf<Workflow>()
        try {
            var after: String? = null
            repeat(MAX_WORKFLOW_PAGES) {
                val page = loadWorkflows(route.accountId, after)
                all += page.workflows
                after = page.nextAfter
                if (after == null) return all
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
        }
        return all
    }

    /** Chooses the workflow to start; the inputs of another workflow do not carry over. */
    fun chooseWorkflow(workflow: Workflow) = edit { if (it.workflowId == workflow.id) it else it.copy(workflowId = workflow.id, inputs = emptyMap()) }
    fun setInput(id: String, text: String) = edit { it.copy(inputs = it.inputs + (id to text)) }

    fun edit(change: (AutomationFormState) -> AutomationFormState) = state.update { it.copy(form = change(it.form), error = null) }

    fun setVariable(index: Int, variable: AutomationVariable) = edit { form -> form.copy(variables = form.variables.mapIndexed { i, v -> if (i == index) variable else v }) }
    fun addVariable() = edit { it.copy(variables = it.variables + AutomationVariable(name = "", path = "$.")) }
    fun removeVariable(index: Int) = edit { form -> form.copy(variables = form.variables.filterIndexed { i, _ -> i != index }) }

    fun save() {
        val current = state.value
        if (current.saving || current.notOwner) return
        state.update { it.copy(saving = true, error = null) }
        viewModelScope.launch {
            try {
                val definition = definitionOf(state.value.form)
                val existing = original
                val id = if (existing == null) {
                    val created = save.create(route.accountId, definition)
                    created.secret?.let { secrets.hold(created.automation.id, it) }
                    created.automation.id
                } else {
                    save.update(route.accountId, existing, definition).id
                }
                state.update { it.copy(saving = false, savedId = id) }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                state.update { it.copy(saving = false, error = error.userMessage()) }
            }
        }
    }

    /** Renders the prompt of the unsaved draft with the sample payload: no run, no quota. */
    fun preview() {
        if (state.value.previewing) return
        state.update { it.copy(previewing = true, preview = null, previewError = null) }
        viewModelScope.launch {
            try {
                val form = state.value.form
                val result = previewing.draft(route.accountId, definitionOf(form), form.samplePayload.ifBlank { "{}" })
                state.update { it.copy(previewing = false, preview = result) }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                state.update { it.copy(previewing = false, previewError = error.userMessage()) }
            }
        }
    }
}

internal fun formOf(definition: AutomationDefinition) = AutomationFormState(
    name = definition.name,
    kind = definition.trigger.kind,
    cron = definition.trigger.cron.orEmpty(),
    timezone = definition.trigger.timezone ?: "UTC",
    catchup = definition.trigger.catchup ?: "once",
    catchupGraceMinutes = definition.trigger.catchupGraceMinutes,
    profileId = definition.action.profileId,
    action = if (definition.action.kind == ActionKind.WORKFLOW) ActionKind.WORKFLOW else ActionKind.CLOUD,
    workflowId = definition.action.workflowId,
    inputs = definition.action.inputs,
    model = definition.action.model.orEmpty(),
    effort = definition.action.effort.orEmpty(),
    maxRuntime = definition.action.maxRuntimeMinutes.toString(),
    prompt = definition.prompt,
    variables = definition.variables,
    samplePayload = definition.samplePayload.orEmpty(),
    overlap = definition.overlap,
    maxRunsPerDay = definition.maxRunsPerDay.toString(),
)

/** The definition a form describes. A number that is not one becomes 0, which the checks refuse in words. */
internal fun definitionOf(form: AutomationFormState): AutomationDefinition {
    val cron = form.kind == TriggerKind.CRON
    return AutomationDefinition(
        name = form.name,
        trigger = if (cron) AutomationTrigger(TriggerKind.CRON, form.cron, form.timezone, form.catchup, form.catchupGraceMinutes) else AutomationTrigger(TriggerKind.WEBHOOK),
        action = if (form.action == ActionKind.WORKFLOW) AutomationAction(ActionKind.WORKFLOW, form.profileId, workflowId = form.workflowId, inputs = form.inputs)
            else AutomationAction(ActionKind.CLOUD, form.profileId, form.model.trim().ifEmpty { null }, form.effort.trim().ifEmpty { null }, form.maxRuntime.trim().toIntOrNull() ?: 0),
        prompt = if (form.action == ActionKind.WORKFLOW) "" else form.prompt,
        variables = if (cron) emptyList() else form.variables,
        samplePayload = if (cron) null else form.samplePayload.trim().ifEmpty { null },
        overlap = form.overlap,
        maxRunsPerDay = form.maxRunsPerDay.trim().toIntOrNull() ?: 0,
    )
}
