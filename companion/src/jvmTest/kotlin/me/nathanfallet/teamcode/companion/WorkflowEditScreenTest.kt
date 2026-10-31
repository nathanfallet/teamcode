package me.nathanfallet.teamcode.companion

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import me.nathanfallet.teamcode.companion.domain.models.*
import me.nathanfallet.teamcode.companion.domain.repositories.Features
import me.nathanfallet.teamcode.companion.domain.usecases.auth.AccountGuard
import me.nathanfallet.teamcode.companion.domain.usecases.auth.RefreshAccountUseCase
import me.nathanfallet.teamcode.companion.domain.usecases.sessions.LoadProfilesUseCase
import me.nathanfallet.teamcode.companion.domain.usecases.transfers.LoadRecipientsUseCase
import me.nathanfallet.teamcode.companion.domain.usecases.workflows.*
import me.nathanfallet.teamcode.companion.presentation.navigation.*
import me.nathanfallet.teamcode.companion.presentation.screens.*
import me.nathanfallet.teamcode.companion.presentation.theme.TeamCodeTheme
import me.nathanfallet.teamcode.companion.presentation.viewmodels.*
import kotlin.test.*

/** The definition editor on the desktop target over a fake repository: the form, its live checks, the save, a conflict, and the delete of a workflow. */
@OptIn(ExperimentalTestApi::class)
class WorkflowEditScreenTest {
    private val repository = FakeWorkflowRepository().also {
        it.settings = WorkflowSettings(WorkflowEditors(EditorMode.ADMINS), canEdit = true)
        it.saved["wf1"] = editable("wf1", 2); it.definitions = listOf(editable("wf1", 2).toWorkflow())
    }
    private val features = FakeFeatures(setOf("workflows.v1", "cloud.sessions.v1", Features.TRANSFER_RECIPIENTS))
    private val accounts = InMemoryAccountRepository(listOf(account()))
    private val guard = AccountGuard(RefreshAccountUseCase(FakeAuthRepository(), accounts, credentials(), FixedClock()))
    private val access = WorkflowAccess(repository, features, accounts, guard)
    private val changes = WorkflowChanges()
    private val drafts = InMemoryWorkflowDrafts()

    private fun editor(route: WorkflowEditRoute) = WorkflowEditViewModel(
        route, LoadWorkflowDefinitionUseCase(access), LoadWorkflowCatalogUseCase(access), LoadWorkflowSettingsUseCase(access),
        LoadProfilesUseCase(FakeSessionRepository(), features, guard), LoadRecipientsUseCase(FakeTransferRepository(), features, accounts, guard), SaveWorkflowUseCase(access, changes), drafts,
    )

    private fun page(id: String = "wf1") = WorkflowViewModel(WorkflowRoute("a1", id), GetWorkflowUseCase(access), LoadWorkflowSettingsUseCase(access), DeleteWorkflowUseCase(access, changes), LoadWorkflowCatalogUseCase(access), drafts, changes)

    private fun ComposeUiTest.type(tag: String, text: String) { onNodeWithTag(tag).performScrollTo(); onNodeWithTag(tag).performTextInput(text) }

    @Test fun aNewWorkflowCannotBeSavedUntilItHasANameAndAnInstructionThenIsCreated() = runComposeUiTest {
        val model = editor(WorkflowEditRoute("a1"))
        var saved: String? = null
        setContent { TeamCodeTheme { WorkflowEditScreen(model, onBack = {}, onSaved = { saved = it }) } }
        waitUntil { !model.uiState.value.loading }
        onNodeWithTag("wfe-save").assertIsNotEnabled()
        // The refusals show against the step and the workflow, in words.
        onNodeWithTag("wfe-n0-problems", useUnmergedTree = true).assertExists()
        type("wfe-name", "Triage")
        type("wfe-n0-title", "Triage it")
        type("wfe-n0-instruction", "Look at the ticket.")
        waitForIdle()
        onNodeWithTag("wfe-save").assertIsEnabled()
        onNodeWithTag("wfe-save").performClick()
        waitUntil { saved != null }
        assertEquals("new-wf", saved)
        assertEquals("Triage", repository.saved.getValue("new-wf").definition.name)
        assertNull(drafts.stored["a1/new"], "the draft is gone once it is saved")
    }

