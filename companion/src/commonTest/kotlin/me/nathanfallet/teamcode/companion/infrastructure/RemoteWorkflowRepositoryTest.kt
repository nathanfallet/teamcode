package me.nathanfallet.teamcode.companion.infrastructure

import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.test.runTest
import me.nathanfallet.teamcode.companion.*
import me.nathanfallet.teamcode.companion.domain.models.*
import me.nathanfallet.teamcode.companion.infrastructure.client.TeamCodeClientProvider
import me.nathanfallet.teamcode.companion.infrastructure.repositories.AccountCalls
import me.nathanfallet.teamcode.companion.infrastructure.repositories.RemoteWorkflowRepository
import kotlin.test.*

/** The workflows over a scripted server: the routes the typed client calls, and what is mapped. */
class RemoteWorkflowRepositoryTest {
    private val requests = mutableListOf<HttpRequestData>()
    private var body = "{}"
    private val engine = object : HttpClientEngineFactory<MockEngineConfig> {
        override fun create(block: MockEngineConfig.() -> Unit) = MockEngine(MockEngineConfig().apply {
            block()
            addHandler { request ->
                requests += request
                respond(ByteReadChannel(body), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
            }
        })
    }
    private val repository = RemoteWorkflowRepository(AccountCalls(TeamCodeClientProvider(engine, credentials(), FakePlatform), InMemoryAccountRepository(listOf(account("a1")))))

    private val run = """{"id":"r1","workflowId":"wf1","workflowName":"Fix a ticket","workflowVersion":2,"status":"waiting","startedById":"m1","inputs":{"ticket":"T-1"},
        "sessionId":"s1","currentNodeId":"review","currentNodeKind":"human","visits":[
        {"seq":1,"nodeId":"work","nodeTitle":"Fix it","kind":"agent","status":"done","outcome":"delivered","output":"done","sessionId":"s1","startedAt":10,"finishedAt":20},
        {"seq":2,"nodeId":"review","kind":"teleport","status":"paused","waitId":"w1","startedAt":20}],"createdAt":10,"updatedAt":20}"""

    @Test fun theRunsListAsksForOneStatusTheTeamAndTheCursorAndMapsTheRows() = runTest {
        body = """{"runs":[$run],"nextBefore":"r1"}"""
        val page = repository.runs("a1", RunScope.TEAM, RunFilter.DONE, "r9")
        val url = requests.single().url
        assertEquals("/api/v1/workflows/runs", url.encodedPath)
        assertEquals("succeeded", url.parameters["status"])
        assertEquals("true", url.parameters["all"])
        assertEquals("r9", url.parameters["before"])
        assertEquals("r1", page.nextBefore)
        val row = page.runs.single()
        assertEquals(RunStatus.WAITING, row.status)
        assertEquals("review", row.currentNodeId); assertEquals(StepKind.HUMAN, row.currentKind)
        // The title comes from the definition when the server names it, else it is the node id; a kind or status a newer server adds is kept, not failed.
        assertEquals(listOf("Fix it", "review"), row.visits.map { it.title })
        assertEquals(StepKind.UNKNOWN, row.visits[1].kind); assertEquals(VisitStatus.UNKNOWN, row.visits[1].status)
        assertEquals("w1", row.visits[1].waitId)
    }

    @Test fun mineAndAllSendNoFilterAndNoTeamFlag() = runTest {
        body = """{"runs":[]}"""
        repository.runs("a1", RunScope.MINE, RunFilter.ALL, null)
        val url = requests.single().url
        assertNull(url.parameters["status"]); assertNull(url.parameters["all"]); assertNull(url.parameters["before"])
    }

    @Test fun aStatusFromANewerServerIsKeptAsText() = runTest {
        body = run.replace("\"status\":\"waiting\"", "\"status\":\"paused_by_policy\"")
        val read = repository.run("a1", "r1")
        assertEquals(RunStatus.UNKNOWN, read.status); assertEquals("paused_by_policy", read.rawStatus)
        assertFalse(read.status.active)
    }

    @Test fun startingSendsTheInputsTheProfileAndTheIdempotencyKey() = runTest {
        body = run
        val started = repository.start("a1", "wf1", RunStart(mapOf("ticket" to "T-1"), "p1"), "key-1")
        val request = requests.single()
        assertEquals(HttpMethod.Post, request.method)
        assertEquals("/api/v1/workflows/wf1/runs", request.url.encodedPath)
        assertEquals("key-1", request.headers["Idempotency-Key"])
        assertEquals("r1", started.id)
        val sent = (request.body as io.ktor.http.content.TextContent).text
        assertTrue("\"ticket\":\"T-1\"" in sent && "\"profileId\":\"p1\"" in sent, sent)
        repository.start("a1", "wf1", RunStart(emptyMap(), " "), "key-2")
        assertFalse("\"profileId\":\"" in (requests.last().body as io.ktor.http.content.TextContent).text, "a blank profile is not sent")
    }

    @Test fun cancellingPostsToTheRunsCancelRoute() = runTest {
        body = run.replace("\"waiting\"", "\"cancelled\"")
        assertEquals(RunStatus.CANCELLED, repository.cancel("a1", "r1").status)
        assertEquals(HttpMethod.Post, requests.single().method)
        assertEquals("/api/v1/workflows/runs/r1/cancel", requests.single().url.encodedPath)
    }

    @Test fun aDefinitionMapsItsInputsStepsAndTransitions() = runTest {
        body = """{"id":"wf1","name":"Fix a ticket","description":" ","version":4,"start":"work","sessionMode":"handoff",
            "inputs":[{"id":"ticket","label":"Ticket","required":true,"maxLength":40},{"id":"notes","label":"Notes","required":false,"default":"small"}],
            "nodes":[{"id":"work","kind":"agent","title":"Fix it","deliverable":{"type":"url","domain":"github.com"},"transitions":[{"on":"delivered","to":"review"}]},
            {"id":"review","kind":"human","title":"Review","assignee":{"kind":"member","memberId":"m2"},"actions":[{"id":"ok","label":"Approve"}],"transitions":[{"on":"ok"}]},
            {"id":"x","kind":"quantum","title":"","maxVisits":5}],"limits":{"maxVisits":12},"createdAt":1,"updatedAt":2}"""
        val flow = repository.workflow("a1", "wf1")
        assertEquals("/api/v1/workflows/wf1", requests.single().url.encodedPath)
        assertNull(flow.description, "a blank description is none")
        assertEquals(listOf(WorkflowInput("ticket", "Ticket", true, null, 40), WorkflowInput("notes", "Notes", false, "small", 4_096)), flow.inputs)
        assertEquals(StepDeliverable("url", "github.com"), flow.step("work")?.deliverable)
        assertEquals(listOf(StepTransition("delivered", "review")), flow.step("work")?.transitions)
        assertEquals("m2", flow.step("review")?.assignee)
        assertEquals(StepTransition("ok", null), flow.step("review")?.transitions?.single())
        assertEquals(StepKind.UNKNOWN, flow.step("x")?.kind); assertEquals("quantum", flow.step("x")?.rawKind); assertEquals("x", flow.step("x")?.title)
        assertTrue(flow.needsProfile)
        assertEquals(12, flow.maxVisits)
    }

    @Test fun theDefinitionsListReadsThePageAfterACursor() = runTest {
        body = """{"workflows":[],"nextAfter":"wf9"}"""
        val page = repository.workflows("a1", "wf3")
        assertEquals("wf3", requests.single().url.parameters["after"])
        assertEquals("wf9", page.nextAfter)
    }
}
