package me.nathanfallet.teamcode.infrastructure.mcp

import io.ktor.client.HttpClient
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.utils.io.*
import java.net.InetAddress
import java.net.URI
import java.net.UnknownHostException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.*
import me.nathanfallet.teamcode.domain.cloud.CloudAdministrationService
import me.nathanfallet.teamcode.domain.mcp.*
import me.nathanfallet.teamcode.domain.variants.EffectiveMcpServer

/** Decides whether the gateway may connect to a host. The default refuses every non-public address. */
fun interface McpAddressPolicy {
    /** @throws McpUpstreamFailure when the destination is not allowed. */
    suspend fun check(host: String)

    companion object {
        /**
         * Resolves the host and refuses loopback, private, link-local (including cloud metadata), carrier-grade NAT,
         * unique-local, multicast and unspecified addresses, for a clear error before the session opens. The connection
         * itself goes through the same check in the HTTP client's [me.nathanfallet.teamcode.infrastructure.network.PinnedDns]
         * resolver, which dials the address it checked, so DNS rebinding between the two lookups gains nothing.
         */
        val PUBLIC_ONLY = McpAddressPolicy { host ->
            val addresses = try { withContext(Dispatchers.IO) { InetAddress.getAllByName(host) } }
                catch (_: UnknownHostException) { throw McpUpstreamFailure(McpOutcome.UPSTREAM_ERROR, "The MCP server host could not be resolved.") }
            if (addresses.isEmpty() || addresses.any { !isPublic(it) })
                throw McpUpstreamFailure(McpOutcome.UPSTREAM_ERROR, "The MCP server resolves to a private or reserved address, which the gateway refuses.")
        }

        /** [CloudAdministrationService.publicAddress], shared with webhooks. */
        fun isPublic(address: InetAddress): Boolean = CloudAdministrationService.publicAddress(address.address)
    }
}

/**
 * Stateless Streamable HTTP client for upstream MCP servers. Each operation opens its own MCP session (initialize,
 * initialized, the request, then a best-effort session DELETE), so no replica keeps upstream state between calls.
 * The upstream credential is attached here and scrubbed from everything returned.
 */
