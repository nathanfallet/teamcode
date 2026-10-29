package me.nathanfallet.teamcode.infrastructure.cloud

import kotlinx.coroutines.*
import kotlinx.serialization.json.JsonPrimitive
import me.nathanfallet.teamcode.domain.cloud.CloudCreateOutcome
import me.nathanfallet.teamcode.domain.cloud.CloudInputKind
import me.nathanfallet.teamcode.domain.cloud.CloudSessionDraft
import me.nathanfallet.teamcode.domain.team.Principal
import me.nathanfallet.teamcode.domain.team.TeamFailure
import me.nathanfallet.teamcode.domain.waits.*
import me.nathanfallet.teamcode.domain.team.TeamEventAudience
import me.nathanfallet.teamcode.domain.team.TeamEventType
import me.nathanfallet.teamcode.infrastructure.database.SqlTeamEventStore
import me.nathanfallet.teamcode.infrastructure.database.SqlWaitStore
import me.nathanfallet.teamcode.infrastructure.database.tables.*
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*
import org.jetbrains.exposed.v1.migration.jdbc.MigrationUtils
import org.junit.jupiter.api.Timeout
import kotlin.test.*

/** Waits on real MySQL, with two pools standing for two replicas (the automations' world supplies the team and the cloud). */
class WaitPersistenceTest {
    private class Replica(val service: WaitService)

    /** The conversation service of the replica, as the composition root wires it into the wait service. */
    private fun waker(r: AutomationReplica) = object : WaitSessions {
        override suspend fun authorize(actor: Principal, sessionId: String) = r.conversation.authorizeWaitAnswer(actor, sessionId)
        override suspend fun deliver(actor: Principal, sessionId: String, text: String, clientKey: String) = r.conversation.queueWaitAnswer(actor, sessionId, text, clientKey)
    }

    @Test @Timeout(240)
    fun waitsHoldAcrossReplicas() = runBlocking {
        AutomationWorld.run(71) { w ->
            w.a.database.query {
                assertEquals(emptyList(), MigrationUtils.statementsRequiredForDatabaseMigration(Waits, CloudSessions, withLogs = false), "The reviewed migrations match the Exposed tables")
            }
            val a = Replica(WaitService(SqlWaitStore(w.a.database))); val b = Replica(WaitService(SqlWaitStore(w.b.database)))
            val session = (w.a.sessions.create(w.member, null, CloudSessionDraft("Fix the login", w.profile.id, null, null, null, emptyMap(), null), null) as CloudCreateOutcome.Created).session
            val review = WaitDefinition(
                session.id, "Review the plan", "Plan attached.", assigneeId = w.admin.member.id, refs = listOf(WaitRef("artifact", id = "a1")),
                fields = listOf(WaitField("feedback", FieldType.TEXT, "Feedback", options = emptyList())),
                actions = listOf(WaitAction("approve", "Approve"), WaitAction("request_changes", "Request changes", requires = listOf("feedback"))),
            )
            aWaitRoundTripsWithItsAssigneeAndSession(a, b, w, review)
            theLiveFeedTellsTheAssigneeAndNotTheAsker(a, w, review)
            idempotentCreationAcrossReplicas(a, b, w, review)
            theFirstAnswerWinsAcrossReplicas(a, b, w, review)
            theCapHoldsAcrossReplicas(a, b, w, review)
            sessionPermissionRequestsAreListedOnTheFirstPageOnly(a, w, session.id)
            pagingAndFilters(a, w, review)
            waitsEndWithTheirSession(a, w, review)
            theAnswerWakesTheSession(w)
        }
    }

    private suspend fun aWaitRoundTripsWithItsAssigneeAndSession(a: Replica, b: Replica, w: AutomationWorld, review: WaitDefinition) {
        val created = a.service.create(w.member, review, null)
        assertTrue(created.changed)
        val wait = b.service.get(w.admin, created.wait.id)
        assertEquals(w.admin.member.id, wait.assigneeId); assertEquals("Fix the login", wait.sessionName); assertEquals(w.member.member.id, wait.createdById)
        assertEquals(listOf("approve", "request_changes"), wait.actions.map { it.id }); assertEquals(listOf("feedback"), wait.actions[1].requires)
        assertEquals(listOf(WaitRef("artifact", id = "a1")), wait.refs); assertEquals(WaitStatus.OPEN, wait.status)
        assertEquals(created.wait.id, b.service.get(w.member, created.wait.id).id, "the asker reads it too")
        a.service.cancel(w.member, created.wait.id)
    }

