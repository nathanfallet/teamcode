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
        val runner = RunnerChannelUseCase(store)
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
                        CloudProfileAssignments, CloudSessions, CloudSessionLabels, CloudExecutions, CloudIdempotency, AuditEvents, withLogs = false)
                    assertEquals(emptyList(), drift, "The reviewed migration matches the Exposed tables")
                }
                val cluster = FakeClusterPort()
                val cipher = CredentialCipher(ByteArray(32) { 41 })
                val ra = Replica(a, cluster, cipher); val rb = Replica(b, cluster, cipher)
                val (admin, colleague) = members(a)
                val connected = administration(ra, rb, admin, colleague, cipher)
                val profile = ra.admin.profiles(admin).single()
                delegatedCreationAndIdempotency(ra, rb, admin, colleague, profile)
                val session = lifecycle(ra, rb, admin, colleague, profile)
                concurrentResumeHasOneEpoch(ra, rb, admin, colleague, session)
                capacityIsFifoAndBounded(ra, rb, admin, colleague, profile, connected)
                deletionIsProvenAndRetainedVolumesAlert(ra, rb, admin, colleague, profile, connected)
                garbageCollection(ra, connected)
                retentionExpiryAndLimits(ra, rb, admin, colleague, profile)
                failureModesAndEarlyCheckpoint(ra, rb, admin, profile)
                environmentPreparation(ra, rb, admin, colleague, connected)
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
        val spec = CloudProfileSpec(cluster.id, "teamcode", "registry.example/toolchain:2026-10", allowedImageOverrides = listOf("registry.example/toolchain:*"))
        failure("storage_class_retains_volumes") { ra.admin.saveProfile(admin, null, 0, "retained", spec.copy(storageClass = "keep"), setOf(colleague.member.id)) }
        failure("namespace_not_allowed") { ra.admin.saveProfile(admin, null, 0, "elsewhere", spec.copy(namespace = "default"), emptySet()) }
        val profile = ra.admin.saveProfile(admin, null, 0, "toolchain", spec, setOf(colleague.member.id))
        val second = rb.admin.saveProfile(admin, profile.id, 1, "toolchain", spec.copy(idleTimeoutMinutes = 45), setOf(colleague.member.id))
        assertEquals(2, second.version)
        failure("profile_conflict") { ra.admin.saveProfile(admin, profile.id, 1, "toolchain", spec, emptySet()) }
        assertEquals(listOf(profile.id), ra.admin.profiles(colleague).map { it.id })
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

    private suspend fun lifecycle(ra: Replica, rb: Replica, admin: Principal, colleague: Principal, profile: CloudProfile): CloudSession {
        val created = (ra.sessions.create(colleague, null, CloudSessionDraft("Interactive", profile.id, null, null, null, emptyMap(), 5), null) as CloudCreateOutcome.Created).session
        failure("session_preparing") { ra.sessions.pause(colleague, null, created.id, false) }
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
        assertEquals(8, page.sessions.size + next.sessions.size)
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
        ra.sessions.delete(colleague, null, prepared.id)
        drain(ra) { ra.store.find(team, prepared.id)!!.workspace.deletion == DeletionState.PROVEN }
    }

    private fun cluster(r: Replica) = r.cluster
    private suspend fun makeDue(r: Replica) = r.database.query { CloudSessions.update({ CloudSessions.reconcileAfter.isNotNull() }) { it[reconcileAfter] = 0 } }
    /** One reconciler pass with every scheduled session made due, so tests do not wait for real time. */
    private suspend fun pass(r: Replica) { makeDue(r); r.reconciler() }
    private suspend fun drain(r: Replica, done: suspend () -> Boolean) {
        repeat(40) { if (done()) return; pass(r) }
        assertTrue(done(), "The reconciler converged")
    }
    private suspend fun failure(code: String, block: suspend () -> Unit) = assertEquals(code, assertFailsWith<TeamFailure> { block() }.code)
}
