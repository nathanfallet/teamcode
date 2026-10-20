package me.nathanfallet.teamcode.infrastructure.cloud

import kotlinx.coroutines.*
import me.nathanfallet.teamcode.domain.cloud.*
import me.nathanfallet.teamcode.domain.cloud.CloudSessionState.*
import me.nathanfallet.teamcode.domain.team.*
import me.nathanfallet.teamcode.infrastructure.database.*
import me.nathanfallet.teamcode.infrastructure.database.Database
import me.nathanfallet.teamcode.infrastructure.database.tables.*
import me.nathanfallet.teamcode.infrastructure.secrets.CredentialCipher
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*
import org.testcontainers.mysql.MySQLContainer
import kotlin.test.*

/** The initial message given at creation and the idle pause (attachment leases included) on real MySQL with two replicas. */
class CloudIdlePersistenceTest {
    private class Replica(val database: Database, val cluster: FakeClusterPort, cipher: CredentialCipher) {
        val store = SqlCloudSessionStore(database)
        val conversations = SqlCloudConversationStore(database)
        val access = CloudAccessService(conversations, store)
        val admin = CloudAdministrationService(SqlCloudAdminStore(database, cipher), cluster)
        val conversation = CloudConversationService(conversations, store, access, sleep = { delay(1) })
        val sessions = CloudSessionService(store, access = access, initialMessages = conversation)
        val reconciler = ReconcileCloudSessionsUseCase(store, cluster, activity = conversations)
        val runner = RunnerChannelUseCase(store)
        val agent = RunnerConversationUseCase(store, conversations, sleep = { delay(1) })
    }

