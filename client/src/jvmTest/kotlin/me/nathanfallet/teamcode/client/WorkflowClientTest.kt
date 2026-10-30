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

class WorkflowClientTest {
    private val requests = mutableListOf<HttpRequestData>()
    private fun client(answer: MockRequestHandleScope.(HttpRequestData) -> io.ktor.client.request.HttpResponseData) =
        TeamCodeClient("https://team.example", { "synthetic-token" }) { configure ->
            HttpClient(MockEngine { request -> requests += request; answer(request) }) { configure(this) }
        }
    private val json = headersOf(HttpHeaders.ContentType, "application/json")
    private fun body(request: HttpRequestData) = Json.parseToJsonElement((request.body as TextContent).text).jsonObject

    private val view = """{"id":"wf1","name":"Fix a ticket","version":2,"start":"analyze","nodes":[{"id":"analyze","kind":"agent","title":"Analyze","instruction":"Plan",
        "deliverable":{"type":"artifact"},"transitions":[{"on":"delivered"}],"someFutureField":1}],"createdAt":1,"updatedAt":2}"""
    private val draft = WorkflowDraft("Fix a ticket", start = "analyze", nodes = listOf(WorkflowNodeView("analyze", "agent", "Analyze", "Plan", deliverable = WorkflowDeliverableView("artifact"), transitions = listOf(WorkflowTransitionView("delivered")))))

    @Test fun listingPagesByCursorAndToleratesNewFields() = runBlocking {
        client { respond("""{"workflows":[$view],"nextAfter":"wf1"}""", HttpStatusCode.OK, json) }.use { client ->
            val page = client.workflows(after = "wf0", limit = 20)
            assertEquals("wf1", page.workflows.single().id); assertEquals("wf1", page.nextAfter)
            assertEquals("/api/v1/workflows", requests.single().url.encodedPath)
            assertEquals("wf0", requests.single().url.parameters["after"]); assertEquals("20", requests.single().url.parameters["limit"])
            assertFailsWith<IllegalArgumentException> { client.workflows(limit = 101) }
        }
    }

    @Test fun savingSendsTheDraftAndTheVersionItWasBuiltOn() = runBlocking {
        client { respond(view, HttpStatusCode.OK, json) }.use { client ->
            client.createWorkflow(draft)
            client.updateWorkflow("wf1", draft, expectedVersion = 2)
            assertEquals(listOf(HttpMethod.Post, HttpMethod.Put), requests.map { it.method })
            assertEquals("/api/v1/workflows/wf1", requests[1].url.encodedPath)
            assertEquals(2, body(requests[1])["expectedVersion"]!!.jsonPrimitive.int)
            assertEquals("Fix a ticket", body(requests[1])["workflow"]!!.jsonObject["name"]!!.jsonPrimitive.content)
        }
    }

    @Test fun readingDeletingAndTheEditorsRule() = runBlocking {
        client { request -> respond(if (request.url.encodedPath.endsWith("settings")) """{"editors":{"mode":"members","memberIds":["m1"]},"canEdit":true}""" else view, HttpStatusCode.OK, json) }.use { client ->
            assertEquals(2, client.workflow("wf1").version)
            assertEquals(HttpMethod.Delete, run { client.deleteWorkflow("wf1"); requests.last().method })
            val settings = client.updateWorkflowEditors(WorkflowEditorsView("members", listOf("m1")))
            assertTrue(settings.canEdit); assertEquals(listOf("m1"), settings.editors.memberIds)
            assertEquals("members", body(requests.last())["mode"]!!.jsonPrimitive.content)
        }
    }

    private val run = """{"id":"r1","workflowId":"wf1","workflowName":"Fix a ticket","workflowVersion":2,"status":"waiting","startedById":"m1","inputs":{"ticket":"T-1"},
        "currentNodeId":"review","visits":[{"seq":1,"nodeId":"analyze","kind":"agent","status":"done","outcome":"delivered","output":"plan-1","startedAt":1,"someFutureField":1}],"createdAt":1,"updatedAt":2}"""

    @Test fun runsAreStartedListedCancelledAndDelivered() = runBlocking {
        client { respond(run, HttpStatusCode.OK, json) }.use { client ->
            val started = client.startWorkflowRun("wf1", StartWorkflowRunRequest(mapOf("ticket" to "T-1"), "p1"), idempotencyKey = "k1")
            assertEquals("r1", started.id); assertEquals("review", started.currentNodeId); assertEquals("plan-1", started.visits.single().output)
            assertEquals("k1", requests.last().headers[CloudHeaders.IDEMPOTENCY_KEY]); assertEquals("/api/v1/workflows/wf1/runs", requests.last().url.encodedPath)
            assertEquals("p1", body(requests.last())["profileId"]!!.jsonPrimitive.content)
            client.cancelWorkflowRun("r1"); assertEquals("/api/v1/workflows/runs/r1/cancel", requests.last().url.encodedPath)
            client.deliverWorkflowRun("r1", JsonPrimitive("plan-2"))
            assertEquals("/api/v1/workflows/runs/r1/deliver", requests.last().url.encodedPath); assertEquals("plan-2", body(requests.last())["output"]!!.jsonPrimitive.content)
            client.workflowRun("r1"); assertEquals(HttpMethod.Get, requests.last().method)
            client.deliverWorkflowSession("s1", buildJsonObject { put("verdict", "ok") })
            assertEquals(HttpMethod.Post, requests.last().method); assertEquals("/api/v1/workflows/session/deliver", requests.last().url.encodedPath)
            assertEquals("s1", body(requests.last())["sessionId"]!!.jsonPrimitive.content); assertEquals("ok", body(requests.last())["output"]!!.jsonObject["verdict"]!!.jsonPrimitive.content)
            client.deliverWorkflowSession(null, JsonPrimitive("x")); assertNull(body(requests.last())["sessionId"])
            assertFailsWith<IllegalArgumentException> { client.deliverWorkflowSession("not an id", JsonPrimitive("x")) }
        }
        client { respond("""{"needsAction":true,"runId":"r1","nodeTitle":"Analyze","deliverable":{"type":"url","domain":"github.com"}}""", HttpStatusCode.OK, json) }.use { client ->
            val pending = client.pendingWorkflowSession("s1")
            assertEquals(HttpMethod.Get, requests.last().method); assertEquals("/api/v1/workflows/session/pending", requests.last().url.encodedPath)
            assertEquals("s1", requests.last().url.parameters["sessionId"])
            assertTrue(pending.needsAction); assertEquals("r1", pending.runId); assertEquals("Analyze", pending.nodeTitle); assertEquals("github.com", pending.deliverable?.domain)
            client.pendingWorkflowSession(null); assertNull(requests.last().url.parameters["sessionId"])
            assertFailsWith<IllegalArgumentException> { client.pendingWorkflowSession("not an id") }
        }
        client { respond("""{"needsAction":false}""", HttpStatusCode.OK, json) }.use { client ->
            assertEquals(WorkflowSessionPendingView(false), client.pendingWorkflowSession("s1"))
        }
        client { respond("""{"runs":[$run],"nextBefore":"r1"}""", HttpStatusCode.OK, json) }.use { client ->
            val page = client.workflowRuns(workflow = "wf1", status = "waiting", limit = 10)
            assertEquals("r1", page.nextBefore)
            assertEquals("waiting", requests.last().url.parameters["status"]); assertEquals("wf1", requests.last().url.parameters["workflow"])
            assertFailsWith<IllegalArgumentException> { client.workflowRuns(status = "paused") }
        }
    }
}
