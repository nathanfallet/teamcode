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
import io.ktor.utils.io.readUTF8Line
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
) : AutoCloseable, RunLifecycleClient {
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
    /** Request-specific evidence and reconciliation are restricted to interactive administrators. */
    suspend fun pendingUsage(memberId: String? = null, beforeId: String? = null): PendingUsagePageView =
        request(usagePage("pending", memberId, beforeId), HttpMethod.Get)
    suspend fun reconciledUsage(memberId: String? = null, beforeId: String? = null): ReconciledUsagePageView =
        request(usagePage("reconciliations", memberId, beforeId), HttpMethod.Get)
    suspend fun usageReview(id: String): UsageReviewView { identifier(id); return request("/api/v1/usage/reservations/$id", HttpMethod.Get) }
    suspend fun reconcileUsage(id: String, input: UsageReconciliationRequest): ReconciledUsageView {
        identifier(id); return request("/api/v1/usage/reservations/$id/reconcile", HttpMethod.Post, input)
    }
    private fun usagePage(resource: String, memberId: String?, beforeId: String?): String {
        memberId?.let(::identifier); beforeId?.let(::identifier)
        val parameters = listOfNotNull(memberId?.let { "member=$it" }, beforeId?.let { "before=$it" }).joinToString("&")
        return "/api/v1/usage/$resource" + if (parameters.isEmpty()) "" else "?$parameters"
    }
    /** Temporary daily allocations require an interactive administrator and never reset usage. */
    suspend fun budgetOverrides(memberId: String, beforeId: String? = null): BudgetOverridePageView {
        identifier(memberId); beforeId?.let(::identifier)
        return request("/api/v1/members/$memberId/budget-overrides" + (beforeId?.let { "?before=$it" } ?: ""), HttpMethod.Get)
    }
    suspend fun grantBudgetOverride(memberId: String, input: BudgetOverrideRequest): BudgetOverrideView {
        identifier(memberId); input.replacesId?.let(::identifier)
        return request("/api/v1/members/$memberId/budget-overrides", HttpMethod.Post, input)
    }
    suspend fun revokeBudgetOverride(memberId: String, id: String, reason: String): BudgetOverrideView {
        identifier(memberId); identifier(id)
        return request("/api/v1/members/$memberId/budget-overrides/$id/revoke", HttpMethod.Post, RevokeBudgetOverrideRequest(reason))
    }
    suspend fun capacity(): CapacityView = request("/api/v1/capacity", HttpMethod.Get)
    /** Accounts the caller may be routed to; members get neutral labels and never another member's usage. */
    suspend fun poolAccounts(): List<PoolAccountStatusView> = request("/api/v1/pools/accounts", HttpMethod.Get)
    /** Allocation and shared physical reserves require an interactive administrator. */
    suspend fun saveMemberPolicy(memberId: String, input: PolicyRequest): CapacityView {
        identifier(memberId); return request("/api/v1/members/$memberId/policy", HttpMethod.Patch, input)
    }
    suspend fun poolBudgets(): List<PoolBudgetView> = request("/api/v1/pools/budgets", HttpMethod.Get)
    suspend fun savePoolBudget(input: PoolBudgetView): PoolBudgetView = request("/api/v1/pools/budgets", HttpMethod.Put, input)
    /** The automatic home pool size and the estimate behind it (`team:read`); turn it on with [savePacingSettings]. */
    suspend fun poolSize(): PoolSizeView = request("/api/v1/pools/size", HttpMethod.Get)
    /** The team's dated API price table (`usage:read`); saving replaces the whole table and needs an interactive administrator. */
    suspend fun apiPrices(): ApiPriceTableView = request("/api/v1/policy/api-prices", HttpMethod.Get)
    suspend fun saveApiPrices(table: ApiPriceTableView): ApiPriceTableView = request("/api/v1/policy/api-prices", HttpMethod.Put, table)
    /** Adds Anthropic's current list prices, dated the day they were read, keeping rows already present (administrators). */
    suspend fun loadDefaultApiPrices(): ApiPriceTableView = request("/api/v1/policy/api-prices/load-defaults", HttpMethod.Post)
    /** Controller configuration is managed with an interactive administrator login. */
    suspend fun pacingSettings(): PacingSettingsView = request("/api/v1/policy/pacing", HttpMethod.Get)
    suspend fun savePacingSettings(settings: PacingSettingsView): PacingSettingsView = request("/api/v1/policy/pacing", HttpMethod.Put, settings)
    /** Read proposed advice for the current member, even when automatic delivery is disabled. */
    suspend fun pacingPreview(): PacingPreviewResponse = request("/api/v1/directives/preview", HttpMethod.Get)
    /** Administrative per-account quota history and conditional forecasts, without provider credentials. */
    suspend fun forecasts(accountId: String? = null): ForecastSnapshotView {
        accountId?.let(::identifier)
        return request("/api/v1/forecasts" + (accountId?.let { "?account=$it" } ?: ""), HttpMethod.Get)
    }
    suspend fun accounts(): List<AccountView> = request("/api/v1/accounts", HttpMethod.Get)
    /** Failures carry the server's code, such as `account_enrollment_forbidden` or `account_in_use`. */
    suspend fun enrollAccount(input: EnrollAccountRequest): EnrollAccountResponse = coded(HttpMethod.Post, "/api/v1/accounts", input)
    /** Who can add accounts, and whether the signed-in member may; any member can ask (feature `accounts.enrollment-policy.v1`). */
    suspend fun accountEnrollmentPolicy(): AccountEnrollmentPolicyView = request("/api/v1/policy/account-enrollment", HttpMethod.Get)
    /** Administrator-only: [policy] is `administrators` or `members`. */
    suspend fun saveAccountEnrollmentPolicy(policy: String): AccountEnrollmentPolicyView =
        coded(HttpMethod.Put, "/api/v1/policy/account-enrollment", AccountEnrollmentPolicyView(policy))
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
    /** Register one local wrapper launch. The UUID makes a repeated start idempotent. */
    override suspend fun startRun(input: StartRunRequest): RunLeaseView { identifier(input.id); return request("/api/v1/runs", HttpMethod.Post, input) }
    override suspend fun heartbeatRun(id: String): RunLeaseView { identifier(id); return request("/api/v1/runs/$id/heartbeat", HttpMethod.Post) }
    override suspend fun endRun(id: String, exitCode: Int?): RunLeaseView { identifier(id); return request("/api/v1/runs/$id/end", HttpMethod.Post, EndRunRequest(exitCode)) }
    suspend fun runs(memberId: String? = null, all: Boolean = false, beforeId: String? = null, labels: Map<String, String> = emptyMap()): RunPageView {
        require(!all || memberId == null) { "Choose one member or all members." }
        memberId?.let(::identifier); beforeId?.let(::identifier)
        val query = buildList {
            memberId?.let { add("member=$it") }; if (all) add("all=true"); beforeId?.let { add("before=$it") }
            labels.forEach { (key, value) -> add("label=" + "$key=$value".encodeURLParameter()) }
        }.joinToString("&")
        return request("/api/v1/runs" + if (query.isEmpty()) "" else "?$query", HttpMethod.Get)
    }
    /** Raw provider tokens by UTC day (`YYYY-MM-DD`, inclusive), member, launch, provider and model; page with `nextAfter`. */
    suspend fun tokenUsage(from: String? = null, to: String? = null, memberId: String? = null, all: Boolean = false, after: String? = null): TokenUsagePageView {
        require(!all || memberId == null) { "Choose one member or all members." }
        val day = Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}")
        require(from?.let(day::matches) ?: true && to?.let(day::matches) ?: true) { "Use dates written YYYY-MM-DD." }
        require(after?.let { Regex("[a-zA-Z0-9_.:-]{1,400}").matches(it) } ?: true) { "Use the nextAfter value of the previous page." }
        memberId?.let(::identifier)
        val query = buildList {
            from?.let { add("from=$it") }; to?.let { add("to=$it") }; memberId?.let { add("member=$it") }; if (all) add("all=true"); after?.let { add("after=$it") }
        }.joinToString("&")
        return request("/api/v1/usage/tokens" + if (query.isEmpty()) "" else "?$query", HttpMethod.Get)
    }
    suspend fun run(id: String): RunDetailView { identifier(id); return request("/api/v1/runs/$id", HttpMethod.Get) }
    suspend fun saveRunPolicy(id: String, policy: RunPolicyView): AgentRunView {
        identifier(id); return request("/api/v1/runs/$id/policy", HttpMethod.Put, policy)
    }
    /** Group manager authority is scoped; budget and membership writes require a team administrator. */
    suspend fun groups(): List<GroupView> = request("/api/v1/groups", HttpMethod.Get)
    suspend fun group(id: String): GroupDetailView { identifier(id); return request("/api/v1/groups/$id", HttpMethod.Get) }
    suspend fun createGroup(policy: GroupPolicyRequest): GroupDetailView = request("/api/v1/groups", HttpMethod.Post, policy)
    suspend fun saveGroup(id: String, policy: GroupPolicyRequest): GroupDetailView {
        identifier(id); return request("/api/v1/groups/$id", HttpMethod.Put, policy)
    }
    suspend fun assignGroupMember(id: String, memberId: String, assignment: GroupAssignmentRequest): GroupDetailView {
        identifier(id); identifier(memberId); return request("/api/v1/groups/$id/members/$memberId", HttpMethod.Put, assignment)
    }
    suspend fun removeGroupMember(id: String, memberId: String): SuccessResponse {
        identifier(id); identifier(memberId); return request("/api/v1/groups/$id/members/$memberId", HttpMethod.Delete)
    }
    suspend fun pauseGroupMember(id: String, memberId: String, paused: Boolean): GroupDetailView {
        identifier(id); identifier(memberId); return request("/api/v1/groups/$id/members/$memberId/pause", HttpMethod.Patch, GroupPauseRequest(paused))
    }
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

    suspend fun cloudSettings(): CloudSettingsView = request("/api/v1/cloud/settings", HttpMethod.Get)
    suspend fun saveCloudSettings(settings: CloudSettingsView): CloudSettingsView = request("/api/v1/cloud/settings", HttpMethod.Put, settings)
    suspend fun cloudClusters(): List<CloudClusterView> = request("/api/v1/cloud/clusters", HttpMethod.Get)
    suspend fun saveCloudCluster(input: SaveCloudClusterRequest, id: String? = null): CloudClusterView {
        id?.let(::identifier)
        return request("/api/v1/cloud/clusters" + (id?.let { "/$it" } ?: ""), if (id == null) HttpMethod.Post else HttpMethod.Put, input)
    }
    /** The profiles the caller can use, or those [member] can use (cloud administrators, `cloud.profiles.member.v1`). */
    suspend fun cloudProfiles(member: String? = null): List<CloudProfileView> {
        member?.let(::identifier)
        return request("/api/v1/cloud/profiles" + (member?.let { "?member=$it" } ?: ""), HttpMethod.Get)
    }
    suspend fun saveCloudProfile(input: SaveCloudProfileRequest, id: String? = null): CloudProfileView {
        id?.let(::identifier)
        return request("/api/v1/cloud/profiles" + (id?.let { "/$it" } ?: ""), if (id == null) HttpMethod.Post else HttpMethod.Put, input)
    }
    /** Retrying with the same [idempotencyKey] and body returns the session created by the first attempt. */
    suspend fun createCloudSession(input: CreateCloudSessionRequest, idempotencyKey: String = Uuid.random().toString(), onBehalfOf: String? = null): CloudSessionView {
        require(CloudHeaders.idempotencyKey.matches(idempotencyKey)) { "Invalid idempotency key." }
        onBehalfOf?.let(::identifier)
        val response = http.request("$origin/api/v1/cloud/sessions") {
            method = HttpMethod.Post; contentType(ContentType.Application.Json); setBody(input); authenticate()
            header(CloudHeaders.IDEMPOTENCY_KEY, idempotencyKey)
            onBehalfOf?.let { header(CloudHeaders.ON_BEHALF_OF, it) }
            timeout { requestTimeoutMillis = 30_000 }
        }
        checkResponse(response)
        return response.body()
    }
    /** [owner] is a member id or `all` (administrators); null lists the caller's own sessions. */
    suspend fun cloudSessions(owner: String? = null, states: List<String> = emptyList(), labels: Map<String, String> = emptyMap(), before: String? = null, limit: Int = 50): CloudSessionPageView {
        owner?.takeIf { it != "all" }?.let(::identifier)
        before?.let(::identifier)
        require(limit in 1..50)
        val response = http.request("$origin/api/v1/cloud/sessions") {
            method = HttpMethod.Get; authenticate(); timeout { requestTimeoutMillis = 10_000 }
            url {
                owner?.let { parameters.append("owner", it) }
                states.forEach { parameters.append("state", it) }
                labels.forEach { (key, value) -> parameters.append("label", "$key=$value") }
                before?.let { parameters.append("before", it) }
                parameters.append("limit", limit.toString())
            }
        }
        checkResponse(response)
        return response.body()
    }
    suspend fun cloudSession(id: String): CloudSessionView { identifier(id); return request("/api/v1/cloud/sessions/$id", HttpMethod.Get) }
    suspend fun cloudExecutions(id: String): List<CloudExecutionView> { identifier(id); return request("/api/v1/cloud/sessions/$id/executions", HttpMethod.Get) }
    suspend fun pauseCloudSession(id: String, force: Boolean = false, onBehalfOf: String? = null): CloudSessionView =
        cloudAction("/api/v1/cloud/sessions/$id/pause", id, onBehalfOf, PauseCloudSessionRequest(force)).body()
    suspend fun resumeCloudSession(id: String, onBehalfOf: String? = null): CloudSessionView = cloudAction("/api/v1/cloud/sessions/$id/resume", id, onBehalfOf, null).body()
    suspend fun deleteCloudSession(id: String, onBehalfOf: String? = null) { cloudAction("/api/v1/cloud/sessions/$id", id, onBehalfOf, null, HttpMethod.Delete) }
    suspend fun githubApp(): GitHubAppView = request("/api/v1/github/app", HttpMethod.Get)
    suspend fun saveGitHubApp(input: SaveGitHubAppRequest): GitHubAppView = request("/api/v1/github/app", HttpMethod.Put, input)
    /** The caller's link, or another member's for an administrator. Never contains a token. */
    suspend fun githubLink(memberId: String? = null): GitHubLinkView {
        memberId?.let(::identifier)
        return request(if (memberId == null) "/api/v1/github/link" else "/api/v1/github/links/$memberId", HttpMethod.Get)
    }
    suspend fun githubLinks(): List<GitHubLinkView> = request("/api/v1/github/links", HttpMethod.Get)
    /** Starts GitHub's device flow; show the user code, then call [pollGitHubDeviceLink] every `interval` seconds. */
    suspend fun startGitHubDeviceLink(): GitHubDeviceLinkView = request("/api/v1/github/link/device", HttpMethod.Post, 30_000)
    suspend fun pollGitHubDeviceLink(id: String): GitHubDeviceLinkPollView { identifier(id); return request("/api/v1/github/link/device/$id", HttpMethod.Post, 60_000) }
    suspend fun unlinkGitHub(memberId: String? = null) {
        memberId?.let(::identifier)
        checkResponse(http.delete("$origin/api/v1/github/" + (memberId?.let { "links/$it" } ?: "link")) { authenticate(); timeout { requestTimeoutMillis = 30_000 } })
    }

    /** The team's artifact bucket (administrators); never contains the secret access key. */
    suspend fun artifactStorage(): ArtifactStorageView = request("/api/v1/artifacts/storage", HttpMethod.Get)
    suspend fun saveArtifactStorage(input: SaveArtifactStorageRequest): ArtifactStorageView = request("/api/v1/artifacts/storage", HttpMethod.Put, input)
    /** Removes the bucket configuration while no artifact exists; artifacts are then off. */
    suspend fun deleteArtifactStorage() {
        checkResponse(http.delete("$origin/api/v1/artifacts/storage") { authenticate(); timeout { requestTimeoutMillis = 30_000 } })
    }
    /** Writes, reads, lists and deletes a probe object; up to a minute against a slow bucket. */
    suspend fun testArtifactStorage(): ArtifactStorageTestView = request("/api/v1/artifacts/storage/test", HttpMethod.Post, 90_000)
    suspend fun artifactSettings(): ArtifactSettingsView = request("/api/v1/artifacts/settings", HttpMethod.Get)
    suspend fun saveArtifactSettings(input: SaveArtifactSettingsRequest): ArtifactSettingsView = request("/api/v1/artifacts/settings", HttpMethod.Put, input)

    /** Runner channel; construct the client with the pod's projected token as its credential. */
    suspend fun runnerRegister(input: RunnerRegisterRequest): RunnerInstructionView = runnerRequest("register", input)
    suspend fun runnerHeartbeat(input: RunnerHeartbeatRequest): RunnerInstructionView = runnerRequest("heartbeat", input)
    suspend fun runnerCheckpoint(input: RunnerCheckpointRequest): RunnerInstructionView = runnerRequest("checkpoint", input)
    /** The preparation recipe with the owner's variable values and GitHub token; never log the result. */
    suspend fun runnerEnvironment(input: RunnerEnvironmentRequest): RunnerEnvironmentView = runnerRequest("environment", input)
    suspend fun runnerPreparation(input: RunnerPreparationRequest): RunnerInstructionView = runnerRequest("preparation", input)
    /** Failures carry the server's error code (for example `execution_fenced`), never its message. */
    private suspend inline fun <reified R, reified B> runnerRequest(path: String, body: B): R {
        val response = http.request("$origin/api/v1/cloud/runner/$path") {
            method = HttpMethod.Post; contentType(ContentType.Application.Json); setBody(body); authenticate(); timeout { requestTimeoutMillis = 30_000 }
        }
        if (!response.status.isSuccess()) {
            throw failure(response)
        }
        return response.body()
    }
    private suspend fun cloudAction(path: String, id: String, onBehalfOf: String?, body: PauseCloudSessionRequest?, method: HttpMethod = HttpMethod.Post): HttpResponse {
        identifier(id); onBehalfOf?.let(::identifier)
        val response = http.request("$origin$path") {
            this.method = method; authenticate(); timeout { requestTimeoutMillis = 10_000 }
            onBehalfOf?.let { header(CloudHeaders.ON_BEHALF_OF, it) }
            if (body != null) { contentType(ContentType.Application.Json); setBody(body) }
        }
        checkResponse(response)
        return response
    }

    // Transcript, messages and attachments (`cloud.transcript.v1`, `cloud.messages.v1`, `cloud.attach.v1`).
    suspend fun cloudEvents(id: String, after: Long? = null, before: Long? = null, limit: Int = 100): CloudEventPageView {
        identifier(id)
        require(limit in 1..200 && (after == null || before == null))
        return coded(HttpMethod.Get, "/api/v1/cloud/sessions/$id/events", null as Unit?) {
            url { after?.let { parameters.append("after", it.toString()) }; before?.let { parameters.append("before", it.toString()) }; parameters.append("limit", limit.toString()) }
        }
    }
    /**
     * Follows the session's event stream after [after], calling [onEvent] for each event in order. Returns
     * [CloudStreamEnd.ENDED] when the session ended and every event was sent, [CloudStreamEnd.DENIED] when the server
     * revoked access mid-stream; throws on a network failure, so the caller reconnects from its last sequence.
     */
    suspend fun followCloudEvents(id: String, after: Long, onEvent: suspend (CloudEventView) -> Unit): CloudStreamEnd {
        identifier(id)
        require(after >= 0)
        return http.prepareGet("$origin/api/v1/cloud/sessions/$id/events/stream") {
            authenticate()
            header("Last-Event-ID", after.toString())
            header(HttpHeaders.Accept, "text/event-stream")
            // Keep-alives arrive every 15 s; a silent connection for 60 s is dead.
            timeout { requestTimeoutMillis = HttpTimeoutConfig.INFINITE_TIMEOUT_MS; socketTimeoutMillis = 60_000 }
        }.execute { response ->
            if (!response.status.isSuccess()) throw failure(response)
            readEventStream(response, onEvent)
        }
    }
    private suspend fun readEventStream(response: HttpResponse, onEvent: suspend (CloudEventView) -> Unit): CloudStreamEnd {
        val channel = response.bodyAsChannel()
        var event: String? = null
        val data = StringBuilder()
        while (true) {
            val line = channel.readUTF8Line(MAX_SSE_LINE) ?: throw TeamCodeException(0, "The event stream closed.")
            when {
                line.isEmpty() -> {
                    when (event) {
                        "end" -> return CloudStreamEnd.ENDED
                        "denied" -> return CloudStreamEnd.DENIED
                        null -> Unit
                        else -> if (data.isNotEmpty()) onEvent(Serialization.json.decodeFromString<CloudEventView>(data.toString()))
                    }
                    event = null; data.clear()
                }
                line.startsWith(":") -> Unit
                line.startsWith("event:") -> event = line.removePrefix("event:").trim()
                line.startsWith("data:") -> { if (data.isNotEmpty()) data.append('\n'); data.append(line.removePrefix("data:").removePrefix(" ")) }
            }
        }
    }
    /** [idempotencyKey] makes a retry return the first acknowledgement instead of sending the message twice. */
    suspend fun sendCloudMessage(id: String, input: SendCloudMessageRequest, idempotencyKey: String? = null): CloudInputView {
        identifier(id)
        idempotencyKey?.let { require(CloudHeaders.idempotencyKey.matches(it)) { "Invalid idempotency key." } }
        return coded(HttpMethod.Post, "/api/v1/cloud/sessions/$id/messages", input) { idempotencyKey?.let { header(CloudHeaders.IDEMPOTENCY_KEY, it) } }
    }
    suspend fun interruptCloudSession(id: String, idempotencyKey: String? = null): CloudInputView {
        identifier(id)
        idempotencyKey?.let { require(CloudHeaders.idempotencyKey.matches(it)) { "Invalid idempotency key." } }
        return coded(HttpMethod.Post, "/api/v1/cloud/sessions/$id/interrupt", null as Unit?) { idempotencyKey?.let { header(CloudHeaders.IDEMPOTENCY_KEY, it) } }
    }
    suspend fun cloudInputs(id: String, before: String? = null, limit: Int = 50): CloudInputPageView {
        identifier(id); before?.let(::identifier); require(limit in 1..200)
        return coded(HttpMethod.Get, "/api/v1/cloud/sessions/$id/messages", null as Unit?) { url { before?.let { parameters.append("before", it) }; parameters.append("limit", limit.toString()) } }
    }
    suspend fun cloudInput(id: String, inputId: String): CloudInputView { identifier(id); identifier(inputId); return coded(HttpMethod.Get, "/api/v1/cloud/sessions/$id/messages/$inputId", null as Unit?) }
    suspend fun attachCloudSession(id: String): CloudAttachmentView { identifier(id); return coded(HttpMethod.Post, "/api/v1/cloud/sessions/$id/attachments", null as Unit?) }
    suspend fun renewCloudAttachment(id: String, attachmentId: String): CloudAttachmentView {
        identifier(id); identifier(attachmentId)
        return coded(HttpMethod.Post, "/api/v1/cloud/sessions/$id/attachments/$attachmentId/renew", null as Unit?)
    }
    suspend fun detachCloudSession(id: String, attachmentId: String) {
        identifier(id); identifier(attachmentId)
        val response = http.delete("$origin/api/v1/cloud/sessions/$id/attachments/$attachmentId") { authenticate(); timeout { requestTimeoutMillis = 10_000 } }
        if (!response.status.isSuccess()) throw failure(response)
    }
    suspend fun cloudAccessPolicy(): CloudAccessPolicyView = coded(HttpMethod.Get, "/api/v1/cloud/access", null as Unit?)
    suspend fun saveCloudAccessPolicy(policy: CloudAccessPolicyView): CloudAccessPolicyView = coded(HttpMethod.Put, "/api/v1/cloud/access", policy)

    // Ownership transfers (`cloud.transfer.v1`).
    /** Retrying with the same [idempotencyKey] replays the first transfer instead of creating another. */
    suspend fun transferCloudSession(id: String, input: CreateCloudTransferRequest, idempotencyKey: String = Uuid.random().toString(), onBehalfOf: String? = null): CloudTransferView {
        identifier(id); identifier(input.toMemberId); onBehalfOf?.let(::identifier)
        require(CloudHeaders.idempotencyKey.matches(idempotencyKey)) { "Invalid idempotency key." }
        return coded(HttpMethod.Post, "/api/v1/cloud/sessions/$id/transfer", input) {
            header(CloudHeaders.IDEMPOTENCY_KEY, idempotencyKey)
            onBehalfOf?.let { header(CloudHeaders.ON_BEHALF_OF, it) }
        }
    }
    /** [direction] is `incoming`, `outgoing` or null for both; [team] lists every team transfer (administrators). */
    suspend fun cloudTransfers(direction: String? = null, team: Boolean = false, states: List<String> = emptyList(), session: String? = null, before: String? = null, limit: Int = 50): CloudTransferPageView {
        session?.let(::identifier); before?.let(::identifier)
        return coded(HttpMethod.Get, "/api/v1/cloud/transfers", null as Unit?) {
            url {
                direction?.let { parameters.append("direction", it) }
                if (team) parameters.append("scope", "team")
                states.forEach { parameters.append("state", it) }
                session?.let { parameters.append("session", it) }
                before?.let { parameters.append("before", it) }
                parameters.append("limit", limit.toString())
            }
        }
    }
    suspend fun cloudTransfer(id: String): CloudTransferView { identifier(id); return coded(HttpMethod.Get, "/api/v1/cloud/transfers/$id", null as Unit?) }
    suspend fun acceptCloudTransfer(id: String, resume: Boolean? = null, onBehalfOf: String? = null): CloudTransferView =
        transferDecision(id, "accept", AcceptCloudTransferRequest(resume), onBehalfOf)
    suspend fun declineCloudTransfer(id: String, onBehalfOf: String? = null): CloudTransferView = transferDecision<Unit>(id, "decline", null, onBehalfOf)
    suspend fun cancelCloudTransfer(id: String, onBehalfOf: String? = null): CloudTransferView = transferDecision<Unit>(id, "cancel", null, onBehalfOf)
    private suspend inline fun <reified B> transferDecision(id: String, action: String, body: B?, onBehalfOf: String?): CloudTransferView {
        identifier(id); onBehalfOf?.let(::identifier)
        return coded(HttpMethod.Post, "/api/v1/cloud/transfers/$id/$action", body) { onBehalfOf?.let { header(CloudHeaders.ON_BEHALF_OF, it) } }
    }
    /** Agent side of the runner channel; construct the client with the pod's projected token. */
    suspend fun runnerAgent(input: RunnerAgentRequest): RunnerAgentView = coded(HttpMethod.Post, "/api/v1/cloud/runner/agent", input)
    suspend fun runnerEvents(input: RunnerEventsRequest): RunnerEventsAckView = coded(HttpMethod.Post, "/api/v1/cloud/runner/events", input)
    suspend fun runnerInputs(input: RunnerInputsRequest): RunnerInputsView =
        coded(HttpMethod.Post, "/api/v1/cloud/runner/inputs", input) { timeout { requestTimeoutMillis = input.waitMillis + 30_000 } }
    suspend fun runnerAcknowledge(input: RunnerInputAckRequest) { coded<Unit, RunnerInputAckRequest>(HttpMethod.Post, "/api/v1/cloud/runner/inputs/ack", input) }
    /** A `tcex_` inference credential for this execution; keep it in memory only. */
    suspend fun runnerInference(input: RunnerInferenceRequest): RunnerInferenceCredentialView = coded(HttpMethod.Post, "/api/v1/cloud/runner/inference", input)
    /** The execution owner's current GitHub token (`status` `linked`), or why there is none; keep it in memory only. */
    suspend fun runnerGitHub(input: RunnerGitHubRequest): RunnerGitHubView = coded(HttpMethod.Post, "/api/v1/cloud/runner/github", input)

    /** Failures carry the server's error code (for example `cloud_permission_denied`), never its message. */
    private suspend inline fun <reified R, reified B> coded(method: HttpMethod, path: String, body: B?, crossinline configure: HttpRequestBuilder.() -> Unit = {}): R {
        val response = http.request("$origin$path") {
            this.method = method; authenticate(); timeout { requestTimeoutMillis = 30_000 }
            if (body != null) { contentType(ContentType.Application.Json); setBody(body) }
            configure()
        }
        if (!response.status.isSuccess()) throw failure(response)
        return if (R::class == Unit::class) Unit as R else response.body()
    }
    /**
     * The failure of a non-success response, with only safe, validated fields: the API error `type` (never its
     * message or the raw body, which a proxy or provider may fill with secrets), `Retry-After` in seconds and a
     * proxy-assigned request id.
     */
    private suspend fun failure(response: HttpResponse): TeamCodeException {
        val code = try {
            // Bounded: an error envelope is small, and a proxy's large or slow body must not be buffered whole.
            val bytes = response.bodyAsChannel().readRemaining(MAX_ERROR_BODY + 1L).readByteArray()
            if (bytes.size > MAX_ERROR_BODY) null
            else Serialization.json.decodeFromString(ApiError.serializer(), bytes.decodeToString()).error.type.takeIf { ERROR_CODE.matches(it) }
        } catch (error: CancellationException) { throw error } catch (_: Exception) { null }
        return TeamCodeException(response.status.value, "TeamCode returned HTTP ${response.status.value}.", code,
            retryAfterSeconds(response.headers[HttpHeaders.RetryAfter]),
            REQUEST_ID_HEADERS.flatMap { response.headers.getAll(it).orEmpty() }.firstOrNull { REQUEST_ID.matches(it) })
    }

    private suspend inline fun <reified R> request(path: String, method: HttpMethod, timeoutMillis: Long = 10_000): R {
        val response = http.request("$origin$path") { this.method = method; authenticate(); timeout { requestTimeoutMillis = timeoutMillis } }
        checkResponse(response)
        return response.body()
    }
    private suspend inline fun <reified R, reified B> request(path: String, method: HttpMethod, body: B): R {
        val response = http.request("$origin$path") { this.method = method; contentType(ContentType.Application.Json); setBody(body); authenticate(); timeout { requestTimeoutMillis = 30_000 } }
        checkResponse(response)
        return response.body()
    }

    /** The skills the signed-in member is currently allowed to install: name, approved version and digest. */
    suspend fun skillManifest(): SkillManifestView = request("/api/v1/skill-delivery", HttpMethod.Get)

    /** The files of one effective skill version; fails with `404` once it is no longer the approved version. */
    suspend fun skillArtifact(name: String, version: String): SkillArtifactView {
        require(SKILL_NAME.matches(name) && SKILL_VERSION.matches(version)) { "Invalid skill name or version." }
        return request("/api/v1/skill-delivery/$name/$version", HttpMethod.Get)
    }

    /** The team hooks the signed-in member is currently allowed to install: name, approved version, digest and definition. */
    suspend fun hookManifest(): HookManifestView = request("/api/v1/hook-delivery", HttpMethod.Get)

    /** The files of one effective hook version; fails with `404` once it is no longer the approved version. */
    suspend fun hookArtifact(name: String, version: String): HookArtifactView {
        require(SKILL_NAME.matches(name) && name.length <= 64 && SKILL_VERSION.matches(version)) { "Invalid hook name or version." }
        return request("/api/v1/hook-delivery/$name/$version", HttpMethod.Get)
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
            throw failure(response)
        }
    }

    override fun close() { inferenceClosed.complete(Unit); http.close() }

    companion object {
        val FORWARDED_HEADERS: Set<String> = me.nathanfallet.teamcode.api.InferenceHeaders.names
        private const val MAX_SSE_LINE = 256 * 1024
        private val ERROR_CODE = Regex("[a-z_]{1,64}")
        private const val MAX_ERROR_BODY = 16 * 1024
        private val REQUEST_ID = Regex("[A-Za-z0-9._:-]{1,128}")
        private val REQUEST_ID_HEADERS = listOf("X-Request-Id", "X-Correlation-Id", "CF-Ray")
        /** MCP variant target names, which are also the last segment of the gateway's `/mcp/<server>` endpoints. */
        val MCP_SERVER_NAME = Regex("[a-z0-9][a-z0-9._-]{0,63}")
        val SKILL_NAME = Regex("[a-z0-9]+(-[a-z0-9]+)*")
        val SKILL_VERSION = Regex("[0-9A-Za-z][0-9A-Za-z.+_-]{0,63}")
        private const val MCP_RESPONSE_LIMIT = 65 * 1024 * 1024 // The largest gateway response limit plus its JSON-RPC envelope.
    }
}

/**
 * A refused or failed TeamCode call. [code] is the API error `type` when the server sent one, [retryAfterSeconds] the
 * `Retry-After` delay and [requestId] a request id set by the server or a proxy; all are validated and safe to print.
 */
class TeamCodeException(
    val statusCode: Int,
    message: String,
    val code: String? = null,
    val retryAfterSeconds: Long? = null,
    val requestId: String? = null,
) : Exception(message)

/** `Retry-After` as delta seconds; HTTP dates and malformed values are ignored. */
internal fun retryAfterSeconds(value: String?): Long? = value?.trim()?.takeIf { it.length in 1..7 && it.all { c -> c in '0'..'9' } }?.toLong()

/** How a followed event stream ended without a network failure. */
enum class CloudStreamEnd { ENDED, DENIED }
/** A gateway MCP response relayed as is: HTTP status and JSON body (empty for an accepted notification). */
class McpRelayResponse(val status: Int, val body: ByteArray)
