package me.nathanfallet.teamcode.presentation

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.testApplication
import me.nathanfallet.teamcode.api.Serialization
import me.nathanfallet.teamcode.api.models.*
import me.nathanfallet.teamcode.domain.gateway.*
import me.nathanfallet.teamcode.domain.team.*
import me.nathanfallet.teamcode.domain.workflows.*
import java.lang.reflect.Proxy
import kotlin.coroutines.Continuation
import kotlin.test.*

/** The run routes decode, authorize by credential and encode; the rules are the domain's (WorkflowRunService). */
class WorkflowRunRoutesTest {
    /** A store that answers what these checks reach (an empty history) and fails loudly for anything else. */
    private val store = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(WorkflowRunStore::class.java)) { _, method, args ->
        @Suppress("UNCHECKED_CAST") val done = args.last() as Continuation<Any?>
        when (method.name) {
            "revalidate" -> Unit
            "now" -> 1L
            "list" -> WorkflowRunPage(emptyList(), null)
            "find" -> null
            else -> throw IllegalStateException("unexpected store call ${method.name}")
        }.also { done.resumeWith(Result.success(it)) }.let { kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED }
    } as WorkflowRunStore
    private val gateway = object : GatewayService {
        override suspend fun status() = GatewaySnapshot("test", 0, 0)
        override suspend fun forward(request: GenerationRequest, receive: suspend (GenerationHead, kotlinx.coroutines.flow.Flow<ByteArray>) -> Unit) = Unit
    }
    private fun team(): TeamService {
        val interactive = FakeTeam()
        return object : TeamService by interactive {
            override suspend fun authenticate(token: String): Principal? = when (token) {
                "reader" -> interactive.authenticate("admin")!!.copy(tokenId = "reader", serviceScopes = setOf(ServiceScope.CLOUD_READ))
                else -> interactive.authenticate(token)
            }
        }
    }

    @Test fun runsAreReadByCredentialAndWritesNeedMoreThanReading() = testApplication {
        application { configureGateway(gateway, team = team(), workflows = WorkflowService(object : WorkflowStore by unsupported() {}), workflowRuns = WorkflowRunService(store)) }
        suspend fun call(method: HttpMethod, path: String, token: String?, json: String? = null) = client.request(path) {
            this.method = method; if (token != null) bearerAuth(token)
            if (json != null) { contentType(ContentType.Application.Json); setBody(json) }
        }
        suspend fun code(response: HttpResponse) = Serialization.json.decodeFromString<ApiError>(response.bodyAsText()).error.type

        assertEquals(HttpStatusCode.Unauthorized, call(HttpMethod.Get, "/api/v1/workflows/runs", null).status)
        val empty = call(HttpMethod.Get, "/api/v1/workflows/runs", "reader")
        assertEquals(HttpStatusCode.OK, empty.status)
        assertEquals(emptyList(), Serialization.json.decodeFromString<WorkflowRunPageView>(empty.bodyAsText()).runs)
        assertEquals("invalid_limit", code(call(HttpMethod.Get, "/api/v1/workflows/runs?limit=0", "reader")))
        assertEquals("invalid_request", code(call(HttpMethod.Get, "/api/v1/workflows/runs?status=nope", "reader")))
        assertEquals("invalid_cursor", code(call(HttpMethod.Get, "/api/v1/workflows/runs?before=../x", "reader")))
        assertEquals("not_found", code(call(HttpMethod.Get, "/api/v1/workflows/runs/missing", "reader")))
        assertEquals("credential_scope", code(call(HttpMethod.Get, "/api/v1/workflows/runs/%20bad", "reader")), "an id outside the route shape is not a route a key may reach")
        // A key that only reads never starts, cancels or delivers.
        assertEquals(HttpStatusCode.Forbidden, call(HttpMethod.Post, "/api/v1/workflows/wf1/runs", "reader", """{"inputs":{}}""").status)
        assertEquals(HttpStatusCode.Forbidden, call(HttpMethod.Post, "/api/v1/workflows/runs/r1/deliver", "reader", """{"output":"x"}""").status)
        assertEquals(HttpStatusCode.Forbidden, call(HttpMethod.Post, "/api/v1/workflows/runs/r1/cancel", "reader").status)
    }

    private val definition = WorkflowDefinition("Fix", null, "ship", SessionMode.SAME, emptyList(),
        listOf(WorkflowNode("ship", NodeKind.AGENT, "Ship", "Ship it", deliverable = Deliverable(DeliverableType.TEXT), transitions = listOf(Transition("delivered")))))
    /** One run of `admin-id` whose agent step runs in session s1; the token `exec-token` is the execution credential of s1, `exec-9` that of another session. */
    private inner class Delivering : WorkflowRunStore by unsupportedRuns() {
        var run = WorkflowRun("r1", "team", "wf1", "Fix", 1, definition, "admin-id", RunStatus.RUNNING, emptyMap(), "p1", "s1", 0, null, null, false, null, null, 1, 1, null)
        var visit = NodeVisit("v1", "r1", 1, "ship", NodeKind.AGENT, VisitStatus.ACTIVE, sessionId = "s1", startedAt = 1)
        override suspend fun now() = 5L
        override suspend fun revalidate(actor: Principal) = Unit
        override suspend fun executionSession(tokenId: String) = mapOf("exec-token" to "s1", "exec-9" to "s9")[tokenId]
        override suspend fun runOfSession(teamId: String, sessionId: String) = (run to visit).takeIf { sessionId == "s1" && visit.status == VisitStatus.ACTIVE }
        override suspend fun sessionOwner(teamId: String, sessionId: String) = mapOf("s1" to "admin-id", "s9" to "admin-id", "s8" to "someone-else")[sessionId]
        override suspend fun complete(visitId: String, status: VisitStatus, outcome: String?, output: String?, at: Long): Boolean {
            if (visit.status != VisitStatus.ACTIVE) return false
            visit = visit.copy(status = status, outcome = outcome, output = output, finishedAt = at); return true
        }
        override suspend fun find(teamId: String, id: String) = run
    }

    @Test fun theAgentDeliversBySessionWithItsExecutionCredentialOrTheStartersKey() = testApplication {
        val store = Delivering()
        val team = FakeTeam().let { interactive ->
            object : TeamService by interactive {
                override suspend fun authenticate(token: String): Principal? = when (token) {
                    "exec-token", "exec-9" -> interactive.authenticate("admin")!!.copy(tokenId = token, serviceScopes = setOf(ServiceScope.INFERENCE, ServiceScope.WORKFLOW_SESSION))
                    "exec-unbound" -> interactive.authenticate("admin")!!.copy(tokenId = token, serviceScopes = setOf(ServiceScope.WORKFLOW_SESSION))
                    "exec-old" -> interactive.authenticate("admin")!!.copy(tokenId = token, serviceScopes = setOf(ServiceScope.INFERENCE, ServiceScope.ARTIFACTS_WRITE))
                    "writer" -> interactive.authenticate("admin")!!.copy(tokenId = token, serviceScopes = setOf(ServiceScope.CLOUD_WRITE))
                    "reader" -> interactive.authenticate("admin")!!.copy(tokenId = token, serviceScopes = setOf(ServiceScope.CLOUD_READ))
                    else -> interactive.authenticate(token)
                }
            }
        }
        application { configureGateway(gateway, team = team, workflows = WorkflowService(object : WorkflowStore by unsupported() {}), workflowRuns = WorkflowRunService(store)) }
        suspend fun call(path: String, token: String, json: String) = client.request(path) {
            method = HttpMethod.Post; bearerAuth(token); contentType(ContentType.Application.Json); setBody(json)
        }
        suspend fun code(response: HttpResponse) = Serialization.json.decodeFromString<ApiError>(response.bodyAsText()).error.type
        val path = "/api/v1/workflows/session/deliver"
        // Refused before anything is touched: no session credential, a reader, a scope without a session, an old execution.
        assertEquals("credential_scope", code(call(path, "reader", """{"sessionId":"s1","output":"done"}""")))
        assertEquals("credential_scope", code(call(path, "exec-unbound", """{"output":"done"}""")))
        assertEquals("credential_scope", code(call(path, "exec-old", """{"output":"done"}""")))
        // The scope grants nothing else: not the run route, not a read.
        assertEquals("credential_scope", code(call("/api/v1/workflows/runs/r1/deliver", "exec-token", """{"output":"done"}""")))
        assertEquals(HttpStatusCode.Forbidden, client.get("/api/v1/workflows/runs/r1") { bearerAuth("exec-token") }.status)
        // The starter's own key must name the session; an unknown or another member's session is not found.
        assertEquals("invalid_request", code(call(path, "writer", """{"output":"done"}""")))
        assertEquals("not_found", code(call(path, "writer", """{"sessionId":"s8","output":"done"}""")))
        assertEquals("not_found", code(call(path, "writer", """{"sessionId":"../x","output":"done"}""")))
        assertEquals("nothing_to_deliver", code(call(path, "writer", """{"sessionId":"s9","output":"done"}""")), "her own session, no step running")
        // An execution credential is confined to its session, whatever the body names; a bad output is refused and stored nowhere.
        assertEquals("nothing_to_deliver", code(call(path, "exec-9", """{"sessionId":"s1","output":"done"}""")))
        assertEquals("invalid_deliverable", code(call(path, "exec-token", """{"output":{"a":1}}""")))
        assertEquals(VisitStatus.ACTIVE, store.visit.status)
        val delivered = call(path, "exec-token", """{"sessionId":"s9","output":"all done"}""")
        assertEquals(HttpStatusCode.OK, delivered.status)
        assertEquals("r1", Serialization.json.decodeFromString<WorkflowRunView>(delivered.bodyAsText()).id)
        assertEquals("all done", store.visit.output)
        assertEquals("nothing_to_deliver", code(call(path, "writer", """{"sessionId":"s1","output":"again"}""")), "a second delivery finds the step done")
    }

    private fun unsupportedRuns(): WorkflowRunStore = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(WorkflowRunStore::class.java)) { _, method, _ ->
        throw IllegalStateException("unexpected run store call ${method.name}")
    } as WorkflowRunStore

    private fun unsupported(): WorkflowStore = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(WorkflowStore::class.java)) { _, method, _ ->
        throw IllegalStateException("unexpected workflow store call ${method.name}")
    } as WorkflowStore
}