class HttpMcpUpstream(
    private val http: HttpClient,
    private val addresses: McpAddressPolicy = McpAddressPolicy.PUBLIC_ONLY,
    private val clientVersion: String = "dev",
    /** Only for in-process test upstreams. Variant validation already requires HTTPS endpoints. */
    private val allowHttp: Boolean = false,
) : McpUpstream {
    override suspend fun listTools(server: EffectiveMcpServer, limits: McpLimits): List<UpstreamTool> =
        session(server, limits, limits.listTimeoutMillis) {
            val tools = mutableListOf<UpstreamTool>()
            var cursor: String? = null
            for (page in 0 until MAX_LIST_PAGES) {
                val result = request("tools/list", cursor?.let { buildJsonObject { put("cursor", it) } }).result()
                val entries = result["tools"] as? JsonArray ?: throw protocol()
                for (entry in entries) {
                    val tool = entry as? JsonObject ?: continue
                    val name = (tool["name"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: continue
                    tools += UpstreamTool(name, scrub(tool).toString())
                    if (tools.size >= limits.maxToolsPerServer) return@session tools
                }
                cursor = (result["nextCursor"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: break
            }
            tools
        }

    override suspend fun callTool(server: EffectiveMcpServer, tool: String, arguments: String?, limits: McpLimits): UpstreamCallResult {
        val parsed = arguments?.let {
            (try { Json.parseToJsonElement(it) } catch (_: SerializationException) { null }) as? JsonObject
                ?: throw McpUpstreamFailure(McpOutcome.TOOL_ERROR, "Tool arguments must be a JSON object.")
        }
        return session(server, limits, limits.callTimeoutMillis) {
            val response = request("tools/call", buildJsonObject { put("name", tool); parsed?.let { put("arguments", it) } })
            response.error()?.let { (code, message) ->
                throw McpUpstreamFailure(McpOutcome.TOOL_ERROR, "The MCP server returned error $code: ${scrub(message).take(1_000)}")
            }
            val result = response.result()
            UpstreamCallResult(scrub(result).toString(), (result["isError"] as? JsonPrimitive)?.booleanOrNull == true, bytesRead)
        }
    }

    private class Session(val endpoint: String, val credentialHeader: Pair<String, String>?, val secret: String?, val limits: McpLimits) {
        var sessionId: String? = null
        var protocolVersion: String? = null
        var nextId = 1L
        var bytesRead = 0L
        fun scrub(text: String) = if (secret.isNullOrEmpty()) text else text.replace(secret, "[REDACTED]")
        /** Scrubs decoded string values and keys, so the result stays valid JSON whatever the secret looks like. */
        fun scrub(element: JsonElement): JsonElement = when (element) {
            is JsonObject -> JsonObject(element.entries.associate { (key, value) -> scrub(key) to scrub(value) })
            is JsonArray -> JsonArray(element.map { scrub(it) })
            is JsonPrimitive -> if (element is JsonNull || secret.isNullOrEmpty() || !element.content.contains(secret)) element else JsonPrimitive(scrub(element.content))
        }
        fun scrub(element: JsonObject) = scrub(element as JsonElement) as JsonObject
    }

    private suspend fun <T> session(server: EffectiveMcpServer, limits: McpLimits, timeoutMillis: Long, block: suspend Session.() -> T): T {
        val uri = URI(server.endpoint)
        if (uri.scheme != "https" && !(allowHttp && uri.scheme == "http")) throw McpUpstreamFailure(McpOutcome.UPSTREAM_ERROR, "The MCP endpoint must use HTTPS.")
        val secret = server.credential?.reveal()
        val header = secret?.let { credentialHeader(server.config, it) }
        val session = Session(server.endpoint, header, secret, limits)
        val outcome = withTimeoutOrNull(timeoutMillis) {
            addresses.check(uri.host)
            try {
                val initialized = session.request("initialize", buildJsonObject {
                    put("protocolVersion", PROTOCOL_VERSIONS.first())
                    putJsonObject("capabilities") {}
                    putJsonObject("clientInfo") { put("name", "teamcode-mcp-gateway"); put("version", clientVersion) }
                }).result()
                val version = (initialized["protocolVersion"] as? JsonPrimitive)?.content
                if (version !in PROTOCOL_VERSIONS) throw McpUpstreamFailure(McpOutcome.UPSTREAM_ERROR, "The MCP server does not support a protocol version this gateway speaks.")
                session.protocolVersion = version
                session.notify("notifications/initialized")
                Holder(session.block())
            } finally {
                // Also on timeout or disconnect: each operation opened its own upstream session.
                session.sessionId?.let { withContext(NonCancellable) { withTimeoutOrNull(CLOSE_TIMEOUT_MILLIS) { session.close() } } }
            }
        } ?: throw McpUpstreamFailure(McpOutcome.TIMEOUT, "The MCP server did not answer in time.")
        return outcome.value
    }
    private class Holder<T>(val value: T)

    private fun credentialHeader(config: Map<String, String>, secret: String): Pair<String, String> {
        val name = config[CREDENTIAL_HEADER_KEY]?.trim()
        if (name == null || name.equals(HttpHeaders.Authorization, ignoreCase = true)) return HttpHeaders.Authorization to "Bearer $secret"
        if (!headerName.matches(name) || name.lowercase() in reservedHeaders)
            throw McpUpstreamFailure(McpOutcome.UPSTREAM_ERROR, "The variant's $CREDENTIAL_HEADER_KEY configuration is not a usable header name.")
        return name to secret
    }

    private suspend fun Session.notify(method: String) {
        send(buildJsonObject { put("jsonrpc", "2.0"); put("method", method) }, null)
    }
    private suspend fun Session.request(method: String, params: JsonObject?): JsonObject {
        val id = nextId++
        return send(buildJsonObject { put("jsonrpc", "2.0"); put("id", id); put("method", method); params?.let { put("params", it) } }, id)
            ?: throw protocol()
    }
    private suspend fun Session.close() {
        try {
            http.delete(endpoint) { headers(this@close) }.discardRemaining()
        } catch (error: CancellationException) { throw error } catch (_: Exception) {}
    }
    private fun HttpRequestBuilder.headers(session: Session) {
        session.credentialHeader?.let { (name, value) -> header(name, value) }
        session.sessionId?.let { header(SESSION_HEADER, it) }
        session.protocolVersion?.let { header(PROTOCOL_HEADER, it) }
    }

    /** Sends one message. Returns the JSON-RPC response for [expectedId], or null for an accepted notification. */
    private suspend fun Session.send(message: JsonObject, expectedId: Long?): JsonObject? = try {
        http.preparePost(endpoint) {
            headers(this@send)
            accept(ContentType.Application.Json); accept(ContentType.Text.EventStream)
            contentType(ContentType.Application.Json)
            setBody(message.toString())
        }.execute { response ->
            when {
                response.status == HttpStatusCode.Unauthorized || response.status == HttpStatusCode.Forbidden ->
                    throw McpUpstreamFailure(McpOutcome.UPSTREAM_ERROR, "The MCP server rejected the gateway's credential (HTTP ${response.status.value}).")
                response.status == HttpStatusCode.NotFound && sessionId != null ->
                    throw McpUpstreamFailure(McpOutcome.UPSTREAM_ERROR, "The MCP server ended the session.")
                !response.status.isSuccess() -> throw McpUpstreamFailure(McpOutcome.UPSTREAM_ERROR, "The MCP server answered HTTP ${response.status.value}.")
            }
            if (sessionId == null) response.headers[SESSION_HEADER]?.takeIf { sessionIdPattern.matches(it) }?.let { sessionId = it }
            if (expectedId == null) { response.discardRemaining(); return@execute null }
            val type = response.contentType()?.withoutParameters()
            val channel = response.bodyAsChannel()
            when (type) {
                ContentType.Application.Json -> parse(readBounded(channel), expectedId)
                ContentType.Text.EventStream -> readEvents(channel, expectedId)
                else -> throw protocol()
            } ?: throw protocol()
        }
    } catch (failure: McpUpstreamFailure) { throw failure }
    catch (error: CancellationException) { throw error }
    catch (_: SerializationException) { throw protocol() }
    catch (_: IllegalArgumentException) { throw protocol() }
    catch (error: Exception) { throw McpUpstreamFailure(McpOutcome.UPSTREAM_ERROR, "The MCP server could not be reached.").also { it.addSuppressed(error) } }

    private suspend fun HttpResponse.discardRemaining() { bodyAsChannel().discard(MAX_DISCARD_BYTES) }

    private suspend fun Session.readBounded(channel: ByteReadChannel): String {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = channel.readAvailable(buffer, 0, buffer.size)
            if (count < 0) break
            if (count == 0) continue
            bytesRead += count
            if (bytesRead > limits.maxResponseBytes) throw McpUpstreamFailure(McpOutcome.TOO_LARGE, "The MCP server's response exceeds the gateway limit.")
            output.write(buffer, 0, count)
        }
        return output.toString(Charsets.UTF_8)
    }

    /** Reads server-sent events until the response to [expectedId]; notifications and server requests are ignored. */
    private suspend fun Session.readEvents(channel: ByteReadChannel, expectedId: Long): JsonObject? {
        val line = java.io.ByteArrayOutputStream()
        val data = StringBuilder()
        val buffer = ByteArray(8192)
        while (true) {
            val count = channel.readAvailable(buffer, 0, buffer.size)
            if (count < 0) return null
            if (count == 0) continue
            bytesRead += count
            if (bytesRead > limits.maxResponseBytes) throw McpUpstreamFailure(McpOutcome.TOO_LARGE, "The MCP server's response exceeds the gateway limit.")
            for (index in 0 until count) {
                val byte = buffer[index]
                if (byte != '\n'.code.toByte()) { line.write(byte.toInt()); continue }
                val text = line.toString(Charsets.UTF_8).removeSuffix("\r")
                line.reset()
                when {
                    text.isEmpty() -> {
                        if (data.isNotEmpty()) parse(data.toString(), expectedId)?.let { return it }
                        data.clear()
                    }
                    text.startsWith("data:") -> { if (data.isNotEmpty()) data.append('\n'); data.append(text.removePrefix("data:").removePrefix(" ")) }
                }
            }
        }
    }

    private fun parse(text: String, expectedId: Long): JsonObject? {
        val message = Json.parseToJsonElement(text) as? JsonObject ?: return null
        val id = (message["id"] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull
        return message.takeIf { id == expectedId && ("result" in it || "error" in it) }
    }
    private fun JsonObject.result(): JsonObject {
        error()?.let { throw McpUpstreamFailure(McpOutcome.UPSTREAM_ERROR, "The MCP server returned JSON-RPC error ${it.first}.") }
        return this["result"] as? JsonObject ?: throw protocol()
    }
    private fun JsonObject.error(): Pair<Long, String>? {
        val error = this["error"] as? JsonObject ?: return null
        return ((error["code"] as? JsonPrimitive)?.longOrNull ?: 0L) to ((error["message"] as? JsonPrimitive)?.content ?: "")
    }
    private fun protocol() = McpUpstreamFailure(McpOutcome.UPSTREAM_ERROR, "The MCP server sent an invalid MCP response.")

    companion object {
        /** Newest first. The gateway offers the first and accepts any of them back. */
        val PROTOCOL_VERSIONS = listOf("2025-11-25", "2025-06-18", "2025-03-26")
        /** Optional non-secret variant configuration: the header that carries the credential instead of `Authorization: Bearer`. */
        const val CREDENTIAL_HEADER_KEY = "credentialHeader"
        const val SESSION_HEADER = "Mcp-Session-Id"
        const val PROTOCOL_HEADER = "MCP-Protocol-Version"
        private const val MAX_LIST_PAGES = 16
        private const val CLOSE_TIMEOUT_MILLIS = 2_000L
        private const val MAX_DISCARD_BYTES = 64L * 1024
        private val headerName = Regex("[A-Za-z0-9-]{1,64}")
        private val sessionIdPattern = Regex("[\\x21-\\x7E]{1,256}")
        private val reservedHeaders = setOf("host", "content-length", "content-type", "accept", "connection", "transfer-encoding", "cookie",
            "mcp-session-id", "mcp-protocol-version", "origin", "te", "upgrade", "proxy-authorization", "forwarded", "x-forwarded-for")
    }
}
