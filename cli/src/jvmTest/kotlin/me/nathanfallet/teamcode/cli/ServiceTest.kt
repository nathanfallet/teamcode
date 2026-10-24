package me.nathanfallet.teamcode.cli

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.testApplication
import me.nathanfallet.teamcode.cli.proxy.SERVICE_CHALLENGE_HEADER
import me.nathanfallet.teamcode.cli.proxy.localProxy
import me.nathanfallet.teamcode.cli.proxy.serviceProof
import me.nathanfallet.teamcode.cli.service.*
import me.nathanfallet.teamcode.client.TeamCodeClient
import java.io.ByteArrayInputStream
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.*
import kotlinx.serialization.json.*

class ServiceTest {
    private val token = "b".repeat(64)
    private fun settings(os: String) = ServiceSettings("a".repeat(32), 8789, token,
        "/home/user/Team & Code/\"100%\$x\"", "/home/user/config dir/config.json", "/home/user/service", os, "501")

    @Test fun backgroundLaunchRequiresAnAuthenticatedMatchingServiceWithWorkloadSupport() {
        val old = buildJsonObject { put("id", "installation"); put("serverUrl", "https://team.example") }
        val current = JsonObject(old + ("capabilities" to JsonArray(listOf(JsonPrimitive("workload-routing-v1")))))
        assertTrue(serviceMatches(old, "installation", "https://team.example", false))
        assertFalse(serviceMatches(old, "installation", "https://team.example", true))
        assertTrue(serviceMatches(current, "installation", "https://team.example", true))
        assertFalse(serviceMatches(current, "another-installation", "https://team.example", true))
        assertFalse(serviceMatches(current, "installation", "https://other-team.example", true))
        assertFalse(serviceMatches(current, "installation", "https://team.example", true, runRouting = true))
        val registered = JsonObject(old + ("capabilities" to JsonArray(listOf(JsonPrimitive("workload-routing-v1"), JsonPrimitive("run-routing-v1")))))
        assertTrue(serviceMatches(registered, "installation", "https://team.example", true, runRouting = true))
        assertFalse(serviceMatches(registered, "another-installation", "https://team.example", true, runRouting = true))
        assertFalse(serviceMatches(current, "installation", "https://team.example", false, mcpRelay = true), "A service without the MCP relay is not used for MCP launches")
        assertFalse(serviceMatches(current, "installation", "https://team.example", false, localTokenHeader = true), "An older service cannot serve a Claude Code that keeps its login")
        assertTrue(serviceMatches(JsonObject(old + ("capabilities" to JsonArray(listOf(JsonPrimitive("local-token-header-v1"))))), "installation", "https://team.example", false, localTokenHeader = true))
        val relay = JsonObject(old + ("capabilities" to JsonArray(listOf(JsonPrimitive("workload-routing-v1"), JsonPrimitive("mcp-relay-v1")))))
        assertTrue(serviceMatches(relay, "installation", "https://team.example", true, mcpRelay = true))
    }

    @Test fun manifestsPreserveLiteralPathsWithoutExposingCredentials() {
        val mac = settings("macos")
        val factory = DocumentBuilderFactory.newInstance().apply {
            setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
        }
        val document = factory.newDocumentBuilder().parse(ByteArrayInputStream(serviceDefinition(mac).toByteArray()))
        val strings = document.getElementsByTagName("array").item(0).childNodes
        val arguments = (0 until strings.length).map { strings.item(it).textContent }
        assertEquals(listOf(mac.executable, "service", "serve", "--config", mac.config), arguments)
        val linux = serviceDefinition(settings("linux"))
        assertTrue(linux.contains("100%%\$\$x"))
        assertTrue(linux.contains("WantedBy=default.target"))
        assertTrue(linux.contains("TimeoutStopSec=660"))
        assertFalse(linux.contains(token))
        assertFalse(serviceDefinition(mac).contains(token))
        assertFalse(mac.toString().contains(token))
    }

    @Test fun serviceIdentityRequiresLocalCapabilityAndRejectsBrowserAccess() = testApplication {
        val sdk = TeamCodeClient("https://team.example", { "server-secret" }) { config ->
            HttpClient(MockEngine { fail("Service identity should not call upstream") }) { config(this) }
        }
        application { localProxy(sdk, token, serviceId = "installation", serverUrl = "https://team.example") }
        try {
            assertEquals(HttpStatusCode.Unauthorized, client.get("/_teamcode/service").status)
            assertEquals(HttpStatusCode.Forbidden, client.get("/_teamcode/service") { bearerAuth(token); header(HttpHeaders.Origin, "https://browser.example") }.status)
            val response = client.get("/_teamcode/service") { bearerAuth(token) }
            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals("no-store", response.headers[HttpHeaders.CacheControl])
            assertTrue(response.bodyAsText().contains("installation"))
            assertFalse(response.bodyAsText().contains("server-secret"))
            assertFalse(response.bodyAsText().contains(token))
        } finally { sdk.close() }
    }

