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

class UsageReconciliationPersistenceTest {
    @Test @Timeout(120)
    fun verifiedCorrectionsPreserveOriginalGroupsPoolsDaysAndRunsWithoutClaimingCompletion() = runBlocking {
        fixture { f ->
            val a = UsageReconciliationServiceImpl(f.a); val b = UsageReconciliationServiceImpl(f.b)
            val groups = GroupServiceImpl(f.a); val member = f.member.principal.member.id
            val original = groups.create(f.admin.principal, GroupPolicy("Original group", 1000)).summary.group.id
            groups.assign(f.admin.principal, original, member, GroupRole.MEMBER)
            val run = id(); val runs = AgentRunServiceImpl(f.a); runs.start(f.member.principal, run, "claude", "interactive")
            val reserved = f.admission.reserve(f.member.principal, AdmissionInput(100, "claude-test", "anthropic", runId = run))
            f.admission.recordProviderRequest(reserved, "req_original"); f.admission.settle(reserved, null)
            failure("forbidden") { a.pending(f.member.principal) }
            failure("forbidden") { a.get(f.member.principal, reserved.id) }
            val serviceKey = SqlServiceKeyService(f.a).create(f.admin.principal, f.admin.principal.member.id, "Owner automation", ServiceScope.entries.toSet(), 1)
            val scoped = assertNotNull(f.team.authenticate(serviceKey.accessToken))
            failure("credential_scope") { a.history(scoped) }
            failure("credential_scope") { a.reconcile(scoped, reserved.id, change(100, 40, "req_original")) }
            val pending = b.pending(f.admin.principal).requests.single()
            assertEquals(reserved.id, pending.id); assertEquals("req_original", pending.providerRequestId)
            assertEquals(100, pending.units); assertEquals("UNCERTAIN", pending.state)
            groups.remove(f.admin.principal, original, member)
            val next = groups.create(f.admin.principal, GroupPolicy("New group", 1000)).summary.group.id
            groups.assign(f.admin.principal, next, member, GroupRole.MEMBER)
            f.admission.configure(f.admin.principal, MemberPolicy(member, maxConcurrent = 4, poolId = "another"))
            val input = change(100, 40, "req_original")
            val record = b.reconcile(f.admin.principal, reserved.id, input)
            assertEquals(record, a.reconcile(f.admin.principal, reserved.id, input))
            f.admission.settle(reserved, 0, true) // A delayed callback cannot undo the verified correction.
            assertEquals(40, f.admission.capacity(f.member.principal).chargedAndReservedUnits)
            assertEquals(40, groups.get(f.admin.principal, original).summary.usage.chargedAndReservedUnits)
            assertEquals(0, groups.get(f.admin.principal, next).summary.usage.chargedAndReservedUnits)
            assertEquals(40, f.a.query { poolSpent("default", "", pending.dayNumber) })
            assertEquals(0, f.a.query { poolSpent("default", "another", pending.dayNumber) })
            assertEquals(40, runs.get(f.member.principal, run).run.chargedAndReservedUnits)
            assertEquals(0, runs.get(f.member.principal, run).run.completed); assertEquals(0, f.admission.statistics().completed)
            assertEquals(listOf(original), record.groupIds); assertEquals("", record.poolId); assertEquals(run, record.runId); assertEquals(pending.dayNumber, record.dayNumber)
            assertEquals(record, b.get(f.admin.principal, reserved.id).reconciliation)
            assertTrue(b.pending(f.admin.principal).requests.isEmpty())
            failure("usage_already_reconciled") { b.reconcile(f.admin.principal, reserved.id, input.copy(actualUnits = 60)) }
            assertEquals(1, SqlAuditService(f.b).list(f.admin.principal).events.count { it.action == "usage.reconciled" })
            f.admission.configure(f.admin.principal, MemberPolicy(member, maxConcurrent = 4))
            val underestimated = f.admission.reserve(f.member.principal, AdmissionInput(20, "claude-test", "anthropic", runId = run))
            f.admission.settle(underestimated, null)
            b.reconcile(f.admin.principal, underestimated.id, change(20, 80))
            assertEquals(120, runs.get(f.member.principal, run).run.chargedAndReservedUnits)
            assertEquals(120, f.admission.capacity(f.member.principal).chargedAndReservedUnits)
            f.team.logout(f.admin.principal)
            failure("authentication_required") { a.pending(f.admin.principal) }
        }
    }

