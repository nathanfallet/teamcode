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
import org.jetbrains.exposed.v1.migration.jdbc.MigrationUtils
import org.testcontainers.mysql.MySQLContainer
import kotlin.test.*

/** Two independent connection pools stand in for two server replicas sharing one MySQL database. */
class CloudSessionPersistenceTest {
    private val ca = "-----BEGIN CERTIFICATE-----\nMIIBsynthetic\n-----END CERTIFICATE-----\n"
    private val token = "synthetic-service-account-token-0123456789"

    private class Replica(val database: Database, val cluster: FakeClusterPort, cipher: CredentialCipher) {
        val store = SqlCloudSessionStore(database)
        val admin = CloudAdministrationService(SqlCloudAdminStore(database, cipher), cluster)
        val sessions = CloudSessionService(store)
        val reconciler = ReconcileCloudSessionsUseCase(store, cluster)
        /** The reconciler of a server whose cloud sessions are turned off. */
        val off = ReconcileCloudSessionsUseCase(store, cluster, startAllowed = { false })
        val runner = RunnerChannelUseCase(store)
        val logins = SqlTeamService(database)
        val authenticate = AuthenticateRunnerUseCase(store, cluster, logins = { logins.authenticate(it) })
        val garbage = CollectCloudGarbageUseCase(store, cluster)
    }

    @Test fun lifecycleIsCoordinatedAcrossReplicasWithFencingAndProvenDeletion() = runBlocking {
        MySQLContainer("mysql:8.4").withDatabaseName("teamcode").withTmpFs(mapOf("/var/lib/mysql" to "rw,nosuid,size=1g")).use { container ->
            container.start()
            Database(container.jdbcUrl, container.username, container.password).use { a ->
            Database(container.jdbcUrl, container.username, container.password).use { b ->
                coroutineScope { listOf(a, b).map { async(Dispatchers.IO) { it.migrate() } }.awaitAll() }
                a.query {
                    val drift = MigrationUtils.statementsRequiredForDatabaseMigration(CloudSettingsTable, CloudClusters, CloudProfiles, CloudProfileVersions,
                        CloudProfileAssignments, CloudSessions, CloudSessionLabels, CloudExecutions, CloudIdempotency, AuditEvents, Devices, withLogs = false)
                    assertEquals(emptyList(), drift, "The reviewed migration matches the Exposed tables")
                }
                val cluster = FakeClusterPort()
                val cipher = CredentialCipher(ByteArray(32) { 41 })
                val ra = Replica(a, cluster, cipher); val rb = Replica(b, cluster, cipher)
                val (admin, colleague) = members(a)
                val connected = administration(ra, rb, admin, colleague, cipher)
                val profile = ra.admin.profiles(admin).single()
                delegatedCreationAndIdempotency(ra, rb, admin, colleague, profile)
                cloudKeys(ra, rb, admin, colleague, profile)
                val session = lifecycle(ra, rb, admin, colleague, profile)
                concurrentResumeHasOneEpoch(ra, rb, admin, colleague, session)
                capacityIsFifoAndBounded(ra, rb, admin, colleague, profile, connected)
                deletionIsProvenAndRetainedVolumesAlert(ra, rb, admin, colleague, profile, connected)
                garbageCollection(ra, connected)
                retentionExpiryAndLimits(ra, rb, admin, colleague, profile)
                failureModesAndEarlyCheckpoint(ra, rb, admin, profile)
                environmentPreparation(ra, rb, admin, colleague, connected)
                kindsAndHosts(ra, rb, admin, colleague, profile)
                remoteLifecycle(ra, rb, admin, colleague)
            } }
        }
    }

    private suspend fun members(a: Database): Pair<Principal, Principal> {
        val team = SqlTeamService(a)
        val password = team.bootstrap()!!
        team.changePassword(team.login("admin", password, "test").principal, password, "cloud admin replacement password")
        val admin = team.login("admin", "cloud admin replacement password", "test").principal
        val created = team.createMember(admin, "cloud-colleague", Role.MEMBER)
        team.changePassword(team.login("cloud-colleague", created.temporaryPassword, "test").principal, created.temporaryPassword, "cloud colleague replacement password")
        return admin to team.login("cloud-colleague", "cloud colleague replacement password", "test").principal
    }

    private suspend fun administration(ra: Replica, rb: Replica, admin: Principal, colleague: Principal, cipher: CredentialCipher): CloudCluster {
        val input = CloudClusterInput("lab", "https://k8s.example.test:6443", ca, token, listOf("teamcode"), 1, "standard")
        failure("forbidden") { ra.admin.saveCluster(colleague, null, null, input) }
        failure("invalid_cluster") { ra.admin.saveCluster(admin, null, null, input.copy(apiUrl = "http://k8s.example.test")) }
        val cluster = ra.admin.saveCluster(admin, null, null, input)
        assertFalse(cluster.toString().contains(token))
        assertEquals(token, SqlCloudAdminStore(rb.database, cipher).connection(cluster.id)!!.token)
        val stored = ra.database.query { CloudClusters.select(CloudClusters.credential).where { CloudClusters.id eq cluster.id }.single()[CloudClusters.credential] }
        assertFalse(stored.contains(token), "The service-account credential is encrypted at rest")
        failure("cluster_conflict") { rb.admin.saveCluster(admin, cluster.id, 7, input.copy(serviceAccountToken = null)) }
        val updated = rb.admin.saveCluster(admin, cluster.id, cluster.version, input.copy(serviceAccountToken = null, caCertificate = null, maxRunningPods = 3))
        assertEquals(3, updated.maxRunningPods)
        // Pointing a cluster at another API server never reuses the stored token or CA: both must be supplied again.
        val moved = input.copy(apiUrl = "https://other.example.test:6443")
        failure("cluster_credentials_required") { rb.admin.saveCluster(admin, cluster.id, updated.version, moved.copy(serviceAccountToken = null, caCertificate = null)) }
        failure("cluster_credentials_required") { rb.admin.saveCluster(admin, cluster.id, updated.version, moved.copy(serviceAccountToken = null)) }
        failure("cluster_credentials_required") { rb.admin.saveCluster(admin, cluster.id, updated.version, moved.copy(caCertificate = null)) }
        assertEquals(token, SqlCloudAdminStore(rb.database, cipher).connection(cluster.id)!!.token)
        assertEquals("https://k8s.example.test:6443", ra.database.query { CloudClusters.select(CloudClusters.apiUrl).where { CloudClusters.id eq cluster.id }.single()[CloudClusters.apiUrl] })
        val spec = CloudProfileSpec(cluster.id, "teamcode", "registry.example/toolchain:2026-10", allowedImageOverrides = listOf("registry.example/toolchain:*"))
        failure("storage_class_retains_volumes") { ra.admin.saveProfile(admin, null, 0, "retained", spec.copy(storageClass = "keep"), setOf(colleague.member.id)) }
        failure("namespace_not_allowed") { ra.admin.saveProfile(admin, null, 0, "elsewhere", spec.copy(namespace = "default"), emptySet()) }
        val profile = ra.admin.saveProfile(admin, null, 0, "toolchain", spec, setOf(colleague.member.id))
        val second = rb.admin.saveProfile(admin, profile.id, 1, "toolchain", spec.copy(idleTimeoutMinutes = 45), setOf(colleague.member.id))
        assertEquals(2, second.version)
        failure("profile_conflict") { ra.admin.saveProfile(admin, profile.id, 1, "toolchain", spec, emptySet()) }
        assertEquals(listOf(profile.id), ra.admin.profiles(colleague).map { it.id })
        assertEquals(listOf(profile.id), rb.admin.profiles(admin, colleague.member.id).map { it.id }, "What the member can use, read by an administrator")
        failure("invalid_effective_member") { ra.admin.profiles(admin, "00000000-0000-4000-8000-000000000000") }
        failure("forbidden") { ra.admin.profiles(colleague, admin.member.id) }
        assertEquals(30, ra.store.profileVersion(admin.teamId, profile.id, 1)!!.idleTimeoutMinutes, "Published versions are immutable")
        failure("forbidden") { ra.admin.saveSettings(colleague, CloudSettings()) }
        failure("invalid_cloud_settings") { ra.admin.saveSettings(admin, CloudSettings(pausedRetentionDays = 0)) }
        assertEquals(CloudSettings(), rb.admin.settings(admin))
        return updated
    }

