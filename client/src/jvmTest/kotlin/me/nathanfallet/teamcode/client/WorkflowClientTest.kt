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
}
