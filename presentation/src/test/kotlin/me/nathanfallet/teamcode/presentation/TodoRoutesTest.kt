package me.nathanfallet.teamcode.presentation

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.testApplication
import me.nathanfallet.teamcode.api.Serialization
import me.nathanfallet.teamcode.api.models.*
import me.nathanfallet.teamcode.domain.gateway.*
import me.nathanfallet.teamcode.domain.team.*
import me.nathanfallet.teamcode.domain.todos.*
import kotlin.test.*

/** The todo routes decode, authorize by credential and encode; the rules are the domain's (TodoRules, TodoService). */
class TodoRoutesTest {
    private class Store : TodoStore {
        var list: TodoList? = null
        override suspend fun now() = 1_800_000_000_000L
        override suspend fun revalidate(actor: Principal) = Unit
        /** Token `exec-token` stands for a session execution bound to s1. */
        override suspend fun executionSession(tokenId: String) = "s1".takeIf { tokenId == "exec-token" }
        override suspend fun sessionOwner(teamId: String, sessionId: String) = "admin-id".takeIf { sessionId == "s1" }
        override suspend fun find(teamId: String, sessionId: String) = list?.takeIf { it.sessionId == sessionId }
        override suspend fun create(list: TodoList) { if (this.list != null) throw TeamFailure("todo_exists", 409, "exists"); this.list = list }
        override suspend fun <T> mutate(teamId: String, sessionId: String, change: (TodoList) -> Pair<TodoList, T>): T {
            val (next, value) = change(list ?: throw TeamFailure("todo_not_found", 404, "none")); list = next; return value
        }
        override suspend fun artifactVersion(teamId: String, artifactId: String) = 2.takeIf { artifactId == "art-1" }
        override suspend fun waitState(teamId: String, sessionId: String, waitId: String): WaitState? = null
        override suspend fun hasOpenWait(teamId: String, sessionId: String) = false
        override suspend fun dropOutcomes(teamId: String, waitIds: Collection<String>) = emptyMap<String, DropOutcome>()
    }
    private val gateway = object : GatewayService {
        override suspend fun status() = GatewaySnapshot("test", 0, 0)
        override suspend fun forward(request: GenerationRequest, receive: suspend (GenerationHead, kotlinx.coroutines.flow.Flow<ByteArray>) -> Unit) = Unit
    }
    private fun team(): TeamService {
        val interactive = FakeTeam()
        suspend fun key(token: String, vararg scopes: ServiceScope) = interactive.authenticate("admin")!!.copy(member = Member("admin-id", "admin", Role.MEMBER, false, false), tokenId = token, serviceScopes = scopes.toSet())
        return object : TeamService by interactive {
            override suspend fun authenticate(token: String): Principal? = when (token) {
                "reader" -> key(token, ServiceScope.CLOUD_READ)
                "exec-token" -> key(token, ServiceScope.INFERENCE, ServiceScope.TODO_SESSION)
                "exec-unbound" -> key(token, ServiceScope.TODO_SESSION)
                "exec-old" -> key(token, ServiceScope.INFERENCE, ServiceScope.ARTIFACTS_WRITE)
                else -> interactive.authenticate(token)
            }
        }
    }
    private val description = "Merge the change that adds the route: what, where, why and what is out of scope, enough for a new agent to do it alone."
    private fun item(id: String, deps: String = "[]") = """{"id":"$id","name":"Item $id","description":"$description","dependsOn":$deps,"doneWhen":{"text":"It is merged","checks":[{"type":"pr_merged","repo":"acme/backend"}]}}"""
    private val create = """{"sessionId":"s1","reference":"The plan","items":[${item("a")},${item("b", "[\"a\"]")}]}"""

