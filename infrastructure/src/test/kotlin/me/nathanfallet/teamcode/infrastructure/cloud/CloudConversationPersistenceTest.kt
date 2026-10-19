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

/** Transcript, messages, wake-up, attachments, access policy and retention on real MySQL with two replicas. */
class CloudConversationPersistenceTest {
    private class Replica(val database: Database, val cluster: FakeClusterPort, cipher: CredentialCipher, groups: CloudGroupDirectory) {
        val store = SqlCloudSessionStore(database)
        val conversations = SqlCloudConversationStore(database)
        val access = CloudAccessService(conversations, store, groups)
        val admin = CloudAdministrationService(SqlCloudAdminStore(database, cipher), cluster)
        val sessions = CloudSessionService(store, access = access)
        val reconciler = ReconcileCloudSessionsUseCase(store, cluster)
        val runner = RunnerChannelUseCase(store)
        val conversation = CloudConversationService(conversations, store, access, sleep = { delay(1) })
        val agent = RunnerConversationUseCase(store, conversations, sleep = { delay(1) })
    }
    /** Groups do not exist in TeamCode yet; this directory stands in for one. */
    private class Groups : CloudGroupDirectory {
        val members = mutableMapOf<String, Set<String>>()
        override suspend fun groupsOf(teamId: String, memberId: String) = members.filterValues { memberId in it }.keys
        override suspend fun exists(teamId: String, groupId: String) = groupId in members
    }

    @Test fun messagesTranscriptAndAccessAreSharedAcrossReplicas() = runBlocking {
        MySQLContainer("mysql:8.4").withDatabaseName("teamcode").withTmpFs(mapOf("/var/lib/mysql" to "rw,nosuid,size=1g")).use { container ->
            container.start()
            Database(container.jdbcUrl, container.username, container.password).use { a ->
            Database(container.jdbcUrl, container.username, container.password).use { b ->
                coroutineScope { listOf(a, b).map { async(Dispatchers.IO) { it.migrate() } }.awaitAll() }
                a.query {
                    val drift = MigrationUtils.statementsRequiredForDatabaseMigration(CloudSettingsTable, CloudSessions, CloudSessionEvents, CloudSessionInputs,
                        CloudSessionAttachments, CloudAccessPolicies, CloudSessionTranscripts, withLogs = false)
                    assertEquals(emptyList(), drift, "The reviewed migration matches the Exposed tables")
                }
                val cluster = FakeClusterPort()
                val cipher = CredentialCipher(ByteArray(32) { 41 })
                val groups = Groups()
                val ra = Replica(a, cluster, cipher, groups); val rb = Replica(b, cluster, cipher, groups)
                val (admin, colleague, other) = members(a)
                val profile = administration(ra, admin, setOf(colleague.member.id, other.member.id))
                val session = ready(ra, rb, admin, colleague, profile)
                messagesAndTranscript(ra, rb, admin, colleague, session)
                accessPolicy(ra, rb, admin, colleague, other, groups, profile, session)
                wakeAndRedelivery(ra, rb, admin, colleague, session)
                retentionAndCancellation(ra, rb, admin, colleague, session)
            } }
        }
    }

    private suspend fun members(a: Database): Triple<Principal, Principal, Principal> {
        val team = SqlTeamService(a)
        val password = team.bootstrap()!!
        team.changePassword(team.login("admin", password, "test").principal, password, "cloud admin replacement password")
        val admin = team.login("admin", "cloud admin replacement password", "test").principal
        fun String.member(): Principal = runBlocking {
            val created = team.createMember(admin, this@member, Role.MEMBER)
            team.changePassword(team.login(this@member, created.temporaryPassword, "test").principal, created.temporaryPassword, "$this@member replacement password")
            team.login(this@member, "$this@member replacement password", "test").principal
        }
        return Triple(admin, "cloud-colleague".member(), "cloud-other".member())
    }

