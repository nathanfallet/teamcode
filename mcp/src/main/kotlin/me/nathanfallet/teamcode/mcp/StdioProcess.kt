package me.nathanfallet.teamcode.mcp

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStreamReader
import java.nio.file.Files
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import me.nathanfallet.teamcode.api.BuildInfo

/** Why a process ends. A failure counts towards the backoff of its key. */
internal enum class StopReason(val label: String, val failure: Boolean, val message: String) {
    CRASHED("crashed", true, "The MCP server stopped during the call."),
    STDOUT_CLOSED("stdout_closed", true, "The MCP server closed its output during the call."),
    MEMORY("memory", true, "The MCP server exceeded its memory limit and was stopped."),
    WRITE_STALL("write_stall", true, "The MCP server stopped reading its input and was stopped."),
    NON_JSON("non_json", true, "The MCP server wrote too much invalid output and was stopped."),
    OVERSIZE("oversize", true, "The MCP server wrote too many oversized messages and was stopped."),
    START_FAILED("start_failed", true, "The MCP server did not start."),
    IDLE("idle", false, "The MCP server was stopped after being idle."),
    LIFETIME("lifetime", false, "The MCP server reached its maximum lifetime and was stopped."),
    RETAINED("retained", false, "The MCP server is no longer assigned and was stopped."),
    STOPPED("stopped", false, "The MCP server was restarted by an administrator."),
    REPLACED("replaced", false, "The MCP server's definition changed and it was restarted."),
    CAPACITY("capacity", false, "The MCP server was stopped to make room for another one."),
    SHUTDOWN("shutdown", false, "The MCP host is shutting down."),
}

/**
 * One MCP server process and its stdio JSON-RPC session: one message per line in both directions, requests
 * multiplexed by ids the host assigns, server requests refused at once (-32601), stderr drained into a bounded buffer.
 * The process is a cache of the session: [initialize] runs once, the tools/list answer is kept until the server says it changed.
 */
