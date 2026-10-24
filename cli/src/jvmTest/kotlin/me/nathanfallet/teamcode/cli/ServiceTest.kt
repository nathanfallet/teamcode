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
            assertEquals(serviceProof(token, challenge, "installation"), body["proof"]!!.jsonPrimitive.content)
            assertNotEquals(serviceProof("x".repeat(64), challenge, "installation"), body["proof"]!!.jsonPrimitive.content, "The proof depends on the token")
            assertNotEquals(serviceProof(token, "d".repeat(64), "installation"), body["proof"]!!.jsonPrimitive.content, "and on the challenge")
            assertFalse(response.bodyAsText().contains(token))
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
