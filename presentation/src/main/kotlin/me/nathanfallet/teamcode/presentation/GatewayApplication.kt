package me.nathanfallet.teamcode.presentation

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.resources.*
import io.ktor.server.routing.*
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.*
import java.security.MessageDigest
import me.nathanfallet.teamcode.domain.team.*
import me.nathanfallet.teamcode.domain.accounts.AccountManagement
import me.nathanfallet.teamcode.domain.capacity.AdmissionStore
import me.nathanfallet.teamcode.domain.capacity.DirectiveService
import me.nathanfallet.teamcode.domain.policy.ModelPolicyStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*
import me.nathanfallet.teamcode.api.Serialization
import me.nathanfallet.teamcode.api.models.*
import me.nathanfallet.teamcode.api.resources.*
import me.nathanfallet.teamcode.domain.gateway.*

private const val MAX_BODY_BYTES = 2 * 1024 * 1024

fun Application.configureGateway(gateway: GatewayService, accessToken: String = "", team: TeamService? = null, publicOrigin: String = "http://localhost:8080", accounts: AccountManagement? = null, admission: AdmissionStore? = null, directives: DirectiveService? = null, modelPolicies: ModelPolicyStore? = null, readiness: suspend () -> Boolean = { true }, routes: me.nathanfallet.teamcode.domain.routing.RoutingStore? = null, inboundProtocols: List<InboundProtocol> = listOf(MessagesInboundProtocol(), ResponsesInboundProtocol()), cancellation: RequestCancellationService? = null, devices: DeviceService? = null, serviceKeys: ServiceKeyService? = null, audit: AuditService? = null, pacingSettings: me.nathanfallet.teamcode.domain.capacity.PacingSettingsStore? = null, forecasts: me.nathanfallet.teamcode.domain.capacity.ForecastService? = null, forecastLivePolicy: ForecastLivePolicy = ForecastLivePolicy(), groups: GroupService? = null, runs: AgentRunService? = null, budgetOverrides: me.nathanfallet.teamcode.domain.capacity.BudgetOverrideService? = null, reconciliation: me.nathanfallet.teamcode.domain.capacity.UsageReconciliationService? = null, cloudSessions: me.nathanfallet.teamcode.domain.cloud.CloudSessionService? = null, cloudAdmin: me.nathanfallet.teamcode.domain.cloud.CloudAdministrationService? = null, cloudRunner: CloudRunnerChannel? = null) {
    require(team != null || accessToken.length >= 32) { "The development access token must contain at least 32 characters." }
    val expected = MessageDigest.getInstance("SHA-256").digest(accessToken.toByteArray())
    install(io.ktor.server.resources.Resources)
    install(ContentNegotiation) { json(Serialization.json) }
    install(StatusPages) {
        exception<TeamFailure> { call, error ->
            if (call.response.isCommitted) throw error // A revoked/failed stream cannot become a replacement JSON response.
            if (error.status == 429) call.response.header(HttpHeaders.RetryAfter, "60")
            if (call.request.path().startsWith("/dashboard")) call.respondText(page("Request failed", "<p>${escape(error.message ?: "Request failed.")}</p><a href='/dashboard'>Back to dashboard</a>"), ContentType.Text.Html, HttpStatusCode.fromValue(error.status))
            else call.respond(HttpStatusCode.fromValue(error.status), ApiError(ErrorDetail(error.code, error.message ?: "Request failed.")))
        }
        exception<CancellationException> { _, error -> throw error }
        exception<InvalidRequest> { call, error -> call.respond(HttpStatusCode.BadRequest, ApiError(ErrorDetail("invalid_request", error.message ?: "Invalid request."))) }
        exception<PayloadTooLarge> { call, _ -> call.respond(HttpStatusCode.PayloadTooLarge, ApiError(ErrorDetail("payload_too_large", "Request exceeds the configured limit."))) }
    }
    intercept(ApplicationCallPipeline.Plugins) {
        call.response.header(HttpHeaders.CacheControl, "no-store")
        call.response.header("X-Content-Type-Options", "nosniff")
        call.response.header("Referrer-Policy", "no-referrer")
        val path = call.request.path()
        if (team != null) {
            if (path.startsWith("/dashboard")) {
                val live = if (path == "/dashboard/forecasts") " script-src 'self'; connect-src 'self';" else ""
                call.response.header("Content-Security-Policy", "default-src 'none'; style-src 'unsafe-inline'; form-action 'self'; frame-ancestors 'none'; base-uri 'none';" + live)
            }
            // Runner calls carry the pod's projected token, which the runner routes verify themselves.
            val runnerCall = cloudRunner != null && path.startsWith(RUNNER_PATH)
            if (!runnerCall && path !in setOf("/health", "/ready", "/", "/dashboard/login", "/api/v1/auth/login")) {
                val credential = if (path.startsWith("/dashboard")) call.request.cookies["teamcode_session"]
                    else call.request.header(HttpHeaders.Authorization)?.takeIf { it.startsWith("Bearer ") }?.removePrefix("Bearer ")
                val principal = credential?.let { team.authenticate(it) }
                if (principal == null) {
                    if (path.startsWith("/dashboard")) call.respondRedirect("/dashboard/login")
                    else call.respond(HttpStatusCode.Unauthorized, ApiError(ErrorDetail("authentication_required", "Sign in to TeamCode.")))
                    finish()
                } else {
                    AuthorizeCredentialUseCase().check(principal, credentialAction(call.request.httpMethod.value, path, inboundProtocols.map { it.path }.toSet()))
                    call.attributes.put(PRINCIPAL, principal)
                    if (path !in setOf("/api/v1/auth/me", "/api/v1/auth/password", "/api/v1/auth/logout", "/dashboard/password", "/dashboard/logout")) {
                        if (principal.member.passwordChangeRequired && path.startsWith("/dashboard")) {
                            call.respondRedirect("/dashboard/password"); finish()
                        } else principal.requireReady()
                    }
                }
            }
        } else if (path !in setOf("/health", "/ready")) {
            val presented = call.request.header(HttpHeaders.Authorization)?.removePrefix("Bearer ") ?: ""
            val actual = MessageDigest.getInstance("SHA-256").digest(presented.toByteArray())
            if (!MessageDigest.isEqual(expected, actual)) {
                call.respond(HttpStatusCode.Unauthorized, ApiError(ErrorDetail("authentication_required", "A valid TeamCode access token is required.")))
                finish()
            }
        }
    }
    routing {
        if (team != null) { teamRoutes(team); dashboardRoutes(team, publicOrigin) }
        if (team != null && runs != null) { agentRunRoutes(runs); agentRunDashboardRoutes(runs, publicOrigin) }
        if (team != null && groups != null) { groupRoutes(groups); groupDashboardRoutes(groups, team, publicOrigin) }
        if (accounts != null) accountRoutes(accounts, publicOrigin)
        if (admission != null && team != null) capacityRoutes(admission, team, publicOrigin, budgetOverrides != null)
        if (team != null && admission != null && budgetOverrides != null) { budgetOverrideRoutes(budgetOverrides); budgetOverrideDashboardRoutes(budgetOverrides, admission, publicOrigin) }
        if (team != null && reconciliation != null) { usageReconciliationRoutes(reconciliation); usageReconciliationDashboardRoutes(reconciliation, publicOrigin) }
        if (directives != null) directiveRoutes(directives, pacingSettings)
        if (pacingSettings != null) pacingSettingsRoutes(pacingSettings, publicOrigin)
        if (forecasts != null) { forecastRoutes(forecasts); forecastLiveRoutes(forecasts, forecastLivePolicy) }
        if (modelPolicies != null) modelPolicyRoutes(modelPolicies, publicOrigin)
        if (routes != null) routingRoutes(routes, publicOrigin)
        if (devices != null && team != null) deviceRoutes(devices, publicOrigin)
        if (serviceKeys != null && team != null) serviceKeyRoutes(serviceKeys, publicOrigin)
        if (audit != null && team != null) auditRoutes(audit)
        if (cloudSessions != null && cloudAdmin != null && team != null) cloudRoutes(cloudSessions, cloudAdmin, publicOrigin)
        if (cloudRunner != null && cloudSessions != null && team != null) cloudRunnerRoutes(cloudRunner)
        if (cancellation != null && team != null) {
            post("/api/v1/requests/{id}/cancel") {
                cancellation.cancel(call.principal, call.parameters["id"].orEmpty())
                call.respond(HttpStatusCode.NoContent)
            }
        }
        get("/health") { call.respondText("OK") }
        get("/ready") {
            if (readiness()) call.respondText("OK")
            else call.respondText("Unavailable", status = HttpStatusCode.ServiceUnavailable)
        }
        get<CapabilitiesResource> {
            val mode = gateway.status().mode
            call.respond(Capabilities(serverVersion = me.nathanfallet.teamcode.api.BuildInfo.VERSION, mode = mode,
                features = listOf("status", if (mode == "fixture") "messages.fixture.streaming" else "subscriptions.streaming") +
                    (if (team != null) listOf("team.auth", "team.members", "dashboard") else emptyList()) +
                    (if (team != null && admission != null) listOf("capacity.shared-overflow.v1") else emptyList()) +
                    (if (team != null && cancellation != null) listOf("requests.cancel.v1") else emptyList()) +
                    (if (team != null && runs != null) listOf("team.runs.v1") else emptyList()) +
                    (if (team != null && reconciliation != null) listOf("capacity.usage-reconciliation.v1") else emptyList()) +
                    (if (team != null && budgetOverrides != null && admission != null) listOf("capacity.budget-overrides.v1") else emptyList()) +
                    (if (team != null && groups != null) listOf("team.groups.v1") else emptyList()) +
                    (if (team != null && devices != null) listOf("team.devices") else emptyList()) +
                    (if (team != null && serviceKeys != null) listOf("team.service-keys.v1") else emptyList()) +
                    (if (team != null && audit != null) listOf("team.audit.v1") else emptyList()) +
                    (if (team != null && forecasts != null) listOf("capacity.forecasts.v1", "capacity.forecasts.live.v1") else emptyList()) +
                    (if (team != null && cloudSessions != null && cloudAdmin != null) listOf("cloud.sessions.v1") else emptyList())))
        }
        get<StatusResource> {
            val status = gateway.status()
            call.respond(GatewayStatus(status.mode, status.active, status.completed, status.queued))
        }
        for (inbound in inboundProtocols) {
        route(inbound.path) {
        install(io.ktor.server.http.HttpRequestLifecycle) { cancelCallOnClose = true }
        post {
            if (call.request.contentType().withoutParameters() != ContentType.Application.Json) throw InvalidRequest("A JSON request body is required.")
            val bytes = call.receiveChannel().readBounded(MAX_BODY_BYTES)
            val value = try { Serialization.json.parseToJsonElement(bytes.decodeToString()) as? JsonObject }
                catch (_: IllegalArgumentException) { null } ?: throw InvalidRequest("A JSON object is required.")
            if (value["stream"] != JsonPrimitive(true)) throw InvalidRequest("This endpoint requires stream=true.")
            val model = value["model"] as? JsonPrimitive
            if (model?.isString != true || model.content.isBlank()) throw InvalidRequest("A model name is required.")
            inbound.validateConversation(value)
            val headers = me.nathanfallet.teamcode.api.InferenceHeaders.capture(call.request.headers::getAll)
            val clientRequestId = call.request.headers.getAll("x-teamcode-client-request-id")?.let { it.singleOrNull() ?: "" }
            gateway.forward(GenerationRequest(bytes, headers, if (team != null) call.principal else null, inbound.protocol, clientRequestId)) { head, chunks ->
                head.headers.filterKeys { it.lowercase() in setOf("retry-after", "x-teamcode-request-id", "x-teamcode-model", "x-teamcode-provider") }.forEach { (name, value) -> call.response.header(name, value) }
                call.response.header(HttpHeaders.CacheControl, "no-store")
                call.response.header("X-Accel-Buffering", "no")
                val consumed = CompletableDeferred<Unit>()
                call.respondBytesWriter(ContentType.parse(head.contentType), HttpStatusCode.fromValue(head.status)) {
                    try {
                        chunks.collect { chunk -> writeFully(chunk); flush() }
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

suspend fun ByteReadChannel.readBounded(limit: Int): ByteArray {
    val output = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (true) {
        val count = readAvailable(buffer, 0, minOf(buffer.size, limit - output.size() + 1))
        if (count < 0) break
        if (count == 0) continue
        if (output.size() + count > limit) throw PayloadTooLarge()
        output.write(buffer, 0, count)
    }
    return output.toByteArray()
}

private class InvalidRequest(message: String) : Exception(message)
private class PayloadTooLarge : Exception()