    private suspend fun administration(ra: Replica, admin: Principal, assigned: Set<String>): CloudProfile {
        val cluster = ra.admin.saveCluster(admin, null, null, CloudClusterInput("lab", "https://k8s.example.test:6443",
            "-----BEGIN CERTIFICATE-----\nMIIBsynthetic\n-----END CERTIFICATE-----\n", "synthetic-service-account-token-0123456789", listOf("teamcode"), 10, "standard"))
        return ra.admin.saveProfile(admin, null, 0, "toolchain", CloudProfileSpec(cluster.id, "teamcode", "registry.example/toolchain:2026-10"), assigned)
    }

    private suspend fun ready(ra: Replica, rb: Replica, admin: Principal, owner: Principal, profile: CloudProfile): CloudSession {
        val created = (ra.sessions.create(owner, null, CloudSessionDraft("Conversation", profile.id, null, null, null, emptyMap(), null), null) as CloudCreateOutcome.Created).session
        pass(ra); pass(ra)
        val pod = CloudSessionService.podName(created.id, 1)
        ra.cluster.start(pod); pass(ra)
        ra.runner.register(admin.teamId, created.id, 1, pod)
        rb.runner.heartbeat(admin.teamId, created.id, 1, ready = true)
        return ra.store.find(admin.teamId, created.id)!!.also { assertEquals(READY, it.state) }
    }

