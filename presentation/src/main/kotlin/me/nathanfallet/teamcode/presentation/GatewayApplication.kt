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
import me.nathanfallet.teamcode.presentation.docs.API_DOCS_PATH
import me.nathanfallet.teamcode.presentation.docs.API_OPENAPI_PATH
import me.nathanfallet.teamcode.presentation.docs.apiDocsRoutes

private const val MAX_BODY_BYTES = 2 * 1024 * 1024

fun Application.configureGateway(gateway: GatewayService, accessToken: String = "", team: TeamService? = null, publicOrigin: String = "http://localhost:8080", accounts: AccountManagement? = null, admission: AdmissionStore? = null, directives: DirectiveService? = null, modelPolicies: ModelPolicyStore? = null, readiness: suspend () -> Boolean = { true }, routes: me.nathanfallet.teamcode.domain.routing.RoutingStore? = null, inboundProtocols: List<InboundProtocol> = listOf(MessagesInboundProtocol(), ResponsesInboundProtocol()), cancellation: RequestCancellationService? = null, devices: DeviceService? = null, serviceKeys: ServiceKeyService? = null, audit: AuditService? = null, pacingSettings: me.nathanfallet.teamcode.domain.capacity.PacingSettingsStore? = null, forecasts: me.nathanfallet.teamcode.domain.capacity.ForecastService? = null, forecastLivePolicy: ForecastLivePolicy = ForecastLivePolicy(), groups: GroupService? = null, runs: AgentRunService? = null, budgetOverrides: me.nathanfallet.teamcode.domain.capacity.BudgetOverrideService? = null, reconciliation: me.nathanfallet.teamcode.domain.capacity.UsageReconciliationService? = null, variants: me.nathanfallet.teamcode.domain.variants.VariantService? = null, skills: me.nathanfallet.teamcode.domain.skills.SkillCatalog? = null, mcp: me.nathanfallet.teamcode.domain.mcp.McpGateway? = null, mcpAccess: me.nathanfallet.teamcode.domain.mcp.McpAccessStore? = null, cloudRuntime: me.nathanfallet.teamcode.domain.cloud.CloudRuntimeService? = null, cloudSessions: me.nathanfallet.teamcode.domain.cloud.CloudSessionService? = null, cloudAdmin: me.nathanfallet.teamcode.domain.cloud.CloudAdministrationService? = null, cloudRunner: CloudRunnerChannel? = null, github: me.nathanfallet.teamcode.domain.github.GitHubLinkService? = null, cloudConversation: CloudConversation? = null, cloudTransfers: me.nathanfallet.teamcode.domain.cloud.CloudTransferService? = null) {
    require(team != null || accessToken.length >= 32) { "The development access token must contain at least 32 characters." }
    val expected = MessageDigest.getInstance("SHA-256").digest(accessToken.toByteArray())
    // Without a runtime service (tests and older wiring), mounted cloud routes are on, as before.
    val cloudOn: () -> Boolean = { cloudRuntime?.enabled ?: true }
    attributes.put(CLOUD_ENABLED, cloudOn)
    install(io.ktor.server.resources.Resources)
    install(ContentNegotiation) { json(Serialization.json) }
    fun inference(call: ApplicationCall) = inboundProtocols.firstOrNull { it.path == call.request.path() }?.protocol
    install(StatusPages) {
        exception<TeamFailure> { call, error ->
            if (call.response.isCommitted) throw error // A revoked/failed stream cannot become a replacement JSON response.
            val protocol = inference(call)
            if (protocol != null) return@exception call.respondInferenceError(protocol, error.status, error.code,
                error.message ?: "Request failed.", error.retryAfterSeconds, error.retryable)
            (error.retryAfterSeconds ?: if (error.status == 429) 60L else null)?.let { call.response.header(HttpHeaders.RetryAfter, it.toString()) }
            if (call.request.path().startsWith("/dashboard")) call.respondText(call.page("Request failed", "<p>${escape(error.message ?: "Request failed.")}</p><a href='/dashboard'>Back to dashboard</a>", lede = ""), ContentType.Text.Html, HttpStatusCode.fromValue(error.status))
            else call.respond(HttpStatusCode.fromValue(error.status), ApiError(ErrorDetail(error.code, error.message ?: "Request failed.")))
        }
        exception<CancellationException> { _, error -> throw error }
        exception<InvalidRequest> { call, error ->
            val protocol = inference(call)
            if (protocol != null) call.respondInferenceError(protocol, 400, "invalid_request", error.message ?: "Invalid request.")
            else call.respond(HttpStatusCode.BadRequest, ApiError(ErrorDetail("invalid_request", error.message ?: "Invalid request.")))
        }
        exception<PayloadTooLarge> { call, _ ->
            val protocol = inference(call)
            if (protocol != null) call.respondInferenceError(protocol, 413, "payload_too_large", "Request exceeds the configured limit.")
            else call.respond(HttpStatusCode.PayloadTooLarge, ApiError(ErrorDetail("payload_too_large", "Request exceeds the configured limit.")))
        }
    }
    intercept(ApplicationCallPipeline.Plugins) {
        call.response.header(HttpHeaders.CacheControl, "no-store")
        call.response.header("X-Content-Type-Options", "nosniff")
        // Not no-referrer: browsers would then send `Origin: null` on dashboard forms, which the origin check rejects.
        call.response.header("Referrer-Policy", "same-origin")
        val path = call.request.path()
        if (team != null) {
            if (path.startsWith("/dashboard")) {
                val live = when (path) {
                    "/dashboard/forecasts" -> " script-src 'self'; connect-src 'self';"
                    "/dashboard/cloud" -> " script-src 'self';" // The kubeconfig drop zone only reads a chosen file in the page.
                    else -> ""
                }
                call.response.header("Content-Security-Policy", "default-src 'none'; style-src 'unsafe-inline'; form-action 'self'; frame-ancestors 'none'; base-uri 'none';" + live)
            }
            // Runner calls carry the pod's projected token, which the runner routes verify themselves.
            // Plain segments only: dot segments, escapes and path parameters never reach another route through this exemption.
            val runnerCall = cloudRunner != null && path.startsWith(RUNNER_PATH) && ".." !in path && '%' !in path && ';' !in path
            // GitHub's redirect back to the dashboard carries no session cookie (SameSite=Strict); the route checks its own state.
            val githubCallback = github != null && path == GITHUB_CALLBACK_PATH
            if (!runnerCall && !githubCallback && path !in setOf("/health", "/ready", "/", "/dashboard/login", "/api/v1/auth/login", API_OPENAPI_PATH, API_DOCS_PATH)) {
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
        } else if (path !in setOf("/health", "/ready", API_OPENAPI_PATH, API_DOCS_PATH)) {
            val presented = call.request.header(HttpHeaders.Authorization)?.removePrefix("Bearer ") ?: ""
            val actual = MessageDigest.getInstance("SHA-256").digest(presented.toByteArray())
            if (!MessageDigest.isEqual(expected, actual)) {
                call.respond(HttpStatusCode.Unauthorized, ApiError(ErrorDetail("authentication_required", "A valid TeamCode access token is required.")))
                finish()
            }
        }
        // After authentication, and only on authenticated routes, so the state is not disclosed to anonymous callers:
        // while cloud sessions are off, only requests that start new work are refused; reading, stopping and configuring
        // stay available. The runner channel and the GitHub callback authenticate themselves and are never gated: no
        // pod exists before a runtime is stored, and a callback only completes a link started while cloud sessions were on.
        if (!cloudOn() && !call.response.isCommitted && cloudStartsWork(call.request.httpMethod.value, path)) throw TeamFailure("not_found", 404, "Cloud sessions are turned off on this server.")
    }
    // The menu mirrors the route registrations below, so it never links to a page this server does not serve.
    attributes.put(DASHBOARD_FEATURES, buildSet {
        if (team == null) return@buildSet
        if (groups != null) add(DashboardFeature.GROUPS)
        if (runs != null) add(DashboardFeature.RUNS)
        if (accounts != null) add(DashboardFeature.ACCOUNTS)
        if (admission != null) add(DashboardFeature.CAPACITY)
        if (reconciliation != null) add(DashboardFeature.USAGE_REVIEW)
        if (directives != null) add(DashboardFeature.GUIDANCE)
        if (forecasts != null) add(DashboardFeature.FORECASTS)
        if (modelPolicies != null) add(DashboardFeature.MODEL_POLICY)
        if (routes != null) add(DashboardFeature.ROUTES)
        if (devices != null) add(DashboardFeature.DEVICES)
        if (serviceKeys != null) add(DashboardFeature.SERVICE_KEYS)
        if (audit != null) add(DashboardFeature.AUDIT)
        if (skills != null) add(DashboardFeature.SKILLS)
        if (variants != null) add(DashboardFeature.VARIANTS)
        if (mcpAccess != null) add(DashboardFeature.MCP)
        if (cloudSessions != null && cloudAdmin != null) add(DashboardFeature.CLOUD)
        if (github != null) add(DashboardFeature.GITHUB)
    })
    routing {
        if (team != null) { teamRoutes(team); dashboardRoutes(team, publicOrigin, githubLinks = github != null, OverviewSources(admission, runs, accounts)) }
        if (team != null && runs != null) { agentRunRoutes(runs); agentRunDashboardRoutes(runs, publicOrigin) }
        if (team != null && groups != null) { groupRoutes(groups); groupDashboardRoutes(groups, team, publicOrigin) }
        if (accounts != null) accountRoutes(accounts, publicOrigin)
        if (admission != null && team != null) capacityRoutes(admission, team, publicOrigin, budgetOverrides != null, groups, accounts)
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
        if (skills != null && team != null) skillRoutes(skills, team, publicOrigin)
        if (variants != null && team != null) variantRoutes(variants, publicOrigin)
        if (mcp != null && team != null) mcpGatewayRoutes(mcp, publicOrigin)
        if (mcpAccess != null && team != null) mcpAdminRoutes(mcpAccess, variants, publicOrigin)
        if (cloudSessions != null && cloudAdmin != null && team != null) cloudRoutes(cloudSessions, cloudAdmin, publicOrigin, cloudRuntime)
        if (cloudRunner != null && cloudSessions != null && team != null) cloudRunnerRoutes(cloudRunner)
        if (github != null && team != null) githubRoutes(github, team, publicOrigin)
        if (cloudConversation != null && cloudSessions != null && team != null) {
            cloudConversationRoutes(cloudConversation)
            if (cloudRunner != null) cloudRunnerConversationRoutes(cloudRunner, cloudConversation)
        }
        if (cloudTransfers != null && cloudSessions != null && team != null) cloudTransferRoutes(cloudTransfers)
        if (cancellation != null && team != null) {
            post("/api/v1/requests/{id}/cancel") {
                cancellation.cancel(call.principal, call.parameters["id"].orEmpty())
                call.respond(HttpStatusCode.NoContent)
            }
        }
        apiDocsRoutes(publicOrigin, inboundProtocols.map { it.path }.toSet())
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
                    (if (team != null && admission != null) listOf("capacity.shared-overflow.v1", "usage.tokens.v1") else emptyList()) +
                    (if (team != null && cancellation != null) listOf("requests.cancel.v1") else emptyList()) +
                    (if (team != null && runs != null) listOf("team.runs.v1") else emptyList()) +
                    (if (team != null && reconciliation != null) listOf("capacity.usage-reconciliation.v1") else emptyList()) +
                    (if (team != null && budgetOverrides != null && admission != null) listOf("capacity.budget-overrides.v1") else emptyList()) +
                    (if (team != null && groups != null) listOf("team.groups.v1") else emptyList()) +
                    (if (team != null && devices != null) listOf("team.devices") else emptyList()) +
                    (if (team != null && serviceKeys != null) listOf("team.service-keys.v1") else emptyList()) +
                    (if (team != null && audit != null) listOf("team.audit.v1") else emptyList()) +
                    (if (team != null && forecasts != null) listOf("capacity.forecasts.v1", "capacity.forecasts.live.v1") else emptyList()) +
                    (if (team != null && skills != null) listOf("team.skills.v1", "team.skills.publish-pin.v1") else emptyList()) +
                    (if (team != null && variants != null) listOf("team.variants.v1") else emptyList()) +
                    (if (team != null && cloudOn()) listOfNotNull<List<String>>(
                        if (cloudSessions?.acceptsInitialMessage == true) listOf("cloud.sessions.initial-message.v1") else null,
                        if (cloudSessions != null && cloudAdmin != null) listOf("cloud.sessions.v1") else null,
                        if (cloudSessions != null && cloudAdmin != null && cloudRunner != null) listOf("cloud.environment.v1") else null,
                        if (cloudSessions != null && cloudRunner != null && cloudConversation != null) listOf("cloud.transcript.v1", "cloud.messages.v1", "cloud.attach.v1", "cloud.access.v1") else null,
                        if (cloudSessions != null && cloudRunner?.inference != null) listOf("cloud.inference.v1") else null,
                        if (cloudSessions != null && cloudRunner?.github != null) listOf("cloud.github.v1") else null,
                        if (cloudSessions != null && cloudTransfers != null) listOf("cloud.transfer.v1") else null,
                        if (cloudSessions != null && cloudAdmin != null) listOf("cloud.keys.v1") else null,
                    ).flatten() else emptyList()) +
                    (if (team != null && mcp != null) listOf("mcp.gateway.v1") else emptyList()) +
                    (if (team != null && github != null) listOf("github.link.v1") else emptyList())))
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
                head.headers.filterKeys { it.lowercase() in setOf("retry-after", "x-teamcode-request-id", "x-teamcode-model", "x-teamcode-provider", "x-teamcode-error") }.forEach { (name, value) -> call.response.header(name, value) }
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
