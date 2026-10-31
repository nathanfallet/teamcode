package me.nathanfallet.teamcode.companion.domain

import kotlinx.coroutines.test.runTest
import me.nathanfallet.teamcode.companion.*
import me.nathanfallet.teamcode.companion.domain.models.*
import me.nathanfallet.teamcode.companion.domain.usecases.auth.AccountGuard
import me.nathanfallet.teamcode.companion.domain.usecases.auth.RefreshAccountUseCase
import me.nathanfallet.teamcode.companion.domain.usecases.workflows.*
import kotlin.test.*

class WorkflowUseCasesTest {
    private val repository = FakeWorkflowRepository()
    private val features = FakeFeatures(setOf("workflows.v1"))
    private val accounts = InMemoryAccountRepository(listOf(account()))
    private val guard = AccountGuard(RefreshAccountUseCase(FakeAuthRepository(), accounts, credentials(), FixedClock()))
    private val access = WorkflowAccess(repository, features, accounts, guard)

    @Test fun theDestinationIsOfferedOnlyWithTheCapabilityAndASignedInAccount() = runTest {
        val availability = WorkflowsAvailabilityUseCase(accounts, features)
        assertTrue(availability.available("a1"))
        features.features = emptySet(); assertFalse(availability.available("a1"))
        features.features = setOf("workflows.v1"); accounts.save(account().copy(signedIn = false)); assertFalse(availability.available("a1"))
        assertFalse(availability.available("unknown"))
    }

    @Test fun aServerWithoutWorkflowsRefusesEveryCall() = runTest {
        features.features = emptySet()
        assertFailsWith<CompanionException.Unsupported> { LoadWorkflowRunsUseCase(access)("a1", RunScope.MINE, RunFilter.ALL) }
        assertEquals(emptyList(), repository.calls)
    }

    @Test fun theTeamsRunsAreAnAdministratorsOnly() = runTest {
        assertFailsWith<CompanionException.Unsupported> { LoadWorkflowRunsUseCase(access)("a1", RunScope.TEAM, RunFilter.ALL) }
        accounts.save(account().copy(role = "ADMIN"))
        LoadWorkflowRunsUseCase(access)("a1", RunScope.TEAM, RunFilter.FAILED)
        assertEquals(listOf("runs:TEAM:FAILED:null"), repository.calls)
    }

    @Test fun onlyTheStarterOrAnAdministratorCancelsARunThatMoves() = runTest {
        repository.runs["r1"] = workflowRun("r1", RunStatus.WAITING, starter = "m2")
        val cancel = CancelWorkflowRunUseCase(access)
        val error = assertFailsWith<InvalidInput> { cancel("a1", repository.runs.getValue("r1")) }
        assertEquals(InvalidInput.Problem.WORKFLOW_NOT_STARTER, error.problem)
        accounts.save(account().copy(role = "ADMIN"))
        assertEquals(RunStatus.CANCELLED, cancel("a1", repository.runs.getValue("r1")).status)
        // A finished run has nothing to cancel, even for its starter.
        val done = workflowRun("r2", RunStatus.SUCCEEDED)
        assertFalse(access.canCancel("a1", done))
        assertFailsWith<InvalidInput> { cancel("a1", done) }
    }

    @Test fun theStartFormChecksRequiredDefaultsLengthAndTheProfile() {
        val wf = workflow(steps = listOf(step("work"), step("end", StepKind.HUMAN)))
        assertEquals(InvalidInput.Problem.WORKFLOW_INPUT_REQUIRED, assertFailsWith<InvalidInput> { checkRunStart(wf, emptyMap(), "p1") }.problem)
        assertEquals(InvalidInput.Problem.WORKFLOW_INPUT_REQUIRED, assertFailsWith<InvalidInput> { checkRunStart(wf, mapOf("ticket" to "   "), "p1") }.problem)
        val tooLong = assertFailsWith<InvalidInput> { checkRunStart(wf, mapOf("ticket" to "x".repeat(11)), "p1") }
        assertEquals(InvalidInput.Problem.WORKFLOW_INPUT_LENGTH, tooLong.problem); assertEquals(10, tooLong.limit)
        // An agent step without a profile needs one.
        assertEquals(InvalidInput.Problem.CHOOSE_PROFILE, assertFailsWith<InvalidInput> { checkRunStart(wf, mapOf("ticket" to "T-1"), null) }.problem)
        // The default fills an optional input that was not typed; values are trimmed.
        val start = checkRunStart(wf, mapOf("ticket" to " T-1 "), "p1")
        assertEquals(mapOf("ticket" to "T-1", "notes" to "small"), start.inputs); assertEquals("p1", start.profileId)
        // Every agent step names its profile: none is asked, none sent.
        val named = workflow(inputs = emptyList(), steps = listOf(step("work", profileId = "pa")))
        assertEquals(RunStart(emptyMap(), null), checkRunStart(named, emptyMap(), "p1"))
    }

    @Test fun startingSendsTheKeyAndTheCheckedInputs() = runTest {
        val run = StartWorkflowRunUseCase(access)("a1", workflow(), mapOf("ticket" to "T-1"), "p1", "key-1")
        assertEquals("new", run.id)
        assertEquals(Triple("wf1", RunStart(mapOf("ticket" to "T-1", "notes" to "small"), "p1"), "key-1"), repository.started.single())
    }

    @Test fun theMembersOpenWaitsAreReadByIdAndBySession() = runTest {
        val waits = object : me.nathanfallet.teamcode.companion.domain.repositories.WaitRepository {
            override suspend fun list(accountId: String, status: WaitStatus, before: String?) = WaitPage(listOf(
                Wait("w1", WaitSource.WAIT, WaitKind.HUMAN_REQUEST, true, "s1", null, null, "m1", "T", null, emptyList(), emptyList(), emptyList(), 1),
            ), null)
            override suspend fun answer(accountId: String, waitId: String, action: String, values: Map<String, kotlinx.serialization.json.JsonElement>) = error("unused")
        }
        features.features = setOf("workflows.v1", "waits.v1")
        assertEquals(OpenWaits(setOf("w1"), setOf("s1")), LoadOpenWaitsUseCase(waits, features, guard)("a1"))
        features.features = setOf("workflows.v1")
        assertEquals(OpenWaits.NONE, LoadOpenWaitsUseCase(waits, features, guard)("a1"))
    }
}
