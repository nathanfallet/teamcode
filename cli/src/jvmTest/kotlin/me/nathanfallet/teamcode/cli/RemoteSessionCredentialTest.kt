package me.nathanfallet.teamcode.cli

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import me.nathanfallet.teamcode.api.models.RunnerInferenceCredentialView
import me.nathanfallet.teamcode.cli.cloud.RunnerInference
import me.nathanfallet.teamcode.cli.platform.REMOTE_AGENT_SCRUBBED_PREFIXES
import me.nathanfallet.teamcode.cli.platform.startAgentProcess
import me.nathanfallet.teamcode.cli.proxy.localProxy
import me.nathanfallet.teamcode.client.TeamCodeClient
import kotlin.test.*

/**
 * A remote session's host relays the agent's artifact, wait and workflow calls with the credential confined to its session,
 * never with the member's login, and its agent process never inherits a TeamCode variable.
 */
class RemoteSessionCredentialTest {
    private val localToken = "synthetic-local-token-long-enough-for-tests"
    private val login = "member-login-token"
    private val confined = "tcex_confined-session-credential"

    private class Upstream { val calls = mutableListOf<Triple<String, String, String?>>() }

    private fun client(upstream: Upstream, token: String) = TeamCodeClient("https://team.example", { token }) { configure ->
        HttpClient(MockEngine { request ->
            upstream.calls += Triple(request.method.value, request.url.encodedPath, request.headers[HttpHeaders.Authorization])
            respond("""{"waits":[]}""", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }) { configure(this) }
    }

    @Test fun theRelaysUseTheConfinedCredentialAndTheMemberLoginStaysWithTheRest() = testApplication {
        val upstream = Upstream()
        val member = client(upstream, login); val session = client(upstream, confined)
        application { localProxy(member, localToken, sessionClient = session) }
        try {
            for (path in listOf("/api/v1/waits", "/api/v1/artifacts", "/api/v1/workflows/session/pending")) {
                assertEquals(HttpStatusCode.OK, client.get(path) { bearerAuth(localToken) }.status, path)
            }
            assertEquals(HttpStatusCode.OK, client.post("/api/v1/waits/w-1/cancel") { bearerAuth(localToken) }.status)
            assertEquals(4, upstream.calls.size)
            assertTrue(upstream.calls.all { it.third == "Bearer $confined" }, "Every relayed call carries the confined credential, never the member's login: ${upstream.calls}")
            // The answer route is still not relayed at all, whatever the credential.
            val before = upstream.calls.size
            assertEquals(HttpStatusCode.NotFound, client.post("/api/v1/waits/w-1/answer") { bearerAuth(localToken); contentType(ContentType.Application.Json); setBody("{}") }.status)
            assertEquals(before, upstream.calls.size, "The answer route never reaches the server")
            // A caller without the local token reaches nothing.
            assertEquals(HttpStatusCode.Unauthorized, client.get("/api/v1/waits").status)
            assertEquals(before, upstream.calls.size)
        } finally { member.close(); session.close() }
    }

    @Test fun withoutASessionCredentialTheRelaysKeepTheProxyLoginAsBefore() = testApplication {
        val upstream = Upstream()
        val member = client(upstream, login)
        application { localProxy(member, localToken) }
        try {
            assertEquals(HttpStatusCode.OK, client.get("/api/v1/waits") { bearerAuth(localToken) }.status)
            assertEquals("Bearer $login", upstream.calls.single().third)
        } finally { member.close() }
    }

    @Test fun anUnavailableSessionCredentialSendsNoLoginAtAll() = testApplication {
        val upstream = Upstream()
        val member = client(upstream, login)
        val engine = MockEngine { request ->
            upstream.calls += Triple(request.method.value, request.url.encodedPath, request.headers[HttpHeaders.Authorization])
            respond("{}", HttpStatusCode.Unauthorized)
        }
        val session = TeamCodeClient("https://team.example", { null }) { configure -> HttpClient(engine) { configure(this) } }
        application { localProxy(member, localToken, sessionClient = session) }
        try {
            val answer = client.get("/api/v1/waits") { bearerAuth(localToken) }
            assertEquals(HttpStatusCode.BadGateway, answer.status, "Fail closed: the relay does not fall back to the member's login")
            assertEquals(listOf<String?>(null), upstream.calls.map { it.third })
        } finally { member.close(); session.close() }
    }

    @Test fun theHostRenewsTheSessionCredentialAndAsksForItOnceUntilRenewalIsDue() = runBlocking {
        var fetches = 0
        var now = 0L
        val logs = mutableListOf<String>()
        val held = RunnerInference({ fetches++; RunnerInferenceCredentialView("tcex_session-$fetches", 0, 3_600_000) }, log = { logs += it }, what = "session credential", now = { now })
        assertEquals("tcex_session-1", held.token()); assertEquals("tcex_session-1", held.token()); assertEquals(1, fetches)
        now = 1_300_000
        assertEquals("tcex_session-2", held.token())
        held.clear()
        val refused = RunnerInference({ throw me.nathanfallet.teamcode.client.TeamCodeException(404, "Not found.", "not_found") }, log = { logs += it }, what = "session credential", now = { now })
        assertNull(refused.token()); assertTrue(refused.unsupported)
        assertEquals(listOf("This server does not issue a session credential to this host."), logs)
    }

    @Test fun theAgentProcessDoesNotInheritTeamCodeVariables() = runBlocking {
        val sentinel = System.getenv("TEAMCODE_SCRUB_SENTINEL") ?: return@runBlocking // set by the build for jvmTest
        val script = "echo \"sentinel=\${TEAMCODE_SCRUB_SENTINEL:-none} own=\${TEAMCODE_WAITS_TOKEN:-none} path=\${PATH:+kept}\""
        suspend fun run(scrub: List<String>, overrides: Map<String, String?> = emptyMap()): String {
            val process = startAgentProcess("/bin/sh", listOf("-c", script), overrides, "/tmp", scrub)
            val line = process.readLine()!!
            process.stop(5_000)
            return line
        }
        assertEquals("sentinel=$sentinel own=none path=kept", run(emptyList()), "Control: without the scrub the child inherits it")
        assertEquals("sentinel=none own=none path=kept", run(REMOTE_AGENT_SCRUBBED_PREFIXES), "A remote session's agent inherits no TeamCode variable, and keeps the rest")
        assertEquals("sentinel=none own=per-launch-token path=kept", run(REMOTE_AGENT_SCRUBBED_PREFIXES, mapOf("TEAMCODE_WAITS_TOKEN" to "per-launch-token")),
            "The per-relay token TeamCode sets itself still reaches the agent")
    }

    @Test fun aServerWithTheCapabilityIssuesTheCredentialToTheHostAndAnOlderOneKeepsTheLogin() = runBlocking {
        val seen = mutableListOf<Triple<String, String, String?>>()
        var body = ""
        val member = TeamCodeClient("https://team.example", { login }) { configure ->
            HttpClient(MockEngine { request ->
                seen += Triple(request.method.value, request.url.encodedPath, request.headers[HttpHeaders.Authorization])
                body = (request.body as? io.ktor.http.content.TextContent)?.text.orEmpty()
                respond("""{"token":"$confined","expiresAt":9,"expiresInMillis":3600000}""", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
            }) { configure(this) }
        }
        try {
            val features = listOf("cloud.sessions.remote.v1", me.nathanfallet.teamcode.cli.remote.SESSION_CREDENTIAL_CAPABILITY)
            assertNull(me.nathanfallet.teamcode.cli.remote.remoteSessionCredential(member, listOf("cloud.sessions.remote.v1"), "t", "s-1", 3, {}), "An older server: no credential, the relays keep the login")
            assertEquals(emptyList(), seen)
            val held = assertNotNull(me.nathanfallet.teamcode.cli.remote.remoteSessionCredential(member, features, "t", "s-1", 3, {}))
            assertEquals(confined, held.token())
            assertEquals(Triple("POST", "/api/v1/cloud/runner/session-credential", "Bearer $login"), seen.single(), "Asked of the runner channel with the host's own login, once")
            assertContains(body, "\"epoch\":3")
        } finally { member.close() }
    }
}