    private suspend fun messagesAndTranscript(ra: Replica, rb: Replica, admin: Principal, colleague: Principal, s: CloudSession) {
        val team = admin.teamId
        failure("invalid_message") { ra.conversation.send(colleague, s.id, InputRequest(" ", true, null, null, null)) }
        // Idempotency: the same key replays the first acknowledgement, a different text with it is refused.
        val first = ra.conversation.send(colleague, s.id, InputRequest("Fix the relist retry", true, "msg-1", "d1", null))
        assertIs<InputOutcome.Accepted>(first)
        assertEquals(CloudInputStatus.QUEUED, first.input.status)
        assertFalse(first.input.woke, "A running session is not woken")
        val replay = rb.conversation.send(colleague, s.id, InputRequest("Fix the relist retry", true, "msg-1", "d1", null))
        assertIs<InputOutcome.Replayed>(replay); assertEquals(first.input.id, replay.input.id)
        failure("idempotency_conflict") { rb.conversation.send(colleague, s.id, InputRequest("Something else", true, "msg-1", "d2", null)) }
        val interrupt = ra.conversation.interrupt(colleague, s.id, InputRequest(null, false, null, null, null))

        // Interrupts are delivered first; delivered inputs are not delivered twice.
        val delivered = rb.agent.inputs(team, s.id, 1, 0)
        assertEquals(listOf(CloudInputKind.INTERRUPT, CloudInputKind.MESSAGE), delivered.map { it.kind })
        assertEquals(emptyList(), ra.agent.inputs(team, s.id, 1, 0))
        failure("execution_fenced") { ra.agent.inputs(team, s.id, 2, 0) }
        ra.agent.acknowledge(team, s.id, 1, listOf(RunnerInputAck(interrupt.input.id, false, "no_turn"), RunnerInputAck(first.input.id, true, null)))
        val consumed = ra.conversation.input(colleague, s.id, first.input.id)
        assertEquals(CloudInputStatus.CONSUMED, consumed.status); assertNotNull(consumed.eventSeq)
        assertEquals("no_turn", ra.conversation.input(colleague, s.id, interrupt.input.id).reason)

        // Runner events: a retried batch is stored once, sequence numbers are per session, turns drive ready/active.
        val batch = listOf(
            RunnerEvent(1, CloudEventType.STATUS, """{"conversation":"resumable","conversationId":"c-1"}""", conversation = ConversationResume.RESUMABLE),
            RunnerEvent(2, CloudEventType.TURN, """{"phase":"started"}""", turn = TurnMarker.STARTED),
            RunnerEvent(3, CloudEventType.AGENT_MESSAGE, """{"text":"Looking at the retry."}"""),
        )
        val acks = coroutineScope { listOf(ra, rb).map { r -> async(Dispatchers.IO) { r.agent.events(team, s.id, 1, batch) } }.awaitAll() }
        assertTrue(acks.all { it.acceptedThrough == 3L })
        var current = ra.store.find(team, s.id)!!
        assertEquals(ACTIVE, current.state); assertEquals(ConversationResume.RESUMABLE, current.resumeConversation)
        val gap = rb.agent.events(team, s.id, 1, listOf(RunnerEvent(5, CloudEventType.AGENT_MESSAGE, """{"text":"later"}""")))
        assertEquals(3, gap.acceptedThrough, "A gap is not stored; the runner resends from acceptedThrough + 1")
        rb.agent.events(team, s.id, 1, listOf(RunnerEvent(4, CloudEventType.TURN, """{"phase":"completed"}""", turn = TurnMarker.ENDED)))
        current = ra.store.find(team, s.id)!!
        assertEquals(READY, current.state)
        failure("invalid_events") { ra.agent.events(team, s.id, 1, listOf(RunnerEvent(6, CloudEventType.INPUT, "{}"))) }

        val page = rb.conversation.events(colleague, s.id, 0, null, 100)
        assertEquals((1L..page.lastSeq).toList(), page.events.map { it.seq }, "Sequence numbers have no gap and no duplicate")
        assertEquals(listOf(CloudEventType.INPUT, CloudEventType.USER_MESSAGE, CloudEventType.STATUS, CloudEventType.TURN, CloudEventType.AGENT_MESSAGE, CloudEventType.TURN),
            page.events.map { it.type })
        assertTrue(page.events[1].data.contains("Fix the relist retry"))
        val backward = ra.conversation.events(colleague, s.id, null, page.lastSeq + 1, 2)
        assertEquals(listOf(page.lastSeq - 1, page.lastSeq), backward.events.map { it.seq }); assertEquals(page.lastSeq - 1, backward.nextBefore)
        failure("stale_view") { ra.conversation.send(colleague, s.id, InputRequest("Based on an old view", true, null, null, 1)) }

        // Following the stream: every event once, in order, ending when the session ends (checked in retention).
        val followed = mutableListOf<Long>()
        withTimeout(5_000) {
            val job = launch { ra.conversation.follow(colleague, s.id, 2, emit = { followed += it.map { e -> e.seq } }, idle = {}) }
            while (followed.size < page.events.size - 2) delay(10)
            job.cancel()
        }
        assertEquals((3L..page.lastSeq).toList(), followed)

        // A secret pasted into a message reaches the agent but is masked in the transcript.
        val secret = ra.conversation.send(colleague, s.id, InputRequest("use ghp_${"a".repeat(36)} please", true, null, null, null))
        rb.agent.inputs(team, s.id, 1, 0).single().also { assertTrue(it.text!!.contains("ghp_")) }
        ra.agent.acknowledge(team, s.id, 1, listOf(RunnerInputAck(secret.input.id, true, null)))
        val masked = ra.conversation.events(colleague, s.id, null, Long.MAX_VALUE, 1).events.single()
        assertFalse(masked.data.contains("ghp_a")); assertTrue(masked.data.contains(CloudConversationRules.MASK))
    }