    private suspend fun delegatedCreationAndIdempotency(ra: Replica, rb: Replica, admin: Principal, colleague: Principal, profile: CloudProfile) {
        val draft = CloudSessionDraft("Task 1234", profile.id, null, "opus", "high", mapOf("orchestrator.task" to "1234"), null)
        val key = IdempotencyRequest("POST /cloud/sessions", "task-1234-create", "a".repeat(64))
        failure("on_behalf_forbidden") { ra.sessions.create(colleague, admin.member.id, draft, key) }
        failure("credential_scope") { ra.sessions.create(admin.copy(serviceScopes = setOf(ServiceScope.INFERENCE)), null, draft, key) }
        failure("image_not_allowed") { ra.sessions.create(admin, colleague.member.id, draft.copy(image = "evil.example/miner:latest"), key) }
        failure("profile_not_found") { ra.sessions.create(colleague, null, draft.copy(profileId = "00000000-0000-4000-8000-000000000000"), key) }
        val outcomes = coroutineScope { listOf(ra, rb).map { r -> async(Dispatchers.IO) { r.sessions.create(admin, colleague.member.id, draft, key) } }.awaitAll() }
        assertEquals(1, outcomes.count { it is CloudCreateOutcome.Created }, "Concurrent replays create one session")
        val ids = outcomes.map { (it as? CloudCreateOutcome.Created)?.session?.id ?: (it as CloudCreateOutcome.Replayed).session.id }.toSet()
        val created = ra.sessions.get(admin, ids.single())
        assertEquals(colleague.member.id, created.ownerId)
        assertEquals(Attribution(admin.member.id, admin.tokenId, colleague.member.id), created.createdBy)
        assertEquals(45, created.idleTimeoutMinutes)
        assertEquals(2, created.profileVersion)
        failure("idempotency_conflict") { rb.sessions.create(admin, colleague.member.id, draft.copy(name = "Other"), key.copy(requestDigest = "b".repeat(64))) }
        val audit = ra.database.query { AuditEvents.selectAll().where { (AuditEvents.action eq "cloud.session.created") and (AuditEvents.subjectId eq created.id) }.single() }
        assertEquals(admin.member.id, audit[AuditEvents.actorId]); assertEquals(colleague.member.id, audit[AuditEvents.effectiveMemberId]); assertEquals(admin.tokenId, audit[AuditEvents.credentialId])
        assertEquals(created.id, ra.sessions.get(colleague, created.id).id, "The effective member owns the session")
        assertEquals(listOf(created.id), ra.sessions.list(colleague, CloudSessionQuery(labels = mapOf("orchestrator.task" to "1234"))).sessions.map { it.id })
        assertTrue(ra.sessions.list(colleague, CloudSessionQuery(labels = mapOf("orchestrator.task" to "other"))).sessions.isEmpty())
        failure("forbidden") { ra.sessions.list(colleague, CloudSessionQuery(owner = "*")) }
        // Delete it right away: creation can be cancelled before any pod exists.
        rb.sessions.delete(colleague, null, created.id)
        rb.sessions.delete(colleague, null, created.id)
        drain(ra) { ra.store.find(admin.teamId, created.id)!!.workspace.deletion == DeletionState.PROVEN }
        assertEquals(TERMINATED, ra.store.find(admin.teamId, created.id)!!.state)
    }

    /** V2 keys: `cloud:*` act as their owner, `admin:cloud` (administrators' keys only) delegates; revalidated in SQL on every call. */
    private suspend fun cloudKeys(ra: Replica, rb: Replica, admin: Principal, colleague: Principal, profile: CloudProfile) {
        val keys = SqlServiceKeyService(ra.database)
        val team = SqlTeamService(rb.database)
        failure("invalid_service_key") { keys.create(colleague, colleague.member.id, "orchestrator", setOf(ServiceScope.ADMIN_CLOUD), 1) }
        failure("invalid_service_key") { keys.create(admin, colleague.member.id, "orchestrator", setOf(ServiceScope.ADMIN_CLOUD), 1) }
        val orchestratorKey = keys.create(admin, admin.member.id, "orchestrator", setOf(ServiceScope.ADMIN_CLOUD), 1)
        val orchestrator = team.authenticate(orchestratorKey.accessToken)!!
        val adminWriter = team.authenticate(keys.create(admin, admin.member.id, "admin writer", setOf(ServiceScope.CLOUD_WRITE), 1).accessToken)!!
        val reader = team.authenticate(keys.create(colleague, colleague.member.id, "reader", setOf(ServiceScope.CLOUD_READ), 1).accessToken)!!
        val draft = CloudSessionDraft("Task 5678", profile.id, null, null, null, mapOf("orchestrator.task" to "5678"), null)
        failure("on_behalf_forbidden") { rb.sessions.create(adminWriter, colleague.member.id, draft, null) }
        failure("credential_scope") { rb.sessions.create(reader, null, draft, null) }
        val created = (rb.sessions.create(orchestrator, colleague.member.id, draft, IdempotencyRequest("POST /cloud/sessions", "task-5678", "c".repeat(64))) as CloudCreateOutcome.Created).session
        assertEquals(Attribution(admin.member.id, orchestratorKey.metadata.id, colleague.member.id), created.createdBy, "Attributed to the key, never its token")
        val audit = ra.database.query { AuditEvents.selectAll().where { (AuditEvents.action eq "cloud.session.created") and (AuditEvents.subjectId eq created.id) }.single() }
        assertEquals(orchestratorKey.metadata.id, audit[AuditEvents.credentialId])
        assertEquals(created.id, ra.sessions.get(reader, created.id).id, "cloud:read sees the owner's session")
        failure("not_found") { ra.sessions.get(adminWriter, created.id) }
        failure("forbidden") { ra.sessions.list(adminWriter, CloudSessionQuery(owner = "*")) }
        assertTrue(created.id in ra.sessions.list(orchestrator, CloudSessionQuery(owner = "*")).sessions.map { it.id })
        // Revocation on one replica stops the key on the other at its next call.
        keys.revoke(admin, orchestratorKey.metadata.id)
        failure("authentication_required") { rb.sessions.get(orchestrator, created.id) }
        failure("authentication_required") { rb.sessions.delete(orchestrator, colleague.member.id, created.id) }
        rb.sessions.delete(colleague, null, created.id)
        drain(ra) { ra.store.find(admin.teamId, created.id)!!.workspace.deletion == DeletionState.PROVEN }
    }

