package me.nathanfallet.teamcode.companion.presentation

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import me.nathanfallet.teamcode.companion.*
import me.nathanfallet.teamcode.companion.domain.models.*
import me.nathanfallet.teamcode.companion.domain.repositories.Features
import me.nathanfallet.teamcode.companion.domain.usecases.auth.AccountGuard
import me.nathanfallet.teamcode.companion.domain.usecases.auth.RefreshAccountUseCase
import me.nathanfallet.teamcode.companion.domain.usecases.sessions.LoadProfilesUseCase
import me.nathanfallet.teamcode.companion.domain.usecases.transfers.LoadRecipientsUseCase
import me.nathanfallet.teamcode.companion.domain.usecases.workflows.*
import me.nathanfallet.teamcode.companion.presentation.navigation.*
import me.nathanfallet.teamcode.companion.presentation.viewmodels.*
import kotlin.test.*

/** The definition editor's view model: live checks, drafts on the device, conflicts, and the delete of a workflow another one calls. */
@OptIn(ExperimentalCoroutinesApi::class)
class WorkflowEditViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val repository = FakeWorkflowRepository().also {
        it.saved["wf1"] = editable("wf1", 2); it.definitions = listOf(editable("wf1", 2).toWorkflow())
        it.settings = WorkflowSettings(WorkflowEditors(EditorMode.ADMINS), canEdit = true)
    }
    private val sessions = FakeSessionRepository().also { it.profileList = listOf(Profile("p1", "support", "claude")) }
    private val features = FakeFeatures().also { it.features = it.features + setOf("workflows.v1", Features.TRANSFER_RECIPIENTS) }
    private val accounts = InMemoryAccountRepository(listOf(account("a1")))
    private val guard = AccountGuard(RefreshAccountUseCase(FakeAuthRepository(), accounts, credentials(), FixedClock()))
    private val access = WorkflowAccess(repository, features, accounts, guard)
    private val changes = WorkflowChanges()
    private val drafts = InMemoryWorkflowDrafts()
    private val transfers = FakeTransferRepository()

    @BeforeTest fun setUp() { Dispatchers.setMain(dispatcher) }
    @AfterTest fun tearDown() { Dispatchers.resetMain() }

    private fun editor(route: WorkflowEditRoute = WorkflowEditRoute("a1", "wf1")) = WorkflowEditViewModel(
        route, LoadWorkflowDefinitionUseCase(access), LoadWorkflowCatalogUseCase(access), LoadWorkflowSettingsUseCase(access), LoadProfilesUseCase(sessions, features, guard),
        LoadRecipientsUseCase(transfers, features, accounts, guard), SaveWorkflowUseCase(access, changes), drafts,
    )

    private fun TestScope.settle() { advanceTimeBy(1_000); runCurrent() }

    @Test fun anExistingWorkflowOpensCheckedAndCleanWithItsProfilesAndMembers() = runTest(dispatcher) {
        val model = editor(); settle()
        val state = model.uiState.value
        assertEquals(validDefinition(), state.definition)
        assertEquals(emptyList(), state.problems); assertTrue(state.canSave)
        assertEquals(2, state.baseVersion); assertEquals("wf1", state.editingId)
        assertEquals(listOf("support"), state.profiles.map { it.name })
        assertEquals(listOf("grace", "ada").sorted(), state.members!!.map { it.username }.sorted())
        assertTrue(model.isEdit)
    }

    @Test fun aNewWorkflowStartsBlankWithTheFirstProblemsShownAndSaveOff() = runTest(dispatcher) {
        val model = editor(WorkflowEditRoute("a1")); settle()
        val state = model.uiState.value
        assertNull(state.editingId); assertFalse(state.canSave)
        assertTrue(state.problems.any { it.rule == ProblemRule.NAME })
        assertTrue(state.problemsOf("work").any { it.rule == ProblemRule.AGENT_INSTRUCTION })
    }

    @Test fun aDuplicateStartsFromACopyOfTheSourceAndCreatesANewWorkflow() = runTest(dispatcher) {
        val model = editor(WorkflowEditRoute("a1", copyOf = "wf1", copySuffix = "(copy)")); settle()
        assertEquals("Fix a ticket (copy)", model.uiState.value.definition.name)
        assertNull(model.uiState.value.editingId); assertNull(model.uiState.value.baseVersion)
        assertTrue(model.uiState.value.canSave)
        model.save(); settle()
        assertEquals("new-wf", model.uiState.value.savedId)
        assertTrue(repository.calls.contains("create"))
        assertEquals(2, repository.saved.size)
    }

    @Test fun everyEditIsCheckedAgainAndSaveFollowsTheProblems() = runTest(dispatcher) {
        val model = editor(); settle()
        model.edit { it.copy(name = "") }
        assertFalse(model.uiState.value.canSave)
        assertEquals(listOf(ProblemRule.NAME), model.uiState.value.problems.map { it.rule })
        model.edit { it.copy(name = "Back") }
        assertTrue(model.uiState.value.canSave)
        model.editNode("work") { it.copy(instruction = "Use {{inputs.nope}}") }
        assertEquals(ProblemRule.TEMPLATE_UNKNOWN, model.uiState.value.problemsOf("work").single().rule)
        // Saving is refused here without a request.
        model.save(); settle()
        assertFalse(repository.calls.any { it.startsWith("update") })
    }

    @Test fun savingSendsTheVersionItStartedFromAndClearsTheDraft() = runTest(dispatcher) {
        val model = editor(); settle()
        model.edit { it.copy(name = "Renamed") }
        assertEquals("Renamed", drafts.stored["a1/wf1"]!!.definition.name)
        var changed = 0
        val job = backgroundScope.launch { changes.changes.collect { changed++ } }
        runCurrent()
        model.save(); settle()
        assertTrue(repository.calls.contains("update:wf1:2"))
        assertEquals("wf1", model.uiState.value.savedId)
        assertEquals(3, repository.saved.getValue("wf1").version)
        assertEquals("Renamed", repository.saved.getValue("wf1").definition.name)
        assertNull(drafts.stored["a1/wf1"])
        assertEquals(1, changed)
        job.cancel()
    }

    @Test fun aDraftThatEqualsTheSavedWorkflowIsNotKept() = runTest(dispatcher) {
        val model = editor(); settle()
        model.edit { it.copy(name = "Other") }
        assertNotNull(drafts.stored["a1/wf1"])
        model.edit { it.copy(name = "Fix a ticket") }
        assertNull(drafts.stored["a1/wf1"])
    }

    @Test fun theUnsavedEditsComeBackWhenTheEditorOpensAgain() = runTest(dispatcher) {
        editor().also { settle(); it.edit { d -> d.copy(name = "Half typed") } }
        val again = editor(); settle()
        assertEquals("Half typed", again.uiState.value.definition.name)
        assertTrue(again.uiState.value.restored)
        assertEquals(2, again.uiState.value.baseVersion)
        // Discarding puts the saved definition back and forgets the draft.
        again.discardDraft(); settle()
        assertEquals("Fix a ticket", again.uiState.value.definition.name)
        assertFalse(again.uiState.value.restored); assertNull(drafts.stored["a1/wf1"])
    }

    @Test fun aNewWorkflowKeepsItsDraftUnderNewAndSurvivesAClosedScreen() = runTest(dispatcher) {
        editor(WorkflowEditRoute("a1")).also { settle(); it.edit { d -> d.copy(name = "Draft") } }
        assertNotNull(drafts.stored["a1/new"])
        val again = editor(WorkflowEditRoute("a1")); settle()
        assertEquals("Draft", again.uiState.value.definition.name); assertTrue(again.uiState.value.restored)
    }

    @Test fun aSaveOverANewerVersionOffersToReloadOrToKeepTheEditsAsACopy() = runTest(dispatcher) {
        val model = editor(); settle()
        model.edit { it.copy(name = "Mine") }
        // Someone else saved version 3 meanwhile.
        repository.saved["wf1"] = editable("wf1", 3, validDefinition("Theirs"))
        model.save(); settle()
        assertTrue(model.uiState.value.conflict); assertNull(model.uiState.value.savedId); assertFalse(model.uiState.value.canSave)
        // Reload: their version replaces mine, and my draft is gone.
        model.reloadAfterConflict(); settle()
        assertEquals("Theirs", model.uiState.value.definition.name); assertEquals(3, model.uiState.value.baseVersion)
        assertFalse(model.uiState.value.conflict); assertNull(drafts.stored["a1/wf1"])
    }

    @Test fun keepingTheEditsAfterAConflictSavesThemAsANewWorkflowAndLeavesTheirsAlone() = runTest(dispatcher) {
        val model = editor(); settle()
        model.edit { it.copy(name = "Mine") }
        repository.saved["wf1"] = editable("wf1", 3, validDefinition("Theirs"))
        model.save(); settle()
        model.keepAsCopy()
        val state = model.uiState.value
        assertFalse(state.conflict); assertNull(state.editingId); assertNull(state.baseVersion)
        assertEquals("Mine (copy)", state.definition.name)
        assertNull(drafts.stored["a1/wf1"]); assertNotNull(drafts.stored["a1/new"])
        model.save(); settle()
        assertEquals("new-wf", model.uiState.value.savedId)
        assertEquals("Theirs", repository.saved.getValue("wf1").definition.name)
    }

    @Test fun theServersOtherRefusalsAreShownInWords() = runTest(dispatcher) {
        val model = editor(); settle()
        repository.failures["update"] = CompanionException.Server(409, "workflow_name_taken")
        model.save(); settle()
        assertFalse(model.uiState.value.conflict)
        assertNotNull(model.uiState.value.error)
        // The member can fix and try again.
        repository.failures.clear()
        model.edit { it.copy(name = "Another") }
        assertNull(model.uiState.value.error)
        model.save(); settle()
        assertEquals("wf1", model.uiState.value.savedId)
    }

    @Test fun aMemberTheTeamDoesNotLetEditSeesNoForm() = runTest(dispatcher) {
        repository.settings = WorkflowSettings(WorkflowEditors(EditorMode.ADMINS), canEdit = false)
        val model = editor(); settle()
        assertTrue(model.uiState.value.notAllowed); assertFalse(model.uiState.value.canSave)
    }

    @Test fun aWorkflowOfAKindANewerServerAddedIsNotEditedHere() = runTest(dispatcher) {
        repository.saved["wf1"] = editable("wf1", 2, validDefinition().let { it.copy(nodes = it.nodes + DefinitionNode("x", StepKind.UNKNOWN, "X", rawKind = "teleport")) })
        val model = editor(); settle()
        assertNotNull(model.uiState.value.loadError)
    }

    @Test fun theOtherSavedWorkflowsFeedTheSubWorkflowChecks() = runTest(dispatcher) {
        repository.definitions = listOf(editable("wf1", 2).toWorkflow(), workflow("callee", inputs = listOf(WorkflowInput("topic", "Topic", true, null, 9)), steps = emptyList()))
        val model = editor(); settle()
        model.edit { WorkflowEdits.addNode(it, StepKind.WORKFLOW) }
        model.editNode("workflow1") { it.copy(workflowId = "callee", title = "Call", transitions = listOf(StepTransition("delivered", null))) }
        model.edit { WorkflowEdits.route(it, "review", "approve", "workflow1") }
        assertEquals(listOf(ProblemRule.ARGUMENT_MISSING), model.uiState.value.problems.map { it.rule })
        model.editNode("workflow1") { it.copy(arguments = mapOf("topic" to "{{inputs.ticket}}")) }
        assertEquals(emptyList(), model.uiState.value.problems)
    }

    @Test fun theMembersAreNotListedWhenTheServerDoesNotOfferThem() = runTest(dispatcher) {
        features.features = features.features - Features.TRANSFER_RECIPIENTS
        val model = editor(); settle()
        assertNull(model.uiState.value.members)
    }

    // ---- the page of a workflow: delete ----

    private fun page(id: String = "wf1") = WorkflowViewModel(WorkflowRoute("a1", id), GetWorkflowUseCase(access), LoadWorkflowSettingsUseCase(access), DeleteWorkflowUseCase(access, changes), LoadWorkflowCatalogUseCase(access), drafts, changes)

    @Test fun thePageKnowsWhetherTheMemberMayEdit() = runTest(dispatcher) {
        val allowed = page(); settle()
        assertTrue(allowed.uiState.value.canEdit)
        repository.settings = WorkflowSettings(WorkflowEditors(EditorMode.ADMINS), canEdit = false)
        val denied = page(); settle()
        assertFalse(denied.uiState.value.canEdit)
        repository.failures["settings"] = CompanionException.Unreachable()
        val unknown = page(); settle()
        assertFalse(unknown.uiState.value.canEdit)
    }

    @Test fun deletingRemovesTheWorkflowItsDraftAndTellsTheLists() = runTest(dispatcher) {
        drafts.save("a1", "wf1", StoredWorkflowDraft(2, validDefinition("x")))
        val model = page(); settle()
        model.delete(); settle()
        assertTrue(model.uiState.value.deleted)
        assertTrue(repository.calls.contains("delete:wf1"))
        assertNull(drafts.stored["a1/wf1"])
        assertEquals(emptyList(), repository.definitions)
    }

    @Test fun deletingAWorkflowAnotherOneCallsIsExplainedByNamingTheCallers() = runTest(dispatcher) {
        repository.definitions = listOf(
            editable("wf1", 2).toWorkflow(),
            workflow("boss", steps = listOf(WorkflowStep("call", StepKind.WORKFLOW, "workflow", "Call", null, null, null, null, "wf1", emptyList(), emptyList(), 3))).copy(name = "Boss"),
        )
        repository.failures["delete"] = CompanionException.Server(409, "workflow_in_use")
        val model = page(); settle()
        model.delete(); settle()
        val state = model.uiState.value
        assertFalse(state.deleted); assertFalse(state.deleting)
        assertEquals(listOf("Boss"), state.usedBy)
        assertNull(state.deleteError)
        model.clearDeleteError()
        assertNull(model.uiState.value.usedBy)
    }

    @Test fun otherFailuresOfTheDeleteAreShown() = runTest(dispatcher) {
        repository.failures["delete"] = CompanionException.Unreachable()
        val model = page(); settle()
        model.delete(); settle()
        assertNotNull(model.uiState.value.deleteError); assertNull(model.uiState.value.usedBy); assertFalse(model.uiState.value.deleted)
    }

    @Test fun aSaveFromTheEditorReloadsThePageAndTheList() = runTest(dispatcher) {
        val model = page(); settle()
        val list = WorkflowsViewModel(WorkflowsRoute("a1"), LoadWorkflowsUseCase(access), LoadWorkflowSettingsUseCase(access), changes); settle()
        assertTrue(list.uiState.value.canEdit)
        val before = repository.calls.count { it == "workflows:null" }
        changes.changed(); settle()
        assertEquals(before + 1, repository.calls.count { it == "workflows:null" })
        assertNotNull(model.uiState.value.workflow)
    }

    // ---- who can edit ----

    @Test fun anAdministratorReadsAndChangesWhoCanEdit() = runTest(dispatcher) {
        accounts.update(account("a1").copy(role = "ADMIN"))
        val model = WorkflowEditorsViewModel(WorkflowsRoute("a1"), LoadWorkflowSettingsUseCase(access), SaveWorkflowEditorsUseCase(access), LoadRecipientsUseCase(transfers, features, accounts, guard), accounts); settle()
        val state = model.uiState.value
        assertTrue(state.administrator); assertTrue(state.loaded)
        assertEquals(EditorMode.ADMINS, state.mode); assertFalse(state.changed)
        model.setMode(EditorMode.MEMBERS); model.toggleMember("m2")
        assertTrue(model.uiState.value.changed)
        model.save(); settle()
        assertEquals(listOf(WorkflowEditors(EditorMode.MEMBERS, listOf("m2"))), repository.sentEditors)
        assertFalse(model.uiState.value.changed)
        // Another mode sends no member list.
        model.setMode(EditorMode.ALL); model.save(); settle()
        assertEquals(WorkflowEditors(EditorMode.ALL, emptyList()), repository.sentEditors.last())
    }

    @Test fun aMemberNeverLoadsTheRule() = runTest(dispatcher) {
        val model = WorkflowEditorsViewModel(WorkflowsRoute("a1"), LoadWorkflowSettingsUseCase(access), SaveWorkflowEditorsUseCase(access), LoadRecipientsUseCase(transfers, features, accounts, guard), accounts); settle()
        assertFalse(model.uiState.value.administrator); assertFalse(model.uiState.value.loaded)
        assertFalse(repository.calls.contains("settings"))
    }

    @Test fun aRefusedChangeOfTheRuleKeepsTheChoiceAndSaysWhy() = runTest(dispatcher) {
        accounts.update(account("a1").copy(role = "ADMIN"))
        val model = WorkflowEditorsViewModel(WorkflowsRoute("a1"), LoadWorkflowSettingsUseCase(access), SaveWorkflowEditorsUseCase(access), LoadRecipientsUseCase(transfers, features, accounts, guard), accounts); settle()
        repository.failures["editors"] = CompanionException.Server(400, "invalid_editors")
        model.setMode(EditorMode.ALL); model.save(); settle()
        assertNotNull(model.uiState.value.error); assertTrue(model.uiState.value.changed); assertFalse(model.uiState.value.saving)
    }
}
