package me.nathanfallet.teamcode.infrastructure.cloud

import java.util.UUID
import kotlinx.coroutines.*
import me.nathanfallet.teamcode.domain.cloud.*
import me.nathanfallet.teamcode.domain.team.Principal
import me.nathanfallet.teamcode.domain.team.TeamFailure
import me.nathanfallet.teamcode.domain.waits.*
import me.nathanfallet.teamcode.infrastructure.database.SqlOrchestrationStore
import me.nathanfallet.teamcode.infrastructure.database.SqlWaitStore
import me.nathanfallet.teamcode.infrastructure.database.tables.*
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*
import org.jetbrains.exposed.v1.migration.jdbc.MigrationUtils
import org.junit.jupiter.api.Timeout
import kotlin.test.*

/**
 * Session orchestration on real MySQL (docs/orchestration.md): the parent/root/depth columns, the cap of a parent's children across two
 * replicas, the keyset list of children, and the `child` waits resolved where a child's turn ends, its permission request opens or it
 * ends, then delivered by the waits worker's existing pass.
 */
class OrchestrationPersistenceTest {
    private fun waker(r: AutomationReplica) = object : WaitSessions {
        override suspend fun authorize(actor: Principal, sessionId: String) = r.conversation.authorizeWaitAnswer(actor, sessionId)
        override suspend fun deliver(actor: Principal, sessionId: String, text: String, clientKey: String) = r.conversation.queueWaitAnswer(actor, sessionId, text, clientKey)
    }

    @Test @Timeout(240)
    fun childSessionsAndTheirWaits() = runBlocking {
        AutomationWorld.run(83) { w ->
            w.a.database.query {
                assertEquals(emptyList(), MigrationUtils.statementsRequiredForDatabaseMigration(CloudSessions, Waits, withLogs = false), "The reviewed migrations match the Exposed tables")
            }
            // The profile's orchestration settings are part of its published version.
            val spec = w.profile.let { w.a.sessionStore.profileVersion(w.teamId, it.id, it.version)!! }
            val published = w.a.admin.saveProfile(w.admin, w.profile.id, w.profile.version, w.profile.name, spec.copy(orchestration = CloudOrchestrationSpec(true, 2, 2, emptyList())), setOf(w.member.member.id))
            assertEquals(CloudOrchestrationSpec(true, 2, 2, emptyList()), w.a.sessionStore.profileVersion(w.teamId, published.id, published.version)!!.orchestration)
            assertEquals(CloudOrchestrationSpec(), w.a.sessionStore.profileVersion(w.teamId, w.profile.id, w.profile.version)!!.orchestration, "an older version stays off")

            val parent = (w.a.sessions.create(w.member, null, CloudSessionDraft("Main agent", published.id, null, null, null, emptyMap(), null), null) as CloudCreateOutcome.Created).session
            assertNull(parent.parentSessionId); assertEquals(0, parent.depth); assertEquals(parent.id, parent.root)
            theCapOfChildrenHoldsAcrossReplicas(w, parent)
            childrenAreListedByKeyset(w, parent)
            childWaitsResolveWhereTheChildChanges(w, parent)
        }
    }

    private fun childOf(parent: CloudSession, name: String) = parent.copy(id = UUID.randomUUID().toString(), name = name, parentSessionId = parent.id, rootSessionId = parent.root,
        depth = parent.depth + 1, labels = mapOf("teamcode.parent" to parent.id), workspace = parent.workspace.copy(claimName = "tc-ws-$name"), generation = 0)

