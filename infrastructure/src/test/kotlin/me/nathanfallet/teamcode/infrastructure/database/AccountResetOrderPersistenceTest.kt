package me.nathanfallet.teamcode.infrastructure.database

import kotlinx.coroutines.runBlocking
import me.nathanfallet.teamcode.domain.accounts.*
import me.nathanfallet.teamcode.domain.capacity.*
import me.nathanfallet.teamcode.domain.team.*
import me.nathanfallet.teamcode.infrastructure.database.tables.Accounts
import me.nathanfallet.teamcode.infrastructure.secrets.CredentialCipher
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.junit.jupiter.api.Timeout
import org.testcontainers.mysql.MySQLContainer
import kotlin.test.*

class AccountResetOrderPersistenceTest {
    @Test @Timeout(120)
    fun accountsComeBackByNextResetWithUnknownLastAndTiesByIdOnTheRealDatabase() = runBlocking {
        MySQLContainer("mysql:8.4").withDatabaseName("teamcode").withTmpFs(mapOf("/var/lib/mysql" to "rw,nosuid,size=1g")).use { container ->
            container.start()
            Database(container.jdbcUrl, container.username, container.password).use { db ->
                db.migrate(); val team = SqlTeamService(db); val initial = assertNotNull(team.bootstrap())
                val pending = team.login("admin", initial, "reset-order-fixture"); team.changePassword(pending.principal, initial, "reset order owner replacement password")
                val admin = team.login("admin", "reset order owner replacement password", "reset-order-fixture").principal
                val accounts = SqlAccountStore(db, CredentialCipher(ByteArray(32) { 43 }))
                val admission = SqlAdmissionStore(db)
                val never = add(accounts, "Never observed")
                val soon = add(accounts, "Resets soon")
                val later = add(accounts, "Resets later")
                val tieA = add(accounts, "Tie A")
                val tieB = add(accounts, "Tie B")
                val modelOnly = add(accounts, "Model window only")
                val now = db.query { nowMillis() } - 100  // Observations may not be dated in the future.
                val hour = 3_600_000L
                // The closest of an account's shared windows decides, not the first one listed.
                admission.observe(later, QuotaUpdate(listOf(AccountQuota(later, "five_hour", .1, now, now + 5 * hour), AccountQuota(later, "seven_day", .1, now, now + 48 * hour)), now))
                admission.observe(soon, QuotaUpdate(listOf(AccountQuota(soon, "seven_day", .1, now, now + 24 * hour), AccountQuota(soon, "five_hour", .1, now, now + hour)), now))
                admission.observe(tieA, QuotaUpdate(listOf(AccountQuota(tieA, "five_hour", .1, now, now + 2 * hour)), now))
                admission.observe(tieB, QuotaUpdate(listOf(AccountQuota(tieB, "five_hour", .9, now, now + 2 * hour)), now))
                // A model-scoped window never bars the account, so it does not give it a reset either.
                admission.observe(modelOnly, QuotaUpdate(listOf(AccountQuota(modelOnly, "model:fable", .1, now, now + hour / 2)), now))

                val expectedTies = listOf(tieA, tieB).sorted()
                assertEquals(listOf(soon) + expectedTies + listOf(later) + listOf(never, modelOnly).sorted(), admission.poolAccounts(admin).mapNotNull { it.accountId })
                assertEquals(admission.poolAccounts(admin).mapNotNull { it.accountId }, accounts.list("default").map { it.id }, "The management listing uses the same order")

                // A later observation moves the account; a window with no reset leaves it where the others still say.
                admission.observe(later, QuotaUpdate(listOf(AccountQuota(later, "five_hour", .2, now + 10, now + hour / 2)), now + 10))
                assertEquals(later, admission.poolAccounts(admin).first().accountId)
                admission.observe(soon, QuotaUpdate(listOf(AccountQuota(soon, "five_hour", .2, now + 20, null)), now + 20))
                assertEquals(now + 24 * hour, stored(db, soon), "An unreported reset falls back to the next window that has one")
                assertEquals(Long.MAX_VALUE, stored(db, never))

                // A reset that passes without a new observation is swept by maintenance, so an unobserved account does not stay first.
                val expiring = add(accounts, "Expiring")
                val t = db.query { nowMillis() }
                admission.observe(expiring, QuotaUpdate(listOf(AccountQuota(expiring, "five_hour", .1, t - 10, t + 1500)), t - 10))
                assertEquals(t + 1500, stored(db, expiring))
                assertEquals(expiring, admission.poolAccounts(admin).first().accountId)
                Thread.sleep(2000)
                admission.maintain()
                assertEquals(Long.MAX_VALUE, stored(db, expiring))
                assertNotEquals(expiring, admission.poolAccounts(admin).first().accountId)
            }
        }
    }

    private suspend fun stored(db: Database, id: String) = db.query { Accounts.selectAll().where { Accounts.id eq id }.single()[Accounts.nextResetAt] }

    private suspend fun add(store: SqlAccountStore, name: String): String {
        val enrolled = store.upsert(AccountMutationActor.System, VerifiedProviderAccount(AccountIdentity("default", "anthropic", name, "synthetic-org"), name),
            ProviderCredentials("synthetic-access", "synthetic-refresh", Long.MAX_VALUE), null, name.filter { it.isLetter() }.padEnd(16, 'x')) as EnrollmentResult.Created
        store.handover(AccountMutationActor.System, "default", enrolled.account.id, accessOnly = true)
        return enrolled.account.id
    }
}
