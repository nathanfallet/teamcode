package me.nathanfallet.teamcode.mcp

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.io.path.Path
import kotlin.io.path.absolutePathString
import kotlin.io.path.isExecutable
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * A real host on a loopback port, whose `npx` and `uvx` are the Node.js fixture server: every test goes through the
 * production launch path (argv, environment, prlimit when present), the stdio transport and the HTTP surface.
 */
class HostFixture(tweak: HostFixture.(HostConfig) -> HostConfig = { it }, memory: MemoryProbe = MemoryProbe.SYSTEM) : AutoCloseable {
    val root: Path = Files.createTempDirectory("teamcode-mcp-test")
    val bin: Path = Files.createDirectories(root.resolve("bin"))
    val token = "test-token-" + "k".repeat(40)
    val events = CopyOnWriteArrayList<JsonObject>()
    val config: HostConfig
    val supervisor: Supervisor
    private val server: io.ktor.server.engine.EmbeddedServer<*, *>
    val origin: String
    val http = HttpClient(CIO) { expectSuccess = false; install(HttpTimeout) { requestTimeoutMillis = 120_000 } }

    init {
        val script = javaClass.getResource("/stdio-fixture-server.js")!!.readText()
        for (name in listOf("npx", "uvx")) bin.resolve(name).also { Files.writeString(it, script); it.toFile().setExecutable(true) }
        config = HostConfig(
            token = token, host = "127.0.0.1", dataDir = root.resolve("data"),
            npx = bin.resolve("npx").absolutePathString(), uvx = bin.resolve("uvx").absolutePathString(),
            prlimit = "/usr/bin/prlimit".takeIf { Path(it).isExecutable() },
            path = "${Path(node).parent}:/usr/bin:/bin",
            drainMinMillis = 0, tickMillis = 100, memoryCheckMillis = 200, closeStdinWaitMillis = 1_000, terminateWaitMillis = 1_500,
        ).let { tweak(it) }
        supervisor = Supervisor(config, events = { events += it; EventSink.LOG.emit(it) }, memory = memory)
        server = embeddedServer(Netty, host = "127.0.0.1", port = 0) { mcpHost(config, supervisor) }.start()
        origin = "http://127.0.0.1:" + runBlocking { server.engine.resolvedConnectors().first().port }
    }

    fun spec(mode: String = "", env: Map<String, String> = emptyMap(), secrets: Map<String, String> = emptyMap(), args: List<String> = emptyList(), launcher: String = Launchers.NPM) =
        ProcessSpec(launcher, if (launcher == Launchers.NPM) "fixture@1.0.0" else "fixture==1.0.0", args, env + ("FIXTURE_MODE" to mode), secrets, System.currentTimeMillis())

    fun HttpRequestBuilder.authorized(token: String? = this@HostFixture.token, protocol: String? = HostProtocol.VERSION) {
        token?.let { header(HttpHeaders.Authorization, "Bearer $it") }
        protocol?.let { header(HostProtocol.HEADER, it) }
    }

    suspend fun post(path: String, body: String, client: HttpClient = http): HttpResponse = client.post(origin + path) {
        authorized(); contentType(ContentType.Application.Json); setBody(body)
    }

    suspend fun ops(key: String, spec: ProcessSpec, op: String, tool: String? = null, arguments: JsonObject? = null, deadlineMs: Long = 20_000, maxResponseBytes: Int = 4 * 1024 * 1024): OpsResponse {
        val response = post(HostProtocol.OPS, HostProtocol.json.encodeToString(OpsRequest.serializer(), OpsRequest(key, spec, op, tool, arguments, deadlineMs, maxResponseBytes)))
        check(response.status.value == 200) { "HTTP ${response.status}: ${response.bodyAsText()}" }
        return HostProtocol.json.decodeFromString(OpsResponse.serializer(), response.bodyAsText())
    }

    suspend fun call(key: String, spec: ProcessSpec, tool: String, arguments: JsonObject = buildJsonObject {}, deadlineMs: Long = 20_000, maxResponseBytes: Int = 4 * 1024 * 1024) =
        ops(key, spec, "call", tool, arguments, deadlineMs, maxResponseBytes)

    suspend fun stats(key: String, spec: ProcessSpec): JsonObject = text(call(key, spec, "stats")).let { HostProtocol.json.parseToJsonElement(it).jsonObject }

    suspend fun status(): HostStatus = http.get(origin + HostProtocol.STATUS) { authorized() }.let { HostProtocol.json.decodeFromString(HostStatus.serializer(), it.bodyAsText()) }

    suspend fun eventually(timeoutMillis: Long = 10_000, condition: suspend () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "condition not met within $timeoutMillis ms" }
            delay(50)
        }
    }

    override fun close() {
        runBlocking { supervisor.shutdown() }
        server.stop(0, 2_000)
        http.close()
        root.toFile().deleteRecursively()
    }

    companion object {
        val node: String by lazy {
            System.getenv("TEAMCODE_MCP_TEST_NODE")?.takeIf { it.isNotBlank() }
                ?: System.getenv("PATH").orEmpty().split(':').map { Path(it, "node") }.firstOrNull { it.isExecutable() }?.absolutePathString()
                ?: error("The host tests need Node.js on PATH (or TEAMCODE_MCP_TEST_NODE).")
        }

        fun key(name: String): String = MessageDigest.getInstance("SHA-256").digest(name.toByteArray()).joinToString("") { "%02x".format(it) }

        fun text(response: OpsResponse): String {
            val result = checkNotNull(response.result) { "error ${response.error}" }
            return result["content"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content
        }
    }
}
