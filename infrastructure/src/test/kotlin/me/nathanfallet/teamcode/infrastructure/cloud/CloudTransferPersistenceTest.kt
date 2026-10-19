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

/** Ownership transfers on real MySQL with two replicas: offers, acceptance, the paused boundary and revocation. */
class CloudTransferPersistenceTest {
    private class Replica(val database: Database, val cluster: FakeClusterPort, cipher: CredentialCipher) {
        val store = SqlCloudSessionStore(database)
        val conversations = SqlCloudConversationStore(database)
        val transferStore = SqlCloudTransferStore(database)
        val access = CloudAccessService(conversations, store)
        val admin = CloudAdministrationService(SqlCloudAdminStore(database, cipher), cluster)
        val sessions = CloudSessionService(store, access = access)
        val recipients = CloudRecipientPolicy(store, access, listOf(ModelPolicyRecipientCheck(SqlModelPolicyStore(database)), QuotaRecipientCheck(SqlMemberAllowance(database))))
        val transfers = CloudTransferService(store, transferStore, sessions, recipients)
        val reconciler = ReconcileCloudSessionsUseCase(store, cluster, transfers = CompleteCloudTransferUseCase(store, transferStore, cluster, recipients))
        val runner = RunnerChannelUseCase(store)
        val conversation = CloudConversationService(conversations, store, access, sleep = { delay(1) })
        val agent = RunnerConversationUseCase(store, conversations, sleep = { delay(1) })
    }
    private data class Team(val admin: Principal, val owner: Principal, val recipient: Principal, val outsider: Principal)

    @Test fun sessionsChangeOwnerAtAPausedBoundary() = runBlocking {
        MySQLContainer("mysql:8.4").withDatabaseName("teamcode").withTmpFs(mapOf("/var/lib/mysql" to "rw,nosuid,size=1g")).use { container ->
            container.start()
            Database(container.jdbcUrl, container.username, container.password).use { a ->
            Database(container.jdbcUrl, container.username, container.password).use { b ->
                coroutineScope { listOf(a, b).map { async(Dispatchers.IO) { it.migrate() } }.awaitAll() }
                a.query {
                    val drift = MigrationUtils.statementsRequiredForDatabaseMigration(CloudSettingsTable, CloudSessions, CloudExecutions, CloudTransfers, AuditEvents, withLogs = false)
                    assertEquals(emptyList(), drift, "The reviewed migration matches the Exposed tables")
                }
                val cluster = FakeClusterPort()
                val cipher = CredentialCipher(ByteArray(32) { 43 })
                val ra = Replica(a, cluster, cipher); val rb = Replica(b, cluster, cipher)
                val team = members(a)
                val profile = administration(ra, team.admin, setOf(team.owner.member.id, team.recipient.member.id))
                val session = ready(ra, rb, team.owner, profile, "Offered")
                offers(ra, rb, team, session)
                acceptance(ra, rb, team, profile, session)
                administratorTransfer(ra, rb, team, profile)
                recheckAtTheBoundary(ra, rb, team, profile)
                endedSessions(ra, rb, team, profile)
            } }
        }
    }