    @Test @Timeout(120)
    fun concurrentCorrectionsAndLateSettlementHaveOneConsistentWinner() = runBlocking {
        fixture { f ->
            val run = id(); val runs = AgentRunServiceImpl(f.a); runs.start(f.member.principal, run, "claude", "interactive")
            val reserved = f.admission.reserve(f.member.principal, AdmissionInput(100, "claude-test", "anthropic", runId = run))
            f.admission.settle(reserved, null)
            val results = coroutineScope {
                val start = CompletableDeferred<Unit>()
                val pending = listOf(f.a to 40L, f.b to 60L).map { (db, actual) -> async {
                    start.await()
                    try { UsageReconciliationServiceImpl(db).reconcile(f.admin.principal, reserved.id, change(100, actual)) to null }
                    catch (error: TeamFailure) { null to error.code }
                } }
                start.complete(Unit); pending.awaitAll()
            }
            val winner = results.mapNotNull { it.first }.single()
            assertEquals(listOf("usage_already_reconciled"), results.mapNotNull { it.second })
            assertEquals(winner.actualUnits, runs.get(f.member.principal, run).run.chargedAndReservedUnits)
            val expired = f.admission.reserve(f.member.principal, AdmissionInput(100, "claude-test", "anthropic", runId = run))
            failure("usage_not_pending") { UsageReconciliationServiceImpl(f.b).reconcile(f.admin.principal, expired.id, change(100, 40)) }
            f.a.query { Reservations.update({ Reservations.id eq expired.id }) { it[expiresAt] = nowMillis() - 1 } }
            f.admission.recordProviderRequest(expired, "req_changed")
            failure("usage_changed") { UsageReconciliationServiceImpl(f.b).reconcile(f.admin.principal, expired.id, change(100, 40)) }
            coroutineScope {
                val start = CompletableDeferred<Unit>()
                val correction = async {
                    start.await()
                    try { UsageReconciliationServiceImpl(f.b).reconcile(f.admin.principal, expired.id, change(100, 40, "req_changed")); "corrected" }
                    catch (error: TeamFailure) { error.code }
                }
                val settlement = async { start.await(); f.admission.settle(expired, 30, true) }
                start.complete(Unit); settlement.await()
                val result = correction.await()
                val current = f.a.query { Reservations.selectAll().where { Reservations.id eq expired.id }.single().let { it[Reservations.state] to it[Reservations.units] } }
                if (result == "corrected") { assertEquals("RECONCILED" to 40L, current); assertEquals(0, f.admission.statistics().completed) }
                else { assertEquals("usage_not_pending", result); assertEquals("SETTLED" to 30L, current); assertEquals(1, f.admission.statistics().completed) }
                assertEquals(winner.actualUnits + current.second, runs.get(f.member.principal, run).run.chargedAndReservedUnits)
            }
        }
    }

    @Test @Timeout(120)
    fun unknownChargesOutliveDetailRetentionAndCorrectionHistoryOutlivesResolvedRows() = runBlocking {
        fixture { f ->
            val groups = GroupServiceImpl(f.a)
            val group = groups.create(f.admin.principal, GroupPolicy("Retained billing group", 1000)).summary.group.id
            groups.assign(f.admin.principal, group, f.member.principal.member.id, GroupRole.MEMBER)
            val run = id(); val runs = AgentRunServiceImpl(f.a); runs.start(f.member.principal, run, "claude", "interactive")
            val known = f.admission.reserve(f.member.principal, AdmissionInput(50, "claude-test", "anthropic", runId = run))
            f.admission.settle(known, 20, true)
            val unknown = f.admission.reserve(f.member.principal, AdmissionInput(100, "claude-test", "anthropic", runId = run))
            f.admission.recordProviderRequest(unknown, "req_retained")
            f.a.query {
                val day = nowMillis() / DAY - 91
                Reservations.update({ Reservations.runId eq run }) { it[dayNumber] = day; it[expiresAt] = nowMillis() - 1 }
                ReservationGroups.update({ ReservationGroups.groupId eq group }) { it[dayNumber] = day }
            }
            f.admission.maintain()
            val service = UsageReconciliationServiceImpl(f.b)
            val pending = service.pending(f.admin.principal).requests.single()
            assertEquals(unknown.id, pending.id); assertEquals("ACTIVE", pending.state)
            assertTrue(f.a.query { Reservations.select(Reservations.id).where { Reservations.id eq known.id }.empty() })
            assertEquals(120, runs.get(f.member.principal, run).run.chargedAndReservedUnits)
            service.reconcile(f.admin.principal, unknown.id, change(100, 40, "req_retained"))
            assertEquals(60, runs.get(f.member.principal, run).run.chargedAndReservedUnits)
            assertEquals(0, f.admission.capacity(f.member.principal).chargedAndReservedUnits, "A historical correction keeps its original day")
            assertEquals(0, groups.get(f.admin.principal, group).summary.usage.chargedAndReservedUnits)
            f.admission.maintain()
            assertTrue(f.a.query { Reservations.select(Reservations.id).where { Reservations.id eq unknown.id }.empty() })
            val record = assertNotNull(service.get(f.admin.principal, unknown.id).reconciliation)
            assertEquals(pending.dayNumber, record.dayNumber); assertEquals(40, record.actualUnits); assertEquals(listOf(group), record.groupIds)
            assertEquals(1, service.history(f.admin.principal).reconciliations.size)
            assertEquals(1, f.admission.statistics().completed)
        }
    }

