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

/** `cloud.sessions.model.v1`: the call that switches a session's model and the one that lists the models to pick from. */
class SessionModelClientTest {
    private val requests = mutableListOf<HttpRequestData>()
    private val json = headersOf(HttpHeaders.ContentType, "application/json")
    private val session = """{"id":"s1","name":"Task","ownerId":"m1","createdBy":{"initiatorId":"m1","credentialId":"c","effectiveMemberId":"m1"},
        "state":"active","desiredState":"running","generation":2,"currentEpoch":1,"profile":{"id":"p1","version":1},"image":{"reference":"img"},
        "agent":{"kind":"claude","model":"claude-sonnet-5-5"},"activity":{"lastActivityAt":42,"idleTimeoutMinutes":30},"resume":{"workspace":"w","conversation":"c"},
        "workspaceDeletion":{"state":"none"},"createdAt":1,"updatedAt":2}"""

    private fun client(status: HttpStatusCode = HttpStatusCode.OK, body: String = session) =
        TeamCodeClient("https://team.example", { "synthetic-token" }) { configure ->
            HttpClient(MockEngine { request -> requests += request; respond(body, status, json) }) { configure(this) }
        }

    @Test fun switchingPutsTheModelAndReturnsTheSession() = runBlocking {
        client().use { client ->
            val changed = client.setCloudSessionModel("s1", "claude-sonnet-5-5")
            assertEquals("claude-sonnet-5-5", changed.agent.model)
            val request = requests.single()
            assertEquals(HttpMethod.Put, request.method)
            assertEquals("/api/v1/cloud/sessions/s1/model", request.url.encodedPath)
            assertEquals("claude-sonnet-5-5", Json.parseToJsonElement((request.body as TextContent).text).jsonObject["model"]!!.jsonPrimitive.content)
        }
    }

    @Test fun anIdentifierThatIsNotOneNeverLeavesTheClient() = runBlocking {
        client().use { client ->
            assertFailsWith<IllegalArgumentException> { client.setCloudSessionModel("../x", "claude-sonnet-5-5") }
            assertFailsWith<IllegalArgumentException> { client.cloudModels("gemini") }
            assertTrue(requests.isEmpty())
        }
    }

    @Test fun thePickersListIsReadPerAgent() = runBlocking {
        client(body = """{"agent":"claude","models":[{"id":"claude-opus-5-5","label":"Opus 5.5"}]}""").use { client ->
            assertEquals(listOf("claude-opus-5-5"), client.cloudModels("claude").models.map { it.id })
            assertEquals("agent=claude", requests.single().url.encodedQuery)
        }
    }

    @Test fun aRefusalSurfacesTheServersFailure() = runBlocking {
        client(HttpStatusCode.Forbidden, """{"error":{"type":"model_not_allowed","message":"This model is not allowed."}}""").use { client ->
            assertEquals("model_not_allowed", assertFailsWith<TeamCodeException> { client.setCloudSessionModel("s1", "gpt-6-sol") }.code)
        }
    }
}
