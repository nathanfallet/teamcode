package me.nathanfallet.teamcode.client

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.*
import io.ktor.http.content.TextContent
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import me.nathanfallet.teamcode.api.models.*
import kotlin.test.*

class SessionRenameClientTest {
    private val requests = mutableListOf<HttpRequestData>()
    private val json = headersOf(HttpHeaders.ContentType, "application/json")
    private val session = """{"id":"s1","name":"New name","ownerId":"m1","createdBy":{"initiatorId":"m1","credentialId":"c","effectiveMemberId":"m1"},
        "state":"active","desiredState":"running","generation":2,"currentEpoch":1,"profile":{"id":"p1","version":1},"image":{"reference":"img"},
        "agent":{"kind":"claude"},"activity":{"lastActivityAt":42,"idleTimeoutMinutes":30},"resume":{"workspace":"w","conversation":"c"},
        "workspaceDeletion":{"state":"none"},"createdAt":1,"updatedAt":2}"""

    private fun client(status: HttpStatusCode = HttpStatusCode.OK, body: String = session) =
        TeamCodeClient("https://team.example", { "synthetic-token" }) { configure ->
            HttpClient(MockEngine { request -> requests += request; respond(body, status, json) }) { configure(this) }
        }

    @Test fun renamingPatchesTheSessionWithTheNewNameAndReturnsIt() = runBlocking {
        client().use { client ->
            val renamed = client.renameCloudSession("s1", "New name", onBehalfOf = "m1")
            assertEquals("New name", renamed.name)
            val request = requests.single()
            assertEquals(HttpMethod.Patch, request.method)
            assertEquals("/api/v1/cloud/sessions/s1", request.url.encodedPath)
            assertEquals("m1", request.headers[CloudHeaders.ON_BEHALF_OF])
            assertEquals("New name", Json.parseToJsonElement((request.body as TextContent).text).jsonObject["name"]!!.jsonPrimitive.content)
        }
    }

    @Test fun anIdentifierThatIsNotOneNeverLeavesTheClient() = runBlocking {
        client().use { client ->
            assertFailsWith<IllegalArgumentException> { client.renameCloudSession("../x", "Name") }
            assertTrue(requests.isEmpty())
        }
    }

    @Test fun aRefusalSurfacesTheServersFailure() = runBlocking {
        client(HttpStatusCode.BadRequest, """{"error":{"type":"invalid_session","message":"Use a session name of 1–120 characters without control characters."}}""").use { client ->
            val failure = assertFailsWith<TeamCodeException> { client.renameCloudSession("s1", "") }
            assertEquals("invalid_session", failure.code)
        }
    }
}
