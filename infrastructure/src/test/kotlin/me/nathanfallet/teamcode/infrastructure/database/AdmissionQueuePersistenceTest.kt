package me.nathanfallet.teamcode.infrastructure.database

import kotlinx.coroutines.*
import me.nathanfallet.teamcode.domain.accounts.*
import me.nathanfallet.teamcode.domain.capacity.*
import me.nathanfallet.teamcode.domain.gateway.WireProtocol
import me.nathanfallet.teamcode.domain.policy.ModelPolicy
import me.nathanfallet.teamcode.domain.routing.*
import me.nathanfallet.teamcode.domain.team.*
import me.nathanfallet.teamcode.infrastructure.database.tables.*
import me.nathanfallet.teamcode.infrastructure.secrets.CredentialCipher
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*
import org.testcontainers.mysql.MySQLContainer
import kotlin.test.*

class AdmissionQueuePersistenceTest {
    @Test fun replicasShareFairTurnsLeasesAndAtomicAdmission() = runBlocking {
        MySQLContainer("mysql:8.4").withDatabaseName("teamcode").withTmpFs(mapOf("/var/lib/mysql" to "rw,nosuid,size=1g")).use { container ->
            container.start()
            Database(container.jdbcUrl, container.username, container.password).use { a ->
            Database(container.jdbcUrl, container.username, container.password).use { b ->
                a.migrate(); b.migrate()
                val team = SqlTeamService(a)
                val password = team.bootstrap()!!
                val initial = team.login("admin", password, "queue test")
                team.changePassword(initial.principal, password, "queue administrator replacement")
                val admin = team.login("admin", "queue administrator replacement", "queue test").principal
                val created = team.createMember(admin, "colleague", Role.MEMBER)
                val login = team.login("colleague", created.temporaryPassword, "queue test")
                team.changePassword(login.principal, created.temporaryPassword, "queue colleague replacement")
                val colleague = team.login("colleague", "queue colleague replacement", "queue test").principal
                val accounts = SqlAccountStore(a, CredentialCipher(ByteArray(32) { 37 }))
                val enrolled = accounts.upsert(AccountMutationActor.System, VerifiedProviderAccount(AccountIdentity("default", "anthropic", "queue-test", "org"), "Queue account"), ProviderCredentials("synthetic", "synthetic-refresh", Long.MAX_VALUE), null, "queue-enrollment") as EnrollmentResult.Created
                accounts.handover(AccountMutationActor.System, "default", enrolled.account.id, accessOnly = true)
                val first = SqlAdmissionStore(a)
                val second = SqlAdmissionStore(b)
                first.configure(admin, MemberPolicy(admin.member.id, maxConcurrent = 4))
                val input = AdmissionInput(100, "claude-test", "anthropic")
                suspend fun failure(code: String, block: suspend () -> Unit) = assertEquals(code, assertFailsWith<TeamFailure> { block() }.code)
                suspend fun pending() = a.query { AdmissionWaiters.selectAll().count() }
                suspend fun waitForPending() = withTimeout(5_000) { while (pending() == 0L) delay(10) }

                val old = first.enqueue(admin, input)
                val newer = second.enqueue(admin, input)
                val other = second.enqueue(colleague, input)
                a.query { AdmissionWaiters.update({ AdmissionWaiters.id eq old.id }) { it[createdAt] = nowMillis() - 1_000 } }
                assertEquals(0, first.capacity(admin).chargedAndReservedUnits)
                assertEquals(2, second.capacity(admin).queuedRequests)
                assertEquals(3, first.statistics().queued)
                val served = second.reserveQueued(admin, old, input)
                first.settle(served, 0)
                failure("queue_turn") { first.reserveQueued(admin, newer, input) }
                val otherServed = first.reserveQueued(colleague, other, input)
                second.settle(otherServed, 0)
                first.settle(second.reserveQueued(admin, newer, input), 0)
                assertEquals(0, pending())

                val background = first.enqueue(colleague, input.copy(workload = Workload.BACKGROUND))
                val interactive = second.enqueue(admin, input)
                failure("queue_turn") { second.reserveQueued(colleague, background, input) }
                first.settle(first.reserveQueued(admin, interactive, input), 0)
                val another = second.enqueue(admin, input)
                a.query { AdmissionWaiters.update({ AdmissionWaiters.id eq background.id }) { it[createdAt] = nowMillis() - 30_001 } }
                failure("queue_turn") { second.reserveQueued(admin, another, input) }
                first.settle(second.reserveQueued(colleague, background, input), 0)
                first.release(admin, another)

                val unrelated = first.enqueue(admin, input.copy(provider = "codex"))
                val sameProvider = second.enqueue(colleague, input)
                first.settle(second.reserveQueued(colleague, sameProvider, input), 0)
                first.release(admin, unrelated)
                val protectedAccount = accounts.upsert(AccountMutationActor.System, VerifiedProviderAccount(AccountIdentity("default", "anthropic", "protected-test", "org"), "Protected account"), ProviderCredentials("synthetic-protected", "synthetic-refresh", Long.MAX_VALUE), null, "protected-enrollment") as EnrollmentResult.Created
                accounts.handover(AccountMutationActor.System, "default", protectedAccount.account.id, accessOnly = true)
                accounts.configure(AccountMutationActor.System, "default", protectedAccount.account.id, "Protected account", false, 0, "protected")
                first.configure(admin, MemberPolicy(colleague.member.id, poolId = "protected"))
                val shared = first.enqueue(admin, input)
                val protectedTicket = second.enqueue(colleague, input)
                val isolated = second.reserveQueued(colleague, protectedTicket, input)
                assertEquals(protectedAccount.account.id, isolated.account.id, "Another pool's pending work cannot take or block a protected slot")
                first.settle(isolated, 0); first.release(admin, shared)
                first.configure(admin, MemberPolicy(colleague.member.id))

                val raced = first.enqueue(admin, input)
                val outcomes = coroutineScope { listOf(first, second).map { store -> async {
                    try { store.reserveQueued(admin, raced, input) to null } catch (error: TeamFailure) { null to error.code }
                } }.awaitAll() }
                val reservation = outcomes.mapNotNull { it.first }.single()
                assertEquals(listOf("queue_expired"), outcomes.mapNotNull { it.second })
                second.settle(reservation, 0)

                val abandoned = first.enqueue(admin, input)
                a.query { AdmissionWaiters.update({ AdmissionWaiters.id eq abandoned.id }) { it[expiresAt] = 0 } }
                failure("queue_expired") { second.reserveQueued(admin, abandoned, input) }
                val bounded = (1..8).map { second.enqueue(admin, input) }
                assertEquals(8, pending(), "Expired crash metadata is removed before counting the queue")
                failure("queue_full") { first.enqueue(admin, input) }
                bounded.forEach { first.release(admin, it) }
                // A member without a concurrency limit is bounded by the team queue only, not by the member cap of 8.
                val unlimited = (1..9).map { second.enqueue(colleague, input) }
                assertEquals(9, pending())
                unlimited.forEach { first.release(colleague, it) }

                // Accounts have no parallelism limit: the colleague waits on their own member limit instead.
                first.configure(admin, MemberPolicy(colleague.member.id, maxConcurrent = 1))
                val held = first.reserve(colleague, 100, "claude-test")
                val waiting = async { AwaitAdmissionUseCase(second).reserve(colleague, input) }
                waitForPending()
                assertFalse(waiting.isCompleted)
                first.settle(held, 0)
                val resumed = withTimeout(5_000) { waiting.await() }
                assertEquals(colleague.member.id, a.query { Reservations.select(Reservations.memberId).where { Reservations.id eq resumed.id }.single()[Reservations.memberId] })
                first.settle(resumed, 0)
                first.configure(admin, MemberPolicy(colleague.member.id))

                first.configure(admin, MemberPolicy(admin.member.id, maxConcurrent = 1))
                val busy = first.reserve(admin, 100, "claude-test")
                val cancelled = launch { AwaitAdmissionUseCase(second).reserve(admin, input) }
                waitForPending(); cancelled.cancelAndJoin()
                assertEquals(0, pending())
                assertEquals(100, first.capacity(admin).chargedAndReservedUnits)
                first.settle(busy, 0)

                val context = AdmissionContext("claude-test", WireProtocol.MESSAGES, listOf(RouteTarget("anthropic")), null, null, false, null)
                val guarded = input.copy(context = context)
                val policyTicket = first.enqueue(admin, guarded)
                SqlModelPolicyStore(b).save(admin, ModelPolicy(deniedModels = listOf("claude-*")))
                failure("model_not_allowed") { second.reserveQueued(admin, policyTicket, guarded) }
                first.release(admin, policyTicket)
                SqlModelPolicyStore(b).save(admin, ModelPolicy())
                val routeTicket = first.enqueue(admin, guarded)
                SqlRoutingStore(b).save(admin, RouteRule("changed", "claude-*", listOf(RouteTarget("codex", "gpt-test"))))
                failure("route_changed") { first.reserveQueued(admin, routeTicket, guarded) }
                second.release(admin, routeTicket)
                verifyTranslationFlag(a, b, accounts, admin, first, second)
                val revoked = first.enqueue(colleague, input)
                team.logout(colleague)
                failure("authentication_required") { second.reserveQueued(colleague, revoked, input) }
                second.release(colleague, revoked)
                assertEquals(0, pending())
                assertEquals(0, first.capacity(admin).chargedAndReservedUnits)
            } }
        }
    }
    private suspend fun verifyTranslationFlag(a: Database, b: Database, accounts: SqlAccountStore, admin: Principal, first: SqlAdmissionStore, second: SqlAdmissionStore) {
    // A flag changed through another pool invalidates queued translation before spending.
    val policiesA = SqlModelPolicyStore(a)
    val policiesB = SqlModelPolicyStore(b)
    assertFalse(policiesA.get("default").experimentalCrossProviderTranslation)
    val codex = accounts.upsert(AccountMutationActor.System, VerifiedProviderAccount(AccountIdentity("default", "codex", "queue-codex", "org"), "Codex"), ProviderCredentials("synthetic-codex", "synthetic-refresh", Long.MAX_VALUE), null, "codex-enrollment") as EnrollmentResult.Created
    accounts.handover(AccountMutationActor.System, "default", codex.account.id, accessOnly = true)
    policiesB.save(admin, ModelPolicy(experimentalCrossProviderTranslation = true))
    assertTrue(policiesA.get("default").experimentalCrossProviderTranslation)
    val translated = AdmissionInput(100, "gpt-test", "codex", context = AdmissionContext(
        "claude-test", WireProtocol.MESSAGES, listOf(RouteTarget("codex", "gpt-test")), null, null, true, null))
    val pendingTranslation = first.enqueue(admin, translated)
    policiesB.save(admin, ModelPolicy())
    assertEquals("translation_disabled", assertFailsWith<TeamFailure> { first.reserveQueued(admin, pendingTranslation, translated) }.code)
    second.release(admin, pendingTranslation)
    assertEquals(0, first.capacity(admin).chargedAndReservedUnits)
    policiesB.save(admin, ModelPolicy(experimentalCrossProviderTranslation = true))
    first.settle(AwaitAdmissionUseCase(second).reserve(admin, translated), 0)
    policiesB.save(admin, ModelPolicy())
    }
}
