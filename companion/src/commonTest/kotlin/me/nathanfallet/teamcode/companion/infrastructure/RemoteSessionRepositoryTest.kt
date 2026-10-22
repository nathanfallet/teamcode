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
import me.nathanfallet.teamcode.companion.domain.models.SessionGroup
import me.nathanfallet.teamcode.companion.domain.models.SessionScope
import me.nathanfallet.teamcode.companion.domain.models.SessionState
import me.nathanfallet.teamcode.companion.domain.models.TranscriptEvent
import me.nathanfallet.teamcode.companion.domain.models.TurnPhase
import me.nathanfallet.teamcode.companion.infrastructure.client.TeamCodeClientProvider
import me.nathanfallet.teamcode.companion.infrastructure.repositories.AccountCalls
import me.nathanfallet.teamcode.companion.infrastructure.repositories.RemoteSessionRepository
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

    @Test fun theListAsksForTheGroupsStatesAndMapsSessions() = runTest {
        json("""{"sessions":[$sessionJson],"nextBefore":"s0"}""")
        val page = sessions.sessions("a1", SessionScope.TEAM, SessionGroup.ACTIVE, before = "s9")
        val session = page.sessions.single()
        assertEquals(SessionState.ACTIVE, session.state); assertEquals("opus", session.model); assertEquals(listOf("github.com/team/app"), session.repositories)
        assertEquals("s0", page.nextBefore)
        val url = requests.single().url
        assertEquals("all", url.parameters["owner"]); assertEquals("s9", url.parameters["before"])
        assertEquals(listOf("creating", "preparing", "ready", "active", "pausing", "resuming", "transferring"), url.parameters.getAll("state"))
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
        val events = page.events
        assertEquals(TranscriptEvent.UserMessage(1, 1, "Hi", "i1", "m2"), events[0])
        val call = assertIs<TranscriptEvent.ToolCall>(events[1])
        assertEquals("Bash", call.name); assertEquals("ls -la…", call.summary); assertTrue("\"command\"" in call.input)
        assertEquals(TranscriptEvent.ToolResult(3, 3, "t1", "a b", true, 2, null), events[2])
        assertEquals(TranscriptEvent.Turn(4, 4, TurnPhase.COMPLETED, 1500, 3), events[3])
        assertEquals(TranscriptEvent.PermissionRequest(5, 5, "r1", "AskUserQuestion", true), events[4])
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
}