    private suspend fun lifecycle(ra: Replica, rb: Replica, admin: Principal, colleague: Principal, profile: CloudProfile): CloudSession {
        val created = (ra.sessions.create(colleague, null, CloudSessionDraft("Interactive", profile.id, null, null, null, emptyMap(), 5), null) as CloudCreateOutcome.Created).session
        failure("session_preparing") { ra.sessions.pause(colleague, null, created.id, false) }
        makeDue(ra); ra.off()
        ra.store.find(admin.teamId, created.id)!!.let { waiting ->
            assertEquals(CREATING, waiting.state); assertEquals(0, waiting.currentEpoch, "Off: no execution is started")
            assertNull(cluster(ra).claims["teamcode/${waiting.workspace.claimName}"], "Off: no workspace claim is created")
        }
        pass(ra)
        var s = ra.store.find(admin.teamId, created.id)!!
        assertEquals(1, s.currentEpoch); assertEquals(CREATING, s.state)
        assertNotNull(cluster(ra).claims["teamcode/${s.workspace.claimName}"], "The workspace claim exists before the pod")
        pass(rb)
        val pod = CloudSessionService.podName(s.id, 1)
        assertEquals(setOf(pod), cluster(ra).podNames())
        cluster(ra).start(pod)
        pass(ra)
        s = ra.store.find(admin.teamId, s.id)!!
        assertEquals("sha256:" + "a".repeat(64), s.imageDigest)
        assertNotNull(s.workspace.volumeHandle)
        failure("execution_fenced") { ra.runner.register(admin.teamId, s.id, 2, CloudSessionService.podName(s.id, 2)) }
        failure("execution_fenced") { ra.runner.register(admin.teamId, s.id, 1, "tc-other-e1") }
        ra.runner.register(admin.teamId, s.id, 1, pod)
        assertEquals(PREPARING, ra.store.find(admin.teamId, s.id)!!.state)
        assertEquals(DesiredState.RUNNING, rb.runner.heartbeat(admin.teamId, s.id, 1, ready = true).desiredState)
        s = ra.store.find(admin.teamId, s.id)!!
        assertEquals(READY, s.state)
        val paused = rb.sessions.pause(colleague, null, s.id, false)
        assertEquals(DesiredState.PAUSED, paused.desiredState)
        assertEquals(paused.generation, ra.sessions.pause(colleague, null, s.id, false).generation, "A repeated pause writes nothing")
        pass(ra)
        assertEquals(PAUSING, ra.store.find(admin.teamId, s.id)!!.state)
        val instruction = ra.runner.heartbeat(admin.teamId, s.id, 1, ready = true)
        assertEquals(DesiredState.PAUSED, instruction.desiredState); assertNotNull(instruction.pauseDeadline)
        rb.runner.checkpoint(admin.teamId, s.id, 1, completed = true)
        pass(rb)
        s = ra.store.find(admin.teamId, s.id)!!
        assertEquals(PAUSED, s.state); assertEquals(WorkspaceResume.CONSISTENT, s.resumeWorkspace)
        assertEquals(s.pausedAt!! + 30 * 86_400_000L, s.expiresAt)
        assertTrue(cluster(ra).podNames().isEmpty(), "A paused session keeps no pod")
        assertNotNull(cluster(ra).claims["teamcode/${s.workspace.claimName}"], "A paused session keeps its workspace")
        assertEquals(ExecutionState.ENDED, ra.store.execution(s.id, 1)!!.state)
        failure("execution_fenced") { ra.runner.heartbeat(admin.teamId, s.id, 1, ready = true) }
        return s
    }

    private suspend fun concurrentResumeHasOneEpoch(ra: Replica, rb: Replica, admin: Principal, colleague: Principal, paused: CloudSession) {
        val resumed = coroutineScope { listOf(ra, rb).map { r -> async(Dispatchers.IO) { r.sessions.resume(colleague, null, paused.id) } }.awaitAll() }
        assertTrue(resumed.all { it.desiredState == DesiredState.RUNNING })
        assertEquals(1, ra.database.query { AuditEvents.selectAll().where { (AuditEvents.action eq "cloud.session.resumed") and (AuditEvents.subjectId eq paused.id) }.count() })
        makeDue(ra)
        // Two replicas compete for the same due session: only one may claim it.
        val claims = coroutineScope { listOf(ra, rb).map { r -> async(Dispatchers.IO) { r.store.claimDue("owner-" + System.identityHashCode(r), 60_000, 8) } }.awaitAll() }
        assertEquals(1, claims.count { it.isNotEmpty() })
        val owner = "owner-" + System.identityHashCode(if (claims[0].isNotEmpty()) ra else rb)
        val leased = claims.single { it.isNotEmpty() }.single()
        ra.reconciler.reconcile(leased, owner) // paused -> resuming
        // A duplicated lease (for example a replica that believes it still owns an expired lease) still starts one epoch.
        val resuming = ra.store.find(admin.teamId, paused.id)!!
        coroutineScope { listOf(ra, rb).map { r -> async(Dispatchers.IO) { r.reconciler.reconcile(resuming, owner) } }.awaitAll() }
        assertEquals(listOf(2, 1), ra.store.executions(admin.teamId, paused.id).map { it.epoch })
        assertEquals(2, ra.store.find(admin.teamId, paused.id)!!.currentEpoch)
        // Stale-owner fencing: once the lease expires and another replica claims it, the old owner cannot commit or release.
        ra.database.query { CloudSessions.update({ CloudSessions.id eq paused.id }) { it[reconcileUntil] = 0; it[reconcileAfter] = 0 } }
        val successor = rb.store.claimDue("successor", 60_000, 8).single { it.id == paused.id }
        val stale = ra.store.find(admin.teamId, paused.id)!!
        assertNull(ra.store.compareAndSet(stale, stale.copy(phase = "stale"), CloudWrite(leaseOwner = owner)), "A stale owner is fenced")
        ra.store.release(paused.id, owner, null)
        assertEquals("successor", ra.database.query { CloudSessions.select(CloudSessions.reconcileOwner).where { CloudSessions.id eq paused.id }.single()[CloudSessions.reconcileOwner] })
        rb.store.release(successor.id, "successor", 0)
        // The resumed pod starts; the epoch-1 runner is fenced, the epoch-2 runner restores the session.
        pass(rb)
        val pod = CloudSessionService.podName(paused.id, 2)
        cluster(ra).start(pod)
        failure("execution_fenced") { ra.runner.register(admin.teamId, paused.id, 1, CloudSessionService.podName(paused.id, 1)) }
        rb.runner.register(admin.teamId, paused.id, 2, pod)
        assertEquals(RESUMING, ra.store.find(admin.teamId, paused.id)!!.state)
        ra.runner.heartbeat(admin.teamId, paused.id, 2, ready = true)
        assertEquals(READY, ra.store.find(admin.teamId, paused.id)!!.state)
        // A lost execution (no heartbeat for 90 seconds) pauses the session as crash-consistent.
        ra.database.query { CloudExecutions.update({ (CloudExecutions.sessionId eq paused.id) and (CloudExecutions.epoch eq 2) }) { it[lastHeartbeatAt] = 1 } }
        pass(ra)
        val lost = ra.store.find(admin.teamId, paused.id)!!
        assertEquals(PAUSED, lost.state); assertEquals(WorkspaceResume.CRASH_CONSISTENT, lost.resumeWorkspace)
        assertEquals("execution_lost", lost.failure?.code)
        assertTrue(cluster(ra).podNames().isEmpty())
    }