    @Test fun aTodoIsCreatedStartedAndFinishedOnlyWithItsChecks() = testApplication {
        application { configureGateway(gateway, team = team(), todos = TodoService(Store())) }
        suspend fun call(method: HttpMethod, path: String, token: String?, json: String? = null) = client.request(path) {
            this.method = method; if (token != null) bearerAuth(token)
            if (json != null) { contentType(ContentType.Application.Json); setBody(json) }
        }
        suspend fun code(response: HttpResponse) = Serialization.json.decodeFromString<ApiError>(response.bodyAsText()).error.type
        suspend fun todo(response: HttpResponse) = Serialization.json.decodeFromString<TodoSessionView>(response.bodyAsText()).todo!!
        assertTrue(Todos.CAPABILITY in Serialization.json.decodeFromString<Capabilities>(call(HttpMethod.Get, "/api/v1/capabilities", "admin").bodyAsText()).features)
        assertEquals(HttpStatusCode.Unauthorized, call(HttpMethod.Get, "/api/v1/todos/session?sessionId=s1", null).status)
        assertNull(Serialization.json.decodeFromString<TodoSessionView>(call(HttpMethod.Get, "/api/v1/todos/session?sessionId=s1", "admin").bodyAsText()).todo, "no list yet")

        val created = call(HttpMethod.Post, "/api/v1/todos/session", "admin", create)
        assertEquals(HttpStatusCode.Created, created.status)
        assertEquals("a", todo(created).nextItemId)
        assertEquals("todo_exists", code(call(HttpMethod.Post, "/api/v1/todos/session", "admin", create)))

        val started = Serialization.json.decodeFromString<StartTodoItemView>(call(HttpMethod.Post, "/api/v1/todos/session/items/a/start", "admin", """{"sessionId":"s1"}""").bodyAsText())
        assertTrue(started.marker.startsWith("<!-- teamcode-todo: ") && started.marker.endsWith("/a -->"))
        assertEquals("doing", started.todo.items.first { it.id == "a" }.status)
        assertEquals("dependency_open", code(call(HttpMethod.Post, "/api/v1/todos/session/items/b/start", "admin", """{"sessionId":"s1"}""")))

        val refused = call(HttpMethod.Post, "/api/v1/todos/session/items/a/proof", "admin", """{"sessionId":"s1","checks":[{"index":0,"passed":false,"observed":"OPEN, 2 checks failing"}]}""")
        assertEquals(HttpStatusCode.Conflict, refused.status); assertEquals("not_done", code(refused))
        val seen = todo(call(HttpMethod.Get, "/api/v1/todos/session?sessionId=s1", "admin")).items.first { it.id == "a" }
        assertEquals("doing", seen.status); assertEquals(false, seen.proof!!.passed)
        assertEquals("invalid_proof", code(call(HttpMethod.Post, "/api/v1/todos/session/items/a/proof", "admin", """{"sessionId":"s1","checks":[{"index":5,"passed":true,"observed":"x"}]}""")))
        val done = todo(call(HttpMethod.Post, "/api/v1/todos/session/items/a/proof", "admin", """{"sessionId":"s1","checks":[{"index":0,"passed":true,"observed":"MERGED","urls":["https://github.com/acme/backend/pull/1"]}]}"""))
        assertEquals("done", done.items.first { it.id == "a" }.status); assertEquals("b", done.nextItemId)

        val linked = todo(call(HttpMethod.Post, "/api/v1/todos/session/items/b/link", "admin", """{"sessionId":"s1","kind":"pr","ref":"https://github.com/acme/backend/pull/9","number":9}"""))
        assertEquals(9, linked.items.first { it.id == "b" }.links.single().number)
        assertEquals("already_linked", code(call(HttpMethod.Post, "/api/v1/todos/session/items/b/link", "admin", """{"sessionId":"s1","kind":"pr","ref":"https://github.com/acme/backend/pull/10"}""")))
        assertEquals("invalid_link", code(call(HttpMethod.Post, "/api/v1/todos/session/items/b/link", "admin", """{"sessionId":"s1","kind":"tweet","ref":"x"}""")))
        val noted = todo(call(HttpMethod.Post, "/api/v1/todos/session/items/b/notes", "admin", """{"sessionId":"s1","text":"Waiting for review"}"""))
        assertEquals("Waiting for review", noted.items.first { it.id == "b" }.notes.single().text)
        val added = todo(call(HttpMethod.Post, "/api/v1/todos/session/items", "admin", """{"sessionId":"s1","reason":"Found a migration","item":${item("extra")}}"""))
        assertEquals("discovered", added.items.first { it.id == "extra" }.origin)
    }