    @Test fun addingAHumanStepShowsItsProblemsAndTheFlowMapListsIt() = runComposeUiTest {
        val model = editor(WorkflowEditRoute("a1", "wf1"))
        setContent { TeamCodeTheme { WorkflowEditScreen(model, onBack = {}, onSaved = {}) } }
        waitUntil { !model.uiState.value.loading }
        onNodeWithTag("wfe-save").assertIsEnabled()
        onNodeWithTag("wfe-map").performScrollTo().assertExists()
        onNodeWithTag("wfe-add-human").performScrollTo().performClick()
        waitForIdle()
        // The new step is not routed: the form says so and Save goes off.
        assertTrue(model.uiState.value.definition.nodes.size == 3)
        onNodeWithTag("wfe-save").assertIsNotEnabled()
        assertTrue(model.uiState.value.problems.any { it.rule == ProblemRule.NOT_REACHABLE })
        assertNotNull(drafts.stored["a1/wf1"], "the unsaved edit is kept on the device")
    }

    @Test fun aSaveOverANewerVersionOffersToReloadOrToKeepTheEdits() = runComposeUiTest {
        val model = editor(WorkflowEditRoute("a1", "wf1"))
        setContent { TeamCodeTheme { WorkflowEditScreen(model, onBack = {}, onSaved = {}) } }
        waitUntil { !model.uiState.value.loading }
        type("wfe-name", " mine")
        repository.saved["wf1"] = editable("wf1", 3, validDefinition("Theirs"))
        onNodeWithTag("wfe-save").performClick()
        waitUntil { model.uiState.value.conflict }
        onNodeWithTag("wfe-conflict").performScrollTo().assertExists()
        onNodeWithTag("wfe-save").assertIsNotEnabled()
        onNodeWithTag("wfe-conflict-reload", useUnmergedTree = true).assertExists()
    }

    @Test fun aMemberWhoCannotEditSeesNoFormAndNoButtonsOnThePage() = runComposeUiTest {
        repository.settings = WorkflowSettings(WorkflowEditors(EditorMode.ADMINS), canEdit = false)
        val workflow = page()
        setContent { TeamCodeTheme { WorkflowScreen(workflow, onBack = {}, onStart = {}) } }
        waitUntil { workflow.uiState.value.workflow != null }
        onNodeWithTag("workflow-edit").assertDoesNotExist(); onNodeWithTag("workflow-delete").assertDoesNotExist(); onNodeWithTag("workflow-duplicate").assertDoesNotExist()
    }

    @Test fun deletingAsksFirstThenExplainsAWorkflowAnotherOneCalls() = runComposeUiTest {
        repository.definitions = repository.definitions + workflow("boss", steps = listOf(WorkflowStep("call", StepKind.WORKFLOW, "workflow", "Call", null, null, null, null, "wf1", emptyList(), emptyList(), 3))).copy(name = "Boss")
        repository.failures["delete"] = CompanionException.Server(409, "workflow_in_use")
        val workflow = page()
        var deleted = false
        setContent { TeamCodeTheme { WorkflowScreen(workflow, onBack = {}, onStart = {}, onDeleted = { deleted = true }) } }
        waitUntil { workflow.uiState.value.canEdit }
        onNodeWithTag("workflow-delete").performScrollTo().performClick()
        onNodeWithTag("workflow-delete-confirm").assertExists()
        assertFalse(repository.calls.any { it.startsWith("delete") }, "nothing is deleted before the confirmation")
        onNodeWithTag("workflow-delete-confirm-yes").performClick()
        waitUntil { workflow.uiState.value.usedBy != null }
        onNode(hasTestTag("workflow-in-use") and (hasText("Boss", substring = true) or hasAnyDescendant(hasText("Boss", substring = true))), useUnmergedTree = true).assertExists()
        assertFalse(deleted)
        // Without the refusal the workflow is deleted and the page leaves.
        repository.failures.clear()
        onNodeWithTag("workflow-delete").performScrollTo().performClick()
        onNodeWithTag("workflow-delete-confirm-yes").performClick()
        waitUntil { deleted }
        assertTrue(repository.calls.contains("delete:wf1"))
    }
}
