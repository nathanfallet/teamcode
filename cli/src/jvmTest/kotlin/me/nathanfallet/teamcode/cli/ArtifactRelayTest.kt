package me.nathanfallet.teamcode.cli

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.*
import me.nathanfallet.teamcode.cli.proxy.localProxy
import me.nathanfallet.teamcode.cli.service.serviceMatches
import me.nathanfallet.teamcode.client.TeamCodeClient
import kotlin.test.*

class ArtifactRelayTest {
    private val localToken = "synthetic-local-token-long-enough-for-tests"
    private val id = "00000000-0000-4000-8000-0000000000aa"
    private val run = "11111111-2222-4333-8444-555555555555"
    private val sha = "a".repeat(64)

    @Test fun relaysOnlyTheListedArtifactCallsWithTheProxyCredential() = testApplication {
        val seen = mutableListOf<String>()
        val sdk = TeamCodeClient("https://team.example", { "device-credential" }) { config -> HttpClient(MockEngine { request ->
            assertEquals("Bearer device-credential", request.headers[HttpHeaders.Authorization], "The local token is replaced by the proxy's credential")
            seen += "${request.method.value} ${request.url.encodedPath}${request.url.encodedQuery.let { if (it.isEmpty()) "" else "?$it" }} run=${request.headers["x-teamcode-run-id"]}"
            when (request.url.encodedPath) {
                "/api/v1/artifacts/$id/versions/2/file" -> respond("page", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType to listOf("application/octet-stream"),
                    "X-TeamCode-Sha256" to listOf(sha), HttpHeaders.ContentDisposition to listOf("attachment")))
                "/api/v1/artifacts/$id/members" -> respond("""{"error":{"type":"artifact_not_writable","message":"No."}}""", HttpStatusCode.Forbidden,
                    headersOf(HttpHeaders.ContentType, "application/json"))
                "/api/v1/artifacts/policy" -> respond("""{"error":{"type":"unauthorized","message":"No."}}""", HttpStatusCode.Unauthorized, headersOf(HttpHeaders.ContentType, "application/json"))
                else -> respond("""{"ok":true}""", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType to listOf("application/json"), HttpHeaders.RetryAfter to listOf("7")))
            }
        }) { config(this) } }
        application { localProxy(sdk, localToken) }
        try {
            suspend fun call(method: HttpMethod, path: String, token: String? = localToken, origin: String? = null, runs: List<String> = listOf(run), body: String? = null) =
                client.request(path) {
                    this.method = method; token?.let { bearerAuth(it) }; origin?.let { header(HttpHeaders.Origin, it) }
                    runs.forEach { header("x-teamcode-run-id", it) }
                    if (body != null) { contentType(ContentType.Application.Json); setBody(body) }
                }

            // Listed calls pass with their query, body, run and the headers callers read.
            val listed = call(HttpMethod.Get, "/api/v1/artifacts?scope=mine&limit=5")
            assertEquals(HttpStatusCode.OK, listed.status); assertEquals("7", listed.headers[HttpHeaders.RetryAfter]); assertEquals("no-store", listed.headers[HttpHeaders.CacheControl])
            val file = call(HttpMethod.Get, "/api/v1/artifacts/$id/versions/2/file?path=index.html")
            assertEquals("page", file.bodyAsText()); assertEquals(sha, file.headers["X-TeamCode-Sha256"]); assertEquals("attachment", file.headers[HttpHeaders.ContentDisposition])
            assertEquals(HttpStatusCode.OK, call(HttpMethod.Post, "/api/v1/artifacts/uploads", body = """{"files":[]}""").status)
            assertEquals(HttpStatusCode.OK, client.put("/api/v1/artifacts/uploads/$id/blobs/$sha/0") { bearerAuth(localToken); contentType(ContentType.Application.OctetStream); setBody(byteArrayOf(1, 2)) }.status)
            assertEquals(HttpStatusCode.OK, call(HttpMethod.Post, "/api/v1/artifacts/$id/versions", body = "{}").status)
            assertEquals(HttpStatusCode.OK, call(HttpMethod.Delete, "/api/v1/artifacts/$id/members/bob", runs = emptyList()).status)
            // A business refusal reaches the caller with its code; a refused sign-in becomes an explicit 502.
            val refused = call(HttpMethod.Post, "/api/v1/artifacts/$id/members", body = """{"member":"bob"}""")
            assertEquals(HttpStatusCode.Forbidden, refused.status); assertTrue("artifact_not_writable" in refused.bodyAsText())
            val signIn = call(HttpMethod.Get, "/api/v1/artifacts/policy")
            assertEquals(HttpStatusCode.BadGateway, signIn.status); assertTrue("teamcode login" in signIn.bodyAsText())
            val relayed = seen.size

            // Making public and deleting are not relayed, nor anything off the list; neither are callers without the token.
            assertEquals(HttpStatusCode.NotFound, call(HttpMethod.Put, "/api/v1/artifacts/$id/access", body = """{"access":"public"}""").status)
            assertEquals(HttpStatusCode.NotFound, call(HttpMethod.Delete, "/api/v1/artifacts/$id").status)
            assertEquals(HttpStatusCode.NotFound, call(HttpMethod.Get, "/api/v1/artifacts/storage").status)
            assertEquals(HttpStatusCode.NotFound, call(HttpMethod.Get, "/api/v1/artifacts/$id/../storage").status)
            assertEquals(HttpStatusCode.Unauthorized, call(HttpMethod.Get, "/api/v1/artifacts", token = null).status)
            assertEquals(HttpStatusCode.Unauthorized, call(HttpMethod.Get, "/api/v1/artifacts", token = "x".repeat(43)).status)
            assertEquals(HttpStatusCode.Forbidden, call(HttpMethod.Get, "/api/v1/artifacts", origin = "https://evil.example").status)
            assertEquals(HttpStatusCode.BadRequest, call(HttpMethod.Get, "/api/v1/artifacts", runs = listOf("not-a-run")).status)
            assertEquals(HttpStatusCode.BadRequest, call(HttpMethod.Get, "/api/v1/artifacts", runs = listOf(run, run)).status)
            assertEquals(relayed, seen.size, "Refused calls never reach the server")
            assertEquals("GET /api/v1/artifacts?scope=mine&limit=5 run=$run", seen.first())
            assertTrue("DELETE /api/v1/artifacts/$id/members/bob run=null" in seen)
        } finally { sdk.close() }
    }

    @Test fun anUnreachableServerIsARetryableBadGateway() = testApplication {
        val sdk = TeamCodeClient("https://team.example", { "device-credential" }) { config -> HttpClient(MockEngine { throw java.io.IOException("down") }) { config(this) } }
        application { localProxy(sdk, localToken) }
        try {
            val answer = client.get("/api/v1/artifacts/policy") { bearerAuth(localToken) }
            assertEquals(HttpStatusCode.BadGateway, answer.status); assertEquals("5", answer.headers[HttpHeaders.RetryAfter])
        } finally { sdk.close() }
    }

    @Test fun theServiceAnnouncesTheRelayAndLaunchesRequireIt() = testApplication {
        val sdk = TeamCodeClient("https://team.example", { "device-credential" }) { config -> HttpClient(MockEngine { respondOk() }) { config(this) } }
        application { localProxy(sdk, localToken, serviceId = "installation", serverUrl = "https://team.example") }
        try {
            val body = Json.parseToJsonElement(client.get("/_teamcode/service") { bearerAuth(localToken) }.bodyAsText()).jsonObject
            assertTrue(JsonPrimitive("artifact-relay-v1") in body["capabilities"]!!.jsonArray)
            assertTrue(serviceMatches(body, "installation", "https://team.example", false, artifactRelay = true))
            val older = JsonObject(body + ("capabilities" to JsonArray(body["capabilities"]!!.jsonArray.filter { it != JsonPrimitive("artifact-relay-v1") })))
            assertFalse(serviceMatches(older, "installation", "https://team.example", false, artifactRelay = true), "An older service without the relay gets a dedicated proxy")
            assertTrue(serviceMatches(older, "installation", "https://team.example", false))
        } finally { sdk.close() }
    }
}
