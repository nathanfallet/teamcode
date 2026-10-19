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
    ).also { it.migrate() } else null
    val providerHttp = HttpClient(CIO) { followRedirects = false; install(HttpTimeout) { connectTimeoutMillis = 10_000; requestTimeoutMillis = 30_000 } }
    val cipher = database?.let { CredentialCipher(Base64.getDecoder().decode(requireNotNull(System.getenv("TEAMCODE_ENCRYPTION_KEY")) { "Set TEAMCODE_ENCRYPTION_KEY to a base64-encoded 32-byte key." })) }
    val accountStore = database?.let { SqlAccountStore(it, cipher!!) }
    val accounts = accountStore?.let { AccountManagement(it, ClaudeAccountVerifier(providerHttp), mapOf("codex" to CodexAccountVerifier(providerHttp))) }
    val admission = database?.let { SqlAdmissionStore(it) }
    val modelPolicies = database?.let { SqlModelPolicyStore(it) }
    val routes = database?.let { me.nathanfallet.teamcode.infrastructure.database.SqlRoutingStore(it) }
    val directives = database?.let { SqlDirectiveService(it) }
    val transports = listOf(ClaudeTransport(providerHttp), CodexTransport(providerHttp))
    val credentialSource = database?.let { SqlCredentialSource(it, cipher!!) { account, token -> transports.single { it.provider == account.identity.provider }.refresh(token) } }
    val cancellationStore = database?.let { SqlRequestCancellationStore(it) }
    val cancellation = cancellationStore?.let { RequestCancellationService(it) }
    val gateway = if (database != null) CancellableGateway(PoolGateway(admission!!, credentialSource!!, transports, modelPolicies!!, routes, queued = me.nathanfallet.teamcode.domain.capacity.AwaitAdmissionUseCase(admission)), cancellation!!) else FixtureGatewayService()
    val workers = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    if (accountStore != null) {
        val work = SqlScheduledWork(database!!)
        val monitor = QuotaMonitor(accountStore, credentialSource!!, admission!!, transports, work)
        workers.launch {
            while (isActive) {
                try {
                    work.runDue("maintenance") { admission.maintain(); cancellationStore!!.maintain(); directives!!.update() }
                    monitor.sample()
                }
                catch (_: TimeoutCancellationException) { System.err.println("TeamCode maintenance deadline exceeded.") }
                catch (error: CancellationException) { throw error }
                catch (_: Exception) { System.err.println("TeamCode quota sampling is temporarily unavailable.") }
                delay(5_000)
            }
        }
    }
    val cloud = System.getenv("TEAMCODE_CLOUD_RUNNER_IMAGE")?.takeIf { it.isNotBlank() && database != null }
        ?.let { image -> CloudServer(database!!, cipher!!, origin, image).also { it.launch(workers) } }
    val team = database?.let { SqlTeamService(it) }
    val devices = database?.let { SqlDeviceService(it) }
    val serviceKeys = database?.let { me.nathanfallet.teamcode.infrastructure.database.SqlServiceKeyService(it) }
    val audit = database?.let { me.nathanfallet.teamcode.infrastructure.database.SqlAuditService(it) }
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
    }) }
    try {
        embeddedServer(Netty, configure = {
            connectors.add(EngineConnectorBuilder().also { it.host = host; it.port = port })
            shutdownGracePeriod = shutdownGrace; shutdownTimeout = 620_000
        }) { configureGateway(koin.koin.get(), token, team, origin, accounts, admission, directives, modelPolicies, readiness = { database?.ready() ?: true }, routes = routes, cancellation = cancellation, devices = devices, serviceKeys = serviceKeys, audit = audit,
            cloudSessions = cloud?.sessions, cloudAdmin = cloud?.admin, cloudRunner = cloud?.runner, github = cloud?.github) }.start(wait = true)
    } finally { runBlocking { workers.coroutineContext[Job]?.cancelAndJoin() }; koin.close(); cloud?.close(); providerHttp.close(); database?.close() }
}