    private suspend fun accessPolicy(ra: Replica, rb: Replica, admin: Principal, colleague: Principal, other: Principal, groups: Groups, profile: CloudProfile, s: CloudSession) {
        val team = admin.teamId
        // Default policy: members use their own sessions only; another member does not even see it.
        failure("not_found") { ra.conversation.events(other, s.id, null, null, 10) }
        failure("not_found") { ra.conversation.attach(other, s.id) }
        failure("forbidden") { ra.access.policy(colleague) }
        assertEquals(CloudAccessPolicy.DEFAULT, ra.access.policy(admin))

        // A member rule with team scope lets a reviewer read every transcript, nothing more.
        failure("member_not_found") { ra.access.savePolicy(admin, 0, listOf(CloudAccessRule(CloudAccessSubject.MemberSubject("missing"), setOf(CloudPermission.TRANSCRIPT), CloudAccessScope.TEAM))) }
        failure("group_not_found") { ra.access.savePolicy(admin, 0, listOf(CloudAccessRule(CloudAccessSubject.GroupSubject("reviewers"), setOf(CloudPermission.TRANSCRIPT), CloudAccessScope.TEAM))) }
        val everyone = CloudAccessPolicy.DEFAULT.rules.single()
        val reviewer = CloudAccessRule(CloudAccessSubject.MemberSubject(other.member.id), setOf(CloudPermission.TRANSCRIPT), CloudAccessScope.TEAM)
        val saved = ra.access.savePolicy(admin, 0, listOf(everyone, reviewer))
        failure("access_policy_conflict") { rb.access.savePolicy(admin, 0, listOf(everyone)) }
        assertEquals(1, saved.version)
        assertTrue(rb.conversation.events(other, s.id, null, null, 10).events.isNotEmpty())
        failure("cloud_permission_denied") { rb.conversation.send(other, s.id, InputRequest("hello", true, null, null, null)) }
        failure("cloud_permission_denied") { rb.conversation.attach(other, s.id) }
        failure("not_found") { rb.sessions.get(other, s.id) }

        // Group rules: membership comes from the group directory.
        groups.members["operators"] = setOf(other.member.id)
        val operators = CloudAccessRule(CloudAccessSubject.GroupSubject("operators"), setOf(CloudPermission.ATTACH, CloudPermission.MESSAGE, CloudPermission.TRANSCRIPT), CloudAccessScope.TEAM)
        rb.access.savePolicy(admin, 1, listOf(everyone, operators))
        val lease = ra.conversation.attach(other, s.id)
        assertEquals(setOf(CloudPermission.TRANSCRIPT, CloudPermission.MESSAGE), lease.permissions)
        assertNotNull(rb.conversation.renew(other, s.id, lease.id))
        failure("attachment_expired") { rb.conversation.renew(colleague, s.id, lease.id) }
        rb.conversation.detach(other, s.id, lease.id)
        failure("attachment_expired") { ra.conversation.renew(other, s.id, lease.id) }
        groups.members["operators"] = emptySet()
        failure("not_found") { ra.conversation.attach(other, s.id) }

        // Removing create from members refuses creation, also on their behalf; administrators keep every right.
        ra.access.savePolicy(admin, 2, listOf(CloudAccessRule(CloudAccessSubject.Everyone, setOf(CloudPermission.ATTACH, CloudPermission.MESSAGE, CloudPermission.TRANSCRIPT), CloudAccessScope.OWN)))
        val draft = CloudSessionDraft("Refused", profile.id, null, null, null, emptyMap(), null)
        failure("cloud_permission_denied") { ra.sessions.create(colleague, null, draft, null) }
        failure("cloud_permission_denied") { ra.sessions.create(admin, colleague.member.id, draft, null) }
        assertTrue(rb.conversation.events(admin, s.id, null, null, 1).events.isNotEmpty())
        ra.access.savePolicy(admin, 3, CloudAccessPolicy.DEFAULT.rules)
        assertEquals(4, ra.database.query { AuditEvents.selectAll().where { AuditEvents.action eq "cloud.access.updated" }.count() })
        assertEquals(team, s.teamId)
    }

