package me.nathanfallet.teamcode.companion.infrastructure

import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import me.nathanfallet.teamcode.companion.FakePlatform
import me.nathanfallet.teamcode.companion.InMemoryAccountRepository
import me.nathanfallet.teamcode.companion.account
import me.nathanfallet.teamcode.companion.credentials
import me.nathanfallet.teamcode.companion.domain.models.CompanionException
import me.nathanfallet.teamcode.companion.domain.models.FieldType
import me.nathanfallet.teamcode.companion.domain.models.WaitKind
import me.nathanfallet.teamcode.companion.domain.models.WaitResolver
import me.nathanfallet.teamcode.companion.domain.models.WaitSource
import me.nathanfallet.teamcode.companion.domain.models.WaitStatus
import me.nathanfallet.teamcode.companion.domain.models.AnswerValue
import me.nathanfallet.teamcode.companion.infrastructure.client.TeamCodeClientProvider
import me.nathanfallet.teamcode.companion.infrastructure.repositories.AccountCalls
import me.nathanfallet.teamcode.companion.infrastructure.repositories.RemoteWaitRepository
import kotlin.test.*

/** The waits over a scripted server: what is asked, how a wait is mapped, how a lost race reads. */
class RemoteWaitRepositoryTest {
    private val requests = mutableListOf<HttpRequestData>()
    private val answers = ArrayDeque<Pair<HttpStatusCode, String>>()
    private val engine = object : HttpClientEngineFactory<MockEngineConfig> {
        override fun create(block: MockEngineConfig.() -> Unit) = MockEngine(MockEngineConfig().apply {
            block()
            addHandler { request ->
                requests += request
                val (status, body) = answers.removeFirstOrNull() ?: (HttpStatusCode.ServiceUnavailable to "{}")
                respond(ByteReadChannel(body), status, headersOf(HttpHeaders.ContentType to listOf("application/json")))
            }
        })
    }
    private val waits = RemoteWaitRepository(AccountCalls(TeamCodeClientProvider(engine, credentials(), FakePlatform), InMemoryAccountRepository(listOf(account("a1")))))

    private val review = """{"id":"w1","source":"wait","kind":"human_request","status":"open","sessionId":"s1","sessionName":"Fix the login","assigneeId":"m1","title":"Review the plan",
        "body":"Plan attached.","refs":[{"kind":"artifact","id":"a1","title":"Plan"}],"fields":[{"id":"feedback","type":"text","label":"Feedback"},{"id":"future","type":"hologram","label":"?"},
        {"id":"pick","type":"choice","label":"Pick","options":[{"value":"a","label":"A"}],"multiple":true}],
        "actions":[{"id":"approve","label":"Approve","style":"primary"},{"id":"request_changes","label":"Request changes","requires":["feedback"]}],"createdAt":5}"""
    private val permission = """{"id":"s2","source":"session","kind":"permission","status":"open","sessionId":"s2","requestId":"req-1","assigneeId":"m1","title":"The agent needs a permission","createdAt":9}"""

    @Test fun theOpenWaitsAreAskedAndMapped() = runTest {
        answers.addLast(HttpStatusCode.OK to """{"waits":[$permission,$review],"nextBefore":"w0"}""")
        val page = waits.list("a1", WaitStatus.OPEN, before = "w9")
        val url = requests.single().url
        assertTrue(url.encodedPath.endsWith("/api/v1/waits")); assertEquals("open", url.parameters["status"]); assertEquals("w9", url.parameters["before"])
        assertEquals("w0", page.nextBefore)
        val (session, stored) = page.waits
        assertEquals(WaitSource.SESSION, session.source); assertEquals(WaitKind.PERMISSION, session.kind); assertEquals("req-1", session.requestId); assertTrue(session.fields.isEmpty())
        assertEquals(WaitSource.WAIT, stored.source); assertEquals(WaitKind.HUMAN_REQUEST, stored.kind); assertEquals("Fix the login", stored.sessionName)
        assertEquals(listOf(FieldType.TEXT, FieldType.UNKNOWN, FieldType.CHOICE), stored.fields.map { it.type }, "a field type from the future is filled as text")
        assertTrue(stored.fields[2].multiple); assertEquals("A", stored.fields[2].options.single().label)
        assertEquals(listOf("feedback"), stored.actions[1].requires); assertEquals("artifact", stored.refs.single().kind)
    }