internal class StdioProcess(
    val key: String,
    private val plan: LaunchPlan,
    private val dirs: ProcessDirs,
    private val config: HostConfig,
    private val events: EventSink,
    private val describe: JsonObject,
    /** Called once, from the exit watcher, with the reason the process ended and its exit code. */
    private val onExit: (StdioProcess, StopReason, Int) -> Unit,
    /** Asks the supervisor to stop this process (invalid output, closed stdout). */
    private val requestStop: (StdioProcess, StopReason) -> Unit,
) {
    private class Reply(val message: JsonObject, val bytes: Int)

    val scrubber = Scrubber(plan.secrets)
    val stderr = StderrBuffer(config.stderrBytes, scrubber)
    val permits = Semaphore(config.maxInFlightPerProcess)
    /** Operations between obtaining this process and their end: a process with users is never stopped for idleness or room. */
    val users = AtomicInteger()
    val startedAt = System.currentTimeMillis()
    val lastUsed = AtomicLong(startedAt)
    val exited = CompletableDeferred<Int>()
    @Volatile var ready = false
        private set
    @Volatile private var tools: JsonObject? = null
    private lateinit var process: Process
    private val pending = ConcurrentHashMap<Long, CompletableDeferred<Reply>>()
    private val nextId = AtomicLong(1)
    private val outbox = ArrayBlockingQueue<String>(config.maxInFlightPerProcess * 2 + 32)
    private val writeStartedNanos = AtomicLong(0)
    private val stopReason = AtomicReference<StopReason?>(null)
    private val dead = AtomicBoolean(false)
    private val nonJsonLines = ArrayDeque<Long>()
    private val oversizeLines = ArrayDeque<Long>()
    private lateinit var reader: Thread
    private lateinit var writer: Thread
    private lateinit var errors: Thread

    val pid: Long? get() = if (::process.isInitialized) process.pid() else null
    val alive get() = !dead.get() && stopReason.get() == null
    fun touch() = lastUsed.set(System.currentTimeMillis())
    fun pids(): List<Long> = if (!::process.isInitialized || !process.isAlive) emptyList() else listOf(process.pid()) + process.descendants().map { it.pid() }.toList()
    fun writeStalledMillis(): Long = writeStartedNanos.get().let { if (it == 0L) 0 else (System.nanoTime() - it) / 1_000_000 }
    /** Claims the stop for [reason]; false when the process is already stopping for another one. */
    fun markStopping(reason: StopReason) = stopReason.compareAndSet(null, reason)

    /** Starts the process. No shell: the argv goes to execve as built by [Launchers]. */
    suspend fun launch() = withContext(Dispatchers.IO) {
        dirs.all.forEach { Files.createDirectories(it) }
        // ProcessBuilder looks a bare command up on the JVM's own PATH: resolve it on the process's PATH instead.
        val program = resolve(plan.command.first(), plan.environment["PATH"].orEmpty())
            ?: throw HostFailure(HostErrors.START_FAILED, "${plan.command.first()} is not installed on this host.").also { dead.set(true); exited.complete(-1) }
        val builder = ProcessBuilder(listOf(program) + plan.command.drop(1)).directory(plan.workDir.toFile())
        builder.environment().clear()
        builder.environment().putAll(plan.environment)
        process = try { builder.start() } catch (_: IOException) {
            dead.set(true)
            exited.complete(-1)
            throw HostFailure(HostErrors.START_FAILED, "The launcher of the MCP server could not be started.")
        }
        reader = daemon("reader") { readStdout() }
        writer = daemon("writer") { writeStdin() }
        errors = daemon("stderr") { readStderr() }
        process.onExit().thenRun { daemon("exit") { onProcessExit() } }
    }

    /** initialize, then notifications/initialized. A server without the tools capability cannot serve the gateway. */
    suspend fun initialize() {
        val response = request("initialize", buildJsonObject {
            put("protocolVersion", PROTOCOL_VERSIONS.first())
            putJsonObject("capabilities") {}
            putJsonObject("clientInfo") { put("name", "teamcode-mcp"); put("version", BuildInfo.VERSION) }
        }, config.maxLineBytes)
        error(response)?.let { (code, _) -> throw HostFailure(HostErrors.START_FAILED, "The MCP server refused initialize (JSON-RPC error $code).") }
        val result = response["result"] as? JsonObject ?: throw HostFailure(HostErrors.START_FAILED, "The MCP server answered initialize without a result.")
        if ((result["protocolVersion"] as? JsonPrimitive)?.content !in PROTOCOL_VERSIONS)
            throw HostFailure(HostErrors.START_FAILED, "The MCP server does not support a protocol version the host speaks (${PROTOCOL_VERSIONS.joinToString()}).")
        if ((result["capabilities"] as? JsonObject)?.get("tools") !is JsonObject)
            throw HostFailure(HostErrors.START_FAILED, "The MCP server does not offer tools, the only capability the gateway relays.")
        notify("notifications/initialized", null)
        ready = true
    }

    /** Every page of tools/list (at most [MAX_LIST_PAGES] pages and [MAX_TOOLS] tools), kept until list_changed. */
    suspend fun listTools(maxResponseBytes: Int): JsonObject {
        tools?.let { return it }
        val collected = mutableListOf<JsonElement>()
        var cursor: String? = null
        for (page in 0 until MAX_LIST_PAGES) {
            val response = request("tools/list", cursor?.let { buildJsonObject { put("cursor", it) } }, maxResponseBytes)
            error(response)?.let { (code, _) -> throw HostFailure(HostErrors.PROTOCOL, "The MCP server answered tools/list with JSON-RPC error $code.") }
            val result = response["result"] as? JsonObject
            val entries = result?.get("tools") as? JsonArray ?: throw HostFailure(HostErrors.PROTOCOL, "The MCP server sent an invalid tools/list answer.")
            collected += entries.take(MAX_TOOLS - collected.size)
            cursor = (result["nextCursor"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            if (cursor == null || collected.size >= MAX_TOOLS) break
        }
        return JsonObject(mapOf("tools" to JsonArray(collected))).also { tools = it }
    }

    suspend fun callTool(tool: String, arguments: JsonObject?, maxResponseBytes: Int): JsonObject {
        val response = request("tools/call", buildJsonObject { put("name", tool); arguments?.let { put("arguments", it) } }, maxResponseBytes)
        error(response)?.let { (code, message) ->
            throw HostFailure(HostErrors.TOOL_ERROR, "The MCP server returned error $code: ${scrubber.scrub(message).take(1_000)}")
        }
        return response["result"] as? JsonObject ?: throw HostFailure(HostErrors.PROTOCOL, "The MCP server sent an invalid tools/call answer.")
    }

    /**
     * Sends one request and waits for its answer. Cancellation (deadline, closed connection) sends
     * notifications/cancelled to the process; the process keeps running.
     */
    private suspend fun request(method: String, params: JsonObject?, maxResponseBytes: Int): JsonObject {
        val id = nextId.getAndIncrement()
        val reply = CompletableDeferred<Reply>()
        pending[id] = reply
        if (dead.get()) { pending.remove(id); throw HostFailure(HostErrors.CRASHED, (stopReason.get() ?: StopReason.CRASHED).message) }
        val message = buildJsonObject { put("jsonrpc", "2.0"); put("id", id); put("method", method); params?.let { put("params", it) } }
        if (!outbox.offer(message.toString())) {
            pending.remove(id)
            throw HostFailure(HostErrors.BUSY, "The MCP server's input queue is full.")
        }
        val answer = try { reply.await() } catch (cancelled: CancellationException) {
            if (pending.remove(id) != null) notify("notifications/cancelled", buildJsonObject { put("requestId", id); put("reason", "Cancelled by TeamCode.") })
            throw cancelled
        }
        if (answer.bytes > maxResponseBytes) throw HostFailure(HostErrors.TOO_LARGE, "The MCP server's answer exceeds the gateway limit.")
        return answer.message
    }

    private fun notify(method: String, params: JsonObject?) {
        // Best effort: a full queue means the process is not reading, which the stall check handles.
        outbox.offer(buildJsonObject { put("jsonrpc", "2.0"); put("method", method); params?.let { put("params", it) } }.toString())
    }

    private fun error(response: JsonObject): Pair<Long, String>? {
        val error = response["error"] as? JsonObject ?: return null
        return ((error["code"] as? JsonPrimitive)?.longOrNull ?: 0L) to ((error["message"] as? JsonPrimitive)?.content ?: "")
    }

    private fun readStdout() {
        val input = process.inputStream
        val buffer = ByteArray(64 * 1024)
        val line = ByteArrayOutputStream()
        var dropping = false
        try {
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                var start = 0
                for (index in 0 until count) {
                    if (buffer[index] != NEWLINE) continue
                    if (!dropping) line.write(buffer, start, index - start)
                    if (dropping || line.size() > config.maxLineBytes) oversize() else handle(line.toByteArray())
                    line.reset()
                    dropping = false
                    start = index + 1
                }
                if (!dropping) {
                    line.write(buffer, start, count - start)
                    // Over the limit: the rest of the line is skipped without being kept or parsed.
                    if (line.size() > config.maxLineBytes) { dropping = true; line.reset() }
                }
            }
        } catch (_: IOException) {}
        // The output closed while the process lives on: it can no longer answer.
        if (!process.waitFor(1, java.util.concurrent.TimeUnit.SECONDS) || process.isAlive) requestStop(this, StopReason.STDOUT_CLOSED)
    }

    private fun handle(bytes: ByteArray) {
        val text = bytes.decodeToString().trimEnd('\r')
        if (text.isBlank()) return
        val message = try { Json.parseToJsonElement(text) as? JsonObject } catch (_: SerializationException) { null } catch (_: IllegalArgumentException) { null }
        if (message == null) {
            // Many servers print a banner on stdout although the specification forbids it: ignored, and counted.
            if (count(nonJsonLines, 60_000, config.nonJsonLimitPerMinute)) requestStop(this, StopReason.NON_JSON)
            return
        }
        val method = (message["method"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        val id = message["id"]
        if (method != null) {
            if (id != null && id !is JsonNull) {
                // sampling, elicitation, roots and any other request: the host announced no capability, so it never leaves one waiting.
                outbox.offer(buildJsonObject {
                    put("jsonrpc", "2.0"); put("id", id)
                    putJsonObject("error") { put("code", METHOD_NOT_FOUND); put("message", "The TeamCode MCP host does not support server requests.") }
                }.toString())
            } else when (method) {
                "notifications/tools/list_changed" -> tools = null
                "notifications/message", "notifications/progress" -> stderr.append("[$method] ${message["params"] ?: JsonNull}\n")
            }
            return
        }
        val numeric = (id as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull ?: return
        pending.remove(numeric)?.complete(Reply(message, bytes.size))
    }

    private fun oversize() {
        events.emit(event("oversize"))
        if (count(oversizeLines, config.oversizeWindowMillis, config.oversizeLimit - 1)) requestStop(this, StopReason.OVERSIZE)
    }

    /** Records one occurrence; true when more than [limit] happened within [windowMillis]. */
    private fun count(times: ArrayDeque<Long>, windowMillis: Long, limit: Int): Boolean = synchronized(times) {
        val now = System.currentTimeMillis()
        times.addLast(now)
        while (times.isNotEmpty() && now - times.first() > windowMillis) times.removeFirst()
        times.size > limit
    }

    private fun writeStdin() {
        val output = process.outputStream
        try {
            while (true) {
                val message = outbox.take()
                writeStartedNanos.set(System.nanoTime())
                output.write((message + "\n").toByteArray())
                output.flush()
                writeStartedNanos.set(0)
            }
        } catch (_: InterruptedException) {
        } catch (_: IOException) {
        } finally {
            writeStartedNanos.set(0)
            try { output.close() } catch (_: IOException) {}
        }
    }

    private fun readStderr() {
        try {
            InputStreamReader(process.errorStream, Charsets.UTF_8).use { reader ->
                val buffer = CharArray(4096)
                while (true) {
                    val count = reader.read(buffer)
                    if (count < 0) break
                    stderr.append(String(buffer, 0, count))
                }
            }
        } catch (_: IOException) {}
    }

    private fun onProcessExit() {
        // Answers and diagnostics written just before the exit are still read.
        reader.join(1_000)
        errors.join(1_000)
        val code = process.exitValue()
        stopReason.compareAndSet(null, StopReason.CRASHED)
        val reason = stopReason.get()!!
        dead.set(true)
        for (id in pending.keys.toList()) pending.remove(id)?.completeExceptionally(HostFailure(HostErrors.CRASHED, reason.message))
        writer.interrupt()
        exited.complete(code)
        events.emit(event("exited") { put("code", code); put("reason", reason.label) })
        onExit(this, reason, code)
    }

    /**
     * Ends the process. A graceful stop closes stdin and waits; a failure, or a process still running, gets SIGTERM on
     * its whole tree, then SIGKILL.
     */
    suspend fun stop(reason: StopReason) {
        markStopping(reason)
        if (!::process.isInitialized) return
        val tree = process.descendants().toList()
        val graceful = !reason.failure && run {
            writer.interrupt()
            withTimeoutOrNull(config.closeStdinWaitMillis) { exited.await() } != null
        }
        val leftovers = (tree + process.descendants().toList()).distinctBy { it.pid() }.filter { it.isAlive }
        if (graceful && leftovers.isEmpty()) return
        if (!graceful || process.isAlive) events.emit(event("killed") { put("reason", reason.label) })
        process.toHandle().destroy()
        leftovers.forEach { it.destroy() }
        val deadline = System.currentTimeMillis() + config.terminateWaitMillis
        while (System.currentTimeMillis() < deadline && (process.isAlive || leftovers.any { it.isAlive })) delay(50)
        if (process.isAlive) process.destroyForcibly()
        leftovers.filter { it.isAlive }.forEach { it.destroyForcibly() }
        writer.interrupt()
        withTimeoutOrNull(config.terminateWaitMillis) { exited.await() }
    }

    /** Immediate SIGKILL of the process and its descendants, when the host cannot wait (its own cancellation). */
    fun kill() {
        markStopping(StopReason.SHUTDOWN)
        if (!::process.isInitialized) return
        process.descendants().forEach { it.destroyForcibly() }
        process.destroyForcibly()
    }

    fun event(name: String, extra: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit = {}) = buildJsonObject {
        put("event", name)
        describe.forEach { (key, value) -> put(key, value) }
        extra()
    }

    private fun daemon(name: String, body: () -> Unit): Thread =
        Thread.ofPlatform().daemon().name("mcp-${key.take(12)}-$name").start(body)

    companion object {
        /** [command] itself when it holds a slash, else the first executable of that name on [path]. */
        fun resolve(command: String, path: String): String? =
            if ('/' in command) command.takeIf { java.nio.file.Path.of(it).let { file -> Files.isRegularFile(file) && Files.isExecutable(file) } }
            else path.split(':').filter { it.isNotEmpty() }.map { java.nio.file.Path.of(it, command) }
                .firstOrNull { Files.isRegularFile(it) && Files.isExecutable(it) }?.toString()

        /** Newest first, as the gateway's HTTP upstream. */
        val PROTOCOL_VERSIONS = listOf("2025-11-25", "2025-06-18", "2025-03-26")
        const val MAX_LIST_PAGES = 16
        const val MAX_TOOLS = 256
        private const val METHOD_NOT_FOUND = -32601
        private const val NEWLINE = '\n'.code.toByte()
    }
}