    private suspend fun capacityIsFifoAndBounded(ra: Replica, rb: Replica, admin: Principal, colleague: Principal, profile: CloudProfile, connected: CloudCluster) {
        ra.admin.saveCluster(admin, connected.id, 2, CloudClusterInput(connected.name, connected.apiUrl, null, null, connected.allowedNamespaces, 1, connected.defaultStorageClass))
        val first = (ra.sessions.create(colleague, null, CloudSessionDraft("First", profile.id, null, null, null, emptyMap(), null), null) as CloudCreateOutcome.Created).session
        val second = (rb.sessions.create(admin, null, CloudSessionDraft("Second", profile.id, null, null, null, emptyMap(), null), null) as CloudCreateOutcome.Created).session
        failure("not_found") { rb.sessions.get(colleague, second.id) }
        failure("not_found") { rb.sessions.pause(colleague, null, second.id, false) }
        pass(ra); pass(rb)
        val running = listOf(first, second).map { ra.store.find(admin.teamId, it.id)!! }
        assertEquals(1, running.count { it.currentEpoch == 1 }, "maxRunningPods admits one execution")
        val waiting = running.single { it.currentEpoch == 0 }
        assertEquals("waiting_capacity", waiting.phase); assertNotNull(waiting.waitingSince)
        val started = running.single { it.currentEpoch == 1 }
        rb.sessions.delete(if (started.ownerId == admin.member.id) admin else colleague, null, started.id)
        drain(ra) { ra.store.find(admin.teamId, waiting.id)!!.currentEpoch == 1 }
        ra.sessions.delete(admin, null, waiting.id)
        drain(ra) { listOf(first, second).all { ra.store.find(admin.teamId, it.id)!!.workspace.deletion == DeletionState.PROVEN } }
        ra.admin.saveCluster(admin, connected.id, 3, CloudClusterInput(connected.name, connected.apiUrl, null, null, connected.allowedNamespaces, 10, connected.defaultStorageClass))
    }

    private suspend fun deletionIsProvenAndRetainedVolumesAlert(ra: Replica, rb: Replica, admin: Principal, colleague: Principal, profile: CloudProfile, connected: CloudCluster) {
        val s = (ra.sessions.create(colleague, null, CloudSessionDraft("Delete me", profile.id, null, null, null, emptyMap(), null), null) as CloudCreateOutcome.Created).session
        pass(ra)
        val claim = cluster(ra).claims.getValue("teamcode/${s.workspace.claimName}")
        val volume = claim.volume
        val handle = cluster(ra).volumes.getValue(volume).observation.volumeHandle
        rb.sessions.delete(colleague, null, s.id)
        drain(ra) { ra.store.find(admin.teamId, s.id)!!.workspace.deletion == DeletionState.PROVEN }
        val terminated = ra.store.find(admin.teamId, s.id)!!
        assertEquals(TERMINATED, terminated.state); assertEquals("deleted", terminated.terminatedReason)
        assertNotNull(terminated.workspace.claimDeletedAt); assertNotNull(terminated.workspace.volumeDeletedAt)
        assertEquals(handle, terminated.workspace.volumeHandle, "The provider volume identifier is kept as evidence")
        assertNull(cluster(ra).claims["teamcode/${s.workspace.claimName}"]); assertNull(cluster(ra).volumes[volume], "No provider volume remains")
        assertEquals(1, ra.database.query { AuditEvents.selectAll().where { (AuditEvents.action eq "cloud.session.workspace_deleted") and (AuditEvents.subjectId eq s.id) }.count() })
        // A volume that would be retained (the class changed to Retain after creation) blocks the proof and alerts.
        val kept = (ra.sessions.create(colleague, null, CloudSessionDraft("Kept", profile.id, null, null, null, emptyMap(), null), null) as CloudCreateOutcome.Created).session
        pass(ra)
        val keptVolume = cluster(ra).claims.getValue("teamcode/${kept.workspace.claimName}").volume
        cluster(ra).volumes[keptVolume] = cluster(ra).volumes.getValue(keptVolume).let { it.copy(observation = it.observation.copy(reclaimPolicy = "Retain")) }
        rb.sessions.delete(colleague, null, kept.id)
        drain(ra) { ra.store.find(admin.teamId, kept.id)!!.workspace.deletion == DeletionState.BLOCKED }
        assertEquals("volume_retained", ra.store.find(admin.teamId, kept.id)!!.workspace.deletionProblem?.code)
        assertEquals(1, ra.garbage(ra.store.cluster(admin.teamId, connected.id)!!).orphanedVolumes)
        assertEquals(1, ra.admin.clusters(admin).single().orphanedVolumes)
        cluster(ra).volumes.remove(keptVolume)
        ra.database.query { CloudSessions.update({ CloudSessions.id eq kept.id }) { it[reconcileAfter] = 0 } }
        drain(ra) { ra.store.find(admin.teamId, kept.id)!!.workspace.deletion == DeletionState.PROVEN }
    }

    private suspend fun garbageCollection(ra: Replica, connected: CloudCluster) {
        cluster(ra).plantOrphans(connected.id, "teamcode")
        val report = ra.garbage(ra.store.cluster(connected.teamId, connected.id)!!)
        assertEquals(1, report.deletedPods); assertEquals(1, report.deletedClaims)
        assertEquals(1, report.orphanedVolumes, "The retained provider volume is reported, the Delete one is left to its provisioner")
        assertTrue(cluster(ra).podNames().none { it == "tc-orphan-e1" })
    }

    private suspend fun retentionExpiryAndLimits(ra: Replica, rb: Replica, admin: Principal, colleague: Principal, profile: CloudProfile) {
        ra.admin.saveSettings(admin, CloudSettings(pausedRetentionDays = 1, maxRunningSessionsPerMember = 2, maxRetainedSessionsPerMember = 2))
        val lost = ra.store.list(admin.teamId, CloudSessionQuery(owner = colleague.member.id, states = setOf(PAUSED))).sessions.single()
        // Settings apply to sessions paused afterwards; expire this one explicitly.
        ra.database.query { CloudSessions.update({ CloudSessions.id eq lost.id }) { it[expiresAt] = 1; it[reconcileAfter] = 0 } }
        drain(ra) { ra.store.find(admin.teamId, lost.id)!!.workspace.deletion == DeletionState.PROVEN }
        assertEquals("expired", ra.store.find(admin.teamId, lost.id)!!.terminatedReason)
        repeat(2) { rb.sessions.create(colleague, null, CloudSessionDraft("Slot $it", profile.id, null, null, null, emptyMap(), null), null) }
        failure("cloud_session_limit") { ra.sessions.create(admin, colleague.member.id, CloudSessionDraft("Over", profile.id, null, null, null, emptyMap(), null), null) }
        assertEquals(1, ra.sessions.list(admin, CloudSessionQuery(owner = colleague.member.id, limit = 1)).sessions.size)
        val page = ra.sessions.list(admin, CloudSessionQuery(owner = "*", limit = 2))
        val next = ra.sessions.list(admin, CloudSessionQuery(owner = "*", limit = 50, beforeId = page.nextBeforeId))
        assertTrue(next.sessions.none { it.id in page.sessions.map(CloudSession::id) })
        // Includes the deleted session created by the `cloud:*` keys step.
        assertEquals(9, page.sessions.size + next.sessions.size)
    }

