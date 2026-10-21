package me.nathanfallet.teamcode.infrastructure.database

import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import me.nathanfallet.teamcode.domain.accounts.*
import me.nathanfallet.teamcode.domain.capacity.*
import me.nathanfallet.teamcode.domain.gateway.WireProtocol
import me.nathanfallet.teamcode.domain.routing.*
import me.nathanfallet.teamcode.domain.team.*
import me.nathanfallet.teamcode.infrastructure.database.tables.*
import me.nathanfallet.teamcode.infrastructure.gateway.SessionAffinityKey
import me.nathanfallet.teamcode.infrastructure.secrets.CredentialCipher
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*
import org.junit.jupiter.api.Timeout
import org.testcontainers.mysql.MySQLContainer
import kotlin.test.*

class AgentRunPersistenceTest {
    @Test @Timeout(120)
    fun lifecycleIsIdempotentCredentialBoundAndReadScopesDoNotGrantWriteAccess() = runBlocking {
        fixture { f ->
            val a = AgentRunServiceImpl(f.a); val b = AgentRunServiceImpl(f.b); val id = id()
            val started = a.start(f.member.principal, id, "claude", "interactive")
            assertEquals(started, b.start(f.member.principal, id, "claude", "interactive"))
            failure("run_conflict") { b.start(f.admin.principal, id, "claude", "interactive") }
            failure("run_conflict") { b.start(f.member.principal, id, "codex", "interactive") }
            val anotherLogin = f.team.login("developer", MEMBER_PASSWORD, "another login")
            failure("run_credential_changed") { b.heartbeat(anotherLogin.principal, id) }
            failure("run_credential_changed") { b.end(f.admin.principal, id, 0) }
            failure("run_credential_changed") { f.admission.reserve(anotherLogin.principal, input(id, 1)) }
            val keys = SqlServiceKeyService(f.a)
            val inference = assertNotNull(f.team.authenticate(keys.create(f.member.principal, f.member.principal.member.id, "Inference", setOf(ServiceScope.INFERENCE), 1).accessToken))
            val reader = assertNotNull(f.team.authenticate(keys.create(f.member.principal, f.member.principal.member.id, "Reader", setOf(ServiceScope.USAGE_READ), 1).accessToken))
            val serviceId = id(); a.start(inference, serviceId, "codex", "background")
            failure("credential_scope") { b.list(inference) }
            failure("credential_scope") { b.start(reader, id(), "claude", "interactive") }
            assertEquals(setOf(id, serviceId), b.list(reader).runs.map { it.id }.toSet())
            val ownerReader = assertNotNull(f.team.authenticate(keys.create(f.admin.principal, f.admin.principal.member.id, "Owner reader", setOf(ServiceScope.USAGE_READ), 1).accessToken))
            failure("credential_scope") { b.list(ownerReader, all = true) }
            failure("credential_scope") { b.list(ownerReader, memberId = f.member.principal.member.id) }
            failure("credential_scope") { b.get(ownerReader, id) }
            failure("credential_scope") { f.admission.capacity(ownerReader, f.member.principal.member.id) }
            failure("credential_scope") { f.admission.quotas(ownerReader) }
            failure("credential_scope") { f.admission.poolBudgets(ownerReader) }
            // team:read: an administrator's key reads every member's launches and writes nothing; only an administrator may hold it.
            failure("invalid_service_key") { keys.create(f.member.principal, f.member.principal.member.id, "Team reader", setOf(ServiceScope.TEAM_READ), 1) }
            failure("invalid_service_key") { keys.create(f.admin.principal, f.member.principal.member.id, "Team reader", setOf(ServiceScope.TEAM_READ), 1) }
            val teamReader = assertNotNull(f.team.authenticate(keys.create(f.admin.principal, f.admin.principal.member.id, "Team reader", setOf(ServiceScope.TEAM_READ), 1).accessToken))
            assertEquals(setOf(id, serviceId), b.list(teamReader, all = true).runs.map { it.id }.toSet())
            assertEquals(setOf(id, serviceId), b.list(teamReader, memberId = f.member.principal.member.id).runs.map { it.id }.toSet())
            assertEquals(id, b.get(teamReader, id).run.id)
            assertEquals(f.member.principal.member.id, f.admission.capacity(teamReader, f.member.principal.member.id).policy.memberId)
            f.admission.quotas(teamReader); f.admission.poolBudgets(teamReader)
            failure("credential_scope") { b.start(teamReader, id(), "claude", "interactive") }
            failure("credential_scope") { b.heartbeat(teamReader, id) }
            failure("credential_scope") { b.end(teamReader, id, 0) }
            failure("credential_scope") { b.configure(teamReader, id, RunPolicy(1)) }
            failure("credential_scope") { f.admission.configure(teamReader, MemberPolicy(f.member.principal.member.id, 1, 1, null, 1, false)) }
            failure("credential_scope") { f.admission.configurePool(teamReader, PoolBudget("home", 1)) }
            failure("forbidden") { b.list(f.member.principal, all = true) }
            failure("forbidden") { b.configure(f.member.principal, id, RunPolicy(1)) }
            f.a.query { AgentRuns.update({ AgentRuns.id eq id }) { it[heartbeatAt] = nowMillis() - 90_001 } }
            assertEquals(RunState.STALE, b.get(f.member.principal, id).run.state)
            a.heartbeat(f.member.principal, id)
            assertEquals(RunState.RUNNING, b.get(f.member.principal, id).run.state)
            val reservation = f.admission.reserve(f.member.principal, input(id, 100))
            a.end(f.member.principal, id, 7); b.end(f.member.principal, id, 0)
            val ended = b.get(reader, id).run
            assertEquals(RunState.ENDED, ended.state); assertEquals(7, ended.exitCode); assertEquals(1, ended.activeRequests)
            failure("run_ended") { f.admission.reserve(f.member.principal, input(id, 1)) }
            failure("run_ended") { b.heartbeat(f.member.principal, id) }
            SqlAdmissionStore(f.b).settle(reservation, 40, true)
            assertEquals(40, b.get(reader, id).run.chargedAndReservedUnits)
            assertEquals(1, b.get(reader, id).run.completed)
            f.team.logout(inference)
            assertEquals(RunState.REVOKED, b.get(f.admin.principal, serviceId).run.state)
            assertEquals(RunState.ENDED, b.get(f.admin.principal, id).run.state)
            val events = SqlAuditService(f.b).list(f.admin.principal).events
            assertEquals(1, events.count { it.action == "run.started" && it.subjectId == id })
            assertEquals(1, events.count { it.action == "run.ended" && it.subjectId == id })
        }
    }

