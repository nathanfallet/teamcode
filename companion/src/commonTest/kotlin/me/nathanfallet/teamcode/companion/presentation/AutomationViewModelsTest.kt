package me.nathanfallet.teamcode.companion.presentation

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import me.nathanfallet.teamcode.companion.*
import me.nathanfallet.teamcode.companion.domain.models.*
import me.nathanfallet.teamcode.companion.domain.usecases.auth.AccountGuard
import me.nathanfallet.teamcode.companion.domain.usecases.auth.RefreshAccountUseCase
import me.nathanfallet.teamcode.companion.domain.usecases.automations.*
import me.nathanfallet.teamcode.companion.domain.usecases.sessions.LoadProfilesUseCase
import me.nathanfallet.teamcode.companion.domain.usecases.workflows.GetWorkflowUseCase
import me.nathanfallet.teamcode.companion.domain.usecases.workflows.LoadWorkflowsUseCase
import me.nathanfallet.teamcode.companion.domain.usecases.workflows.WorkflowAccess
import me.nathanfallet.teamcode.companion.presentation.navigation.*
import me.nathanfallet.teamcode.companion.presentation.viewmodels.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class AutomationViewModelsTest {
    private val dispatcher = StandardTestDispatcher()
    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)
    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private val repository = FakeAutomationRepository()
    private val sessions = FakeSessionRepository()
    private val features = FakeFeatures(setOf("automations.v1", "cloud.sessions.v1", "workflows.v1"))
    private val secrets = AutomationSecrets()
    private val accounts = InMemoryAccountRepository(listOf(account()))
    private var role: String
        get() = accounts.accounts.value.single().role
        set(value) = accounts.save(account().copy(role = value))
    private val guard = AccountGuard(RefreshAccountUseCase(FakeAuthRepository(), accounts, credentials(), FixedClock()))
    private val access = AutomationAccess(repository, features, accounts, guard)
    private val workflows = FakeWorkflowRepository()
    private val workflowAccess = WorkflowAccess(workflows, features, accounts, guard)
    private val profiles = LoadProfilesUseCase(sessions, features, AccountGuard(RefreshAccountUseCase(FakeAuthRepository(), accounts, credentials(), FixedClock())))

    private fun TestScope.settle() = runCurrent()
    private fun list() = AutomationsViewModel(AutomationsRoute("a1"), LoadAutomationsUseCase(access), SetAutomationEnabledUseCase(access), DeleteAutomationUseCase(access), accounts)
    private fun page() = AutomationViewModel(AutomationRoute("a1", "au1"), GetAutomationUseCase(access), SetAutomationEnabledUseCase(access), DeleteAutomationUseCase(access),
        RotateAutomationSecretUseCase(access), LoadAutomationRunsUseCase(access), CancelAutomationRunUseCase(access), secrets, profiles, GetWorkflowUseCase(workflowAccess), accounts)
    private fun form(id: String? = null) = AutomationEditViewModel(AutomationEditRoute("a1", id), GetAutomationUseCase(access), profiles, LoadWorkflowsUseCase(workflowAccess), SaveAutomationUseCase(access), PreviewAutomationUseCase(access), secrets, accounts)
    private fun test() = AutomationTestViewModel(AutomationTestRoute("a1", "au1"), GetAutomationUseCase(access), PreviewAutomationUseCase(access), TestAutomationUseCase(access), SequenceIds(), accounts)

    @Test fun theListLoadsAndShowsTheEmptyAndErrorStates() = runTest(dispatcher) {
        val model = list(); assertTrue(model.uiState.value.loading); settle()
        assertEquals(2, model.uiState.value.automations?.size)
        repository.summaries = emptyList(); model.reload(); settle()
        assertTrue(model.uiState.value.empty)
        repository.failure = CompanionException.Unreachable(); model.reload(); settle()
        assertNotNull(model.uiState.value.error)
        repository.failure = null; model.reload(); settle()
        assertNull(model.uiState.value.error)
    }

    @Test fun aServerWithoutAutomationsLeavesTheListUnset() = runTest(dispatcher) {
        features.features = emptySet()
        val model = list(); settle()
        assertNull(model.uiState.value.automations)
        assertNotNull(model.uiState.value.error)
    }

    @Test fun theTeamFilterIsAnAdministratorsOnly() = runTest(dispatcher) {
        val member = list(); settle()
        member.setScope(AutomationScope.TEAM); settle()
        assertEquals(AutomationScope.MINE, member.uiState.value.scope)
        role = "ADMIN"
        val admin = list(); settle()
        admin.setScope(AutomationScope.TEAM); settle()
        assertEquals(AutomationScope.TEAM, admin.uiState.value.scope)
        assertTrue("list:TEAM:null" in repository.calls)
    }

    @Test fun theSwitchArmsAndPausesFromTheList() = runTest(dispatcher) {
        val model = list(); settle()
        val paused = model.uiState.value.automations!![1]
        repository.current = automation(enabled = false)
        model.toggle(paused); settle()
        assertTrue(model.uiState.value.automations!![1].enabled)
        assertTrue("enable:au2" in repository.calls)
        model.toggle(model.uiState.value.automations!![0]); settle()
        assertFalse(model.uiState.value.automations!![0].enabled)
    }

    @Test fun anAdministratorPausesAMembersAutomationButDoesNotArmIt() = runTest(dispatcher) {
        role = "ADMIN"
        repository.summaries = listOf(summary("x1", ownerId = "m2", enabled = true), summary("x2", ownerId = "m2", enabled = false))
        val model = list(); settle()
        val (active, paused) = model.uiState.value.automations!!
        assertTrue(model.uiState.value.canToggle(active))
        assertFalse(model.uiState.value.canToggle(paused))
        model.toggle(paused); settle()
        assertNotNull(model.uiState.value.actionError)
        assertTrue(repository.calls.none { it.startsWith("enable") })
    }

    @Test fun removingFromTheListDropsTheRow() = runTest(dispatcher) {
        val model = list(); settle()
        model.remove(model.uiState.value.automations!![0]); settle()
        assertEquals(listOf("au2"), model.uiState.value.automations!!.map { it.id })
    }

    @Test fun thePageLoadsTheAutomationItsHistoryAndTheProfileName() = runTest(dispatcher) {
        sessions.profileList = listOf(Profile("p1", "support", "claude"))
        val model = page(); settle()
        val state = model.uiState.value
        assertEquals("au1", state.automation?.id)
        assertEquals(2, state.runs.size)
        assertEquals("support", state.profileName)
        assertTrue(state.canTest && state.canEdit && state.canEnable && !state.canPause)
    }

    @Test fun hidingTestsReloadsTheHistoryWithoutThem() = runTest(dispatcher) {
        val model = page(); settle()
        model.setHideTests(true); settle()
        assertEquals(listOf("r1"), model.uiState.value.runs.map { it.id })
    }

    @Test fun thePageOfAMembersAutomationIsReadOnlyForAnAdministrator() = runTest(dispatcher) {
        role = "ADMIN"; repository.current = automation(ownerId = "m2", enabled = true, definition = webhookDefinition())
        val state = page().also { settle() }.uiState.value
        assertTrue(state.readOnly)
        assertTrue(state.canPause && state.canDelete)
        assertFalse(state.canTest || state.canEdit || state.canEnable || state.canRotate)
    }

    @Test fun rotatingKeepsTheSecretInMemoryUntilItIsStored() = runTest(dispatcher) {
        repository.current = automation(definition = webhookDefinition())
        val model = page(); settle()
        model.rotateSecret(); settle()
        assertEquals("whsec_new", model.uiState.value.secret)
        assertEquals("/api/v1/automations/au1/trigger/whsec_new".let { "https://team.example$it" }, model.also { it.setProof(WebhookProof.SECRET_URL) }.uiState.value.link?.url)
        // Reopening the page still has it; once stored, it is gone.
        assertEquals("whsec_new", page().also { settle() }.uiState.value.secret)
        model.secretStored()
        assertNull(model.uiState.value.secret)
        assertNull(page().also { settle() }.uiState.value.secret)
    }

    @Test fun cancellingARunTakesTheServersAnswer() = runTest(dispatcher) {
        repository.runs = listOf(run("r2", RunSource.CRON, "queued", null))
        val model = page(); settle()
        model.cancelRun(model.uiState.value.runs.single()); settle()
        assertEquals("cancelled", model.uiState.value.runs.single().status)
    }

    @Test fun deletingMarksThePageDeleted() = runTest(dispatcher) {
        val model = page(); settle()
        model.remove(); settle()
        assertTrue(model.uiState.value.deleted)
    }

    @Test fun theFormCreatesAndKeepsTheSecretForThePage() = runTest(dispatcher) {
        sessions.profileList = listOf(Profile("p1", "support", "claude"))
        val model = form(); settle()
        assertEquals("p1", model.uiState.value.form.profileId)
        model.edit { it.copy(name = "Nightly", prompt = "Do it") }
        model.save(); settle()
        assertEquals("au9", model.uiState.value.savedId)
        assertEquals("whsec_new", secrets.secret("au9"))
        assertEquals("Nightly", repository.saved.single().name)
    }

    @Test fun aWorkflowActionIsChosenFilledAndSavedWithItsInputs() = runTest(dispatcher) {
        sessions.profileList = listOf(Profile("p1", "support", "claude"))
        val model = form(); settle()
        assertEquals(listOf("wf1"), model.uiState.value.workflows.map { it.id })
        model.edit { it.copy(name = "Triage", kind = TriggerKind.WEBHOOK, action = ActionKind.WORKFLOW) }
        model.save(); settle()
        assertNotNull(model.uiState.value.error, "a workflow must be chosen")
        model.chooseWorkflow(model.uiState.value.workflows.single())
        model.setInput("ticket", "T-{{ticket.number}}")
        model.save(); settle()
        val saved = repository.saved.single()
        assertEquals(ActionKind.WORKFLOW, saved.action.kind); assertEquals("wf1", saved.action.workflowId); assertEquals(mapOf("ticket" to "T-{{ticket.number}}"), saved.action.inputs)
        assertEquals("", saved.prompt)
    }

    @Test fun anotherWorkflowStartsFromEmptyInputsAndTheEditKeepsTheSavedOnes() = runTest(dispatcher) {
        workflows.definitions = listOf(workflow("wf1"), workflow("wf2"))
        repository.current = automation(definition = workflowAutomationDefinition())
        val model = form("au1"); settle()
        assertEquals(ActionKind.WORKFLOW, model.uiState.value.form.action); assertEquals(mapOf("ticket" to "T-{{ticket.number}}"), model.uiState.value.form.inputs)
        model.chooseWorkflow(workflow("wf1")); assertEquals(1, model.uiState.value.form.inputs.size, "the same workflow keeps its inputs")
        model.chooseWorkflow(workflow("wf2")); assertTrue(model.uiState.value.form.inputs.isEmpty())
    }

    @Test fun aServerWithoutWorkflowsStillEditsCloudActions() = runTest(dispatcher) {
        features.features = setOf("automations.v1", "cloud.sessions.v1")
        val model = form(); settle()
        assertTrue(model.uiState.value.workflows.isEmpty()); assertNull(model.uiState.value.loadError)
    }

    @Test fun thePageNamesTheWorkflowAndLinksTheRunsToTheirWorkflowRuns() = runTest(dispatcher) {
        repository.current = automation(definition = workflowAutomationDefinition())
        repository.runs = listOf(run("r1", RunSource.WEBHOOK, "running", sessionId = null, workflowRunId = "wr1"))
        val state = page().also { settle() }.uiState.value
        assertEquals("Fix a ticket", state.workflowName)
        assertEquals("wr1", state.runs.single().workflowRunId)
    }

    @Test fun aRefusedFormShowsTheProblemAndSendsNothing() = runTest(dispatcher) {
        val model = form(); settle()
        model.save(); settle()
        assertNotNull(model.uiState.value.error)
        assertNull(model.uiState.value.savedId)
        assertTrue(repository.calls.none { it == "create" })
    }

    @Test fun editingLoadsTheDefinitionAndSavesAgainstItsVersion() = runTest(dispatcher) {
        repository.current = automation(version = 7, definition = webhookDefinition())
        val model = form("au1"); settle()
        assertEquals(TriggerKind.WEBHOOK, model.uiState.value.form.kind)
        assertEquals(1, model.uiState.value.form.variables.size)
        model.save(); settle()
        assertTrue("update:au1:7" in repository.calls)
    }

    @Test fun anotherMembersAutomationIsNotEditable() = runTest(dispatcher) {
        repository.current = automation(ownerId = "m2")
        val model = form("au1"); settle()
        assertTrue(model.uiState.value.notOwner)
        model.save(); settle()
        assertTrue(repository.calls.none { it.startsWith("update") })
    }

    @Test fun variablesCanBeAddedEditedAndRemoved() = runTest(dispatcher) {
        val model = form(); settle()
        model.addVariable()
        model.setVariable(0, AutomationVariable("a", "$.a"))
        assertEquals("a", model.uiState.value.form.variables.single().name)
        model.removeVariable(0)
        assertTrue(model.uiState.value.form.variables.isEmpty())
    }

    @Test fun theDraftPreviewRendersThePrompt() = runTest(dispatcher) {
        sessions.profileList = listOf(Profile("p1", "support", "claude"))
        val model = form(); settle()
        model.edit { it.copy(name = "N", prompt = "P") }
        model.preview(); settle()
        assertEquals("Fix ticket 735.", model.uiState.value.preview?.renderedPrompt)
    }

    @Test fun theTestIsPrefilledWithTheSampleAndPreviewsWithoutRunning() = runTest(dispatcher) {
        repository.current = automation(definition = webhookDefinition())
        val model = test(); settle()
        assertTrue(model.uiState.value.payload.contains("735"))
        model.preview(); settle()
        assertNotNull(model.uiState.value.preview)
        assertTrue(repository.tested.isEmpty())
    }

    @Test fun aBadPayloadIsRefusedAndAGoodOneRunsWithOneKeyUntilAccepted() = runTest(dispatcher) {
        repository.current = automation(definition = webhookDefinition())
        val model = test(); settle()
        model.setPayload("nope"); model.run(); settle()
        assertNotNull(model.uiState.value.runError)
        assertTrue(repository.tested.isEmpty())
        model.setPayload("""{"ticket":{"number":1}}""")
        repository.failure = CompanionException.Unreachable(); model.run(); settle()
        repository.failure = null; model.run(); settle()
        model.run(); settle()
        val keys = repository.tested.map { it.third }
        assertEquals(2, keys.size)
        // The retry after a failure did not draw a new key; the next run after the accepted one did.
        assertNotEquals(keys[0], keys[1])
        assertEquals("queued", model.uiState.value.run?.status)
    }

    @Test fun anAdministratorCannotRunARealTestOfAMembersAutomation() = runTest(dispatcher) {
        role = "ADMIN"; repository.current = automation(ownerId = "m2")
        val model = test(); settle()
        assertFalse(model.uiState.value.canRun)
        model.run(); settle()
        assertTrue(repository.tested.isEmpty())
        model.preview(); settle()
        assertNotNull(model.uiState.value.preview)
    }

    @Test fun theRunStatusIsReadAgainOnDemand() = runTest(dispatcher) {
        val model = test(); settle()
        model.run(); settle()
        model.refreshRun(); settle()
        assertEquals("succeeded", model.uiState.value.run?.status)
    }

    @Test fun anEditKeepsTheCatchupGraceTheFormDoesNotShow() = runTest(dispatcher) {
        repository.current = automation(definition = cronDefinition())
        val model = form("au1"); settle()
        model.save(); settle()
        assertEquals(360, repository.saved.single().trigger.catchupGraceMinutes)
    }

    @Test fun aTriggerKindFromANewerServerIsNotEditable() = runTest(dispatcher) {
        repository.current = automation(definition = cronDefinition().copy(trigger = AutomationTrigger(TriggerKind.UNKNOWN, rawKind = "mqtt")))
        val model = form("au1"); settle()
        assertNotNull(model.uiState.value.loadError)
        model.save(); settle()
        assertTrue(repository.calls.none { it.startsWith("update") })
    }

    @Test fun anotherPayloadIsAnotherRequestWithItsOwnKey() = runTest(dispatcher) {
        repository.current = automation(definition = webhookDefinition())
        val model = test(); settle()
        repository.failure = CompanionException.Unreachable(); model.run(); settle()
        repository.failure = null
        model.run(); settle()
        model.setPayload("""{"ticket":{"number":2}}""")
        model.run(); settle()
        val keys = repository.tested.map { it.third }
        assertEquals(2, keys.size)
        assertNotEquals(keys[0], keys[1])
    }
}
