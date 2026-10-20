package me.nathanfallet.teamcode.infrastructure.database

import kotlinx.coroutines.*
import me.nathanfallet.teamcode.domain.accounts.*
import me.nathanfallet.teamcode.domain.capacity.*
import me.nathanfallet.teamcode.domain.team.*
import me.nathanfallet.teamcode.infrastructure.database.tables.*
import me.nathanfallet.teamcode.infrastructure.secrets.CredentialCipher
import me.nathanfallet.teamcode.infrastructure.secrets.Passwords
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*
import org.junit.jupiter.api.Timeout
import org.testcontainers.mysql.MySQLContainer
import kotlin.test.*

class OwnerPersistenceTest {
    @Test @Timeout(120)
    fun concurrentOwnershipChangesPreserveRecoveryQuotasAndScopedCredentials() = runBlocking {
        database { container, a, b ->
            a.migrate(); b.migrate()
            val teamA = SqlTeamService(a); val teamB = SqlTeamService(b)
            val initial = assertNotNull(teamA.bootstrap())
            val pending = teamA.login("admin", initial, "owner-test")
            assertEquals(Role.OWNER, pending.principal.member.role)
            teamB.changePassword(pending.principal, initial, "owner initial replacement password")
            val first = teamA.login("admin", "owner initial replacement password", "owner-test")
            suspend fun ready(created: NewMember): LoginSession {
                val login = teamB.login(created.member.username, created.temporaryPassword, "owner-test")
                teamA.changePassword(login.principal, created.temporaryPassword, "owner test replacement password")
                return teamB.login(created.member.username, "owner test replacement password", "owner-test")
            }
            val admin = ready(teamA.createMember(first.principal, "operator", Role.ADMIN))
            failure("owner_required") { teamB.createMember(admin.principal, "takeover", Role.OWNER) }
            failure("owner_required") { teamB.updateMember(admin.principal, admin.principal.member.id, Role.OWNER, false) }
            for ((role, disabled) in listOf(Role.OWNER to true, Role.MEMBER to false, Role.ADMIN to false)) {
                failure("owner_required") { teamB.updateMember(admin.principal, first.principal.member.id, role, disabled) }
                failure("last_owner") { teamB.updateMember(first.principal, first.principal.member.id, role, disabled) }
            }
            failure("owner_required") { teamB.resetPassword(admin.principal, first.principal.member.id) }
            failure("last_owner") { teamB.resetPassword(first.principal, first.principal.member.id) }
            val secondPending = teamA.createMember(first.principal, "second-owner", Role.OWNER)
            failure("last_owner") { teamB.updateMember(first.principal, first.principal.member.id, Role.ADMIN, false) }
            val second = ready(secondPending)
            val keys = SqlServiceKeyService(a)
            val ownerKeys = listOf(first, second).map { keys.create(it.principal, it.principal.member.id, "Owner CI", ServiceScope.entries.toSet(), 1) }
            for (key in ownerKeys) {
                val actor = assertNotNull(teamB.authenticate(key.accessToken))
                assertEquals(Role.OWNER, actor.member.role)
                failure("credential_scope") { teamB.createMember(actor, "service-takeover", Role.OWNER) }
            }
            val group = GroupServiceImpl(a).create(first.principal, GroupPolicy("Ownership continuity", 1000, 2)).summary.group.id
            for (login in listOf(first, second)) GroupServiceImpl(b).assign(first.principal, group, login.principal.member.id, GroupRole.MEMBER)
            val store = SqlAccountStore(a, CredentialCipher(ByteArray(32) { 31 }))
            val enrolled = store.upsert(AccountMutationActor.System,
                VerifiedProviderAccount(AccountIdentity("default", "anthropic", "synthetic-owner-account", "synthetic-org"), "Fixture"),
                ProviderCredentials("synthetic-access", "synthetic-refresh", Long.MAX_VALUE), null, "owner-fixture") as EnrollmentResult.Created
            store.handover(AccountMutationActor.System, "default", enrolled.account.id, accessOnly = true)
            val admission = SqlAdmissionStore(a)
            admission.settle(admission.reserve(first.principal, 100, "claude-test"), 20)
            admission.settle(admission.reserve(second.principal, 100, "claude-test"), 40)
            val start = CompletableDeferred<Unit>()
            val outcomes = coroutineScope {
                val jobs = listOf(first to teamA, second to teamB).map { (login, team) -> async {
                    start.await()
                    try { team.updateMember(login.principal, login.principal.member.id, Role.ADMIN, false); "removed" }
                    catch (error: TeamFailure) { error.code }
                } }
                start.complete(Unit); jobs.awaitAll()
            }
            assertEquals(listOf("last_owner", "removed"), outcomes.sorted())
            val remainingIndex = outcomes.indexOf("last_owner"); val removedIndex = outcomes.indexOf("removed")
            val logins = listOf(first, second)
            val remaining = assertNotNull(teamB.authenticate(logins[remainingIndex].token))
            assertEquals(Role.OWNER, remaining.member.role)
            assertNull(teamB.authenticate(logins[removedIndex].token), "Demotion must revoke captured owner sessions")
            assertNull(teamB.authenticate(ownerKeys[removedIndex].accessToken), "Demotion must revoke owner-owned CI keys")
            assertNotNull(teamB.authenticate(ownerKeys[remainingIndex].accessToken))
            failure("authentication_required") { GroupServiceImpl(b).create(logins[removedIndex].principal, GroupPolicy("Stale authority")) }
            val removed = teamB.login(logins[removedIndex].principal.member.username,
                if (removedIndex == 0) "owner initial replacement password" else "owner test replacement password", "after-demotion")
            assertEquals(Role.ADMIN, removed.principal.member.role)
            failure("owner_required") { teamB.updateMember(removed.principal, removed.principal.member.id, Role.OWNER, false) }
            assertEquals(if (removedIndex == 0) 20 else 40, SqlAdmissionStore(b).capacity(removed.principal).chargedAndReservedUnits)
            assertEquals(GroupUsage(60, 0), GroupServiceImpl(b).get(remaining, group).summary.usage)
            assertEquals(2, GroupServiceImpl(b).get(remaining, group).members.size)
            val baseRows = a.query { Members.select(Members.id, Members.role, Members.isOwner).where { Members.teamId eq "default" }.toList() }
            assertEquals(1, baseRows.count { it[Members.isOwner] })
            assertTrue(baseRows.all { it[Members.role] == "ADMIN" }, "Legacy readers must not see an unknown OWNER enum")
            assertNull(teamB.bootstrap())
            val events = SqlAuditService(b).list(remaining).events
            assertEquals(1, events.count { it.action == "member.owner_granted" })
            assertEquals(1, events.count { it.action == "member.owner_removed" })
            val reset = teamB.resetPassword(remaining, removed.principal.member.id)
            assertTrue(reset.member.passwordChangeRequired, "Owners retain ordinary member recovery authority")
            assertNull(teamA.authenticate(removed.token))
        }
    }