    @Test @Timeout(120)
    fun runBudgetsConcurrencyAndQueuedPausesRemainAtomicAndSurviveDetailedLedgerRetention() = runBlocking {
        fixture { f ->
            val a = AgentRunServiceImpl(f.a); val b = AgentRunServiceImpl(f.b); val id = id()
            a.start(f.member.principal, id, "claude", "interactive")
            b.configure(f.admin.principal, id, RunPolicy(1000, 1))
            SqlPacingSettingsStore(f.a).save(f.admin.principal, PacingSettings(true))
            suspend fun race(units: Long): List<Pair<Reservation?, String?>> = coroutineScope {
                val start = CompletableDeferred<Unit>()
                val jobs = listOf(f.a, f.b).map { db -> async {
                    start.await()
                    try { SqlAdmissionStore(db).reserve(f.member.principal, input(id, units)) to null } catch (error: TeamFailure) { null to error.code }
                } }
                start.complete(Unit); jobs.awaitAll()
            }
            val first = race(100)
            assertEquals(1, first.count { it.first != null }); assertEquals(listOf("run_concurrency"), first.mapNotNull { it.second })
            f.admission.settle(first.mapNotNull { it.first }.single(), 40, true)
            b.configure(f.admin.principal, id, RunPolicy(150, 1))
            val second = race(80)
            assertEquals(1, second.count { it.first != null }); assertEquals(listOf("run_allowance"), second.mapNotNull { it.second })
            val held = second.mapNotNull { it.first }.single()
            coroutineScope {
                val waiting = async {
                    try { AwaitAdmissionUseCase(SqlAdmissionStore(f.b)).reserve(f.member.principal, input(id, 10)); "admitted" }
                    catch (error: TeamFailure) { error.code }
                }
                withTimeout(5000) { while (b.get(f.admin.principal, id).run.queuedRequests != 1) delay(25) }
                a.configure(f.admin.principal, id, RunPolicy(150, 1, true))
                assertEquals("run_paused", withTimeout(5000) { waiting.await() })
            }
            assertEquals(0, b.get(f.member.principal, id).run.queuedRequests)
            SqlDirectiveService(f.b).update()
            assertEquals(Pace.PAUSE, SqlDirectiveService(f.a).preview(f.member.principal)!!.advice.pace)
            assertEquals(1, b.get(f.member.principal, id).run.activeRequests, "Pause never preempts active streams")
            a.configure(f.admin.principal, id, RunPolicy(120))
            f.a.query { Reservations.update({ Reservations.id eq held.id }) { it[expiresAt] = nowMillis() - 1 } }
            assertEquals(0, b.get(f.member.principal, id).run.activeRequests)
            f.admission.settle(held, null); SqlAdmissionStore(f.b).settle(held, 0)
            val current = b.get(f.member.principal, id).run
            assertEquals(120, current.chargedAndReservedUnits); assertEquals(2, current.attempts); assertEquals(1, current.completed)
            f.a.query { Reservations.update({ Reservations.runId eq id }) { it[dayNumber] = nowMillis() / DAY - 91 } }
            f.admission.maintain()
            assertEquals(listOf(held.id), f.a.query { Reservations.select(Reservations.id).where { Reservations.runId eq id }.map { it[Reservations.id] } }, "Resolved details expire; the unknown charge remains available for reconciliation")
            assertEquals(120, b.get(f.member.principal, id).run.chargedAndReservedUnits)
            failure("run_allowance") { f.admission.reserve(f.member.principal, input(id, 1)) }
        }
    }

