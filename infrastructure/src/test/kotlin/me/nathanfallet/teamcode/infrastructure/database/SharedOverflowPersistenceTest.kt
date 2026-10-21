package me.nathanfallet.teamcode.infrastructure.database

import java.util.UUID
import kotlinx.coroutines.*
import me.nathanfallet.teamcode.domain.accounts.*
import me.nathanfallet.teamcode.domain.capacity.*
import me.nathanfallet.teamcode.domain.gateway.WireProtocol
import me.nathanfallet.teamcode.domain.routing.*
import me.nathanfallet.teamcode.domain.team.*
import me.nathanfallet.teamcode.infrastructure.database.tables.*
import me.nathanfallet.teamcode.infrastructure.secrets.CredentialCipher
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*
import org.junit.jupiter.api.Timeout
import org.testcontainers.mysql.MySQLContainer
import kotlin.test.*

class SharedOverflowPersistenceTest {
    @Test @Timeout(120)
    fun defaultDenyPrimaryAssignmentsRevocationAndProtectedAccountsAreEnforced() = runBlocking { fixture { f ->
        failure("pool_unavailable") { f.admission.reserve(f.member, 10, "claude-test") }
        failure("invalid_overflow_pool") { f.admission.configurePool(f.admin, PoolBudget("", 1000, true)) }
        failure("overflow_primary_pool") { f.admission.configurePool(f.admin, PoolBudget("home-a", 1000, true)) }
        failure("overflow_primary_pool") { f.admission.configure(f.admin, MemberPolicy(f.member.member.id, poolId = "reserve")) }
        failure("forbidden") { f.admission.configure(f.member, MemberPolicy(f.member.member.id, allowSharedOverflow = true)) }
        val key = SqlServiceKeyService(f.a).create(f.admin, f.admin.member.id, "No interactive policy writes", ServiceScope.entries.toSet(), 1)
        val service = assertNotNull(f.team.authenticate(key.accessToken))
        failure("credential_scope") { f.admission.configurePool(service, PoolBudget("reserve", 1000, true)) }
        f.optIn()
        val borrowed = f.admission.reserve(f.member, 10, "claude-test")
        assertEquals(f.reserve, borrowed.account.id); f.admission.settle(borrowed, 10, true)
        f.admission.configurePool(f.admin, PoolBudget("reserve", 1000, false))
        failure("pool_unavailable") { SqlAdmissionStore(f.b).reserve(f.member, 1, "claude-test") }
        f.admission.configure(f.admin, MemberPolicy(f.admin.member.id, 1000, 4, "reserve"))
        assertEquals(10, f.a.query { poolSpent("default", "reserve", nowMillis() / DAY) })
        assertEquals(10, f.admission.capacity(f.member).chargedAndReservedUnits)
        assertNotEquals(f.protected, borrowed.account.id)
    } }

    @Test @Timeout(120)
    fun homePreferenceConversationAffinityAndQuotaScopesUseTheSameEligibility() = runBlocking { fixture { f ->
        f.optIn()
        val affinity = RequestAffinity("a".repeat(64), "b".repeat(64), "claude-test", "c".repeat(64), WireProtocol.MESSAGES)
        val first = f.admission.reserve(f.member, 10, "claude-test", affinity = affinity)
        assertEquals(f.reserve, first.account.id); f.admission.settle(first, 5, true)
        val home = f.addAccount("home", "home-a")
        val pinned = SqlAdmissionStore(f.b).reserve(f.member, 10, "claude-test", affinity = affinity)
        assertEquals(f.reserve, pinned.account.id); f.admission.settle(pinned, 5, true)
        val fresh = f.admission.reserve(f.member, 10, "claude-test")
        assertEquals(home, fresh.account.id); f.admission.settle(fresh, 5, true)
        f.admission.observe(home, listOf(AccountQuota(home, "model:claude-test:weekly", .99, System.currentTimeMillis(), System.currentTimeMillis() + 60_000)))
        val fallback = f.admission.reserve(f.member, 10, "claude-test")
        assertEquals(f.reserve, fallback.account.id); f.admission.settle(fallback, 5, true)
        // A different model does not inherit this exhausted model window.
        val other = f.admission.reserve(f.member, 10, "other-model")
        assertEquals(home, other.account.id); f.admission.settle(other, 5, true)
        f.admission.configure(f.admin, MemberPolicy(f.member.member.id, 1000, 4, "home-a", allowSharedOverflow = false))
        val revokedPin = f.admission.reserve(f.member, 10, "other-model", affinity = affinity)
        assertEquals(home, revokedPin.account.id)
    } }

