package me.nathanfallet.teamcode.mcp

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import me.nathanfallet.teamcode.mcp.HostFixture.Companion.key
import me.nathanfallet.teamcode.mcp.HostFixture.Companion.text

class McpHostTest {
    private fun host(tweak: HostFixture.(HostConfig) -> HostConfig = { it }, memory: MemoryProbe = MemoryProbe.SYSTEM, block: suspend HostFixture.() -> Unit) =
        HostFixture(tweak, memory).use { runBlocking { it.block() } }

    private fun ProcessStatus?.pid() = checkNotNull(this?.pid)
    private suspend fun HostFixture.process(key: String) = status().processes.firstOrNull { it.key == key }

    @Test
    fun `initialize runs once per process, and list and calls are multiplexed on it`() = host {
        val key = key("multiplex")
        val spec = spec(env = mapOf("FIXTURE_PAGE_SIZE" to "4"), args = listOf("stdio", "--verbose"))
        val list = ops(key, spec, "list")
        assertTrue(list.cold)
        assertEquals(ProcessStates.READY, list.state)
        val names = list.result!!["tools"]!!.jsonArray.map { it.jsonObject["name"]!!.jsonPrimitive.content }
        assertEquals(15, names.size, "every page of tools/list is read")
        assertTrue("echo" in names && "spam" in names)
        val echo = call(key, spec, "echo", buildJsonObject { put("text", "hello") })
        assertFalse(echo.cold)
        assertEquals("hello", text(echo))
        // 16 slow calls at once: they overlap on the one process, each answered by its own id.
        val started = System.nanoTime()
        val results = coroutineScope { (1..16).map { async { call(key, spec, "slow", buildJsonObject { put("ms", 800) }) } }.awaitAll() }
        assertTrue(results.all { it.error == null && text(it) == "done" })
        assertTrue((System.nanoTime() - started) / 1_000_000 < 8 * 800, "the calls ran concurrently")
        val stats = stats(key, spec)
        assertEquals(1, stats["initialize"]!!.jsonPrimitive.int)
        assertEquals(listOf("--yes", "--", "fixture@1.0.0", "stdio", "--verbose"), stats["argv"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertTrue(events.any { it["event"]?.jsonPrimitive?.content == "started" })
    }

    @Test
    fun `launcher commands are found on the processes' PATH, not the host's`() =
        host({ it.copy(npx = "npx", path = "$bin:${it.path}") }) {
            // The JVM's own PATH does not contain the fixture's directory: only the configured PATH does.
            assertEquals("ok", text(call(key("path"), spec(), "echo", buildJsonObject { put("text", "ok") })))
        }

    @Test
    fun `a pypi process runs through uvx with its pinned package`() = host {
        val key = key("pypi")
        val spec = spec(launcher = Launchers.PYPI)
        val argv = stats(key, spec)["argv"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertEquals("--exclude-newer", argv[0])
        assertEquals("fixture==1.0.0", argv.last())
    }

    @Test
    fun `requests from the server are refused at once and a stdout banner is ignored`() = host {
        val key = key("ask")
        val spec = spec(mode = "banner")
        assertEquals("""{"code":-32601}""", text(call(key, spec, "ask")))
    }

    @Test
    fun `list_changed refreshes the cached tool list`() = host {
        val key = key("changed")
        val spec = spec()
        assertFalse(ops(key, spec, "list").result.toString().contains("\"added\""))
        call(key, spec, "change")
        eventually { ops(key, spec, "list").result.toString().contains("\"added\"") }
    }

    @Test
    fun `a crash fails the running call, the next call restarts the process, and repeated crashes back off`() = host {
        val key = key("crash")
        val spec = spec()
        val first = process(key).also { assertNull(it) }
        call(key, spec, "echo")
        val pid = process(key).pid()
        val crash = call(key, spec, "crash")
        assertEquals(HostErrors.CRASHED, crash.error?.code)
        val again = call(key, spec, "echo", buildJsonObject { put("text", "back") })
        assertTrue(again.cold)
        assertEquals("back", text(again))
        assertNotEquals(pid, process(key).pid())
        repeat(3) { assertEquals(HostErrors.CRASHED, call(key, spec, "crash").error?.code) }
        val refused = call(key, spec, "echo")
        assertEquals(HostErrors.BACKOFF, refused.error?.code)
        assertEquals(ProcessStates.BACKOFF, refused.state)
        assertEquals(ProcessStates.BACKOFF, process(key)?.state)
        assertTrue(events.any { it["event"]?.jsonPrimitive?.content == "backoff" })
        // The administrator's restart lifts the backoff.
        assertTrue(post(HostProtocol.STOP, """{"key":"$key"}""").bodyAsText().contains("true"))
        assertEquals("ok", text(call(key, spec, "echo", buildJsonObject { put("text", "ok") })))
        assertNull(first)
    }

    @Test
    fun `a deadline cancels the request in the process without restarting it`() = host {
        val key = key("timeout")
        val spec = spec()
        call(key, spec, "echo")
        val pid = process(key).pid()
        val slow = call(key, spec, "slow", buildJsonObject { put("ms", 5_000) }, deadlineMs = 500)
        assertEquals(HostErrors.TIMEOUT, slow.error?.code)
        eventually { stats(key, spec)["cancelled"]!!.jsonArray.isNotEmpty() }
        assertEquals(pid, process(key).pid())
    }

    @Test
    fun `a closed connection cancels the request in the process`() = host {
        val key = key("disconnect")
        val spec = spec()
        call(key, spec, "echo")
        HttpClient(CIO) { install(HttpTimeout) { requestTimeoutMillis = 500 } }.use { impatient ->
            val request = OpsRequest(key, spec, "call", "slow", buildJsonObject { put("ms", 10_000) }, 20_000, 1024 * 1024)
            val failure = runCatching { post(HostProtocol.OPS, HostProtocol.json.encodeToString(OpsRequest.serializer(), request), impatient) }
            assertTrue(failure.exceptionOrNull() is HttpRequestTimeoutException)
        }
        eventually { stats(key, spec)["cancelled"]!!.jsonArray.isNotEmpty() }
    }

    @Test
    fun `calls beyond the in-flight limit wait, then fail as busy`() = host({ it.copy(maxInFlightPerProcess = 1) }) {
        val key = key("busy")
        val spec = spec()
        call(key, spec, "echo")
        coroutineScope {
            val running = async { call(key, spec, "slow", buildJsonObject { put("ms", 2_000) }) }
            delay(300)
            assertEquals(HostErrors.BUSY, call(key, spec, "echo", deadlineMs = 500).error?.code)
            val queued = async { call(key, spec, "echo", buildJsonObject { put("text", "after") }, deadlineMs = 10_000) }
            assertEquals("done", text(running.await()))
            assertEquals("after", text(queued.await()))
        }
    }

    @Test
    fun `an answer over the request's limit is refused and lines over the host's limit are dropped, then restart the process`() =
        host({ it.copy(maxLineBytes = 256 * 1024) }) {
            val key = key("large")
            val spec = spec()
            call(key, spec, "echo")
            val pid = process(key).pid()
            val tooLarge = call(key, spec, "big", buildJsonObject { put("bytes", 100_000) }, maxResponseBytes = 64 * 1024)
            assertEquals(HostErrors.TOO_LARGE, tooLarge.error?.code)
            repeat(3) {
                // The line is dropped unparsed, but its id is read at its start or end: the call fails at once, not at its deadline.
                val started = System.currentTimeMillis()
                val dropped = call(key, spec, "big", buildJsonObject { put("bytes", 300_000) }, deadlineMs = 10_000)
                if (it < 2) assertEquals(HostErrors.TOO_LARGE, dropped.error?.code)
                else assertTrue(dropped.error?.code in setOf(HostErrors.TOO_LARGE, HostErrors.CRASHED))
                assertTrue(System.currentTimeMillis() - started < 5_000)
            }
            assertTrue(events.count { it["event"]?.jsonPrimitive?.content == "oversize" } >= 3)
            eventually { process(key)?.pid == null || process(key)?.pid != pid }
            assertEquals("fine", text(call(key, spec, "echo", buildJsonObject { put("text", "fine") })))
            assertNotEquals(pid, process(key).pid())
        }

    @Test
    fun `too much non-JSON on stdout restarts the process`() = host {
        val key = key("noise")
        val spec = spec()
        call(key, spec, "echo")
        val pid = process(key).pid()
        call(key, spec, "spam", buildJsonObject { put("lines", 150) }, deadlineMs = 2_000)
        eventually { process(key)?.pid != pid }
        // The key is freed at once; the stop, and its event, follow in the background.
        eventually { events.any { it["event"]?.jsonPrimitive?.content == "killed" && it["reason"]?.jsonPrimitive?.content == "non_json" } }
    }

    @Test
    fun `a process that stops reading stdin is restarted`() = host({ it.copy(writeStallMillis = 1_000) }) {
        val key = key("stuck")
        val spec = spec()
        call(key, spec, "stuck")
        val pid = process(key).pid()
        val payload = buildJsonObject { put("text", "y".repeat(400_000)) }
        val stuck = coroutineScope { (1..4).map { async { call(key, spec, "echo", payload, deadlineMs = 10_000) } }.awaitAll() }
        assertTrue(stuck.all { it.error?.code == HostErrors.CRASHED }, stuck.map { it.error }.toString())
        assertTrue(events.any { it["event"]?.jsonPrimitive?.content == "killed" && it["reason"]?.jsonPrimitive?.content == "write_stall" })
        assertEquals("ok", text(call(key, spec, "echo", buildJsonObject { put("text", "ok") })))
        assertNotEquals(pid, process(key).pid())
    }

    @Test
    fun `secrets reach the process only, and leave it scrubbed`() {
        val logs = ListAppender<ILoggingEvent>().apply { start() }
        val root = LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME) as Logger
        root.addAppender(logs)
        val secret = "sk-live-0123456789abcdef"
        try {
            host {
                val key = key("secrets")
                val spec = spec(secrets = mapOf("FIXTURE_SECRET" to secret), args = listOf("--token=\${FIXTURE_SECRET}"), env = mapOf("API_URL" to "https://api.example.org"))
                assertEquals("the secret is [REDACTED]", text(call(key, spec, "secret")))
                val environment = HostProtocol.json.parseToJsonElement(text(call(key, spec, "env"))).jsonObject
                assertEquals("[REDACTED]", environment["FIXTURE_SECRET"]!!.jsonPrimitive.content)
                val allowed = setOf("PATH", "HOME", "TMPDIR", "LANG", "API_URL", "FIXTURE_MODE", "FIXTURE_SECRET")
                // macOS adds __CF_USER_TEXT_ENCODING to every process it starts; the host's own variables never pass.
                val unexpected = environment.keys.filter { it !in allowed && !it.startsWith("npm_config_") && it != "__CF_USER_TEXT_ENCODING" }
                assertEquals(emptyList(), unexpected, "nothing of the host's environment reaches the process")
                assertEquals("[\"--yes\",\"--\",\"fixture@1.0.0\",\"--token=[REDACTED]\"]", stats(key, spec)["argv"].toString())
                val failed = call(key, spec, "fail")
                assertEquals(HostErrors.TOOL_ERROR, failed.error?.code)
                assertFalse(secret in failed.error!!.message)
                assertTrue("[REDACTED]" in failed.error!!.message)
                eventually { process(key)?.stderr?.contains("secret=[REDACTED]") == true }
                val everything = http.get(origin + HostProtocol.STATUS) { authorized() }.bodyAsText()
                assertFalse(secret in everything)
                // A crash, a restart and a revocation: none of them logs a secret either.
                call(key, spec, "crash")
                call(key, spec, "echo")
                assertEquals("""{"stopped":1}""", post(HostProtocol.RETAIN, """{"keys":[]}""").bodyAsText())
                assertFalse(events.any { secret in it.toString() })
            }
            assertTrue(logs.list.isNotEmpty())
            assertFalse(logs.list.any { secret in it.formattedMessage || secret in (it.throwableProxy?.message ?: "") }, "no secret in any log line")
        } finally {
            root.detachAppender(logs)
        }
    }

    @Test
    fun `the token and the protocol version are required, the probes are open`() = host {
        val body = HostProtocol.json.encodeToString(OpsRequest.serializer(), OpsRequest(key("auth"), spec(), "list", deadlineMs = 5_000, maxResponseBytes = 1_024))
        for ((token, protocol, expected) in listOf(
            Triple(null, HostProtocol.VERSION, HttpStatusCode.Unauthorized),
            Triple("wrong-" + token.drop(6), HostProtocol.VERSION, HttpStatusCode.Unauthorized),
            Triple(token + "x", HostProtocol.VERSION, HttpStatusCode.Unauthorized),
            Triple(token, null, HttpStatusCode.BadRequest),
            Triple(token, "2", HttpStatusCode.BadRequest),
        )) {
            val response = http.post(origin + HostProtocol.OPS) { authorized(token, protocol); contentType(ContentType.Application.Json); setBody(body) }
            assertEquals(expected, response.status, "token=${token != null} protocol=$protocol")
            val code = if (expected == HttpStatusCode.Unauthorized) HostErrors.UNAUTHORIZED else HostErrors.UNSUPPORTED_PROTOCOL
            assertTrue(response.bodyAsText().contains(code))
        }
        assertEquals(HttpStatusCode.Unauthorized, http.get(origin + HostProtocol.STATUS) { authorized(null) }.status)
        assertNull(process(key("auth")), "a refused request starts nothing")
        assertEquals(HttpStatusCode.OK, http.get("$origin/health").status)
        assertEquals(HttpStatusCode.OK, http.get("$origin/ready").status)
        val oversized = http.post(origin + HostProtocol.OPS) { authorized(); contentType(ContentType.Application.Json); setBody("x".repeat(config.maxRequestBytes + 1)) }
        assertEquals(HttpStatusCode.PayloadTooLarge, oversized.status)
        assertEquals(HttpStatusCode.BadRequest, http.post(origin + HostProtocol.OPS) { authorized(); contentType(ContentType.Application.Json); setBody("{") }.status)
    }

    @Test
    fun `a retain sweep spares a key first seen within its grace period`() = host({ it.copy(retainGraceMillis = 1_500) }) {
        // The server read its list before this assignment existed: the new process survives the sweep.
        val fresh = key("fresh")
        call(fresh, spec(), "echo")
        assertEquals("""{"stopped":0}""", post(HostProtocol.RETAIN, """{"keys":[]}""").bodyAsText())
        assertNotNull(process(fresh))
        delay(1_600)
        assertEquals("""{"stopped":1}""", post(HostProtocol.RETAIN, """{"keys":[]}""").bodyAsText())
        eventually { process(fresh) == null }
    }

    @Test
    fun `stopping a process closes stdin, then terminates and kills its whole tree`() = host {
        val key = key("tree")
        val spec = spec(mode = "ignore-term")
        val child = HostProtocol.json.parseToJsonElement(text(call(key, spec, "spawn"))).jsonObject["pid"]!!.jsonPrimitive.long
        val pid = process(key).pid()
        assertTrue(ProcessHandle.of(child).isPresent)
        assertEquals("""{"stopped":1}""", post(HostProtocol.RETAIN, """{"keys":["${key("other")}"]}""").bodyAsText())
        eventually { ProcessHandle.of(pid).isEmpty && ProcessHandle.of(child).isEmpty }
        assertTrue(events.any { it["event"]?.jsonPrimitive?.content == "killed" })
        assertNull(process(key))
    }

    @Test
    fun `a process exits on its own when stdin closes`() = host {
        val key = key("graceful")
        val spec = spec()
        call(key, spec, "echo")
        val pid = process(key).pid()
        assertTrue(post(HostProtocol.STOP, """{"key":"$key"}""").bodyAsText().contains("true"))
        eventually(3_000) { ProcessHandle.of(pid).isEmpty }
        // The exit is reported by the exit watcher, just after the process is gone.
        eventually { events.any { it["event"]?.jsonPrimitive?.content == "exited" && it["code"]?.jsonPrimitive?.int == 0 } }
    }

    @Test
    fun `shutdown drains running calls, then stops every process`() = host {
        val key = key("drain")
        val spec = spec()
        call(key, spec, "echo")
        val pid = process(key).pid()
        coroutineScope {
            val running = async { call(key, spec, "slow", buildJsonObject { put("ms", 1_500) }) }
            delay(200)
            val drained = async { supervisor.drain() }
            eventually { !supervisor.ready() }
            assertEquals(HttpStatusCode.ServiceUnavailable, http.get("$origin/ready").status)
            assertEquals("done", text(running.await()))
            drained.await()
        }
        supervisor.shutdown()
        eventually { ProcessHandle.of(pid).isEmpty }
    }

    @Test
    fun `idle processes stop, and at capacity the oldest idle one makes room`() = host({ it.copy(idleMillis = 600, maxProcesses = 1) }) {
        val first = key("first")
        val second = key("second")
        val spec = spec()
        call(first, spec, "echo")
        eventually { process(first)?.pid == null }
        assertTrue(call(first, spec, "echo").cold)
        // The only slot is taken by an idle process: it is stopped for the new key.
        assertEquals(ProcessStates.READY, call(second, spec, "echo").state)
        eventually { process(first)?.pid == null }
        // A busy process is never evicted: the new key is refused.
        coroutineScope {
            val busy = async { call(second, spec, "slow", buildJsonObject { put("ms", 1_500) }) }
            delay(300)
            assertEquals(HostErrors.CAPACITY, call(first, spec, "echo").error?.code)
            busy.await()
        }
    }

    @Test
    fun `a process over its memory limit is stopped`() =
        host(memory = MemoryProbe { 2L * 1024 * 1024 * 1024 }) {
            val key = key("memory")
            val spec = spec()
            call(key, spec, "echo")
            eventually { events.any { it["event"]?.jsonPrimitive?.content == "killed" && it["reason"]?.jsonPrimitive?.content == "memory" } }
            // The failure is recorded when the process has exited, after the kill.
            eventually { process(key)?.lastFailure == "memory" }
        }

    @Test
    fun `a server that cannot start is reported, with no tools capability or an early exit`() = host {
        val noTools = call(key("no-tools"), spec(mode = "no-tools"), "echo")
        assertEquals(HostErrors.START_FAILED, noTools.error?.code)
        assertTrue("tools" in noTools.error!!.message)
        assertEquals(HostErrors.START_FAILED, call(key("early-exit"), spec(mode = "exit-at-start"), "echo").error?.code)
        assertEquals(HostErrors.INVALID_SPEC, call(key("invalid"), spec().copy(pkg = "fixture@latest"), "echo").error?.code)
        val badKey = post(HostProtocol.OPS, HostProtocol.json.encodeToString(OpsRequest.serializer(), OpsRequest("../etc", spec(), "list", deadlineMs = 5_000, maxResponseBytes = 1_024)))
        assertEquals(HttpStatusCode.BadRequest, badKey.status)
    }

    @Test
    fun `an unexpected start error fails the operation without leaving the key stuck`() = host({ it.copy(dataDir = java.nio.file.Files.createTempFile("teamcode-mcp", ".file")) }) {
        // The data directory is a file: creating the key's directories fails with an I/O error.
        val key = key("unwritable")
        repeat(2) { assertEquals(HostErrors.START_FAILED, call(key, spec(), "echo").error?.code) }
        assertEquals(ProcessStates.FAILED, process(key)?.state)
    }

    @Test
    fun `a ping from the server is answered, other requests are refused`() = host {
        assertEquals("""{"result":{}}""", text(call(key("ping"), spec(), "ping")))
    }

    @Test
    fun `a process whose stdin breaks is stopped instead of leaving calls waiting`() = host {
        val key = key("broken-stdin")
        val spec = spec(mode = "ignore-term")
        call(key, spec, "close-stdin")
        val pid = process(key).pid()
        val started = System.currentTimeMillis()
        assertEquals(HostErrors.CRASHED, call(key, spec, "echo", deadlineMs = 15_000).error?.code)
        assertTrue(System.currentTimeMillis() - started < 10_000)
        eventually { ProcessHandle.of(pid).isEmpty }
        assertEquals("ok", text(call(key, spec, "echo", buildJsonObject { put("text", "ok") })))
    }

    @Test
    fun `a crash also ends the processes the server started`() = host {
        val key = key("orphans")
        val spec = spec()
        val child = HostProtocol.json.parseToJsonElement(text(call(key, spec, "spawn"))).jsonObject["pid"]!!.jsonPrimitive.long
        delay(500) // the host learns the tree on its next tick
        assertEquals(HostErrors.CRASHED, call(key, spec, "crash").error?.code)
        eventually { ProcessHandle.of(child).isEmpty }
    }

    @Test
    fun `the supervision loop survives an error of the memory probe`() =
        host({ it.copy(idleMillis = 600) }, memory = MemoryProbe { throw IllegalStateException("probe failure") }) {
            val key = key("probe")
            call(key, spec(), "echo")
            delay(500)
            eventually { process(key)?.pid == null }
        }

    @Test
    fun `a new definition for a key that is still starting gets its own process`() = host {
        val key = key("redefined")
        val old = spec(mode = "slow-start", env = mapOf("FIXTURE_LABEL" to "old"))
        val new = spec(mode = "slow-start", env = mapOf("FIXTURE_LABEL" to "new"))
        assertEquals(HttpStatusCode.Accepted, post(HostProtocol.WARM, HostProtocol.json.encodeToString(WarmRequest.serializer(), WarmRequest(key, old))).status)
        delay(200)
        val environment = HostProtocol.json.parseToJsonElement(text(call(key, new, "env"))).jsonObject
        assertEquals("new", environment["FIXTURE_LABEL"]!!.jsonPrimitive.content)
    }

    @Test
    fun `warm starts a process without waiting, and the first call finds it ready`() = host {
        val key = key("warm")
        val spec = spec()
        val warm = post(HostProtocol.WARM, HostProtocol.json.encodeToString(WarmRequest.serializer(), WarmRequest(key, spec)))
        assertEquals(HttpStatusCode.Accepted, warm.status)
        eventually { process(key)?.state == ProcessStates.READY }
        val list = ops(key, spec, "list")
        assertFalse(list.cold)
        assertEquals(1, stats(key, spec)["initialize"]!!.jsonPrimitive.int)
        assertTrue(process(key)!!.coldStartMillis!! > 0)
        assertEquals(JsonPrimitive(key), JsonPrimitive(status().processes.single().key))
    }
}