    @Test @Timeout(120)
    fun paginationFanoutAndDeliveryMetadataAreBoundedMemberScopedAndContentFree() = runBlocking {
        fixture { f ->
            val a = AgentRunServiceImpl(f.a); val b = AgentRunServiceImpl(f.b)
            val ids = (1..55).map { id().also { a.start(f.member.principal, it, "claude", "interactive") } }.toSet()
            val first = b.list(f.member.principal)
            assertEquals(50, first.runs.size)
            val newest = id(); a.start(f.member.principal, newest, "claude", "interactive")
            val next = b.list(f.member.principal, beforeId = assertNotNull(first.nextBeforeId))
            assertEquals(5, next.runs.size); assertNull(next.nextBeforeId)
            assertEquals(ids, (first.runs + next.runs).map { it.id }.toSet())
            val other = id(); a.start(f.admin.principal, other, "codex", "interactive")
            failure("forbidden") { b.get(f.member.principal, other) }
            val targets = listOf(RouteTarget("anthropic", "claude-test"))
            fun affinity(text: String) = assertNotNull(SessionAffinityKey.from(mapOf("x-claude-code-session-id" to "native-session-private"),
                Json.parseToJsonElement("""{"messages":[{"role":"user","content":"$text"}]}""").jsonObject, WireProtocol.MESSAGES, "claude-test", targets))
            val one = affinity("private opening one"); val two = affinity("private opening two")
            assertEquals(one.sessionKey, two.sessionKey); assertNotEquals(one.conversationKey, two.conversationKey)
            val reserved = listOf(one, two).map { f.admission.reserve(f.member.principal, input(newest, 100).copy(affinity = it)) }
            reserved.forEach { f.admission.settle(it, 10, true) }
            a.configure(f.admin.principal, newest, RunPolicy(maxConcurrent = 1))
            SqlDirectiveService(f.b).update()
            assertEquals(1, SqlDirectiveService(f.a).current(f.member.principal, "native-session-private")!!.advice.maxConcurrent)
            val directive = id(); val now = f.a.query { nowMillis() }
            f.a.query {
                SessionDirectives.upsert {
                    it[memberId] = f.member.principal.member.id; it[scopeKey] = one.sessionKey; it[id] = directive; it[pace] = "SLOW_DOWN"; it[maxConcurrent] = 1
                    it[minInterval] = 10000; it[message] = "Synthetic advice"; it[createdAt] = now; it[expiresAt] = now + 180000
                }
                for (stage in listOf("RECEIVED_BY_CLI", "EMITTED_TO_HOOK")) DirectiveDeliveries.insert {
                    it[memberId] = f.member.principal.member.id; it[sessionKey] = one.sessionKey; it[DirectiveDeliveries.stage] = stage
                    it[directiveId] = if (stage == "RECEIVED_BY_CLI") directive else id(); it[claimId] = id(); it[tokenId] = f.member.principal.tokenId
                    it[claimedUntil] = now + 15000; it[emittedAt] = now; it[updatedAt] = now
                }
            }
            SqlPacingSettingsStore(f.a).save(f.admin.principal, PacingSettings(true))
            val detail = b.get(f.member.principal, newest)
            assertEquals(2, detail.conversations.size); assertTrue(detail.observationOnly)
            assertTrue(detail.conversations.all { it.pace == "SLOW_DOWN" && it.receivedByCliAtMillis == now && it.emittedToHookAtMillis == null })
            assertFalse(detail.toString().contains("native-session-private")); assertFalse(detail.toString().contains("private opening"))
            f.a.query { RunConversations.batchInsert(0 until 126) { n ->
                this[RunConversations.runId] = newest; this[RunConversations.conversationKey] = n.toString(16).padStart(64, '0'); this[RunConversations.sessionKey] = one.sessionKey
                this[RunConversations.requestedModel] = "claude-test"; this[RunConversations.provider] = "anthropic"; this[RunConversations.model] = "claude-test"; this[RunConversations.lastSeenAt] = now
            } }
            val extra = f.admission.reserve(f.member.principal, input(newest, 1).copy(affinity = affinity("third private opening")))
            f.admission.settle(extra, 0, true)
            val bounded = b.get(f.member.principal, newest)
            assertTrue(bounded.run.contextsTruncated); assertEquals(128, bounded.conversations.size)
            assertEquals(20, bounded.run.chargedAndReservedUnits); assertEquals(3, bounded.run.completed)
            SqlDirectiveService(f.b).update()
            assertTrue(SqlDirectiveService(f.a).preview(f.member.principal)!!.advice.message.contains("metadata is incomplete"))
            a.end(f.member.principal, newest, 0)
            assertTrue(b.get(f.member.principal, newest).conversations.all { it.pace == null && it.receivedByCliAtMillis == null }, "An ended wrapper must not inherit a later native-session directive")
        }
    }

