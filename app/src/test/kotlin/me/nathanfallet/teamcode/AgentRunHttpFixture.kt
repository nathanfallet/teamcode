package me.nathanfallet.teamcode

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.engine.*
import io.ktor.server.netty.Netty
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.serialization.json.*
import me.nathanfallet.teamcode.domain.accounts.*
import me.nathanfallet.teamcode.domain.capacity.*
import me.nathanfallet.teamcode.domain.gateway.WireProtocol
import me.nathanfallet.teamcode.domain.team.*
import me.nathanfallet.teamcode.infrastructure.database.*
import me.nathanfallet.teamcode.infrastructure.fixtures.FixtureGatewayService
import me.nathanfallet.teamcode.infrastructure.gateway.PoolGateway
import me.nathanfallet.teamcode.infrastructure.providers.ProviderTransport
import me.nathanfallet.teamcode.infrastructure.secrets.CredentialCipher
import me.nathanfallet.teamcode.presentation.configureGateway
import org.testcontainers.mysql.MySQLContainer

/** Owned MySQL, two real gateway sockets and a loopback-only synthetic provider. */
internal class AgentRunHttpFixture(private val reportUsage: Boolean = true) : AutoCloseable {
    private val container = MySQLContainer("mysql:8.4").withDatabaseName("teamcode").withTmpFs(mapOf("/var/lib/mysql" to "rw,nosuid,size=1g"))
    lateinit var a: Database; private set
    lateinit var b: Database; private set
    lateinit var admin: LoginSession; private set
    lateinit var member: LoginSession; private set
    lateinit var origins: List<String>; private set
    val dispatches = AtomicInteger()
    private val servers = mutableListOf<EmbeddedServer<*, *>>()
    private val providerHttp = HttpClient(CIO) { install(HttpTimeout) { requestTimeoutMillis = 5000 }; followRedirects = false }

    suspend fun start() {
        container.start(); a = Database(container.jdbcUrl, container.username, container.password); b = Database(container.jdbcUrl, container.username, container.password)
        a.migrate(); b.migrate()
        val team = SqlTeamService(a); val temporary = requireNotNull(team.bootstrap())
        val initial = team.login("admin", temporary, "run-http-fixture")
        team.changePassword(initial.principal, temporary, "run HTTP owner replacement")
        admin = team.login("admin", "run HTTP owner replacement", "run-http-fixture")
        val created = team.createMember(admin.principal, "developer", Role.MEMBER)
        val pending = team.login("developer", created.temporaryPassword, "run-http-fixture")
        team.changePassword(pending.principal, created.temporaryPassword, "run HTTP member replacement")
        member = team.login("developer", "run HTTP member replacement", "run-http-fixture")
        val upstream = embeddedServer(Netty, host = "127.0.0.1", port = 0) {
            routing { post("/fixture/{provider}") {
                check(call.request.headers[HttpHeaders.Authorization] == null)
                check(call.request.headers["x-teamcode-run-id"] == null && call.request.headers["x-teamcode-workload"] == null)
                val body = Json.parseToJsonElement(call.receiveText()).jsonObject
                check(body["stream"] == JsonPrimitive(true)); dispatches.incrementAndGet()
                val kind = call.parameters["provider"]
                call.response.headers.append(if (kind == "anthropic") "request-id" else "x-request-id", "req_fixture_$kind")
                val response = if (kind == "anthropic") {
                    if (reportUsage) FixtureGatewayService.EVENTS.joinToString("") else "event: message_stop\ndata: {\"type\":\"message_stop\"}\n\n"
                } else RESPONSES
                call.respondText(response, ContentType.Text.EventStream)
            } }
        }
        servers += upstream; upstream.start(wait = false)
        val upstreamOrigin = "http://127.0.0.1:${upstream.engine.resolvedConnectors().single().port}"
        val cipher = CredentialCipher(ByteArray(32) { 51 })
        val store = SqlAccountStore(a, cipher)
        val transports = listOf("anthropic" to WireProtocol.MESSAGES, "codex" to WireProtocol.RESPONSES).map { (kind, wire) ->
            val enrolled = store.upsert(AccountMutationActor.System, VerifiedProviderAccount(AccountIdentity("default", kind, "synthetic-$kind", "synthetic-org"), "Fixture"),
                ProviderCredentials("synthetic-access-$kind", "synthetic-refresh", Long.MAX_VALUE), null, "run-http-$kind") as EnrollmentResult.Created
            store.handover(AccountMutationActor.System, "default", enrolled.account.id, accessOnly = true)
            object : ProviderTransport {
                override val provider = kind
                override val protocol = wire
                override suspend fun refresh(previous: ProviderCredentials): ProviderCredentials = error("No refresh allowed")
                override suspend fun usage(account: AccountRecord, credentials: ProviderCredentials): List<AccountQuota> = error("No usage probe allowed")
                override fun quotas(accountId: String, headers: Headers, now: Long): List<AccountQuota> = emptyList()
                override suspend fun send(account: AccountRecord, credentials: ProviderCredentials, body: JsonObject, headers: Map<String, String>, receive: suspend (HttpResponse) -> Unit) {
                    check(credentials.accessToken == "synthetic-access-$kind")
                    check("x-teamcode-run-id" !in headers && "x-teamcode-workload" !in headers)
                    providerHttp.preparePost("$upstreamOrigin/fixture/$kind") { contentType(ContentType.Application.Json); setBody(body.toString()) }.execute(receive)
                }
            }
        }
        origins = listOf(a, b).map { database ->
            val admission = SqlAdmissionStore(database)
            val gateway = PoolGateway(admission, SqlCredentialSource(database, cipher) { _, _ -> error("No refresh allowed") }, transports,
                SqlModelPolicyStore(database), SqlRoutingStore(database), queued = AwaitAdmissionUseCase(admission))
            val server = embeddedServer(Netty, host = "127.0.0.1", port = 0) {
                configureGateway(gateway, team = SqlTeamService(database), admission = admission, runs = AgentRunServiceImpl(database),
                    directives = SqlDirectiveService(database), pacingSettings = SqlPacingSettingsStore(database), reconciliation = UsageReconciliationServiceImpl(database))
            }
            servers += server; server.start(wait = false)
            "http://127.0.0.1:${server.engine.resolvedConnectors().single().port}"
        }
    }
    override fun close() {
        try { servers.asReversed().forEach { it.stop(0, 5000) } }
        finally {
            providerHttp.close()
            try { if (::b.isInitialized) b.close() } finally { try { if (::a.isInitialized) a.close() } finally { container.close() } }
        }
    }
    companion object {
        private const val RESPONSES = "event: response.completed\ndata: {\"type\":\"response.completed\",\"response\":{\"id\":\"response_fixture\",\"status\":\"completed\",\"output\":[],\"usage\":{\"input_tokens\":1,\"output_tokens\":1}}}\n\n"
    }
}
