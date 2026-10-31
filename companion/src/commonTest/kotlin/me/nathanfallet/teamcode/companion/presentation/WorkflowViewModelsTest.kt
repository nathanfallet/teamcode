package me.nathanfallet.teamcode.companion.presentation

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import me.nathanfallet.teamcode.companion.*
import me.nathanfallet.teamcode.companion.domain.models.*
import me.nathanfallet.teamcode.companion.domain.repositories.WaitRepository
import me.nathanfallet.teamcode.companion.domain.usecases.auth.AccountGuard
import me.nathanfallet.teamcode.companion.domain.usecases.auth.RefreshAccountUseCase
import me.nathanfallet.teamcode.companion.domain.usecases.sessions.LoadProfilesUseCase
import me.nathanfallet.teamcode.companion.domain.usecases.sessions.SessionFeed
import me.nathanfallet.teamcode.companion.domain.usecases.workflows.*
import me.nathanfallet.teamcode.companion.presentation.navigation.*
import me.nathanfallet.teamcode.companion.presentation.viewmodels.*
import kotlin.test.*

/** The workflow screens' view models: runs kept live by the feed, the run page, the saved workflows and the start form. */
@OptIn(ExperimentalCoroutinesApi::class)
class WorkflowViewModelsTest {
    private val dispatcher = StandardTestDispatcher()
    private val feed = Channel<LiveUpdate<SessionChange>>(Channel.UNLIMITED)
    private val feedScope = kotlinx.coroutines.CoroutineScope(dispatcher + kotlinx.coroutines.SupervisorJob())
    private val repository = FakeWorkflowRepository()
    private val sessions = FakeSessionRepository()
    private val features = FakeFeatures().also { it.features += setOf("workflows.v1", "workflows.live.v1", "waits.v1") }
    private val accounts = InMemoryAccountRepository(listOf(account("a1")))
    private val openWaits = mutableListOf<Wait>()
    private val waits = object : WaitRepository {
        val calls = mutableListOf<String>()
        override suspend fun list(accountId: String, status: WaitStatus, before: String?): WaitPage { calls += "list"; return WaitPage(openWaits.toList(), null) }
        override suspend fun answer(accountId: String, waitId: String, action: String, values: Map<String, kotlinx.serialization.json.JsonElement>) = error("unused")
    }
    private val guard = AccountGuard(RefreshAccountUseCase(FakeAuthRepository(), accounts, credentials(), FixedClock()))
    private val access = WorkflowAccess(repository, features, accounts, guard)
    private val follow get() = FollowWorkflowRunsUseCase(SessionFeed(sessions, features, feedScope), features, guard)

    @BeforeTest fun setUp() { Dispatchers.setMain(dispatcher); sessions.changes = feed.receiveAsFlow() }
    @AfterTest fun tearDown() { feedScope.cancel(); Dispatchers.resetMain() }

    private fun runs() = WorkflowRunsViewModel(WorkflowsRoute("a1"), LoadWorkflowRunsUseCase(access), GetWorkflowRunUseCase(access), LoadWorkflowsUseCase(access),
        LoadOpenWaitsUseCase(waits, features, guard), follow, accounts)
    private fun run(id: String = "r1") = WorkflowRunViewModel(WorkflowRunRoute("a1", id), GetWorkflowRunUseCase(access), CancelWorkflowRunUseCase(access), LoadOpenWaitsUseCase(waits, features, guard), follow, accounts)
    private val drafts = InMemoryWorkflowDrafts()
    private fun detail(id: String = "wf1", changes: WorkflowChanges = WorkflowChanges()) = WorkflowViewModel(WorkflowRoute("a1", id), GetWorkflowUseCase(access), LoadWorkflowSettingsUseCase(access), DeleteWorkflowUseCase(access, changes), LoadWorkflowCatalogUseCase(access), drafts, changes)
    private fun start() = WorkflowStartViewModel(WorkflowStartRoute("a1", "wf1"), GetWorkflowUseCase(access), LoadProfilesUseCase(sessions, features, guard), StartWorkflowRunUseCase(access), SequenceIds())