    /** Audit L9: the probe sends a challenge, never the token, and only the service can answer it. */
    @Test fun serviceIdentityAnswersAChallengeWithoutTheTokenBeingSent() = testApplication {
        val sdk = TeamCodeClient("https://team.example", { "server-secret" }) { config ->
            HttpClient(MockEngine { fail("Service identity should not call upstream") }) { config(this) }
        }
        application { localProxy(sdk, token, serviceId = "installation", serverUrl = "https://team.example") }
        try {
            val challenge = "c".repeat(64)
            val response = client.get("/_teamcode/service") { header(SERVICE_CHALLENGE_HEADER, challenge) }
            assertEquals(HttpStatusCode.OK, response.status)
            val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            val proof = body["proof"]!!.jsonPrimitive.content
            assertEquals(serviceProof(token, challenge, "installation", "https://team.example"), proof)
            assertNotEquals(serviceProof("x".repeat(64), challenge, "installation", "https://team.example"), proof, "The proof depends on the token")
            assertNotEquals(serviceProof(token, "d".repeat(64), "installation", "https://team.example"), proof, "on the challenge")
            assertNotEquals(serviceProof(token, challenge, "other", "https://team.example"), proof, "on the installation")
            assertNotEquals(serviceProof(token, challenge, "installation", "https://other.example"), proof, "and on the origin")
            // Without the token, the answer names neither the installation nor the server.
            assertFalse(response.bodyAsText().contains(token)); assertFalse(response.bodyAsText().contains("installation")); assertFalse(response.bodyAsText().contains("team.example"))
            // A malformed challenge is no credential, and browsers stay refused.
            assertEquals(HttpStatusCode.Unauthorized, client.get("/_teamcode/service") { header(SERVICE_CHALLENGE_HEADER, "short") }.status)
            assertEquals(HttpStatusCode.Forbidden, client.get("/_teamcode/service") { header(SERVICE_CHALLENGE_HEADER, challenge); header(HttpHeaders.Origin, "https://browser.example") }.status)
        } finally { sdk.close() }
    }

    @Test fun theProbeRecognizesTheRealServiceByItsProof() = kotlinx.coroutines.runBlocking {
        val sdk = TeamCodeClient("https://team.example", { "server-secret" }) { config ->
            HttpClient(MockEngine { fail("Service identity should not call upstream") }) { config(this) }
        }
        val server = me.nathanfallet.teamcode.cli.proxy.proxyServer(sdk, token, 0, "a".repeat(32), "https://team.example", report = {})
        try {
            server.start(wait = false)
            val port = server.engine.resolvedConnectors().single().port
            fun settings(localToken: String) = ServiceSettings("a".repeat(32), port, localToken, "/usr/local/bin/teamcode", "/home/user/config.json", "/home/user/service", "linux", "501")
            assertTrue(serviceResponds(settings(token), "https://team.example"))
            assertFalse(serviceResponds(settings("e".repeat(64)), "https://team.example"), "A service holding another token is not this one")
        } finally { server.stop(100, 1_000); sdk.close() }
    }