    private suspend fun theCapOfChildrenHoldsAcrossReplicas(w: AutomationWorld, parent: CloudSession) {
        val link = ChildSessionLink(parent.id, parent.root, 1, 2)
        val actor = CloudActor(w.member, w.member.member.id)
        val outcomes = coroutineScope {
            (0 until 6).map { i ->
                async(Dispatchers.IO) {
                    val store = if (i % 2 == 0) w.a.sessionStore else w.b.sessionStore
                    runCatching { store.insertChild(childOf(parent, "race-$i"), actor, IdempotencyRequest("POST /cloud/children", "race-$i", "d$i"), 20, link) }
                }
            }.awaitAll()
        }
        assertEquals(2, outcomes.count { it.isSuccess }, "two children, the cap, across both replicas")
        assertTrue(outcomes.filter { it.isFailure }.all { (it.exceptionOrNull() as TeamFailure).code == "child_session_limit" })
        val stored = w.a.database.query { CloudSessions.selectAll().where { CloudSessions.parentSessionId eq parent.id }.toList() }
        assertEquals(2, stored.size)
        assertTrue(stored.all { it[CloudSessions.depth] == 1 && it[CloudSessions.rootSessionId] == parent.id })
        assertEquals(2, w.a.database.query { AuditEvents.selectAll().where { (AuditEvents.action eq "cloud.session.child_created") and (AuditEvents.details eq parent.id) }.count() }.toInt())
        // A child being deleted no longer counts: a new one fits.
        val first = w.a.sessionStore.find(w.teamId, stored.first()[CloudSessions.id])!!
        w.a.sessions.delete(w.member, null, first.id)
        w.a.sessionStore.insertChild(childOf(parent, "after-delete"), actor, IdempotencyRequest("POST /cloud/children", "after", "d"), 20, link)
        refusedWith("child_session_limit") { w.b.sessionStore.insertChild(childOf(parent, "over"), actor, IdempotencyRequest("POST /cloud/children", "over", "d"), 20, link) }
        // The same key from the same member replays the first child.
        val replay = w.a.sessionStore.insertChild(childOf(parent, "again"), actor, IdempotencyRequest("POST /cloud/children", "after", "d"), 20, link)
        assertTrue(replay is CloudCreateOutcome.Replayed)
    }

    private suspend fun childrenAreListedByKeyset(w: AutomationWorld, parent: CloudSession) {
        val store = SqlOrchestrationStore(w.a.database)
        val first = store.children(w.teamId, parent.id, null, 2)
        assertEquals(2, first.sessions.size); assertNotNull(first.nextBeforeId)
        val second = store.children(w.teamId, parent.id, first.nextBeforeId, 2)
        val all = first.sessions + second.sessions
        assertEquals(3, all.size); assertNull(second.nextBeforeId)
        assertEquals(all.map { it.id }.toSet().size, 3)
        assertTrue(all.all { it.parentSessionId == parent.id && it.labels["teamcode.parent"] == parent.id })
        assertEquals(emptyList(), store.children(w.teamId, UUID.randomUUID().toString(), null, 10).sessions)
        refusedWith("invalid_cursor") { store.children(w.teamId, parent.id, UUID.randomUUID().toString(), 10) }
    }