    @Test @Timeout(120)
    fun legacyUpgradeSelectsOneExistingAdministratorOnceWithoutChangingTheirPasswordOrLedgers() = runBlocking {
        database { container, a, b ->
            Flyway.configure().dataSource(container.jdbcUrl, container.username, container.password)
                .locations("classpath:db/migration").target("20261019185445.3").load().migrate()
            val password = "legacy owner password kept unchanged"
            val old = seedLegacyAdministrator(a, password)
            val oldKey = "tcsk_" + Passwords.secret()
            a.query { Tokens.insert {
                it[id] = "legacy-owner-key"; it[memberId] = old.principal.member.id; it[tokenHash] = Passwords.digest(oldKey)
                it[device] = "Legacy automation"; it[serviceScopes] = "[\"usage:read\"]"; it[createdAt] = nowMillis(); it[expiresAt] = nowMillis() + 86_400_000
            } }
            val beforeHash = a.query { Members.select(Members.passwordHash).where { Members.id eq old.principal.member.id }.single()[Members.passwordHash] }
            a.query { MemberPolicies.insert { it[memberId] = old.principal.member.id; it[dailyUnits] = 12345; it[weight] = 5 } }
            a.migrate(); b.migrate()
            assertFalse(a.query { Members.select(Members.isOwner).single()[Members.isOwner] })
            val teamA = SqlTeamService(a); val teamB = SqlTeamService(b)
            val migrations = coroutineScope { listOf(async { teamA.bootstrap() }, async { teamB.bootstrap() }).awaitAll() }
            assertTrue(migrations.all { it == null }, "An upgrade never creates or returns a new password")
            assertNull(teamA.authenticate(old.token), "Old credentials must not silently gain owner authority")
            assertNull(teamB.authenticate(oldKey), "A legacy service credential must be revoked along with interactive logins")
            val owner = teamB.login("admin", password, "upgraded-owner")
            assertEquals(old.principal.member.id, owner.principal.member.id)
            assertEquals(Role.OWNER, owner.principal.member.role)
            assertEquals(beforeHash, a.query { Members.select(Members.passwordHash).single()[Members.passwordHash] })
            assertEquals(12345, SqlAdmissionStore(b).capacity(owner.principal).policy.dailyUnits)
            assertEquals(5, SqlAdmissionStore(b).capacity(owner.principal).policy.weight)
            assertNull(teamA.bootstrap()); assertNotNull(teamB.authenticate(owner.token))
            assertEquals(1, SqlAuditService(b).list(owner.principal).events.count { it.action == "member.owner_migrated" })
            assertEquals("ADMIN", a.query { Members.select(Members.role).single()[Members.role] })
        }
    }

    private suspend fun failure(code: String, block: suspend () -> Any?) { assertEquals(code, assertFailsWith<TeamFailure> { block() }.code) }
    private suspend fun database(test: suspend (MySQLContainer, Database, Database) -> Unit) {
        MySQLContainer("mysql:8.4").withDatabaseName("teamcode").withTmpFs(mapOf("/var/lib/mysql" to "rw,nosuid,size=1g")).use { container ->
            container.start()
            Database(container.jdbcUrl, container.username, container.password).use { a ->
                Database(container.jdbcUrl, container.username, container.password).use { b -> test(container, a, b) }
            }
        }
    }
}