    private suspend fun failureModesAndEarlyCheckpoint(ra: Replica, rb: Replica, admin: Principal, profile: CloudProfile) {
        val team = admin.teamId
        suspend fun create(name: String) = (ra.sessions.create(admin, null, CloudSessionDraft(name, profile.id, null, null, null, emptyMap(), null), null) as CloudCreateOutcome.Created).session
        suspend fun ageExecution(id: String) = ra.database.query { CloudExecutions.update({ CloudExecutions.sessionId eq id }) { it[createdAt] = 1 } }
        suspend fun ready(id: String): String {
            pass(ra); pass(ra)
            val pod = CloudSessionService.podName(id, 1)
            cluster(ra).start(pod); pass(ra)
            ra.runner.register(team, id, 1, pod)
            rb.runner.heartbeat(team, id, 1, ready = true)
            assertEquals(READY, ra.store.find(team, id)!!.state)
            return pod
        }

        // A pull error is retried by the kubelet: the session keeps starting until the grace period is over.
        val pulling = create("Pulling")
        pass(ra); pass(ra)
        cluster(ra).fail(CloudSessionService.podName(pulling.id, 1), "ErrImagePull")
        pass(ra)
        assertEquals(CREATING, ra.store.find(team, pulling.id)!!.state, "A transient pull error does not fail the session")
        ageExecution(pulling.id)
        pass(ra)
        assertEquals("image_unavailable", ra.store.find(team, pulling.id)!!.failure?.code)
        drain(ra) { ra.store.find(team, pulling.id)!!.workspace.deletion == DeletionState.PROVEN }
        assertEquals(FAILED, ra.store.find(team, pulling.id)!!.state)
        // Deleting a failed session whose workspace is already gone still terminates it.
        ra.sessions.delete(admin, null, pulling.id)
        drain(ra) { ra.store.find(team, pulling.id)!!.state == TERMINATED }
        assertEquals(DeletionState.PROVEN, ra.store.find(team, pulling.id)!!.workspace.deletion)

        // The runner may checkpoint as soon as its heartbeat reports the pause, before the reconciler moves to pausing.
        val early = create("Early checkpoint")
        ready(early.id)
        rb.sessions.pause(admin, null, early.id, false)
        assertEquals(DesiredState.PAUSED, ra.runner.heartbeat(team, early.id, 1, ready = true).desiredState)
        ra.runner.checkpoint(team, early.id, 1, completed = true)
        assertEquals(PAUSING, ra.store.find(team, early.id)!!.state)
        drain(ra) { ra.store.find(team, early.id)!!.state == PAUSED }
        assertEquals(WorkspaceResume.CONSISTENT, ra.store.find(team, early.id)!!.resumeWorkspace)
        ra.sessions.delete(admin, null, early.id)

        // A resume before the checkpoint cancels the pause: the same execution goes back to ready, no new pod.
        val cancelled = create("Pause cancelled")
        val cancelledPod = ready(cancelled.id)
        rb.sessions.pause(admin, null, cancelled.id, false)
        pass(ra)
        assertEquals(PAUSING, ra.store.find(team, cancelled.id)!!.state)
        val requested = ra.store.find(team, cancelled.id)!!.pauseRequestedAt
        val resumedWhilePausing = rb.sessions.resume(admin, null, cancelled.id)
        assertEquals(DesiredState.RUNNING, resumedWhilePausing.desiredState, "Accepted at once, not 409 session_pausing")
        assertEquals(requested, resumedWhilePausing.pauseRequestedAt, "The pause keeps its deadlines until the reconciler decides")
        assertEquals(DesiredState.RUNNING, ra.runner.heartbeat(team, cancelled.id, 1, ready = true).desiredState, "The runner stops draining")
        pass(ra)
        val back = ra.store.find(team, cancelled.id)!!
        assertEquals(READY, back.state); assertNull(back.pauseRequestedAt); assertEquals(1, back.currentEpoch)
        assertEquals(setOf(cancelledPod), cluster(ra).podNames().filter { it.startsWith("tc-${cancelled.id}-") }.toSet(), "The pod kept running")
        assertEquals(1, ra.database.query { AuditEvents.selectAll().where { (AuditEvents.action eq "cloud.session.pause_cancelled") and (AuditEvents.subjectId eq cancelled.id) }.count() })
        failure("not_pausing") { ra.runner.checkpoint(team, cancelled.id, 1, completed = true) }

        // A resume after the checkpoint waits for the pause, then resumes at once into the next epoch.
        rb.sessions.pause(admin, null, cancelled.id, false)
        pass(ra)
        ra.runner.checkpoint(team, cancelled.id, 1, completed = true)
        rb.sessions.resume(admin, null, cancelled.id)
        drain(ra) { ra.store.find(team, cancelled.id)!!.currentEpoch == 2 }
        val next = ra.store.find(team, cancelled.id)!!
        assertEquals(RESUMING, next.state); assertEquals(DesiredState.RUNNING, next.desiredState)
        assertEquals(WorkspaceResume.CONSISTENT, next.resumeWorkspace, "The checkpointed pause completed before the resume")
        assertEquals("paused", ra.store.execution(cancelled.id, 1)!!.exitReason)
        ra.sessions.delete(admin, null, cancelled.id)

        // A running session without its execution row pauses crash-consistent instead of an illegal ready -> failed.
        val orphaned = create("No execution")
        ready(orphaned.id)
        ra.database.query { CloudExecutions.deleteWhere { sessionId eq orphaned.id } }
        drain(ra) { ra.store.find(team, orphaned.id)!!.state == PAUSED }
        assertTrue(cluster(ra).podNames().none { it.startsWith("tc-${orphaned.id}-") }, "Its pod is removed first")
        val paused = ra.store.find(team, orphaned.id)!!
        assertEquals(PAUSED, paused.state); assertEquals("execution_missing", paused.failure?.code); assertEquals(WorkspaceResume.CRASH_CONSISTENT, paused.resumeWorkspace)
        ra.sessions.delete(admin, null, orphaned.id)

        // Preparation that never reports ready fails after its timeout and frees the workspace.
        val preparing = create("Never ready")
        pass(ra); pass(ra)
        val pod = CloudSessionService.podName(preparing.id, 1)
        cluster(ra).start(pod); pass(ra)
        ra.runner.register(team, preparing.id, 1, pod)
        ageExecution(preparing.id)
        pass(ra)
        assertEquals("prepare_timeout", ra.store.find(team, preparing.id)!!.failure?.code)
        drain(ra) { ra.store.find(team, preparing.id)!!.workspace.deletion == DeletionState.PROVEN }

        // A namespace removed from the cluster is refused at creation and never used by a pending session.
        val pending = create("Narrowed")
        val current = ra.admin.clusters(admin).single()
        val input = CloudClusterInput(current.name, current.apiUrl, null, null, listOf("elsewhere"), current.maxRunningPods, current.defaultStorageClass)
        val narrowed = ra.admin.saveCluster(admin, current.id, current.version, input)
        failure("namespace_not_allowed") { create("Refused") }
        pass(ra)
        assertEquals("namespace_not_allowed", ra.store.find(team, pending.id)!!.failure?.code)
        assertNull(cluster(ra).claims["teamcode/${pending.workspace.claimName}"], "No object is created in a namespace no longer allowed")
        cluster(ra).plantOrphans(current.id, "teamcode")
        assertEquals(1, ra.garbage(narrowed).deletedPods, "Garbage collection still covers namespaces that hold sessions")
        ra.admin.saveCluster(admin, current.id, narrowed.version, input.copy(allowedNamespaces = current.allowedNamespaces))
        drain(ra) { listOf(early, orphaned).all { ra.store.find(team, it.id)!!.workspace.deletion == DeletionState.PROVEN } }
    }

