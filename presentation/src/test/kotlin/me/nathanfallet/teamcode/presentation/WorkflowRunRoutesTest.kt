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

    private fun unsupported(): WorkflowStore = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(WorkflowStore::class.java)) { _, method, _ ->
        throw IllegalStateException("unexpected workflow store call ${method.name}")
    } as WorkflowStore
}
