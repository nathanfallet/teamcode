package me.nathanfallet.teamcode.infrastructure.database

import java.util.UUID
import kotlinx.coroutines.*
import me.nathanfallet.teamcode.domain.accounts.*
import me.nathanfallet.teamcode.domain.capacity.*
import me.nathanfallet.teamcode.domain.gateway.RequestCancellationService
import me.nathanfallet.teamcode.domain.team.*
import me.nathanfallet.teamcode.infrastructure.database.tables.*
import me.nathanfallet.teamcode.infrastructure.secrets.*
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*
import org.testcontainers.mysql.MySQLContainer
import kotlin.test.*

class ServiceKeyPersistenceTest {
    @Test fun serviceKeysAreScopedSharedAndRevokedWithoutResettingTheMemberLedger() = runBlocking {
        MySQLContainer("mysql:8.4").withDatabaseName("teamcode").withTmpFs(mapOf("/var/lib/mysql" to "rw,nosuid,size=1g")).use { container ->
            container.start()
            Flyway.configure().dataSource(container.jdbcUrl, container.username, container.password).target("20261019125211").load().migrate()
            Database(container.jdbcUrl, container.username, container.password).use { a ->
            Database(container.jdbcUrl, container.username, container.password).use { b ->
                val password = "service owner replacement password"
                val legacy = seedLegacyAdministrator(a, password)
                a.migrate(); b.migrate()
                val teamA = SqlTeamService(a); val teamB = SqlTeamService(b)
                val admin = teamB.authenticate(legacy.token)!!
                assertNull(admin.serviceScopes, "Published interactive tokens remain interactive after migration")
                val first = SqlServiceKeyService(a); val second = SqlServiceKeyService(b)
                val created = teamA.createMember(admin, "service-colleague", Role.MEMBER)
                val temporary = teamB.login("service-colleague", created.temporaryPassword, "test")
                teamA.changePassword(temporary.principal, created.temporaryPassword, "service colleague replacement password")
                val colleague = teamB.login("service-colleague", "service colleague replacement password", "test").principal
                val key = first.create(admin, admin.member.id, "CI's runner", setOf(ServiceScope.INFERENCE), 30)
                val actor = teamB.authenticate(key.accessToken)!!
                assertEquals(admin.member.id, actor.member.id)
                assertEquals(setOf(ServiceScope.INFERENCE), actor.serviceScopes)
                assertEquals(key.metadata, second.list(admin).single())
                assertFalse(first.list(admin).toString().contains(key.accessToken))
                assertEquals(Passwords.digest(key.accessToken), a.query { Tokens.select(Tokens.tokenHash).where { Tokens.id eq key.metadata.id }.single()[Tokens.tokenHash] })
                assertTrue(SqlDeviceService(b).sessions(admin).none { it.id == key.metadata.id })
                failure("credential_scope") { first.list(actor) }
                failure("credential_scope") { first.create(actor, admin.member.id, "child", setOf(ServiceScope.INFERENCE), 1) }
                failure("credential_scope") { teamA.changePassword(actor, password, "cannot change owner password") }
                failure("credential_scope") { teamA.members(actor) }
                failure("authentication_required") { second.list(actor.copy(serviceScopes = null)) }
                failure("authentication_required") { second.list(admin.copy(teamId = "another-team")) }
                failure("forbidden") { second.list(colleague, admin.member.id) }
                failure("not_found") { first.revoke(colleague, key.metadata.id) }
                failure("forbidden") { first.create(colleague, admin.member.id, "steal", setOf(ServiceScope.INFERENCE), 1) }
                verifyLedgerAndRevocation(a, b, admin, key, actor)
                verifyLoginLimit(a, b, admin, password)
                verifyExpiryAndInvalidation(a, b, admin, colleague, password)
                verifyNoExpiry(a, b, password)
                verifyCreationLimit(a, b, password)
                assertTrue(a.query { AuditEvents.select(AuditEvents.action).where { AuditEvents.action inList listOf("service_key.created", "service_key.revoked") }.count() } >= 2)
            } }
        }
    }
    private suspend fun verifyLedgerAndRevocation(a: Database, b: Database, admin: Principal, key: CreatedServiceKey, actor: Principal) = coroutineScope {
        val accounts = SqlAccountStore(a, CredentialCipher(ByteArray(32) { 59 }))
        val account = accounts.upsert(AccountMutationActor.System, VerifiedProviderAccount(AccountIdentity("default", "anthropic", "service-account", "org"), "Service account"), ProviderCredentials("synthetic-access", "synthetic-refresh", Long.MAX_VALUE), null, "service-enrollment") as EnrollmentResult.Created
        accounts.handover(AccountMutationActor.System, "default", account.account.id, accessOnly = true)
        val admissionA = SqlAdmissionStore(a); val admissionB = SqlAdmissionStore(b)
        admissionA.configurePool(admin, PoolBudget("", 200))
        admissionA.configure(admin, MemberPolicy(admin.member.id, dailyUnits = 100))
        val held = admissionA.reserve(actor, 60, "claude-test")
        failure("member_allowance") { admissionB.reserve(admin, 60, "claude-test") }
        admissionB.settle(held, 30)
        val usageKey = SqlServiceKeyService(a).create(admin, admin.member.id, "Quota reader", setOf(ServiceScope.USAGE_READ), 1)
        val reader = SqlTeamService(b).authenticate(usageKey.accessToken)!!
        assertEquals(30, admissionB.capacity(reader).chargedAndReservedUnits)
        failure("credential_scope") { admissionB.reserve(reader, 1, "claude-test") }
        failure("credential_scope") { admissionB.configure(actor, MemberPolicy(admin.member.id)) }
        failure("credential_scope") { admissionB.capacity(actor) }
        failure("credential_scope") { SqlDirectiveService(b).current(actor) }
        val guidanceKey = SqlServiceKeyService(a).create(admin, admin.member.id, "Guidance", setOf(ServiceScope.GUIDANCE), 1)
        val guidance = SqlTeamService(b).authenticate(guidanceKey.accessToken)!!
        assertNull(SqlDirectiveService(a).current(guidance))
        failure("credential_scope") { admissionB.capacity(guidance) }
        val started = CompletableDeferred<Unit>(); val stopped = CompletableDeferred<Unit>()
        val control = RequestCancellationService(SqlRequestCancellationStore(a))
        val work = async {
            try { control.run(actor, UUID.randomUUID().toString()) {
                started.complete(Unit)
                try { awaitCancellation() } finally { stopped.complete(Unit) }
            } } catch (error: TeamFailure) { error.code }
        }
        started.await()
        SqlServiceKeyService(b).revoke(admin, key.metadata.id)
        assertEquals("authentication_required", withTimeout(5_000) { work.await() })
        withTimeout(5_000) { stopped.await() }
        assertNull(SqlTeamService(a).authenticate(key.accessToken))
        assertNotNull(SqlTeamService(a).authenticate(usageKey.accessToken))
        assertEquals(30, admissionA.capacity(admin).chargedAndReservedUnits)
    }
    private suspend fun verifyLoginLimit(a: Database, b: Database, admin: Principal, password: String) {
        val keys = SqlServiceKeyService(a)
        val service = keys.create(admin, admin.member.id, "Keep across logins", setOf(ServiceScope.INFERENCE), 1)
        a.query {
            Tokens.update({ Tokens.id eq service.metadata.id }) { it[expiresAt] = nowMillis() + 60_000 }
            val count = Tokens.select(Tokens.id).where { (Tokens.memberId eq admin.member.id) and Tokens.serviceScopes.isNull() }.count().toInt()
            repeat(50 - count) {
                Tokens.insert {
                    it[id] = UUID.randomUUID().toString(); it[memberId] = admin.member.id
                    it[tokenHash] = Passwords.digest(Passwords.secret()); it[device] = "Synthetic login"
                    it[createdAt] = nowMillis(); it[expiresAt] = nowMillis() + 2 * 86_400_000
                }
            }
        }
        val login = SqlTeamService(b).login("admin", password, "New login")
        assertNotNull(SqlTeamService(a).authenticate(service.accessToken), "Session rotation cannot evict an unrelated CI key")
        assertEquals(50, SqlDeviceService(a).sessions(login.principal).size)
    }
    private suspend fun verifyExpiryAndInvalidation(a: Database, b: Database, original: Principal, colleague: Principal, password: String) {
        val team = SqlTeamService(a)
        val admin = team.login("admin", password, "Validation").principal
        val keys = SqlServiceKeyService(b)
        val expired = keys.create(admin, colleague.member.id, "Expired", setOf(ServiceScope.INFERENCE), 1)
        a.query { Tokens.update({ Tokens.id eq expired.metadata.id }) { it[expiresAt] = nowMillis() - 1 } }
        assertNull(team.authenticate(expired.accessToken))
        val malformed = keys.create(admin, colleague.member.id, "Invalid scopes", setOf(ServiceScope.INFERENCE), 1)
        a.query { Tokens.update({ Tokens.id eq malformed.metadata.id }) { it[serviceScopes] = "[]" } }
        failure("authentication_required") { team.authenticate(malformed.accessToken) }
        keys.revoke(admin, malformed.metadata.id)
        val revoked = keys.create(admin, colleague.member.id, "Role change", setOf(ServiceScope.INFERENCE), 1)
        team.updateMember(admin, colleague.member.id, Role.MEMBER, true)
        assertNull(SqlTeamService(b).authenticate(revoked.accessToken))
        assertEquals(30, SqlAdmissionStore(a).capacity(admin).chargedAndReservedUnits)
        assertEquals(original.member.id, admin.member.id)
    }
    private suspend fun verifyNoExpiry(a: Database, b: Database, password: String) {
        val team = SqlTeamService(a)
        val admin = team.login("admin", password, "No expiry").principal
        // The earlier colleague was disabled by the role-change check; use a fresh, active member.
        val created = team.createMember(admin, "no-expiry-colleague", Role.MEMBER)
        val temporary = team.login("no-expiry-colleague", created.temporaryPassword, "test")
        team.changePassword(temporary.principal, created.temporaryPassword, "no expiry colleague password")
        val colleague = team.login("no-expiry-colleague", "no expiry colleague password", "test").principal
        val keys = SqlServiceKeyService(b)
        val forever = keys.create(admin, admin.member.id, "Skills CI", setOf(ServiceScope.SKILLS_PUBLISH), null)
        assertTrue(forever.metadata.neverExpires)
        assertEquals(ServiceKeyService.NO_EXPIRY, forever.metadata.expiresAt)
        assertEquals(forever.metadata, SqlServiceKeyService(a).list(admin).single { it.id == forever.metadata.id })
        assertEquals(1, a.query { AuditEvents.select(AuditEvents.id).where { (AuditEvents.action eq "service_key.created_no_expiry") and (AuditEvents.subjectId eq forever.metadata.id) }.count() })
        assertEquals(0, a.query { AuditEvents.select(AuditEvents.id).where { (AuditEvents.action eq "service_key.created") and (AuditEvents.subjectId eq forever.metadata.id) }.count() })
        // Accepted well past any 90-day lifetime: move the key's creation time back ten years; only the expiry decides.
        a.query { Tokens.update({ Tokens.id eq forever.metadata.id }) { it[createdAt] = nowMillis() - 10 * 365 * 86_400_000L } }
        val actor = SqlTeamService(b).authenticate(forever.accessToken)!!
        assertEquals(setOf(ServiceScope.SKILLS_PUBLISH), actor.serviceScopes)
        // A regular 90-day key is still bounded, and members and keys created for members cannot be unlimited.
        failure("invalid_service_key") { keys.create(admin, admin.member.id, "Too long", setOf(ServiceScope.INFERENCE), 91) }
        failure("invalid_service_key") { keys.create(admin, colleague.member.id, "Member unlimited", setOf(ServiceScope.INFERENCE), null) }
        failure("invalid_service_key") { keys.create(colleague, colleague.member.id, "Self unlimited", setOf(ServiceScope.INFERENCE), null) }
        val bounded = keys.create(colleague, colleague.member.id, "Member bounded", setOf(ServiceScope.INFERENCE), 90)
        assertFalse(bounded.metadata.neverExpires)
        // Revocation works the same, across replicas.
        SqlServiceKeyService(a).revoke(admin, forever.metadata.id)
        assertNull(SqlTeamService(a).authenticate(forever.accessToken))
        assertTrue(keys.list(admin).none { it.id == forever.metadata.id })
        keys.revoke(colleague, bounded.metadata.id)
    }
    private suspend fun verifyCreationLimit(a: Database, b: Database, password: String) = coroutineScope {
        val admin = SqlTeamService(a).login("admin", password, "Limits").principal
        val keys = SqlServiceKeyService(a)
        val count = keys.list(admin).size
        repeat(19 - count) { keys.create(admin, admin.member.id, "Synthetic CI", setOf(ServiceScope.INFERENCE), 1) }
        val attempts = listOf(keys, SqlServiceKeyService(b)).map { store -> async {
            try { store.create(admin, admin.member.id, "Last slot", setOf(ServiceScope.INFERENCE), 1); true }
            catch (error: TeamFailure) { assertEquals("service_key_limit", error.code); false }
        } }.awaitAll()
        assertEquals(1, attempts.count { it })
        assertEquals(20, keys.list(admin).size)
        keys.revoke(admin, keys.list(admin).first().id)
        keys.create(admin, admin.member.id, "Reclaimed slot", setOf(ServiceScope.USAGE_READ), 90)
        assertEquals(20, keys.list(admin).size)
    }
    private suspend fun failure(code: String, block: suspend () -> Unit) = assertEquals(code, assertFailsWith<TeamFailure> { block() }.code)
}