    private suspend fun wakeAndRedelivery(ra: Replica, rb: Replica, admin: Principal, colleague: Principal, s: CloudSession) {
        val team = admin.teamId
        // A message delivered to an execution that ends unconsumed is delivered again to the next one.
        val unconsumed = ra.conversation.send(colleague, s.id, InputRequest("Survives the pod", true, null, null, null)).let { (it as InputOutcome.Accepted).input }
        assertEquals(unconsumed.id, rb.agent.inputs(team, s.id, 1, 0).single().id)

        // Pausing: a message sent now waits, then wakes the session once it is paused.
        ra.sessions.pause(colleague, null, s.id, false)
        pass(ra)
        assertEquals(PAUSING, ra.store.find(team, s.id)!!.state)
        assertEquals(emptyList(), ra.agent.inputs(team, s.id, 1, 0), "A pausing session receives no new input")
        val queued = rb.conversation.send(colleague, s.id, InputRequest("Continue after the pause", true, null, null, null)) as InputOutcome.Accepted
        assertFalse(queued.input.woke)
        assertEquals(0, ra.conversation.wakePending(), "Nothing wakes while the session is still pausing")
        rb.runner.checkpoint(team, s.id, 1, completed = true)
        pass(rb)
        assertEquals(PAUSED, ra.store.find(team, s.id)!!.state)
        assertEquals(1, rb.conversation.wakePending())
        assertEquals(DesiredState.RUNNING, ra.store.find(team, s.id)!!.desiredState)
        assertEquals(1, ra.database.query { AuditEvents.selectAll().where { (AuditEvents.action eq "cloud.session.woken") and (AuditEvents.subjectId eq s.id) }.count() })
        assertEquals(0, ra.conversation.wakePending(), "A wake request is handled once")

        // Resume into epoch 2: the unconsumed message comes first, then the queued one.
        val pod = CloudSessionService.podName(s.id, 2)
        repeat(10) { if (pod !in ra.cluster.podNames()) pass(ra) }
        ra.cluster.start(pod); pass(ra)
        ra.runner.register(team, s.id, 2, pod)
        rb.runner.heartbeat(team, s.id, 2, ready = true)
        assertEquals(READY, ra.store.find(team, s.id)!!.state)
        val redelivered = ra.agent.inputs(team, s.id, 2, 0)
        assertEquals(listOf(unconsumed.id, queued.input.id), redelivered.map { it.id })
        assertEquals(listOf(2, 1), redelivered.map { it.attempts })
        rb.agent.acknowledge(team, s.id, 2, redelivered.map { RunnerInputAck(it.id, true, null) })
        failure("execution_fenced") { ra.agent.acknowledge(team, s.id, 1, emptyList()) }

        // A delivery lost on the way to this same execution is offered again once it went unacknowledged.
        val lost = (ra.conversation.send(colleague, s.id, InputRequest("Lost response", true, null, null, null)) as InputOutcome.Accepted).input
        assertEquals(lost.id, rb.agent.inputs(team, s.id, 2, 0).single().id)
        assertEquals(emptyList(), ra.agent.inputs(team, s.id, 2, 0), "A fresh delivery is not repeated")
        ra.database.query { CloudSessionInputs.update({ CloudSessionInputs.id eq lost.id }) { it[deliveredAt] = 1 } }
        val again = ra.agent.inputs(team, s.id, 2, 0).single()
        assertEquals(lost.id to 2, again.id to again.attempts)
        rb.agent.acknowledge(team, s.id, 2, listOf(RunnerInputAck(lost.id, true, null)))

        // Wake on a paused session happens synchronously; wake = false leaves it paused.
        ra.sessions.pause(colleague, null, s.id, true)
        pass(ra); rb.runner.checkpoint(team, s.id, 2, completed = true); pass(ra)
        assertEquals(PAUSED, ra.store.find(team, s.id)!!.state)
        val stay = rb.conversation.send(colleague, s.id, InputRequest("Later", false, null, null, null)) as InputOutcome.Accepted
        assertFalse(stay.input.woke); assertEquals(DesiredState.PAUSED, ra.store.find(team, s.id)!!.desiredState)
        failure("nothing_to_interrupt") { ra.conversation.interrupt(colleague, s.id, InputRequest(null, false, null, null, null)) }
        val wake = rb.conversation.send(colleague, s.id, InputRequest("Now", true, null, null, null)) as InputOutcome.Accepted
        assertTrue(wake.input.woke); assertEquals(DesiredState.RUNNING, ra.store.find(team, s.id)!!.desiredState)

        // The pending cap.
        ra.sessions.pause(colleague, null, s.id, true)
        val room = CloudConversationRules.MAX_PENDING_INPUTS - ra.conversation.inputs(colleague, s.id, null, 200).inputs.count { !it.status.final }
        repeat(room) { ra.conversation.send(colleague, s.id, InputRequest("filler $it", false, null, null, null)) }
        failure("too_many_pending_inputs") { ra.conversation.send(colleague, s.id, InputRequest("one too many", false, null, null, null)) }
    }

