package me.nathanfallet.teamcode.infrastructure.cloud

import kotlinx.coroutines.*
import me.nathanfallet.teamcode.domain.cloud.*
import me.nathanfallet.teamcode.domain.cloud.CloudSessionState.*
import me.nathanfallet.teamcode.domain.team.*
import me.nathanfallet.teamcode.infrastructure.database.*
import me.nathanfallet.teamcode.infrastructure.database.Database
import me.nathanfallet.teamcode.infrastructure.database.tables.*
import me.nathanfallet.teamcode.infrastructure.secrets.CredentialCipher
import me.nathanfallet.teamcode.infrastructure.secrets.Passwords
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*
import org.jetbrains.exposed.v1.migration.jdbc.MigrationUtils
import org.testcontainers.mysql.MySQLContainer
import kotlin.test.*

/** `tcex_` inference credentials of cloud executions on real MySQL with two replicas: issuance, attribution and revocation. */
class CloudInferencePersistenceTest {
    private class Replica(val database: Database, val cluster: FakeClusterPort, cipher: CredentialCipher) {
        val store = SqlCloudSessionStore(database)
        val team = SqlTeamService(database)
        val credentials = SqlCloudInferenceCredentialStore(database)
        val admin = CloudAdministrationService(SqlCloudAdminStore(database, cipher), cluster)
        val sessions = CloudSessionService(store)
        val reconciler = ReconcileCloudSessionsUseCase(store, cluster)
        val runner = RunnerChannelUseCase(store)
        val inference = RunnerInferenceUseCase(store, credentials)
    }

