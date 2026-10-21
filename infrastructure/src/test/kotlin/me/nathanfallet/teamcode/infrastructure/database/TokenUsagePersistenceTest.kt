package me.nathanfallet.teamcode.infrastructure.database

import java.util.UUID
import kotlinx.coroutines.runBlocking
import me.nathanfallet.teamcode.domain.accounts.*
import me.nathanfallet.teamcode.domain.capacity.*
import me.nathanfallet.teamcode.domain.routing.TokenUsage
import me.nathanfallet.teamcode.domain.team.*
import me.nathanfallet.teamcode.infrastructure.database.tables.AccountTokenUsageTable
import me.nathanfallet.teamcode.infrastructure.database.tables.TokenUsageTable
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*
import me.nathanfallet.teamcode.infrastructure.secrets.CredentialCipher
import org.jetbrains.exposed.v1.migration.jdbc.MigrationUtils
import org.junit.jupiter.api.Timeout
import org.testcontainers.mysql.MySQLContainer
import kotlin.test.*

/** The raw token ledger against real MySQL: once per settlement, per run and model, and read only by its owner or an administrator. */
class TokenUsagePersistenceTest {
    @Test @Timeout(120)
    fun settledRequestsAddTheirRawTokensOnceAndOnlyTheOwnerOrAnAdministratorReadsThem() = runBlocking {
        MySQLContainer("mysql:8.4").withDatabaseName("teamcode")
            .withTmpFs(mapOf("/var/lib/mysql" to "rw,nosuid,size=1g")).use { container ->
            container.start()
            Database(container.jdbcUrl, container.username, container.password).use { db ->
                db.migrate()
                db.query {
                    assertEquals(emptyList(), MigrationUtils.statementsRequiredForDatabaseMigration(TokenUsageTable, AccountTokenUsageTable, withLogs = false),
                        "The reviewed migration matches the Exposed table")
                }
                val team = SqlTeamService(db)
                val initial = assertNotNull(team.bootstrap())
                team.changePassword(team.login("admin", initial, "usage-test").principal, initial, "usage owner replacement password")
                val admin = team.login("admin", "usage owner replacement password", "usage-test").principal
                val created = team.createMember(admin, "developer", Role.MEMBER)
                team.changePassword(team.login("developer", created.temporaryPassword, "usage-test").principal, created.temporaryPassword, MEMBER_PASSWORD)
                val member = team.login("developer", MEMBER_PASSWORD, "usage-test").principal
                val accounts = SqlAccountStore(db, CredentialCipher(ByteArray(32) { 31 }))
                val enrolled = accounts.upsert(AccountMutationActor.System,
                    VerifiedProviderAccount(AccountIdentity("default", "anthropic", "synthetic-usage-account", "synthetic-org"), "Fixture"),
                    ProviderCredentials("synthetic-access", "synthetic-refresh", Long.MAX_VALUE), null, "usage-fixture") as EnrollmentResult.Created
                accounts.handover(AccountMutationActor.System, "default", enrolled.account.id, accessOnly = true)
                val admission = SqlAdmissionStore(db)
                val runs = AgentRunServiceImpl(db)
                val runId = UUID.randomUUID().toString()
                runs.start(member, runId, "claude", "interactive")
                val opus = "claude-opus-test"
                fun tokens(scale: Long) = TokenUsage(input = 1 * scale, output = 2 * scale, cacheRead = 3 * scale, cacheCreation = 4 * scale)

                val first = admission.reserve(member, AdmissionInput(100, opus, "anthropic", runId = runId))
                admission.settle(first, 30, true, MeteredUsage("anthropic", opus, tokens(10)))
                admission.settle(first, 30, true, MeteredUsage("anthropic", opus, tokens(10)))
                admission.settle(admission.reserve(member, AdmissionInput(100, opus, "anthropic", runId = runId)), 30, true, MeteredUsage("anthropic", opus, tokens(1)))
                admission.settle(admission.reserve(member, AdmissionInput(100, opus, "anthropic", runId = runId)), null, false, MeteredUsage("anthropic", opus, null))
                admission.settle(admission.reserve(member, 100, "claude-haiku-test"), 5, true, MeteredUsage("anthropic", "claude-haiku-test", tokens(100)))
                admission.settle(admission.reserve(member, 100, opus), 0, false)
                admission.settle(admission.reserve(admin, 100, opus), 7, true, MeteredUsage("anthropic", opus, tokens(1000)))

                val today = db.query { nowMillis() } / DAY
                val own = admission.tokenUsage(member, TokenUsageQuery(today, today))
                assertNull(own.nextAfter)
                assertEquals(setOf(member.member.id), own.rows.map { it.memberId }.toSet(), "A member reads only their own rows")
                val run = own.rows.single { it.runId == runId }
                assertEquals(TokenUsageRow(today, member.member.id, "developer", runId, "anthropic", opus, requests = 3, unmeteredRequests = 1,
                    input = 11, cacheCreation = 44, cacheRead = 33, output = 22), run, "A repeated settlement adds nothing; an unmetered request adds no tokens")
                val outside = own.rows.single { it.runId == null }
                assertEquals("claude-haiku-test", outside.model, "Without usage a settlement leaves the ledger untouched")
                assertEquals(1, outside.requests); assertEquals(100, outside.input)

                assertEquals("forbidden", assertFailsWith<TeamFailure> { admission.tokenUsage(member, TokenUsageQuery(today, today, all = true)) }.code)
                assertEquals("forbidden", assertFailsWith<TeamFailure> { admission.tokenUsage(member, TokenUsageQuery(today, today, admin.member.id)) }.code)
                val keys = SqlServiceKeyService(db)
                val ownerKey = assertNotNull(team.authenticate(keys.create(admin, admin.member.id, "Usage reader", setOf(ServiceScope.USAGE_READ), 1).accessToken))
                assertEquals("credential_scope", assertFailsWith<TeamFailure> { admission.tokenUsage(ownerKey, TokenUsageQuery(today, today, all = true)) }.code)
                assertEquals(listOf(admin.member.id), admission.tokenUsage(ownerKey, TokenUsageQuery(today, today)).rows.map { it.memberId }, "A usage:read key reads its owner's rows")
                val teamKey = assertNotNull(team.authenticate(keys.create(admin, admin.member.id, "Team reader", setOf(ServiceScope.TEAM_READ), 1).accessToken))
                assertEquals(3, admission.tokenUsage(teamKey, TokenUsageQuery(today, today, all = true)).rows.size, "An administrator's team:read key reads the team")
                val inference = assertNotNull(team.authenticate(keys.create(member, member.member.id, "Inference", setOf(ServiceScope.INFERENCE), 1).accessToken))
                assertEquals("credential_scope", assertFailsWith<TeamFailure> { admission.tokenUsage(inference, TokenUsageQuery(today, today)) }.code)

                val everyone = admission.tokenUsage(admin, TokenUsageQuery(today, today, all = true)).rows
                assertEquals(3, everyone.size)
                assertEquals(own.rows, admission.tokenUsage(admin, TokenUsageQuery(today, today, member.member.id)).rows)
                assertEquals(emptyList(), admission.tokenUsage(admin, TokenUsageQuery(today + 1, today + 2, all = true)).rows)
                val after = everyone.first().let { TokenUsageCursor(it.dayNumber, it.memberId, it.runId.orEmpty(), it.provider, it.model) }
                val hourly = db.query { AccountTokenUsageTable.selectAll().where { AccountTokenUsageTable.accountId eq enrolled.account.id }.toList() }
                val opusHour = hourly.filter { it[AccountTokenUsageTable.model] == opus }
                assertEquals(4, opusHour.sumOf { it[AccountTokenUsageTable.requests] }, "Each settled request also counts once on its account")
                assertEquals(1, opusHour.sumOf { it[AccountTokenUsageTable.unmeteredRequests] })
                assertEquals(1011, opusHour.sumOf { it[AccountTokenUsageTable.inputTokens] })
                assertEquals(2022, opusHour.sumOf { it[AccountTokenUsageTable.outputTokens] })
                assertEquals(everyone.drop(1), admission.tokenUsage(admin, TokenUsageQuery(today, today, all = true, after = after)).rows, "Keyset pages resume after the cursor")
            }
        }
    }
    companion object { private const val MEMBER_PASSWORD = "usage member replacement password"; private const val DAY = 86_400_000L }
}
