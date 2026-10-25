package me.nathanfallet.teamcode

import io.ktor.server.engine.embeddedServer
import io.ktor.server.engine.EngineConnectorBuilder
import io.ktor.server.netty.Netty
import kotlinx.coroutines.*
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import java.util.Base64
import me.nathanfallet.teamcode.domain.accounts.AccountManagement
import me.nathanfallet.teamcode.infrastructure.database.SqlScheduledWork
import me.nathanfallet.teamcode.infrastructure.database.SqlAdmissionStore
import me.nathanfallet.teamcode.infrastructure.database.SqlDirectiveService
import me.nathanfallet.teamcode.infrastructure.database.SqlModelPolicyStore
import me.nathanfallet.teamcode.infrastructure.database.SqlCredentialSource
import me.nathanfallet.teamcode.infrastructure.database.AccountEmailBackfill
import me.nathanfallet.teamcode.infrastructure.gateway.PoolGateway
import me.nathanfallet.teamcode.infrastructure.gateway.QuotaMonitor
import me.nathanfallet.teamcode.infrastructure.providers.ClaudeTransport
import me.nathanfallet.teamcode.infrastructure.providers.CodexTransport
import me.nathanfallet.teamcode.infrastructure.providers.CodexAccountVerifier
import me.nathanfallet.teamcode.infrastructure.database.SqlAccountStore
import me.nathanfallet.teamcode.infrastructure.providers.ClaudeAccountVerifier
import me.nathanfallet.teamcode.infrastructure.secrets.CredentialCipher
import me.nathanfallet.teamcode.domain.gateway.GatewayService
import me.nathanfallet.teamcode.domain.gateway.CancellableGateway
import me.nathanfallet.teamcode.domain.gateway.RequestCancellationService
import me.nathanfallet.teamcode.infrastructure.database.SqlRequestCancellationStore
import me.nathanfallet.teamcode.domain.team.TeamService
import me.nathanfallet.teamcode.infrastructure.database.Database
import me.nathanfallet.teamcode.infrastructure.database.SqlTeamService
import me.nathanfallet.teamcode.infrastructure.database.SqlDeviceService
import me.nathanfallet.teamcode.infrastructure.fixtures.FixtureGatewayService
import me.nathanfallet.teamcode.infrastructure.network.PinnedDns
import me.nathanfallet.teamcode.presentation.configureGateway
import org.koin.dsl.koinApplication
import org.koin.dsl.module

