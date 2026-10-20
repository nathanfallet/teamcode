package me.nathanfallet.teamcode.infrastructure.database

import java.util.UUID
import kotlinx.coroutines.*
import me.nathanfallet.teamcode.domain.accounts.*
import me.nathanfallet.teamcode.domain.capacity.*
import me.nathanfallet.teamcode.domain.team.*
import me.nathanfallet.teamcode.infrastructure.database.tables.*
import me.nathanfallet.teamcode.infrastructure.secrets.CredentialCipher
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*
import org.junit.jupiter.api.Timeout
import org.testcontainers.mysql.MySQLContainer
import kotlin.test.*

class BudgetOverridePersistenceTest {
    @Test @Timeout(120)
    fun expiryReplacementAndRevocationKeepUsageAndAnAuditableHistoryAcrossPools() = runBlocking {
        fixture { f ->
            val a = BudgetOverrideServiceImpl(f.a); val b = BudgetOverrideServiceImpl(f.b)
            val member = f.member.principal.member.id
            failure("forbidden") { a.grant(f.member.principal, member, BudgetOverrideChange(300, 60, "Denied")) }
            failure("forbidden") { a.history(f.member.principal, member) }
            val key = SqlServiceKeyService(f.a).create(f.admin.principal, f.admin.principal.member.id, "Scoped owner", ServiceScope.entries.toSet(), 1)
            val service = assertNotNull(f.team.authenticate(key.accessToken))
            failure("credential_scope") { a.grant(service, member, BudgetOverrideChange(300, 60, "Denied")) }
            failure("credential_scope") { a.history(service, member) }
            failure("not_found") { a.history(f.admin.principal, id()) }
            val first = a.grant(f.admin.principal, member, BudgetOverrideChange(300, 60, " Support incident "))
            assertEquals("Support incident", first.reason)
            val capacity = SqlAdmissionStore(f.b).capacity(f.member.principal)
            assertEquals(100, capacity.baseDailyAllowanceUnits); assertEquals(300, capacity.dailyAllowanceUnits)
            assertEquals(first.summary(), capacity.budgetOverride)
            val reservation = SqlAdmissionStore(f.b).reserve(f.member.principal, 250, "claude-test")
            f.admission.settle(reservation, 200, true)
            f.a.query { BudgetOverrides.update({ BudgetOverrides.id eq first.id }) { it[expiresAt] = nowMillis() - 1 } }
            val expired = SqlAdmissionStore(f.b).capacity(f.member.principal)
            assertEquals(200, expired.chargedAndReservedUnits); assertEquals(100, expired.dailyAllowanceUnits); assertNull(expired.budgetOverride)
            failure("member_allowance") { f.admission.reserve(f.member.principal, 1, "claude-test") }
            failure("budget_override_expired") { b.revoke(f.admin.principal, member, first.id, "Too late") }
            failure("budget_override_changed") { b.grant(f.admin.principal, member, BudgetOverrideChange(400, 60, "Stale form", first.id)) }
            val second = b.grant(f.admin.principal, member, BudgetOverrideChange(400, 60, "Continued incident"))
            val third = a.grant(f.admin.principal, member, BudgetOverrideChange(350, 60, "Reduced exception", second.id))
            failure("budget_override_changed") { b.grant(f.admin.principal, member, BudgetOverrideChange(500, 60, "Lost update", second.id)) }
            val history = b.history(f.admin.principal, member)
            assertEquals(third.id, history.activeOverrideId)
            assertEquals(second.id, history.overrides.first { it.id == third.id }.replacesId)
            val superseded = history.overrides.first { it.id == second.id }
            assertNotNull(superseded.revokedAtMillis); assertEquals(f.admin.principal.member.id, superseded.revokedBy)
            assertTrue(superseded.revocationReason!!.contains(third.id))
            a.revoke(f.admin.principal, member, second.id, "Old UI retry")
            assertEquals(third.id, b.history(f.admin.principal, member).activeOverrideId)
            val revoked = b.revoke(f.admin.principal, member, third.id, "Incident resolved")
            assertEquals(revoked, a.revoke(f.admin.principal, member, third.id, "Idempotent retry"))
            assertEquals("Incident resolved", revoked.revocationReason)
            assertNull(b.history(f.admin.principal, member).activeOverrideId)
            assertEquals(200, f.admission.capacity(f.member.principal).chargedAndReservedUnits)
            val events = SqlAuditService(f.a).list(f.admin.principal).events
            assertEquals(3, events.count { it.action == "budget_override.granted" })
            assertEquals(1, events.count { it.action == "budget_override.superseded" })
            assertEquals(1, events.count { it.action == "budget_override.revoked" })
            assertTrue(events.filter { it.action.startsWith("budget_override.") }.all { it.subjectId.startsWith("$member/") })
            f.team.logout(f.admin.principal)
            failure("authentication_required") { a.grant(f.admin.principal, member, BudgetOverrideChange(500, 60, "Revoked writer")) }
        }
    }

