package me.nathanfallet.teamcode.domain.automations

import kotlinx.coroutines.runBlocking
import me.nathanfallet.teamcode.domain.automations.Fixtures.ada
import me.nathanfallet.teamcode.domain.automations.Fixtures.principal
import me.nathanfallet.teamcode.domain.team.TeamFailure
import me.nathanfallet.teamcode.domain.webhooks.WebhookService
import kotlin.test.*

class AutomationWebhookTest {
    private val body = """{"ticket":{"number":735}}""".encodeToByteArray()

    private class Hook(val world: Fixtures.World, val id: String, val secret: String)
    private fun hook(definition: AutomationDefinition = Fixtures.webhook): Hook = runBlocking {
        val world = Fixtures.World()
        val created = world.service.create(principal(ada), definition)
        world.service.enable(principal(ada), created.automation.id)
        Hook(world, created.automation.id, created.secret!!)
    }
    private fun Hook.signed(bytes: ByteArray = body, secret: String = this.secret, seconds: Long = world.store.clock / 1000, version: Int? = null, key: String? = null) =
        TriggerRequest(id, version, null, seconds.toString(), AutomationRules.signature(secret, seconds.toString(), bytes), key, bytes)

    @Test fun theSignatureIsTheOneOutgoingWebhooksUse() {
        assertEquals(WebhookService.signature("whsec_test", 1_700_000_000, """{"id":"d1"}"""), AutomationRules.signature("whsec_test", "1700000000", """{"id":"d1"}""".encodeToByteArray()))
    }

    @Test fun aSignedRequestRecordsAQueuedRunWithTheRenderedPrompt() = runBlocking {
        val hook = hook()
        val outcome = hook.world.service.trigger(hook.signed())
        assertIs<RunOutcome.Created>(outcome)
        assertEquals(RunSource.WEBHOOK, outcome.run.source)
        assertEquals("Fix ticket 735", hook.world.store.prompts.getValue(outcome.run.id))
    }

    @Test fun theSecretUrlIsTheOtherProof() = runBlocking {
        val hook = hook()
        assertIs<RunOutcome.Created>(hook.world.service.trigger(TriggerRequest(hook.id, null, hook.secret, null, null, "d1", body)))
        assertEquals("invalid_signature", assertFailsWith<TeamFailure> { hook.world.service.trigger(TriggerRequest(hook.id, null, hook.secret + "x", null, null, "d2", body)) }.code)
    }

    @Test fun everyRefusalBeforeTheProofLooksTheSame() = runBlocking {
        val hook = hook()
        val now = hook.world.store.clock / 1000
        for (request in listOf(
            hook.signed().let { TriggerRequest("unknown", null, null, it.timestamp, it.signature, null, body) },
            hook.signed(secret = "whsec_wrong"),
            hook.signed(seconds = now - 301),
            TriggerRequest(hook.id, null, null, null, null, null, body),
            hook.signed().let { TriggerRequest(hook.id, null, null, it.timestamp, it.signature, null, """{"ticket":{"number":736}}""".encodeToByteArray()) },
        )) {
            val error = assertFailsWith<TeamFailure> { hook.world.service.trigger(request) }
            assertEquals(401, error.status); assertEquals("invalid_signature", error.code)
        }
        assertTrue(hook.world.store.runs.isEmpty())
    }

    @Test fun theFormerSecretWorksDuringItsGraceOnly() = runBlocking {
        val hook = hook()
        hook.world.service.rotateSecret(principal(ada), hook.id)
        hook.world.service.trigger(hook.signed(key = "a"))
        hook.world.store.clock += AutomationRules.PREVIOUS_SECRET_GRACE_MILLIS + 1
        assertEquals("invalid_signature", assertFailsWith<TeamFailure> { hook.world.service.trigger(hook.signed(key = "b")) }.code)
    }

    @Test fun aVersionedUrlRunsOnlyThatVersion() = runBlocking {
        val hook = hook(Fixtures.webhook.copy(overlap = OverlapPolicy.ALLOW))
        hook.world.service.trigger(hook.signed(version = 1, key = "a"))
        hook.world.service.update(principal(ada), hook.id, 1, Fixtures.webhook.copy(prompt = "Look at {{ticket.number}}", overlap = OverlapPolicy.ALLOW))
        assertEquals("automation_version_changed", assertFailsWith<TeamFailure> { hook.world.service.trigger(hook.signed(version = 1, key = "b")) }.code)
        val latest = hook.world.service.trigger(hook.signed(key = "c"))
        assertEquals(2, latest.run.automationVersion)
        assertEquals("Look at 735", hook.world.store.prompts.getValue(latest.run.id))
        assertEquals(2, hook.world.service.trigger(hook.signed(version = 2, key = "d")).run.automationVersion)
    }

