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

class WaitClientTest {
    private val requests = mutableListOf<HttpRequestData>()
    private fun client(answer: MockRequestHandleScope.(HttpRequestData) -> io.ktor.client.request.HttpResponseData) =
        TeamCodeClient("https://team.example", { "synthetic-token" }) { configure ->
            HttpClient(MockEngine { request -> requests += request; answer(request) }) { configure(this) }
        }
    private val json = headersOf(HttpHeaders.ContentType, "application/json")
    private fun body(request: HttpRequestData) = Json.parseToJsonElement((request.body as TextContent).text).jsonObject

    private val view = """{"id":"w1","source":"wait","kind":"human_request","status":"open","assigneeId":"m1","title":"Review the plan",
        "refs":[{"kind":"artifact","id":"a1"}],"fields":[{"id":"feedback","type":"text","label":"Feedback"}],
        "actions":[{"id":"approve","label":"Approve"}],"createdAt":5,"someFutureField":1}"""

    @Test fun listingAsksForOpenWaitsByDefaultAndToleratesNewFields() = runBlocking {
        client { respond("""{"waits":[$view],"nextBefore":"w1"}""", HttpStatusCode.OK, json) }.use { client ->
            val page = client.waits()
            assertEquals("w1", page.waits.single().id); assertEquals("w1", page.nextBefore)
            assertEquals("/api/v1/waits", requests.single().url.encodedPath)
            assertEquals("open", requests.single().url.parameters["status"]); assertEquals("50", requests.single().url.parameters["limit"])
        }
    }

    @Test fun creationSendsTheWaitAndAnIdempotencyKey() = runBlocking {
        client { respond(view, HttpStatusCode.Created, json) }.use { client ->
            client.createWait(WaitInput("s1", "Review the plan", assigneeId = "m2", refs = listOf(WaitRefView("artifact", id = "a1"))), idempotencyKey = "k1")
            val request = requests.single()
            assertEquals(HttpMethod.Post, request.method); assertEquals("k1", request.headers[CloudHeaders.IDEMPOTENCY_KEY])
            val wait = body(request)["wait"]!!.jsonObject
            assertEquals("s1", wait["sessionId"]!!.jsonPrimitive.content); assertEquals("m2", wait["assigneeId"]!!.jsonPrimitive.content)
        }
    }

    @Test fun answeringSendsTheActionAndValues() = runBlocking {
        client { respond(view.replace("open", "resolved"), HttpStatusCode.OK, json) }.use { client ->
            val result = client.answerWait("w1", "request_changes", mapOf("feedback" to JsonPrimitive("Split step 2")))
            assertEquals("resolved", result.status)
            assertEquals("/api/v1/waits/w1/answer", requests.single().url.encodedPath)
            assertEquals("request_changes", body(requests.single())["action"]!!.jsonPrimitive.content)
            assertEquals("Split step 2", body(requests.single())["values"]!!.jsonObject["feedback"]!!.jsonPrimitive.content)
        }
    }

    @Test fun badArgumentsNeverLeaveTheClient() = runBlocking {
        client { respond(view, HttpStatusCode.OK, json) }.use { client ->
            assertFailsWith<IllegalArgumentException> { client.waits(status = "pending") }
            assertFailsWith<IllegalArgumentException> { client.waits(limit = 0) }
            assertFailsWith<IllegalArgumentException> { client.wait("../x") }
            assertTrue(requests.isEmpty())
        }
    }
}
