package me.nathanfallet.teamcode.infrastructure.mcp

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import io.ktor.http.content.TextContent
import io.ktor.client.request.HttpResponseData
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import me.nathanfallet.teamcode.domain.mcp.*
import me.nathanfallet.teamcode.domain.team.TeamFailure
import me.nathanfallet.teamcode.domain.variants.*
import kotlin.test.*

/** The gateway's client of the MCP stdio host against a simulated host (Ktor's mock engine): wire format, errors, scrubbing, retries. */
class HostMcpUpstreamTest {
    private val token = "synthetic-host-token-" + "t".repeat(32)
    private val secret = "synthetic-\"stdio\"-secret"
    private val key = "a".repeat(64)
    private val limits = McpLimits(callTimeoutMillis = 2_000, listTimeoutMillis = 2_000, maxResponseBytes = 4_096, stdioStartTimeoutMillis = 3_000)
    private fun server(secrets: Map<String, String>? = mapOf("TOKEN" to secret)) = EffectiveMcpServer(
        "everything", "variant", "team", 4, 9,
        McpServerTransport.Stdio(StdioCommand(StdioLauncher.NPM, "fixture@1.0.0", listOf("--token", "\${TOKEN}")), 1_700_000_000_000, key),
        mapOf("MODE" to "plain"), secrets?.let(StdioEnvironment::encode),
    )

    private class Host(val answer: suspend MockRequestHandleScope.(path: String, body: JsonObject?) -> HttpResponseData) {
        val requests = CopyOnWriteArrayList<Pair<String, JsonObject?>>()
        val headers = CopyOnWriteArrayList<Headers>()
        val engine = MockEngine { request ->
            headers += request.headers
            val body = (request.body as? TextContent)?.text?.let { Json.parseToJsonElement(it).jsonObject }
            requests += request.url.encodedPath to body
            answer(request.url.encodedPath, body)
        }
    }
    private fun MockRequestHandleScope.json(text: String, status: HttpStatusCode = HttpStatusCode.OK) =
        respond(text, status, headersOf(HttpHeaders.ContentType, "application/json"))
    private fun upstream(host: Host) = HostMcpUpstream(HttpClient(host.engine), "http://teamcode-mcp:8090/", token)

    @Test fun anOperationCarriesTheWholeProcessDefinitionAndItsSecretsToTheHostOnly() = runBlocking {
        val host = Host { _, body ->
            when (body!!["op"]!!.jsonPrimitive.content) {
                "list" -> json("""{"result":{"tools":[{"name":"echo","description":"echoes $secret"},{"name":"bad name"},{"description":"no name"}]},"cold":true,"state":"ready"}""".replace("\"stdio\"", "\\\"stdio\\\""))
                else -> json("""{"result":{"content":[{"type":"text","text":"said ${secret.replace("\"", "\\\"")}"}],"isError":true},"cold":false,"state":"ready"}""")
            }
        }
        val upstream = upstream(host)
        assertTrue(upstream.hostsCommands)
        val tools = upstream.listTools(server(), limits)
        assertEquals(listOf("echo", "bad name"), tools.map { it.name }, "The gateway, not this client, filters tool names")
        assertFalse(tools.any { secret in it.definition }, "The client scrubs the process's secrets again")
        val result = upstream.callTool(server(), "echo", """{"text":"hi"}""", limits)
        assertTrue(result.isError)
        assertFalse(secret in result.result)
        assertTrue(result.result.contains("[REDACTED]"))
        assertEquals(result.result.toByteArray().size.toLong(), result.bytes)

        val (path, list) = host.requests[0]
        assertEquals("/v1/ops", path)
        assertEquals(key, list!!["key"]!!.jsonPrimitive.content)
        assertEquals(limits.listTimeoutMillis + limits.stdioStartTimeoutMillis, list["deadlineMs"]!!.jsonPrimitive.long, "A list may wait for the process to start")
        assertEquals(4_096, list["maxResponseBytes"]!!.jsonPrimitive.int)
        val spec = list["spec"]!!.jsonObject
        assertEquals("npm", spec["launcher"]!!.jsonPrimitive.content)
        assertEquals("fixture@1.0.0", spec["package"]!!.jsonPrimitive.content)
        assertEquals(listOf("--token", "\${TOKEN}"), spec["args"]!!.jsonArray.map { it.jsonPrimitive.content }, "The host substitutes \${NAME}, the server never does")
        assertEquals(mapOf("MODE" to "plain"), spec["env"]!!.jsonObject.mapValues { it.value.jsonPrimitive.content })
        assertEquals(mapOf("TOKEN" to secret), spec["secrets"]!!.jsonObject.mapValues { it.value.jsonPrimitive.content })
        assertEquals(1_700_000_000_000, spec["declaredAt"]!!.jsonPrimitive.long)
        val call = host.requests[1].second!!
        assertEquals("call", call["op"]!!.jsonPrimitive.content)
        assertEquals("echo", call["tool"]!!.jsonPrimitive.content)
        assertEquals(buildJsonObject { put("text", "hi") }, call["arguments"])
        assertEquals(limits.callTimeoutMillis + limits.stdioStartTimeoutMillis, call["deadlineMs"]!!.jsonPrimitive.long)
        for (headers in host.headers) {
            assertEquals("Bearer $token", headers[HttpHeaders.Authorization])
            assertEquals("1", headers["TeamCode-Host-Protocol"])
        }
        assertFailsWith<McpUpstreamFailure> { upstream.callTool(server(), "echo", "[1]", limits) }.also { assertEquals(McpOutcome.TOOL_ERROR, it.outcome) }
        assertEquals(2, host.requests.size, "Invalid arguments never reach the host")
        val noSecrets = upstream.listTools(server(secrets = null), limits)
        assertEquals(2, noSecrets.size)
        assertEquals(JsonObject(emptyMap()), host.requests.last().second!!["spec"]!!.jsonObject["secrets"])
    }