    private suspend fun childWaitsResolveWhereTheChildChanges(w: AutomationWorld, parent: CloudSession) {
        val waits = SqlWaitStore(w.a.database)
        val service = WaitService(waits, waker(w.a))
        val actor = CloudActor(w.member, w.member.member.id)
        val link = ChildSessionLink(parent.id, parent.root, 1, 20)
        suspend fun child(name: String) = (w.a.sessionStore.insertChild(childOf(parent, name), actor, IdempotencyRequest("POST /cloud/children", name, name), 20, link) as CloudCreateOutcome.Created).session
        suspend fun ready(id: String) = w.a.database.query { CloudSessions.update({ CloudSessions.id eq id }) { it[state] = CloudSessionState.READY.wire; it[currentEpoch] = 1 } }
        var sequence = 0L
        suspend fun turn(id: String, phase: String) = w.a.conversations.appendRunnerEvents(id, 1,
            listOf(RunnerEvent(++sequence, CloudEventType.TURN, """{"phase":"$phase"}""", if (phase == "started") TurnMarker.STARTED else TurnMarker.ENDED)))
        suspend fun waitFor(childId: String) = service.createChildWait(w.member, parent.id, childId, "Child", 3600, null, null, null).wait
        suspend fun row(id: String) = w.a.database.query { Waits.selectAll().where { Waits.id eq id }.single() }
        suspend fun parentMessages() = w.a.conversation.inputs(w.member, parent.id, null, 50).inputs.filter { it.kind == CloudInputKind.MESSAGE }
        // A child's events start a fresh numbering per session; the helper keeps one counter per test session.
        val busy = child("busy"); ready(busy.id); sequence = 0
        turn(busy.id, "started")
        val open = waitFor(busy.id)
        assertEquals(WaitStatus.OPEN, open.status, "a turn runs: the wait waits")
        assertEquals(busy.id, open.timer?.childSessionId)
        // Another session's turn end changes nothing.
        val other = (w.a.sessions.create(w.member, null, CloudSessionDraft("Unrelated", parent.profileId, null, null, null, emptyMap(), null), null) as CloudCreateOutcome.Created).session
        w.a.conversations.appendRunnerEvents(other.id, 1, listOf(RunnerEvent(1, CloudEventType.TURN, """{"phase":"completed"}""", TurnMarker.ENDED)))
        assertEquals(WaitStatus.OPEN.wire, row(open.id)[Waits.status])
        turn(busy.id, "completed")
        val resolved = row(open.id)
        assertEquals(WaitStatus.RESOLVED.wire, resolved[Waits.status]); assertEquals("child_turn_ended", resolved[Waits.answerAction])
        assertTrue(resolved[Waits.wakePending], "the wake message is owed to the worker's pass")
        assertTrue("\"phase\":\"completed\"" in resolved[Waits.answerValues]!!)
        // The worker's pass (the existing resolveDue) queues the message on the parent, once, past the grace period.
        w.a.database.query { Waits.update({ Waits.id eq open.id }) { it[resolvedAt] = 1L } }
        service.resolveDue(); service.resolveDue()
        val message = parentMessages().single { it.text!!.contains(open.id) }
        assertTrue(message.text!!.startsWith("[TeamCode wait answered] Child\nwait: ${open.id}\naction: the child session ended its turn (child_turn_ended)\nresolved by: TeamCode (a child session)\nchild: ${busy.id}\nphase: completed"), message.text)
        assertFalse(row(open.id)[Waits.wakePending])

        // An idle child (its last turn ended, nothing pending) resolves the wait at once, with no message.
        val idle = waitFor(busy.id)
        assertEquals(WaitStatus.RESOLVED, idle.status); assertEquals("child_turn_ended", idle.answer?.action)
        assertFalse(row(idle.id)[Waits.wakePending])
        // A message pending for the child keeps the wait open: a turn will start.
        w.a.conversation.queueWaitAnswer(w.member, busy.id, "Go on", "go-on")
        assertEquals(WaitStatus.OPEN, waitFor(busy.id).status)

        // A permission request of the child wakes the parent with child_waiting.
        val asking = child("asking"); ready(asking.id); sequence = 0
        turn(asking.id, "started")
        val permission = waitFor(asking.id)
        w.a.conversations.appendRunnerEvents(asking.id, 1, listOf(RunnerEvent(++sequence, CloudEventType.PERMISSION_REQUEST, """{"requestId":"req-1","tool":"Bash"}""")))
        assertEquals("child_waiting", row(permission.id)[Waits.answerAction])
        assertTrue("\"reason\":\"permission\"" in row(permission.id)[Waits.answerValues]!!)

        // A child that ends wakes the parent with child_ended.
        val ending = child("ending"); ready(ending.id); sequence = 0
        turn(ending.id, "started")
        val end = waitFor(ending.id)
        w.a.sessions.delete(w.member, null, ending.id)
        assertEquals("child_ended", row(end.id)[Waits.answerAction])
        assertTrue("\"state\":\"terminating\"" in row(end.id)[Waits.answerValues]!!)
        // And a wait on a child already ended resolves at once.
        assertEquals("child_ended", waitFor(ending.id).answer?.action)
        assertEquals(1, w.a.database.query { AuditEvents.selectAll().where { (AuditEvents.subjectId eq end.id) and (AuditEvents.action eq "wait.child_ended") }.count() }.toInt())
    }
}