    @Test @Timeout(120)
    fun grantsCannotBypassGroupRunPoolOrProviderPlacementAndPoolMovesRevokeThem() = runBlocking {
        fixture { f ->
            val overrides = BudgetOverrideServiceImpl(f.a); val member = f.member.principal.member.id
            val granted = overrides.grant(f.admin.principal, member, BudgetOverrideChange(1000, 60, "Extra capacity"))
            val groups = GroupServiceImpl(f.b)
            val group = groups.create(f.admin.principal, GroupPolicy("Support cap", 75)).summary.group.id
            groups.assign(f.admin.principal, group, member, GroupRole.MEMBER)
            failure("group_allowance") { f.admission.reserve(f.member.principal, 100, "claude-test") }
            f.admission.settle(f.admission.reserve(f.member.principal, 50, "claude-test"), 50, true)
            groups.update(f.admin.principal, group, GroupPolicy("Support cap", 500))
            val runs = AgentRunServiceImpl(f.b); val run = id()
            runs.start(f.member.principal, run, "claude", "interactive"); runs.configure(f.admin.principal, run, RunPolicy(25))
            failure("run_allowance") { f.admission.reserve(f.member.principal, AdmissionInput(30, "claude-test", "anthropic", runId = run)) }
            val linked = f.admission.reserve(f.member.principal, AdmissionInput(20, "claude-test", "anthropic", runId = run))
            f.admission.settle(linked, 20, true)
            overrides.grant(f.admin.principal, f.admin.principal.member.id, BudgetOverrideChange(1000, 60, "Pool pressure fixture"))
            f.admission.settle(f.admission.reserve(f.admin.principal, 900, "claude-test"), 900, true)
            failure("pool_allowance") { f.admission.reserve(f.member.principal, 31, "claude-test") }
            f.accounts.configure(AccountMutationActor.System, "default", f.accountId, "Protected", false, 0, "protected")
            failure("pool_unavailable") { f.admission.reserve(f.member.principal, 1, "claude-test") }
            f.admission.configure(f.admin.principal, MemberPolicy(member, 100, 4, "protected"))
            val moved = SqlAdmissionStore(f.b).capacity(f.member.principal)
            assertNull(moved.budgetOverride); assertEquals(70, moved.chargedAndReservedUnits)
            assertEquals("Member pool changed.", overrides.history(f.admin.principal, member).overrides.single().revocationReason)
            f.admission.settle(f.admission.reserve(f.member.principal, 1, "claude-test"), 1)
            f.admission.configure(f.admin.principal, MemberPolicy(member, 100, 4))
            assertNull(SqlAdmissionStore(f.b).capacity(f.member.principal).budgetOverride, "Moving back cannot resurrect an exception")
            assertEquals(granted.id, overrides.history(f.admin.principal, member).overrides.single().id)
            assertTrue(SqlAuditService(f.b).list(f.admin.principal).events.any { it.action == "budget_override.pool_changed" })
        }
    }

    @Test @Timeout(120)
    fun concurrentGrantsAndSpendingAreSerializedAndHistoryPaginationIsStable() = runBlocking {
        fixture { f ->
            val member = f.member.principal.member.id
            val results = coroutineScope {
                val start = CompletableDeferred<Unit>()
                val pending = listOf(f.a, f.b).map { db -> async {
                    start.await()
                    try { BudgetOverrideServiceImpl(db).grant(f.admin.principal, member, BudgetOverrideChange(150, 60, "Concurrent grant")) to null }
                    catch (error: TeamFailure) { null to error.code }
                } }
                start.complete(Unit); pending.awaitAll()
            }
            var current = results.mapNotNull { it.first }.single()
            assertEquals(listOf("budget_override_changed"), results.mapNotNull { it.second })
            val spending = coroutineScope {
                val start = CompletableDeferred<Unit>()
                val pending = listOf(f.a, f.b).map { db -> async {
                    start.await()
                    try { SqlAdmissionStore(db).reserve(f.member.principal, 100, "claude-test") to null }
                    catch (error: TeamFailure) { null to error.code }
                } }
                start.complete(Unit); pending.awaitAll()
            }
            assertEquals(listOf("member_allowance"), spending.mapNotNull { it.second })
            f.admission.settle(spending.mapNotNull { it.first }.single(), 40, true)
            val service = BudgetOverrideServiceImpl(f.b)
            val ids = mutableSetOf(current.id)
            repeat(54) { current = service.grant(f.admin.principal, member, BudgetOverrideChange(200, 60, "Replacement $it", current.id)); ids += current.id }
            val first = service.history(f.admin.principal, member)
            assertEquals(50, first.overrides.size)
            service.grant(f.admin.principal, member, BudgetOverrideChange(300, 60, "Concurrent append", current.id))
            val next = service.history(f.admin.principal, member, assertNotNull(first.nextBeforeId))
            assertEquals(5, next.overrides.size); assertNull(next.nextBeforeId)
            assertEquals(ids, (first.overrides + next.overrides).map { it.id }.toSet())
            failure("invalid_override_cursor") { service.history(f.admin.principal, f.admin.principal.member.id, first.overrides.first().id) }
            assertEquals(40, SqlAdmissionStore(f.b).capacity(f.member.principal).chargedAndReservedUnits)
        }
    }