    @Test fun initialMessageAndIdlePause() = runBlocking {
        MySQLContainer("mysql:8.4").withDatabaseName("teamcode").withTmpFs(mapOf("/var/lib/mysql" to "rw,nosuid,size=1g")).use { container ->
            container.start()
            Database(container.jdbcUrl, container.username, container.password).use { a ->
            Database(container.jdbcUrl, container.username, container.password).use { b ->
                coroutineScope { listOf(a, b).map { async(Dispatchers.IO) { it.migrate() } }.awaitAll() }
                val cluster = FakeClusterPort()
                val cipher = CredentialCipher(ByteArray(32) { 53 })
                val ra = Replica(a, cluster, cipher); val rb = Replica(b, cluster, cipher)
                val (admin, member) = members(a)
                val cloud = ra.admin.saveCluster(admin, null, null, CloudClusterInput("lab", "https://k8s.example.test:6443",
                    "-----BEGIN CERTIFICATE-----\nMIIBsynthetic\n-----END CERTIFICATE-----\n", "synthetic-service-account-token-0123456789", listOf("teamcode"), 10, "standard"))
                val profile = ra.admin.saveProfile(admin, null, 0, "toolchain",
                    CloudProfileSpec(cloud.id, "teamcode", "registry.example/toolchain:2026-10", idleTimeoutMinutes = 10, minIdleTimeoutMinutes = 5, maxIdleTimeoutMinutes = 60), setOf(member.member.id))
                val teamId = admin.teamId

                // The initial message is queued with the session; a replayed creation queues nothing more.
                failure("invalid_message") { ra.sessions.create(member, null, draft(profile, "\u0000"), key("bad")) }
                val created = (ra.sessions.create(member, null, draft(profile, "Fix the relist retry"), key("create-1")) as CloudCreateOutcome.Created).session
                assertIs<CloudCreateOutcome.Replayed>(rb.sessions.create(member, null, draft(profile, "Fix the relist retry"), key("create-1")))
                val queued = ra.conversations.inputs(created.id, null, 10).inputs
                assertEquals(listOf("Fix the relist retry"), queued.map { it.text })
                assertEquals(member.member.id, queued.single().authorId)
                assertEquals(5, created.idleTimeoutMinutes, "The member's choice is clamped to the administrator's bounds")
                // An administrator creating on behalf of a member writes the message themselves.
                val delegated = (ra.sessions.create(admin, member.member.id, draft(profile, "On behalf"), key("create-2")) as CloudCreateOutcome.Created).session
                assertEquals(admin.member.id, ra.conversations.inputs(delegated.id, null, 10).inputs.single().authorId)
                ra.sessions.delete(admin, null, delegated.id)

                // Delivered once the session is ready, never before.
                pass(ra); pass(ra)
                val pod = CloudSessionService.podName(created.id, 1)
                cluster.start(pod); pass(ra)
                ra.runner.register(teamId, created.id, 1, pod)
                assertEquals(emptyList(), rb.agent.inputs(teamId, created.id, 1, 0), "Nothing is delivered while preparing")
                rb.runner.heartbeat(teamId, created.id, 1, ready = true)
                val delivered = rb.agent.inputs(teamId, created.id, 1, 0)
                assertEquals(listOf(queued.single().id), delivered.map { it.id })

                // A waiting input keeps the session running past its idle timeout.
                idleFor(a, created.id, 6)
                pass(rb)
                assertEquals(DesiredState.RUNNING, ra.store.find(teamId, created.id)!!.desiredState, "A delivered, unconsumed message is activity")
                ra.agent.acknowledge(teamId, created.id, 1, listOf(RunnerInputAck(delivered.single().id, true, null)))

                // A live attachment lease keeps it running; the end of the lease counts as the last activity.
                val lease = ra.conversation.attach(member, created.id)
                idleFor(a, created.id, 6)
                pass(rb)
                assertEquals(DesiredState.RUNNING, ra.store.find(teamId, created.id)!!.desiredState, "A member attached keeps the session running")
                ra.conversation.detach(member, created.id, lease.id)
                failure("attachment_expired") { ra.conversation.renew(member, created.id, lease.id) }
                pass(rb)
                assertEquals(DesiredState.RUNNING, ra.store.find(teamId, created.id)!!.desiredState, "Idle time counts from the detach")
                a.query { CloudSessionAttachments.update({ CloudSessionAttachments.sessionId eq created.id }) { it[leaseUntil] = nowMillis() - 6 * 60_000L } }

                // An input stuck on a broken runner counts once, from when it was queued, not forever.
                val stuck = (ra.conversation.send(member, created.id, InputRequest("Never acknowledged", true, null, null, null)) as InputOutcome.Accepted).input
                a.query { CloudSessionInputs.update({ CloudSessionInputs.id eq stuck.id }) { it[createdAt] = nowMillis() - 6 * 60_000L } }

                // Idle: an ordinary pause, journaled, then a message wakes the session again.
                pass(rb)
                val paused = ra.store.find(teamId, created.id)!!
                assertEquals(DesiredState.PAUSED, paused.desiredState)
                assertFalse(paused.pauseForce)
                assertEquals(1L, a.query { AuditEvents.selectAll().where { (AuditEvents.action eq "cloud.session.idle_paused") and (AuditEvents.subjectId eq created.id) }.count() })
                pass(ra)
                assertEquals(PAUSING, ra.store.find(teamId, created.id)!!.state)
                rb.runner.checkpoint(teamId, created.id, 1, completed = true)
                pass(rb)
                assertEquals(PAUSED to WorkspaceResume.CONSISTENT, ra.store.find(teamId, created.id)!!.let { it.state to it.resumeWorkspace })
                val wake = ra.conversation.send(member, created.id, InputRequest("Back to work", true, null, null, null))
                assertTrue((wake as InputOutcome.Accepted).input.woke)

                // Ended leases are kept for idle detection, then purged after a day, the longest idle timeout.
                assertEquals(1L, a.query { CloudSessionAttachments.selectAll().where { CloudSessionAttachments.sessionId eq created.id }.count() })
                a.query { CloudSessionAttachments.update({ CloudSessionAttachments.sessionId eq created.id }) { it[leaseUntil] = 1 } }
                rb.conversations.purgeTranscripts(100)
                assertEquals(0L, a.query { CloudSessionAttachments.selectAll().where { CloudSessionAttachments.sessionId eq created.id }.count() })
            } }
        }
    }

    private fun draft(profile: CloudProfile, message: String) = CloudSessionDraft("Idle", profile.id, null, null, null, emptyMap(), 1, initialMessage = message)
    private fun key(value: String) = IdempotencyRequest("POST /cloud/sessions", value, value.padEnd(64, '0').take(64))
    private suspend fun idleFor(a: Database, id: String, minutes: Long) = a.query {
        CloudSessions.update({ CloudSessions.id eq id }) { it[lastActivityAt] = nowMillis() - minutes * 60_000L }
    }

    private suspend fun members(a: Database): Pair<Principal, Principal> {
        val team = SqlTeamService(a)
        val password = team.bootstrap()!!
        team.changePassword(team.login("admin", password, "test").principal, password, "cloud admin replacement password")
        val admin = team.login("admin", "cloud admin replacement password", "test").principal
        val created = team.createMember(admin, "cloud-member", Role.MEMBER)
        team.changePassword(team.login("cloud-member", created.temporaryPassword, "test").principal, created.temporaryPassword, "cloud member replacement password")
        return admin to team.login("cloud-member", "cloud member replacement password", "test").principal
    }

    private suspend fun makeDue(r: Replica) = r.database.query { CloudSessions.update({ CloudSessions.reconcileAfter.isNotNull() }) { it[reconcileAfter] = 0 } }
    private suspend fun pass(r: Replica) { makeDue(r); r.reconciler() }
    private suspend fun failure(code: String, block: suspend () -> Unit) = assertEquals(code, assertFailsWith<TeamFailure> { block() }.code)
}