fun main() {
    val mode = System.getenv("TEAMCODE_MODE") ?: "server"
    require(mode in setOf("server", "fixture")) { "TEAMCODE_MODE must be server or fixture." }
    val host = System.getenv("TEAMCODE_HOST") ?: "127.0.0.1"
    val port = (System.getenv("TEAMCODE_PORT") ?: "8080").toInt()
    val shutdownGrace = (System.getenv("TEAMCODE_SHUTDOWN_GRACE_MS") ?: "1000").toLong()
    require(shutdownGrace in 0..600_000) { "TEAMCODE_SHUTDOWN_GRACE_MS must be between 0 and 600000." }
    val origin = System.getenv("TEAMCODE_PUBLIC_URL") ?: "http://localhost:$port"
    val database = if (mode == "server") Database(
        requireNotNull(System.getenv("TEAMCODE_DATABASE_URL")) { "Set TEAMCODE_DATABASE_URL (a MySQL JDBC URL)." },
        requireNotNull(System.getenv("TEAMCODE_DATABASE_USER")) { "Set TEAMCODE_DATABASE_USER." },
        requireNotNull(System.getenv("TEAMCODE_DATABASE_PASSWORD")) { "Set TEAMCODE_DATABASE_PASSWORD." },
        System.getenv("TEAMCODE_DATABASE_POOL_SIZE")?.toInt() ?: Database.DEFAULT_POOL_SIZE,
    ).also { it.migrate() } else null
    val providerHttp = HttpClient(CIO) { followRedirects = false; install(HttpTimeout) { connectTimeoutMillis = 10_000; requestTimeoutMillis = 30_000 } }
    val encryptionKey = database?.let { Base64.getDecoder().decode(requireNotNull(System.getenv("TEAMCODE_ENCRYPTION_KEY")) { "Set TEAMCODE_ENCRYPTION_KEY to a base64-encoded 32-byte key." }) }
    val cipher = encryptionKey?.let { CredentialCipher(it) }
    val accountStore = database?.let { SqlAccountStore(it, cipher!!) }
    val accounts = accountStore?.let { AccountManagement(it, ClaudeAccountVerifier(providerHttp), mapOf("codex" to CodexAccountVerifier(providerHttp))) }
    val admission = database?.let { SqlAdmissionStore(it) }
    val modelPolicies = database?.let { SqlModelPolicyStore(it) }
    val routes = database?.let { me.nathanfallet.teamcode.infrastructure.database.SqlRoutingStore(it) }
    val directives = database?.let { SqlDirectiveService(it) }
    val pacingSettings = database?.let { me.nathanfallet.teamcode.infrastructure.database.SqlPacingSettingsStore(it) }
    val forecasts = database?.let { me.nathanfallet.teamcode.infrastructure.database.ForecastServiceImpl(it) }
    val transports = listOf(ClaudeTransport(providerHttp), CodexTransport(providerHttp))
    val emailBackfill = accountStore?.let { AccountEmailBackfill(transports, mapOf("anthropic" to ClaudeAccountVerifier(providerHttp), "codex" to CodexAccountVerifier(providerHttp)), it) }
    val credentialSource = database?.let { SqlCredentialSource(it, cipher!!) { account, token -> emailBackfill?.renew(account, token) ?: transports.single { it.provider == account.identity.provider }.refresh(token) } }
    val cancellationStore = database?.let { SqlRequestCancellationStore(it) }
    val cancellation = cancellationStore?.let { RequestCancellationService(it) }
    val mcpAccess = database?.let { me.nathanfallet.teamcode.infrastructure.database.SqlMcpAccessStore(it) }
    val gateway = if (database != null) CancellableGateway(PoolGateway(admission!!, credentialSource!!, transports, modelPolicies!!, routes, queued = me.nathanfallet.teamcode.domain.capacity.AwaitAdmissionUseCase(admission)), cancellation!!) else FixtureGatewayService()
    // The dashboard's trusted-browser cookie is signed with a key derived from the encryption key (audit M3).
    val team = database?.let { SqlTeamService(it, browsers = me.nathanfallet.teamcode.infrastructure.secrets.TrustedBrowsers(encryptionKey!!)) }
    val trustedProxies = me.nathanfallet.teamcode.presentation.TrustedProxies.parse(System.getenv("TEAMCODE_TRUSTED_PROXIES"))
    val workers = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    if (accountStore != null) {
        val work = SqlScheduledWork(database!!)
        val monitor = QuotaMonitor(accountStore, credentialSource!!, admission!!, transports, work, emailBackfill)
        workers.launch {
            while (isActive) {
                try {
                    work.runDue("maintenance") { admission.maintain(); cancellationStore!!.maintain(); directives!!.update(); mcpAccess!!.maintain(); team!!.purgeLoginThrottle() }
                    monitor.sample()
                }
                catch (_: TimeoutCancellationException) { System.err.println("TeamCode maintenance deadline exceeded.") }
                catch (error: CancellationException) { throw error }
                catch (_: Exception) { System.err.println("TeamCode quota sampling is temporarily unavailable.") }
                delay(5_000)
            }
        }
    }
    val apiPrices = database?.let { me.nathanfallet.teamcode.infrastructure.database.SqlApiPriceStore(it) }
    val poolSizing = database?.let { me.nathanfallet.teamcode.infrastructure.database.SqlPoolSizing(it) }
    if (poolSizing != null) {
        // The automatic home pool size (ADR 0002): one owner across replicas, every ten minutes.
        val work = SqlScheduledWork(database!!)
        workers.launch {
            while (isActive) {
                try { work.runDue("pool-size", intervalMillis = me.nathanfallet.teamcode.domain.capacity.AutomaticPoolSize.REFRESH_MILLIS, timeoutMillis = 120_000) { poolSizing.refresh() } }
                catch (_: TimeoutCancellationException) { System.err.println("TeamCode pool sizing deadline exceeded.") }
                catch (error: CancellationException) { throw error }
                catch (_: Exception) { System.err.println("TeamCode pool sizing is temporarily unavailable.") }
                delay(30_000)
            }
        }
    }
    if (database != null) {
        // Runs go silent without any write: report them (once per silence) and trim the feed past its retention.
        val work = SqlScheduledWork(database)
        val stale = me.nathanfallet.teamcode.infrastructure.database.SqlRunStaleSweep(database)
        val feed = me.nathanfallet.teamcode.infrastructure.database.SqlTeamEventStore(database)
        workers.launch {
            while (isActive) {
                try { work.runDue("team-events/stale", intervalMillis = 30_000) { stale.sweep() }; work.runDue("team-events/purge", intervalMillis = 60_000) { feed.purge(1_000) } }
                catch (_: TimeoutCancellationException) { System.err.println("TeamCode event feed maintenance deadline exceeded.") }
                catch (error: CancellationException) { throw error }
                catch (_: Exception) { System.err.println("TeamCode event feed maintenance is temporarily unavailable.") }
                delay(10_000)
            }
        }
    }
    val webhookStore = database?.let { me.nathanfallet.teamcode.infrastructure.database.SqlWebhookStore(it) }
    val webhookSealer = cipher?.let { me.nathanfallet.teamcode.infrastructure.secrets.CipherSecretSealer(it) }
    // Webhooks reach public addresses only, unless the operator allows private targets (audit L1); every client below
    // dials the address its policy checked (audit L2).
    val webhookPrivateTargets = webhookPrivateTargets(System.getenv("TEAMCODE_WEBHOOK_PRIVATE_TARGETS"))
    val webhooks = if (webhookStore != null && webhookSealer != null) me.nathanfallet.teamcode.domain.webhooks.WebhookService(webhookStore, webhookSealer, allowPrivateTargets = webhookPrivateTargets) else null
    val webhookSender = if (webhookStore != null && webhookSealer != null) me.nathanfallet.teamcode.infrastructure.webhooks.HttpWebhookSender(allowPrivateTargets = webhookPrivateTargets) else null
    if (webhookStore != null && webhookSealer != null && webhookSender != null) {
        val work = SqlScheduledWork(database!!)
        val delivery = me.nathanfallet.teamcode.domain.webhooks.DeliverWebhooksUseCase(webhookStore, webhookSealer, webhookSender)
        workers.launch {
            while (isActive) {
                try {
                    work.runDue("webhooks/deliver", intervalMillis = 1_000, timeoutMillis = 40_000) { delivery.run() }
                    work.runDue("webhooks/maintain", intervalMillis = 60_000) { webhookStore.maintain(500) }
                }
                catch (_: TimeoutCancellationException) { System.err.println("TeamCode webhook delivery deadline exceeded.") }
                catch (error: CancellationException) { throw error }
                catch (_: Exception) { System.err.println("TeamCode webhook delivery is temporarily unavailable.") }
                delay(2_000)
            }
        }
    }
    // Artifacts: the bucket and its settings live in the database, configured on the dashboard; no deployment variable.
    val artifactHttp = HttpClient(CIO) {
        followRedirects = false; expectSuccess = false
        engine { dnsResolver = PinnedDns.resolver(me.nathanfallet.teamcode.domain.cloud.CloudAdministrationService::allowedResolvedAddress) }
        install(HttpTimeout) { connectTimeoutMillis = 10_000; requestTimeoutMillis = 60_000; socketTimeoutMillis = 30_000 }
    }
    val artifactObjects = me.nathanfallet.teamcode.infrastructure.artifacts.S3ObjectStore(artifactHttp)
    // Cloud sessions are configured on the dashboard; the former variables only seed an empty database. Files sent to
    // sessions use the artifact bucket.
    // The session pods' network policy is the operator's: deployment variables only, read when cloud sessions can run
    // (with a database); an invalid value stops the start.
    val cloud = database?.let { CloudServer(it, cipher!!, origin, artifactObjects,
        me.nathanfallet.teamcode.infrastructure.cloud.SessionNetworkPolicy.fromEnvironment(System::getenv)) }?.also {
        // The seed is a convenience: a database error here is reported and the stored runtime is reread later.
        try { runBlocking { it.start(initialCloudRuntime()) } }
        catch (error: CancellationException) { throw error }
        catch (error: Exception) { System.err.println("TeamCode could not seed the cloud runtime from the environment (${error::class.simpleName}).") }
        it.launch(workers)
    }
    val runs = database?.let { me.nathanfallet.teamcode.infrastructure.database.AgentRunServiceImpl(it) }
    val reconciliation = database?.let { me.nathanfallet.teamcode.infrastructure.database.UsageReconciliationServiceImpl(it) }
    val budgetOverrides = database?.let { me.nathanfallet.teamcode.infrastructure.database.BudgetOverrideServiceImpl(it) }
    val groups = database?.let { me.nathanfallet.teamcode.infrastructure.database.GroupServiceImpl(it) }
    val devices = database?.let { SqlDeviceService(it) }
    val serviceKeys = database?.let { me.nathanfallet.teamcode.infrastructure.database.SqlServiceKeyService(it) }
    val audit = database?.let { me.nathanfallet.teamcode.infrastructure.database.SqlAuditService(it) }
    val skills = database?.let { me.nathanfallet.teamcode.domain.skills.SkillCatalog(me.nathanfallet.teamcode.infrastructure.database.SqlSkillStore(it)) }
    val hooks = database?.let { me.nathanfallet.teamcode.domain.hooks.HookCatalog(me.nathanfallet.teamcode.infrastructure.database.SqlHookStore(it)) }
    // No MCP host is wired yet, so declaring a hosted MCP command (transport stdio) answers 409 mcp_disabled.
    val variants = database?.let { me.nathanfallet.teamcode.infrastructure.database.SqlVariantService(it, cipher!!, stdioLaunchers = emptySet()) }
    /** A member's own environment variables, for `teamcode run`: resolved from the variants on every read. */
    val environment = variants?.let { me.nathanfallet.teamcode.domain.variants.ResolveOwnEnvironmentUseCase(
        me.nathanfallet.teamcode.domain.cloud.ResolveCloudVariablesUseCase(me.nathanfallet.teamcode.domain.variants.ResolveEffectiveVariantsUseCase(it))) }
    val mcpLimits = mcpLimits()
    val mcpHttp = HttpClient(CIO) {
        followRedirects = false
        engine { dnsResolver = PinnedDns.resolver(me.nathanfallet.teamcode.domain.cloud.CloudAdministrationService::publicAddress) }
        install(HttpTimeout) { connectTimeoutMillis = 10_000; requestTimeoutMillis = maxOf(mcpLimits.callTimeoutMillis, mcpLimits.listTimeoutMillis) + 5_000 }
    }
    val mcp = mcpAccess?.let {
        me.nathanfallet.teamcode.domain.mcp.McpGateway(me.nathanfallet.teamcode.domain.variants.ResolveEffectiveVariantsUseCase(variants!!), it,
            me.nathanfallet.teamcode.infrastructure.mcp.HttpMcpUpstream(mcpHttp, clientVersion = me.nathanfallet.teamcode.api.BuildInfo.VERSION), mcpLimits)
    }
    val artifactAdminStore = database?.let { me.nathanfallet.teamcode.infrastructure.database.SqlArtifactAdminStore(it, cipher!!) }
    val artifactAdmin = artifactAdminStore?.let { me.nathanfallet.teamcode.domain.artifacts.ArtifactAdministrationService(it, artifactObjects) }
    val artifactStore = database?.let { me.nathanfallet.teamcode.infrastructure.database.SqlArtifactStore(it) }
    val artifacts = artifactStore?.let { me.nathanfallet.teamcode.domain.artifacts.ArtifactService(it, artifactAdminStore!!, artifactObjects) }
    // View tickets are signed with a key derived from the credential key (HKDF, label artifact-view), never with it.
    val artifactViewing = artifacts?.let { service -> me.nathanfallet.teamcode.domain.artifacts.ArtifactViewing(service, artifactStore!!, artifactAdminStore!!, artifactObjects,
        me.nathanfallet.teamcode.domain.artifacts.ArtifactTickets(Base64.getDecoder().decode(System.getenv("TEAMCODE_ENCRYPTION_KEY")!!))) }
    if (artifactStore != null) {
        // Drafts, retention, expired uploads, unused blobs and the proven removal of deleted folders: one replica every ten minutes.
        val work = SqlScheduledWork(database!!)
        val maintenance = me.nathanfallet.teamcode.domain.artifacts.ArtifactMaintenance(artifactStore, artifactAdminStore!!, artifactObjects)
        workers.launch {
            while (isActive) {
                try { work.runDue("artifacts/maintain", intervalMillis = 600_000, timeoutMillis = 240_000) { maintenance.run() } }
                catch (_: TimeoutCancellationException) { System.err.println("TeamCode artifact maintenance deadline exceeded.") }
                catch (error: CancellationException) { throw error }
                catch (_: Exception) { System.err.println("TeamCode artifact maintenance is temporarily unavailable.") }
                delay(60_000)
            }
        }
    }
    val initialPassword = runBlocking { team?.bootstrap() }
    if (initialPassword != null) {
        // The sole logging exception: explicitly requested, only after first-admin creation commits.
        System.err.println("TeamCode first-run administrator: admin")
        System.err.println("TeamCode temporary administrator password: $initialPassword")
        System.err.println("Sign in at $origin/dashboard and change this password. It will not be printed again.")
    }
    val token = if (mode == "fixture") requireNotNull(System.getenv("TEAMCODE_ACCESS_TOKEN")) { "Set TEAMCODE_ACCESS_TOKEN for fixture mode." } else ""
    val koin = koinApplication { modules(module {
        single<GatewayService> { gateway }
        if (team != null) single<TeamService> { team }
        if (runs != null) single<me.nathanfallet.teamcode.domain.team.AgentRunService> { runs }
        if (reconciliation != null) single<me.nathanfallet.teamcode.domain.capacity.UsageReconciliationService> { reconciliation }
        if (budgetOverrides != null) single<me.nathanfallet.teamcode.domain.capacity.BudgetOverrideService> { budgetOverrides }
        if (groups != null) single<me.nathanfallet.teamcode.domain.team.GroupService> { groups }
    }) }
    try {
        embeddedServer(Netty, configure = {
            connectors.add(EngineConnectorBuilder().also { it.host = host; it.port = port })
            shutdownGracePeriod = shutdownGrace; shutdownTimeout = 620_000
        }) { configureGateway(koin.koin.get(), token, team, origin, accounts, admission, directives, modelPolicies, readiness = { database?.ready() ?: true }, routes = routes, cancellation = cancellation, devices = devices, serviceKeys = serviceKeys, audit = audit, pacingSettings = pacingSettings, forecasts = forecasts, groups = if (groups != null) koin.koin.get() else null, runs = if (runs != null) koin.koin.get() else null, budgetOverrides = if (budgetOverrides != null) koin.koin.get() else null, reconciliation = if (reconciliation != null) koin.koin.get() else null, variants = variants, environment = environment, skills = skills, hooks = hooks, mcp = mcp, mcpAccess = mcpAccess,
            cloudRuntime = cloud?.runtime, cloudSessions = cloud?.sessions, cloudAdmin = cloud?.admin, cloudRunner = cloud?.runner, github = cloud?.github, cloudConversation = cloud?.conversation, cloudTransfers = cloud?.transfers, webhooks = webhooks,
            apiPrices = apiPrices, poolSizing = poolSizing, artifactAdmin = artifactAdmin, artifacts = artifacts, artifactViewing = artifactViewing, deviceDaemons = cloud?.daemons, trustedProxies = trustedProxies) }.start(wait = true)
    } finally { runBlocking { workers.coroutineContext[Job]?.cancelAndJoin() }; koin.close(); cloud?.close(); providerHttp.close(); mcpHttp.close(); webhookSender?.close(); artifactHttp.close(); database?.close() }
}

