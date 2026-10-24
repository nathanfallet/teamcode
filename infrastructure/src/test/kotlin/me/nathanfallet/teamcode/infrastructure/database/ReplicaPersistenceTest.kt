package me.nathanfallet.teamcode.infrastructure.database

import me.nathanfallet.teamcode.infrastructure.database.tables.*
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import me.nathanfallet.teamcode.domain.accounts.*
import me.nathanfallet.teamcode.domain.routing.*
import me.nathanfallet.teamcode.domain.capacity.*
import me.nathanfallet.teamcode.domain.team.TeamFailure
import me.nathanfallet.teamcode.infrastructure.secrets.CredentialCipher
import org.testcontainers.mysql.MySQLContainer
import kotlin.test.*

class ReplicaPersistenceTest {
    @Test fun independentReplicasShareAdmissionRefreshLeasesAndAuthentication() = runBlocking {
        MySQLContainer("mysql:8.4").withDatabaseName("teamcode")
            .withTmpFs(mapOf("/var/lib/mysql" to "rw,nosuid,size=1g")).use { container ->
            container.start()
            Database(container.jdbcUrl, container.username, container.password).use { a ->
            Database(container.jdbcUrl, container.username, container.password).use { b ->
                coroutineScope { listOf(a, b).map { async(Dispatchers.IO) { it.migrate() } }.awaitAll() }
                val teamA = SqlTeamService(a)
                val teamB = SqlTeamService(b)
                val password = coroutineScope { listOf(teamA, teamB).map { async { it.bootstrap() } }.awaitAll() }.filterNotNull().single()
                val initial = teamA.login("admin", password, "replica test")
                assertNotNull(teamB.authenticate(initial.token))
                teamB.changePassword(initial.principal, password, "replica test replacement password")
                assertNull(teamA.authenticate(initial.token))
                val admin = teamA.login("admin", "replica test replacement password", "replica test")
                val routingA = SqlRoutingStore(a)
                val routingB = SqlRoutingStore(b)
                val route = routingA.save(admin.principal, RouteRule("opus", "claude-opus-*", listOf(RouteTarget("anthropic"), RouteTarget("codex", "gpt-6-astra"))))
                assertEquals(listOf(route), routingB.list("default"))
                assertTrue(routingB.list("another-team").isEmpty())
                val updated = routingB.save(admin.principal, route.copy(priority = 10))
                assertEquals("route_conflict", assertFailsWith<TeamFailure> { routingA.save(admin.principal, route) }.code)
                assertEquals("route_conflict", assertFailsWith<TeamFailure> { routingA.delete(admin.principal, route.id, route.version) }.code)
                routingA.delete(admin.principal, updated.id, updated.version)
                assertTrue(routingB.list("default").isEmpty())
                val cipher = CredentialCipher(ByteArray(32) { 17 })
                val store = SqlAccountStore(a, cipher)
                val verified = VerifiedProviderAccount(AccountIdentity("default", "anthropic", "synthetic-user", "synthetic-org"), "Replica test")
                val valid = ProviderCredentials("synthetic-access", "synthetic-refresh", System.currentTimeMillis() + 3_600_000)
                var account = (store.upsert(AccountMutationActor.System, verified, valid, null, "replica-enroll-0001") as EnrollmentResult.Created).account
                account = store.handover(AccountMutationActor.System, "default", account.id)
                val admissionA = SqlAdmissionStore(a)
                val admissionB = SqlAdmissionStore(b)
                admissionA.configure(admin.principal, MemberPolicy(admin.principal.member.id, 1000, 1))
                // Definite provider rejection releases admission without making an internal retry wait.
                a.query {
                    val c = this
                    val now = c.nowMillis()
                    Directives.insert { it[memberId] = admin.principal.member.id; it[id] = "retry-pacing"; it[pace] = "SLOW_DOWN"; it[maxConcurrent] = 1; it[minInterval] = 60000; it[message] = "Synthetic pacing test"; it[createdAt] = now; it[expiresAt] = now + 120_000 }
                }
                val rejected = admissionA.reserve(admin.principal, 100, "claude-test")
                admissionA.settle(rejected, 0, false)
                val retried = admissionB.reserve(admin.principal, 100, "claude-test")
                admissionB.settle(retried, 0, false)
                assertEquals("pool_unavailable", assertFailsWith<TeamFailure> {
                    admissionA.reserve(admin.principal, 100, "claude-test", excludedAccountIds = setOf(account.id))
                }.code)
                assertEquals(0, admissionB.capacity(admin.principal).chargedAndReservedUnits)
                a.query { Directives.deleteWhere { memberId eq admin.principal.member.id } }
                val raced = coroutineScope { (0..7).map { n -> async {
                    runCatching { (if (n % 2 == 0) admissionA else admissionB).reserve(admin.principal, 100, "claude-test") }
                } }.awaitAll() }
                assertEquals(1, raced.count { it.isSuccess })
                val reservation = raced.single { it.isSuccess }.getOrThrow()
                assertEquals(1, admissionB.statistics().active)
                assertEquals(100, admissionB.capacity(admin.principal).chargedAndReservedUnits)
                admissionB.settle(reservation, 30, true)
                admissionA.settle(reservation, 0, true)
                assertEquals(AdmissionStatistics(0, 1), admissionA.statistics())
                assertEquals(30, admissionB.capacity(admin.principal).chargedAndReservedUnits)
                val abandoned = admissionA.reserve(admin.principal, 100, "claude-test")
                a.query { Reservations.update({ Reservations.id eq abandoned.id }) { it[expiresAt] = 0 } }
                assertEquals(0, admissionB.statistics().active)
                assertEquals(130, admissionB.capacity(admin.principal).chargedAndReservedUnits) // A dead pod cannot refund spend.

                val expired = ProviderCredentials("expired-access", "rotating-refresh", 1)
                account = (store.upsert(AccountMutationActor.System, verified, expired, account.credentialVersion, "replica-expired-0001") as EnrollmentResult.Updated).account
                val renewals = AtomicInteger()
                val entered = CompletableDeferred<Unit>()
                val release = CompletableDeferred<Unit>()
                val renew: suspend (AccountRecord, ProviderCredentials) -> ProviderCredentials = { _, _ -> renewals.incrementAndGet(); entered.complete(Unit); release.await(); valid }
                val sourceA = SqlCredentialSource(a, cipher, renew)
                val sourceB = SqlCredentialSource(b, cipher, renew)
                coroutineScope {
                    val first = async { sourceA.fresh(account) }
                    entered.await()
                    assertEquals(EnrollmentResult.Conflict, store.upsert(AccountMutationActor.System, verified, valid, account.credentialVersion, "replica-race-000001"))
                    assertFailsWith<TeamFailure> { store.handover(AccountMutationActor.System, "default", account.id) }
                    val second = async { sourceB.fresh(account) }
                    release.complete(Unit)
                    assertEquals(valid.accessToken, first.await().accessToken)
                    assertEquals(valid.accessToken, second.await().accessToken)
                }
                assertEquals(1, renewals.get())
                account = store.find(verified.identity)!!
                // A 401 can reject an unexpired token. Two replicas must rotate it only once;
                // a late 401 from the old token must reuse the already committed replacement.
                val forcedCount = AtomicInteger()
                val replacement = ProviderCredentials("new-access", "new-refresh", System.currentTimeMillis() + 3_600_000)
                val forced: suspend (AccountRecord, ProviderCredentials) -> ProviderCredentials = { _, old ->
                    assertEquals(valid.accessToken, old.accessToken)
                    forcedCount.incrementAndGet(); delay(100); replacement
                }
                val forcedA = SqlCredentialSource(a, cipher, forced)
                val forcedB = SqlCredentialSource(b, cipher, forced)
                val refreshed = coroutineScope {
                    listOf(forcedA, forcedB).map { source -> async { source.fresh(account, valid.accessToken) } }.awaitAll()
                }
                assertTrue(refreshed.all { it.accessToken == replacement.accessToken })
                assertEquals(replacement.accessToken, forcedA.fresh(account, valid.accessToken).accessToken)
                assertEquals(1, forcedCount.get())
                account = store.find(verified.identity)!!

                // Model exhaustion is visible on every replica without parking other models.
                val now = a.query { nowMillis() }
                admissionA.observe(account.id, listOf(AccountQuota(account.id, "model:opus", 1.0, now, now + 3_600_000)))
                assertEquals("pool_unavailable", assertFailsWith<TeamFailure> {
                    admissionB.reserve(admin.principal, 100, "claude-opus-test")
                }.code)
                admissionA.settle(admissionB.reserve(admin.principal, 100, "claude-sonnet-test"), 0, false)
                account = (store.upsert(AccountMutationActor.System, verified, expired, account.credentialVersion, "replica-expired-0002") as EnrollmentResult.Updated).account
                // Simulate a process killed after committing refresh intent, before persisting the rotated token.
                a.query { Accounts.update({ Accounts.id eq account.id }) { it[refreshPending] = true; it[refreshStartedAt] = 0 } }
                assertEquals("provider_reconnect", assertFailsWith<TeamFailure> { sourceB.fresh(account) }.code)
                assertEquals(1, renewals.get())
                assertEquals(AccountState.RECONNECT_REQUIRED, store.find(verified.identity)!!.state)
                assertFailsWith<TeamFailure> { store.handover(AccountMutationActor.System, "default", account.id) }
                store.upsert(AccountMutationActor.System, verified, valid, account.credentialVersion, "replica-reconnect-01")
                account = store.handover(AccountMutationActor.System, "default", account.id)
                assertEquals(valid.accessToken, sourceA.fresh(account).accessToken)

                val workA = SqlScheduledWork(a)
                val workB = SqlScheduledWork(b)
                val ownerEntered = CompletableDeferred<Unit>()
                val ownerRelease = CompletableDeferred<Unit>()
                coroutineScope {
                    val owner = async { workA.runDue("maintenance") { ownerEntered.complete(Unit); ownerRelease.await() } }
                    ownerEntered.await()
                    assertFalse(workB.runDue("maintenance") { fail("Lease must be exclusive across connection pools") })
                    // Reclaim an expired lease and ensure the old owner cannot release the new lease.
                    b.query { ScheduledWork.update({ ScheduledWork.name eq "maintenance" }) { it[availableAt] = 0 } }
                    val newEntered = CompletableDeferred<Unit>()
                    val newRelease = CompletableDeferred<Unit>()
                    val successor = async { workB.runDue("maintenance") { newEntered.complete(Unit); newRelease.await() } }
                    newEntered.await()
                    ownerRelease.complete(Unit); assertTrue(owner.await())
                    assertFalse(workA.runDue("maintenance") { fail("Stale owner released successor") })
                    newRelease.complete(Unit); assertTrue(successor.await())
                }
                assertFalse(SqlScheduledWork(a).runDue("maintenance") { fail("Schedule must survive worker restart") })
                assertTrue(account.id in workB.dueAccounts())
                workA.runDue("quota/${account.id}") { }
                assertFalse(account.id in workB.dueAccounts())

                // Both API and dashboard call this same database budget per source address; pod count does not multiply it.
                val source = SqlLoginThrottle.sourceKey("203.0.113.7")
                // Avoid a legitimate fixed-window rollover while asserting exactly 30 concurrent admits.
                val elapsed = a.query { nowMillis() % 60_000 }
                if (elapsed > 50_000) delay(60_000 - elapsed + 50)
                val pools = listOf(a, b)
                val allowed = coroutineScope { (0..39).map { n -> async { pools[n % 2].query { SqlLoginThrottle.admitSource(this, source) } } }.awaitAll() }
                assertEquals(SqlLoginThrottle.SOURCE_ATTEMPTS, allowed.count { it })
                assertFalse(b.query { SqlLoginThrottle.admitSource(this, source) })
                assertTrue(a.query { SqlLoginThrottle.admitSource(this, SqlLoginThrottle.sourceKey("203.0.113.8")) }, "Another address keeps its own budget")
                teamB.logout(admin.principal)
                assertNull(teamA.authenticate(admin.token))
            } }
        }
    }
}