    private fun event(id: String, status: RunStatus, owner: String = "m1", parent: String? = null, seq: Long = 1) =
        LiveUpdate.Item(SessionChange(seq, SessionChange.WORKFLOW_RUN, "", owner, seq, null, null, runId = id, runStatus = status, runWorkflowId = "wf1", runParentId = parent))

    private suspend fun TestScope.opened(model: WorkflowRunsViewModel) { model.start(); settle(); feed.send(LiveUpdate.Opened(false)); settle() }

    @Test fun theRunsLoadWhenTheFeedOpensNewestFirstWithTheStepOfEachRow() = runTest(dispatcher) {
        repository.runs["r1"] = workflowRun("r1", createdAt = 100, current = "work")
        repository.runs["r2"] = workflowRun("r2", RunStatus.SUCCEEDED, createdAt = 200)
        val model = runs()
        model.start(); settle()
        // Nothing is read before the feed is open, so nothing is missed between the load and the stream.
        assertEquals(emptyList(), repository.calls)
        feed.send(LiveUpdate.Opened(false)); settle()
        val state = model.uiState.value
        assertEquals(listOf("r2", "r1"), state.runs!!.map { it.id })
        assertEquals("runs:MINE:ALL:null", repository.calls.first())
        // The title comes from the saved definition; the kind from the server; a finished run has no step.
        assertEquals(RunStep("Step work", StepKind.AGENT), state.stepOf(state.runs!!.last()))
        assertNull(state.stepOf(state.runs!!.first()))
        model.stop()
    }

    @Test fun anEventReadsTheOneRunItNamesAndNeverThePageAgain() = runTest(dispatcher) {
        repository.runs["r1"] = workflowRun("r1", createdAt = 100, current = "work")
        val model = runs(); opened(model)
        val pages = repository.calls.count { it.startsWith("runs:") }

        // A run started elsewhere appears at the top; a known run takes its new status.
        repository.runs["r2"] = workflowRun("r2", createdAt = 200)
        feed.send(event("r2", RunStatus.RUNNING)); settle()
        assertEquals(listOf("r2", "r1"), model.uiState.value.runs!!.map { it.id })
        repository.runs["r1"] = workflowRun("r1", RunStatus.FAILED, createdAt = 100, failure = "session_failed")
        feed.send(event("r1", RunStatus.FAILED, seq = 2)); settle()
        assertEquals(RunStatus.FAILED, model.uiState.value.runs!!.last().status)
        assertEquals("session_failed", model.uiState.value.runs!!.last().failure)
        assertEquals(pages, repository.calls.count { it.startsWith("runs:") }, "no page was read again")
        assertTrue("run:r2" in repository.calls && "run:r1" in repository.calls)

        // Another member's run is not this list's, and a run that left the filter leaves the list.
        feed.send(event("r9", RunStatus.RUNNING, owner = "m2", seq = 3)); settle()
        assertFalse("run:r9" in repository.calls)
        model.setFilter(RunFilter.RUNNING); settle()
        assertEquals(listOf("r2"), model.uiState.value.runs!!.map { it.id })
        repository.runs["r2"] = workflowRun("r2", RunStatus.SUCCEEDED, createdAt = 200)
        feed.send(event("r2", RunStatus.SUCCEEDED, seq = 4)); settle()
        assertEquals(emptyList(), model.uiState.value.runs)
        model.stop()
    }

