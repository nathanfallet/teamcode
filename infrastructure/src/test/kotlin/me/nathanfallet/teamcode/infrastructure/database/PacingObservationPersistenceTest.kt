package me.nathanfallet.teamcode.infrastructure.database

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import me.nathanfallet.teamcode.domain.accounts.*
import me.nathanfallet.teamcode.domain.capacity.*
import me.nathanfallet.teamcode.domain.gateway.WireProtocol
import me.nathanfallet.teamcode.domain.routing.*
import me.nathanfallet.teamcode.domain.team.*
import me.nathanfallet.teamcode.infrastructure.gateway.SessionAffinityKey
import me.nathanfallet.teamcode.infrastructure.secrets.CredentialCipher
import org.junit.jupiter.api.Timeout
import org.testcontainers.mysql.MySQLContainer
import kotlin.test.*

class PacingObservationPersistenceTest {
    @Test @Timeout(120)
    fun observationAcrossReplicasPreservesForecastsAndHardLimitsWithoutDeliveringAdvice() = runBlocking {
        MySQLContainer("mysql:8.4").withDatabaseName("teamcode")
            .withTmpFs(mapOf("/var/lib/mysql" to "rw,nosuid,size=1g")).use { container ->
            container.start()
            Database(container.jdbcUrl, container.username, container.password).use { a ->
                Database(container.jdbcUrl, container.username, container.password).use { b ->
                    a.migrate(); b.migrate()
                    val team = SqlTeamService(a)
                    val initial = team.bootstrap()!!
                    val pending = team.login("admin", initial, "controller-test")
                    team.changePassword(pending.principal, initial, "controller replacement password")
                    val actor = team.login("admin", "controller replacement password", "controller-test").principal
                    val accounts = SqlAccountStore(a, CredentialCipher(ByteArray(32) { 27 }))
                    val identity = VerifiedProviderAccount(AccountIdentity("default", "anthropic", "synthetic-account", "synthetic-org"), "Fixture")
                    val enrolled = accounts.upsert(AccountMutationActor.System, identity,
                        ProviderCredentials("synthetic-access", "synthetic-refresh", Long.MAX_VALUE), null, "pacing-fixture") as EnrollmentResult.Created
                    val account = accounts.handover(AccountMutationActor.System, "default", enrolled.account.id, accessOnly = true)
                    val admissionA = SqlAdmissionStore(a)
                    val admissionB = SqlAdmissionStore(b)
                    val settingsA = SqlPacingSettingsStore(a)
                    val settingsB = SqlPacingSettingsStore(b)
                    val controllerA = SqlDirectiveService(a)
                    val controllerB = SqlDirectiveService(b)
                    val model = "claude-opus-test"
                    val session = "observation-session"
                    val targets = ResolveRouteUseCase().resolve(SqlRoutingStore(a).list("default"), model, WireProtocol.MESSAGES)
                    val affinity = SessionAffinityKey.from(mapOf("session-id" to session),
                        Json.parseToJsonElement("""{"messages":[{"role":"user","content":"synthetic"}]}""").jsonObject,
                        WireProtocol.MESSAGES, model, targets)!!
                    a.query { lockTeam("default"); SqlSessionAffinity().record(this, actor, affinity, RouteTarget("anthropic", model), account.id, nowMillis()) }
                    val now = a.query { nowMillis() }
                    admissionA.observe(account.id, QuotaUpdate(listOf("five_hour", "seven_day").flatMap { window ->
                        listOf(.1, .2, .3).mapIndexed { index, value -> AccountQuota(account.id, window, value, now - 120_000 + index * 60_000, now + 3_600_000) }
                    }, now))
                    controllerA.update()
                    assertFalse(settingsB.get(actor).observationOnly, "Existing installations retain predictive enforcement")
                    assertEquals(Pace.SLOW_DOWN, controllerB.current(actor, session)!!.advice.pace)
                    val first = admissionA.reserve(actor, 100, model, affinity = affinity)
                    suspend fun refusal(code: String, block: suspend () -> Unit) {
                        assertEquals(code, assertFailsWith<TeamFailure> { block() }.code)
                    }
                    refusal("pacing_delay") { admissionB.reserve(actor, 100, model, affinity = affinity) }

                    val created = team.createMember(actor, "pacing-member", Role.MEMBER)
                    val memberPending = team.login("pacing-member", created.temporaryPassword, "test")
                    team.changePassword(memberPending.principal, created.temporaryPassword, "member replacement password")
                    val member = team.login("pacing-member", "member replacement password", "test").principal
                    refusal("forbidden") { settingsB.save(member, PacingSettings(true)) }
                    refusal("credential_scope") { settingsB.save(actor.copy(serviceScopes = ServiceScope.entries.toSet()), PacingSettings(true)) }
                    assertFalse(settingsA.get(actor).observationOnly)

                    settingsB.save(actor, PacingSettings(true))
                    assertTrue(SqlPacingSettingsStore(a).get(actor).observationOnly, "A fresh service instance reads shared settings")
                    controllerB.update()
                    assertEquals(Pace.SLOW_DOWN, controllerA.preview(actor)!!.advice.pace, "Observation still computes forecasts")
                    assertNull(controllerA.current(actor))
                    assertNull(controllerB.current(actor, session), "Manual hint paths cannot emit observation advice")
                    assertNull(controllerA.claim(actor, session, "EMITTED_TO_HOOK"))
                    assertNull(controllerB.claim(actor, session, "RECEIVED_BY_CLI"))
                    admissionA.configure(actor, MemberPolicy(actor.member.id, maxConcurrent = 2))
                    val second = admissionB.reserve(actor, 100, model, affinity = affinity)
                    refusal("member_concurrency") { admissionA.reserve(actor, 1, model, affinity = affinity) }
                    admissionA.configure(actor, MemberPolicy(actor.member.id, maxConcurrent = 3))
                    refusal("pool_unavailable") { admissionB.reserve(actor, 1, model, affinity = affinity) }
                    admissionA.configure(actor, MemberPolicy(actor.member.id, dailyUnits = 100, maxConcurrent = 3))
                    refusal("member_allowance") { admissionB.reserve(actor, 1, model, affinity = affinity) }
                    admissionA.configure(actor, MemberPolicy(actor.member.id, maxConcurrent = 3, poolId = "protected"))
                    refusal("pool_unavailable") { admissionB.reserve(actor, 1, model, affinity = affinity) }
                    admissionA.configure(actor, MemberPolicy(actor.member.id, maxConcurrent = 3))
                    admissionA.settle(first, 0); admissionB.settle(second, 0)
                    val saturatedAt = a.query { nowMillis() }
                    admissionB.observe(account.id, listOf(AccountQuota(account.id, "five_hour", .99, saturatedAt, saturatedAt + 3_600_000)))
                    refusal("pool_unavailable") { admissionA.reserve(actor, 1, model, affinity = affinity) }
                    controllerA.update()
                    assertEquals(Pace.PAUSE, controllerB.preview(actor)!!.advice.pace)
                    assertNull(controllerB.current(actor, session))

                    settingsA.save(actor, PacingSettings())
                    assertFalse(settingsB.get(actor).observationOnly)
                    assertEquals(Pace.PAUSE, controllerB.current(actor, session)!!.advice.pace)
                    assertNotNull(controllerA.claim(actor, session, "EMITTED_TO_HOOK"), "Re-enabling resumes normal claimed delivery")
                    val freshAt = a.query { nowMillis() }
                    admissionB.observe(account.id, listOf(AccountQuota(account.id, "five_hour", .3, freshAt, freshAt + 3_600_000)))
                    val resumed = admissionA.reserve(actor, 100, model, affinity = affinity)
                    refusal("pacing_delay") { admissionB.reserve(actor, 100, model, affinity = affinity) }
                    admissionB.settle(resumed, 0)
                    val actions = SqlAuditService(b).list(actor).events.map { it.action }
                    assertTrue("pacing.observation_enabled" in actions)
                    assertTrue("pacing.enforcement_enabled" in actions)
                    team.logout(actor)
                    refusal("authentication_required") { settingsB.save(actor, PacingSettings(true)) }
                    assertFalse(a.query { readPacingSettings("default").observationOnly })
                }
            }
        }
    }
}