    /** Audit L9: a process squatting the service's port while it is down never receives the token, and is not taken for the service. */
    @Test fun aPortSquatterNeverReceivesTheLocalToken() = kotlinx.coroutines.runBlocking {
        val seen = mutableListOf<String>()
        val squatter = com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress(java.net.InetAddress.getLoopbackAddress(), 0), 0)
        squatter.createContext("/") { exchange ->
            seen += exchange.requestHeaders.entries.joinToString("\n") { "${it.key}: ${it.value.joinToString(",")}" } + exchange.requestURI
            val answer = """{"id":"${"a".repeat(32)}","serverUrl":"https://team.example","proof":"${"0".repeat(64)}","capabilities":[]}""".toByteArray()
            exchange.sendResponseHeaders(200, answer.size.toLong()); exchange.responseBody.use { it.write(answer) }
        }
        squatter.start()
        try {
            val settings = ServiceSettings("a".repeat(32), squatter.address.port, token, "/usr/local/bin/teamcode", "/home/user/config.json", "/home/user/service", "linux", "501")
            assertFalse(serviceResponds(settings, "https://team.example"), "A wrong proof is not the service")
            assertEquals(1, seen.size)
            assertFalse(seen.single().contains(token), "The token never leaves the probe")
            assertFalse(seen.single().contains("Authorization", ignoreCase = true))
        } finally { squatter.stop(0) }
    }

    /** A rollback waits for an older service, which answers the challenge with 401: only then does the probe send the token. */
    @Test fun anOlderServiceIsRecognizedOnlyWhileWaitingForItsVersion() = kotlinx.coroutines.runBlocking {
        val bearers = mutableListOf<String?>()
        val older = com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress(java.net.InetAddress.getLoopbackAddress(), 0), 0)
        older.createContext("/_teamcode/service") { exchange ->
            val bearer = exchange.requestHeaders.getFirst("Authorization")
            bearers += bearer
            if (bearer != "Bearer $token") { exchange.sendResponseHeaders(401, -1); exchange.close(); return@createContext }
            val answer = """{"id":"${"a".repeat(32)}","serverUrl":"https://team.example","version":"0.9.0","capabilities":[]}""".toByteArray()
            exchange.sendResponseHeaders(200, answer.size.toLong()); exchange.responseBody.use { it.write(answer) }
        }
        older.start()
        try {
            val settings = ServiceSettings("a".repeat(32), older.address.port, token, "/usr/local/bin/teamcode", "/home/user/config.json", "/home/user/service", "linux", "501")
            assertFalse(serviceResponds(settings, "https://team.example"))
            assertEquals(listOf<String?>(null), bearers, "No token when no older version is awaited")
            assertTrue(serviceResponds(settings, "https://team.example", expectedVersion = "0.9.0"))
            assertEquals(listOf(null, null, "Bearer $token"), bearers)
            // A version newer than this binary answers the challenge: waiting for it never sends the token.
            assertFalse(serviceResponds(settings, "https://team.example", expectedVersion = "999.0.0"))
            assertFalse(serviceResponds(settings, "https://team.example", expectedVersion = "not-a-version"))
            assertEquals(listOf(null, null, "Bearer $token", null, null), bearers)
        } finally { older.stop(0) }
    }

    @Test fun olderReleaseComparesMajorMinorPatch() {
        assertTrue(olderRelease("0.9.0", "1.0.0")); assertTrue(olderRelease("1.2.3", "1.2.10")); assertTrue(olderRelease("v1.2.3", "1.3.0-beta"))
        assertFalse(olderRelease("1.2.3", "1.2.3")); assertFalse(olderRelease("1.10.0", "1.9.9")); assertFalse(olderRelease("2.0.0", "1.0.0"))
        assertFalse(olderRelease("1.2", "1.3.0")); assertFalse(olderRelease("1.2.x", "1.3.0")); assertFalse(olderRelease("0.1.0", "unknown"))
        assertTrue(olderRelease("1.2.3-rc1", "1.2.3")); assertFalse(olderRelease("1.2.3", "1.2.3-rc1")); assertFalse(olderRelease("1.2.3-rc1", "1.2.3-rc2"))
    }

    @Test fun windowsTaskUsesTheCurrentUserWithoutEmbeddingCredentialsOrRequiringElevation() {
        val windows = ServiceSettings("a".repeat(32), 8789, token, "C:\\Program Files\\TeamCode\\teamcode.exe",
            "C:\\Users\\équipe\\.config\\teamcode\\config.json", "C:\\Users\\équipe\\.config\\teamcode\\service-task.xml", "windows", "S-1-5-21-123-456-789-1001")
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(ByteArrayInputStream(registrationBytes(windows)))
        assertEquals(windows.executable, document.getElementsByTagName("Command").item(0).textContent)
        assertEquals("\"service\" \"serve\" \"--config\" \"${windows.config}\"", document.getElementsByTagName("Arguments").item(0).textContent)
        assertEquals("InteractiveToken", document.getElementsByTagName("LogonType").item(0).textContent)
        assertEquals("LeastPrivilege", document.getElementsByTagName("RunLevel").item(0).textContent)
        assertEquals("IgnoreNew", document.getElementsByTagName("MultipleInstancesPolicy").item(0).textContent)
        assertEquals("false", document.getElementsByTagName("AllowHardTerminate").item(0).textContent)
        assertFalse(serviceDefinition(windows).contains(token))
    }
}
