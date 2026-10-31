package me.nathanfallet.teamcode.cli.proxy

import io.ktor.http.*
import io.ktor.client.statement.bodyAsChannel
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.utils.io.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.TimeMark
import kotlin.time.TimeSource
import kotlinx.io.readByteArray
import kotlinx.serialization.json.*
import me.nathanfallet.teamcode.client.TeamCodeClient
import okio.ByteString.Companion.encodeUtf8

/** The header of a service probe's challenge: 64 lowercase hexadecimal digits, fresh for each probe. */
internal const val SERVICE_CHALLENGE_HEADER = "TeamCode-Service-Challenge"
private val serviceChallenge = Regex("[0-9a-f]{64}")

/**
 * What only a holder of [localToken] can answer to [challenge]: HMAC-SHA256, keyed with the token, of the challenge, the
 * installation [serviceId] and the upstream [serverUrl]. The probe knows the last two and checks them through the proof,
 * so the answer to an unauthenticated challenge does not carry them. It reveals nothing about the token either, and a
 * proof is useless to another listener: answering a probe's fresh challenge takes the token, at that moment.
 */
internal fun serviceProof(localToken: String, challenge: String, serviceId: String, serverUrl: String?): String =
    "teamcode-service-v1\n$challenge\n$serviceId\n${serverUrl.orEmpty()}".encodeUtf8().hmacSha256(localToken.encodeUtf8()).hex()