    @Test fun theAnsweredAndWithdrawnWaitsAreAskedByStatusAndKeepWhatWasGiven() = runTest {
        val answered = review.replace(""""status":"open"""", """"status":"resolved"""").replace(""""createdAt":5""",
            """"createdAt":5,"resolvedAt":8,"answer":{"action":"request_changes","values":{"feedback":"Split step 2","pick":["a"]},"authorId":"m1","answeredAt":8}""")
        val cancelled = review.replace(""""status":"open"""", """"status":"cancelled"""").replace(""""createdAt":5""", """"createdAt":5,"resolvedAt":7""")
        answers.addLast(HttpStatusCode.OK to """{"waits":[$answered]}""")
        answers.addLast(HttpStatusCode.OK to """{"waits":[$cancelled]}""")
        val done = waits.list("a1", WaitStatus.RESOLVED).waits.single()
        val withdrawn = waits.list("a1", WaitStatus.CANCELLED).waits.single()
        assertEquals(listOf("resolved", "cancelled"), requests.map { it.url.parameters["status"] })
        assertEquals(WaitStatus.RESOLVED, done.status); assertEquals("Request changes", done.answerActionLabel); assertEquals(8L, done.resolvedAt)
        assertEquals(listOf("Feedback: Split step 2", "Pick: A"), done.answerLines().map { "${it.label}: ${(it.value as AnswerValue.Words).text}" }, "a choice shows its option's label")
        assertEquals(WaitStatus.CANCELLED, withdrawn.status); assertTrue(withdrawn.cancelled); assertNull(withdrawn.answer)
    }

    @Test fun anAnswerPostsItsActionAndValuesAndALostRaceIsAConflict() = runTest {
        answers.addLast(HttpStatusCode.OK to review.replace(""""status":"open"""", """"status":"resolved""""))
        val done = waits.answer("a1", "w1", "request_changes", mapOf("feedback" to JsonPrimitive("Split step 2")))
        assertFalse(done.open)
        val request = requests.single()
        assertEquals(HttpMethod.Post, request.method); assertTrue(request.url.encodedPath.endsWith("/api/v1/waits/w1/answer"))
        val body = (request.body as OutgoingContent.ByteArrayContent).bytes().decodeToString()
        assertTrue("\"action\":\"request_changes\"" in body && "Split step 2" in body, body)
        answers.addLast(HttpStatusCode.Conflict to """{"error":{"type":"wait_closed","message":"x"}}""")
        val lost = assertFailsWith<CompanionException.Server> { waits.answer("a1", "w1", "approve", emptyMap()) }
        assertEquals(409, lost.statusCode); assertEquals("wait_closed", lost.code)
    }

    @Test fun theInProgressWaitsAreReadByResolverSoonestFirstAndWithdrawnByCancel() = runTest {
        val timer = """{"id":"t1","source":"wait","kind":"timed","resolver":"time","status":"open","sessionId":"s1","sessionName":"Fix the login","assigneeId":"m1","title":"Resume at 14:30","createdAt":5,"dueAt":9000}"""
        val ci = """{"id":"t2","source":"wait","kind":"timed","resolver":"event","status":"open","sessionId":"s1","assigneeId":"m1","title":"CI of the PR","createdAt":6,"timeoutAt":4000,"eventSource":"webhook","onTimeout":"turn_failed"}"""
        answers.addLast(HttpStatusCode.OK to """{"waits":[$timer]}""")
        answers.addLast(HttpStatusCode.OK to """{"waits":[$ci]}""")
        val progress = waits.inProgress("a1")
        assertEquals(listOf("time", "event"), requests.map { it.url.parameters["resolver"] }); assertEquals(listOf("open", "open"), requests.map { it.url.parameters["status"] })
        assertEquals(listOf("t2", "t1"), progress.map { it.id }, "the soonest instant first: the CI's timeout, then the timer")
        val (first, second) = progress
        assertEquals(WaitKind.TIMED, first.kind); assertEquals(WaitResolver.EVENT, first.resolver); assertEquals(4000L, first.timeoutAt); assertEquals("turn_failed", first.onTimeout); assertEquals("webhook", first.eventSource)
        assertEquals(WaitResolver.TIME, second.resolver); assertEquals(9000L, second.dueAt)

        answers.addLast(HttpStatusCode.OK to timer.replace(""""status":"open"""", """"status":"cancelled""""))
        val withdrawn = waits.cancel("a1", "t1")
        assertTrue(withdrawn.cancelled)
        val cancel = requests.last()
        assertEquals(HttpMethod.Post, cancel.method); assertTrue(cancel.url.encodedPath.endsWith("/api/v1/waits/t1/cancel"))
    }
}
