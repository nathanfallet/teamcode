package me.nathanfallet.teamcode.companion.infrastructure

import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import me.nathanfallet.teamcode.companion.FakePlatform
import me.nathanfallet.teamcode.companion.InMemoryAccountRepository
import me.nathanfallet.teamcode.companion.account
import me.nathanfallet.teamcode.companion.credentials
import me.nathanfallet.teamcode.companion.domain.models.CompanionException
import me.nathanfallet.teamcode.companion.domain.models.LiveUpdate
import me.nathanfallet.teamcode.companion.domain.models.RunStatus
import me.nathanfallet.teamcode.companion.domain.models.SessionChange
import me.nathanfallet.teamcode.companion.domain.models.SessionFilter
import me.nathanfallet.teamcode.companion.domain.models.SessionHost
import me.nathanfallet.teamcode.companion.domain.models.SessionKind
import me.nathanfallet.teamcode.companion.domain.models.SessionScope
import me.nathanfallet.teamcode.companion.domain.models.SessionState
import me.nathanfallet.teamcode.companion.domain.models.TranscriptEvent
import me.nathanfallet.teamcode.companion.domain.models.TurnPhase
import me.nathanfallet.teamcode.companion.infrastructure.client.TeamCodeClientProvider
import me.nathanfallet.teamcode.companion.infrastructure.repositories.AccountCalls
import me.nathanfallet.teamcode.companion.infrastructure.repositories.RemoteDeviceRepository
import me.nathanfallet.teamcode.companion.infrastructure.repositories.RemoteSessionRepository
import me.nathanfallet.teamcode.companion.domain.models.DeviceFolder
import me.nathanfallet.teamcode.companion.domain.models.ResumableSession
import me.nathanfallet.teamcode.companion.domain.models.ResumableSessions
import me.nathanfallet.teamcode.companion.domain.models.HostDevice
import me.nathanfallet.teamcode.companion.domain.models.NewDeviceSession
import me.nathanfallet.teamcode.companion.domain.models.PermissionMode
import io.ktor.http.content.OutgoingContent
import me.nathanfallet.teamcode.companion.infrastructure.repositories.RemoteTranscriptRepository
import me.nathanfallet.teamcode.companion.infrastructure.stream.ReconnectPolicy
import kotlin.random.Random
import kotlin.test.*

/** The session list, the feed and the transcript over a scripted server: mapping, resumption, and when to stop. */
class RemoteSessionRepositoryTest {
    private val requests = mutableListOf<HttpRequestData>()
    /** Answers in order; each is a status, a content type and a body. */
    private val answers = ArrayDeque<Triple<HttpStatusCode, String, String>>()
    private val engine = object : HttpClientEngineFactory<MockEngineConfig> {
        override fun create(block: MockEngineConfig.() -> Unit) = MockEngine(MockEngineConfig().apply {
            block()
            addHandler { request ->
                requests += request
                val (status, type, body) = answers.removeFirstOrNull() ?: Triple(HttpStatusCode.ServiceUnavailable, "application/json", "{}")
                respond(ByteReadChannel(body), status, headersOf(HttpHeaders.ContentType to listOf(type), HttpHeaders.RetryAfter to listOf(if (status.value == 429) "20" else "")))
            }
        })
    }
    private val noJitter = object : Random() { override fun nextBits(bitCount: Int) = 0 }
    private val calls = AccountCalls(TeamCodeClientProvider(engine, credentials(), FakePlatform), InMemoryAccountRepository(listOf(account("a1"))))
    private val sessions = RemoteSessionRepository(calls, ReconnectPolicy(random = noJitter))
    private val transcripts = RemoteTranscriptRepository(calls, ReconnectPolicy(random = noJitter))

    private fun json(body: String, status: HttpStatusCode = HttpStatusCode.OK) = answers.addLast(Triple(status, "application/json", body))
    private fun sse(body: String) = answers.addLast(Triple(HttpStatusCode.OK, "text/event-stream", "retry: 2000\n\n$body"))
    private fun teamEvent(seq: Long, type: String, subject: String = "session", data: String = "{}") =
        "id: $seq\nevent: $type\ndata: {\"seq\":$seq,\"type\":\"$type\",\"subject\":\"$subject\",\"subjectId\":\"s1\",\"memberId\":\"m1\",\"at\":$seq,\"data\":$data}\n\n"
    private fun cloudEvent(seq: Long, type: String, data: String) = "id: $seq\nevent: $type\ndata: {\"seq\":$seq,\"type\":\"$type\",\"at\":$seq,\"epoch\":1,\"data\":$data}\n\n"