    /**
     * The recipe is versioned with the profile and resolved onto the session; the owner's variables are resolved on
     * every execution; a failed preparation fails a first execution (workspace deleted) and blocks a resume
     * (workspace kept), and is never reported ready.
     */
    private suspend fun environmentPreparation(ra: Replica, rb: Replica, admin: Principal, colleague: Principal, connected: CloudCluster) {
        val team = admin.teamId
        ra.admin.saveSettings(admin, CloudSettings())
        val assigned = mutableMapOf<String, String>()
        val runner = RunnerChannelUseCase(ra.store, variables = { _, member -> CloudVariables(if (member == colleague.member.id) assigned.toMap() else emptyMap()) })
        val recipe = CloudEnvironmentSpec(listOf(CloudRepository("https://github.com/example/api.git", "api", "main")), "api",
            CloudSetup(listOf("npm ci"), listOf("npm run db:migrate")), listOf("NPM_TOKEN"), gitEmailDomain = "example.com")
        val spec = CloudProfileSpec(connected.id, "teamcode", "registry.example/toolchain:2026-10", environment = recipe)
        failure("invalid_environment") { ra.admin.saveProfile(admin, null, 0, "unsafe", spec.copy(environment = recipe.copy(workingDirectory = "../etc")), emptySet()) }
        val profile = ra.admin.saveProfile(admin, null, 0, "prepared", spec, setOf(colleague.member.id))
        assertEquals(recipe, rb.store.profileVersion(team, profile.id, 1)!!.environment, "The recipe is part of the immutable profile version")
        suspend fun create(name: String) = (ra.sessions.create(colleague, null, CloudSessionDraft(name, profile.id, null, null, null, emptyMap(), null,
            CloudEnvironmentOverride(listOf(CloudRepository("https://github.com/example/api.git", "api", "release", "task-$name")))), null) as CloudCreateOutcome.Created).session
        suspend fun registered(id: String, epoch: Int) {
            val pod = CloudSessionService.podName(id, epoch)
            drain(ra) { pod in cluster(ra).podNames() }
            cluster(ra).start(pod); pass(ra)
            rb.runner.register(team, id, epoch, pod)
        }

        // Missing required variables: the environment is refused and the first execution fails, freeing the workspace.
        val missing = create("missing")
        assertEquals("task-missing", rb.store.find(team, missing.id)!!.environment.repositories.single().branch, "The resolved recipe is stored on the session")
        assertEquals(recipe.setup, rb.store.find(team, missing.id)!!.environment.setup)
        registered(missing.id, 1)
        failure(CloudEnvironmentRules.PREPARATION_FAILED) { runner.environment(team, missing.id, 1) }
        rb.runner.heartbeat(team, missing.id, 1, ready = true)
        assertEquals(PREPARING, ra.store.find(team, missing.id)!!.state, "A failed preparation is never reported ready")
        pass(ra)
        val failed = ra.store.find(team, missing.id)!!
        assertEquals(FAILED, failed.state); assertEquals(CloudEnvironmentRules.PREPARATION_FAILED, failed.failure?.code)
        assertTrue(failed.failure!!.message.contains("NPM_TOKEN"), "Names, never values, explain the failure")
        drain(ra) { ra.store.find(team, missing.id)!!.workspace.deletion == DeletionState.PROVEN }

        // Assigned: the runner receives the owner's values and Git identity, reports progress, then ready.
        assigned["NPM_TOKEN"] = "npm_synthetic_value"
        val prepared = create("ok")
        registered(prepared.id, 1)
        val environment = runner.environment(team, prepared.id, 1)
        assertEquals(mapOf("NPM_TOKEN" to "npm_synthetic_value"), environment.variables.values)
        assertEquals(GitIdentity("cloud-colleague", "cloud-colleague@example.com"), environment.git)
        runner.progress(team, prepared.id, 1, "cloning_repositories", null)
        assertEquals("cloning_repositories", rb.store.find(team, prepared.id)!!.phase)
        assertFalse(ra.database.query { CloudSessions.selectAll().where { CloudSessions.id eq prepared.id }.single()[CloudSessions.environment] }!!.contains("npm_synthetic_value"),
            "No variable value is stored with the session")
        rb.runner.heartbeat(team, prepared.id, 1, ready = true)
        assertEquals(READY, ra.store.find(team, prepared.id)!!.state)
        failure("not_preparing") { runner.environment(team, prepared.id, 1) }

        // A resume whose preparation fails is blocked back to paused; the workspace and its work are kept.
        ra.sessions.pause(colleague, null, prepared.id, false)
        pass(ra)
        rb.runner.checkpoint(team, prepared.id, 1, completed = true)
        drain(ra) { ra.store.find(team, prepared.id)!!.state == PAUSED }
        ra.sessions.resume(colleague, null, prepared.id)
        registered(prepared.id, 2)
        assertEquals(RESUMING, ra.store.find(team, prepared.id)!!.state)
        runner.environment(team, prepared.id, 2)
        failure("execution_fenced") { runner.progress(team, prepared.id, 1, "running_setup", null) }
        runner.progress(team, prepared.id, 2, "running_setup", PreparationFailure(PreparationStep.SETUP_START, 0, 1))
        pass(ra)
        val blocked = ra.store.find(team, prepared.id)!!
        assertEquals(PAUSED, blocked.state); assertEquals(CloudEnvironmentRules.PREPARATION_FAILED, blocked.resumeBlocked?.code)
        assertTrue(blocked.resumeBlocked!!.message.startsWith("Setup command 1 (start) failed (exit code 1)"))
        assertNull(blocked.failure, "The session itself has not failed")
        assertNotNull(cluster(ra).claims["teamcode/${blocked.workspace.claimName}"], "A blocked resume keeps the workspace")
        assertTrue(cluster(ra).podNames().none { it.startsWith("tc-${prepared.id}-") }, "Its pod is stopped")
        // A runner that goes silent while preparing a resume is lost after 90 seconds, not at the 30-minute timeout.
        ra.sessions.resume(colleague, null, prepared.id)
        registered(prepared.id, 3)
        ra.database.query { CloudExecutions.update({ (CloudExecutions.sessionId eq prepared.id) and (CloudExecutions.epoch eq 3) }) { it[lastHeartbeatAt] = 1 } }
        pass(ra)
        val silent = ra.store.find(team, prepared.id)!!
        assertEquals(PAUSED, silent.state); assertEquals("execution_lost", silent.resumeBlocked?.code)
        // Unreadable required values are a retryable outage, not a missing assignment.
        val outage = RunnerChannelUseCase(ra.store, variables = { _, _ -> CloudVariables(emptyMap(), listOf("NPM_TOKEN")) })
        val unreadable = create("outage")
        registered(unreadable.id, 1)
        failure(CloudEnvironmentRules.PREPARATION_FAILED) { outage.environment(team, unreadable.id, 1) }
        val problem = ra.store.find(team, unreadable.id)!!.failure!!
        assertTrue(problem.retryable && problem.message.contains("unavailable right now"))
        ra.sessions.delete(colleague, null, unreadable.id)
        ra.sessions.delete(colleague, null, prepared.id)
        drain(ra) { listOf(prepared, unreadable).all { ra.store.find(team, it.id)!!.workspace.deletion == DeletionState.PROVEN } }
    }

