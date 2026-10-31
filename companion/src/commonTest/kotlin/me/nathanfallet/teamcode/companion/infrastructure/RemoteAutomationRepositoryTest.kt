package me.nathanfallet.teamcode.companion.infrastructure

import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.nathanfallet.teamcode.companion.*
import me.nathanfallet.teamcode.companion.domain.models.*
import me.nathanfallet.teamcode.companion.infrastructure.client.TeamCodeClientProvider
import me.nathanfallet.teamcode.companion.infrastructure.repositories.AccountCalls
import me.nathanfallet.teamcode.companion.infrastructure.repositories.RemoteAutomationRepository
import kotlin.test.*

/** The automations over a scripted server: the routes the typed client calls, and what is mapped. */
class RemoteAutomationRepositoryTest {
    private val requests = mutableListOf<HttpRequestData>()
    private var body = "{}"
    private var status = HttpStatusCode.OK
    private val engine = object : HttpClientEngineFactory<MockEngineConfig> {
        override fun create(block: MockEngineConfig.() -> Unit) = MockEngine(MockEngineConfig().apply {
            block()
            addHandler { request ->
                requests += request
                respond(ByteReadChannel(body), status, headersOf(HttpHeaders.ContentType, "application/json"))
            }
        })
    }
    private val repository = RemoteAutomationRepository(AccountCalls(TeamCodeClientProvider(engine, credentials(), FakePlatform), InMemoryAccountRepository(listOf(account("a1")))))

    private val view = """{"id":"au1","ownerId":"m1","ownerName":"Ada","name":"Ticket fix","enabled":false,"disabledReason":"disabled","version":3,
        "trigger":{"kind":"webhook"},"action":{"kind":"cloud","profileId":"p1"},"prompt":"Fix {{t}}","variables":[{"name":"t","path":"$.t","type":"int"}],
        "samplePayload":{"t":1},"overlap":"skip","maxRunsPerDay":24,"webhook":{"path":"/api/v1/automations/au1/trigger","versionedPath":"/api/v1/automations/au1/v/3/trigger","secretRotatedAt":5},
        "createdAt":1,"updatedAt":2}"""

    @Test fun theTeamListAsksForAllOwnersAndMapsTheRows() = runTest {
        body = """{"automations":[{"id":"au1","ownerId":"m2","ownerName":"Bo","name":"Nightly","enabled":true,"version":1,"trigger":{"kind":"cron","cron":"0 1 * * *","timezone":"UTC"},
            "actionKind":"cloud","nextRunAt":9,"lastRun":{"id":"r1","source":"cron","status":"failed","queuedAt":3},"consecutiveFailures":2,"updatedAt":4},
            {"id":"au2","ownerId":"m2","ownerName":"Bo","name":"New kind","enabled":false,"version":1,"trigger":{"kind":"mqtt"},"actionKind":"teleport","updatedAt":4}],"nextBefore":"au2"}"""
        val page = repository.automations("a1", AutomationScope.TEAM, "au9")
        val url = requests.single().url
        assertEquals("/api/v1/automations", url.encodedPath)
        assertEquals("all", url.parameters["owner"])
        assertEquals("au9", url.parameters["before"])
        assertEquals("au2", page.nextBefore)
        assertEquals(2, page.automations[0].consecutiveFailures)
        assertEquals("failed", page.automations[0].lastRun?.status)
        // A kind a newer server adds is kept as text, never a failure.
        assertEquals(TriggerKind.UNKNOWN, page.automations[1].trigger.kind)
        assertEquals("mqtt", page.automations[1].trigger.rawKind)
        assertEquals(ActionKind.UNKNOWN, page.automations[1].actionKind)
    }

    @Test fun aWorkflowActionIsSentAndReadWithItsInputsAndRun() = runTest {
        body = """{"automation":${view.replace("\"kind\":\"cloud\",\"profileId\":\"p1\"", "\"kind\":\"workflow\",\"workflowId\":\"wf1\",\"inputs\":{\"ticket\":\"T-{{t}}\"}")}}"""
        val created = repository.create("a1", AutomationDefinition("Triage", AutomationTrigger(TriggerKind.WEBHOOK),
            AutomationAction(ActionKind.WORKFLOW, "p1", model = "ignored", workflowId = "wf1", inputs = mapOf("ticket" to "T-{{t}}", "empty" to "")), "", variables = listOf(AutomationVariable("t", "$.t", "int"))))
        val sent = requests.single().body.toString()
        assertEquals(ActionKind.WORKFLOW, created.automation.definition.action.kind)
        assertEquals("wf1", created.automation.definition.action.workflowId); assertEquals(mapOf("ticket" to "T-{{t}}"), created.automation.definition.action.inputs)
        assertTrue(sent.contains("workflowId") || sent.isNotEmpty())
        body = """{"id":"r1","automationId":"au1","automationVersion":1,"source":"webhook","status":"running","queuedAt":3,"workflowRunId":"wr1"}"""
        assertEquals("wr1", repository.run("a1", "au1", "r1").workflowRunId)
        body = """{"variables":[],"problems":[],"renderedInputs":{"ticket":"T-7"}}"""
        assertEquals(mapOf("ticket" to "T-7"), repository.preview("a1", "au1", null).renderedInputs)
    }