    private val sessionJson = """{"id":"s1","name":"Fix the build","ownerId":"m1","createdBy":{"initiatorId":"m1","credentialId":"c","effectiveMemberId":"m1"},
        "state":"active","phase":"agent","desiredState":"ready","generation":1,"currentEpoch":1,"profile":{"id":"p1","version":1},"image":{"reference":"img"},
        "agent":{"kind":"claude","model":"opus"},"activity":{"lastActivityAt":42,"idleTimeoutMinutes":30},"resume":{"workspace":"w","conversation":"c"},
        "workspaceDeletion":{"state":"none"},"createdAt":1,"updatedAt":2,"environment":{"repositories":[{"url":"https://github.com/team/app.git","path":"app"}]},"future":{"x":1}}"""

    @Test fun theListAsksForTheFiltersStatesAndMapsSessions() = runTest {
        json("""{"sessions":[$sessionJson],"nextBefore":"s0"}""")
        val page = sessions.sessions("a1", SessionScope.TEAM, SessionFilter.OPEN, before = "s9")
        val session = page.sessions.single()
        assertEquals(SessionState.ACTIVE, session.state); assertEquals("opus", session.model); assertEquals(listOf("github.com/team/app"), session.repositories)
        assertEquals("s0", page.nextBefore)
        val url = requests.single().url
        assertEquals("all", url.parameters["owner"]); assertEquals("s9", url.parameters["before"])
        assertEquals(listOf("creating", "preparing", "ready", "active", "pausing", "resuming", "transferring", "paused"), url.parameters.getAll("state"))
    }

    @Test fun theAllFilterAsksForEveryStateAndEndingDeletesTheSession() = runTest {
        json("""{"sessions":[]}""")
        sessions.sessions("a1", SessionScope.MINE, SessionFilter.ALL, before = null)
        assertEquals(listOf("creating", "preparing", "ready", "active", "pausing", "resuming", "transferring", "paused", "terminated", "failed"), requests.single().url.parameters.getAll("state"))
        assertEquals(listOf("terminated", "failed"), SessionFilter.ENDED.states.map { it.wire })
        requests.clear()
        json("", HttpStatusCode.NoContent)
        sessions.end("a1", "s1")
        assertEquals(io.ktor.http.HttpMethod.Delete, requests.single().method)
        assertTrue(requests.single().url.encodedPath.endsWith("/cloud/sessions/s1"))
    }

    @Test fun aRemoteSessionCarriesItsHostAndTheListAsksForTheKindsGiven() = runTest {
        val remote = sessionJson.replace(""""future":{"x":1}}""", """"kind":"remote","host":{"deviceId":"d1","deviceName":"Ada's MacBook","platform":"macos","osVersion":"15.1",
            "directoryName":"app","online":false,"permissionMode":"bypass"},"failure":{"code":"host_offline","message":"Gone.","retryable":true}}""")
        json("""{"sessions":[$remote,$sessionJson]}""")
        val page = sessions.sessions("a1", SessionScope.MINE, SessionFilter.ALL, before = null, kinds = setOf(SessionKind.REMOTE, SessionKind.CLOUD))
        val (onDevice, inPod) = page.sessions
        assertEquals(SessionKind.REMOTE, onDevice.kind)
        assertEquals(SessionHost("Ada's MacBook", "macos", "15.1", "app", online = false, removed = false, permissionMode = "bypass"), onDevice.host)
        assertTrue(onDevice.hostOffline)
        // A server without the field: a cloud session, without a host.
        assertEquals(SessionKind.CLOUD, inPod.kind); assertNull(inPod.host); assertFalse(inPod.hostOffline)
        assertEquals(listOf("cloud", "remote"), requests.single().url.parameters.getAll("kind"))
    }

    @Test fun withoutKindsTheListAndTheFeedAskAsBefore() = runTest {
        json("""{"sessions":[]}""")
        sessions.sessions("a1", SessionScope.MINE, SessionFilter.OPEN, before = null)
        assertNull(requests.single().url.parameters.getAll("kind"))
        sse(teamEvent(1, "cloud.turn", data = """{"phase":"completed","kind":"remote"}""") + "event: denied\ndata: {}\n\n")
        val seen = mutableListOf<LiveUpdate<SessionChange>>()
        assertFailsWith<CompanionException.AccessRevoked> { sessions.sessionChanges("a1").collect { seen += it } }
        assertNull(requests[1].url.parameters.getAll("kind"))
        assertEquals(SessionKind.REMOTE, seen.filterIsInstance<LiveUpdate.Item<SessionChange>>().single().value.kind)
        requests.clear()
        sse("event: denied\ndata: {}\n\n")
        assertFailsWith<CompanionException.AccessRevoked> { sessions.sessionChanges("a1", setOf(SessionKind.CLOUD, SessionKind.REMOTE)).collect { } }
        assertEquals(listOf("cloud", "remote"), requests.first().url.parameters.getAll("kind"))
    }

