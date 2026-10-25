package me.nathanfallet.teamcode.infrastructure.mcp

import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.preparePost
import io.ktor.client.request.prepareGet
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpStatement
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.utils.io.readAvailable
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.*
import me.nathanfallet.teamcode.domain.mcp.*
import me.nathanfallet.teamcode.domain.team.TeamFailure
import me.nathanfallet.teamcode.domain.variants.EffectiveMcpServer
import me.nathanfallet.teamcode.domain.variants.McpServerTransport
import me.nathanfallet.teamcode.domain.variants.StdioEnvironment
import me.nathanfallet.teamcode.domain.variants.StdioLauncher

/**
 * The gateway's client of the MCP stdio host (host protocol v1, docs/mcp.md), for hosted commands (transport stdio).
 * Every request carries the whole definition of the one process it targets, its secret variables included, so the host
 * keeps nothing the server depends on and no replica keeps state either. The host scrubs the process's secrets from
 * what it returns; this client scrubs them again, as it does for an HTTPS credential.
 *
 * [baseUrl] is fixed by the deployment (`TEAMCODE_MCP_URL`, the chart's internal Service): a variant never chooses an
 * address, so this client does not go through the public-address policy of [HttpMcpUpstream].
 */
class HostMcpUpstream(
    private val http: HttpClient,
    baseUrl: String,
    private val token: String,
    private val monotonicMillis: () -> Long = { System.nanoTime() / 1_000_000 },
) : McpUpstream, McpProcessHost, AutoCloseable {
    private val base = baseUrl.trimEnd('/')
    private val background = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    /** Keys warmed recently, so a client that reads its server list often does not send a warm-up each time. */
    private val warmed = ConcurrentHashMap<String, Long>()
    @Volatile private var launcherCache: Pair<Long, Set<StdioLauncher>>? = null

    override val hostsCommands get() = true

    override suspend fun listTools(server: EffectiveMcpServer, limits: McpLimits): List<UpstreamTool> {
        val process = process(server)
        val deadline = limits.deadlineMillis(McpOperation.LIST, hosted = true)
        val started = monotonicMillis()
        val result = try { ops(process, "list", null, null, deadline, limits) } catch (failure: RetryableHostFailure) {
            // A host that restarted (rollout, out of memory) lost the process: one more list starts it again. A call is
            // never replayed, since a tool call is not idempotent.
            val remaining = deadline - (monotonicMillis() - started)
            if (remaining < MIN_RETRY_MILLIS) throw failure.failure
            try { ops(process, "list", null, null, remaining, limits) } catch (again: RetryableHostFailure) { throw again.failure }
        }
        val entries = result["tools"] as? JsonArray ?: throw protocol()
        val tools = mutableListOf<UpstreamTool>()
        for (entry in entries) {
            val tool = entry as? JsonObject ?: continue
            val name = (tool["name"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: continue
            tools += UpstreamTool(name, process.scrubber.scrub(tool).toString())
            if (tools.size >= limits.maxToolsPerServer) break
        }
        return tools
    }

    override suspend fun callTool(server: EffectiveMcpServer, tool: String, arguments: String?, limits: McpLimits): UpstreamCallResult {
        val parsed = arguments?.let {
            (try { Json.parseToJsonElement(it) } catch (_: SerializationException) { null }) as? JsonObject
                ?: throw McpUpstreamFailure(McpOutcome.TOOL_ERROR, "Tool arguments must be a JSON object.")
        }
        val process = process(server)
        val result = try { ops(process, "call", tool, parsed, limits.deadlineMillis(McpOperation.CALL, hosted = true), limits) }
            catch (failure: RetryableHostFailure) { throw failure.failure }
        val text = process.scrubber.scrub(result).toString()
        return UpstreamCallResult(text, (result["isError"] as? JsonPrimitive)?.booleanOrNull == true, text.toByteArray().size.toLong())
    }

    override fun warm(servers: List<EffectiveMcpServer>) {
        val now = monotonicMillis()
        if (warmed.size > MAX_WARMED_KEYS) warmed.entries.removeIf { now - it.value > WARM_INTERVAL_MILLIS }
        for (server in servers) {
            val process = try { process(server) } catch (_: McpUpstreamFailure) { continue }
            val previous = warmed[process.key]
            if (previous != null && now - previous < WARM_INTERVAL_MILLIS) continue
            warmed[process.key] = now
            background.launch {
                // Best effort: the agent's own tools/list starts the process anyway, with a longer deadline.
                try {
                    withTimeoutOrNull(WARM_TIMEOUT_MILLIS) {
                        request(HOST_WARM, buildJsonObject { put("key", process.key); put("spec", process.spec) }, MAX_CONTROL_BYTES)
                    }
                } catch (error: CancellationException) { throw error } catch (_: Exception) { warmed.remove(process.key, now) }
            }
        }
    }

    override suspend fun launchers(): Set<StdioLauncher> {
        launcherCache?.let { (at, launchers) -> if (monotonicMillis() - at < LAUNCHERS_CACHE_MILLIS) return launchers }
        val status = try {
            withTimeoutOrNull(CONTROL_TIMEOUT_MILLIS) { get(HOST_STATUS, MAX_STATUS_BYTES) } ?: throw HostUnreachable()
        } catch (error: CancellationException) { throw error } catch (_: Exception) {
            throw TeamFailure("mcp_host_unreachable", 503, "The MCP host of this server could not be reached, so a hosted command cannot be declared right now. Retry shortly.")
        }
        val launchers = (status["launchers"] as? JsonArray).orEmpty()
            .mapNotNull { name -> StdioLauncher.entries.find { it.wire == (name as? JsonPrimitive)?.content } }.toSet()
        launcherCache = monotonicMillis() to launchers
        return launchers
    }

    override suspend fun retain(keys: Collection<String>): Int {
        val answer = withTimeoutOrNull(CONTROL_TIMEOUT_MILLIS) {
            request(HOST_RETAIN, buildJsonObject { putJsonArray("keys") { keys.forEach { add(it) } } }, MAX_CONTROL_BYTES)
        } ?: throw HostUnreachable()
        answer.error?.let { throw IllegalStateException("The MCP host refused the retain sweep (${it.first}).") }
        return (answer.body["stopped"] as? JsonPrimitive)?.intOrNull ?: 0
    }

    override fun close() { background.cancel() }

    /** What the host needs to run [server], with the scrubber of its secret variables. */
    private class HostedProcess(val key: String, val spec: JsonObject, val scrubber: McpScrubber)

    private fun process(server: EffectiveMcpServer): HostedProcess {
        val transport = server.transport as? McpServerTransport.Stdio
            ?: throw McpUpstreamFailure(McpOutcome.UPSTREAM_ERROR, "This MCP server is an HTTPS endpoint, not a hosted command.")
        val secrets = server.credential?.let { StdioEnvironment.decodeOrNull(it) ?: throw McpUpstreamFailure(McpOutcome.UPSTREAM_ERROR, "The hosted MCP server's secret variables could not be read.") }.orEmpty()
        val spec = buildJsonObject {
            put("launcher", transport.command.launcher.wire)
            put("package", transport.command.pkg)
            putJsonArray("args") { transport.command.args.forEach { add(it) } }
            putJsonObject("env") { server.config.forEach { (name, value) -> put(name, value) } }
            putJsonObject("secrets") { secrets.forEach { (name, value) -> put(name, value) } }
            put("declaredAt", transport.declaredAt)
        }
        return HostedProcess(transport.key, spec, McpScrubber(secrets.values))
    }

    private suspend fun ops(process: HostedProcess, op: String, tool: String?, arguments: JsonObject?, deadlineMillis: Long, limits: McpLimits): JsonObject {
        val body = buildJsonObject {
            put("key", process.key); put("spec", process.spec); put("op", op)
            tool?.let { put("tool", it) }; arguments?.let { put("arguments", it) }
            put("deadlineMs", deadlineMillis); put("maxResponseBytes", limits.maxResponseBytes)
        }
        // The host answers within the deadline it was given; the margin covers the request itself. Cancelling this
        // coroutine (the agent disconnected) closes the connection, which the host turns into notifications/cancelled.
        val answer = try {
            withTimeoutOrNull(deadlineMillis + HOST_MARGIN_MILLIS) { request(HOST_OPS, body, limits.maxResponseBytes.toLong() + RESPONSE_OVERHEAD_BYTES) }
                ?: throw McpUpstreamFailure(McpOutcome.TIMEOUT, "The MCP server did not answer in time.")
        } catch (failure: McpUpstreamFailure) { throw failure }
        catch (error: CancellationException) { throw error }
        catch (_: TooLarge) { throw McpUpstreamFailure(McpOutcome.TOO_LARGE, "The MCP server's response exceeds the gateway limit.") }
        catch (_: SerializationException) { throw protocolFromHost() }
        catch (_: IllegalArgumentException) { throw protocolFromHost() }
        catch (error: Exception) { throw RetryableHostFailure(McpUpstreamFailure(McpOutcome.UPSTREAM_ERROR, "The MCP host is unreachable.").also { it.addSuppressed(error) }) }
        val error = answer.error ?: (answer.body["error"] as? JsonObject)?.let { code(it) to message(it) }
        if (error != null) throw failure(error.first, process.scrubber.scrub(error.second).take(MAX_MESSAGE_LENGTH))
        return answer.body["result"] as? JsonObject ?: throw protocolFromHost()
    }

    private fun failure(code: String, message: String): Exception {
        fun upstream(text: String) = McpUpstreamFailure(McpOutcome.UPSTREAM_ERROR, text)
        return when (code) {
            "timeout" -> McpUpstreamFailure(McpOutcome.TIMEOUT, message.ifEmpty { "The MCP server did not answer in time." })
            "too_large" -> McpUpstreamFailure(McpOutcome.TOO_LARGE, "The MCP server's response exceeds the gateway limit.")
            "tool_error" -> McpUpstreamFailure(McpOutcome.TOOL_ERROR, message.ifEmpty { "The MCP server returned an error." })
            // The process ended or the host is stopping: a restarted host starts it again on the next operation.
            "crashed" -> RetryableHostFailure(upstream(message.ifEmpty { "The hosted MCP server stopped during the call." }))
            "shutting_down" -> RetryableHostFailure(upstream("The MCP host is restarting. Retry shortly."))
            "unauthorized", "unsupported_protocol" ->
                upstream("The MCP host refused this server's request: deploy the TeamCode server and its MCP host at the same version, with the same token.")
            "start_failed", "busy", "backoff", "capacity", "protocol", "invalid_spec" ->
                upstream(message.ifEmpty { "The hosted MCP server is unavailable ($code)." })
            else -> upstream("The MCP host failed ($code).".takeIf { CODE.matches(code) } ?: "The MCP host failed.")
        }
    }

    /** A host answer: its JSON body, and the error of a refused request (any status but 200). */
    private class HostAnswer(val body: JsonObject, val error: Pair<String, String>?)

    private suspend fun request(path: String, body: JsonObject, maxBytes: Long): HostAnswer =
        http.preparePost(base + path) {
            headers(); contentType(ContentType.Application.Json); setBody(body.toString())
        }.answer(maxBytes)

    private suspend fun get(path: String, maxBytes: Long): JsonObject {
        val answer = http.prepareGet(base + path) { headers() }.answer(maxBytes)
        answer.error?.let { throw IllegalStateException("The MCP host refused ${it.first}.") }
        return answer.body
    }

    private fun io.ktor.client.request.HttpRequestBuilder.headers() {
        header(HttpHeaders.Authorization, "Bearer $token")
        header(PROTOCOL_HEADER, PROTOCOL_VERSION)
    }

    private suspend fun HttpStatement.answer(maxBytes: Long): HostAnswer = execute { response ->
        val text = readBounded(response.bodyAsChannel(), maxBytes)
        val body = Json.parseToJsonElement(text) as? JsonObject ?: throw SerializationException("not an object")
        if (response.status.value == 200) HostAnswer(body, null)
        else {
            val error = body["error"] as? JsonObject
            // A proxy or an older host without a JSON error: still a refusal, named by its status.
            HostAnswer(body, (error?.let(::code) ?: "http_${response.status.value}") to (error?.let(::message) ?: ""))
        }
    }

    private fun code(error: JsonObject) = (error["code"] as? JsonPrimitive)?.contentOrNull?.takeIf { CODE.matches(it) } ?: "unknown"
    private fun message(error: JsonObject) = (error["message"] as? JsonPrimitive)?.contentOrNull.orEmpty()

    private class TooLarge : Exception()
    private class HostUnreachable : Exception()
    /** A failure after which a `tools/list` is worth one more attempt; a call reports [failure] as is. */
    private class RetryableHostFailure(val failure: McpUpstreamFailure) : Exception(failure.message)

    private suspend fun readBounded(channel: io.ktor.utils.io.ByteReadChannel, maxBytes: Long): String {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = channel.readAvailable(buffer, 0, buffer.size)
            if (count < 0) break
            if (count == 0) continue
            if (output.size() + count > maxBytes) throw TooLarge()
            output.write(buffer, 0, count)
        }
        return output.toString(Charsets.UTF_8)
    }

    private fun protocol() = McpUpstreamFailure(McpOutcome.UPSTREAM_ERROR, "The MCP server sent an invalid MCP response.")
    private fun protocolFromHost() = McpUpstreamFailure(McpOutcome.UPSTREAM_ERROR, "The MCP host sent an invalid answer.")

    companion object {
        const val PROTOCOL_HEADER = "TeamCode-Host-Protocol"
        const val PROTOCOL_VERSION = "1"
        private const val HOST_OPS = "/v1/ops"
        private const val HOST_WARM = "/v1/warm"
        private const val HOST_RETAIN = "/v1/retain"
        private const val HOST_STATUS = "/v1/status"
        private const val HOST_MARGIN_MILLIS = 5_000L
        private const val MIN_RETRY_MILLIS = 2_000L
        private const val CONTROL_TIMEOUT_MILLIS = 15_000L
        private const val WARM_TIMEOUT_MILLIS = 10_000L
        private const val WARM_INTERVAL_MILLIS = 60_000L
        private const val MAX_WARMED_KEYS = 4_096
        private const val LAUNCHERS_CACHE_MILLIS = 60_000L
        /** The JSON envelope around a result, which escaping can also grow. */
        private const val RESPONSE_OVERHEAD_BYTES = 1024L * 1024
        private const val MAX_CONTROL_BYTES = 64L * 1024
        /** The status names every process with up to 64 KiB of stderr each. */
        private const val MAX_STATUS_BYTES = 32L * 1024 * 1024
        private const val MAX_MESSAGE_LENGTH = 1_000
        private val CODE = Regex("[a-z0-9_]{1,40}")
    }
}