    @Test @Timeout(120)
    fun bothBudgetsSerializeAcrossReplicasAndCorrectionsKeepImmutableSourceAttribution() = runBlocking { fixture { f ->
        f.optIn(); f.optIn(f.admin, "home-b")
        f.admission.configurePool(f.admin, PoolBudget("reserve", 100, true))
        val results = coroutineScope {
            val start = CompletableDeferred<Unit>()
            val work = listOf(f.a to f.member, f.b to f.admin).map { (db, member) -> async {
                start.await()
                try { SqlAdmissionStore(db).reserve(member, 60, "claude-test") to null }
                catch (e: TeamFailure) { null to e.code }
            } }
            start.complete(Unit); work.awaitAll()
        }
        val held = results.mapNotNull { it.first }.single()
        assertEquals(listOf("pool_unavailable"), results.mapNotNull { it.second })
        val member = if (f.admission.capacity(f.member).chargedAndReservedUnits == 60L) f.member else f.admin
        assertEquals(60, f.a.query { poolSpent("default", "reserve", nowMillis() / DAY) })
        f.admission.recordProviderRequest(held, "req_overflow_fixture")
        f.admission.settle(held, null)
        val reviews = UsageReconciliationServiceImpl(f.b)
        assertEquals("reserve", reviews.get(f.admin, held.id).pending?.sourcePoolId)
        f.accounts.configure(AccountMutationActor.System, "default", f.reserve, "Moved account", false, 0, "elsewhere")
        val corrected = reviews.reconcile(f.admin, held.id, UsageReconciliationChange(60, 20, UsageEvidence.PROVIDER_RECORD,
            "Synthetic request record", "Verified exact charge", "req_overflow_fixture"))
        assertEquals("reserve", corrected.sourcePoolId)
        assertEquals(20, f.admission.capacity(member).chargedAndReservedUnits)
        assertEquals(20, f.a.query { poolSpent("default", "reserve", nowMillis() / DAY) })
        f.admission.settle(held, 1, true) // A late result cannot alter the final correction.
        assertEquals(20, f.admission.capacity(member).chargedAndReservedUnits)
        f.a.query { Reservations.deleteWhere { Reservations.id eq held.id } }
        assertEquals(corrected, reviews.get(f.admin, held.id).reconciliation)
    } }

    @Test @Timeout(120)
    fun memberGroupRunAndLogicalPoolCapsRemainHardWhileBorrowing() = runBlocking { fixture { f ->
        f.optIn()
        f.admission.configure(f.admin, MemberPolicy(f.member.member.id, 50, 4, "home-a", allowSharedOverflow = true))
        failure("member_allowance") { f.admission.reserve(f.member, 51, "claude-test") }
        BudgetOverrideServiceImpl(f.a).grant(f.admin, f.member.member.id, BudgetOverrideChange(1000, 60, "Test allocation"))
        val groups = GroupServiceImpl(f.b)
        val group = groups.create(f.admin, GroupPolicy("Shared reserve consumers", 25)).summary.group.id
        groups.assign(f.admin, group, f.member.member.id, GroupRole.MEMBER)
        failure("group_allowance") { f.admission.reserve(f.member, 30, "claude-test") }
        groups.update(f.admin, group, GroupPolicy("Shared reserve consumers", 1000))
        val runs = AgentRunServiceImpl(f.a); val run = UUID.randomUUID().toString()
        runs.start(f.member, run, "claude", "interactive"); runs.configure(f.admin, run, RunPolicy(10))
        failure("run_allowance") { f.admission.reserve(f.member, AdmissionInput(11, "claude-test", "anthropic", runId = run)) }
        val borrowed = f.admission.reserve(f.member, 40, "claude-test"); f.admission.settle(borrowed, 40, true)
        f.optIn(f.admin, "home-a")
        BudgetOverrideServiceImpl(f.a).grant(f.admin, f.admin.member.id, BudgetOverrideChange(1000, 60, "Consume home budget"))
        f.admission.settle(f.admission.reserve(f.admin, 960, "claude-test"), 960, true)
        failure("pool_allowance") { f.admission.reserve(f.member, 1, "claude-test") }
        assertEquals(1000, f.a.query { poolSpent("default", "reserve", nowMillis() / DAY) })
    } }

    @Test @Timeout(120)
    fun physicalSourceQueueRotatesAcrossHomePoolsAndRevocationRechecksPlacement() = runBlocking { fixture { f ->
        f.optIn(); f.optIn(f.admin, "home-b")
        val a = SqlAdmissionStore(f.a); val b = SqlAdmissionStore(f.b)
        val input = AdmissionInput(10, "claude-test", "anthropic")
        val first = a.enqueue(f.member, input)
        val second = b.enqueue(f.admin, input)
        f.a.query { AdmissionWaiters.update({ AdmissionWaiters.id eq second.id }) { it[createdAt] = nowMillis() + 1 } }
        failure("queue_turn") { b.reserveQueued(f.admin, second, input) }
        val held = a.reserveQueued(f.member, first, input)
        val third = a.enqueue(f.member, input)
        failure("queue_turn") { a.reserveQueued(f.member, third, input) }
        val next = b.reserveQueued(f.admin, second, input)
        assertEquals(f.reserve, next.account.id)
        // The busy reserve account takes the third request at once: accounts have no parallelism limit.
        val extra = a.reserveQueued(f.member, third, input)
        assertEquals(f.reserve, extra.account.id)
        a.settle(held, 5, true); b.settle(next, 5, true); a.settle(extra, 5, true)
        a.configure(f.admin, MemberPolicy(f.member.member.id, 1000, 4, "home-a"))
        val fourth = a.enqueue(f.member, input)
        failure("pool_unavailable") { a.reserveQueued(f.member, fourth, input) }; a.release(f.member, fourth)
        assertEquals(0, a.capacity(f.member).queuedRequests)
        // An ineligible member's old turn cannot block eligible work until its poll happens.
        f.optIn()
        val blocked = a.enqueue(f.member, input); a.renew(f.member, blocked, true, input)
        val ready = b.enqueue(f.admin, input)
        b.settle(b.reserveQueued(f.admin, ready, input), 0)
        a.release(f.member, blocked)
    } }