    @Test fun theWaitEventsAreAskedForOnlyWhenTheServerHasWaitsAndBelongToTheirAssigneeAndSession() = runTest {
        val data = """{"waitId":"w1","sessionId":"s9","waitKind":"human_request","assigneeId":"m2","kind":"cloud"}"""
        sse(teamEvent(1, "cloud.wait.created", data = data) + teamEvent(2, "cloud.wait.resolved", data = data.dropLast(1) + ""","action":"approve"}""") +
            teamEvent(3, "cloud.wait.cancelled", data = "{}") + "event: denied\ndata: {}\n\n")
        val seen = mutableListOf<LiveUpdate<SessionChange>>()
        assertFailsWith<CompanionException.AccessRevoked> { sessions.sessionChanges("a1", waits = true).collect { seen += it } }
        assertEquals("cloud.session.created,cloud.session.ended,cloud.session.paused,cloud.session.resumed,cloud.session.woken,cloud.turn,cloud.wait.cancelled,cloud.wait.created,cloud.wait.resolved",
            requests.single().url.parameters["types"].orEmpty().split(",").sorted().joinToString(","))
        val items = seen.filterIsInstance<LiveUpdate.Item<SessionChange>>().map { it.value }
        // The event's subject is the wait; its session and its assignee are in the data. One without a session is not a session's change.
        assertEquals(2, items.size)
        val (created, resolved) = items
        assertEquals("s9", created.sessionId); assertEquals("s1", created.waitId, "the subject of the event"); assertEquals("m2", created.memberId); assertEquals(SessionKind.CLOUD, created.kind)
        assertEquals("cloud.wait.resolved", resolved.type)
        requests.clear()
        sse("event: denied\ndata: {}\n\n")
        assertFailsWith<CompanionException.AccessRevoked> { sessions.sessionChanges("a1").collect { } }
        assertFalse("cloud.wait" in requests.single().url.parameters["types"].orEmpty(), "an older server refuses a type it does not know")
    }

    @Test fun theWorkflowRunEventIsAskedForOnlyWhenWantedAndNamesTheRunItsStarterAndItsParent() = runTest {
        val data = """{"runId":"r1","workflowId":"wf1","status":"waiting","ownerId":"m2","parentRunId":"r0"}"""
        sse(teamEvent(1, "cloud.workflow.run.updated", data = data) + "event: denied\ndata: {}\n\n")
        val seen = mutableListOf<LiveUpdate<SessionChange>>()
        assertFailsWith<CompanionException.AccessRevoked> { sessions.sessionChanges("a1", workflows = true).collect { seen += it } }
        assertTrue("cloud.workflow.run.updated" in requests.single().url.parameters["types"].orEmpty().split(","))
        val change = seen.filterIsInstance<LiveUpdate.Item<SessionChange>>().single().value
        assertEquals("r1", change.runId); assertEquals(RunStatus.WAITING, change.runStatus); assertEquals("m2", change.memberId)
        assertEquals("wf1", change.runWorkflowId); assertEquals("r0", change.runParentId); assertEquals("", change.sessionId, "a run belongs to no session")
        requests.clear()
        sse("event: denied\ndata: {}\n\n")
        assertFailsWith<CompanionException.AccessRevoked> { sessions.sessionChanges("a1").collect { } }
        assertFalse("workflow" in requests.single().url.parameters["types"].orEmpty(), "an older server refuses a type it does not know")
    }