    @Test @Timeout(120)
    fun pendingStateMergeAndRetainedHistoryUseStableBoundedMemberScopedPages() = runBlocking {
        fixture { f ->
            val service = UsageReconciliationServiceImpl(f.b)
            val ids = (0 until 55).map { n ->
                val reserved = f.admission.reserve(f.member.principal, 1, "claude-test")
                if (n % 2 == 0) f.admission.settle(reserved, null)
                else f.a.query { Reservations.update({ Reservations.id eq reserved.id }) { it[expiresAt] = nowMillis() - 1 } }
                reserved.id
            }.toSet()
            val first = service.pending(f.admin.principal, f.member.principal.member.id)
            assertEquals(50, first.requests.size)
            val appended = f.admission.reserve(f.member.principal, 1, "claude-test"); f.admission.settle(appended, null)
            val next = service.pending(f.admin.principal, f.member.principal.member.id, assertNotNull(first.nextBeforeId))
            assertEquals(5, next.requests.size); assertNull(next.nextBeforeId)
            assertEquals(ids, (first.requests + next.requests).map { it.id }.toSet())
            failure("invalid_usage_cursor") { service.pending(f.admin.principal, f.admin.principal.member.id, first.requests.first().id) }
            for (id in ids) service.reconcile(f.admin.principal, id, change(1, 1))
            val history = service.history(f.admin.principal)
            assertEquals(50, history.reconciliations.size)
            service.reconcile(f.admin.principal, appended.id, change(1, 1))
            val older = service.history(f.admin.principal, beforeId = assertNotNull(history.nextBeforeId))
            assertEquals(5, older.reconciliations.size); assertNull(older.nextBeforeId)
            assertEquals(ids, (history.reconciliations + older.reconciliations).map { it.id }.toSet())
            assertEquals(56, f.admission.capacity(f.member.principal).chargedAndReservedUnits)
        }
    }

    private fun change(expected: Long, actual: Long, requestId: String? = null) = UsageReconciliationChange(expected, actual, UsageEvidence.PROVIDER_RECORD,
        "Synthetic provider record", "Verified terminal usage", requestId)
    private fun id() = UUID.randomUUID().toString()
    private suspend fun failure(code: String, action: suspend () -> Any?) { assertEquals(code, assertFailsWith<TeamFailure> { action() }.code) }
    private class Fixture(val a: Database, val b: Database, val team: SqlTeamService, val admin: LoginSession, val member: LoginSession) { val admission = SqlAdmissionStore(a) }
    private suspend fun fixture(test: suspend (Fixture) -> Unit) {
        MySQLContainer("mysql:8.4").withDatabaseName("teamcode").withTmpFs(mapOf("/var/lib/mysql" to "rw,nosuid,size=1g")).use { container ->
            container.start()
            Database(container.jdbcUrl, container.username, container.password).use { a -> Database(container.jdbcUrl, container.username, container.password).use { b ->
                a.migrate(); b.migrate(); val team = SqlTeamService(a); val initial = assertNotNull(team.bootstrap())
                val pending = team.login("admin", initial, "usage-test"); team.changePassword(pending.principal, initial, "usage owner replacement password")
                val admin = team.login("admin", "usage owner replacement password", "usage-test")
                val created = team.createMember(admin.principal, "developer", Role.MEMBER)
                val memberPending = team.login("developer", created.temporaryPassword, "usage-test")
                team.changePassword(memberPending.principal, created.temporaryPassword, "usage member replacement password")
                val member = team.login("developer", "usage member replacement password", "usage-test")
                val accounts = SqlAccountStore(a, CredentialCipher(ByteArray(32) { 71 }))
                val enrolled = accounts.upsert(AccountMutationActor.System, VerifiedProviderAccount(AccountIdentity("default", "anthropic", "synthetic-usage-account", "synthetic-org"), "Fixture"),
                    ProviderCredentials("synthetic-access", "synthetic-refresh", Long.MAX_VALUE), null, "usage-fixture") as EnrollmentResult.Created
                accounts.handover(AccountMutationActor.System, "default", enrolled.account.id, accessOnly = true)
                test(Fixture(a, b, team, admin, member))
            } }
        }
    }
    companion object { private const val DAY = 86_400_000L }
}
