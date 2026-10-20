package me.nathanfallet.teamcode

import io.ktor.http.*
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.*
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.serialization.json.*
import me.nathanfallet.teamcode.domain.accounts.*
import me.nathanfallet.teamcode.domain.capacity.*
import me.nathanfallet.teamcode.domain.gateway.*
import me.nathanfallet.teamcode.domain.team.*
import me.nathanfallet.teamcode.infrastructure.database.*
import me.nathanfallet.teamcode.infrastructure.secrets.CredentialCipher
import me.nathanfallet.teamcode.presentation.ForecastLivePolicy
import me.nathanfallet.teamcode.presentation.configureGateway
import org.testcontainers.mysql.MySQLContainer

/** Owned local fixtures only: no provider transports, quota sampler or operator database are constructed. */
internal data class ForecastLiveFixture(val originA: String, val originB: String, val session: LoginSession, val accountId: String,
    val loads: AtomicInteger, val teamA: SqlTeamService, val teamB: SqlTeamService, val admissionB: SqlAdmissionStore, val databaseB: Database) {
    suspend fun update(utilization: Double) {
        val at = ForecastServiceImpl(databaseB).load(session.principal, accountId).generatedAtMillis
        admissionB.observe(accountId, listOf(AccountQuota(accountId, "five_hour", utilization, at, at + 3_600_000)))
    }
}

/** Separate connection pools and actual Netty listeners exercise the same routes used by the packaged service. */
internal suspend fun <T> withForecastLiveFixture(policy: ForecastLivePolicy = ForecastLivePolicy(100, 5000), run: suspend (ForecastLiveFixture) -> T): T {
    MySQLContainer("mysql:8.4").withDatabaseName("teamcode").withTmpFs(mapOf("/var/lib/mysql" to "rw,nosuid,size=1g")).use { container ->
        container.start()
        Database(container.jdbcUrl, container.username, container.password).use { a ->
            Database(container.jdbcUrl, container.username, container.password).use { b ->
                a.migrate(); b.migrate()
                val teamA = SqlTeamService(a)
                val teamB = SqlTeamService(b)
                val temporary = requireNotNull(teamA.bootstrap())
                val initial = teamA.login("admin", temporary, "live forecast fixture")
                teamA.changePassword(initial.principal, temporary, "live forecast replacement")
                val session = teamA.login("admin", "live forecast replacement", "live forecast fixture")
                val accounts = SqlAccountStore(a, CredentialCipher(ByteArray(32) { 42 }))
                val enrolled = accounts.upsert(AccountMutationActor.System,
                    VerifiedProviderAccount(AccountIdentity("default", "anthropic", "live-fixture", "fixture-org"), "Synthetic live fixture <img src=x onerror=globalThis.forecastInjected=true>"),
                    ProviderCredentials("synthetic-access", "synthetic-refresh", Long.MAX_VALUE), null, "live-forecast-enrollment") as EnrollmentResult.Created
                accounts.upsert(AccountMutationActor.System,
                    VerifiedProviderAccount(AccountIdentity("default", "anthropic", "live-fixture-other", "fixture-org"), "Other synthetic account"),
                    ProviderCredentials("synthetic-other", "synthetic-other-refresh", Long.MAX_VALUE), null, "live-forecast-other")
                val admission = SqlAdmissionStore(b)
                val at = ForecastServiceImpl(b).load(session.principal, enrolled.account.id).generatedAtMillis
                admission.observe(enrolled.account.id, listOf(AccountQuota(enrolled.account.id, "five_hour", .31, at, at + 3_600_000)))
                val count = AtomicInteger()
                val delegate = ForecastServiceImpl(a)
                val counted = object : ForecastService {
                    override suspend fun load(actor: Principal, accountId: String?): ForecastSnapshot {
                        count.incrementAndGet(); return delegate.load(actor, accountId)
                    }
                }
                val gateway = object : GatewayService {
                    override suspend fun status() = GatewaySnapshot("synthetic-live-forecast", 0, 0)
                    override suspend fun forward(request: GenerationRequest, receive: suspend (GenerationHead, kotlinx.coroutines.flow.Flow<ByteArray>) -> Unit) = error("Inference forbidden in forecast acceptance")
                }
                var fixture: ForecastLiveFixture? = null
                val first = embeddedServer(Netty, host = "127.0.0.1", port = 0) { configureGateway(gateway, team = teamA, forecasts = counted, forecastLivePolicy = policy) }
                val second = embeddedServer(Netty, host = "127.0.0.1", port = 0) {
                    configureGateway(gateway, team = teamB, forecasts = ForecastServiceImpl(b), forecastLivePolicy = policy)
                    // These control routes exist only in this unshipped test fixture; normal shared login auth still applies.
                    routing {
                        post("/fixture/quota") {
                            val used = Json.parseToJsonElement(call.receiveText()).jsonObject.getValue("utilization").jsonPrimitive.double
                            require(used.isFinite() && used in 0.0..1.0)
                            requireNotNull(fixture).update(used); call.respondText("OK")
                        }
                        post("/fixture/revoke") { teamB.logout(session.principal); call.respondText("OK") }
                        get("/fixture/loads") { call.respondText(count.get().toString()) }
                    }
                }
                first.start(wait = false)
                try {
                    second.start(wait = false)
                    fixture = ForecastLiveFixture("http://127.0.0.1:${first.engine.resolvedConnectors().single().port}",
                        "http://127.0.0.1:${second.engine.resolvedConnectors().single().port}", session, enrolled.account.id, count, teamA, teamB, admission, b)
                    return run(fixture)
                } finally { first.stop(0, 5_000); second.stop(0, 5_000) }
            }
        }
    }
}
