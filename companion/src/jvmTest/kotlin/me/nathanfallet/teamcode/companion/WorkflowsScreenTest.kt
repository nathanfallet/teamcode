package me.nathanfallet.teamcode.companion

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import me.nathanfallet.teamcode.companion.domain.models.*
import me.nathanfallet.teamcode.companion.domain.repositories.WaitRepository
import me.nathanfallet.teamcode.companion.domain.usecases.auth.AccountGuard
import me.nathanfallet.teamcode.companion.domain.usecases.auth.RefreshAccountUseCase
import me.nathanfallet.teamcode.companion.domain.usecases.sessions.LoadProfilesUseCase
import me.nathanfallet.teamcode.companion.domain.usecases.sessions.SessionFeed
import me.nathanfallet.teamcode.companion.domain.usecases.workflows.*
import me.nathanfallet.teamcode.companion.presentation.navigation.*
import me.nathanfallet.teamcode.companion.presentation.screens.*
import me.nathanfallet.teamcode.companion.presentation.theme.TeamCodeTheme
import me.nathanfallet.teamcode.companion.presentation.viewmodels.*
import kotlin.test.*

/** The workflow screens on the desktop target over a fake repository: runs, run page, definitions and the start form. */
@OptIn(ExperimentalTestApi::class)
class WorkflowsScreenTest {
    private val repository = FakeWorkflowRepository()
    private val sessions = FakeSessionRepository().also { it.profileList = listOf(Profile("p1", "support", "claude")) }
    // No live capability: the screens load once, which is what these tests need.
    private val features = FakeFeatures(setOf("workflows.v1", "cloud.sessions.v1", "waits.v1"))
    private val accounts = InMemoryAccountRepository(listOf(account()))
    private val guard = AccountGuard(RefreshAccountUseCase(FakeAuthRepository(), accounts, credentials(), FixedClock()))
    private val access = WorkflowAccess(repository, features, accounts, guard)
    private val openWaits = mutableListOf<Wait>()
    private val waits = object : WaitRepository {
        override suspend fun list(accountId: String, status: WaitStatus, before: String?) = WaitPage(openWaits.toList(), null)
        override suspend fun answer(accountId: String, waitId: String, action: String, values: Map<String, kotlinx.serialization.json.JsonElement>) = error("unused")
    }
    private val feedScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob())

    @BeforeTest fun startKoin() { org.koin.core.context.startKoin { modules(org.koin.dsl.module { single<me.nathanfallet.teamcode.companion.domain.services.Clock> { FixedClock(1_000_000L) } }) } }
    @AfterTest fun stopKoin() { feedScope.coroutineContext[kotlinx.coroutines.Job]?.cancel(); org.koin.core.context.stopKoin() }

    private val follow get() = FollowWorkflowRunsUseCase(SessionFeed(sessions, features, feedScope), features, guard)
    private fun runs() = WorkflowRunsViewModel(WorkflowsRoute("a1"), LoadWorkflowRunsUseCase(access), GetWorkflowRunUseCase(access), LoadWorkflowsUseCase(access), LoadOpenWaitsUseCase(waits, features, guard), follow, accounts)
    private fun definitions() = WorkflowsViewModel(WorkflowsRoute("a1"), LoadWorkflowsUseCase(access))
    private fun page(id: String) = WorkflowRunViewModel(WorkflowRunRoute("a1", id), GetWorkflowRunUseCase(access), CancelWorkflowRunUseCase(access), LoadOpenWaitsUseCase(waits, features, guard), follow, accounts)
    private fun startForm() = WorkflowStartViewModel(WorkflowStartRoute("a1", "wf1"), GetWorkflowUseCase(access), LoadProfilesUseCase(sessions, features, guard), StartWorkflowRunUseCase(access), SequenceIds())

    private fun ComposeUiTest.assertTagged(tag: String, text: String) {
        onNode(hasTestTag(tag) and (hasText(text) or hasAnyDescendant(hasText(text))), useUnmergedTree = true).assertExists()
    }

    @Test fun theRunsShowStatusStepKindTimeAndTheFailureInWords() = runComposeUiTest {
        repository.runs["r1"] = workflowRun("r1", RunStatus.RUNNING, createdAt = 400_000, current = "work")
        repository.runs["r2"] = workflowRun("r2", RunStatus.FAILED, createdAt = 300_000, failure = "max_visits:review").let { it.copy(finishedAt = 360_000) }
        repository.runs["r3"] = workflowRun("r3", RunStatus.SUCCEEDED, createdAt = 200_000).let { it.copy(finishedAt = 260_000) }
        val runs = runs(); val definitions = definitions()
        setContent { TeamCodeTheme { WorkflowsScreen(runs, definitions, onOpenRun = {}, onOpenWorkflow = {}, onStartWorkflow = {}, onOpenWaits = {}) } }
        waitUntil { runs.uiState.value.runs != null && definitions.uiState.value.workflows != null }
        assertTagged("workflow-run-status-r1", "Running")
        // The title comes from the saved definition, read beside the page: wait for it.
        waitUntil { runs.uiState.value.definitions.isNotEmpty() }
        assertTagged("workflow-run-step-r1", "Step work · Agent")
        assertTagged("workflow-run-status-r2", "Failed")
        assertTagged("workflow-run-failure-r2", "The step \"review\" came back too many times.")
        assertTagged("workflow-run-status-r3", "Done")
        onNodeWithTag("workflow-run-failure-r1", useUnmergedTree = true).assertDoesNotExist()
        onNodeWithTag("workflows-scope-team").assertDoesNotExist()
    }

    @Test fun aRunBlockedOnTheMembersWaitSaysSoAndOpensTheWaits() = runComposeUiTest {
        repository.runs["r1"] = workflowRun("r1", RunStatus.WAITING, session = "s1", current = "review")
        openWaits += Wait("w1", WaitSource.WAIT, WaitKind.HUMAN_REQUEST, true, "s1", null, null, "m1", "Review", null, emptyList(), emptyList(), emptyList(), 1)
        var opened = false
        val runs = runs(); val definitions = definitions()
        setContent { TeamCodeTheme { WorkflowsScreen(runs, definitions, onOpenRun = {}, onOpenWorkflow = {}, onStartWorkflow = {}, onOpenWaits = { opened = true }) } }
        waitUntil { runs.uiState.value.runs != null }
        waitUntil { runs.uiState.value.waitsForMe(runs.uiState.value.runs!!.first()) }
        assertTagged("workflow-run-mine-r1", "Waiting for you")
        onNodeWithTag("workflow-run-open-wait-r1", useUnmergedTree = true).performClick()
        assertTrue(opened)
    }

    @Test fun theRunPageShowsTheTimelineTheSessionAndCancelsAfterAConfirmation() = runComposeUiTest {
        repository.runs["r1"] = workflowRun("r1", RunStatus.WAITING, visits = listOf(
            visit(1, "work", VisitStatus.DONE, session = "s1").copy(outcome = "delivered", output = "Fixed in #12"),
            visit(2, "review", kind = StepKind.HUMAN, wait = "w1"),
        ))
        var session: String? = null
        val model = page("r1")
        setContent { TeamCodeTheme { WorkflowRunScreen(model, onBack = null, onOpenSession = { session = it }, onOpenRun = {}, onOpenWaits = {}) } }
        waitUntil { model.uiState.value.run != null }
        assertTagged("workflow-run-status", "Waiting")
        assertTagged("workflow-visit-outcome-1", "Outcome: delivered")
        assertTagged("workflow-visit-output-1", "Fixed in #12")
        assertTagged("workflow-visit-status-2", "In progress")
        onNodeWithTag("workflow-visit-session-1", useUnmergedTree = true).performScrollTo().performClick()
        assertEquals("s1", session)
        onNodeWithTag("workflow-run-cancel").performClick()
        onNodeWithTag("workflow-cancel-confirm").assertExists()
        assertFalse("cancel:r1" in repository.calls, "nothing is cancelled before the confirmation")
    }

    @Test fun aFinishedRunHasNoCancelAndAFailedOneSaysWhy() = runComposeUiTest {
        repository.runs["r1"] = workflowRun("r1", RunStatus.FAILED, failure = "session_failed").copy(finishedAt = 500)
        val model = page("r1")
        setContent { TeamCodeTheme { WorkflowRunScreen(model, onBack = null, onOpenSession = {}, onOpenRun = {}, onOpenWaits = {}) } }
        waitUntil { model.uiState.value.run != null }
        onNodeWithTag("workflow-run-cancel").assertDoesNotExist()
        assertTagged("workflow-run-failure", "The session of a step failed.")
    }

    @Test fun theDefinitionsListStartsAWorkflowFromTheFormWithItsInputs() = runComposeUiTest {
        var started: WorkflowRun? = null
        val model = startForm()
        setContent { TeamCodeTheme { WorkflowStartScreen(model, onBack = null, onStarted = { started = it }) } }
        waitUntil { model.uiState.value.workflow != null }
        onNodeWithTag("workflow-input-ticket").performScrollTo().performTextInput("T-9")
        waitUntil { model.uiState.value.canStart }
        onNodeWithTag("workflow-start-submit").performScrollTo().performClick()
        waitUntil { started != null }
        val (id, sent, _) = repository.started.single()
        assertEquals("wf1", id); assertEquals("T-9", sent.inputs["ticket"]); assertEquals("small", sent.inputs["notes"]); assertEquals("p1", sent.profileId)
    }

    @Test fun theDefinitionsListShowsEachWorkflowWithAStartButtonAndTheEditNote() = runComposeUiTest {
        val runs = runs(); val definitions = definitions()
        var started: Workflow? = null
        setContent { TeamCodeTheme { WorkflowsScreen(runs, definitions, onOpenRun = {}, onOpenWorkflow = {}, onStartWorkflow = { started = it }, onOpenWaits = {}) } }
        waitUntil { definitions.uiState.value.workflows != null }
        onNodeWithTag("workflows-part-definitions").performClick()
        onNodeWithTag("workflows-edit-note").assertExists()
        assertTagged("workflow-wf1", "Fix a ticket")
        onNodeWithTag("workflow-start-wf1", useUnmergedTree = true).performClick()
        assertEquals("wf1", started?.id)
    }
}