/** Fixed inference and local guidance routes. No CONNECT or arbitrary destination. */
fun Application.localProxy(client: TeamCodeClient, localToken: String, maximumBodyBytes: Int = 2 * 1024 * 1024, serviceId: String? = null, serverUrl: String? = null,
    /** False while the upstream credential is unavailable: answer a retryable `503` rather than forward a request bound to be refused. */
    upstreamReady: (suspend () -> Boolean)? = null,
    /**
     * The credential of the artifact, wait and workflow relays when it is not [client]'s: a remote session's host relays them
     * with the credential confined to its session (`cloud.remote.session-credential.v1`), never with the member's login that
     * [client] holds. Null: those relays use [client], as under `teamcode run` and in a pod (whose [client] is already confined).
     */
    sessionClient: TeamCodeClient? = null,
    /** Receives the cause of a TeamCode refusal, once per cause for a while (see [RefusalNotices]). */
    report: ((String) -> Unit)? = null) {
    require(localToken.length >= 32) { "A local proxy token of at least 32 characters is required." }
    val refusals = RefusalNotices(report)
    routing {
        guidanceHook(client, localToken, report)
        if (serviceId != null) get("/_teamcode/service") {
            // Either the local token (older probes), or a challenge the answer proves the token on (audit L9): a probe
            // then never sends the token to whatever listens on the port before knowing it is this service.
            val challenge = call.request.header(SERVICE_CHALLENGE_HEADER)?.takeIf { serviceChallenge.matches(it) }
            if (challenge == null && !equalToken(call.request.header(HttpHeaders.Authorization)?.removePrefix("Bearer "), localToken)) {
                call.respond(HttpStatusCode.Unauthorized)
            } else if (call.request.local.serverHost !in setOf("localhost", "127.0.0.1", "::1") || call.request.header(HttpHeaders.Origin) != null) {
                call.respond(HttpStatusCode.Forbidden)
            } else {
                call.response.header(HttpHeaders.CacheControl, "no-store")
                call.respondText(buildJsonObject {
                    // Unauthenticated: the proof stands for the installation ID and the upstream origin, which only a
                    // caller holding the token sees in clear.
                    if (challenge != null) put("proof", serviceProof(localToken, challenge, serviceId, serverUrl))
                    else { put("id", serviceId); put("serverUrl", serverUrl) }
                    put("version", me.nathanfallet.teamcode.cli.VERSION)
                    putJsonArray("capabilities") { add(JsonPrimitive("workload-routing-v1")); add(JsonPrimitive("run-routing-v1")); add(JsonPrimitive("mcp-relay-v1")); add(JsonPrimitive("local-token-header-v1")); add(JsonPrimitive("artifact-relay-v1")); add(JsonPrimitive("activity-relay-v1")); add(JsonPrimitive("wait-relay-v1")); add(JsonPrimitive("workflow-relay-v1")) }
                }.toString(), ContentType.Application.Json)
            }
        }
        mcpRelay(client, localToken, maximumBodyBytes)
        artifactRelay(sessionClient ?: client, localToken, maximumBodyBytes)
        waitRelay(sessionClient ?: client, localToken, maximumBodyBytes)
        workflowRelay(sessionClient ?: client, localToken, maximumBodyBytes)
        for (path in listOf("/v1/messages", "/v1/responses")) {
            for (mode in listOf("interactive", "background", "run")) {
                val prefix = when (mode) { "background" -> "/_teamcode/workloads/background"; "run" -> "/_teamcode/runs/{runId}/{workload}"; else -> "" }
                route(prefix + path) {
                    install(io.ktor.server.http.HttpRequestLifecycle) { cancelCallOnClose = true }
                    post {
                        val auth = call.request.header(HttpHeaders.Authorization)
                        val key = call.request.header("x-api-key")
                        // A Claude Code that keeps its claude.ai login sends the capability in LOCAL_TOKEN_HEADER and
                        // its own OAuth bearer, which is ignored here and never forwarded (only InferenceHeaders are).
                        val header = call.request.header(LOCAL_TOKEN_HEADER)
                        if (!equalToken(auth?.removePrefix("Bearer "), localToken) && !equalToken(key, localToken) && !equalToken(header, localToken)) {
                            call.respondText("A local TeamCode capability is required.", status = HttpStatusCode.Unauthorized)
                            return@post
                        }
                        val host = call.request.local.serverHost
                        if (host !in setOf("localhost", "127.0.0.1", "::1") || call.request.header(HttpHeaders.Origin) != null) {
                            call.respondText("Browser or non-loopback destinations are not accepted.", status = HttpStatusCode.Forbidden)
                            return@post
                        }
                        if (call.request.contentType().withoutParameters() != ContentType.Application.Json) {
                            call.respondText("A JSON body is required.", status = HttpStatusCode.UnsupportedMediaType)
                            return@post
                        }
                        if (upstreamReady?.invoke() == false) {
                            call.response.header(HttpHeaders.RetryAfter, "15")
                            call.respondText("TeamCode inference is temporarily unavailable.", status = HttpStatusCode.ServiceUnavailable)
                            return@post
                        }
                        val buffer = ByteArray(8192)
                        val chunks = mutableListOf<ByteArray>()
                        var size = 0
                        val channel = call.receiveChannel()
                        while (true) {
                            val count = channel.readAvailable(buffer, 0, minOf(buffer.size, maximumBodyBytes - size + 1))
                            if (count < 0) break
                            if (count == 0) continue
                            if (size + count > maximumBodyBytes) {
                                call.respondText("Request is too large.", status = HttpStatusCode.PayloadTooLarge)
                                return@post
                            }
                            size += count
                            chunks.add(buffer.copyOf(count))
                        }
                        val body = ByteArray(size)
                        var offset = 0
                        for (chunk in chunks) { chunk.copyInto(body, offset); offset += chunk.size }
                        val headers = me.nathanfallet.teamcode.api.InferenceHeaders.capture(call.request.headers::getAll).toMutableMap()
                        val launchWorkload = if (mode == "run") call.parameters["workload"] else mode.takeIf { it == "background" }
                        if (launchWorkload != null) {
                            if (launchWorkload !in setOf("interactive", "background") || headers["x-teamcode-workload"]?.let { it != launchWorkload } == true) {
                                call.respondText("The workload header conflicts with this launch.", status = HttpStatusCode.BadRequest)
                                return@post
                            }
                            headers["x-teamcode-workload"] = launchWorkload
                        }
                        if (mode == "run") {
                            val runId = call.parameters["runId"].orEmpty()
                            if (!me.nathanfallet.teamcode.api.models.RunIdentifiers.isValid(runId) || headers["x-teamcode-run-id"]?.let { it != runId } == true) {
                                call.respondText("Invalid or conflicting launch identifier.", status = HttpStatusCode.BadRequest)
                                return@post
                            }
                            headers["x-teamcode-run-id"] = runId
                        }
                        client.forwardInference(path, body, headers) { upstream ->
                            RELAYED_HEADERS.forEach { name -> upstream.headers[name]?.let { call.response.header(name, it) } }
                            call.response.header(HttpHeaders.CacheControl, "no-store")
                            call.response.header("X-Accel-Buffering", "no")
                            val source = upstream.bodyAsChannel()
                            // A TeamCode refusal is small JSON written by the server, never provider content.
                            val head = refusals.inspect(upstream.status.value, upstream.headers["x-teamcode-error"], source)
                            // Ktor may schedule the writer after respond returns. Keep the SDK response
                            // scope open until the downstream has actually consumed/cancelled the body.
                            val consumed = CompletableDeferred<Unit>()
                            call.respondBytesWriter(upstream.contentType() ?: ContentType.Application.OctetStream, upstream.status) {
                                try {
                                    if (head.isNotEmpty()) writeFully(head)
                                    source.copyTo(this)
                                    consumed.complete(Unit)
                                } catch (error: Throwable) {
                                    consumed.completeExceptionally(error)
                                    throw error
                                }
                            }
                            consumed.await()
                        }
                    }
                }
            }
        }
    }
}

