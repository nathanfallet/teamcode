package me.nathanfallet.teamcode.client

import kotlinx.coroutines.*
import kotlin.uuid.Uuid
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.HttpTimeoutConfig
import io.ktor.client.plugins.timeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.resources.Resources
import io.ktor.client.plugins.resources.get
import io.ktor.client.request.*
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.utils.io.readRemaining
import kotlinx.io.readByteArray
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.json
import me.nathanfallet.teamcode.api.Serialization
import me.nathanfallet.teamcode.api.models.*
import me.nathanfallet.teamcode.api.resources.*

/** The factory must create independently owned HTTP clients; this SDK owns their lifetime. */
class TeamCodeClient(
    serverUrl: String,
    private val token: suspend () -> String?,
    private val createHttpClient: (io.ktor.client.HttpClientConfig<*>.() -> Unit) -> HttpClient,
) : AutoCloseable {
    private val origin = Url(serverUrl.also { require(it.isNotBlank()) { "A TeamCode server URL is required." } }).also {
        require(it.protocol == URLProtocol.HTTPS ||
            (it.protocol == URLProtocol.HTTP && it.host in setOf("localhost", "127.0.0.1", "::1"))) {
            "TeamCode requires HTTPS except on loopback."
        }
        require(it.user.isNullOrEmpty() && it.password.isNullOrEmpty() && it.encodedPath in setOf("", "/") &&
            it.parameters.isEmpty() && it.fragment.isEmpty()) { "Use a server origin without credentials, path, query or fragment." }
    }.toString().trimEnd('/')
    private val configureHttpClient: io.ktor.client.HttpClientConfig<*>.() -> Unit = {
        expectSuccess = false
        followRedirects = false // A redirect must not carry a team credential to another origin.
        install(HttpTimeout) {
            connectTimeoutMillis = 10_000
            socketTimeoutMillis = 300_000
            requestTimeoutMillis = HttpTimeoutConfig.INFINITE_TIMEOUT_MS
        }
        install(Resources)
        install(ContentNegotiation) { json(Serialization.json) }
    }

    private val http = createHttpClient(configureHttpClient)

    private val inferenceClosed = CompletableDeferred<Unit>()

    suspend fun capabilities(): Capabilities {
        val response = http.get(CapabilitiesResource()) { url.takeFrom("$origin/api/v1/capabilities"); authenticate(); timeout { requestTimeoutMillis = 10_000 } }
        checkResponse(response)
        return response.body()
    }

    suspend fun status(): GatewayStatus {
        val response = http.get(StatusResource()) { url.takeFrom("$origin/api/v1/status"); authenticate(); timeout { requestTimeoutMillis = 10_000 } }
        checkResponse(response)
        return response.body()
    }

    suspend fun login(username: String, password: String, device: String = "TeamCode CLI", installationId: String? = null): LoginResponse =
        request("/api/v1/auth/login", HttpMethod.Post, LoginRequest(username, password, device, installationId))
    suspend fun me(): MemberView = request("/api/v1/auth/me", HttpMethod.Get)
    suspend fun changePassword(current: String, replacement: String): SuccessResponse =
        request("/api/v1/auth/password", HttpMethod.Post, PasswordRequest(current, replacement))
    suspend fun logout(): SuccessResponse = request("/api/v1/auth/logout", HttpMethod.Post)
    suspend fun directive(sessionId: String? = null): DirectiveResponse = if (sessionId == null)
        request("/api/v1/directives/current", HttpMethod.Get) else request("/api/v1/directives/current", HttpMethod.Post, DirectiveScope(sessionId))
    suspend fun claimDirective(sessionId: String, stage: String): DirectiveClaimResponse = request("/api/v1/directives/claim", HttpMethod.Post, DirectiveClaimRequest(sessionId, stage))
    suspend fun acknowledgeDirective(id: String, stage: String, sessionId: String? = null, claimId: String? = null): SuccessResponse = request("/api/v1/directives/receipt", HttpMethod.Post, DirectiveReceipt(id, stage, sessionId, claimId))
    suspend fun capacity(): CapacityView = request("/api/v1/capacity", HttpMethod.Get)
    suspend fun accounts(): List<AccountView> = request("/api/v1/accounts", HttpMethod.Get)
    suspend fun enrollAccount(input: EnrollAccountRequest): EnrollAccountResponse = request("/api/v1/accounts", HttpMethod.Post, input)
    suspend fun handoverAccount(id: String, accessOnly: Boolean = false): AccountView {
        require(Regex("[a-zA-Z0-9-]{1,36}").matches(id))
        return request("/api/v1/accounts/$id/${if (accessOnly) "access-only" else "handover"}", HttpMethod.Post)
    }
    suspend fun routes(): List<RouteRuleView> = request("/api/v1/routes", HttpMethod.Get)
    suspend fun saveRoute(rule: RouteRuleView): RouteRuleView {
        require(Regex("[a-zA-Z0-9_-]{1,36}").matches(rule.id))
        return request("/api/v1/routes/${rule.id}", HttpMethod.Put, rule)
    }
    suspend fun deleteRoute(id: String, version: Long): SuccessResponse {
        require(Regex("[a-zA-Z0-9_-]{1,36}").matches(id) && version > 0)
        return request("/api/v1/routes/$id", HttpMethod.Delete, RouteDeleteRequest(version))
    }
    suspend fun audit(beforeId: Long? = null): AuditPageView {
        require(beforeId == null || beforeId > 0) { "Use a positive audit event identifier." }
        return request("/api/v1/audit" + (beforeId?.let { "?before=$it" } ?: ""), HttpMethod.Get)
    }
    suspend fun members(): List<MemberView> = request("/api/v1/members", HttpMethod.Get)
    suspend fun serviceKeys(memberId: String? = null): List<ServiceKeyView> = request(memberPath(memberId) + "/service-keys", HttpMethod.Get)
    suspend fun createServiceKey(input: CreateServiceKeyRequest, memberId: String? = null): CreatedServiceKeyResponse =
        request(memberPath(memberId) + "/service-keys", HttpMethod.Post, input)
    suspend fun revokeServiceKey(id: String) = revoke("service-keys", id)
    suspend fun devices(memberId: String? = null): List<DeviceView> = request(memberPath(memberId) + "/devices", HttpMethod.Get)
    suspend fun authSessions(memberId: String? = null): List<AuthSessionView> = request(memberPath(memberId) + "/auth/sessions", HttpMethod.Get)
    suspend fun renameDevice(id: String, name: String): DeviceView {
        identifier(id)
        return request("/api/v1/devices/$id", HttpMethod.Patch, RenameDeviceRequest(name))
    }
    suspend fun revokeDevice(id: String) = revoke("devices", id)
    suspend fun revokeAuthSession(id: String) = revoke("auth/sessions", id)
    private suspend fun revoke(resource: String, id: String) {
        identifier(id)
        checkResponse(http.delete("$origin/api/v1/$resource/$id") { authenticate(); timeout { requestTimeoutMillis = 10_000 } })
    }
    private fun memberPath(id: String?): String {
        if (id == null) return "/api/v1"
        identifier(id)
        return "/api/v1/members/$id"
    }
    private fun identifier(id: String) { require(Regex("[a-zA-Z0-9-]{1,36}").matches(id)) { "Invalid identifier." } }

    private suspend inline fun <reified R> request(path: String, method: HttpMethod): R {
        val response = http.request("$origin$path") { this.method = method; authenticate(); timeout { requestTimeoutMillis = 10_000 } }
        checkResponse(response)
        return response.body()
    }
    private suspend inline fun <reified R, reified B> request(path: String, method: HttpMethod, body: B): R {
        val response = http.request("$origin$path") { this.method = method; contentType(ContentType.Application.Json); setBody(body); authenticate(); timeout { requestTimeoutMillis = 30_000 } }
        checkResponse(response)
        return response.body()
    }

    /** MCP servers currently assigned to the signed-in member; each has its own gateway endpoint. */
    suspend fun mcpServers(): List<McpServerView> = request("/api/v1/mcp/servers", HttpMethod.Get)

    /**
     * Relays one MCP JSON-RPC message to the gateway endpoint of an assigned [server] and returns the gateway's status
     * and bounded JSON body unchanged, including JSON-RPC errors, so the agent sees what the gateway decided.
     */
    suspend fun forwardMcp(server: String, body: ByteArray, protocolVersion: String? = null): McpRelayResponse {
        require(MCP_SERVER_NAME.matches(server)) { "Invalid MCP server name." }
        return http.preparePost("$origin/mcp/$server") {
            contentType(ContentType.Application.Json)
            accept(ContentType.Application.Json)
            protocolVersion?.let { header("MCP-Protocol-Version", it) }
            authenticate()
            // The gateway bounds every upstream call; this only stops a stalled connection.
            timeout { requestTimeoutMillis = 15 * 60_000 }
            setBody(body)
        }.execute { response ->
            // Streamed and bounded: the response is never buffered whole before the size check.
            val bytes = response.bodyAsChannel().readRemaining(MCP_RESPONSE_LIMIT + 1L).readByteArray()
            if (bytes.size > MCP_RESPONSE_LIMIT) throw TeamCodeException(502, "The MCP gateway response exceeds the limit.")
            McpRelayResponse(response.status.value, bytes)
        }
    }

    /** The body must be consumed inside [receive]; no full response is buffered by this SDK. */
    suspend fun forwardMessages(
        body: ByteArray,
        headers: Map<String, String> = emptyMap(),
        receive: suspend (HttpResponse) -> Unit,
    ) = forwardInference("/v1/messages", body, headers, receive)

    suspend fun forwardInference(path: String, body: ByteArray, headers: Map<String, String> = emptyMap(), receive: suspend (HttpResponse) -> Unit) {
        require(path in setOf("/v1/messages", "/v1/responses")) { "Unsupported inference path." }
        check(!inferenceClosed.isCompleted) { "The TeamCode client is closed." }
        val requestId = Uuid.random().toString()
        // Capture once: a config change must not send cancellation with another identity.
        val credential = token()?.takeIf { it.isNotBlank() }
        var consumed = false
        // Curl/WinHTTP can retain a pre-header request after coroutine cancellation. An
        // independently owned engine lets us close that transport without cancelling peers.
        // Management keeps its shared connection pool; inference engines live for one call.
        try {
            createHttpClient(configureHttpClient).use { inference ->
                lateinit var requestJob: Job
                val statement = inference.preparePost("$origin$path") {
                    requestJob = executionContext
                    contentType(ContentType.Application.Json)
                    for ((name, value) in headers) {
                        require(name.lowercase() in FORWARDED_HEADERS) { "Unsupported inference header." }
                        header(name, value)
                    }
                    header("x-teamcode-client-request-id", requestId)
                    credential?.let { bearerAuth(it) }
                    setBody(body)
                }
                coroutineScope {
                    val cancellation = launch(start = CoroutineStart.UNDISPATCHED) {
                        try {
                            inferenceClosed.await()
                            this@coroutineScope.cancel(CancellationException("The TeamCode client is closed."))
                        }
                        finally { requestJob.cancel(); inference.close() }
                    }
                    try { statement.execute { response -> receive(response); consumed = true } }
                    finally { cancellation.cancel() }
                }
            }
        } catch (error: CancellationException) {
            // Native engines may finish execute() before cancellation is rethrown by
            // coroutineScope itself. Catch outside the entire owned transport lifetime.
            if (!consumed) cancelInference(requestId, credential)
            throw error
        } catch (error: Exception) {
            // Some engines report an I/O failure while their caller is already cancelled.
            if (!consumed && !currentCoroutineContext().isActive) cancelInference(requestId, credential)
            throw error
        }
    }

    /** Best effort, independently owned control transport survives SDK/inference shutdown. */
    private suspend fun cancelInference(requestId: String, credential: String?) = withContext(NonCancellable) {
        withTimeoutOrNull(2_500) {
            createHttpClient(configureHttpClient).use { control ->
                repeat(2) { attempt ->
                    try {
                        val response = control.post("$origin/api/v1/requests/$requestId/cancel") {
                            credential?.let { bearerAuth(it) }
                            timeout { connectTimeoutMillis = 750; socketTimeoutMillis = 750; requestTimeoutMillis = 1_000 }
                        }
                        if (response.status.value < 500) return@withTimeoutOrNull
                    } catch (error: TimeoutCancellationException) {
                        currentCoroutineContext().ensureActive()
                    } catch (error: CancellationException) { throw error }
                    catch (_: Exception) { /* Retry only the idempotent cancellation control call. */ }
                    if (attempt == 0) delay(100)
                }
            }
        }
        Unit
    }

    private suspend fun HttpRequestBuilder.authenticate() {
        token()?.takeIf { it.isNotBlank() }?.let { bearerAuth(it) }
    }

    private suspend fun checkResponse(response: HttpResponse) {
        if (!response.status.isSuccess()) {
            // Do not expose raw response bodies: a proxy/provider may echo secrets.
            throw TeamCodeException(response.status.value, "TeamCode returned HTTP ${response.status.value}.")
        }
    }

    override fun close() { inferenceClosed.complete(Unit); http.close() }

    companion object {
        val FORWARDED_HEADERS: Set<String> = me.nathanfallet.teamcode.api.InferenceHeaders.names
        /** MCP variant target names, which are also the last segment of the gateway's `/mcp/<server>` endpoints. */
        val MCP_SERVER_NAME = Regex("[a-z0-9][a-z0-9._-]{0,63}")
        private const val MCP_RESPONSE_LIMIT = 65 * 1024 * 1024 // The largest gateway response limit plus its JSON-RPC envelope.
    }
}

class TeamCodeException(val statusCode: Int, message: String) : Exception(message)

/** A gateway MCP response relayed as is: HTTP status and JSON body (empty for an accepted notification). */
class McpRelayResponse(val status: Int, val body: ByteArray)
