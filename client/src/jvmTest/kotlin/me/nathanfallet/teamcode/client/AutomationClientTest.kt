package me.nathanfallet.teamcode.client

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.*
import io.ktor.http.content.TextContent
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import me.nathanfallet.teamcode.api.models.*
import kotlin.test.*

class AutomationClientTest {
    private val requests = mutableListOf<HttpRequestData>()
    private fun client(answer: MockRequestHandleScope.(HttpRequestData) -> io.ktor.client.request.HttpResponseData) =
        TeamCodeClient("https://team.example", { "synthetic-token" }) { configure ->
            HttpClient(MockEngine { request -> requests += request; answer(request) }) { configure(this) }
        }
    private val json = headersOf(HttpHeaders.ContentType, "application/json")
    private fun body(request: HttpRequestData) = Json.parseToJsonElement((request.body as TextContent).text).jsonObject

    private val view = """{"id":"a1","ownerId":"m1","ownerName":"ada","name":"Nightly","enabled":false,"version":1,
        "trigger":{"kind":"cron","cron":"0 21 * * *","timezone":"Europe/Paris","catchup":"once","catchupGraceMinutes":360},
        "action":{"kind":"cloud","profileId":"p1","maxRuntimeMinutes":60},"prompt":"Summarize {{run.date}}","variables":[],
        "overlap":"skip","maxRunsPerDay":24,"createdAt":1,"updatedAt":1}"""
    private val run = """{"id":"r1","automationId":"a1","automationVersion":1,"source":"test","status":"queued","queuedAt":5}"""

    @Test fun creationSendsTheDefinitionAndReturnsTheSecretOnce() = runBlocking {
        client { respond("""{"automation":$view,"secret":"whsec_x"}""", HttpStatusCode.Created, json) }.use { client ->
            val input = AutomationInput("Nightly", AutomationTriggerView("cron", "0 21 * * *", "Europe/Paris"), AutomationActionView("cloud", "p1"), "Summarize {{run.date}}")
            val created = client.createAutomation(input)
            assertEquals("whsec_x", created.secret)
            assertEquals("a1", created.automation.id)
            assertEquals(HttpMethod.Post, requests.single().method)
            assertEquals("/api/v1/automations", requests.single().url.encodedPath)
            assertEquals("Nightly", body(requests.single())["automation"]!!.jsonObject["name"]!!.jsonPrimitive.content)
        }
    }

    @Test fun aWorkflowActionSendsItsWorkflowAndInputsAndReadsTheWorkflowRunBack() = runBlocking {
        client { request ->
            if (request.url.encodedPath.endsWith("/runs/r1")) respond("""{"id":"r1","automationId":"a1","automationVersion":1,"source":"test","status":"running","queuedAt":5,"workflowRunId":"wr1"}""", HttpStatusCode.OK, json)
            else respond("""{"automation":${view.replace(""""kind":"cloud","profileId":"p1"""", """"kind":"workflow","profileId":"p1","workflowId":"wf1","inputs":{"ticket":"T-{{ticket}}"}""")}}""", HttpStatusCode.Created, json)
        }.use { client ->
            val input = AutomationInput("Triage", AutomationTriggerView("webhook"), AutomationActionView("workflow", "p1", workflowId = "wf1", inputs = mapOf("ticket" to "T-{{ticket}}")))
            val created = client.createAutomation(input)
            assertEquals("wf1", created.automation.action.workflowId); assertEquals(mapOf("ticket" to "T-{{ticket}}"), created.automation.action.inputs)
            val action = body(requests.single())["automation"]!!.jsonObject["action"]!!.jsonObject
            assertEquals("workflow", action["kind"]!!.jsonPrimitive.content); assertEquals("T-{{ticket}}", action["inputs"]!!.jsonObject["ticket"]!!.jsonPrimitive.content)
            assertEquals("wr1", client.automationRun("a1", "r1").workflowRunId)
        }
    }

    @Test fun aTestRunCarriesItsIdempotencyKeyAndPayload() = runBlocking {
        client { respond(run, HttpStatusCode.Accepted, json) }.use { client ->
            val result = client.runAutomation("a1", buildJsonObject { put("ticket", buildJsonObject { put("number", 735) }) }, idempotencyKey = "test-1")
            assertEquals("r1", result.id)
            val request = requests.single()
            assertEquals("/api/v1/automations/a1/run", request.url.encodedPath)
            assertEquals("test-1", request.headers[CloudHeaders.IDEMPOTENCY_KEY])
            assertEquals(false, body(request)["dryRun"]!!.jsonPrimitive.boolean)
            assertEquals(735, body(request)["payload"]!!.jsonObject["ticket"]!!.jsonObject["number"]!!.jsonPrimitive.int)
        }
    }

    @Test fun aDryRunSendsNoIdempotencyKey() = runBlocking {
        client { respond("""{"renderedPrompt":"Summarize 2026-10-28","variables":[],"problems":[]}""", HttpStatusCode.OK, json) }.use { client ->
            assertEquals("Summarize 2026-10-28", client.previewAutomationRun("a1").renderedPrompt)
            assertNull(requests.single().headers[CloudHeaders.IDEMPOTENCY_KEY])
            assertEquals(true, body(requests.single())["dryRun"]!!.jsonPrimitive.boolean)
        }
    }

    @Test fun historyUsesTheCursorAndFilters() = runBlocking {
        client { respond("""{"runs":[$run],"nextBefore":"r1"}""", HttpStatusCode.OK, json) }.use { client ->
            val page = client.automationRuns("a1", before = "r9", limit = 20, sources = listOf("cron"), statuses = listOf("failed", "timed_out"))
            assertEquals("r1", page.nextBefore)
            val url = requests.single().url
            assertEquals("r9", url.parameters["before"]); assertEquals("20", url.parameters["limit"])
            assertEquals(listOf("cron"), url.parameters.getAll("source")); assertEquals(listOf("failed", "timed_out"), url.parameters.getAll("status"))
        }
    }

    @Test fun pausingAndCancellingUseTheirRoutes() = runBlocking {
        client { request -> respond(if (request.url.encodedPath.endsWith("/cancel")) run else view, HttpStatusCode.OK, json) }.use { client ->
            client.disableAutomation("a1"); client.enableAutomation("a1"); client.cancelAutomationRun("a1", "r1")
            assertEquals(listOf("/api/v1/automations/a1/disable", "/api/v1/automations/a1/enable", "/api/v1/automations/a1/runs/r1/cancel"), requests.map { it.url.encodedPath })
        }
    }

    @Test fun failuresCarryTheServerCode() = runBlocking {
        client { respond("""{"error":{"type":"not_available_yet","message":"Remote sessions are not available to automations yet."}}""", HttpStatusCode.UnprocessableEntity, json) }.use { client ->
            val input = AutomationInput("Remote", AutomationTriggerView("webhook"), AutomationActionView("remote"), "Go")
            assertEquals("not_available_yet", assertFailsWith<TeamCodeException> { client.createAutomation(input) }.code)
        }
    }

    @Test fun invalidIdentifiersNeverReachTheServer() = runBlocking {
        client { respond(view, HttpStatusCode.OK, json) }.use { client ->
            assertFailsWith<IllegalArgumentException> { client.automation("../members") }
            assertFailsWith<IllegalArgumentException> { client.runAutomation("a1", idempotencyKey = "bad key") }
            assertTrue(requests.isEmpty())
        }
    }
}