    /** An owner's request is only an offer: nothing changes until the recipient accepts. */
    private suspend fun offers(ra: Replica, rb: Replica, t: Team, s: CloudSession) {
        val teamId = t.admin.teamId
        val to = t.recipient.member.id
        failure("not_found") { ra.transfers.request(t.recipient, null, s.id, CloudTransferRequest(t.recipient.member.id, false, false), key("steal", "d")) }
        failure("invalid_transfer") { ra.transfers.request(t.owner, null, s.id, CloudTransferRequest(t.owner.member.id, false, false), key("self", "d")) }
        failure("on_behalf_forbidden") { ra.transfers.request(t.owner, to, s.id, CloudTransferRequest(to, false, false), key("obo", "d")) }
        // Eligibility: the outsider is not assigned to the profile; a recipient without allowance is refused too.
        val unassigned = assertFailsWith<TeamFailure> { ra.transfers.request(t.owner, null, s.id, CloudTransferRequest(t.outsider.member.id, false, false), key("x", "d")) }
        assertEquals("recipient_ineligible", unassigned.code); assertTrue("profile_unassigned" in unassigned.message.orEmpty())
        ra.database.query { MemberPolicies.upsert { it[memberId] = to; it[weight] = 0 } }
        assertTrue("recipient_no_quota" in assertFailsWith<TeamFailure> { ra.transfers.request(t.owner, null, s.id, CloudTransferRequest(to, false, false), key("q", "d")) }.message.orEmpty())
        ra.database.query { MemberPolicies.deleteWhere { memberId eq to } }
        // The team can turn offers off; administrators still transfer.
        ra.admin.saveSettings(t.admin, CloudSettings(memberTransfers = MemberTransfers.DISABLED))
        failure("transfer_disabled") { ra.transfers.request(t.owner, null, s.id, CloudTransferRequest(to, false, false), key("off", "d")) }
        ra.admin.saveSettings(t.admin, CloudSettings())

        // Concurrent identical requests on both replicas create one offer; a different body with the key conflicts.
        val outcomes = coroutineScope { listOf(ra, rb).map { r -> async(Dispatchers.IO) { r.transfers.request(t.owner, null, s.id, CloudTransferRequest(to, false, false), key("offer-1", "d1")) } }.awaitAll() }
        assertEquals(1, outcomes.count { it is CloudTransferOutcome.Created })
        val offer = outcomes.first().transfer
        assertEquals(setOf(offer.id), outcomes.map { it.transfer.id }.toSet())
        assertEquals(CloudTransferState.OFFERED, offer.state); assertNotNull(offer.expiresAt)
        assertIs<CloudTransferOutcome.Replayed>(rb.transfers.request(t.owner, null, s.id, CloudTransferRequest(to, false, false), key("offer-1", "d1")))
        failure("idempotency_conflict") { ra.transfers.request(t.owner, null, s.id, CloudTransferRequest(to, true, false), key("offer-1", "d2")) }
        failure("transfer_pending") { ra.transfers.request(t.owner, null, s.id, CloudTransferRequest(to, false, false), key("offer-2", "d3")) }
        val current = ra.store.find(teamId, s.id)!!
        assertEquals(READY to null, current.state to current.transferId, "An offer does not touch the session")

        // Visibility and rights: both members see it, the outsider does not; only the recipient decides.
        assertEquals(offer.id, rb.transfers.list(t.recipient, CloudTransferQuery(direction = CloudTransferDirection.INCOMING)).transfers.single().id)
        assertEquals(offer.id, rb.transfers.list(t.owner, CloudTransferQuery(direction = CloudTransferDirection.OUTGOING)).transfers.single().id)
        assertEquals(emptyList(), rb.transfers.list(t.owner, CloudTransferQuery(direction = CloudTransferDirection.INCOMING)).transfers)
        failure("not_found") { ra.transfers.get(t.outsider, offer.id) }
        failure("forbidden") { ra.transfers.list(t.owner, CloudTransferQuery(team = true)) }
        failure("transfer_forbidden") { ra.transfers.accept(t.owner, null, offer.id, false) }
        failure("transfer_forbidden") { ra.transfers.cancel(t.recipient, null, offer.id) }

        // Declined, withdrawn and expired offers leave the session alone.
        assertEquals(CloudTransferState.DECLINED, rb.transfers.decline(t.recipient, null, offer.id).state)
        failure("transfer_not_open") { ra.transfers.accept(t.recipient, null, offer.id, false) }
        val second = ra.transfers.request(t.owner, null, s.id, CloudTransferRequest(to, false, false), key("offer-3", "d4")).transfer
        assertEquals(CloudTransferState.CANCELLED, ra.transfers.cancel(t.owner, null, second.id).state)
        val third = ra.transfers.request(t.owner, null, s.id, CloudTransferRequest(to, false, false), key("offer-4", "d5")).transfer
        ra.database.query { CloudTransfers.update({ CloudTransfers.id eq third.id }) { it[expiresAt] = 1 } }
        failure("transfer_expired") { rb.transfers.accept(t.recipient, null, third.id, false) }
        assertEquals(CloudTransferState.EXPIRED, ra.transfers.get(t.owner, third.id).state)
        val fourth = ra.transfers.request(t.owner, null, s.id, CloudTransferRequest(to, false, false), key("offer-5", "d6")).transfer
        ra.database.query { CloudTransfers.update({ CloudTransfers.id eq fourth.id }) { it[expiresAt] = 1 } }
        assertEquals(1, rb.transfers.expireOffers())
        assertEquals(CloudTransferState.EXPIRED, ra.transfers.get(t.recipient, fourth.id).state)
        assertEquals(READY to null, ra.store.find(teamId, s.id)!!.let { it.state to it.transferId })
    }