private val RELAYED_HEADERS = listOf("retry-after", "x-should-retry", "x-teamcode-error", "x-teamcode-request-id", "x-teamcode-model", "x-teamcode-provider")

/**
 * Prints the cause of a TeamCode refusal once: agents retry the same refusal many times and often show only a generic
 * error meanwhile. Each status and code is reported at most once per [interval], whatever other requests sharing the
 * proxy do meanwhile.
 */
internal class RefusalNotices(private val report: ((String) -> Unit)?, private val interval: Duration = 10.minutes,
    private val clock: TimeSource = TimeSource.Monotonic) {
    private val lock = Mutex()
    private val reported = mutableMapOf<String, TimeMark>()

    /** Returns the body bytes it read, which the caller relays before the rest of [body]. */
    suspend fun inspect(status: Int, code: String?, body: ByteReadChannel): ByteArray {
        if (report == null || status in 200..299) return ByteArray(0)
        if (code == null || !CODE.matches(code)) return ByteArray(0)
        val key = "$status $code"
        val first = lock.withLock {
            val last = reported[key]
            if (last != null && last.elapsedNow() < interval) return@withLock false
            if (reported.size >= 64) reported.clear()
            reported[key] = clock.markNow()
            true
        }
        if (!first) return ByteArray(0)
        val head = body.readRemaining(MAXIMUM_BODY_BYTES.toLong()).readByteArray()
        val message = if (!body.isClosedForRead) null else try {
            val error = (Json.parseToJsonElement(head.decodeToString()) as? JsonObject)?.get("error") as? JsonObject
            (error?.get("message") as? JsonPrimitive)?.takeIf { it.isString }?.content
        } catch (_: IllegalArgumentException) { null }
        report("TeamCode answered HTTP $status ($code): ${message?.let(::printable) ?: "see the agent's error."}")
        return head
    }

    private fun printable(text: String) = text.map { if (it.isISOControl()) ' ' else it }.joinToString("").trim().take(600)

    private companion object {
        val CODE = Regex("[a-z_]{1,64}")
        const val MAXIMUM_BODY_BYTES = 16 * 1024
    }
}

/** Carries the local capability beside an agent's own login (Claude Code: through ANTHROPIC_CUSTOM_HEADERS). */
internal const val LOCAL_TOKEN_HEADER = "x-teamcode-local-token"

/**
 * Relays an agent's MCP messages to the gateway endpoint of one assigned server, `/mcp/<server>`. The agent holds
 * only the local launch token; the TeamCode credential is added here and upstream MCP credentials never leave the
 * server. Authorization is the gateway's, on every message.
 */
private fun Routing.mcpRelay(client: TeamCodeClient, localToken: String, maximumBodyBytes: Int) {
    route("/mcp/{server}") {
        install(io.ktor.server.http.HttpRequestLifecycle) { cancelCallOnClose = true }
        post {
            if (!equalToken(call.request.header(HttpHeaders.Authorization)?.removePrefix("Bearer "), localToken)) {
                call.respondText("A local TeamCode capability is required.", status = HttpStatusCode.Unauthorized)
                return@post
            }
            if (call.request.local.serverHost !in setOf("localhost", "127.0.0.1", "::1") || call.request.header(HttpHeaders.Origin) != null) {
                call.respondText("Browser or non-loopback destinations are not accepted.", status = HttpStatusCode.Forbidden)
                return@post
            }
            val server = call.parameters["server"].orEmpty()
            if (!TeamCodeClient.MCP_SERVER_NAME.matches(server)) {
                call.respond(HttpStatusCode.NotFound)
                return@post
            }
            if (call.request.contentType().withoutParameters() != ContentType.Application.Json) {
                call.respondText("A JSON body is required.", status = HttpStatusCode.UnsupportedMediaType)
                return@post
            }
            val body = call.receiveChannel().readRemaining(maximumBodyBytes + 1L).readByteArray()
            if (body.size > maximumBodyBytes) {
                call.respondText("Request is too large.", status = HttpStatusCode.PayloadTooLarge)
                return@post
            }
            val relayed = try {
                client.forwardMcp(server, body, call.request.header("MCP-Protocol-Version"))
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (_: Exception) {
                call.respondText("The TeamCode server could not be reached.", status = HttpStatusCode.BadGateway)
                return@post
            }
            call.response.header(HttpHeaders.CacheControl, "no-store")
            // A 401/403 here is about the TeamCode sign-in, not the MCP server: agents would otherwise start an
            // OAuth flow against the local relay.
            if (relayed.status == 401 || relayed.status == 403) {
                call.respondText("TeamCode refused the MCP request; run `teamcode login` or check your access.", status = HttpStatusCode.BadGateway)
                return@post
            }
            if (relayed.body.isEmpty()) call.respond(HttpStatusCode.fromValue(relayed.status))
            else call.respondBytes(relayed.body, ContentType.Application.Json, HttpStatusCode.fromValue(relayed.status))
        }
        // The gateway is stateless: no server-initiated stream and no session to delete.
        get { call.response.header(HttpHeaders.Allow, "POST"); call.respond(HttpStatusCode.MethodNotAllowed) }
        delete { call.response.header(HttpHeaders.Allow, "POST"); call.respond(HttpStatusCode.MethodNotAllowed) }
    }
}

/** The relay's answer when the server refuses the proxy's own credential. */
internal const val RELAY_SIGN_IN_REFUSED = "relay_sign_in_refused"

private const val ARTIFACT_ID = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"

/**
 * The artifact API calls `teamcode mcp artifacts` makes, and nothing else: reading, uploading, committing and inviting.
 * Changing an artifact's access and deleting it are absent, so an agent can never make an artifact public or delete it
 * through the relay, whatever the credential the relay holds would allow.
 */
internal val ARTIFACT_RELAY_ROUTES: Map<HttpMethod, List<Regex>> = mapOf(
    HttpMethod.Get to listOf(Regex("/api/v1/artifacts"), Regex("/api/v1/artifacts/policy"),
        Regex("/api/v1/artifacts/$ARTIFACT_ID(/versions(/[0-9]{1,9}(/file)?)?|/members)?")),
    HttpMethod.Post to listOf(Regex("/api/v1/artifacts/uploads"), Regex("/api/v1/artifacts/$ARTIFACT_ID/(versions|members)")),
    HttpMethod.Put to listOf(Regex("/api/v1/artifacts/uploads/$ARTIFACT_ID/blobs/[0-9a-f]{64}/[0-9]{1,6}")),
    HttpMethod.Delete to listOf(// Never `.` or `..`: a segment a server or proxy could collapse into the artifact itself.
    Regex("/api/v1/artifacts/$ARTIFACT_ID/members/(?!\\.{1,2}$)[A-Za-z0-9._@-]{1,64}")),
)

private const val WAIT_ID = "[a-zA-Z0-9-]{1,36}"

/**
 * The wait API calls `teamcode mcp waits` makes, and nothing else: listing, reading, asking and withdrawing. Answering
 * (`POST /api/v1/waits/{id}/answer`) is absent on purpose: the relay holds the session owner's credential, and the owner
 * is the default assignee, so an agent could otherwise answer its own question.
 */
internal val WAIT_RELAY_ROUTES: Map<HttpMethod, List<Regex>> = mapOf(
    HttpMethod.Get to listOf(Regex("/api/v1/waits"), Regex("/api/v1/waits/$WAIT_ID")),
    HttpMethod.Post to listOf(Regex("/api/v1/waits"), Regex("/api/v1/waits/$WAIT_ID/cancel")),
)

/**
 * The two workflow API calls the agent's side makes: the delivery of the step running in the session (`teamcode mcp workflow`)
 * and the question of the Stop hook (`teamcode cloud workflow-stop`) whether that step still has to deliver or ask. Reading
 * runs, delivering by run id and starting or cancelling a run are absent: both act for their own session and nothing else.
 */
internal val WORKFLOW_RELAY_ROUTES: Map<HttpMethod, List<Regex>> = mapOf(
    HttpMethod.Get to listOf(Regex("/api/v1/workflows/session/pending")),
    HttpMethod.Post to listOf(Regex("/api/v1/workflows/session/deliver")),
)

/** Relays the delivery call of `teamcode mcp workflow`, as [apiRelay] does. */
private fun Routing.workflowRelay(client: TeamCodeClient, localToken: String, maximumBodyBytes: Int) =
    apiRelay("/api/v1/workflows/session", WORKFLOW_RELAY_ROUTES, localToken, maximumBodyBytes) { request ->
        client.forwardWorkflow(request.method, request.path, request.query, request.body, request.contentType, request.runId)
    }

/** Relays the artifact calls of `teamcode mcp artifacts`, as [apiRelay] does. */
private fun Routing.artifactRelay(client: TeamCodeClient, localToken: String, maximumBodyBytes: Int) =
    apiRelay("/api/v1/artifacts", ARTIFACT_RELAY_ROUTES, localToken, maximumBodyBytes) { request ->
        client.forwardArtifact(request.method, request.path, request.query, request.body, request.contentType, request.runId)
    }

private val IDEMPOTENCY_KEY = Regex("[A-Za-z0-9._:-]{1,128}")

/** Relays the wait calls of `teamcode mcp waits`, as [apiRelay] does, with the caller's `Idempotency-Key` (at most one). */
private fun Routing.waitRelay(client: TeamCodeClient, localToken: String, maximumBodyBytes: Int) =
    apiRelay("/api/v1/waits", WAIT_RELAY_ROUTES, localToken, maximumBodyBytes, accepts = { headers ->
        headers.getAll(me.nathanfallet.teamcode.api.models.CloudHeaders.IDEMPOTENCY_KEY).orEmpty().let { keys -> keys.size <= 1 && keys.all { IDEMPOTENCY_KEY.matches(it) } }
    }) { request ->
        client.forwardWait(request.method, request.path, request.query, request.body, request.contentType, request.runId,
            request.headers[me.nathanfallet.teamcode.api.models.CloudHeaders.IDEMPOTENCY_KEY])
    }

/** One call an [apiRelay] accepted. */
private class RelayedCall(val method: HttpMethod, val path: String, val query: String, val body: ByteArray, val contentType: ContentType?, val runId: String?, val headers: Headers)

/**
 * Relays the API calls below [prefix] that [routes] lists, with this proxy's TeamCode credential (the member's device
 * login locally, the execution credential in a cloud session). The caller holds only the local token; the relay reads
 * no file and keeps no state. Same guards as the MCP relay: the token compared in constant time, loopback only, no
 * browser `Origin`, a bounded body, `no-store`, and a sign-in refusal turned into an explicit `502`. Anything off
 * [routes] is a `404` that never reaches the server; request headers [accepts] refuses are a `400`.
 */
private fun Routing.apiRelay(prefix: String, routes: Map<HttpMethod, List<Regex>>, localToken: String, maximumBodyBytes: Int,
    accepts: (Headers) -> Boolean = { true }, forward: suspend (RelayedCall) -> me.nathanfallet.teamcode.client.ArtifactRelayResponse) {
    for (pattern in listOf(prefix, "$prefix/{rest...}")) route(pattern) {
        install(io.ktor.server.http.HttpRequestLifecycle) { cancelCallOnClose = true }
        handle {
            if (!equalToken(call.request.header(HttpHeaders.Authorization)?.removePrefix("Bearer "), localToken)) {
                call.respondText("A local TeamCode capability is required.", status = HttpStatusCode.Unauthorized)
                return@handle
            }
            if (call.request.local.serverHost !in setOf("localhost", "127.0.0.1", "::1") || call.request.header(HttpHeaders.Origin) != null) {
                call.respondText("Browser or non-loopback destinations are not accepted.", status = HttpStatusCode.Forbidden)
                return@handle
            }
            val method = call.request.httpMethod
            val path = call.request.path()
            if (routes[method].orEmpty().none { it.matches(path) }) {
                call.respond(HttpStatusCode.NotFound)
                return@handle
            }
            val runs = call.request.headers.getAll("x-teamcode-run-id").orEmpty()
            if (runs.size > 1 || runs.any { !me.nathanfallet.teamcode.api.models.RunIdentifiers.isValid(it) }) {
                call.respondText("Invalid launch identifier.", status = HttpStatusCode.BadRequest)
                return@handle
            }
            if (!accepts(call.request.headers)) {
                call.respondText("Invalid request header.", status = HttpStatusCode.BadRequest)
                return@handle
            }
            val body = call.receiveChannel().readRemaining(maximumBodyBytes + 1L).readByteArray()
            if (body.size > maximumBodyBytes) {
                call.respondText("Request is too large.", status = HttpStatusCode.PayloadTooLarge)
                return@handle
            }
            val relayed = try {
                forward(RelayedCall(method, path, call.request.queryString(), body, call.request.contentType().takeIf { it != ContentType.Any }, runs.singleOrNull(), call.request.headers))
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (_: Exception) {
                call.response.header(HttpHeaders.RetryAfter, "5")
                call.respondText("The TeamCode server could not be reached.", status = HttpStatusCode.BadGateway)
                return@handle
            }
            call.response.header(HttpHeaders.CacheControl, "no-store")
            if (relayed.status == 401) {
                // Not retryable: the member's login is gone, or, in a cloud session, the execution's credential ended.
                call.response.header("x-teamcode-error", RELAY_SIGN_IN_REFUSED)
                call.respondText(buildJsonObject { putJsonObject("error") { put("type", RELAY_SIGN_IN_REFUSED)
                    put("message", "TeamCode refused the sign-in; run `teamcode login` (in a cloud session, the execution has ended).") } }.toString(),
                    ContentType.Application.Json, HttpStatusCode.BadGateway)
                return@handle
            }
            relayed.headers.forEach { (name, value) -> call.response.header(name, value) }
            call.respondBytes(relayed.body, relayed.contentType ?: ContentType.Application.OctetStream, HttpStatusCode.fromValue(relayed.status))
        }
    }
}

internal fun equalToken(presented: String?, expected: String): Boolean {
    if (presented == null) return false
    var difference = presented.length xor expected.length
    for (index in expected.indices) {
        difference = difference or (expected[index].code xor (presented.getOrNull(index)?.code ?: 0))
    }
    return difference == 0
}
