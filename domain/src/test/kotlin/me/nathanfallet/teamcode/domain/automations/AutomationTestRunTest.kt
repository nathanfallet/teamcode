package me.nathanfallet.teamcode.domain.automations

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import me.nathanfallet.teamcode.domain.automations.Fixtures.admin
import me.nathanfallet.teamcode.domain.automations.Fixtures.ada
import me.nathanfallet.teamcode.domain.automations.Fixtures.principal
import me.nathanfallet.teamcode.domain.team.ServiceScope
import me.nathanfallet.teamcode.domain.team.TeamFailure
import kotlin.test.*

class AutomationTestRunTest {
    private val ticket = buildJsonObject { put("ticket", buildJsonObject { put("number", 735) }) }

    @Test fun aDryRunRendersThePromptWithoutARun() = runBlocking {
        val world = Fixtures.World()
        val id = world.service.create(principal(ada), Fixtures.webhook).automation.id
        val preview = (world.service.test(principal(ada), id, ticket, dryRun = true, idempotencyKey = null) as TestOutcome.Preview).preview
        assertEquals("Fix ticket 735", preview.renderedPrompt)
        assertEquals("735", preview.variables.single().value)
        assertEquals(3, preview.profileVersion)
        assertEquals("disabled", preview.wouldSkip, "a real trigger of a paused automation would not start")
        assertTrue(world.store.runs.isEmpty())
        assertEquals("automation.test_previewed", world.store.audit.last())
    }

    @Test fun aDryRunReportsEachProblemByPath() = runBlocking {
        val world = Fixtures.World()
        val id = world.service.create(principal(ada), Fixtures.webhook).automation.id
        val bad = buildJsonObject { put("ticket", buildJsonObject { put("number", "abc") }) }
        val preview = (world.service.test(principal(ada), id, bad, dryRun = true, idempotencyKey = null) as TestOutcome.Preview).preview
        assertNull(preview.renderedPrompt)
        assertEquals(listOf(VariableProblem("$.ticket.number", "type:int")), preview.problems)
    }

    @Test fun aRealTestRunsTheSavedDefinitionEvenDisabled() = runBlocking {
        val world = Fixtures.World()
        val id = world.service.create(principal(ada), Fixtures.webhook).automation.id
        val outcome = (world.service.test(principal(ada), id, ticket, dryRun = false, idempotencyKey = "k1") as TestOutcome.Run).outcome
        assertIs<RunOutcome.Created>(outcome)
        assertEquals(RunSource.TEST, outcome.run.source); assertEquals(RunStatus.QUEUED, outcome.run.status)
        assertEquals("Fix ticket 735", world.store.prompts.getValue(outcome.run.id))
        val replay = (world.service.test(principal(ada), id, ticket, dryRun = false, idempotencyKey = "k1") as TestOutcome.Run).outcome
        assertIs<RunOutcome.Replayed>(replay); assertEquals(outcome.run.id, replay.run.id)
        assertEquals("idempotency_key_required", assertFailsWith<TeamFailure> { world.service.test(principal(ada), id, ticket, dryRun = false, idempotencyKey = null) }.code)
    }

    @Test fun anInvalidTestPayloadIsRecordedAndRefusedLikeTheWebhook() = runBlocking {
        val world = Fixtures.World()
        val id = world.service.create(principal(ada), Fixtures.webhook).automation.id
        val error = assertFailsWith<TeamFailure> { world.service.test(principal(ada), id, buildJsonObject { }, dryRun = false, idempotencyKey = "k2") }
        assertEquals("invalid_payload", error.code); assertEquals(422, error.status)
        assertTrue(error.message!!.contains("$.ticket.number: required"))
        assertEquals(RunStatus.INVALID_PAYLOAD, world.store.runs.values.single().status)
        assertTrue(world.store.prompts.isEmpty())
    }