    @Test fun executionCredentialsFollowTheirExecution() = runBlocking {
        MySQLContainer("mysql:8.4").withDatabaseName("teamcode").withTmpFs(mapOf("/var/lib/mysql" to "rw,nosuid,size=1g")).use { container ->
            container.start()
            Database(container.jdbcUrl, container.username, container.password).use { a ->
            Database(container.jdbcUrl, container.username, container.password).use { b ->
                coroutineScope { listOf(a, b).map { async(Dispatchers.IO) { it.migrate() } }.awaitAll() }
                a.query {
                    val drift = MigrationUtils.statementsRequiredForDatabaseMigration(Tokens, CloudExecutions, CloudInferenceCredentials, withLogs = false)
                    assertEquals(emptyList(), drift, "The reviewed migration matches the Exposed tables")
                }
                val cluster = FakeClusterPort()
                val cipher = CredentialCipher(ByteArray(32) { 47 })
                val ra = Replica(a, cluster, cipher); val rb = Replica(b, cluster, cipher)
                val (admin, member) = members(a)
                val cloud = ra.admin.saveCluster(admin, null, null, CloudClusterInput("lab", "https://k8s.example.test:6443",
                    "-----BEGIN CERTIFICATE-----\nMIIBsynthetic\n-----END CERTIFICATE-----\n", "synthetic-service-account-token-0123456789", listOf("teamcode"), 10, "standard"))
                val profile = ra.admin.saveProfile(admin, null, 0, "toolchain", CloudProfileSpec(cloud.id, "teamcode", "registry.example/toolchain:2026-10"), setOf(member.member.id))
                val teamId = admin.teamId

                val created = (ra.sessions.create(member, null, CloudSessionDraft("Inference", profile.id, null, null, null, emptyMap(), null), null) as CloudCreateOutcome.Created).session
                failure("execution_fenced") { ra.inference.issue(teamId, created.id, 1) }
                pass(ra); pass(ra)
                val pod1 = CloudSessionService.podName(created.id, 1)
                cluster.start(pod1); pass(ra)
                failure("execution_fenced") { ra.inference.issue(teamId, created.id, 1) }
                ra.runner.register(teamId, created.id, 1, pod1)
                rb.runner.heartbeat(teamId, created.id, 1, ready = true)
                assertEquals(READY, ra.store.find(teamId, created.id)!!.state)

                // Issued to the registered runner of the current epoch, for the member the execution runs for.
                val first = rb.inference.issue(teamId, created.id, 1)
                assertTrue(first.token.startsWith("tcex_")); assertFalse(first.toString().contains(first.token))
                val principal = assertNotNull(ra.team.authenticate(first.token))
                assertEquals(member.member.id, principal.member.id)
                assertEquals(setOf(ServiceScope.INFERENCE, ServiceScope.MCP, ServiceScope.SKILLS_READ, ServiceScope.HOOKS_READ), principal.serviceScopes)
                // The owner's inference, MCP gateway and skills; nothing that publishes, manages or reads usage or cloud sessions.
                for (action in listOf(CredentialAction.INFERENCE, CredentialAction.MCP, CredentialAction.SKILLS_READ, CredentialAction.HOOKS_READ)) AuthorizeCredentialUseCase().check(principal, action)
                failure("credential_scope") { AuthorizeCredentialUseCase().check(principal, CredentialAction.SKILLS_PUBLISH) }
                failure("credential_scope") { AuthorizeCredentialUseCase().check(principal, CredentialAction.INTERACTIVE) }
                failure("credential_scope") { AuthorizeCredentialUseCase().check(principal, CredentialAction.USAGE_READ) }
                ra.store.revalidate(principal) // The gateway's own recheck on every request and on its cancellation polls.
                failure("execution_fenced") { ra.inference.issue(teamId, created.id, 2) }
                assertEquals(emptyList(), SqlServiceKeyService(a).list(member).map { it.id }, "Execution credentials are not service keys")
                assertNull(a.query { Tokens.selectAll().where { Tokens.tokenHash eq first.token }.singleOrNull() }, "Only the digest is stored")

                // Renewal keeps the current credential and the previous one only.
                val second = ra.inference.issue(teamId, created.id, 1)
                val third = rb.inference.issue(teamId, created.id, 1)
                assertNull(ra.team.authenticate(first.token), "The oldest credential is gone after two renewals")
                assertNotNull(ra.team.authenticate(second.token)); assertNotNull(ra.team.authenticate(third.token))

                // A pause: the draining execution keeps inference to finish its turn, and loses it with its pod.
                ra.sessions.pause(member, null, created.id, force = false)
                pass(ra)
                assertEquals(PAUSING, ra.store.find(teamId, created.id)!!.state)
                val draining = rb.inference.issue(teamId, created.id, 1)
                rb.runner.checkpoint(teamId, created.id, 1, completed = true)
                pass(rb)
                assertEquals(PAUSED, ra.store.find(teamId, created.id)!!.state)
                for (token in listOf(second, third, draining)) assertNull(ra.team.authenticate(token.token), "Revoked when the execution ended")
                failure("authentication_required") { ra.store.revalidate(principal) }
                failure("execution_fenced") { ra.inference.issue(teamId, created.id, 1) }
                assertEquals(0L, a.query { CloudInferenceCredentials.selectAll().count() })

                // The next epoch gets its own credentials; a lost execution revokes them too.
                ra.sessions.resume(member, null, created.id)
                val pod2 = CloudSessionService.podName(created.id, 2)
                repeat(10) { if (pod2 !in cluster.podNames()) pass(ra) }
                cluster.start(pod2); pass(ra)
                ra.runner.register(teamId, created.id, 2, pod2)
                rb.runner.heartbeat(teamId, created.id, 2, ready = true)
                val resumed = ra.inference.issue(teamId, created.id, 2)
                assertNotNull(ra.team.authenticate(resumed.token))
                a.query { CloudExecutions.update({ (CloudExecutions.sessionId eq created.id) and (CloudExecutions.epoch eq 2) }) { it[lastHeartbeatAt] = 1 } }
                repeat(5) { if (ra.store.find(teamId, created.id)!!.state != PAUSED) pass(ra) }
                assertEquals(PAUSED, ra.store.find(teamId, created.id)!!.state)
                assertNull(ra.team.authenticate(resumed.token), "A lost execution loses its credential")

                // Disabled members get none; the purge removes expired credentials and any left by an ended execution.
                ra.sessions.resume(member, null, created.id)
                val pod3 = CloudSessionService.podName(created.id, 3)
                repeat(10) { if (pod3 !in cluster.podNames()) pass(ra) }
                cluster.start(pod3); pass(ra)
                ra.runner.register(teamId, created.id, 3, pod3)
                rb.runner.heartbeat(teamId, created.id, 3, ready = true)
                val expiring = ra.inference.issue(teamId, created.id, 3)
                val live = ra.inference.issue(teamId, created.id, 3)
                a.query { CloudInferenceCredentials.update({ CloudInferenceCredentials.tokenId inSubQuery Tokens.select(Tokens.id).where { Tokens.tokenHash eq Passwords.digest(expiring.token) } }) { it[expiresAt] = 1 } }
                assertEquals(1, rb.credentials.purge(100))
                assertNull(ra.team.authenticate(expiring.token)); assertNotNull(ra.team.authenticate(live.token))
                a.query { Members.update({ Members.id eq member.member.id }) { it[disabled] = true } }
                failure("execution_fenced") { ra.inference.issue(teamId, created.id, 3) }
                assertNull(ra.team.authenticate(live.token), "A disabled member's credentials stop authenticating")
                a.query { Members.update({ Members.id eq member.member.id }) { it[disabled] = false } }

                // Deleting the session revokes at once, before the pod is gone.
                ra.sessions.delete(member, null, created.id)
                assertNull(ra.team.authenticate(live.token))
                failure("execution_fenced") { ra.inference.issue(teamId, created.id, 3) }
            } }
        }
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