    private fun input(id: String, units: Long) = AdmissionInput(units, "claude-test", "anthropic", runId = id)
    private fun id() = UUID.randomUUID().toString()
    private suspend fun failure(code: String, block: suspend () -> Any?) { assertEquals(code, assertFailsWith<TeamFailure> { block() }.code) }
    private class Fixture(val a: Database, val b: Database, val team: SqlTeamService, val admin: LoginSession, val member: LoginSession) { val admission = SqlAdmissionStore(a) }
    private suspend fun fixture(test: suspend (Fixture) -> Unit) {
        MySQLContainer("mysql:8.4").withDatabaseName("teamcode").withTmpFs(mapOf("/var/lib/mysql" to "rw,nosuid,size=1g")).use { container ->
            container.start()
            Database(container.jdbcUrl, container.username, container.password).use { a -> Database(container.jdbcUrl, container.username, container.password).use { b ->
                a.migrate(); b.migrate(); val team = SqlTeamService(a); val initial = assertNotNull(team.bootstrap())
                val pending = team.login("admin", initial, "run-test"); team.changePassword(pending.principal, initial, "run owner replacement password")
                val admin = team.login("admin", "run owner replacement password", "run-test")
                val created = team.createMember(admin.principal, "developer", Role.MEMBER)
                val memberPending = team.login("developer", created.temporaryPassword, "run-test")
                team.changePassword(memberPending.principal, created.temporaryPassword, MEMBER_PASSWORD)
                val member = team.login("developer", MEMBER_PASSWORD, "run-test")
                val store = SqlAccountStore(a, CredentialCipher(ByteArray(32) { 47 }))
                val enrolled = store.upsert(AccountMutationActor.System, VerifiedProviderAccount(AccountIdentity("default", "anthropic", "synthetic-run-account", "synthetic-org"), "Fixture"),
                    ProviderCredentials("synthetic-access", "synthetic-refresh", Long.MAX_VALUE), null, "run-fixture") as EnrollmentResult.Created
                store.handover(AccountMutationActor.System, "default", enrolled.account.id, accessOnly = true)
                test(Fixture(a, b, team, admin, member))
            } }
        }
    }
    companion object { private const val MEMBER_PASSWORD = "run member replacement password"; private const val DAY = 86_400_000L }
}