    @Test fun badInputIsRefusedWithItsCodeAndKeysAreLimitedToTheirScope() = testApplication {
        application { configureGateway(gateway, team = team(), todos = TodoService(Store())) }
        suspend fun call(method: HttpMethod, path: String, token: String, json: String? = null) = client.request(path) {
            this.method = method; bearerAuth(token)
            if (json != null) { contentType(ContentType.Application.Json); setBody(json) }
        }
        suspend fun code(response: HttpResponse) = Serialization.json.decodeFromString<ApiError>(response.bodyAsText()).error.type
        assertEquals("invalid_todo", code(call(HttpMethod.Post, "/api/v1/todos/session", "admin", create.replace("pr_merged", "magic"))))
        assertEquals("guessed_number", code(call(HttpMethod.Post, "/api/v1/todos/session", "admin", create.replace("{\"type\":\"pr_merged\",\"repo\":\"acme/backend\"}", "{\"type\":\"command\",\"run\":\"gh api repos/acme/backend/pulls/1310\"}"))))
        assertEquals("check_required", code(call(HttpMethod.Post, "/api/v1/todos/session", "admin", create.replace("[{\"type\":\"pr_merged\",\"repo\":\"acme/backend\"}]", "[]"))))
        assertEquals("not_found", code(call(HttpMethod.Post, "/api/v1/todos/session", "admin", create.replace("\"s1\"", "\"s2\""))))
        assertEquals(HttpStatusCode.NotFound, call(HttpMethod.Post, "/api/v1/todos/session/items/NOT%20A%20SLUG/start", "admin", "{}").status)
        assertEquals(HttpStatusCode.BadRequest, call(HttpMethod.Get, "/api/v1/todos/session?sessionId=bad%20id", "admin").status)

        // A cloud:read key reads but never writes; the execution credential acts on its own session whatever it names.
        assertEquals(HttpStatusCode.OK, call(HttpMethod.Get, "/api/v1/todos/session?sessionId=s1", "reader").status)
        assertEquals(HttpStatusCode.Forbidden, call(HttpMethod.Post, "/api/v1/todos/session", "reader", create).status)
        val own = call(HttpMethod.Post, "/api/v1/todos/session", "exec-token", create.replace("\"s1\"", "\"other\""))
        assertEquals(HttpStatusCode.Created, own.status)
        assertEquals("s1", Serialization.json.decodeFromString<TodoSessionView>(own.bodyAsText()).todo!!.sessionId)
        // A key that carries todo:session without being bound to a session, and a credential from before the scope, get nothing.
        assertEquals("credential_scope", code(call(HttpMethod.Get, "/api/v1/todos/session", "exec-unbound")))
        assertEquals(HttpStatusCode.Forbidden, call(HttpMethod.Get, "/api/v1/todos/session", "exec-old").status)
    }

    @Test fun theStopHookAsksThroughItsRoutesAndAFailureIsRecorded() = testApplication {
        application { configureGateway(gateway, team = team(), todos = TodoService(Store())) }
        suspend fun call(path: String, token: String, json: String) = client.request(path) { this.method = HttpMethod.Post; bearerAuth(token); contentType(ContentType.Application.Json); setBody(json) }
        suspend fun code(response: HttpResponse) = Serialization.json.decodeFromString<ApiError>(response.bodyAsText()).error.type
        suspend fun stop(json: String) = Serialization.json.decodeFromString<TodoStopView>(call("/api/v1/todos/session/stop", "exec-token", json).bodyAsText())
        assertEquals("no_list", stop("""{}""").why)
        suspend fun context() = Serialization.json.decodeFromString<TodoContextView>(client.request("/api/v1/todos/session/context") { bearerAuth("exec-token") }.bodyAsText()).text
        assertNull(context(), "no list, no reminder")
        call("/api/v1/todos/session", "exec-token", create)
        assertTrue(context()!!.startsWith("[TeamCode todo] Your todo: 0 of 2 items done.\nPlan reference:\n  The plan"), context())
        assertEquals(HttpStatusCode.Forbidden, client.request("/api/v1/todos/session/context") { bearerAuth("exec-old") }.status)
        assertEquals("verify", stop("""{"phase":"begin"}""").action)
        // The Stop hook of a local run asks what a wait has to say: the route answers from the session of its credential, and a reader cannot.
        val wokeCall = call("/api/v1/todos/session/wake", "exec-token", """{"waitMillis":0}""")
        val woke = Serialization.json.decodeFromString<TodoWakeView>(wokeCall.bodyAsText().also { assertEquals(HttpStatusCode.OK, wokeCall.status, it) })
        assertEquals(false to emptyList(), woke.open to woke.messages, "A server without local sessions never holds the hook")
        assertEquals(HttpStatusCode.Forbidden, call("/api/v1/todos/session/wake", "reader", """{}""").status)
        assertEquals("invalid_todo", code(call("/api/v1/todos/session/stop", "exec-token", """{"phase":"later"}""")))
        val first = stop("""{"phase":"verify","items":[{"itemId":"a","checks":[{"index":0,"passed":true,"observed":"PR #1 MERGED"}]}],"orphans":["https://github.com/acme/backend/pull/5"],"lastMessage":"done"}""")
        assertEquals("block", first.action)
        assertTrue(first.reason!!.startsWith("[TeamCode todo] 1 of 3 items done."), first.reason)
        assertEquals(listOf("a", "finish-pr-5", "b").sorted(), first.todo!!.items.map { it.id }.sorted())
        val failed = Serialization.json.decodeFromString<TodoSessionView>(call("/api/v1/todos/session/hook-error", "exec-token", """{"text":"The stop hook ran out of time (140 s)."}""").bodyAsText())
        assertEquals(listOf("hook_error"), failed.todo!!.hookEvents.map { it.kind })
        // Another credential (the reader, a key without todo:session) never reaches them.
        assertEquals(HttpStatusCode.Forbidden, call("/api/v1/todos/session/stop", "reader", """{}""").status)
        assertEquals(HttpStatusCode.Forbidden, call("/api/v1/todos/session/hook-error", "reader", """{"text":"x"}""").status)
    }
}