    @Test fun hostErrorCodesBecomeTheGatewayOutcomesWithoutASecret() = runBlocking {
        val cases = mapOf(
            "timeout" to McpOutcome.TIMEOUT, "too_large" to McpOutcome.TOO_LARGE, "tool_error" to McpOutcome.TOOL_ERROR,
            "start_failed" to McpOutcome.UPSTREAM_ERROR, "busy" to McpOutcome.UPSTREAM_ERROR, "backoff" to McpOutcome.UPSTREAM_ERROR,
            "capacity" to McpOutcome.UPSTREAM_ERROR, "protocol" to McpOutcome.UPSTREAM_ERROR, "invalid_spec" to McpOutcome.UPSTREAM_ERROR,
            "crashed" to McpOutcome.UPSTREAM_ERROR, "something_new" to McpOutcome.UPSTREAM_ERROR,
        )
        for ((code, outcome) in cases) {
            val upstream = upstream(Host { _, _ -> json("""{"error":{"code":"$code","message":"host says ${secret.replace("\"", "\\\"")}"},"cold":false,"state":"ready"}""") })
            val failure = assertFailsWith<McpUpstreamFailure>(code) { upstream.callTool(server(), "echo", null, limits) }
            assertEquals(outcome, failure.outcome, code)
            assertFalse(secret in failure.message!!, code)
        }
        // Refusals of the request itself: token or version mismatch, a stopping host.
        for ((status, code) in listOf(HttpStatusCode.Unauthorized to "unauthorized", HttpStatusCode.BadRequest to "unsupported_protocol", HttpStatusCode.ServiceUnavailable to "shutting_down")) {
            val upstream = upstream(Host { _, _ -> json("""{"error":{"code":"$code","message":"refused"}}""", status) })
            val failure = assertFailsWith<McpUpstreamFailure>(code) { upstream.callTool(server(), "echo", null, limits) }
            assertEquals(McpOutcome.UPSTREAM_ERROR, failure.outcome)
            assertTrue(failure.message!!.contains(if (code == "shutting_down") "restarting" else "same version"), failure.message)
        }
        // A proxy without the host behind it is an unreachable host (a list tries once more); a 200 that is not JSON is the host's fault.
        var proxied = 0
        val proxy = upstream(Host { _, _ -> proxied++; respond("<html>bad gateway</html>", HttpStatusCode.BadGateway) })
        assertEquals("The MCP host is unreachable (HTTP 502).", assertFailsWith<McpUpstreamFailure> { proxy.callTool(server(), "echo", null, limits) }.message)
        assertEquals(McpOutcome.UPSTREAM_ERROR, assertFailsWith<McpUpstreamFailure> { proxy.listTools(server(), limits) }.outcome)
        assertEquals(3, proxied)
        val garbled = upstream(Host { _, _ -> respond("not json", HttpStatusCode.OK) })
        assertEquals("The MCP host sent an invalid answer.", assertFailsWith<McpUpstreamFailure> { garbled.callTool(server(), "echo", null, limits) }.message)
        val large = upstream(Host { _, _ -> json("""{"result":{"content":[{"type":"text","text":"${"x".repeat(4_096 + 1024 * 1024)}"}]},"cold":false,"state":"ready"}""") })
        assertEquals(McpOutcome.TOO_LARGE, assertFailsWith<McpUpstreamFailure> { large.callTool(server(), "echo", null, limits) }.outcome)
        val hostless = HostMcpUpstream(HttpClient(MockEngine { throw IOException("connection refused") }), "http://teamcode-mcp:8090", token)
        val unreachable = assertFailsWith<McpUpstreamFailure> { hostless.callTool(server(), "echo", null, limits) }
        assertEquals("The MCP host is unreachable.", unreachable.message)
        val http = server().copy(transport = McpServerTransport.Http("https://tracker.example.test"))
        assertFailsWith<McpUpstreamFailure> { hostless.listTools(http, limits) }
        Unit
    }

