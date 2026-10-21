package me.nathanfallet.teamcode.infrastructure.database

import kotlinx.coroutines.runBlocking
import me.nathanfallet.teamcode.domain.accounts.*
import me.nathanfallet.teamcode.domain.capacity.*
import me.nathanfallet.teamcode.domain.team.*
import me.nathanfallet.teamcode.infrastructure.secrets.CredentialCipher
import org.junit.jupiter.api.Timeout
import org.testcontainers.mysql.MySQLContainer
import kotlin.test.*

class QuotaPressureSettingPersistenceTest {
    @Test @Timeout(120)
    fun theSharedThresholdDrivesExclusionExhaustionAndForecastsAndIsAudited() = runBlocking {
        MySQLContainer("mysql:8.4").withDatabaseName("teamcode")
            .withTmpFs(mapOf("/var/lib/mysql" to "rw,nosuid,size=1g")).use { container ->
            container.start()
            Database(container.jdbcUrl, container.username, container.password).use { db ->
                db.migrate()
                val team = SqlTeamService(db)
                val initial = team.bootstrap()!!
                team.changePassword(team.login("admin", initial, "threshold-test").principal, initial, "threshold replacement password")
                val actor = team.login("admin", "threshold replacement password", "threshold-test").principal
                val accounts = SqlAccountStore(db, CredentialCipher(ByteArray(32) { 28 }))
                val enrolled = accounts.upsert(AccountMutationActor.System,
                    VerifiedProviderAccount(AccountIdentity("default", "anthropic", "synthetic-account", "synthetic-org"), "Fixture"),
                    ProviderCredentials("synthetic-access", "synthetic-refresh", Long.MAX_VALUE), null, "threshold-fixture") as EnrollmentResult.Created
                val account = accounts.handover(AccountMutationActor.System, "default", enrolled.account.id, accessOnly = true)
                val admission = SqlAdmissionStore(db)
                val settings = SqlPacingSettingsStore(db)
                val model = "claude-opus-test"
                val now = db.query { nowMillis() }
                admission.observe(account.id, listOf(AccountQuota(account.id, "five_hour", .85, now, now + 3_600_000)))

                assertEquals(95, settings.get(actor).quotaPressurePercent, "Existing installations keep the 95% default")
                assertEquals(.95, ForecastServiceImpl(db).load(actor).pressureThreshold)
                assertFalse(admission.isQuotaExhausted(account.id, model))
                admission.settle(admission.reserve(actor, 100, model), 0)

                assertEquals(80, settings.save(actor, PacingSettings(quotaPressurePercent = 80)).quotaPressurePercent)
                assertEquals(80, SqlPacingSettingsStore(db).get(actor).quotaPressurePercent, "A fresh service instance reads the shared value")
                assertEquals("pool_unavailable", assertFailsWith<TeamFailure> { admission.reserve(actor, 100, model) }.code)
                assertTrue(admission.isQuotaExhausted(account.id, model))
                val snapshot = ForecastServiceImpl(db).load(actor)
                assertEquals(.8, snapshot.pressureThreshold)
                assertEquals(QuotaForecast.State.EXHAUSTED, snapshot.windows.single().forecast.state)

                settings.save(actor, PacingSettings(quotaPressurePercent = 100))
                assertFalse(admission.isQuotaExhausted(account.id, model))
                admission.settle(admission.reserve(actor, 100, model), 0)

                val actions = SqlAuditService(db).list(actor).events.map { it.action }
                assertEquals(2, actions.count { it == "pacing.quota_pressure_changed" })
                assertFalse("pacing.observation_enabled" in actions, "An unchanged mode is not re-audited")
            }
        }
    }
}
