package me.nathanfallet.teamcode.client

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.*
import io.ktor.http.content.TextContent
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import kotlin.test.*

/** `cloud.sessions.effort.v1`: the call that switches a session's effort. */
class SessionEffortClientTest {
    private val requests = mutableListOf<HttpRequestData>()
    private val json = headersOf(HttpHeaders.ContentType, "application/json")
    private val session = """{"id":"s1","name":"Task","ownerId":"m1","createdBy":{"initiatorId":"m1","credentialId":"c","effectiveMemberId":"m1"},
        "state":"active","desiredState":"running","generation":2,"currentEpoch":1,"profile":{"id":"p1","version":1},"image":{"reference":"img"},
        "agent":{"kind":"claude","effort":"max"},"activity":{"lastActivityAt":42,"idleTimeoutMinutes":30},"resume":{"workspace":"w","conversation":"c"},
        "workspaceDeletion":{"state":"none"},"createdAt":1,"updatedAt":2}"""

    private fun client(status: HttpStatusCode = HttpStatusCode.OK, body: String = session) =
        TeamCodeClient("https://team.example", { "synthetic-token" }) { configure ->
            HttpClient(MockEngine { request -> requests += request; respond(body, status, json) }) { configure(this) }
        }

    @Test fun switchingPutsTheEffortAndReturnsTheSession() = runBlocking {
        client().use { client ->
            val changed = client.setCloudSessionEffort("s1", "max")
            assertEquals("max", changed.agent.effort)
            val request = requests.single()
            assertEquals(HttpMethod.Put, request.method)
            assertEquals("/api/v1/cloud/sessions/s1/effort", request.url.encodedPath)
            assertEquals("max", Json.parseToJsonElement((request.body as TextContent).text).jsonObject["effort"]!!.jsonPrimitive.content)
        }
    }

    @Test fun anIdentifierThatIsNotOneNeverLeavesTheClient() = runBlocking {
        client().use { client ->
            assertFailsWith<IllegalArgumentException> { client.setCloudSessionEffort("../x", "max") }
            assertTrue(requests.isEmpty())
        }
    }

    @Test fun aRefusalSurfacesTheServersFailure() = runBlocking {
        client(HttpStatusCode.BadRequest, """{"error":{"type":"invalid_agent","message":"Use a valid agent effort."}}""").use { client ->
            assertEquals("invalid_agent", assertFailsWith<TeamCodeException> { client.setCloudSessionEffort("s1", "two words") }.code)
        }
    }
}