    @Test @Timeout(120)
    fun queueHintsMoveToNewHomeCapacityWithoutBlockingAnotherBorrower() = runBlocking { fixture { f ->
        f.optIn(); f.optIn(f.admin, "home-b")
        val a = f.admission; val b = SqlAdmissionStore(f.b); val input = AdmissionInput(10, "claude-test", "anthropic")
        val one = a.reserve(f.member, input); val two = b.reserve(f.admin, input)
        val earlier = a.enqueue(f.member, input); val later = b.enqueue(f.admin, input)
        val home = f.addAccount("new-home-capacity", "home-a")
        a.renew(f.member, earlier, false, input)
        f.a.query { assertEquals("home-a", AdmissionWaiters.selectAll().where { AdmissionWaiters.id eq earlier.id }.single()[AdmissionWaiters.turnPoolId]) }
        a.settle(one, 0)
        val borrowed = b.reserveQueued(f.admin, later, input)
        val returnedHome = a.reserveQueued(f.member, earlier, input)
        assertEquals(f.reserve, borrowed.account.id); assertEquals(home, returnedHome.account.id)
        a.settle(returnedHome, 0); b.settle(borrowed, 0); b.settle(two, 0)
    } }

    private class Fixture(val a: Database, val b: Database, val team: SqlTeamService, val admin: Principal, val member: Principal,
        val accounts: SqlAccountStore, val reserve: String, val protected: String) {
        val admission = SqlAdmissionStore(a)
        suspend fun optIn(actor: Principal = member, pool: String = "home-a") {
            admission.configure(admin, MemberPolicy(actor.member.id, 1000, 4, pool, allowSharedOverflow = true))
        }
        suspend fun addAccount(identity: String, pool: String): String = add(accounts, identity, pool)
    }
    private suspend fun failure(code: String, block: suspend () -> Any?) = assertEquals(code, assertFailsWith<TeamFailure> { block() }.code)
    private suspend fun fixture(test: suspend (Fixture) -> Unit) {
        MySQLContainer("mysql:8.4").withDatabaseName("teamcode").withTmpFs(mapOf("/var/lib/mysql" to "rw,nosuid,size=1g")).use { container ->
            container.start()
            Database(container.jdbcUrl, container.username, container.password).use { a -> Database(container.jdbcUrl, container.username, container.password).use { b ->
                a.migrate(); b.migrate(); val team = SqlTeamService(a); val initial = assertNotNull(team.bootstrap())
                val pending = team.login("admin", initial, "overflow-fixture"); team.changePassword(pending.principal, initial, "overflow owner replacement password")
                val admin = team.login("admin", "overflow owner replacement password", "overflow-fixture").principal
                val created = team.createMember(admin, "developer", Role.MEMBER)
                val pendingMember = team.login("developer", created.temporaryPassword, "overflow-fixture")
                team.changePassword(pendingMember.principal, created.temporaryPassword, "overflow member replacement password")
                val member = team.login("developer", "overflow member replacement password", "overflow-fixture").principal
                val store = SqlAccountStore(a, CredentialCipher(ByteArray(32) { 69 }))
                val reserve = add(store, "reserve-account", "reserve"); val protected = add(store, "protected-account", "protected")
                val admission = SqlAdmissionStore(a)
                admission.configurePool(admin, PoolBudget("reserve", 1000, true))
                admission.configurePool(admin, PoolBudget("home-a", 1000)); admission.configurePool(admin, PoolBudget("home-b", 1000))
                admission.configure(admin, MemberPolicy(member.member.id, 1000, 4, "home-a"))
                admission.configure(admin, MemberPolicy(admin.member.id, 1000, 4, "home-b"))
                test(Fixture(a, b, team, admin, member, store, reserve, protected))
            } }
        }
    }
    companion object {
        private const val DAY = 86_400_000L
        private suspend fun add(store: SqlAccountStore, identity: String, pool: String): String {
            val enrolled = store.upsert(AccountMutationActor.System, VerifiedProviderAccount(AccountIdentity("default", "anthropic", identity, "synthetic-org"), identity),
                ProviderCredentials("synthetic-access", "synthetic-refresh", Long.MAX_VALUE), null, identity) as EnrollmentResult.Created
            store.handover(AccountMutationActor.System, "default", enrolled.account.id, accessOnly = true)
            store.configure(AccountMutationActor.System, "default", enrolled.account.id, identity, false, 0, pool)
            return enrolled.account.id
        }
    }
}