    @Test @Timeout(120)
    fun expiredQueuedExceptionsFailAdmissionAndPacingWithoutAWorkerOrRefund() = runBlocking {
        fixture { f ->
            val member = f.member.principal.member.id
            f.admission.configure(f.admin.principal, MemberPolicy(member, 100, 1))
            val grant = BudgetOverrideServiceImpl(f.a).grant(f.admin.principal, member, BudgetOverrideChange(300, 60, "Queued work"))
            val held = f.admission.reserve(f.member.principal, 200, "claude-test")
            coroutineScope {
                val waiting = async {
                    try { AwaitAdmissionUseCase(SqlAdmissionStore(f.b)).reserve(f.member.principal, AdmissionInput(10, "claude-test", "anthropic")); "admitted" }
                    catch (error: TeamFailure) { error.code }
                }
                withTimeout(5000) { while (SqlAdmissionStore(f.b).capacity(f.member.principal).queuedRequests != 1) delay(25) }
                f.a.query { BudgetOverrides.update({ BudgetOverrides.id eq grant.id }) { it[expiresAt] = nowMillis() - 1 } }
                f.admission.settle(held, 200, true)
                assertEquals("member_allowance", withTimeout(5000) { waiting.await() })
            }
            assertEquals(0, SqlAdmissionStore(f.b).capacity(f.member.principal).queuedRequests)
            SqlDirectiveService(f.b).update()
            assertEquals(Pace.PAUSE, assertNotNull(SqlDirectiveService(f.a).preview(f.member.principal)).advice.pace)
            val zero = BudgetOverrideServiceImpl(f.b).grant(f.admin.principal, member, BudgetOverrideChange(0, 60, "Pause admissions"))
            SqlPacingSettingsStore(f.a).save(f.admin.principal, PacingSettings(true))
            failure("member_allowance") { f.admission.reserve(f.member.principal, 1, "claude-test") }
            assertEquals(200, f.admission.capacity(f.member.principal).chargedAndReservedUnits)
            assertEquals(zero.id, f.admission.capacity(f.member.principal).budgetOverride?.id)
        }
    }

    private fun id() = UUID.randomUUID().toString()
    private suspend fun failure(code: String, block: suspend () -> Any?) { assertEquals(code, assertFailsWith<TeamFailure> { block() }.code) }
    private class Fixture(val a: Database, val b: Database, val team: SqlTeamService, val admin: LoginSession, val member: LoginSession,
        val accounts: SqlAccountStore, val accountId: String) { val admission = SqlAdmissionStore(a) }
    private suspend fun fixture(test: suspend (Fixture) -> Unit) {
        MySQLContainer("mysql:8.4").withDatabaseName("teamcode").withTmpFs(mapOf("/var/lib/mysql" to "rw,nosuid,size=1g")).use { container ->
            container.start()
            Database(container.jdbcUrl, container.username, container.password).use { a -> Database(container.jdbcUrl, container.username, container.password).use { b ->
                a.migrate(); b.migrate(); val team = SqlTeamService(a); val initial = assertNotNull(team.bootstrap())
                val pending = team.login("admin", initial, "override-test"); team.changePassword(pending.principal, initial, "override owner replacement password")
                val admin = team.login("admin", "override owner replacement password", "override-test")
                val created = team.createMember(admin.principal, "developer", Role.MEMBER)
                val memberPending = team.login("developer", created.temporaryPassword, "override-test")
                team.changePassword(memberPending.principal, created.temporaryPassword, "override member replacement password")
                val member = team.login("developer", "override member replacement password", "override-test")
                val store = SqlAccountStore(a, CredentialCipher(ByteArray(32) { 67 }))
                val enrolled = store.upsert(AccountMutationActor.System, VerifiedProviderAccount(AccountIdentity("default", "anthropic", "synthetic-override-account", "synthetic-org"), "Fixture"),
                    ProviderCredentials("synthetic-access", "synthetic-refresh", Long.MAX_VALUE), null, "override-fixture") as EnrollmentResult.Created
                store.handover(AccountMutationActor.System, "default", enrolled.account.id, accessOnly = true)
                SqlAdmissionStore(a).configurePool(admin.principal, PoolBudget("", 1000))
                SqlAdmissionStore(a).configure(admin.principal, MemberPolicy(member.principal.member.id, 100, 4))
                test(Fixture(a, b, team, admin, member, store, enrolled.account.id))
            } }
        }
    }
}
