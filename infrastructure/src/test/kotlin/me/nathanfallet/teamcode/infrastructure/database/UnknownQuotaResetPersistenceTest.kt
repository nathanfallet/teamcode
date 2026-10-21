package me.nathanfallet.teamcode.infrastructure.database

import me.nathanfallet.teamcode.infrastructure.database.tables.*
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*
import kotlinx.coroutines.runBlocking
import me.nathanfallet.teamcode.domain.accounts.*
import me.nathanfallet.teamcode.domain.capacity.*
import me.nathanfallet.teamcode.domain.team.TeamFailure
import me.nathanfallet.teamcode.infrastructure.secrets.CredentialCipher
import org.flywaydb.core.Flyway
import org.testcontainers.mysql.MySQLContainer
import kotlin.test.*

class UnknownQuotaResetPersistenceTest {
    @Test fun upgradePreservesKnownQuotasAndReplicasShareUnknownResetFreshness() = runBlocking {
        MySQLContainer("mysql:8.4").withDatabaseName("teamcode")
            .withTmpFs(mapOf("/var/lib/mysql" to "rw,nosuid,size=1g")).use { container ->
            container.start()
            Flyway.configure().dataSource(container.jdbcUrl, container.username, container.password)
                .locations("classpath:db/migration").target("9").load().migrate()
            Database(container.jdbcUrl, container.username, container.password).use { a ->
            Database(container.jdbcUrl, container.username, container.password).use { b ->
                val admin = seedLegacyAdministrator(a, "quota fixture replacement password").principal
                // Seed the account through the V9 schema too: current stores read columns that later migrations add.
                val account = seedLegacyAccount(a, CredentialCipher(ByteArray(32) { 19 }), ProviderCredentials("synthetic-access", "synthetic-refresh", System.currentTimeMillis() + 3_600_000))
                val first = SqlAdmissionStore(a)
                val second = SqlAdmissionStore(b)
                val now = a.query { nowMillis() }
                val known = AccountQuota(account.id, "five_hour", .99, now - 5_000, now + 3_600_000)
                // Seed the old schema directly; current stores require the latest migrations.
                a.query {
                    val c = this; LegacyQuotaWindowsV9.insert { it[accountId] = known.accountId; it[windowName] = known.window; it[utilization] = known.utilization; it[observedAt] = known.observedAtMillis; it[resetsAt] = known.resetsAtMillis!! } }
                assertEquals(known.utilization, a.query { LegacyQuotaWindowsV9.selectAll().single()[LegacyQuotaWindowsV9.utilization] })
                a.migrate()
                assertEquals("pool_unavailable", assertFailsWith<TeamFailure> { second.reserve(admin, 1, "claude-opus") }.code)
                assertEquals(known, second.quotas(admin).single(), "Nullable migration changed a known observation")

                // Keep synthetic samples and their envelope on the same database clock.
                // A host clock a millisecond ahead must not turn this ordering test into a future-envelope test.
                val zero = known.copy(utilization = 0.0, observedAtMillis = now, resetsAtMillis = null)
                second.observe(account.id, QuotaUpdate(listOf(zero), now))
                first.observe(account.id, QuotaUpdate(listOf(known.copy(observedAtMillis = now - 1_000)), now))
                assertEquals(zero, first.quotas(admin).single(), "Delayed known-reset sample replaced a newer unknown-reset observation")
                val admitted = first.reserve(admin, 1, "claude-opus")
                second.settle(admitted, 0)
                assertTrue(a.query {
                    val c = this; QuotaHistory.select(QuotaHistory.resetsAt).where { QuotaHistory.accountId eq account.id }.orderBy(QuotaHistory.observedAt to SortOrder.DESC).limit(1).single()[QuotaHistory.resetsAt] == null })

                val scoped = AccountQuota(account.id, "model:opus", 1.0, now, null)
                first.observe(account.id, QuotaUpdate(listOf(scoped), now))
                assertEquals("pool_unavailable", assertFailsWith<TeamFailure> { second.reserve(admin, 1, "claude-opus") }.code)
                val sonnet = second.reserve(admin, 1, "claude-sonnet")
                first.settle(sonnet, 0)

                // Simulate observation age in this isolated database; never sleep through a real window.
                a.query {
                    val c = this; QuotaWindows.update({ (QuotaWindows.accountId eq account.id) and (QuotaWindows.windowName eq "model:opus") }) { it[observedAt] = now - MAX_QUOTA_OBSERVATION_AGE_MILLIS - 1 } }
                val staleProbe = second.reserve(admin, 1, "claude-opus")
                first.settle(staleProbe, 0)
                assertNull(first.quotas(admin).single { it.window == "model:opus" }.resetsAtMillis)

                // Known future reset protection is retained even when its observation is old.
                a.query {
                    val c = this; QuotaWindows.update({ (QuotaWindows.accountId eq account.id) and (QuotaWindows.windowName eq "model:opus") }) { it[resetsAt] = now + 3_600_000 } }
                assertEquals("pool_unavailable", assertFailsWith<TeamFailure> { second.reserve(admin, 1, "claude-opus") }.code)
                val before = second.quotas(admin)
                first.observe(account.id, QuotaUpdate(listOf(scoped.copy(utilization = 0.0, observedAtMillis = now + 3_600_000)), now))
                assertEquals(before, second.quotas(admin), "Future-dated samples must not erase protection")
            } }
        }
    }
}

/** The published pre-nullable schema, only used to seed an upgrade acceptance database. */
private object LegacyQuotaWindowsV9 : Table("quota_windows") {
    val accountId = varchar("account_id", 36)
    val windowName = varchar("window_name", 80)
    val utilization = double("utilization")
    val observedAt = long("observed_at")
    val resetsAt = long("resets_at")
    override val primaryKey = PrimaryKey(accountId, windowName)
}

private object LegacyAccountsV9 : Table("accounts") {
    val id = varchar("id", 36); val teamId = varchar("team_id", 36); val provider = varchar("provider", 32)
    val providerAccountId = varchar("provider_account_id", 128); val organizationId = varchar("organization_id", 128)
    val displayName = varchar("display_name", 120); val credentialVersion = long("credential_version")
    val credentialEnvelope = mediumText("credential_envelope"); val state = varchar("state", 32)
}
/** A refresh-owned account written with the V9 columns only, as an older server would have stored it. */
private suspend fun seedLegacyAccount(database: Database, cipher: CredentialCipher, credentials: ProviderCredentials): AccountRecord = database.query {
    val id = java.util.UUID.randomUUID().toString()
    val payload = kotlinx.serialization.json.Json.encodeToString(CredentialPayload(credentials.accessToken, credentials.refreshToken, credentials.expiresAtMillis))
    LegacyAccountsV9.insert {
        it[LegacyAccountsV9.id] = id; it[teamId] = "default"; it[provider] = "anthropic"; it[providerAccountId] = "fixture-user"
        it[organizationId] = "fixture-org"; it[displayName] = "Fixture account"; it[credentialVersion] = 1
        it[credentialEnvelope] = cipher.encrypt(payload.toByteArray(), "default:$id"); it[state] = AccountState.READY.name
    }
    AccountRecord(id, AccountIdentity("default", "anthropic", "fixture-user", "fixture-org"), "Fixture account", 1, false, null, 0, AccountState.READY)
}
