package me.nathanfallet.teamcode.infrastructure.database

import kotlinx.coroutines.runBlocking
import me.nathanfallet.teamcode.domain.accounts.*
import me.nathanfallet.teamcode.domain.capacity.*
import me.nathanfallet.teamcode.domain.team.*
import me.nathanfallet.teamcode.infrastructure.database.tables.*
import me.nathanfallet.teamcode.infrastructure.secrets.CredentialCipher
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*
import org.jetbrains.exposed.v1.migration.jdbc.MigrationUtils
import org.junit.jupiter.api.Timeout
import org.testcontainers.mysql.MySQLContainer
import kotlin.math.roundToLong
import kotlin.test.*

/**
 * The automatic home pool size against real MySQL: prices are team data, the scheduled refresh stores a value next to
 * the manual budget, and admission reads it only while the setting is on. The manual budget never changes.
 */
class AutomaticPoolSizePersistenceTest {
    @Test @Timeout(120)
    fun theCheckboxMovesOnlyThePoolTotalAndTheManualBudgetIsKept() = runBlocking {
        MySQLContainer("mysql:8.4").withDatabaseName("teamcode")
            .withTmpFs(mapOf("/var/lib/mysql" to "rw,nosuid,size=1g")).use { container ->
            container.start()
            Database(container.jdbcUrl, container.username, container.password).use { db ->
                db.migrate()
                db.query {
                    assertEquals(emptyList(), MigrationUtils.statementsRequiredForDatabaseMigration(ApiPricesTable, PoolBudgets, PacingSettingsTable, withLogs = false),
                        "The reviewed migrations match the Exposed tables")
                }
                val team = SqlTeamService(db)
                val initial = assertNotNull(team.bootstrap())
                team.changePassword(team.login("admin", initial, "pool-test").principal, initial, ADMIN_PASSWORD)
                val admin = team.login("admin", ADMIN_PASSWORD, "pool-test").principal
                val created = team.createMember(admin, "developer", Role.MEMBER)
                team.changePassword(team.login("developer", created.temporaryPassword, "pool-test").principal, created.temporaryPassword, MEMBER_PASSWORD)
                val member = team.login("developer", MEMBER_PASSWORD, "pool-test").principal
                val accounts = SqlAccountStore(db, CredentialCipher(ByteArray(32) { 17 }))
                val enrolled = accounts.upsert(AccountMutationActor.System,
                    VerifiedProviderAccount(AccountIdentity("default", "anthropic", "synthetic-pool-account", "synthetic-org"), "Fixture"),
                    ProviderCredentials("synthetic-access", "synthetic-refresh", Long.MAX_VALUE), null, "pool-fixture") as EnrollmentResult.Created
                accounts.handover(AccountMutationActor.System, "default", enrolled.account.id, accessOnly = true)
                val account = enrolled.account.id
                val admission = SqlAdmissionStore(db)
                val prices = SqlApiPriceStore(db)
                val sizing = SqlPoolSizing(db)
                val settings = SqlPacingSettingsStore(db)

                val now = db.query { nowMillis() }
                val today = now / DAY
                // $3 per million input tokens for the sonnet family, from yesterday.
                val sonnet = ApiPrice("anthropic", "claude-sonnet", today - 1, 3_000_000, 3_750_000, 300_000, 15_000_000)
                assertEquals("forbidden", assertFailsWith<TeamFailure> { prices.replace(member, listOf(sonnet)) }.code)
                assertEquals(listOf(sonnet), prices.replace(admin, listOf(sonnet)))
                assertEquals(listOf(sonnet), prices.list(member), "Members read the table")
                assertEquals("invalid_api_price", assertFailsWith<TeamFailure> { prices.replace(admin, listOf(sonnet, sonnet)) }.code)
                val opus = ApiPrice("anthropic", "claude-opus", today, 15_000_000, 18_750_000, 1_500_000, 75_000_000)
                assertEquals(listOf(opus, sonnet), prices.put(admin, opus), "One row added under the team lock, the rest kept")
                assertEquals(listOf(sonnet), prices.remove(admin, "anthropic", "claude-opus", today))

                // Three hours of history at hour boundaries: each hour settles 200,000 priced input tokens ($0.60) and moves
                // the weekly window by 6 points (full window $10) and the five-hour window by 12 points (full window $5).
                val base = (now / HOUR - 4) * HOUR
                val weeklyReset = now + 3 * DAY + 12 * HOUR
                val fiveHourReset = now + 2 * HOUR
                db.query {
                    for (i in 0..3) {
                        for ((window, utilization, reset) in listOf(Triple("seven_day", .10 + .06 * i, weeklyReset), Triple("five_hour", .10 + .12 * i, fiveHourReset))) {
                            QuotaHistory.insert {
                                it[accountId] = account; it[windowName] = window; it[QuotaHistory.utilization] = utilization
                                it[observedAt] = base + i * HOUR; it[resetsAt] = reset; it[sampleMinute] = (base + i * HOUR) / 60_000
                            }
                        }
                    }
                    for ((window, utilization, reset) in listOf(Triple("seven_day", .28, weeklyReset), Triple("five_hour", .46, fiveHourReset))) {
                        QuotaWindows.insert { it[accountId] = account; it[windowName] = window; it[QuotaWindows.utilization] = utilization; it[observedAt] = now; it[resetsAt] = reset }
                    }
                    for (i in 0..2) AccountTokenUsageTable.insert {
                        it[accountId] = account; it[hourNumber] = base / HOUR + i; it[provider] = "anthropic"; it[model] = "claude-sonnet-test"
                        it[requests] = 1; it[inputTokens] = 200_000
                    }
                    TokenUsageTable.insert {
                        it[teamId] = "default"; it[dayNumber] = today; it[memberId] = member.member.id; it[provider] = "anthropic"; it[model] = "claude-sonnet-test"
                        it[requests] = 3; it[inputTokens] = 600_000
                    }
                }
                assertEquals(1_800_000, admission.tokenUsage(member, TokenUsageQuery(today, today)).rows.single().apiEquivalentMicros, "600,000 input tokens at $3 per million")

                admission.configurePool(admin, PoolBudget("", 2_000_000))
                assertEquals(2_000_000, admission.capacity(admin, member.member.id).poolDailyUnits, "Unchecked: the manual budget")
                sizing.refresh()
                val report = sizing.report(admin)
                assertFalse(report.enabled); assertFalse(report.usesAutomatic)
                val share = report.estimate.accounts.single()
                assertEquals(PoolContribution.COUNTED, share.status, "Learned windows: ${report.capacities}")
                assertEquals(mapOf("five_hour" to 5_000_000L, "seven_day" to 10_000_000L), report.capacities.getValue(account).associate { it.window to it.fullWindowMicros!! })
                // Weekly: 0.67 × $10 / 3.5 days; five-hour: $5 × 0.95 × 24 / 5. The weekly term binds.
                assertEquals((.67 * 10_000_000 / 3.5).roundToLong().toDouble(), share.dailyMicros.toDouble(), 2_000.0)
                assertEquals(1.0 / 3, report.creditsPerMicro!!, 1e-9, "600,000 credits for $1.80")
                val automatic = assertNotNull(report.automaticDailyUnits)
                assertEquals(report.estimate.dailyUnits!!.toDouble(), automatic.toDouble(), 1_000.0, "The first value is the estimate")
                assertEquals(2_000_000, admission.capacity(admin, member.member.id).poolDailyUnits, "Computed but unchecked: still the manual budget")

                settings.setAutoPoolSize(admin, true)
                assertTrue(settings.get(admin).autoPoolSize)
                val on = admission.capacity(admin, member.member.id)
                assertEquals(automatic, on.poolDailyUnits, "Checked: the automatic value")
                assertEquals(automatic * 1 / on.poolTotalWeight, on.dailyAllowanceUnits, "The member keeps their weighted share of the new total")
                assertTrue(sizing.report(admin).usesAutomatic)
                settings.update(admin, observationOnly = true, quotaPressurePercent = null)
                assertTrue(settings.get(admin).autoPoolSize, "Changing another controller setting keeps the checkbox")

                settings.setAutoPoolSize(admin, false)
                assertEquals(2_000_000, admission.capacity(admin, member.member.id).poolDailyUnits, "Unchecked again: back to the manual budget")
                assertEquals(listOf(PoolBudget("", 2_000_000)), admission.poolBudgets(admin), "The manual budget was never rewritten")

                settings.setAutoPoolSize(admin, true)
                db.query { PoolBudgets.update({ PoolBudgets.teamId eq "default" }) { it[autoComputedAt] = now - 2 * HOUR } }
                assertEquals(2_000_000, admission.capacity(admin, member.member.id).poolDailyUnits, "A value older than an hour gives way to the manual budget")

                assertEquals("forbidden", assertFailsWith<TeamFailure> { sizing.report(member) }.code)
                assertEquals("forbidden", assertFailsWith<TeamFailure> { settings.setAutoPoolSize(member, false) }.code)
                val actions = db.query { AuditEvents.select(AuditEvents.action).where { AuditEvents.teamId eq "default" }.map { it[AuditEvents.action] } }
                assertEquals(3, actions.count { it == "capacity.api_price_changed" }, "One audited event per change; the refused ones wrote nothing")
                assertEquals(2, actions.count { it == "pool_size.automatic_enabled" })
                assertEquals(1, actions.count { it == "pool_size.manual_enabled" })
            }
        }
    }

    companion object {
        private const val ADMIN_PASSWORD = "pool owner replacement password"
        private const val MEMBER_PASSWORD = "pool member replacement password"
        private const val HOUR = 3_600_000L
        private const val DAY = 86_400_000L
    }
}