    private fun cluster(r: Replica) = r.cluster
    private suspend fun makeDue(r: Replica) = r.database.query { CloudSessions.update({ CloudSessions.reconcileAfter.isNotNull() }) { it[reconcileAfter] = 0 } }
    /** One reconciler pass with every scheduled session made due, so tests do not wait for real time. */
    private suspend fun pass(r: Replica) { makeDue(r); r.reconciler() }
    private suspend fun drain(r: Replica, done: suspend () -> Boolean) {
        repeat(40) { if (done()) return; pass(r) }
        assertTrue(done(), "The reconciler converged")
    }
    /**
     * Every existing session is a cloud session; a remote one carries its device, read with the device's current name
     * and system, and loses it when the device is removed. Runs last: no reconciler pass sees the remote session.
     */
    private suspend fun kindsAndHosts(ra: Replica, rb: Replica, admin: Principal, colleague: Principal, profile: CloudProfile) {
        val team = SqlTeamService(ra.database)
        val installation = "0f0e0d0c-0b0a-4009-8807-060504030201"
        val laptop = team.login("cloud-colleague", "cloud colleague replacement password", "Work laptop", installation, "macos", "15.1").principal
        failure("invalid_platform") { team.login("cloud-colleague", "cloud colleague replacement password", "Work laptop", installation, "beos", null) }
        failure("invalid_os_version") { team.login("cloud-colleague", "cloud colleague replacement password", "Work laptop", installation, null, "15.1") }
        val devices = SqlDeviceService(rb.database)
        val device = devices.devices(laptop, colleague.member.id).single { it.current }
        assertEquals("macos" to "15.1", device.platform to device.osVersion)
        // An older client reports no system: the device keeps the one reported before.
        team.login("cloud-colleague", "cloud colleague replacement password", "Work laptop", installation)
        assertEquals("macos", devices.devices(laptop, colleague.member.id).single { it.id == device.id }.platform)

        val draft = CloudSessionDraft("Kinds", profile.id, null, null, null, emptyMap(), null)
        val cloud = (ra.sessions.create(colleague, null, draft, null) as CloudCreateOutcome.Created).session
        assertEquals(CloudSessionKind.CLOUD, rb.sessions.get(colleague, cloud.id).kind)
        assertNull(rb.sessions.get(colleague, cloud.id).host)
        val remote = cloud.copy(id = java.util.UUID.randomUUID().toString(), name = "On the laptop", labels = emptyMap(), kind = CloudSessionKind.REMOTE,
            host = CloudSessionHost(device.id, "teamcode"))
        ra.store.insert(remote, CloudActor(colleague, colleague.member.id), null, 100)
        val read = rb.sessions.get(colleague, remote.id)
        assertEquals(CloudSessionKind.REMOTE, read.kind)
        assertEquals(CloudSessionHost(device.id, "teamcode", HostDevice("Work laptop", "macos", "15.1")), read.host)
        val event = ra.database.query { TeamEvents.selectAll().where { (TeamEvents.subjectId eq remote.id) and (TeamEvents.type eq "cloud.session.created") }.single()[TeamEvents.data] }
        assertTrue("\"kind\":\"remote\"" in event && "\"hostDeviceId\":\"${device.id}\"" in event, event)

        suspend fun ids(vararg kinds: CloudSessionKind) = rb.sessions.list(colleague, CloudSessionQuery(kinds = kinds.toSet())).sessions.map { it.id }
        assertEquals(listOf(remote.id), ids(CloudSessionKind.REMOTE))
        assertTrue(cloud.id in ids(CloudSessionKind.CLOUD) && remote.id !in ids(CloudSessionKind.CLOUD))
        assertEquals(ids(), ids(CloudSessionKind.CLOUD, CloudSessionKind.REMOTE))
        assertTrue(cloud.id in ids() && remote.id in ids())
        assertEquals(read.host, rb.sessions.list(colleague, CloudSessionQuery(kinds = setOf(CloudSessionKind.REMOTE))).sessions.single().host)
        assertEquals(listOf(remote.id), rb.sessions.list(admin, CloudSessionQuery(owner = "*", kinds = setOf(CloudSessionKind.REMOTE))).sessions.map { it.id })

        // Removing the device ends its remote sessions in the same transaction; they keep their transcript and directory name.
        devices.revokeDevice(colleague, device.id)
        val removed = rb.sessions.get(colleague, remote.id)
        assertEquals(CloudSessionHost(null, "teamcode"), removed.host)
        assertEquals(DesiredState.TERMINATED to "device_removed", removed.desiredState to removed.terminatedReason)
        assertEquals(cloud.state, rb.sessions.get(colleague, cloud.id).state, "Cloud sessions are not touched")
        pass(ra)
        assertEquals(TERMINATED, ra.store.find(admin.teamId, remote.id)!!.state)
        ra.sessions.delete(colleague, null, cloud.id)
    }

