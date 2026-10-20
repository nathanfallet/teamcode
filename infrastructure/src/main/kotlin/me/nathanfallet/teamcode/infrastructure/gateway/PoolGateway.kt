package me.nathanfallet.teamcode.infrastructure.gateway

import io.ktor.client.statement.*
import io.ktor.utils.io.*
import kotlinx.coroutines.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.*
import me.nathanfallet.teamcode.domain.accounts.*
import me.nathanfallet.teamcode.domain.capacity.*
import me.nathanfallet.teamcode.domain.gateway.*
import me.nathanfallet.teamcode.domain.team.*
import me.nathanfallet.teamcode.domain.policy.*
import me.nathanfallet.teamcode.infrastructure.providers.ProviderTransport
import me.nathanfallet.teamcode.domain.routing.*
import me.nathanfallet.teamcode.infrastructure.protocols.ConversationCodec
import me.nathanfallet.teamcode.infrastructure.protocols.StreamTranslation

/** Native passthrough by default; explicit routes translate only when the destination protocol changes. */
class PoolGateway(private val admission: AdmissionStore, private val credentials: CredentialSource, private val transports: List<ProviderTransport>, private val modelPolicies: ModelPolicyStore, private val routes: RoutingStore? = null, private val codecs: ConversationCodec = ConversationCodec(), private val queued: AwaitAdmissionUseCase? = null) : GatewayService {
    override suspend fun status(): GatewaySnapshot = admission.statistics().let { GatewaySnapshot("subscription-pool", it.active, it.completed, it.queued) }
    override suspend fun forward(request: GenerationRequest, receive: suspend (GenerationHead, kotlinx.coroutines.flow.Flow<ByteArray>) -> Unit) = withTimeout(10 * 60_000L) {
        route(request, receive)
    }
    private suspend fun route(request: GenerationRequest, receive: suspend (GenerationHead, kotlinx.coroutines.flow.Flow<ByteArray>) -> Unit) {
        val actor = request.principal ?: throw TeamFailure("authentication_required", 401, "Sign in to TeamCode.")
        actor.requireScope(ServiceScope.INFERENCE)
        val runId = request.headers["x-teamcode-run-id"]?.also(AgentRunService::identifier)
        val workload = when (request.headers["x-teamcode-workload"]) {
            null, "interactive" -> Workload.INTERACTIVE
            "background" -> Workload.BACKGROUND
            else -> throw TeamFailure("invalid_workload", 400, "Workload must be interactive or background.")
        }
        val body = try { Json.parseToJsonElement(request.body.decodeToString()).jsonObject } catch (_: IllegalArgumentException) { throw TeamFailure("invalid_request", 400, "Invalid Messages request.") }
        val model = (body["model"] as? JsonPrimitive)?.contentOrNull ?: throw TeamFailure("invalid_model", 400, "A model is required.")
        if (!Regex("[a-zA-Z0-9_.-]{1,120}").matches(model)) throw TeamFailure("unsupported_model", 400, "Use an explicit model identifier or configured alias.")
        val outputConfig = body[if (request.protocol == WireProtocol.MESSAGES) "output_config" else "reasoning"] as? JsonObject
        val effortValue = outputConfig?.get("effort")
        val effort = (effortValue as? JsonPrimitive)?.takeIf { it.isString }?.content
        if (effortValue != null && effort == null) throw TeamFailure("invalid_effort", 400, "Reasoning effort must be a string.")
        val thinking = body["thinking"] as? JsonObject
        val thinkingType = (thinking?.get("type") as? JsonPrimitive)?.contentOrNull
        if (body["thinking"] != null && thinkingType !in setOf("enabled", "disabled", "adaptive")) throw TeamFailure("invalid_thinking", 400, "Unsupported thinking configuration.")
        val thinkingBudget = if (thinkingType == "enabled") (thinking?.get("budget_tokens") as? JsonPrimitive)?.longOrNull
            ?: throw TeamFailure("invalid_thinking", 400, "An explicit thinking budget is required.") else null
        val policy = modelPolicies.get(actor.teamId)
        CheckModelPolicyUseCase().check(policy, model, model, effort, thinkingBudget)
        val maximumOutput = if (request.protocol == WireProtocol.MESSAGES) (body["max_tokens"] as? JsonPrimitive)?.longOrNull
            else (body["max_output_tokens"] as? JsonPrimitive)?.longOrNull ?: 16_384
        if (maximumOutput == null || maximumOutput !in 1..65_536) throw TeamFailure("invalid_max_tokens", 400, "max_tokens must be between 1 and 65536.")
        if (listOf("thread", "previous_response_id", "conversation").any { body[it] != null && body[it] != JsonNull }) throw TeamFailure("thread_unsupported", 400, "Send the complete conversation; provider-managed message threads are not supported by this pool.")
        val configuredTargets = ResolveRouteUseCase().resolve(routes?.list(actor.teamId).orEmpty(), model, request.protocol)
        val affinity = SessionAffinityKey.from(request.headers, body, request.protocol, model, configuredTargets)
        val preferredTarget = affinity?.let { admission.preferredTarget(actor, it) }
        val targets = if (preferredTarget in configuredTargets) listOf(preferredTarget!!) + configuredTargets.filterNot { it == preferredTarget } else configuredTargets
        val attemptedTargets = mutableMapOf<Pair<String, String>, MutableSet<String>>()
        var attempts = 0
        val reauthenticatedAccounts = mutableSetOf<String>()
        var compatibleTargetFound = false
        var translationFailure: TeamFailure? = null
        var waitingFailure: TeamFailure? = null
        // Exhaust eligible accounts for each preferred target before trying the next configured provider/model.
        for (target in targets) {
            val resolved = target.model ?: model
            val attemptedAccounts = attemptedTargets.getOrPut(target.provider to resolved) { mutableSetOf() }
            val transport = transports.singleOrNull { it.provider == target.provider }
                ?: throw TeamFailure("unsupported_provider", 400, "The configured route provider is unavailable.")
            val translated = transport.protocol != request.protocol
            var conversation: Conversation? = null
            val outgoing = try {
                if (translated) CheckModelPolicyUseCase().checkTranslation(policy)
                CheckModelPolicyUseCase().check(policy, model, resolved, effort, thinkingBudget)
                if (translated) {
                    conversation = codecs.decode(body, request.protocol)
                    codecs.encode(conversation, transport.protocol, resolved)
                }
                else JsonObject(body + ("model" to JsonPrimitive(resolved)))
            } catch (error: TeamFailure) {
                if (error.code !in setOf("translation_unsupported", "translation_disabled")) throw error
                translationFailure = error
                continue // A native/compatible target can still preserve this request unchanged.
            }
            compatibleTargetFound = true
            if (transport.provider == "codex" && outgoing["store"] != null && outgoing["store"] != JsonPrimitive(false)) {
                throw TeamFailure("unsupported_storage", 400, "The Codex subscription endpoint requires store=false.")
            }
            if (translated) {
                val targetEffort = (outgoing[if (transport.protocol == WireProtocol.MESSAGES) "output_config" else "reasoning"] as? JsonObject)?.get("effort") as? JsonPrimitive
                val disabled = (outgoing["thinking"] as? JsonObject)?.get("type") == JsonPrimitive("disabled")
                CheckModelPolicyUseCase().check(policy, model, resolved, if (disabled) "none" else targetEffort?.contentOrNull, null)
            }
            // Credits are team policy, not an asserted exchange rate for subscription percentages.
            val estimate = maxOf(request.body.size, outgoing.toString().toByteArray().size).toLong() + 16_384 + maximumOutput * 5
            while (true) {
                if (attempts >= 64) throw TeamFailure("routing_attempt_limit", 503, "The request reached its bounded routing limit. Retry shortly; available capacity has not been ruled out.")
                val reservation = try {
                    if (queued == null) admission.reserve(actor, AdmissionInput(estimate, resolved, transport.provider, attemptedAccounts, affinity, workload, runId = runId))
                    else {
                        val targetEffort = (outgoing[if (transport.protocol == WireProtocol.MESSAGES) "output_config" else "reasoning"] as? JsonObject)?.get("effort") as? JsonPrimitive
                        val disabled = (outgoing["thinking"] as? JsonObject)?.get("type") == JsonPrimitive("disabled")
                        val context = AdmissionContext(model, request.protocol, configuredTargets, effort, thinkingBudget, translated, if (disabled) "none" else targetEffort?.contentOrNull)
                        queued.reserve(actor, AdmissionInput(estimate, resolved, transport.provider, attemptedAccounts, affinity, workload, context, runId))
                    }
                } catch (error: TeamFailure) {
                    if (error.code in setOf("queue_timeout", "queue_full", "queue_expired")) waitingFailure = error
                    else if (error.code != "pool_unavailable") throw error
                    break
                }
                attemptedAccounts += reservation.account.id
                attempts++
                try {
                    attempt(request, receive, transport, outgoing, resolved, model, reservation, translated, reauthenticatedAccounts, conversation?.tools)
                    return
                } catch (_: SafeRetry) { /* This definite rejection settled with zero charge; select another account. */ }
            }
        }
        if (!compatibleTargetFound) translationFailure?.let { throw it }
        waitingFailure?.let { throw it }
        throw TeamFailure("pool_unavailable", 429, "No eligible account remains in your pool across the configured route targets.")
    }