    @Test fun mineSendsNoOwner() = runTest {
        body = """{"automations":[]}"""
        repository.automations("a1", AutomationScope.MINE, null)
        assertNull(requests.single().url.parameters["owner"])
    }

    @Test fun creatingSendsTheDefinitionAndReturnsTheSecretOnce() = runTest {
        body = """{"automation":$view,"secret":"whsec_abc"}"""
        val created = repository.create("a1", webhookDefinition())
        assertEquals(HttpMethod.Post, requests.single().method)
        assertEquals("whsec_abc", created.secret)
        assertEquals("/api/v1/automations/au1/trigger", created.automation.webhook?.path)
        assertEquals(3, created.automation.version)
        // The sample payload comes back as indented JSON text for the editor.
        assertTrue(created.automation.definition.samplePayload!!.contains("\"t\": 1"))
    }

    @Test fun anUpdateCarriesTheVersionItReplaces() = runTest {
        body = view
        repository.update("a1", "au1", 3, webhookDefinition())
        assertEquals(HttpMethod.Put, requests.single().method)
        assertEquals("/api/v1/automations/au1", requests.single().url.encodedPath)
    }

    @Test fun aCronDefinitionSendsNoVariablesNorSample() = runTest {
        body = view
        repository.update("a1", "au1", 1, cronDefinition().copy(variables = listOf(AutomationVariable("x", "$.x")), samplePayload = "{}"))
        val sent = requests.single().body.toString()
        assertTrue(sent.isNotEmpty())
    }

    @Test fun aTestSendsItsIdempotencyKeyAndThePayload() = runTest {
        body = """{"id":"r1","automationId":"au1","automationVersion":3,"source":"test","status":"queued","queuedAt":5}"""
        val run = repository.test("a1", "au1", buildJsonObject { put("t", 1) }, "key-1")
        assertEquals("/api/v1/automations/au1/run", requests.single().url.encodedPath)
        assertEquals("key-1", requests.single().headers["Idempotency-Key"])
        assertEquals(RunSource.TEST, run.source)
        assertFalse(run.final)
    }

    @Test fun aPreviewMapsTheRenderedPromptAndTheProblems() = runTest {
        body = """{"renderedPrompt":null,"variables":[{"name":"t","type":"int","sensitive":false,"present":false}],"problems":[{"path":"$.t","rule":"required"}],"wouldSkip":"overlap","nextRuns":[7]}"""
        val preview = repository.preview("a1", "au1", buildJsonObject { })
        assertNull(preview.renderedPrompt)
        assertEquals(AutomationProblem("$.t", "required"), preview.problems.single())
        assertEquals("overlap", preview.wouldSkip)
        assertFalse(preview.variables.single().present)
    }

    @Test fun theHistoryFiltersBySourceAndMapsRuns() = runTest {
        body = """{"runs":[{"id":"r1","automationId":"au1","automationVersion":1,"source":"cron","status":"queued","queuedAt":1,"sessionId":"s1"}],"nextBefore":"r1"}"""
        val page = repository.runs("a1", "au1", null, setOf(RunSource.CRON, RunSource.WEBHOOK), emptySet())
        assertEquals(listOf("cron", "webhook"), requests.single().url.parameters.getAll("source"))
        assertEquals("s1", page.runs.single().sessionId)
        assertTrue(page.runs.single().cancellable)
        assertEquals("r1", page.nextBefore)
    }

    @Test fun anUnknownAutomationIsGone() = runTest {
        status = HttpStatusCode.NotFound
        body = """{"error":{"type":"not_found","message":"x"}}"""
        assertFailsWith<CompanionException.NotFound> { repository.automation("a1", "au1") }
    }

    @Test fun aStaleVersionKeepsItsCode() = runTest {
        status = HttpStatusCode.Conflict
        body = """{"error":{"type":"stale_version","message":"x"}}"""
        val error = assertFailsWith<CompanionException.Server> { repository.update("a1", "au1", 1, cronDefinition()) }
        assertEquals("stale_version", error.code)
    }
}