    @Test fun thePayloadMatchesTheTrigger() = runBlocking {
        val world = Fixtures.World()
        val cron = world.service.create(principal(ada), Fixtures.cron).automation.id
        val hook = world.service.create(principal(ada), Fixtures.webhook).automation.id
        assertEquals("payload_not_accepted", assertFailsWith<TeamFailure> { world.service.test(principal(ada), cron, ticket, dryRun = true, idempotencyKey = null) }.code)
        assertEquals("payload_required", assertFailsWith<TeamFailure> { world.service.test(principal(ada), hook, null, dryRun = true, idempotencyKey = null) }.code)
        val preview = (world.service.test(principal(ada), cron, null, dryRun = true, idempotencyKey = null) as TestOutcome.Preview).preview
        assertTrue(preview.renderedPrompt!!.startsWith("Summarize 20")); assertTrue(preview.renderedPrompt!!.endsWith("(Europe/Paris)"))
        assertEquals(3, preview.nextRuns.size)
    }

    @Test fun anAdministratorPreviewsAnotherMembersAutomationButNeverRunsIt() = runBlocking {
        val world = Fixtures.World()
        val id = world.service.create(principal(ada), Fixtures.webhook).automation.id
        assertEquals("Fix ticket 735", (world.service.test(principal(admin), id, ticket, dryRun = true, idempotencyKey = null) as TestOutcome.Preview).preview.renderedPrompt)
        assertEquals("automation_owner_only", assertFailsWith<TeamFailure> { world.service.test(principal(admin), id, ticket, dryRun = false, idempotencyKey = "k") }.code)
        assertEquals("automation_owner_only", assertFailsWith<TeamFailure> { world.service.test(principal(admin, setOf(ServiceScope.ADMIN_CLOUD)), id, ticket, dryRun = false, idempotencyKey = "k") }.code)
    }

    @Test fun theOwnersCloudWriteKeyTestsButACloudReadKeyOnlyPreviews() = runBlocking {
        val world = Fixtures.World()
        val id = world.service.create(principal(ada), Fixtures.webhook).automation.id
        world.service.test(principal(ada, setOf(ServiceScope.CLOUD_WRITE)), id, ticket, dryRun = false, idempotencyKey = "ci-1")
        world.service.test(principal(ada, setOf(ServiceScope.CLOUD_READ)), id, ticket, dryRun = true, idempotencyKey = null)
        assertEquals("credential_scope", assertFailsWith<TeamFailure> { world.service.test(principal(ada, setOf(ServiceScope.CLOUD_READ)), id, ticket, dryRun = false, idempotencyKey = "k") }.code)
    }

    @Test fun aTestHonoursOverlapButNotTheDailyBudget() = runBlocking {
        val world = Fixtures.World()
        val id = world.service.create(principal(ada), Fixtures.webhook.copy(maxRunsPerDay = 1)).automation.id
        val first = (world.service.test(principal(ada), id, ticket, false, "a") as TestOutcome.Run).outcome.run
        assertEquals(RunStatus.SKIPPED_OVERLAP, (world.service.test(principal(ada), id, ticket, false, "b") as TestOutcome.Run).outcome.run.status)
        world.store.finish(first.id, RunStatus.SUCCEEDED, null)
        assertEquals(RunStatus.QUEUED, (world.service.test(principal(ada), id, ticket, false, "c") as TestOutcome.Run).outcome.run.status, "tests are outside maxRunsPerDay")
        assertEquals(0, world.store.automations.getValue(id).consecutiveFailures)
    }

    @Test fun aDraftPreviewChecksWithoutSaving() = runBlocking {
        val world = Fixtures.World()
        val preview = world.service.preview(principal(ada), Fixtures.webhook, ticket)
        assertEquals("Fix ticket 735", preview.renderedPrompt)
        assertTrue(world.store.automations.isEmpty())
        assertEquals("invalid_automation", assertFailsWith<TeamFailure> { world.service.preview(principal(ada), Fixtures.webhook.copy(prompt = "{{nope}}"), ticket) }.code)
    }
}