    /**
     * Accepting a running session: it drains and checkpoints like a pause, the pod goes, then one transaction changes
     * the owner, removes attachment leases and cancels unconsumed messages. History stays with the former owner.
     */
    private suspend fun acceptance(ra: Replica, rb: Replica, t: Team, profile: CloudProfile, s: CloudSession) {
        val teamId = t.admin.teamId
        val from = t.owner.member.id; val to = t.recipient.member.id
        // Work for the former owner in epoch 1: a consumed message, an attachment, then one message left unconsumed.
        val done = (ra.conversation.send(t.owner, s.id, InputRequest("Before the transfer", true, null, null, null)) as InputOutcome.Accepted).input
        rb.agent.acknowledge(teamId, s.id, 1, rb.agent.inputs(teamId, s.id, 1, 0).map { RunnerInputAck(it.id, true, null) })
        val lease = ra.conversation.attach(t.owner, s.id)
        val offer = rb.transfers.request(t.owner, null, s.id, CloudTransferRequest(to, false, false), key("offer-run", "d7")).transfer

        // Both replicas accept at once: one acceptance, the same transfer for both.
        val accepted = coroutineScope { listOf(ra, rb).map { r -> async(Dispatchers.IO) { r.transfers.accept(t.recipient, null, offer.id, true) } }.awaitAll() }
        assertTrue(accepted.all { it.id == offer.id && it.state == CloudTransferState.ACCEPTED && it.resume })
        var current = ra.store.find(teamId, s.id)!!
        assertEquals(offer.id, current.transferId); assertEquals(DesiredState.PAUSED, current.desiredState)
        assertEquals(from, current.ownerId, "The owner changes only at the paused boundary")
        failure("session_transferring") { ra.sessions.resume(t.owner, null, s.id) }
        failure("transfer_pending") { ra.transfers.request(t.admin, null, s.id, CloudTransferRequest(t.admin.member.id, false, false), key("admin-race", "d8")) }
        failure("transfer_forbidden") { ra.transfers.cancel(t.owner, null, offer.id) }

        // The drain: pausing, the runner's checkpoint, the pod deleted, paused, then transferring.
        pass(ra)
        assertEquals(PAUSING, ra.store.find(teamId, s.id)!!.state)
        val unconsumed = (rb.conversation.send(t.owner, s.id, InputRequest("Sent during the drain", true, null, null, null)) as InputOutcome.Accepted).input
        assertFalse(unconsumed.woke)
        rb.runner.checkpoint(teamId, s.id, 1, completed = true)
        pass(rb)
        current = ra.store.find(teamId, s.id)!!
        assertEquals(PAUSED, current.state); assertEquals(WorkspaceResume.CONSISTENT, current.resumeWorkspace)
        assertEquals(0, ra.conversation.wakePending(), "A message never wakes a session that is changing owner")
        assertEquals(emptySet(), cluster(ra).podNames().filter { it.startsWith("tc-${s.id}-") }.toSet(), "No pod runs for the former owner")
        pass(ra)
        assertEquals(TRANSFERRING, ra.store.find(teamId, s.id)!!.state)
        failure("transfer_in_progress") { ra.transfers.cancel(t.admin, null, offer.id) }
        pass(rb)

        current = ra.store.find(teamId, s.id)!!
        assertEquals(to, current.ownerId)
        assertEquals(RESUMING to DesiredState.RUNNING, current.state to current.desiredState, "The recipient asked to resume")
        assertNull(current.transferId)
        val completed = ra.transfers.get(t.recipient, offer.id)
        assertEquals(CloudTransferState.COMPLETED, completed.state); assertEquals(1, completed.atEpoch)

        // Former-owner access ended: the session, its transcript and the lease are gone for them.
        failure("not_found") { ra.sessions.get(t.owner, s.id) }
        failure("not_found") { ra.conversation.events(t.owner, s.id, 0, null, 10) }
        failure("not_found") { ra.conversation.renew(t.owner, s.id, lease.id) }
        assertEquals(emptyList(), ra.conversations.attachments(s.id, ra.store.now()))
        assertEquals(CloudInputStatus.CANCELLED to "transferred", ra.conversation.input(t.recipient, s.id, unconsumed.id).let { it.status to it.reason })
        assertEquals(CloudInputStatus.CONSUMED, ra.conversation.input(t.recipient, s.id, done.id).status, "History is kept")
        val transferEvent = ra.conversation.events(t.recipient, s.id, 0, null, 200).events.single { it.type == CloudEventType.TRANSFER }
        assertEquals(completed.eventSeq, transferEvent.seq)
        assertTrue(""""fromMemberId":"$from"""" in transferEvent.data && """"toMemberId":"$to"""" in transferEvent.data)
        val journal = ra.database.query {
            AuditEvents.selectAll().where { (AuditEvents.action eq "cloud.session.transferred") and (AuditEvents.subjectId eq s.id) }.single()
        }
        assertEquals(listOf(from, to, from), listOf(journal[AuditEvents.actorId], journal[AuditEvents.effectiveMemberId], journal[AuditEvents.relatedMemberId]),
            "The journal names the initiator and both members")

        // The next execution runs for the recipient; the first one stays the former owner's.
        val pod = CloudSessionService.podName(s.id, 2)
        repeat(10) { if (pod !in cluster(ra).podNames()) pass(ra) }
        cluster(ra).start(pod); pass(ra)
        ra.runner.register(teamId, s.id, 2, pod)
        rb.runner.heartbeat(teamId, s.id, 2, ready = true)
        assertEquals(READY, ra.store.find(teamId, s.id)!!.state)
        assertEquals(listOf(2 to to, 1 to from), ra.sessions.executions(t.recipient, s.id).map { it.epoch to it.ownerId })
        assertEquals(0, ra.agent.inputs(teamId, s.id, 2, 0).size, "The former owner's unconsumed message never reaches the recipient's agent")
        failure("execution_fenced") { ra.runner.heartbeat(teamId, s.id, 1, ready = true) }
        assertEquals(profile.id, ra.store.find(teamId, s.id)!!.profileId)
    }

    /** An administrator transfers at once, with `TeamCode-On-Behalf-Of` recorded; a pending offer is superseded. */
    private suspend fun administratorTransfer(ra: Replica, rb: Replica, t: Team, profile: CloudProfile) {
        val teamId = t.admin.teamId
        val s = ready(ra, rb, t.owner, profile, "Administrator")
        val offer = ra.transfers.request(t.owner, null, s.id, CloudTransferRequest(t.recipient.member.id, false, false), key("offer-admin", "d9")).transfer
        ra.sessions.pause(t.owner, null, s.id, false); pass(ra); rb.runner.checkpoint(teamId, s.id, 1, completed = true); pass(ra)
        assertEquals(PAUSED, ra.store.find(teamId, s.id)!!.state)
        ra.admin.saveSettings(t.admin, CloudSettings(transferPendingInputs = TransferPendingInputs.KEEP))
        val kept = (ra.conversation.send(t.owner, s.id, InputRequest("Keep me", false, null, null, null)) as InputOutcome.Accepted).input
        val direct = rb.transfers.request(t.admin, t.owner.member.id, s.id, CloudTransferRequest(t.admin.member.id, false, false), key("admin-1", "d10")).transfer
        assertEquals(CloudTransferState.ACCEPTED, direct.state)
        assertEquals(Attribution(t.admin.member.id, t.admin.tokenId, t.owner.member.id), direct.initiatedBy)
        assertEquals(CloudTransferState.CANCELLED to "superseded", ra.transfers.get(t.owner, offer.id).let { it.state to it.failure?.code })
        pass(ra); pass(rb)
        val current = ra.store.find(teamId, s.id)!!
        assertEquals(t.admin.member.id, current.ownerId)
        assertEquals(PAUSED to DesiredState.PAUSED, current.state to current.desiredState, "Without resume the session stays paused")
        assertNotNull(current.expiresAt, "The recipient gets the full paused retention")
        assertEquals(CloudInputStatus.QUEUED, ra.conversation.input(t.admin, s.id, kept.id).status, "The team kept unconsumed messages")
        ra.admin.saveSettings(t.admin, CloudSettings())
        ra.sessions.delete(t.admin, null, s.id)
    }

    /** The recipient is checked again right before the owner changes: one who lost access fails the transfer. */
    private suspend fun recheckAtTheBoundary(ra: Replica, rb: Replica, t: Team, profile: CloudProfile) {
        val teamId = t.admin.teamId
        val s = ready(ra, rb, t.owner, profile, "Recheck")
        failure("recipient_ineligible") { ra.transfers.request(t.admin, null, s.id, CloudTransferRequest(t.outsider.member.id, false, true), key("recheck", "d11")) }
        // Assigned when requested, unassigned before the boundary.
        val reassigned = ra.admin.saveProfile(t.admin, profile.id, ra.admin.profiles(t.admin).single { it.id == profile.id }.version, profile.name, profile.spec,
            setOf(t.owner.member.id, t.recipient.member.id, t.outsider.member.id))
        val accepted = ra.transfers.request(t.admin, null, s.id, CloudTransferRequest(t.outsider.member.id, false, true), key("recheck-2", "d12")).transfer
        assertEquals(true, ra.store.find(teamId, s.id)!!.pauseForce, "A forced transfer does not wait for the turn")
        ra.admin.saveProfile(t.admin, profile.id, reassigned.version, profile.name, profile.spec, setOf(t.owner.member.id, t.recipient.member.id))
        pass(ra); rb.runner.checkpoint(teamId, s.id, 1, completed = false)
        repeat(6) { pass(ra) }
        val failed = ra.transfers.get(t.admin, accepted.id)
        assertEquals(CloudTransferState.FAILED to "profile_unassigned", failed.state to failed.failure?.code)
        val current = ra.store.find(teamId, s.id)!!
        assertEquals(Triple(PAUSED, t.owner.member.id, null), Triple(current.state, current.ownerId, current.transferId), "The session stays with its owner")
        assertEquals(WorkspaceResume.CRASH_CONSISTENT, current.resumeWorkspace)
        assertEquals(1, ra.database.query { AuditEvents.selectAll().where { (AuditEvents.action eq "cloud.transfer.failed") and (AuditEvents.subjectId eq accepted.id) }.count() })
        ra.sessions.resume(t.owner, null, s.id)

        // The retained cap is the recipient's, checked in the owner-change transaction.
        ra.admin.saveSettings(t.admin, CloudSettings(maxRetainedSessionsPerMember = 2, maxRunningSessionsPerMember = 2))
        val capped = ra.transfers.request(t.admin, null, s.id, CloudTransferRequest(t.recipient.member.id, false, true), key("cap", "d13")).transfer
        repeat(10) { if (ra.store.find(teamId, s.id)!!.state == PAUSING) rb.runner.checkpoint(teamId, s.id, ra.store.find(teamId, s.id)!!.currentEpoch, true); pass(ra) }
        val outcome = ra.transfers.get(t.admin, capped.id)
        assertEquals(CloudTransferState.COMPLETED, outcome.state, "The recipient holds one session: under the cap of 2")
        val third = ready(ra, rb, t.owner, profile, "Third")
        val over = ra.transfers.request(t.admin, null, third.id, CloudTransferRequest(t.recipient.member.id, false, true), key("cap-2", "d14")).transfer
        repeat(10) { if (ra.store.find(teamId, third.id)!!.state == PAUSING) rb.runner.checkpoint(teamId, third.id, 1, true); pass(ra) }
        assertEquals(CloudTransferState.FAILED to "cloud_session_limit", ra.transfers.get(t.admin, over.id).let { it.state to it.failure?.code })
        assertEquals(t.owner.member.id, ra.store.find(teamId, third.id)!!.ownerId)
        ra.admin.saveSettings(t.admin, CloudSettings())
        ra.sessions.delete(t.admin, null, third.id)
    }

    /** Deleting a session cancels its accepted transfer; an ended session cannot be transferred. */
    private suspend fun endedSessions(ra: Replica, rb: Replica, t: Team, profile: CloudProfile) {
        val teamId = t.admin.teamId
        val s = ready(ra, rb, t.owner, profile, "Deleted")
        val accepted = ra.transfers.request(t.admin, null, s.id, CloudTransferRequest(t.recipient.member.id, false, false), key("deleted", "d15")).transfer
        ra.sessions.delete(t.owner, null, s.id)
        repeat(40) { if (ra.store.find(teamId, s.id)!!.state == TERMINATED && ra.store.find(teamId, s.id)!!.transferId == null) return@repeat; pass(rb) }
        assertEquals(CloudTransferState.CANCELLED to "session_not_running", ra.transfers.get(t.admin, accepted.id).let { it.state to it.failure?.code })
        assertEquals(t.owner.member.id, ra.store.find(teamId, s.id)!!.ownerId)
        failure("session_not_running") { ra.transfers.request(t.admin, null, s.id, CloudTransferRequest(t.recipient.member.id, false, false), key("deleted-2", "d16")) }
        val creating = (ra.sessions.create(t.owner, null, CloudSessionDraft("Creating", profile.id, null, null, null, emptyMap(), null), null) as CloudCreateOutcome.Created).session
        failure("session_preparing") { ra.transfers.request(t.admin, null, creating.id, CloudTransferRequest(t.recipient.member.id, false, false), key("creating", "d17")) }
    }

    private fun cluster(r: Replica) = r.cluster
    private fun key(value: String, digest: String) = IdempotencyRequest("POST /cloud/sessions/{id}/transfer", value, digest.padEnd(64, '0'))

    private suspend fun members(a: Database): Team {
        val team = SqlTeamService(a)
        val password = team.bootstrap()!!
        team.changePassword(team.login("admin", password, "test").principal, password, "transfer admin replacement password")
        val admin = team.login("admin", "transfer admin replacement password", "test").principal
        fun String.member(): Principal = runBlocking {
            val created = team.createMember(admin, this@member, Role.MEMBER)
            team.changePassword(team.login(this@member, created.temporaryPassword, "test").principal, created.temporaryPassword, "$this@member replacement password")
            team.login(this@member, "$this@member replacement password", "test").principal
        }
        return Team(admin, "transfer-owner".member(), "transfer-recipient".member(), "transfer-outsider".member())
    }

    private suspend fun administration(ra: Replica, admin: Principal, assigned: Set<String>): CloudProfile {
        val cluster = ra.admin.saveCluster(admin, null, null, CloudClusterInput("lab", "https://k8s.example.test:6443",
            "-----BEGIN CERTIFICATE-----\nMIIBsynthetic\n-----END CERTIFICATE-----\n", "synthetic-service-account-token-0123456789", listOf("teamcode"), 10, "standard"))
        return ra.admin.saveProfile(admin, null, 0, "toolchain", CloudProfileSpec(cluster.id, "teamcode", "registry.example/toolchain:2026-10"), assigned)
    }

    private suspend fun ready(ra: Replica, rb: Replica, owner: Principal, profile: CloudProfile, name: String): CloudSession {
        val created = (ra.sessions.create(owner, null, CloudSessionDraft(name, profile.id, null, null, null, emptyMap(), null), null) as CloudCreateOutcome.Created).session
        pass(ra); pass(ra)
        val pod = CloudSessionService.podName(created.id, 1)
        ra.cluster.start(pod); pass(ra)
        ra.runner.register(owner.teamId, created.id, 1, pod)
        rb.runner.heartbeat(owner.teamId, created.id, 1, ready = true)
        return ra.store.find(owner.teamId, created.id)!!.also { assertEquals(READY, it.state) }
    }

    private suspend fun makeDue(r: Replica) = r.database.query { CloudSessions.update({ CloudSessions.reconcileAfter.isNotNull() }) { it[reconcileAfter] = 0 } }
    private suspend fun pass(r: Replica) { makeDue(r); r.reconciler() }
    private suspend fun failure(code: String, block: suspend () -> Unit) = assertEquals(code, assertFailsWith<TeamFailure> { block() }.code)
}
