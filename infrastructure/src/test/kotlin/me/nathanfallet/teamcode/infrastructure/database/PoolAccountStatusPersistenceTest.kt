package me.nathanfallet.teamcode.infrastructure.database

import kotlinx.coroutines.runBlocking
import me.nathanfallet.teamcode.domain.accounts.*
import me.nathanfallet.teamcode.domain.capacity.*
import me.nathanfallet.teamcode.domain.team.*
import me.nathanfallet.teamcode.infrastructure.secrets.CredentialCipher
import org.junit.jupiter.api.Timeout
import org.testcontainers.mysql.MySQLContainer
import kotlin.test.*

class PoolAccountStatusPersistenceTest {
    @Test @Timeout(120)
    fun membersSeeOnlyTheirPoolsUnderNeutralLabelsAndAdministratorsSeeNames() = runBlocking {
        MySQLContainer("mysql:8.4").withDatabaseName("teamcode").withTmpFs(mapOf("/var/lib/mysql" to "rw,nosuid,size=1g")).use { container ->
            container.start()
            Database(container.jdbcUrl, container.username, container.password).use { db ->
                db.migrate(); val team = SqlTeamService(db); val initial = assertNotNull(team.bootstrap())
                val pending = team.login("admin", initial, "pool-status-fixture"); team.changePassword(pending.principal, initial, "pool status owner replacement password")
                val admin = team.login("admin", "pool status owner replacement password", "pool-status-fixture").principal
                val created = team.createMember(admin, "developer", Role.MEMBER)
                val pendingMember = team.login("developer", created.temporaryPassword, "pool-status-fixture")
                team.changePassword(pendingMember.principal, created.temporaryPassword, "pool status member replacement password")
                val member = team.login("developer", "pool status member replacement password", "pool-status-fixture").principal
                val accounts = SqlAccountStore(db, CredentialCipher(ByteArray(32) { 41 }))
                val admission = SqlAdmissionStore(db)
                val home = add(accounts, "Home work account", "home")
                val busy = add(accounts, "Home busy account", "home")
                val reserve = add(accounts, "Shared reserve", "reserve")
                val other = add(accounts, "Other team pool", "other")
                admission.configurePool(admin, PoolBudget("reserve", 1000, true))
                admission.configure(admin, MemberPolicy(member.member.id, 1000, 4, "home"))
                admission.configure(admin, MemberPolicy(admin.member.id, 1000, 4, "other"))
                val now = db.query { nowMillis() }
                admission.observe(home, QuotaUpdate(listOf(AccountQuota(home, "five_hour", .42, now, now + 3_600_000), AccountQuota(home, "model:fable", 1.0, now, now + 86_400_000)), now))
                admission.observe(busy, QuotaUpdate(listOf(AccountQuota(busy, "seven_day", .97, now, now + 86_400_000)), now))

                // The member's view: home pool only, neutral labels, no identifiers, no other pool and no reserve without opt-in.
                val seen = admission.poolAccounts(member)
                // The listing is ordered by next reset (home's 5-hour window first); the number follows priority then random id, so only the set is stable.
                assertEquals(setOf("Account 1", "Account 2"), seen.map { it.label }.toSet())
                assertTrue(seen.first().windows.any { it.window == "five_hour" }, "The account resetting first comes first")
                assertTrue(seen.all { it.accountId == null && it.name == null && it.email == null && it.poolId == null }, "Members see no identifiers, not even pool names")
                val homeView = seen.single { it.windows.any { w -> w.window == "five_hour" } }
                assertTrue(homeView.usable, "A spent model window bars only that model")
                assertEquals(AccountUnavailability.QUOTA_EXHAUSTED, seen.single { it !== homeView }.unavailable)
                assertFalse(seen.flatMap { it.windows }.any { it.accountId == other || it.accountId == reserve })

                admission.configure(admin, MemberPolicy(member.member.id, 1000, 4, "home", allowSharedOverflow = true))
                assertEquals(3, admission.poolAccounts(member).size, "Opting in adds the shared reserve, never the other pool")
                assertEquals(setOf("home", "reserve", "other"), admission.poolAccounts(admin).mapNotNull { it.poolId }.toSet())

                // A key the administrator created for themselves acts as a member view: their own pool, no names.
                val key = SqlServiceKeyService(db).create(admin, admin.member.id, "Usage reader", setOf(ServiceScope.USAGE_READ), 1)
                val service = assertNotNull(team.authenticate(key.accessToken))
                assertEquals(listOf(null), admission.poolAccounts(service).map { it.name })
                // team:read sees what an interactive administrator sees: every account by name.
                val teamKey = SqlServiceKeyService(db).create(admin, admin.member.id, "Team reader", setOf(ServiceScope.TEAM_READ), 1)
                assertEquals(admission.poolAccounts(admin).map { it.label }, admission.poolAccounts(assertNotNull(team.authenticate(teamKey.accessToken))).map { it.label })
                val inference = SqlServiceKeyService(db).create(admin, admin.member.id, "Inference only", setOf(ServiceScope.INFERENCE), 1)
                assertEquals("credential_scope", assertFailsWith<TeamFailure> { admission.poolAccounts(assertNotNull(team.authenticate(inference.accessToken))) }.code)

                val full = admission.poolAccounts(admin)
                assertEquals(setOf(home, busy, reserve, other), full.mapNotNull { it.accountId }.toSet())
                assertEquals(setOf("Home work account", "Home busy account", "Shared reserve", "Other team pool"), full.map { it.label }.toSet())

                // A request in flight is reserved; once settled it is charged and no longer reserved.
                val held = admission.reserve(member, 30, "claude-test")
                assertEquals(30, admission.capacity(member).reservedUnits)
                assertEquals(30, admission.capacity(member).chargedAndReservedUnits)
                admission.settle(held, 12, true)
                assertEquals(0, admission.capacity(member).reservedUnits)
                assertEquals(12, admission.capacity(member).chargedAndReservedUnits)
            }
        }
    }

    private suspend fun add(store: SqlAccountStore, name: String, pool: String): String {
        val enrolled = store.upsert(AccountMutationActor.System, VerifiedProviderAccount(AccountIdentity("default", "anthropic", name, "synthetic-org"), name),
            ProviderCredentials("synthetic-access", "synthetic-refresh", Long.MAX_VALUE), null, name.filter { it.isLetter() }.padEnd(16, 'x')) as EnrollmentResult.Created
        store.handover(AccountMutationActor.System, "default", enrolled.account.id, accessOnly = true)
        store.configure(AccountMutationActor.System, "default", enrolled.account.id, name, false, 0, pool)
        return enrolled.account.id
    }
}
