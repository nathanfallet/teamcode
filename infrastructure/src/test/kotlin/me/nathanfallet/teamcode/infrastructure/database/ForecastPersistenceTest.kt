package me.nathanfallet.teamcode.infrastructure.database

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import me.nathanfallet.teamcode.domain.accounts.*
import me.nathanfallet.teamcode.domain.capacity.*
import me.nathanfallet.teamcode.domain.team.*
import me.nathanfallet.teamcode.infrastructure.database.tables.Teams
import me.nathanfallet.teamcode.infrastructure.database.tables.QuotaHistory
import me.nathanfallet.teamcode.infrastructure.database.tables.QuotaWindows
import me.nathanfallet.teamcode.infrastructure.secrets.CredentialCipher
import org.jetbrains.exposed.v1.jdbc.*
import org.jetbrains.exposed.v1.core.eq
import org.junit.jupiter.api.Timeout
import org.testcontainers.mysql.MySQLContainer
import kotlin.test.*

class ForecastPersistenceTest {
    @Test @Timeout(120)
    fun boundedAdminSnapshotsShareCurrentHistoriesWithoutRetiredCapsOrCredentialAccess() = runBlocking {
        MySQLContainer("mysql:8.4").withDatabaseName("teamcode").withTmpFs(mapOf("/var/lib/mysql" to "rw,nosuid,size=1g")).use { container ->
            container.start()
            Database(container.jdbcUrl, container.username, container.password).use { a ->
                Database(container.jdbcUrl, container.username, container.password).use { b ->
                    a.migrate(); b.migrate()
                    val team = SqlTeamService(a)
                    val temporary = team.bootstrap()!!
                    val initial = team.login("admin", temporary, "forecast view")
                    team.changePassword(initial.principal, temporary, "forecast view replacement")
                    val actor = team.login("admin", "forecast view replacement", "forecast view").principal
                    val serviceA = ForecastServiceImpl(a)
                    val serviceB = ForecastServiceImpl(b)
                    assertNull(serviceA.load(actor).account)
                    // A different cipher demonstrates the read path never decrypts provider credentials.
                    val accounts = SqlAccountStore(a, CredentialCipher(ByteArray(32) { 33 }))
                    suspend fun account(subject: String, teamId: String = "default") =
                        (accounts.upsert(AccountMutationActor.System, VerifiedProviderAccount(AccountIdentity(teamId, "anthropic", subject, "org"), subject),
                            ProviderCredentials("synthetic-access", "synthetic-refresh", Long.MAX_VALUE), null, "enroll-$subject") as EnrollmentResult.Created).account
                    val selected = account("selected")
                    val empty = account("empty")
                    assertTrue(serviceB.load(actor, empty.id).windows.isEmpty())
                    assertEquals(setOf(selected.id, empty.id), serviceA.load(actor).accounts.map { it.id }.toSet())
                    val now = a.query { nowMillis() }
                    fun q(window: String, age: Long, used: Double, reset: Long? = now + 3_600_000) = AccountQuota(selected.id, window, used, now - age, reset)
                    val readings = listOf("five_hour", "seven_day", "model:unknown").flatMap { window ->
                        (0..2).map { i -> q(window, 120_000 - i * 60_000L, if (window == "five_hour") .1 + i * .1 else .1, if (window == "model:unknown") null else now + 3_600_000) }
                    } + listOf(q("model:stale", 400_000, .2), q("model:gap", 700_000, .1), q("model:gap", 640_000, .2), q("model:gap", 0, .3),
                        q("model:drop", 180_000, .8), q("model:drop", 120_000, .1), q("model:drop", 60_000, .2), q("model:drop", 0, .3), q("model:retired", 0, .99))
                    SqlAdmissionStore(a).observe(selected.id, QuotaUpdate(readings, now))
                    // Retained, out-of-range history must not enter the rate or enlarge the returned series.
                    a.query {
                        QuotaHistory.batchInsert(1..4096) { minute ->
                            val at = now - (minute + 61L) * 60_000
                            this[QuotaHistory.accountId] = selected.id
                            this[QuotaHistory.windowName] = "five_hour"
                            this[QuotaHistory.utilization] = .1
                            this[QuotaHistory.observedAt] = at
                            this[QuotaHistory.resetsAt] = now + 3_600_000
                            this[QuotaHistory.sampleMinute] = at / 60_000
                        }
                    }
                    val plan = a.query {
                        val starts = QuotaWindows.selectAll().where { QuotaWindows.accountId eq selected.id }.orderBy(QuotaWindows.windowName).limit(128)
                            .associate { it[QuotaWindows.windowName] to it[QuotaWindows.windowStartedAt] }
                        explain(analyze = true) { forecastHistoryQuery(selected.id, now, starts) }.joinToString("\n")
                    }
                    println("Forecast fixture execution plan (synthetic metadata only):\n$plan")
                    val snapshot = serviceB.load(actor, selected.id)
                    assertFalse(snapshot.truncated)
                    assertEquals(selected.id, snapshot.account!!.id)
                    val byWindow = snapshot.windows.associateBy { it.quota.window }
                    assertEquals(QuotaForecast.State.EXHAUSTS_FIRST, byWindow.getValue("five_hour").forecast.state)
                    assertEquals(3, byWindow.getValue("five_hour").learningSamples)
                    assertEquals(QuotaForecast.State.IDLE, byWindow.getValue("seven_day").forecast.state)
                    assertEquals(0.0, byWindow.getValue("seven_day").forecast.pointsPerHour)
                    assertEquals(QuotaForecast.State.UNKNOWN, byWindow.getValue("model:unknown").forecast.state)
                    assertNull(byWindow.getValue("model:unknown").quota.resetsAtMillis)
                    assertEquals(QuotaForecast.State.STALE, byWindow.getValue("model:stale").forecast.state)
                    assertEquals(QuotaForecast.State.UNKNOWN, byWindow.getValue("model:gap").forecast.state)
                    assertEquals(listOf(1, 3), byWindow.getValue("model:drop").segments.map { it.size })
                    assertEquals(QuotaForecast.State.EXHAUSTED, byWindow.getValue("model:retired").forecast.state)
                    val probeAt = a.query { nowMillis() }
                    val kept = readings.filterNot { it.window == "model:retired" }
                    SqlAdmissionStore(b).observe(selected.id, QuotaUpdate(kept, probeAt, replaceModelWindows = true))
                    assertTrue(serviceA.load(actor, selected.id).windows.none { it.quota.window == "model:retired" })
                    delay(2)
                    val reintroducedAt = a.query { nowMillis() }
                    SqlAdmissionStore(a).observe(selected.id, listOf(AccountQuota(selected.id, "model:retired", .1, reintroducedAt, reintroducedAt + 3_600_000)))
                    val reintroduced = serviceB.load(actor, selected.id).windows.single { it.quota.window == "model:retired" }
                    assertEquals(1, reintroduced.learningSamples, "A reintroduced cap cannot join its retired history")
                    assertEquals(QuotaForecast.State.UNKNOWN, reintroduced.forecast.state)
                    // More windows than the response bound must be reported explicitly, never silently complete.
                    val capAt = a.query { nowMillis() }
                    SqlAdmissionStore(a).observe(empty.id, (1..130).map { AccountQuota(empty.id, "window-$it", .1, capAt, capAt + 3_600_000) })
                    val bounded = serviceB.load(actor, empty.id)
                    assertTrue(bounded.truncated); assertEquals(128, bounded.windows.size)
                    val created = team.createMember(actor, "forecast-member", Role.MEMBER)
                    val pending = team.login("forecast-member", created.temporaryPassword, "forecast view")
                    team.changePassword(pending.principal, created.temporaryPassword, "forecast member replacement")
                    val member = team.login("forecast-member", "forecast member replacement", "forecast view").principal
                    assertEquals("forbidden", assertFailsWith<TeamFailure> { serviceA.load(member) }.code)
                    assertEquals("credential_scope", assertFailsWith<TeamFailure> { serviceB.load(actor.copy(serviceScopes = ServiceScope.entries.toSet())) }.code)
                    assertEquals("invalid_account", assertFailsWith<TeamFailure> { serviceB.load(actor, "../invalid") }.code)
                    a.query { Teams.insert { it[id] = "foreign"; it[name] = "Foreign fixture" } }
                    val foreign = account("foreign-account", "foreign")
                    assertEquals("not_found", assertFailsWith<TeamFailure> { serviceA.load(actor, foreign.id) }.code)
                    assertTrue(serviceB.load(actor).accounts.none { it.id == foreign.id })
                    team.logout(actor)
                    assertEquals("authentication_required", assertFailsWith<TeamFailure> { serviceB.load(actor, selected.id) }.code)
                }
            }
        }
    }
}