    private class SafeRetry(val failure: TeamFailure) : RuntimeException()
    private suspend fun attempt(request: GenerationRequest, receive: suspend (GenerationHead, kotlinx.coroutines.flow.Flow<ByteArray>) -> Unit,
        transport: ProviderTransport, body: JsonObject, model: String, requestedModel: String, reservation: Reservation, translated: Boolean, reauthenticatedAccounts: MutableSet<String>, replyTools: List<ToolDefinition>?) {
        val meter = SseUsageMeter()
        var knownCharge: Long? = null
        var dispatched = false
        var committed = false
        try {
            suspend fun credential(rejected: String? = null): ProviderCredentials = try {
                credentials.fresh(reservation.account, rejected)
            } catch (error: TeamFailure) {
                if (error.code !in setOf("provider_reconnect", "access_token_expired", "account_unavailable", "refresh_in_progress")) throw error
                admission.coolDown(reservation.account.id, System.currentTimeMillis() + 60_000)
                throw SafeRetry(error)
            }
            var token = credential()
            System.out.println("TeamCode inference dispatch request=${reservation.id} account=${reservation.account.id} provider=${transport.provider} model=$model")
            dispatched = true
            withTimeout(10 * 60_000L) {
                while (true) {
                    var refreshRejected = false
                    knownCharge = null
                    transport.send(reservation.account, token, body, request.headers - setOf("x-teamcode-run-id", "x-teamcode-workload")) { response ->
                        transport.requestId(response.headers)?.let { admission.recordProviderRequest(reservation, it) }
                        val now = System.currentTimeMillis()
                        val observation = transport.quotaUpdate(reservation.account.id, model, response.headers, now)
                        val observations = observation.windows
                        admission.observe(reservation.account.id, observation)
                        val status = response.status.value
                        val encoding = response.headers["content-encoding"]?.takeIf { it in setOf("gzip", "br", "deflate", "zstd", "identity") } ?: "none"
                        System.out.println("TeamCode inference upstream request=${reservation.id} status=$status encoding=$encoding")
                        if (status !in 200..299) {
                            if (status in 400..499) knownCharge = 0
                            if (status == 401 && reauthenticatedAccounts.add(reservation.account.id)) {
                                refreshRejected = true
                                return@send
                            }
                            val retry = response.headers["retry-after"]?.toLongOrNull()?.coerceIn(1, 3600) ?: 60
                            val spent = observations.filter { it.utilization >= 1.0 && it.appliesTo(model, observation.modelLimit?.limit) }
                            // A durable model limit is already enforced in SQL. Do not park the
                            // entire subscription: another configured model may still be available.
                            val exhausted = status == 429 && (spent.isNotEmpty() || admission.isQuotaExhausted(reservation.account.id, model))
                            if ((status == 429 && !exhausted) || status >= 500 || status == 401 || status == 403) admission.coolDown(reservation.account.id, now + retry * 1000, reconnect = status == 401)
                            if (status in setOf(401, 403, 429)) throw SafeRetry(TeamFailure("pool_unavailable", 429, "Configured subscriptions are unavailable or rate-limited. Retry shortly."))
                            val publicStatus = if (status in setOf(400, 413, 429)) status else 502
                            val publicError = if (status == 429) "The selected subscription is rate-limited. Retry after the indicated delay." else "The provider rejected this request. Check the model and account status."
                            val error = buildJsonObject { put("type", "error"); putJsonObject("error") { put("type", "upstream_error"); put("message", publicError) } }.toString().toByteArray()
                            committed = true
                            receive(GenerationHead(publicStatus, "application/json", mapOf("retry-after" to retry.toString(), "x-teamcode-request-id" to reservation.id)), flow { emit(error) })
                            return@send
                        }
                        val reportedType = response.headers["content-type"].orEmpty()
                        // The subscription Responses endpoint can omit Content-Type on a valid SSE stream.
                        // Its destination and stream=true contract are fixed; an explicit incompatible type still fails.
                        if (!reportedType.startsWith("text/event-stream") && !(reportedType.isEmpty() && transport.protocol == WireProtocol.RESPONSES)) throw TeamFailure("upstream_protocol", 502, "The provider did not return a stream.")
                        committed = true
                        val source = flow {
                            val channel = response.bodyAsChannel()
                            val buffer = ByteArray(8192)
                            var bytes = 0L
                            while (true) {
                                val count = channel.readAvailable(buffer)
                                if (count < 0) break
                                if (count == 0) continue
                                bytes += count
                                if (bytes > 64 * 1024 * 1024) throw TeamFailure("stream_limit", 502, "The response exceeded the stream limit.")
                                val chunk = buffer.copyOf(count)
                                meter.accept(chunk)
                                emit(chunk)
                            }
                        }
                        val chunks = if (translated) StreamTranslation(codecs).translate(source, transport.protocol, request.protocol, requestedModel, replyTools) else source
                        receive(GenerationHead(status, "text/event-stream", mapOf("x-teamcode-request-id" to reservation.id, "x-teamcode-model" to model, "x-teamcode-provider" to transport.provider)), chunks)
                    }
                    if (!refreshRejected) break
                    // execute has closed the rejected response before renewing or retrying.
                    // The shared credential source fences concurrent replicas and rotating tokens.
                    token = credential(token.accessToken)
                }
            }
        } catch (error: CancellationException) { throw error }
        catch (error: Exception) {
            if (error is SafeRetry) throw error
            if (error is TeamFailure) System.err.println("TeamCode inference failure request=${reservation.id} category=${error.code}")
            if (committed) throw error // Never write a replacement response or replay a committed stream.
            if (error is TeamFailure) throw error
            throw TeamFailure("upstream_unavailable", 502, "The provider request could not complete.")
        } finally {
            withContext(NonCancellable) {
                // Native clients can close immediately after the terminal SSE
                // event, cancelling receive before HTTP EOF. Count the observed
                // provider terminal usage, independently of downstream delivery.
                val terminalUsage = meter.settledUnits
                val charge = if (!dispatched) 0 else knownCharge ?: terminalUsage
                try {
                    withTimeout(5_000) { admission.settle(reservation, charge, terminalUsage != null) }
                    System.out.println("TeamCode inference settled request=${reservation.id} units=${charge ?: "unknown"} terminal=${meter.lastEvent} failure=${meter.failureCategory}")
                } catch (_: Exception) {
                    // The durable reservation remains charged; never mask cancellation or a stream failure.
                    System.err.println("TeamCode inference settlement pending request=${reservation.id}")
                }
            }
        }
    }
}
