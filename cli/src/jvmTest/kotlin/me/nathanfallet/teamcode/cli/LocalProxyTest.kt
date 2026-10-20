package me.nathanfallet.teamcode.cli

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.testApplication
import me.nathanfallet.teamcode.cli.proxy.localProxy
import me.nathanfallet.teamcode.client.TeamCodeClient
import kotlin.test.*

class LocalProxyTest {
    private val localToken = "synthetic-local-token-long-enough-for-tests"
    private fun payload() = """{"model":"fixture","stream":true,"messages":[]}"""

    @Test fun registeredLaunchPathsIsolateSharedServiceMetadataAndRejectConflictingIds() = testApplication {
        val id = "11111111-1111-1111-1111-111111111111"
        val other = "22222222-2222-2222-2222-222222222222"
        var calls = 0
        val sdk = TeamCodeClient("https://team.example", { "server-token" }) { config -> HttpClient(MockEngine { request ->
            calls++; assertTrue(request.headers["x-teamcode-run-id"] in setOf(id, other))
            assertEquals(if (request.headers["x-teamcode-run-id"] == id) "background" else "interactive", request.headers["x-teamcode-workload"])
            assertTrue(request.url.encodedPath in setOf("/v1/messages", "/v1/responses"))
            respond("data: {}\n\n", headers = headersOf(HttpHeaders.ContentType, "text/event-stream"))
        }) { config(this) } }
        application { localProxy(sdk, localToken) }
        try {
            for (path in listOf("/v1/messages", "/v1/responses")) {
                suspend fun request(run: String = id, ids: List<String> = emptyList(), workload: String? = null, pathWorkload: String = "background") = client.post("/_teamcode/runs/$run/$pathWorkload$path") {
                    bearerAuth(localToken); ids.forEach { header("x-teamcode-run-id", it) }; workload?.let { header("x-teamcode-workload", it) }
                    contentType(ContentType.Application.Json); setBody(payload())
                }
                assertEquals(HttpStatusCode.OK, request().status)
                assertEquals(HttpStatusCode.OK, request(run = other, pathWorkload = "interactive").status)
                assertEquals(HttpStatusCode.BadRequest, request(ids = listOf(other)).status)
                assertEquals(HttpStatusCode.BadRequest, request(ids = listOf(id, id)).status)
                assertEquals(HttpStatusCode.BadRequest, request(run = "invalid").status)
                assertEquals(HttpStatusCode.BadRequest, request(workload = "interactive").status)
            }
            assertEquals(4, calls)
        } finally { sdk.close() }
    }

    @Test fun `local gateway credential replaces caller auth and SSE bytes are preserved`() = testApplication {
        var calls = 0
        val engine = MockEngine { request ->
            calls++
            assertEquals("Bearer server-token", request.headers[HttpHeaders.Authorization])
            assertNull(request.headers["x-api-key"])
            assertEquals("codex-session", request.headers["session-id"])
            assertEquals("/v1/messages", request.url.encodedPath)
            respond("event: done\ndata: {}\n\n", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "text/event-stream"))
        }
        val sdk = TeamCodeClient("https://self.hosted.example", { "server-token" }) { config -> HttpClient(engine) { config(this) } }
        application { localProxy(sdk, localToken) }
        try {
            val response = client.post("/v1/messages") {
                contentType(ContentType.Application.Json)
                header("x-api-key", localToken)
                header("session-id", "codex-session")
                setBody(payload())
            }
            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals("event: done\ndata: {}\n\n", response.bodyAsText())
            assertEquals(1, calls)
        } finally { sdk.close() }
    }

    @Test fun `unkeyed browser and oversized requests never reach upstream`() = testApplication {
        var calls = 0
        val sdk = TeamCodeClient("https://self.hosted.example", { "server-token" }) { config -> HttpClient(MockEngine {
            calls++; respond("unexpected")
        }) { config(this) } }
        application { localProxy(sdk, localToken, maximumBodyBytes = 64) }
        try {
            assertEquals(HttpStatusCode.Unauthorized, client.post("/v1/messages") {
                contentType(ContentType.Application.Json); setBody(payload())
            }.status)
            assertEquals(HttpStatusCode.Forbidden, client.post("/v1/messages") {
                header("x-api-key", localToken); header(HttpHeaders.Origin, "https://attacker.example")
                contentType(ContentType.Application.Json); setBody(payload())
            }.status)
            assertEquals(HttpStatusCode.PayloadTooLarge, client.post("/v1/messages") {
                header("x-api-key", localToken); contentType(ContentType.Application.Json); setBody("x".repeat(65))
            }.status)
            assertEquals(0, calls)
        } finally { sdk.close() }
    }

    @Test fun `shared proxy isolates background launches and rejects conflicting metadata`() = testApplication {
        val forwarded = mutableListOf<Pair<String, String?>>()
        val sdk = TeamCodeClient("https://team.example", { "server-token" }) { config -> HttpClient(MockEngine { request ->
            assertEquals("Bearer server-token", request.headers[HttpHeaders.Authorization])
            forwarded += request.url.encodedPath to request.headers["x-teamcode-workload"]
            respond("data: {}\n\n", headers = headersOf(HttpHeaders.ContentType, "text/event-stream"))
        }) { config(this) } }
        application { localProxy(sdk, localToken, serviceId = "shared", serverUrl = "https://team.example") }
        try {
            for (path in listOf("/v1/messages", "/v1/responses")) {
                val background = "/_teamcode/workloads/background$path"
                suspend fun request(url: String, workload: List<String> = emptyList(), authenticated: Boolean = true) = client.post(url) {
                    if (authenticated) bearerAuth(localToken)
                    workload.forEach { header("x-teamcode-workload", it) }
                    contentType(ContentType.Application.Json); setBody(payload())
                }
                assertEquals(HttpStatusCode.OK, request(background).status)
                assertEquals(HttpStatusCode.OK, request(path).status)
                assertEquals(listOf(path to "background", path to null), forwarded.takeLast(2))
                assertEquals(HttpStatusCode.BadRequest, request(background, listOf("interactive")).status)
                assertEquals(HttpStatusCode.BadRequest, request(background, listOf("background", "background")).status)
                assertEquals(HttpStatusCode.Unauthorized, request(background, authenticated = false).status)
            }
            assertEquals(4, forwarded.size, "Rejected requests must not reach the server")
            assertEquals(HttpStatusCode.NotFound, client.post("/_teamcode/workloads/urgent/v1/messages") { bearerAuth(localToken) }.status)
        } finally { sdk.close() }
    }
}