    @Test fun aPausedAutomationRefusesAfterTheProof() = runBlocking {
        val hook = hook()
        hook.world.service.disable(principal(ada), hook.id)
        assertEquals("automation_disabled", assertFailsWith<TeamFailure> { hook.world.service.trigger(hook.signed()) }.code)
    }

    @Test fun aDeliveryKeyOrTheBodyDeduplicates() = runBlocking {
        val hook = hook(Fixtures.webhook.copy(overlap = OverlapPolicy.ALLOW))
        val first = hook.world.service.trigger(hook.signed(key = "ticket-735"))
        val again = hook.world.service.trigger(hook.signed(key = "ticket-735"))
        assertIs<RunOutcome.Replayed>(again); assertEquals(first.run.id, again.run.id)
        val unkeyed = hook.world.service.trigger(hook.signed())
        assertIs<RunOutcome.Replayed>(hook.world.service.trigger(hook.signed()), "the same body within a minute")
        hook.world.store.clock += AutomationRules.BODY_DEDUPLICATION_MILLIS
        assertNotEquals(unkeyed.run.id, hook.world.service.trigger(hook.signed()).run.id)
    }

    @Test fun anInvalidBodyIsRecordedAndNamed() = runBlocking {
        val hook = hook()
        val bad = """{"ticket":{"number":"abc"}}""".encodeToByteArray()
        val error = assertFailsWith<TeamFailure> { hook.world.service.trigger(hook.signed(bad)) }
        assertEquals("invalid_payload", error.code); assertTrue(error.message!!.contains("$.ticket.number: type:int")); assertFalse(error.message!!.contains("abc"))
        val notObject = "[1]".encodeToByteArray()
        assertTrue(assertFailsWith<TeamFailure> { hook.world.service.trigger(hook.signed(notObject)) }.message!!.contains("$: not_object"))
        assertEquals(listOf(RunStatus.INVALID_PAYLOAD, RunStatus.INVALID_PAYLOAD), hook.world.store.runs.values.map { it.status })
        assertEquals(2, hook.world.store.automations.getValue(hook.id).consecutiveFailures)
    }

    @Test fun theDailyBudgetAndTheMinuteRateAreEnforced() = runBlocking {
        val hook = hook(Fixtures.webhook.copy(overlap = OverlapPolicy.ALLOW, maxRunsPerDay = 2))
        hook.world.service.trigger(hook.signed(key = "1")); hook.world.service.trigger(hook.signed(key = "2"))
        val error = assertFailsWith<TeamFailure> { hook.world.service.trigger(hook.signed(key = "3")) }
        assertEquals(429, error.status); assertEquals("automation_budget", error.code)
        assertEquals(RunStatus.SKIPPED_BUDGET, hook.world.store.runs.values.last().status)
        val busy = hook(Fixtures.webhook.copy(overlap = OverlapPolicy.ALLOW, maxRunsPerDay = 1000))
        repeat(AutomationRules.WEBHOOK_PER_MINUTE) { busy.world.service.trigger(busy.signed(key = "k$it")) }
        assertEquals("automation_rate_limit", assertFailsWith<TeamFailure> { busy.world.service.trigger(busy.signed(key = "over")) }.code)
    }

    @Test fun overlapSkipsWhileARunIsActive() = runBlocking {
        val hook = hook()
        hook.world.service.trigger(hook.signed(key = "1"))
        assertEquals(RunStatus.SKIPPED_OVERLAP, hook.world.service.trigger(hook.signed(key = "2")).run.status)
        val queue = hook(Fixtures.webhook.copy(overlap = OverlapPolicy.QUEUE))
        assertEquals(RunStatus.QUEUED, queue.world.service.trigger(queue.signed(key = "1")).run.status)
        assertEquals(RunStatus.SKIPPED_OVERLAP, queue.world.service.trigger(queue.signed(key = "2")).run.status, "one waiting run at most")
    }

    @Test fun anOversizedBodyIsRefused() = runBlocking {
        val hook = hook()
        assertEquals(413, assertFailsWith<TeamFailure> { hook.world.service.trigger(hook.signed(ByteArray(AutomationRules.MAX_PAYLOAD_BYTES + 1))) }.status)
    }
}