/**
 * The runtime the former `TEAMCODE_CLOUD_*` deployment variables described, used once to seed a database that stores
 * none; null when `TEAMCODE_CLOUD_RUNNER_IMAGE` is unset. Afterwards the dashboard is the only place to change it.
 */
private fun initialCloudRuntime(): me.nathanfallet.teamcode.domain.cloud.CloudRuntime? {
    fun value(name: String) = System.getenv(name)?.trim()?.takeIf { it.isNotEmpty() }
    val image = value("TEAMCODE_CLOUD_RUNNER_IMAGE") ?: return null
    return me.nathanfallet.teamcode.domain.cloud.CloudRuntime(enabled = true, runnerImage = image,
        runnerBinary = value("TEAMCODE_CLOUD_RUNNER_BINARY") ?: me.nathanfallet.teamcode.domain.cloud.CloudRuntime.DEFAULT_RUNNER_BINARY,
        serverUrl = value("TEAMCODE_CLOUD_SERVER_URL"),
        workspaceAccessMode = value("TEAMCODE_CLOUD_WORKSPACE_ACCESS_MODE") ?: me.nathanfallet.teamcode.domain.cloud.CloudRuntime.DEFAULT_ACCESS_MODE)
}

/** `TEAMCODE_WEBHOOK_PRIVATE_TARGETS`: `true` lets webhooks reach loopback and private addresses; unset or `false` does not. Anything else stops the start. */
internal fun webhookPrivateTargets(value: String?): Boolean = when (value?.trim()?.lowercase()) {
    null, "", "false" -> false
    "true" -> true
    else -> throw IllegalArgumentException("TEAMCODE_WEBHOOK_PRIVATE_TARGETS must be true or false.")
}