    private suspend fun theLiveFeedTellsTheAssigneeAndNotTheAsker(a: Replica, w: AutomationWorld, review: WaitDefinition) {
        val feed = SqlTeamEventStore(w.a.database)
        val types = setOf(TeamEventType.WAIT_CREATED, TeamEventType.WAIT_RESOLVED, TeamEventType.WAIT_CANCELLED)
        val head = feed.head(w.teamId)
        fun audience(who: Principal, team: Boolean = false) = TeamEventAudience(who.member.id, sessions = true, runs = false, teamSessions = team, teamRuns = false)
        val id = a.service.create(w.member, review, null).wait.id
        a.service.answer(w.admin, id, "approve", emptyMap())
        val cancelled = a.service.create(w.member, review, null).wait.id
        a.service.cancel(w.member, cancelled)
        val assignee = feed.read(w.teamId, audience(w.admin), head, types, 100).events
        assertEquals(listOf(TeamEventType.WAIT_CREATED, TeamEventType.WAIT_RESOLVED, TeamEventType.WAIT_CREATED, TeamEventType.WAIT_CANCELLED), assignee.map { it.type })
        assertEquals(listOf(id, id, cancelled, cancelled), assignee.map { it.subjectId }); assertTrue(assignee.all { it.ownerId == w.admin.member.id })
        assertTrue("\"kind\":\"cloud\"" in assignee.first().data && "\"waitKind\":\"human_request\"" in assignee.first().data, assignee.first().data)
        assertTrue("\"action\":\"approve\"" in assignee[1].data)
        assertEquals(emptyList(), feed.read(w.teamId, audience(w.member), head, types, 100).events, "the asker is not the one asked: the feed stays quiet for them")
    }

    private suspend fun idempotentCreationAcrossReplicas(a: Replica, b: Replica, w: AutomationWorld, review: WaitDefinition) {
        val first = a.service.create(w.member, review, "key-1"); val again = b.service.create(w.member, review, "key-1")
        assertTrue(first.changed); assertFalse(again.changed); assertEquals(first.wait.id, again.wait.id)
        a.service.cancel(w.member, first.wait.id)
    }

    private suspend fun theFirstAnswerWinsAcrossReplicas(a: Replica, b: Replica, w: AutomationWorld, review: WaitDefinition) {
        val id = a.service.create(w.member, review, null).wait.id
        val outcomes = coroutineScope {
            listOf(a to "approve", b to "request_changes").map { (replica, action) ->
                async(Dispatchers.IO) {
                    runCatching { replica.service.answer(w.admin, id, action, if (action == "approve") emptyMap() else mapOf("feedback" to JsonPrimitive("Split step 2"))) }
                }
            }.awaitAll()
        }
        assertEquals(1, outcomes.count { it.isSuccess }, "exactly one answer is recorded")
        assertEquals("wait_closed", (outcomes.single { it.isFailure }.exceptionOrNull() as TeamFailure).code)
        val stored = a.service.get(w.admin, id)
        assertEquals(WaitStatus.RESOLVED, stored.status); assertEquals(outcomes.single { it.isSuccess }.getOrThrow().answer!!.action, stored.answer!!.action)
        assertEquals(w.admin.member.id, stored.answer!!.authorId)
        refusedWith("wait_closed") { b.service.cancel(w.member, id) }
        val audits = w.a.database.query { AuditEvents.selectAll().where { AuditEvents.subjectId eq id }.map { it[AuditEvents.action] } }
        assertEquals(setOf("wait.created", "wait.answered"), audits.toSet(), "creation and the one answer are audited, the refused one is not")
        assertEquals(2, audits.size)
    }

    private suspend fun theCapHoldsAcrossReplicas(a: Replica, b: Replica, w: AutomationWorld, review: WaitDefinition) {
        val results = coroutineScope {
            (1..30).map { n -> async(Dispatchers.IO) { runCatching { (if (n % 2 == 0) a else b).service.create(w.member, review, null) } } }.awaitAll()
        }
        assertEquals(WaitRules.MAX_OPEN_PER_SESSION, results.count { it.isSuccess }, "twenty open waits fit, whichever replica takes them")
        assertTrue(results.filter { it.isFailure }.all { (it.exceptionOrNull() as TeamFailure).code == "wait_limit" })
        // Clean the session so the next steps start from none.
        val open = a.service.list(w.admin, WaitService.ALL, "open", null, 100).waits
        open.forEach { a.service.cancel(w.member, it.id) }
    }