    @Test fun aGapOrAConnectionThatCouldNotResumeLoadsAgain() = runTest(dispatcher) {
        repository.runs["r1"] = workflowRun("r1")
        val model = runs(); opened(model)
        val before = repository.calls.count { it.startsWith("runs:") }
        feed.send(LiveUpdate.Gap); settle()
        assertEquals(before + 1, repository.calls.count { it.startsWith("runs:") })
        feed.send(LiveUpdate.Reconnecting(1000)); settle()
        assertTrue(model.uiState.value.reconnecting)
        feed.send(LiveUpdate.Opened(true)); settle()
        assertFalse(model.uiState.value.reconnecting)
        assertEquals(before + 1, repository.calls.count { it.startsWith("runs:") }, "a resumed connection misses nothing")
        model.stop()
    }

    @Test fun withoutTheFeedTheRunsLoadOnceAndAreNeverPolled() = runTest(dispatcher) {
        features.features = setOf("workflows.v1")
        repository.runs["r1"] = workflowRun("r1")
        val model = runs(); model.start(); settle()
        assertEquals(1, repository.calls.count { it.startsWith("runs:") })
        advanceTimeBy(120_000); runCurrent()
        assertEquals(1, repository.calls.count { it.startsWith("runs:") })
        model.stop()
    }

    @Test fun aWaitingRunIsMarkedWhenItsWaitIsTheMembers() = runTest(dispatcher) {
        repository.runs["r1"] = workflowRun("r1", RunStatus.WAITING, session = "s1")
        repository.runs["r2"] = workflowRun("r2", RunStatus.WAITING, createdAt = 50, session = "s2")
        openWaits += Wait("w1", WaitSource.WAIT, WaitKind.HUMAN_REQUEST, true, "s1", null, null, "m1", "Review", null, emptyList(), emptyList(), emptyList(), 1)
        val model = runs(); opened(model)
        val state = model.uiState.value
        assertTrue(state.waitsForMe(state.runs!!.first { it.id == "r1" }))
        assertFalse(state.waitsForMe(state.runs!!.first { it.id == "r2" }))
        // A single read names the wait itself.
        val detailed = workflowRun("r2", RunStatus.WAITING, visits = listOf(visit(1, "review", wait = "w1", kind = StepKind.HUMAN)))
        assertTrue(state.waitsForMe(detailed))
        // The wait is answered: its event reloads the open waits, the mark goes.
        openWaits.clear(); feed.send(LiveUpdate.Item(SessionChange(5, SessionChange.WAIT_RESOLVED, "s1", "m1", 5, null, null, waitId = "w1"))); settle()
        assertFalse(model.uiState.value.waitsForMe(model.uiState.value.runs!!.first { it.id == "r1" }))
        model.stop()
    }

    @Test fun onlyAnAdministratorSwitchesToTheTeamsRuns() = runTest(dispatcher) {
        val member = runs(); member.setScope(RunScope.TEAM); settle()
        assertEquals(RunScope.MINE, member.uiState.value.scope)
        accounts.save(account().copy(role = "ADMIN"))
        val admin = runs(); admin.setScope(RunScope.TEAM); settle()
        assertEquals(RunScope.TEAM, admin.uiState.value.scope)
        assertTrue("runs:TEAM:ALL:null" in repository.calls)
    }

    @Test fun theRunPageFollowsItsOwnAndItsChildrensChangesOnly() = runTest(dispatcher) {
        repository.runs["r1"] = workflowRun("r1", RunStatus.WAITING, visits = listOf(visit(1, "call", kind = StepKind.WORKFLOW, child = "c1")))
        val model = run(); settle()
        assertEquals("r1", model.uiState.value.run?.id)
        model.start(); settle(); feed.send(LiveUpdate.Opened(false)); settle()
        val reads = repository.calls.count { it == "run:r1" }
        feed.send(event("other", RunStatus.RUNNING)); settle()
        assertEquals(reads, repository.calls.count { it == "run:r1" })
        feed.send(event("c1", RunStatus.RUNNING, parent = "r1", seq = 2)); settle()
        assertEquals(reads + 1, repository.calls.count { it == "run:r1" })
        repository.runs["r1"] = workflowRun("r1", RunStatus.FAILED, failure = "expired")
        feed.send(event("r1", RunStatus.FAILED, seq = 3)); settle()
        assertEquals("expired", model.uiState.value.run?.failure)
        model.stop()
    }