/** Gateway bounds from `TEAMCODE_MCP_*` variables; unset values keep the defaults documented in docs/mcp-gateway.md. */
private fun mcpLimits(): me.nathanfallet.teamcode.domain.mcp.McpLimits {
    val defaults = me.nathanfallet.teamcode.domain.mcp.McpLimits()
    fun long(name: String, default: Long) = System.getenv(name)?.let { requireNotNull(it.toLongOrNull()) { "$name must be an integer." } } ?: default
    fun int(name: String, default: Int) = long(name, default.toLong()).let { require(it in 1..Int.MAX_VALUE) { "$name is out of range." }; it.toInt() }
    return try {
        me.nathanfallet.teamcode.domain.mcp.McpLimits(
            callTimeoutMillis = long("TEAMCODE_MCP_CALL_TIMEOUT_MS", defaults.callTimeoutMillis),
            listTimeoutMillis = long("TEAMCODE_MCP_LIST_TIMEOUT_MS", defaults.listTimeoutMillis),
            maxResponseBytes = int("TEAMCODE_MCP_MAX_RESPONSE_BYTES", defaults.maxResponseBytes),
            maxRequestBytes = int("TEAMCODE_MCP_MAX_REQUEST_BYTES", defaults.maxRequestBytes),
            memberConcurrentPerServer = int("TEAMCODE_MCP_MEMBER_CONCURRENCY", defaults.memberConcurrentPerServer),
            serverConcurrent = int("TEAMCODE_MCP_SERVER_CONCURRENCY", defaults.serverConcurrent),
            memberCallsPerMinute = int("TEAMCODE_MCP_MEMBER_CALLS_PER_MINUTE", defaults.memberCallsPerMinute),
            serverCallsPerMinute = int("TEAMCODE_MCP_SERVER_CALLS_PER_MINUTE", defaults.serverCallsPerMinute),
        )
    } catch (error: IllegalArgumentException) {
        throw IllegalArgumentException("Invalid TEAMCODE_MCP_* limits: ${error.message ?: "check the documented ranges."}", error)
    }
}