    @Test fun aListIsTriedOnceMoreAfterAHostRestartButACallNeverIs() = runBlocking {
        var attempts = 0
        val host = Host { _, body ->
            attempts++
            when {
                attempts == 1 -> throw IOException("connection reset")
                body!!["op"]!!.jsonPrimitive.content == "list" -> json("""{"result":{"tools":[{"name":"echo"}]},"cold":true,"state":"ready"}""")
                else -> json("""{"error":{"code":"crashed","message":"The MCP server stopped."},"cold":false,"state":"idle"}""")
            }
        }
        val upstream = upstream(host)
        assertEquals(listOf("echo"), upstream.listTools(server(), limits).map { it.name })
        assertEquals(2, attempts)
        assertTrue(host.requests[1].second!!["deadlineMs"]!!.jsonPrimitive.long <= limits.listTimeoutMillis + limits.stdioStartTimeoutMillis)
        assertFailsWith<McpUpstreamFailure> { upstream.callTool(server(), "echo", null, limits) }
        assertEquals(3, attempts, "A tool call is not idempotent: never replayed")
        var lists = 0
        val alwaysDown = upstream(Host { _, _ -> lists++; json("""{"error":{"code":"shutting_down","message":"stopping"}}""", HttpStatusCode.ServiceUnavailable) })
        assertFailsWith<McpUpstreamFailure> { alwaysDown.listTools(server(), limits) }
        assertEquals(2, lists, "One more attempt only")
    }

    @Test fun aHostThatDoesNotAnswerInTimeIsATimeout() = runBlocking {
        val slow = upstream(Host { _, _ -> delay(60_000); json("{}") })
        val quick = McpLimits(callTimeoutMillis = 1_000, listTimeoutMillis = 1_000, stdioStartTimeoutMillis = 1_000)
        val started = System.nanoTime()
        assertEquals(McpOutcome.TIMEOUT, assertFailsWith<McpUpstreamFailure> { slow.callTool(server(), "echo", null, quick) }.outcome)
        assertTrue((System.nanoTime() - started) / 1_000_000 < 15_000)
    }