    private suspend fun retentionAndCancellation(ra: Replica, rb: Replica, admin: Principal, colleague: Principal, s: CloudSession) {
        val team = admin.teamId
        // Deleting the session cancels what is still pending and ends a follower once it read everything.
        ra.sessions.delete(colleague, null, s.id)
        failure("session_not_running") { ra.conversation.send(colleague, s.id, InputRequest("too late", true, null, null, null)) }
        assertTrue(rb.conversations.cancelEnded(50) >= CloudConversationRules.MAX_PENDING_INPUTS)
        assertTrue(ra.conversation.inputs(colleague, s.id, null, 200).inputs.none { !it.status.final })
        repeat(40) { if (ra.store.find(team, s.id)!!.state == TERMINATED) return@repeat; pass(ra) }
        assertEquals(TERMINATED, ra.store.find(team, s.id)!!.state)
        val last = ra.conversation.events(colleague, s.id, 0, null, 1).lastSeq
        var seen = 0L
        withTimeout(10_000) { ra.conversation.follow(colleague, s.id, last - 1, emit = { seen = it.last().seq }, idle = {}) }
        assertEquals(last, seen, "The stream ends after the last event of an ended session")

        // Retention: only events older than the team setting are deleted, and the setting can keep them forever.
        failure("invalid_cloud_settings") { ra.admin.saveSettings(admin, CloudSettings(transcriptRetentionDays = 0)) }
        ra.admin.saveSettings(admin, CloudSettings(transcriptRetentionDays = 30))
        assertEquals(30, rb.admin.settings(admin).transcriptRetentionDays)
        ra.database.query { CloudSessionEvents.update({ (CloudSessionEvents.sessionId eq s.id) and (CloudSessionEvents.seq lessEq 3L) }) { it[at] = 1 } }
        ra.admin.saveSettings(admin, CloudSettings(transcriptRetentionDays = null))
        assertEquals(0, rb.conversations.purgeTranscripts(1_000), "No retention keeps everything")
        ra.admin.saveSettings(admin, CloudSettings(transcriptRetentionDays = 30))
        assertEquals(3, rb.conversations.events(s.id, 0, null, 200).events.count { it.seq <= 3 } )
        assertTrue(rb.conversations.purgeTranscripts(1_000) >= 3)
        val remaining = ra.conversation.events(colleague, s.id, 0, null, 200)
        assertEquals(4L, remaining.firstSeq, "Expired events are gone and the page says where the transcript starts")
        assertEquals(last, remaining.lastSeq)
        ra.database.query { CloudSessionEvents.update({ CloudSessionEvents.sessionId eq s.id }) { it[at] = 1 } }
        rb.conversations.purgeTranscripts(1_000)
        assertEquals(emptyList(), ra.conversation.events(colleague, s.id, 0, null, 200).events)
        assertEquals(last, rb.conversations.lastSeq(s.id), "Numbering never restarts after a purge")
    }

    private suspend fun makeDue(r: Replica) = r.database.query { CloudSessions.update({ CloudSessions.reconcileAfter.isNotNull() }) { it[reconcileAfter] = 0 } }
    private suspend fun pass(r: Replica) { makeDue(r); r.reconciler() }
    private suspend fun failure(code: String, block: suspend () -> Unit) = assertEquals(code, assertFailsWith<TeamFailure> { block() }.code)
}