    /**
     * A remote session from creation to removal, on real MySQL: created on the caller's device, started by its host with
     * the member's login, fenced when a second host starts, paused by a checkpoint and by a silent host, resumed only on
     * its device, never transferred, and never a cluster call.
     */
    private suspend fun remoteLifecycle(ra: Replica, rb: Replica, admin: Principal, colleague: Principal) {
        val team = SqlTeamService(ra.database)
        val password = "cloud colleague replacement password"
        val laptop = team.login("cloud-colleague", password, "Laptop", "1f0e0d0c-0b0a-4009-8807-060504030201", "linux", "6.8")
        val desktop = team.login("cloud-colleague", password, "Desktop", "2f0e0d0c-0b0a-4009-8807-060504030201", "windows", "11")
        val noDevice = team.login("cloud-colleague", password, "Script")
        val device = ra.store.deviceOf(laptop.principal)!!
        val draft = RemoteSessionDraft("Refactor", "claude", "opus", null, mapOf("area" to "cli"), "teamcode", RemotePermissionMode.ASK)
        failure("remote_requires_device") { ra.sessions.createRemote(noDevice.principal, null, draft, null) }
        failure("on_behalf_forbidden") { ra.sessions.createRemote(admin, colleague.member.id, draft, null) }
        failure("invalid_directory_name") { ra.sessions.createRemote(laptop.principal, null, draft.copy(directoryName = "/home/someone/teamcode"), null) }
        failure("invalid_agent") { ra.sessions.createRemote(laptop.principal, null, draft.copy(agentKind = "gemini"), null) }
        val created = (ra.sessions.createRemote(laptop.principal, null, draft, IdempotencyRequest("POST /cloud/sessions", "remote-1", "d")) as CloudCreateOutcome.Created).session
        assertEquals(created.id, (ra.sessions.createRemote(laptop.principal, null, draft, IdempotencyRequest("POST /cloud/sessions", "remote-1", "d")) as CloudCreateOutcome.Replayed).session.id)
        var s = rb.sessions.get(colleague, created.id)
        assertEquals(CloudSessionKind.REMOTE to CREATING, s.kind to s.state)
        assertEquals(CloudSessionHost(device, "teamcode", HostDevice("Laptop", "linux", "6.8"), RemotePermissionMode.ASK), s.host)
        assertEquals(60, s.idleTimeoutMinutes, "The team's remote idle pause applies")
        assertEquals("" to "", s.profileId to s.clusterId)
        // The reconciler has nothing to do until a host starts it.
        pass(ra)
        assertEquals(CREATING to 0, ra.store.find(admin.teamId, s.id)!!.let { it.state to it.currentEpoch })

        failure("remote_host_mismatch") { rb.sessions.host(desktop.principal, s.id) }
        failure("not_found") { rb.sessions.host(admin, s.id) }
        failure("remote_not_transferable") { CloudTransferService.transferable(s) }
        val hosted = rb.sessions.host(laptop.principal, s.id)
        assertEquals(1, hosted.currentEpoch)
        // The runner channel takes the owner's login from the session's device only.
        failure("runner_unauthenticated") { ra.authenticate(desktop.token, admin.teamId, s.id, 1) }
        failure("runner_unauthenticated") { ra.authenticate(noDevice.token, admin.teamId, s.id, 1) }
        failure("execution_fenced") { ra.authenticate(laptop.token, admin.teamId, s.id, 2) }
        val identity = ra.authenticate(laptop.token, admin.teamId, s.id, 1)
        ra.runner.register(identity.teamId, identity.sessionId, identity.epoch, identity.podName)
        assertEquals(PREPARING, ra.store.find(admin.teamId, s.id)!!.state)
        rb.runner.heartbeat(admin.teamId, s.id, 1, ready = true)
        s = rb.sessions.get(colleague, s.id)
        assertEquals(READY, s.state)
        assertTrue(s.host!!.online && s.host!!.lastHeartbeatAt != null, "The host is online while it heartbeats")
        val listed = rb.sessions.list(colleague, CloudSessionQuery(kinds = setOf(CloudSessionKind.REMOTE))).sessions.single { it.id == s.id }
        assertTrue(listed.host!!.online)

        // A second host on the same device takes a new epoch: the first one is fenced and exits.
        rb.sessions.host(laptop.principal, s.id)
        failure("execution_fenced") { ra.authenticate(laptop.token, admin.teamId, s.id, 1) }
        assertEquals("replaced", ra.store.execution(s.id, 1)!!.exitReason)
        assertEquals(RESUMING, ra.store.find(admin.teamId, s.id)!!.state)
        ra.authenticate(laptop.token, admin.teamId, s.id, 2).let { ra.runner.register(it.teamId, it.sessionId, it.epoch, it.podName) }
        ra.runner.heartbeat(admin.teamId, s.id, 2, ready = true)
        assertEquals(READY, ra.store.find(admin.teamId, s.id)!!.state)

        // A pause drains on the host and completes at its checkpoint: nothing is deleted.
        rb.sessions.pause(laptop.principal, null, s.id, false)
        pass(ra)
        assertEquals(PAUSING, ra.store.find(admin.teamId, s.id)!!.state)
        rb.runner.checkpoint(admin.teamId, s.id, 2, completed = true)
        pass(ra)
        s = ra.store.find(admin.teamId, s.id)!!
        assertEquals(PAUSED to WorkspaceResume.CONSISTENT, s.state to s.resumeWorkspace)
        assertEquals(DeletionState.NONE, s.workspace.deletion)
        assertFalse(rb.sessions.get(colleague, s.id).host!!.online)

        // Only the host device resumes it; a message's wake elsewhere leaves it waiting for that host.
        failure("remote_host_mismatch") { rb.sessions.resume(desktop.principal, null, s.id) }
        rb.sessions.resume(laptop.principal, null, s.id)
        pass(ra)
        s = ra.store.find(admin.teamId, s.id)!!
        assertEquals(RESUMING to CloudSessionService.WAITING_HOST, s.state to s.phase)
        rb.sessions.host(laptop.principal, s.id)
        ra.runner.register(admin.teamId, s.id, 3, CloudSessionService.hostExecutionName(3))
        ra.runner.heartbeat(admin.teamId, s.id, 3, ready = true)

        // A silent host (device asleep, network down) pauses the session after 90 s, "host offline".
        ra.database.query { CloudExecutions.update({ (CloudExecutions.sessionId eq s.id) and (CloudExecutions.epoch eq 3) }) { it[lastHeartbeatAt] = 1 } }
        pass(ra)
        s = ra.store.find(admin.teamId, s.id)!!
        assertEquals(PAUSED to ReconcileCloudSessionsUseCase.HOST_OFFLINE, s.state to s.failure?.code)
        assertEquals("heartbeat_lost", ra.store.execution(s.id, 3)!!.exitReason)
        failure("execution_fenced") { ra.runner.heartbeat(admin.teamId, s.id, 3, ready = true) }
        // The host starting again resumes it and clears the failure.
        assertNull(rb.sessions.host(laptop.principal, s.id).failure)

        // All kinds at once: one page per kind, merged in creation order, with a cursor across both.
        val all = rb.sessions.list(colleague, CloudSessionQuery(limit = 1, kinds = CloudSessionKind.entries.toSet()))
        val everything = rb.sessions.list(colleague, CloudSessionQuery(limit = 50)).sessions
        assertEquals(everything.sortedWith(compareByDescending<CloudSession> { it.createdAt }.thenByDescending { it.id }).map { it.id }, everything.map { it.id })
        assertEquals(everything.first().id, all.sessions.single().id)
        assertEquals(everything[1].id, rb.sessions.list(colleague, CloudSessionQuery(limit = 1, beforeId = all.nextBeforeId)).sessions.single().id)
        assertTrue(everything.any { it.kind == CloudSessionKind.REMOTE } && everything.any { it.kind == CloudSessionKind.CLOUD })

        // Deleting it ends it without any cleanup: the directory is the member's.
        rb.sessions.delete(laptop.principal, null, s.id)
        pass(ra)
        s = ra.store.find(admin.teamId, s.id)!!
        assertEquals(TERMINATED to DeletionState.NONE, s.state to s.workspace.deletion)
        assertEquals(ExecutionState.ENDED, ra.store.execution(s.id, 4)!!.state)
        assertTrue(cluster(ra).podNames().none { s.id in it } && cluster(ra).claims.keys.none { s.id in it }, "A remote session never reaches the cluster")
    }

    private suspend fun failure(code: String, block: suspend () -> Unit) = assertEquals(code, assertFailsWith<TeamFailure> { block() }.code)
}