    @Test fun theWaitingEventsAreAskedWhenWantedAndASessionSaysWhatItWaitsFor() = runTest {
        sse(teamEvent(1, "cloud.session.waiting", data = """{"reason":"question","requestId":"r1","eventSeq":4}""") +
            teamEvent(2, "cloud.session.answered", data = """{"requestId":"r1","behavior":"allow","reason":"answered"}""") + "event: denied\ndata: {}\n\n")
        val seen = mutableListOf<LiveUpdate<SessionChange>>()
        assertFailsWith<CompanionException.AccessRevoked> { sessions.sessionChanges("a1", waiting = true).collect { seen += it } }
        assertEquals("cloud.session.answered,cloud.session.created,cloud.session.ended,cloud.session.paused,cloud.session.resumed,cloud.session.waiting,cloud.session.woken,cloud.turn",
            requests.single().url.parameters["types"])
        val (asked, answered) = seen.filterIsInstance<LiveUpdate.Item<SessionChange>>().map { it.value }
        assertEquals("question", asked.waitingReason); assertEquals("r1", asked.requestId); assertNull(asked.turnPhase)
        assertNull(answered.waitingReason); assertEquals("r1", answered.requestId)

        json("""{"sessions":[${sessionJson.replace(""""future":{"x":1}}""", """"waiting":{"reason":"permission","requestId":"r2","since":7,"eventSeq":3}}""")},$sessionJson]}""")
        val (waiting, idle) = sessions.sessions("a1", SessionScope.MINE, SessionFilter.OPEN, before = null).sessions
        assertEquals(me.nathanfallet.teamcode.companion.domain.models.SessionWaiting("permission", "r2", 7), waiting.waiting)
        assertNull(idle.waiting)
    }

    @Test fun aMissingSessionIsNotFound() = runTest {
        json("""{"error":{"type":"session_not_found","message":"No."}}""", HttpStatusCode.NotFound)
        assertFailsWith<CompanionException.NotFound> { sessions.session("a1", "s1") }
    }

    @Test fun theFeedResumesAfterACutFromTheLastEventWithoutLossOrDuplicate() = runTest {
        // Cut in the middle of event 3; a run event moves the cursor too but is not a session change.
        sse(teamEvent(1, "cloud.turn", data = """{"phase":"started"}""") + teamEvent(2, "run.stale", subject = "run") + teamEvent(3, "cloud.session.paused").take(40))
        sse(teamEvent(3, "cloud.session.paused", data = """{"state":"paused"}""") + "event: gap\ndata: {\"oldestSeq\":9}\n\n" + teamEvent(9, "cloud.turn") + "event: denied\ndata: {}\n\n")
        val seen = mutableListOf<LiveUpdate<*>>()
        val failure = assertFailsWith<CompanionException.AccessRevoked> { sessions.sessionChanges("a1").collect { seen += it } }
        assertNotNull(failure, seen.toString())
        assertNull(requests[0].headers["Last-Event-ID"])
        assertEquals("2", requests[1].headers["Last-Event-ID"])
        // Only the session types of the feed's first version are asked for.
        assertEquals("cloud.session.created,cloud.session.ended,cloud.session.paused,cloud.session.resumed,cloud.session.woken,cloud.turn", requests[0].url.parameters["types"])
        val items = seen.filterIsInstance<LiveUpdate.Item<*>>().map { (it.value as me.nathanfallet.teamcode.companion.domain.models.SessionChange) }
        assertEquals(listOf(1L, 3, 9), items.map { it.seq })
        assertEquals("started", items[0].turnPhase); assertEquals(SessionState.PAUSED, items[1].state); assertEquals("unknown", items[2].turnPhase)
        assertEquals(LiveUpdate.Opened(resumed = false), seen.first())
        assertTrue(LiveUpdate.Opened(resumed = true) in seen)
        assertEquals(LiveUpdate.Reconnecting(2_000), seen[seen.indexOf(LiveUpdate.Opened(resumed = true)) - 1])
        assertTrue(LiveUpdate.Gap in seen)
    }

    @Test fun failuresBackOffAndA429WaitsForItsRetryAfter() = runTest {
        answers.addLast(Triple(HttpStatusCode.BadGateway, "text/html", "<html/>"))
        answers.addLast(Triple(HttpStatusCode.ServiceUnavailable, "text/html", "<html/>"))
        json("""{"error":{"type":"rate_limited","message":"Slow down."}}""", HttpStatusCode.TooManyRequests)
        sse("event: denied\ndata: {}\n\n")
        val seen = mutableListOf<LiveUpdate<*>>()
        assertFailsWith<CompanionException.AccessRevoked> { sessions.sessionChanges("a1").collect { seen += it } }
        assertEquals(listOf(2_000L, 4_000, 20_000), seen.filterIsInstance<LiveUpdate.Reconnecting>().map { it.delayMillis })
        assertEquals(4, requests.size)
    }

    @Test fun aConnectionThatStayedOpenRestartsTheBackOffAndOneDroppedAtOnceDoesNot() = runTest {
        // Each clock read moves on by [step]: a connection lasts one step between being accepted and cut.
        suspend fun delays(step: Long): List<Long> {
            var clock = 0L
            val repository = RemoteSessionRepository(calls, ReconnectPolicy(random = noJitter, now = { clock.also { clock += step } }))
            repeat(3) { sse(": keep-alive\n\n") }
            sse("event: denied\ndata: {}\n\n")
            val seen = mutableListOf<LiveUpdate<*>>()
            assertFailsWith<CompanionException.AccessRevoked> { repository.sessionChanges("a1").collect { seen += it } }
            return seen.filterIsInstance<LiveUpdate.Reconnecting>().map { it.delayMillis }
        }
        // Quiet but open for a minute each time (a proxy's idle cut): every reconnection waits the first delay.
        assertEquals(listOf(2_000L, 2_000, 2_000), delays(60_000))
        // Accepted then dropped at once: the delays keep doubling.
        assertEquals(listOf(2_000L, 4_000, 8_000), delays(1))
    }

    @Test fun aRefusalOrAnUnreadableEventStopsWithoutReconnecting() = runTest {
        json("""{"error":{"type":"unauthorized","message":"No."}}""", HttpStatusCode.Unauthorized)
        assertFailsWith<CompanionException.Unauthorized> { sessions.sessionChanges("a1").toList() }
        assertEquals(1, requests.size)
        sse("id: 1\nevent: cloud.turn\ndata: {not json}\n\n")
        assertFailsWith<CompanionException.UnreadableEvent> { sessions.sessionChanges("a1").toList() }
        assertEquals(2, requests.size)
        json("""{"error":{"type":"cloud_disabled","message":"No."}}""", HttpStatusCode.Conflict)
        assertEquals(409, assertFailsWith<CompanionException.Server> { sessions.sessionChanges("a1").toList() }.statusCode)
        assertEquals(3, requests.size)
    }

    @Test fun eventsMapEveryTypeAndTolerateUnknownOnes() = runTest {
        json("""{"events":[
            ${cloudEvent(1, "message.user", """{"text":"Hi","inputId":"i1","authorId":"m2"}""").substringAfter("data: ").trim()},
            ${cloudEvent(2, "tool.call", """{"id":"t1","name":"Bash","input":{"command":"ls -la\nrm x","description":"List"}}""").substringAfter("data: ").trim()},
            ${cloudEvent(3, "tool.result", """{"id":"t1","output":"a b","isError":true,"exitCode":2}""").substringAfter("data: ").trim()},
            ${cloudEvent(4, "turn", """{"phase":"completed","durationMs":1500,"steps":3,"future":1}""").substringAfter("data: ").trim()},
            ${cloudEvent(5, "permission.request", """{"requestId":"r1","toolName":"AskUserQuestion","question":true}""").substringAfter("data: ").trim()},
            ${cloudEvent(6, "message.agent", """{"truncated":true,"originalBytes":70000}""").substringAfter("data: ").trim()},
            ${cloudEvent(7, "something.new", """{"x":[1,2]}""").substringAfter("data: ").trim()},
            ${cloudEvent(8, "message.agent", """{"text":42}""").substringAfter("data: ").trim()}
        ],"firstSeq":1,"lastSeq":8,"nextBefore":null}""")
        val page = transcripts.page("a1", "s1", before = null)
        assertEquals("100", requests.single().url.parameters["limit"])
        // The newest page: without a cursor the server would answer the oldest events.
        assertEquals(Long.MAX_VALUE.toString(), requests.single().url.parameters["before"])
        assertNull(requests.single().url.parameters["after"])
        val events = page.events
        assertEquals(TranscriptEvent.UserMessage(1, 1, "Hi", "i1", "m2"), events[0])
        val call = assertIs<TranscriptEvent.ToolCall>(events[1])
        assertEquals("Bash", call.name); assertEquals("ls -la…", call.summary); assertTrue("\"command\"" in call.input)
        assertEquals(TranscriptEvent.ToolResult(3, 3, "t1", "a b", true, 2, null), events[2])
        assertEquals(TranscriptEvent.Turn(4, 4, TurnPhase.COMPLETED, 1500, 3), events[3])
        // Without an input the request can only be declined, as the server reads it.
        assertEquals(TranscriptEvent.PermissionRequest(5, 5, "r1", "AskUserQuestion", true, inputLeftOut = true), events[4])
        assertEquals(TranscriptEvent.Truncated(6, 6, "message.agent", 70_000), events[5])
        assertEquals(TranscriptEvent.Unknown(7, 7, "something.new"), events[6])
        // A field of an unexpected type reads as missing.
        assertEquals(TranscriptEvent.AgentMessage(8, 8, "", null), events[7])
        assertEquals(8, page.lastSeq)
    }

    @Test fun theTranscriptResumesAfterTheLastEventAndCompletesWhenTheSessionEnds() = runTest {
        sse(cloudEvent(11, "message.agent", """{"text":"a"}""") + cloudEvent(12, "message.agent", """{"text":"b"}""").take(30))
        sse(cloudEvent(12, "message.agent", """{"text":"b"}""") + "event: end\ndata: {}\n\n")
        val seen = transcripts.follow("a1", "s1", after = 10).toList()
        assertEquals(listOf("10", "11"), requests.map { it.headers["Last-Event-ID"] })
        assertEquals(listOf(11L, 12), seen.filterIsInstance<LiveUpdate.Item<TranscriptEvent>>().map { it.value.seq })
        assertEquals(LiveUpdate.Ended, seen.last())
    }

    private val inputJson = """{"id":"in1","kind":"message","status":"queued","authorId":"m1","text":"Go","woke":true,"createdAt":1}"""

    @Test fun aMessageCarriesItsIdAsTheIdempotencyKeyAndWakesTheSession() = runTest {
        json(inputJson)
        val receipt = sessions.send("a1", "s1", "Go", "00000000-0000-4000-8000-000000000001")
        assertEquals("in1", receipt.inputId); assertTrue(receipt.woke)
        val request = requests.single()
        assertEquals("/api/v1/cloud/sessions/s1/messages", request.url.encodedPath)
        assertEquals("00000000-0000-4000-8000-000000000001", request.headers["Idempotency-Key"])
        val body = (request.body as io.ktor.http.content.TextContent).text
        assertTrue("\"wake\":true" in body || "\"wake\"" !in body, body)
        assertTrue("\"text\":\"Go\"" in body)
    }

    @Test fun creationSendsTheKeyAndOnlyTheFieldsGiven() = runTest {
        json(sessionJson); json(sessionJson)
        sessions.create("a1", me.nathanfallet.teamcode.companion.domain.models.NewSession("Fix", "p1", null, null, null), "key-1")
        sessions.create("a1", me.nathanfallet.teamcode.companion.domain.models.NewSession("Fix", "p1", "opus", null, "Start"), "key-2")
        val (first, second) = requests.map { (it.body as io.ktor.http.content.TextContent).text }
        assertEquals("key-1", requests[0].headers["Idempotency-Key"])
        // Absent fields go as null, which the server reads as "not given".
        assertTrue("\"agent\":null" in first && "\"initialMessage\":null" in first, first)
        assertTrue("\"model\":\"opus\"" in second && "\"initialMessage\":\"Start\"" in second, second)
    }

    @Test fun renamingPatchesTheSessionAndTheRenameEventIsAskedForOnlyWhenTheServerHasIt() = runTest {
        json(sessionJson.replace("Fix the build", "Better name"))
        assertEquals("Better name", sessions.rename("a1", "s1", "Better name").name)
        assertEquals(io.ktor.http.HttpMethod.Patch, requests.single().method)
        assertTrue(requests.single().url.encodedPath.endsWith("/cloud/sessions/s1"))
        requests.clear()
        sse(teamEvent(1, "cloud.session.renamed", data = """{"name":"Better name","state":"paused"}""") + "event: denied\ndata: {}\n\n")
        val seen = mutableListOf<LiveUpdate<SessionChange>>()
        assertFailsWith<CompanionException.AccessRevoked> { sessions.sessionChanges("a1", renames = true).collect { seen += it } }
        assertTrue("cloud.session.renamed" in requests.single().url.parameters["types"].orEmpty().split(","))
        val renamed = seen.filterIsInstance<LiveUpdate.Item<SessionChange>>().single().value
        assertEquals("Better name", renamed.name); assertEquals("s1", renamed.sessionId)
        requests.clear()
        sse("event: denied\ndata: {}\n\n")
        assertFailsWith<CompanionException.AccessRevoked> { sessions.sessionChanges("a1").collect { } }
        assertTrue("cloud.session.renamed" !in requests.single().url.parameters["types"].orEmpty().split(","), "An older server refuses a type it does not know")
    }

    @Test fun pauseAndInterruptMapTheirAnswers() = runTest {
        json(sessionJson.replace("\"state\":\"active\"", "\"state\":\"pausing\""))
        assertEquals(SessionState.PAUSING, sessions.pause("a1", "s1").state)
        json("""{"error":{"type":"nothing_to_interrupt","message":"No."}}""", HttpStatusCode.Conflict)
        val error = assertFailsWith<CompanionException.Server> { sessions.interrupt("a1", "s1", "k1") }
        assertEquals("nothing_to_interrupt", error.code)
        assertEquals("k1", requests.last().headers["Idempotency-Key"])
    }

    @Test fun anAnswerPostsTheIndexesOrTextsWithItsIdempotencyKey() = runTest {
        json(inputJson.replace("\"kind\":\"message\"", "\"kind\":\"permission\""))
        json(inputJson)
        val answer = me.nathanfallet.teamcode.companion.domain.models.PermissionAnswer(
            "r1", allow = true,
            answers = listOf(me.nathanfallet.teamcode.companion.domain.models.QuestionAnswer(0, listOf(1)), me.nathanfallet.teamcode.companion.domain.models.QuestionAnswer(1, text = "S, XL")),
        )
        val receipt = sessions.answer("a1", "s1", answer, "00000000-0000-4000-8000-000000000007")
        assertEquals("in1", receipt.inputId)
        val request = requests.first()
        assertEquals("/api/v1/cloud/sessions/s1/permissions", request.url.encodedPath)
        assertEquals("POST", request.method.value)
        assertEquals("00000000-0000-4000-8000-000000000007", request.headers["Idempotency-Key"])
        val body = kotlinx.serialization.json.Json.parseToJsonElement((request.body as io.ktor.http.content.TextContent).text) as kotlinx.serialization.json.JsonObject
        assertEquals(kotlinx.serialization.json.Json.parseToJsonElement("""{"requestId":"r1","behavior":"allow","answers":[{"question":0,"options":[1]},{"question":1,"text":"S, XL"}]}"""),
            kotlinx.serialization.json.JsonObject(body.filterValues { it != kotlinx.serialization.json.JsonNull }.mapValues { (key, value) ->
                if (key != "answers") value else kotlinx.serialization.json.JsonArray((value as kotlinx.serialization.json.JsonArray).map { item ->
                    // Defaults left out or sent empty mean the same to the server.
                    kotlinx.serialization.json.JsonObject((item as kotlinx.serialization.json.JsonObject).filterValues { it != kotlinx.serialization.json.JsonNull && it != kotlinx.serialization.json.JsonArray(emptyList()) })
                })
            }))
        // A denial sends no answers.
        sessions.answer("a1", "s1", me.nathanfallet.teamcode.companion.domain.models.PermissionAnswer("r2", allow = false), "k2")
        val denial = (requests.last().body as io.ktor.http.content.TextContent).text
        assertTrue("\"behavior\":\"deny\"" in denial && "\"requestId\":\"r2\"" in denial, denial)
        assertFalse("\"question\"" in denial, denial)
    }

    @Test fun aPermissionRequestCarriesItsQuestionsAndWhetherItsInputWasLeftOut() = runTest {
        val question = """{"requestId":"r1","toolName":"AskUserQuestion","question":true,"input":{"questions":[
            {"question":"Red or blue?","header":"Colour","options":[{"label":"Red","description":"Warm"},{"label":"Blue"}]},
            {"question":"Which sizes?","multiSelect":true,"options":[{"label":"S"},{"label":"M"}]},
            {"question":"Red or blue?","options":[{"label":"Red"}]}]}}"""
        json("""{"events":[
            ${cloudEvent(1, "permission.request", question).substringAfter("data: ").trim()},
            ${cloudEvent(2, "permission.request", """{"requestId":"t1","toolName":"Bash","inputTruncated":true,"inputPreview":"{\"command\":","question":false}""").substringAfter("data: ").trim()},
            ${cloudEvent(3, "input", """{"inputId":"i1","kind":"permission","status":"consumed","requestId":"r1","behavior":"allow"}""").substringAfter("data: ").trim()}
        ],"firstSeq":1,"lastSeq":3,"nextBefore":null}""")
        val events = transcripts.page("a1", "s1", before = null).events
        val asked = assertIs<TranscriptEvent.PermissionRequest>(events[0])
        assertTrue(asked.question); assertFalse(asked.inputLeftOut)
        // The repeated question text is kept once: Claude Code keys answers by text.
        assertEquals(listOf(0, 1), asked.questions.map { it.index })
        assertEquals("Colour", asked.questions[0].header)
        assertEquals(listOf("Warm", null), asked.questions[0].options.map { it.description })
        assertTrue(asked.questions[1].multiSelect)
        val tool = assertIs<TranscriptEvent.PermissionRequest>(events[1])
        assertTrue(tool.inputLeftOut); assertNull(tool.input); assertEquals("{\"command\":", tool.inputPreview)
        assertEquals(TranscriptEvent.InputChanged(3, 3, "i1", "consumed", null, requestId = "r1"), events[2])
    }

    private val devices = RemoteDeviceRepository(calls)

    @Test fun aSessionOnADeviceSendsTheDeviceAndFolderNeverADirectory() = runTest {
        val remote = sessionJson.replace(""""future":{"x":1}}""", """"kind":"remote","phase":"waiting_host","host":{"deviceId":"d1","deviceName":"Ada's Mac","directoryName":"app","online":false,
            "permissionMode":"bypass","folderId":"f1"}}""").replace(""""state":"active","phase":"agent",""", """"state":"creating",""")
        json(remote, HttpStatusCode.Created)
        val form = NewDeviceSession("Fix", "d1", "f1", "codex", "gpt-5", null, PermissionMode.BYPASS, "Go")
        val session = sessions.createOnDevice("a1", form, "key-1")
        assertTrue(session.waitingForHost); assertFalse(session.hostRefused)
        val request = requests.single()
        assertEquals("/api/v1/cloud/sessions", request.url.encodedPath); assertEquals("key-1", request.headers["Idempotency-Key"])
        val body = (request.body as OutgoingContent.ByteArrayContent).bytes().decodeToString()
        for (part in listOf("\"kind\":\"remote\"", "\"deviceId\":\"d1\"", "\"folderId\":\"f1\"", "\"permissionMode\":\"bypass\"", "\"kind\":\"codex\"", "\"initialMessage\":\"Go\""))
            assertTrue(body.contains(part), body)
        // The folder's name is the server's to fill: the app never sends a directory, let alone a path.
        assertFalse(body.contains("\"directoryName\":\""), body); assertFalse(body.contains("\"profileId\":\""), body)
        assertFalse(body.contains("resumeSessionId"), "A new conversation sends no resumeSessionId, as before")
        json(remote, HttpStatusCode.Created)
        sessions.createOnDevice("a1", form.copy(agent = "claude", resumeSessionId = "0000000a-aaaa-4bbb-8ccc-00000000000a"), "key-2")
        assertTrue((requests.last().body as OutgoingContent.ByteArrayContent).bytes().decodeToString().contains("\"resumeSessionId\":\"0000000a-aaaa-4bbb-8ccc-00000000000a\""))
    }

    @Test fun aFoldersConversationsAreReadThroughTheServerWithTheDaemonsFeatures() = runTest {
        json("""{"deviceId":"d1","daemon":{"online":true,"lastSeenAt":3,"features":["resume.v1"]},"folders":[{"id":"f1","name":"app"}]}""")
        assertTrue(devices.folders("a1", "d1").resumes)
        json("""{"deviceId":"d1","daemon":{"online":true,"lastSeenAt":3},"folders":[{"id":"f1","name":"app"}]}""")
        assertFalse(devices.folders("a1", "d1").resumes, "An older daemon announces nothing")
        json("""{"sessions":[{"id":"0000000a-aaaa-4bbb-8ccc-00000000000a","title":"Fix the build","lastActiveAt":5,"turns":2}],"nextBefore":5}""")
        val page = devices.resumable("a1", "d1", "f1", 20, 9)
        assertEquals(ResumableSessions(listOf(ResumableSession("0000000a-aaaa-4bbb-8ccc-00000000000a", "Fix the build", 5, 2)), 5), page)
        assertEquals("/api/v1/devices/d1/folders/f1/resumable", requests.last().url.encodedPath)
        assertEquals("20" to "9", requests.last().url.parameters["limit"] to requests.last().url.parameters["before"])
    }

    @Test fun aRefusedSessionKeepsOnlyTheDaemonsReasonCode() = runTest {
        fun refused(message: String) = sessionJson.replace(""""future":{"x":1}}""", """"kind":"remote","host":{"deviceId":"d1","deviceName":"Mac","directoryName":"app","online":false},
            "failure":{"code":"host_refused","message":"$message","retryable":false}}""").replace(""""state":"active",""", """"state":"terminated",""")
        json("""{"sessions":[${refused("The device refused to start this session (folder_missing).")},${refused("Refused <b>(Not A Code)</b>.")}]}""")
        val (worded, unreadable) = sessions.sessions("a1", SessionScope.MINE, SessionFilter.ENDED, before = null).sessions
        assertTrue(worded.hostRefused); assertEquals("folder_missing", worded.refusalReason)
        // Anything but a short code is the server's text, never shown.
        assertTrue(unreadable.hostRefused); assertNull(unreadable.refusalReason)
        assertFalse(worded.waitingForHost)
    }

    @Test fun onlyTheOwnersOnlineDevicesAreOfferedWithTheFoldersTheyPublished() = runTest {
        json("""[{"id":"d1","memberId":"m1","name":"Ada's Mac","createdAt":1,"lastLoginAt":2,"activeSessions":0,"current":false,"platform":"macos","osVersion":"15.1",
            "daemon":{"online":true,"lastSeenAt":3,"version":"1.0.0"}},
            {"id":"d2","memberId":"m1","name":"Old PC","createdAt":1,"lastLoginAt":2,"activeSessions":0,"current":false,"daemon":{"online":false,"lastSeenAt":1}},
            {"id":"d3","memberId":"m1","name":"Phone","createdAt":1,"lastLoginAt":2,"activeSessions":0,"current":true,"platform":"ios"}]""")
        assertEquals(listOf(HostDevice("d1", "Ada's Mac", "macos", "15.1", current = false)), devices.onlineDevices("a1"))
        assertEquals("/api/v1/devices", requests.last().url.encodedPath)
        json("""{"deviceId":"d1","daemon":{"online":true,"lastSeenAt":3},"folders":[{"id":"f1","name":"app","label":"~/git/app"},{"id":"f2","name":"docs"}]}""")
        val folders = devices.folders("a1", "d1")
        assertTrue(folders.online)
        assertEquals(listOf(DeviceFolder("f1", "app", "~/git/app"), DeviceFolder("f2", "docs", null)), folders.folders)
        assertEquals("/api/v1/devices/d1/folders", requests.last().url.encodedPath)
        json("""{"error":{"type":"device_not_found","message":"No."}}""", HttpStatusCode.NotFound)
        val refused = assertFailsWith<CompanionException.Server> { devices.folders("a1", "d9") }
        assertEquals("device_not_found", refused.code)
    }
}
