package me.nathanfallet.teamcode.infrastructure.database

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import me.nathanfallet.teamcode.domain.accounts.*
import me.nathanfallet.teamcode.domain.capacity.*
import me.nathanfallet.teamcode.domain.gateway.WireProtocol
import me.nathanfallet.teamcode.domain.routing.*
import me.nathanfallet.teamcode.domain.team.*
import me.nathanfallet.teamcode.infrastructure.database.tables.*
import me.nathanfallet.teamcode.infrastructure.gateway.SessionAffinityKey
import me.nathanfallet.teamcode.infrastructure.secrets.CredentialCipher
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*
import org.jetbrains.exposed.v1.migration.jdbc.MigrationUtils
import org.junit.jupiter.api.Timeout
import org.testcontainers.mysql.MySQLContainer
import kotlin.test.*

/** Unlimited member concurrency by default, and a SLOW_DOWN sized by the overshoot, against real MySQL and two replicas. */
class ProgressivePacingPersistenceTest {
    @Test @Timeout(180)
    fun unlimitedMembersRunInParallelUntilTheForecastJustifiesAProgressiveSlowDown() = runBlocking {
        MySQLContainer("mysql:8.4").withDatabaseName("teamcode")
            .withTmpFs(mapOf("/var/lib/mysql" to "rw,nosuid,size=1g")).use { container ->
            container.start()
            // Upgrade path: an explicit limit saved before this release must survive the new default.
            Flyway.configure().dataSource(container.jdbcUrl, container.username, container.password)
                .locations("classpath:db/migration").target("20261021103630").load().migrate()
            Database(container.jdbcUrl, container.username, container.password).use { a ->
                Database(container.jdbcUrl, container.username, container.password).use { b ->
                    val team = SqlTeamService(a)
                    val initial = team.bootstrap()!!
                    team.changePassword(team.login("admin", initial, "pacing-test").principal, initial, "pacing replacement password")
                    val actor = team.login("admin", "pacing replacement password", "pacing-test").principal
                    val colleague = team.createMember(actor, "explicit-limit", Role.MEMBER).member
                    SqlAdmissionStore(a).configure(actor, MemberPolicy(colleague.id, maxConcurrent = 2))
                    a.migrate(); b.migrate()
                    a.query {
                        assertEquals(emptyList(), MigrationUtils.statementsRequiredForDatabaseMigration(MemberPolicies, Directives, SessionDirectives, withLogs = false),
                            "The reviewed migration matches the Exposed tables")
                    }
                    val admissionA = SqlAdmissionStore(a)
                    val admissionB = SqlAdmissionStore(b)
                    assertEquals(2, admissionA.capacity(actor, colleague.id).policy.maxConcurrent, "Explicit limits are not rewritten")
                    assertEquals(UNLIMITED_CONCURRENCY, admissionB.capacity(actor).policy.maxConcurrent, "Members without a saved policy are unlimited")
                    var step = 0
                    suspend fun refusal(code: String, block: suspend () -> Unit) {
                        step++
                        val failure = try { block(); null } catch (error: TeamFailure) { error }
                        assertEquals(code, failure?.code, "Refusal $step")
                    }
                    refusal("invalid_policy") { admissionA.configure(actor, MemberPolicy(colleague.id, maxConcurrent = -1)) }
                    refusal("invalid_policy") { admissionA.configure(actor, MemberPolicy(colleague.id, maxConcurrent = MAX_MEMBER_CONCURRENCY + 1)) }
                    assertEquals(0, admissionA.configure(actor, MemberPolicy(colleague.id, maxConcurrent = 0)).maxConcurrent)
                    admissionA.configure(actor, MemberPolicy(colleague.id, maxConcurrent = 2))

                    val store = SqlAccountStore(a, CredentialCipher(ByteArray(32) { 41 }))
                    val accounts = (1..3).map { index ->
                        val identity = VerifiedProviderAccount(AccountIdentity("default", "anthropic", "synthetic-$index", "org-$index"), "Fixture $index")
                        val enrolled = store.upsert(AccountMutationActor.System, identity,
                            ProviderCredentials("synthetic-access-$index", "synthetic-refresh-$index", Long.MAX_VALUE), null, "pacing-fixture-$index") as EnrollmentResult.Created
                        store.handover(AccountMutationActor.System, "default", enrolled.account.id, accessOnly = true)
                    }
                    val controller = SqlDirectiveService(a)
                    val model = "claude-opus-test"
                    val session = "parallel-session"
                    val targets = ResolveRouteUseCase().resolve(SqlRoutingStore(a).list("default"), model, WireProtocol.MESSAGES)
                    val affinity = SessionAffinityKey.from(mapOf("session-id" to session),
                        Json.parseToJsonElement("""{"messages":[{"role":"user","content":"synthetic"}]}""").jsonObject,
                        WireProtocol.MESSAGES, model, targets)!!
                    a.query { lockTeam("default"); SqlSessionAffinity().record(this, actor, affinity, RouteTarget("anthropic", model), accounts[0].id, nowMillis()) }
                    controller.update()
                    val unknown = controller.current(actor, session)!!.advice
                    assertEquals(Triple(Pace.UNKNOWN, 6, 0L), Triple(unknown.pace, unknown.maxConcurrent, unknown.minIntervalMillis),
                        "Without a member limit, only the accounts' physical slots bound the advice")

                    // Five simultaneous requests are admitted at once: no member limit, no queue, no interval.
                    val parallel = coroutineScope {
                        (1..5).map { index -> async(Dispatchers.IO) { (if (index % 2 == 0) admissionA else admissionB).reserve(actor, 100, model, affinity = affinity) } }.awaitAll()
                    }
                    assertEquals(5, admissionA.capacity(actor).activeRequests)
                    val sixth = admissionA.reserve(actor, 100, model, affinity = affinity)
                    refusal("pool_unavailable") { admissionB.reserve(actor, 100, model, affinity = affinity) }
                    assertEquals(List(3) { 2 }, a.query {
                        accounts.map { account -> Reservations.selectAll().where { (Reservations.accountId eq account.id) and (Reservations.state eq "ACTIVE") }.count().toInt() }
                    }, "Each physical account still serves at most two requests")
                    admissionA.settle(sixth, 0)

                    // A light overshoot: at the current rate the threshold arrives at 3/4 of the time left before reset.
                    val now = a.query { nowMillis() }
                    accounts.forEach { account ->
                        admissionA.observe(account.id, QuotaUpdate(listOf(.1, .2, .3).mapIndexed { index, value ->
                            AccountQuota(account.id, "five_hour", value, now - 120_000 + index * 60_000, now + 480_000)
                        } + listOf(.1, .1, .1).mapIndexed { index, value ->
                            AccountQuota(account.id, "seven_day", value, now - 120_000 + index * 60_000, now + 86_400_000)
                        }, now))
                    }
                    controller.update()
                    val light = SqlDirectiveService(b).current(actor, session)!!.advice
                    assertEquals(Triple(Pace.SLOW_DOWN, 3, 2_000L), Triple(light.pace, light.maxConcurrent, light.minIntervalMillis),
                        "Five observed requests are halved, with a short interval")
                    assertFalse("one request" in light.message, light.message)
                    delay(2_100)
                    refusal("pacing_concurrency") { admissionB.reserve(actor, 100, model, affinity = affinity) }
                    parallel.take(3).forEach { admissionA.settle(it, 0) }
                    val paced = admissionB.reserve(actor, 100, model, affinity = affinity)
                    refusal("pacing_delay") { admissionA.reserve(actor, 100, model, affinity = affinity) }
                    controller.update()
                    assertEquals(light, controller.current(actor, session)!!.advice, "The throttle cannot shrink its own observed baseline")

                    // PAUSE stays the hard bound.
                    val pressured = a.query { nowMillis() }
                    accounts.forEach { admissionB.observe(it.id, listOf(AccountQuota(it.id, "five_hour", .9, pressured, pressured + 480_000))) }
                    controller.update()
                    val pause = controller.current(actor, session)!!.advice
                    assertEquals(Triple(Pace.PAUSE, 1, 30_000L), Triple(pause.pace, pause.maxConcurrent, pause.minIntervalMillis))
                    (parallel.drop(3) + paced).forEach { admissionB.settle(it, 100) }  // Charged work keeps its admission time for the gap.
                    delay(2_100)
                    refusal("pacing_delay") { admissionA.reserve(actor, 100, model, affinity = affinity) }
                }
            }
        }
    }
}
