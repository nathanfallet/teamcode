package me.nathanfallet.teamcode.infrastructure.cloud

import kotlinx.coroutines.*
import kotlinx.serialization.json.JsonPrimitive
import me.nathanfallet.teamcode.domain.cloud.CloudCreateOutcome
import me.nathanfallet.teamcode.domain.cloud.TurnMarker
import me.nathanfallet.teamcode.domain.cloud.RunnerEvent
import me.nathanfallet.teamcode.domain.cloud.CloudSessionService
import me.nathanfallet.teamcode.domain.cloud.CloudEventType
import me.nathanfallet.teamcode.domain.cloud.CloudInputKind
import me.nathanfallet.teamcode.domain.cloud.CloudSessionDraft
import me.nathanfallet.teamcode.domain.team.Principal
import me.nathanfallet.teamcode.domain.team.ServiceScope
import me.nathanfallet.teamcode.domain.team.TeamFailure
import me.nathanfallet.teamcode.domain.waits.*
import me.nathanfallet.teamcode.domain.team.TeamEventAudience
import me.nathanfallet.teamcode.domain.team.TeamEventType
import me.nathanfallet.teamcode.infrastructure.database.SqlTeamEventStore
import me.nathanfallet.teamcode.infrastructure.database.SqlWaitStore
import me.nathanfallet.teamcode.infrastructure.database.REMOTE_SESSION_SCOPES
import me.nathanfallet.teamcode.infrastructure.database.SqlWorkflowRunStore
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
            anExecutionCredentialIsConfinedToItsSession(a, b, w)
            aFailedTurnOpensAWaitForTheOwner(w)
            timedWaitsResolveOnceAcrossReplicas(w)
        }
    }

    /** Waits for a time or an event on real MySQL: claimed once by two replicas, an event and a timeout racing, a secret that is only a hash. */
    private suspend fun timedWaitsResolveOnceAcrossReplicas(w: AutomationWorld) {
        suspend fun draft(name: String) = (w.a.sessions.create(w.member, null, CloudSessionDraft(name, w.profile.id, null, null, null, emptyMap(), null), null) as CloudCreateOutcome.Created).session
        val a = Replica(WaitService(SqlWaitStore(w.a.database), waker(w.a))); val b = Replica(WaitService(SqlWaitStore(w.b.database), waker(w.b)))
        suspend fun messages(sessionId: String) = w.a.conversation.inputs(w.member, sessionId, null, 50).inputs.filter { it.kind == CloudInputKind.MESSAGE }
        suspend fun makeDue(id: String) { w.a.database.query { Waits.update({ Waits.id eq id }) { it[dueAt] = 1L } } }
        val feed = SqlTeamEventStore(w.a.database); val head = feed.head(w.teamId)
        val owner = TeamEventAudience(w.member.member.id, sessions = true, runs = false, teamSessions = false, teamRuns = false)

        // A due wait is resolved and the session woken exactly once, whatever the two replicas do together.
        val session = draft("Timed")
        val time = a.service.create(w.member, WaitDefinition(session.id, "Resume at 14:30", resolver = WaitResolver.TIME, schedule = WaitSchedule(afterSeconds = 60, resumeMessage = "Check the deploy")), null).wait
        assertEquals(0, a.service.resolveDue() + b.service.resolveDue(), "not due yet")
        assertTrue(SqlWorkflowRunStore(w.a.database).sessionHasOpenWait(session.id), "an open timed wait counts as an open wait (the Stop hook lets the agent end its turn)")
        assertEquals(emptyList(), a.service.list(w.member, null, "open", null, 50).waits.filter { it.id == time.id }, "never in Waiting for you")
        assertEquals(listOf(time.id), b.service.list(w.member, null, "open", null, 50, resolver = "time").waits.map { it.id })
        makeDue(time.id)
        val resolved = coroutineScope { listOf(a, b, a, b).map { r -> async(Dispatchers.IO) { r.service.resolveDue() } }.awaitAll() }
        assertEquals(1, resolved.sum(), "exactly one scan resolved it")
        val stored = a.service.get(w.member, time.id)
        assertEquals(WaitStatus.RESOLVED, stored.status); assertEquals("time_reached", stored.answer!!.action)
        val message = messages(session.id).single()
        assertTrue(message.wake); assertEquals(w.member.member.id, message.authorId)
        assertTrue(message.text!!.startsWith("[TeamCode wait answered] Resume at 14:30\nwait: ${time.id}\naction: the time came (time_reached)\nresolved by: TeamCode (the clock)\nresume: Check the deploy"), message.text)
        assertEquals(1, w.a.database.query { AuditEvents.selectAll().where { (AuditEvents.subjectId eq time.id) and (AuditEvents.action eq "wait.time_reached") }.count() }.toInt())
        val events = feed.read(w.teamId, owner, head, setOf(TeamEventType.WAIT_RESOLVED), 100).events.filter { it.subjectId == time.id }
        assertEquals(1, events.size); assertTrue("\"waitKind\":\"timed\"" in events.single().data && "\"action\":\"time_reached\"" in events.single().data, events.single().data)
        assertFalse(SqlWorkflowRunStore(w.a.database).sessionHasOpenWait(session.id))
        assertFalse(w.a.database.query { Waits.select(Waits.wakePending).where { Waits.id eq time.id }.single()[Waits.wakePending] }, "the debt of the wake message is cleared")

        // The secret of an event wait is a hash in the table and nowhere else; a wrong token is a 404.
        val ci = draft("CI")
        val event = a.service.create(w.member, WaitDefinition(ci.id, "CI of the PR", resolver = WaitResolver.EVENT, schedule = WaitSchedule(source = EventSource.WEBHOOK, timeoutSeconds = 3600)), null)
        val secret = event.secret!!
        val row = w.a.database.query { Waits.selectAll().where { Waits.id eq event.wait.id }.single() }
        assertEquals(java.security.MessageDigest.getInstance("SHA-256").digest(secret.encodeToByteArray()).joinToString("") { "%02x".format(it) }, row[Waits.eventTokenHash])
        assertFalse(listOf(row[Waits.title], row[Waits.body], row[Waits.refs], row[Waits.fieldDefs], row[Waits.actions], row[Waits.resumeMessage], row[Waits.eventTokenHash]).any { it?.contains(secret) == true })
        refusedWith("not_found") { b.service.receiveEvent(event.wait.id, "wev_wrong", ByteArray(0)) }
        refusedWith("not_found") { b.service.receiveEvent(event.wait.id, null, ByteArray(0)) }
        assertEquals(WaitStatus.OPEN, a.service.get(w.member, event.wait.id).status)
        b.service.receiveEvent(event.wait.id, secret, """{"result":"success","note":"green"}""".encodeToByteArray())
        refusedWith("not_found") { a.service.receiveEvent(event.wait.id, secret, ByteArray(0)) }
        assertEquals("event_received", a.service.get(w.member, event.wait.id).answer!!.action)
        assertTrue("note: green" in messages(ci.id).single().text!!)
        assertFalse(secret in messages(ci.id).single().text!!)

        // An event and a timeout race: exactly one wins and the session is woken once, over many rounds on both replicas.
        var received = 0; var timeouts = 0
        repeat(12) { round ->
            val s = draft("Race $round")
            val created = a.service.create(w.member, WaitDefinition(s.id, "Race", resolver = WaitResolver.EVENT, schedule = WaitSchedule(source = EventSource.WEBHOOK)), null)
            makeDue(created.wait.id)
            val outcomes = coroutineScope { listOf(
                async(Dispatchers.IO) { runCatching { a.service.receiveEvent(created.wait.id, created.secret, ByteArray(0)) }.map { 1 } },
                async(Dispatchers.IO) { runCatching { b.service.resolveDue() } },
                async(Dispatchers.IO) { runCatching { b.service.receiveEvent(created.wait.id, created.secret, ByteArray(0)) }.map { 1 } },
                async(Dispatchers.IO) { runCatching { a.service.resolveDue() } },
            ).awaitAll() }
            assertTrue(outcomes.all { it.isSuccess || (it.exceptionOrNull() as? TeamFailure)?.code == "not_found" }, outcomes.toString())
            val final = a.service.get(w.member, created.wait.id)
            assertEquals(WaitStatus.RESOLVED, final.status)
            when (final.answer!!.action) { "event_received" -> received++; "timed_out" -> timeouts++; else -> fail("unexpected ${final.answer!!.action}") }
            a.service.resolveDue(); b.service.resolveDue()
            assertEquals(1, messages(s.id).size, "woken once in round $round")
            assertEquals(1, w.a.database.query { AuditEvents.selectAll().where { (AuditEvents.subjectId eq created.wait.id) and (AuditEvents.action inList listOf("wait.event_received", "wait.timed_out")) }.count() }.toInt())
        }
        assertEquals(12, received + timeouts)

        // A timeout that asks the owner opens a turn_failed wait and does not wake the agent.
        val failing = draft("Gives up")
        val gives = a.service.create(w.member, WaitDefinition(failing.id, "Never comes", resolver = WaitResolver.EVENT, schedule = WaitSchedule(source = EventSource.WEBHOOK, onTimeout = TimeoutAction.TURN_FAILED)), null).wait
        makeDue(gives.id)
        assertEquals(1, b.service.resolveDue())
        assertEquals("timed_out", a.service.get(w.member, gives.id).answer!!.action)
        assertEquals(emptyList(), messages(failing.id))
        val asked = a.service.list(w.member, null, "open", null, 50).waits.single { it.sessionId == failing.id }
        assertEquals(WaitKind.TURN_FAILED, asked.kind); assertEquals(w.member.member.id, asked.assigneeId)

        // The list filter and the due scan have indexes made for them; the cap of 20 open waits per session counts timed waits too.
        val capped = draft("Capped")
        repeat(20) { a.service.create(w.member, WaitDefinition(capped.id, "t$it", resolver = WaitResolver.TIME, schedule = WaitSchedule(afterSeconds = 3600)), null) }
        refusedWith("wait_limit") { b.service.create(w.member, WaitDefinition(capped.id, "one more", resolver = WaitResolver.TIME, schedule = WaitSchedule(afterSeconds = 3600)), null) }
        assertEquals(20, a.service.list(w.member, null, "open", null, 100, session = capped.id, resolver = "time").waits.size)
        assertEquals(emptyList(), a.service.list(w.member, null, "open", null, 100, session = capped.id).waits, "a session's own list is human waits too, unless asked")
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

    /** A `tcex_` credential as the runner gets one: a token row with `waits:session`, linked to the session of its execution. */
    private suspend fun anExecutionCredentialIsConfinedToItsSession(a: Replica, b: Replica, w: AutomationWorld) {
        suspend fun draft(name: String) = (w.a.sessions.create(w.member, null, CloudSessionDraft(name, w.profile.id, null, null, null, emptyMap(), null), null) as CloudCreateOutcome.Created).session
        val own = draft("Own"); val other = draft("Other")
        val tokenId = java.util.UUID.randomUUID().toString()
        w.a.database.query {
            val now = System.currentTimeMillis()
            Tokens.insert {
                it[id] = tokenId; it[memberId] = w.member.member.id; it[tokenHash] = "execution-hash-$tokenId"; it[device] = "cloud session"; it[deviceId] = null
                it[createdAt] = now; it[expiresAt] = now + 3_600_000; it[serviceScopes] = "[\"inference\",\"waits:session\"]"
            }
            CloudInferenceCredentials.insert { it[CloudInferenceCredentials.tokenId] = tokenId; it[sessionId] = own.id; it[epoch] = 1; it[teamId] = w.teamId; it[createdAt] = now; it[expiresAt] = now + 3_600_000 }
        }
        val agent = w.member.copy(tokenId = tokenId, serviceScopes = setOf(ServiceScope.INFERENCE, ServiceScope.WAITS_SESSION))
        val ask = WaitDefinition(own.id, "Account access", assigneeId = w.admin.member.id)
        val mine = a.service.create(agent, ask, "agent-key").wait
        assertEquals(mine.id, b.service.create(agent, ask, "agent-key").wait.id, "idempotent across replicas")
        val elsewhere = a.service.create(w.member, ask.copy(sessionId = other.id), null).wait
        refusedWith("not_found") { a.service.create(agent, ask.copy(sessionId = other.id), null) }
        refusedWith("not_found") { b.service.get(agent, elsewhere.id) }
        refusedWith("credential_scope") { b.service.answer(agent, mine.id, "done", emptyMap()) }
        // Asked of the administrator, listed for the agent by session, with no permission request of other sessions added.
        w.a.database.query { CloudSessions.update({ CloudSessions.id eq other.id }) { it[awaitingReason] = "permission"; it[awaitingRequestId] = "r"; it[awaitingSince] = 5_000_000_000_000L; it[awaitingEventSeq] = 1; it[awaitingEpoch] = 1 } }
        assertEquals(listOf(mine.id), b.service.list(agent, "all", "open", null, 50).waits.map { it.id })
        assertEquals(listOf(elsewhere.id), a.service.list(w.member, null, "open", null, 50, session = other.id).waits.map { it.id }, "the owner lists a session's waits by session, asked of anyone")
        assertEquals(WaitStatus.CANCELLED, b.service.cancel(agent, mine.id).status)
        // The credential of a remote session's host carries the same wait scope without inference: the same confinement.
        val remoteId = java.util.UUID.randomUUID().toString()
        w.a.database.query {
            val now = System.currentTimeMillis()
            Tokens.insert {
                it[id] = remoteId; it[memberId] = w.member.member.id; it[tokenHash] = "remote-hash-$remoteId"; it[device] = "remote session"; it[deviceId] = null
                it[createdAt] = now; it[expiresAt] = now + 3_600_000; it[serviceScopes] = kotlinx.serialization.json.Json.encodeToString(REMOTE_SESSION_SCOPES.map { scope -> scope.wire })
            }
            CloudInferenceCredentials.insert { it[CloudInferenceCredentials.tokenId] = remoteId; it[sessionId] = own.id; it[epoch] = 1; it[teamId] = w.teamId; it[createdAt] = now; it[expiresAt] = now + 3_600_000 }
        }
        val remoteHost = agent.copy(tokenId = remoteId, serviceScopes = REMOTE_SESSION_SCOPES.toSet())
        val remoteAsk = a.service.create(remoteHost, ask.copy(title = "Remote host"), "remote-key").wait
        assertEquals(remoteAsk.id, b.service.create(remoteHost, ask.copy(title = "Remote host"), "remote-key").wait.id)
        refusedWith("credential_scope") { b.service.answer(remoteHost, remoteAsk.id, "done", emptyMap()) }
        refusedWith("not_found") { b.service.get(remoteHost, elsewhere.id) }
        refusedWith("not_found") { a.service.create(remoteHost, ask.copy(sessionId = other.id), null) }
        assertEquals(listOf(remoteAsk.id), b.service.list(remoteHost, "all", "open", null, 50).waits.map { it.id }, "listed for its own session only")
        assertEquals(WaitStatus.CANCELLED, b.service.cancel(remoteHost, remoteAsk.id).status)
        // The same scope on a token bound to no session (a key) gets nothing.
        val keyId = java.util.UUID.randomUUID().toString()
        w.a.database.query {
            val now = System.currentTimeMillis()
            Tokens.insert {
                it[id] = keyId; it[memberId] = w.member.member.id; it[tokenHash] = "key-hash-$keyId"; it[device] = "service key"; it[deviceId] = null
                it[createdAt] = now; it[expiresAt] = now + 3_600_000; it[serviceScopes] = "[\"inference\",\"waits:session\"]"
            }
        }
        refusedWith("credential_scope") { a.service.list(agent.copy(tokenId = keyId), null, "open", null, 50) }
        w.a.database.query { CloudSessions.update({ CloudSessions.id eq other.id }) { it[awaitingReason] = null; it[awaitingRequestId] = null; it[awaitingSince] = null; it[awaitingEventSeq] = null; it[awaitingEpoch] = null } }
        a.service.cancel(w.member, elsewhere.id)
    }

    /** A running session whose runner reports turns: a failure opens a `turn_failed` wait, the next turn cancels it, retrying wakes the session. */
    private suspend fun aFailedTurnOpensAWaitForTheOwner(w: AutomationWorld) {
        val session = (w.a.sessions.create(w.member, null, CloudSessionDraft("Flaky", w.profile.id, null, null, null, emptyMap(), null), null) as CloudCreateOutcome.Created).session
        // The member's earlier sessions would compete for the running place (which one wins depends on random ids): only this one remains.
        w.a.database.query { CloudSessions.deleteWhere { CloudSessions.id neq session.id } }
        val pod = CloudSessionService.podName(session.id, 1)
        // Reconciler passes until the cluster holds the session's pod (the claim and the pod take a pass each); bounded, so a stuck session fails the assertion.
        repeat(10) { if (w.a.cluster.pods.keys.none { it.endsWith("/$pod") }) w.pass() }
        assertTrue(w.a.cluster.pods.keys.any { it.endsWith("/$pod") }, "the session's pod was created")
        w.a.cluster.start(pod); w.pass()
        w.a.runner.register(w.teamId, session.id, 1, pod)
        w.b.runner.heartbeat(w.teamId, session.id, 1, ready = true)
        val a = Replica(WaitService(SqlWaitStore(w.a.database), waker(w.a))); val b = Replica(WaitService(SqlWaitStore(w.b.database), waker(w.b)))
        var next = 0L
        suspend fun turn(phase: String, marker: TurnMarker, replica: AutomationReplica = w.a) {
            replica.agent.events(w.teamId, session.id, 1, listOf(RunnerEvent(++next, CloudEventType.TURN, """{"phase":"$phase"}""", marker)))
        }
        suspend fun open() = a.service.list(w.member, null, "open", null, 50).waits.filter { it.sessionId == session.id }
        val feed = SqlTeamEventStore(w.a.database); val head = feed.head(w.teamId)

        turn("started", TurnMarker.STARTED); assertEquals(emptyList(), open(), "a turn that starts opens nothing")
        turn("failed", TurnMarker.ENDED, w.b)
        val failed = open().single()
        assertEquals(WaitKind.TURN_FAILED, failed.kind); assertEquals(w.member.member.id, failed.assigneeId); assertNull(failed.createdById)
        assertEquals(listOf("retry", "dismiss"), failed.actions.map { it.id }); assertEquals(listOf(WaitRef("session", id = session.id)), failed.refs)
        val audience = TeamEventAudience(w.member.member.id, sessions = true, runs = false, teamSessions = false, teamRuns = false)
        val types = setOf(TeamEventType.WAIT_CREATED, TeamEventType.WAIT_CANCELLED, TeamEventType.WAIT_RESOLVED)
        assertEquals(listOf(TeamEventType.WAIT_CREATED), feed.read(w.teamId, audience, head, types, 100).events.map { it.type }, "the owner is told live")

        // A second failure replaces the first; the next turn to start cancels it.
        turn("started", TurnMarker.STARTED); assertEquals(emptyList(), open())
        assertEquals(WaitStatus.CANCELLED, a.service.get(w.member, failed.id).status)
        turn("failed", TurnMarker.ENDED); val second = open().single()
        turn("failed", TurnMarker.ENDED); val third = open().single()
        assertEquals(WaitStatus.CANCELLED, a.service.get(w.member, second.id).status, "one open at a time")

        // Dismissing tells the agent nothing; trying again wakes the session with the note.
        b.service.answer(w.member, third.id, "dismiss", emptyMap())
        assertEquals(emptyList(), w.a.conversation.inputs(w.member, session.id, null, 10).inputs.filter { it.kind == CloudInputKind.MESSAGE })
        turn("failed", TurnMarker.ENDED); val fourth = open().single()
        a.service.answer(w.member, fourth.id, "retry", mapOf("note" to JsonPrimitive("Use the staging key")))
        val message = w.a.conversation.inputs(w.member, session.id, null, 10).inputs.single { it.kind == CloudInputKind.MESSAGE }
        assertTrue(message.text!!.startsWith("[TeamCode wait answered] The agent's turn failed\nwait: ${fourth.id}\naction: Try again (retry)"), message.text)
        assertTrue("Anything to change before trying again?: Use the staging key" in message.text!!, message.text)
        refusedWith("wait_closed") { a.service.answer(w.member, fourth.id, "dismiss", emptyMap()) }
        // The session's other owner-side reads see it as a normal wait.
        assertEquals(WaitStatus.RESOLVED, b.service.get(w.admin, fourth.id).status)
    }
}