    @Test fun aMissingRunIsSaidNotRetried() = runTest(dispatcher) {
        val model = run("nope"); settle()
        assertTrue(model.uiState.value.missing)
        assertNull(model.uiState.value.error)
    }

    @Test fun cancellingKeepsTheTimelineAndTakesTheStatusOfTheStartersRunOnly() = runTest(dispatcher) {
        repository.runs["r1"] = workflowRun("r1", RunStatus.WAITING, visits = listOf(visit(1, "review", kind = StepKind.HUMAN, wait = "w1")))
        repository.runs["r2"] = workflowRun("r2", RunStatus.WAITING, starter = "m2")
        val mine = run("r1"); settle()
        assertTrue(mine.uiState.value.canCancel)
        mine.cancel(); settle()
        assertEquals(RunStatus.CANCELLED, mine.uiState.value.run?.status)
        assertFalse(mine.uiState.value.canCancel)
        val other = run("r2"); settle()
        assertFalse(other.uiState.value.canCancel)
        other.cancel(); settle()
        assertFalse("cancel:r2" in repository.calls)
        accounts.save(account().copy(role = "ADMIN"))
        val admin = run("r2"); settle()
        assertTrue(admin.uiState.value.canCancel)
    }

    @Test fun theStartFormShowsDefaultsKeepsItsKeyWhileUnchangedAndLandsOnTheRun() = runTest(dispatcher) {
        repository.definitions = listOf(workflow())
        sessions.profileList = listOf(Profile("p1", "support", "claude"), Profile("p2", "review", "codex"))
        val model = start(); settle()
        val state = model.uiState.value
        assertEquals("small", state.valueOf("notes")); assertTrue(state.needsProfile)
        assertFalse(state.canStart, "a profile is needed")
        model.onProfile("p2"); model.onInput("ticket", "T-7"); settle()
        model.start(); settle()
        val (id, sent, key) = repository.started.single()
        assertEquals("wf1", id); assertEquals(RunStart(mapOf("ticket" to "T-7", "notes" to "small"), "p2"), sent)
        assertEquals("new", model.uiState.value.started?.id)
        assertTrue(key.isNotBlank())
    }

    @Test fun aRefusedStartKeepsTheFormAndTheNextEditDrawsANewKey() = runTest(dispatcher) {
        sessions.profileList = listOf(Profile("p1", "support", "claude"))
        val model = start(); settle()
        assertEquals("p1", model.uiState.value.profileId, "a single profile is chosen")
        model.start(); settle()
        assertNotNull(model.uiState.value.startError, "the required ticket is missing")
        assertEquals(emptyList(), repository.started)
        model.onInput("ticket", "T-1")
        repository.failure = CompanionException.Server(409, "run_limit")
        model.start(); settle()
        assertNotNull(model.uiState.value.startError)
        assertNull(model.uiState.value.started)
        repository.failure = null
        model.start(); settle()
        model.onInput("ticket", "T-2")
        assertNotNull(model.uiState.value.started)
    }

    @Test fun theSavedWorkflowsPageAndFailGently() = runTest(dispatcher) {
        val model = WorkflowsViewModel(WorkflowsRoute("a1"), LoadWorkflowsUseCase(access), LoadWorkflowSettingsUseCase(access), WorkflowChanges()); settle()
        assertEquals(listOf("wf1"), model.uiState.value.workflows!!.map { it.id })
        repository.definitions = emptyList(); model.reload(); settle()
        assertTrue(model.uiState.value.empty)
        repository.failure = CompanionException.Unreachable(); model.reload(); settle()
        assertNotNull(model.uiState.value.error)
        val one = detail("nope"); repository.failure = null; one.reload(); settle()
        assertTrue(one.uiState.value.missing)
    }

    private fun TestScope.settle() { advanceTimeBy(1_000); runCurrent() }
}