    @Test fun warmUpLaunchesInTheBackgroundOnceAMinuteAndNeverFails() = runBlocking {
        val host = Host { _, _ -> json("""{"key":"$key","state":"starting"}""", HttpStatusCode.Accepted) }
        val upstream = upstream(host)
        val http = server().copy(name = "tracker", transport = McpServerTransport.Http("https://tracker.example.test"))
        upstream.warm(listOf(server(), http))
        upstream.warm(listOf(server()))
        val deadline = System.currentTimeMillis() + 5_000
        while (host.requests.isEmpty() && System.currentTimeMillis() < deadline) delay(20)
        delay(200)
        assertEquals(listOf("/v1/warm"), host.requests.map { it.first }, "One warm-up per key and minute, none for an HTTPS server")
        val warm = host.requests.single().second!!
        assertEquals(key, warm["key"]!!.jsonPrimitive.content)
        assertEquals(secret, warm["spec"]!!.jsonObject["secrets"]!!.jsonObject["TOKEN"]!!.jsonPrimitive.content)
        HostMcpUpstream(HttpClient(MockEngine { throw IOException("down") }), "http://teamcode-mcp:8090", token).use { it.warm(listOf(server())) }
        upstream.close()
        // A refused warm-up leaves the key free: the next server list asks again instead of waiting a minute.
        val refusing = Host { _, _ -> json("""{"error":{"code":"capacity","message":"full"}}""", HttpStatusCode.ServiceUnavailable) }
        upstream(refusing).use { client ->
            client.warm(listOf(server()))
            val until = System.currentTimeMillis() + 5_000
            while (refusing.requests.isEmpty() && System.currentTimeMillis() < until) delay(20)
            delay(200)
            client.warm(listOf(server()))
            while (refusing.requests.size < 2 && System.currentTimeMillis() < until) delay(20)
            assertEquals(2, refusing.requests.size)
        }
    }

    @Test fun launchersComeFromTheHostStatusAndAnUnreachableHostIsA503() = runBlocking {
        var statuses = 0
        val host = Host { path, _ ->
            when (path) {
                "/v1/status" -> { statuses++; json("""{"version":"1","protocol":"1","launchers":["npm","future"],"maxProcesses":16,"draining":false,"processes":[]}""") }
                "/v1/retain" -> json("""{"stopped":2}""")
                else -> error(path)
            }
        }
        val upstream = upstream(host)
        assertEquals(setOf(StdioLauncher.NPM), upstream.launchers())
        assertEquals(setOf(StdioLauncher.NPM), upstream.launchers())
        assertEquals(1, statuses, "Cached for a minute")
        assertEquals(2, upstream.retain(listOf(key)))
        assertEquals(listOf(key), host.requests.last().second!!["keys"]!!.jsonArray.map { it.jsonPrimitive.content })
        val down = HostMcpUpstream(HttpClient(MockEngine { throw IOException("down") }), "http://teamcode-mcp:8090", token)
        val failure = assertFailsWith<TeamFailure> { down.launchers() }
        assertEquals("mcp_host_unreachable" to 503, failure.code to failure.status)
        assertFailsWith<Exception> { down.retain(listOf(key)) }
        val refusing = upstream(Host { _, _ -> json("""{"error":{"code":"unauthorized","message":"no"}}""", HttpStatusCode.Unauthorized) })
        assertFailsWith<TeamFailure> { refusing.launchers() }.also { assertEquals("mcp_host_unreachable", it.code); assertTrue(it.message!!.contains("same token"), it.message) }
        var asked = 0
        val starting = upstream(Host { _, _ -> asked++; json("""{"version":"1","protocol":"1","launchers":[],"maxProcesses":16,"draining":false,"processes":[]}""") })
        assertEquals(emptySet(), starting.launchers()); assertEquals(emptySet(), starting.launchers())
        assertEquals(2, asked, "An empty launcher list is not cached")
        assertEquals("unauthorized", assertFailsWith<McpHostRefused> { refusing.retain(listOf(key)) }.code, "The sweep tells a refusal from an outage")
        assertEquals("unauthorized", assertFailsWith<McpHostRefused> { refusing.status() }.code)
        Unit
    }