    private suspend fun sessionPermissionRequestsAreListedOnTheFirstPageOnly(a: Replica, w: AutomationWorld, sessionId: String) {
        val stored = a.service.create(w.member, WaitDefinition(sessionId, "Log in to the shop", assigneeId = w.member.member.id), null).wait
        w.a.database.query {
            CloudSessions.update({ CloudSessions.id eq sessionId }) { it[awaitingReason] = "permission"; it[awaitingRequestId] = "req-1"; it[awaitingSince] = 5_000_000_000_000L; it[awaitingEventSeq] = 3; it[awaitingEpoch] = 1 }
        }
        val first = a.service.list(w.member, null, "open", null, 1)
        assertEquals(listOf(WaitSource.SESSION, WaitSource.WAIT), first.waits.map { it.source }, "the permission request leads, then the stored wait")
        val projected = first.waits.first()
        assertEquals(WaitKind.PERMISSION, projected.kind); assertEquals("req-1", projected.requestId); assertEquals(sessionId, projected.id); assertEquals(w.member.member.id, projected.assigneeId)
        assertEquals(stored.id, first.waits.last().id)
        assertEquals(emptyList(), a.service.list(w.member, null, "open", stored.id, 5).waits.filter { it.source == WaitSource.SESSION }, "later pages carry stored waits only")
        assertEquals(sessionId, a.service.get(w.member, sessionId).id, "a session wait is readable by its id")
        assertEquals(0, a.service.list(w.admin, null, "open", null, 5).waits.size, "the administrator's own list holds only what is asked of them")
        assertEquals(2, a.service.list(w.admin, WaitService.ALL, "open", null, 5).waits.size)
        refusedWith("answer_through_session") { a.service.answer(w.member, sessionId, "done", emptyMap()) }
        w.a.database.query { CloudSessions.update({ CloudSessions.id eq sessionId }) { it[awaitingReason] = null; it[awaitingRequestId] = null; it[awaitingSince] = null; it[awaitingEventSeq] = null; it[awaitingEpoch] = null } }
        assertEquals(1, a.service.list(w.admin, WaitService.ALL, "open", null, 5).waits.size, "answered requests disappear")
        a.service.cancel(w.member, stored.id)
    }

    private suspend fun pagingAndFilters(a: Replica, w: AutomationWorld, review: WaitDefinition) {
        val ids = (1..5).map { a.service.create(w.member, review, null).wait.id }
        a.service.answer(w.admin, ids[0], "approve", emptyMap())
        val seen = mutableListOf<String>(); var cursor: String? = null
        do { val page = a.service.list(w.admin, null, "open", cursor, 2); seen += page.waits.map { it.id }; cursor = page.nextBefore } while (cursor != null)
        assertEquals(4, seen.size); assertEquals(seen.size, seen.toSet().size, "no repeats"); assertFalse(ids[0] in seen)
        assertEquals(listOf(ids[0]), a.service.list(w.admin, null, "resolved", null, 10).waits.map { it.id }.filter { it == ids[0] })
        refusedWith("invalid_cursor") { a.service.list(w.admin, null, "open", "00000000-0000-0000-0000-000000000000", 5) }
        assertEquals(emptyList(), a.service.list(w.member, null, "open", null, 10).waits, "nothing is asked of the session's owner")
        ids.drop(1).forEach { a.service.cancel(w.member, it) }
    }

    private suspend fun waitsEndWithTheirSession(a: Replica, w: AutomationWorld, review: WaitDefinition) {
        val id = a.service.create(w.member, review, null).wait.id
        w.a.database.query { CloudSessions.deleteWhere { CloudSessions.id eq review.sessionId } }
        refusedWith("not_found") { a.service.get(w.admin, id) }
        assertEquals(0L, w.a.database.query { Waits.selectAll().count() })
    }

    /** The answer is a message of the session written by the one who answered; the same answer sent to the other replica queues nothing more. */
    private suspend fun theAnswerWakesTheSession(w: AutomationWorld) {
        val session = (w.a.sessions.create(w.member, null, CloudSessionDraft("Wake me", w.profile.id, null, null, null, emptyMap(), null), null) as CloudCreateOutcome.Created).session
        val a = Replica(WaitService(SqlWaitStore(w.a.database), waker(w.a))); val b = Replica(WaitService(SqlWaitStore(w.b.database), waker(w.b)))
        val id = a.service.create(w.member, WaitDefinition(session.id, "Account access", assigneeId = w.admin.member.id), null).wait.id
        a.service.answer(w.admin, id, "done", emptyMap())
        b.service.answer(w.admin, id, "done", emptyMap())
        val inputs = w.a.conversation.inputs(w.member, session.id, null, 10).inputs
        val message = inputs.single { it.kind == CloudInputKind.MESSAGE }
        assertEquals(w.admin.member.id, message.authorId); assertTrue(message.wake)
        assertTrue(message.text!!.startsWith("[TeamCode wait answered] Account access\nwait: $id\naction: Done (done)\nanswered by: ${w.admin.member.username}"), message.text)
        refusedWith("wait_closed") { b.service.answer(w.admin, id, "other", emptyMap()) }
    }
}