    @Test fun theStatusIsReadDefensivelyAndARestartStopsOneKey() = runBlocking {
        val other = "b".repeat(64)
        val host = Host { path, body ->
            when (path) {
                "/v1/status" -> json("""{"version":"1.2.3","protocol":"1","launchers":["npm"],"maxProcesses":16,"draining":false,"processes":[
                    {"key":"$key","state":"backoff","launcher":"npm","package":"fixture@1.0.0","starts":4,"failures":4,"lastFailure":"crashed","lastExitCode":1,"coldStartMillis":812,"rssBytes":1048576,"backoffUntil":99,"stderr":"line\n"},
                    {"key":"$other","state":"Not A State!","launcher":"pypi","package":"x==1","lastFailure":"<script>"},
                    {"key":"not-a-key","state":"ready"},
                    "not an object"]}""")
                "/v1/stop" -> if (body!!["key"]!!.jsonPrimitive.content == key) json("""{"stopped":true}""") else json("""{"stopped":false}""")
                else -> error(path)
            }
        }
        val upstream = upstream(host)
        val status = upstream.status()
        assertEquals("1.2.3", status.version)
        assertEquals(listOf(key, other), status.processes.map { it.key }, "A key that is not a process key is dropped")
        val first = status.byKey.getValue(key)
        assertEquals(listOf("backoff", "crashed", "line\n"), listOf(first.state, first.lastFailure, first.stderr))
        assertEquals(listOf(4L, 4L, 1L, 812L, 1_048_576L, 99L), listOf(first.starts.toLong(), first.failures.toLong(), first.lastExitCode!!.toLong(), first.coldStartMillis!!, first.rssBytes!!, first.backoffUntil!!))
        assertEquals("unknown" to null, status.byKey.getValue(other).let { it.state to it.lastFailure }, "Labels outside the code alphabet are not passed on")
        assertTrue(upstream.stop(key))
        assertFalse(upstream.stop(other))
        assertEquals(buildJsonObject { put("key", key) }, host.requests.first { it.first == "/v1/stop" }.second)

        val down = upstream(Host { _, _ -> throw IOException("refused") })
        assertFails { down.status() }
        assertEquals("mcp_host_unreachable", assertFailsWith<TeamFailure> { down.stop(key) }.code)
        val refusing = upstream(Host { _, _ -> json("""{"error":{"code":"unauthorized","message":"no"}}""", HttpStatusCode.Unauthorized) })
        assertEquals(503, assertFailsWith<TeamFailure> { refusing.stop(key) }.status)
    }

    @Test fun theTransportRouterSendsEachServerToItsOwnUpstream() = runBlocking {
        val calls = mutableListOf<String>()
        fun fake(name: String, hosts: Boolean) = object : McpUpstream {
            override val hostsCommands get() = hosts
            override suspend fun listTools(server: EffectiveMcpServer, limits: McpLimits): List<UpstreamTool> { calls += "$name:${server.name}"; return emptyList() }
            override suspend fun callTool(server: EffectiveMcpServer, tool: String, arguments: String?, limits: McpLimits): UpstreamCallResult { calls += "$name:${server.name}"; return UpstreamCallResult("{}", false, 2) }
            override fun warm(servers: List<EffectiveMcpServer>) { calls += "$name:warm:${servers.map { it.name }}" }
        }
        val http = server().copy(name = "tracker", transport = McpServerTransport.Http("https://tracker.example.test"))
        val router = TransportMcpUpstream(fake("http", false), fake("host", true))
        assertTrue(router.hostsCommands)
        router.listTools(http, limits); router.listTools(server(), limits); router.callTool(server(), "echo", null, limits)
        router.warm(listOf(http, server()))
        assertEquals(listOf("http:tracker", "host:everything", "host:everything", "host:warm:[everything]"), calls)
        val hostless = TransportMcpUpstream(fake("http", false), null)
        assertFalse(hostless.hostsCommands)
        assertFailsWith<McpUpstreamFailure> { hostless.listTools(server(), limits) }
        hostless.warm(listOf(server()))
        Unit
    }

    @Test fun theScrubberReplacesEverySecretInTextAndJsonTrees() {
        val scrubber = McpScrubber(listOf("synthetic-abc", "synthetic-abcdef", ""))
        assertEquals("[REDACTED] and [REDACTED]", scrubber.scrub("synthetic-abcdef and synthetic-abc"), "Longest first")
        val tree = buildJsonObject { put("synthetic-abc", "x synthetic-abcdef"); putJsonArray("a") { add(1); add("synthetic-abc") }; put("n", JsonNull) }
        assertEquals("""{"[REDACTED]":"x [REDACTED]","a":[1,"[REDACTED]"],"n":null}""", scrubber.scrub(tree).toString())
        assertEquals("unchanged", McpScrubber(emptyList()).scrub("unchanged"))
    }
}
