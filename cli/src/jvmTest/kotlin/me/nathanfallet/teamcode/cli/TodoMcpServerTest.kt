package me.nathanfallet.teamcode.cli

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import me.nathanfallet.teamcode.api.Serialization
import me.nathanfallet.teamcode.api.models.*
import me.nathanfallet.teamcode.cli.mcp.TodoMcpServer
import me.nathanfallet.teamcode.cli.proxy.TODO_RELAY_ROUTES
import me.nathanfallet.teamcode.cli.todo.*
import me.nathanfallet.teamcode.client.TeamCodeClient
import kotlin.test.*

class TodoMcpServerTest {
    private val session = "00000000-0000-4000-8000-0000000000cc"
    private val repo = "acme/backend"
    private val description = "Do this item completely: what, where, why and what is out of scope, enough for a new agent to do it alone."

    private fun item(id: String, status: String = "todo", checks: List<TodoCheckView>, links: List<TodoLinkView> = emptyList(), dependsOn: List<String> = emptyList()) = TodoItemView(
        id, "Item $id", description, TodoDoneWhenView("It is done", checks), dependsOn, "plan", null, status, null, emptyList(), links = links,
    )
    private fun view(vararg items: TodoItemView, next: String? = items.firstOrNull { it.status == "todo" }?.id) =
        TodoView("7f3a9c10-aaaa-4bbb-8ccc-000000000001", session, "The plan: https://teamcode.example/artifact/p1", emptyList(), "active", 0, items.toList(), 1, 2, next)

    /** The todo routes as a stateful server answers them, recording each request. */
    private inner class Server(var todo: TodoView?) {
        val requests = mutableListOf<Pair<String, String>>()
        var refuse: Pair<String, String>? = null
        val client = TeamCodeClient("https://team.example", { "token" }) { config -> HttpClient(MockEngine { request ->
            val body = request.body.toByteArray().decodeToString()
            val route = "${request.method.value} ${request.url.encodedPath}"
            requests += route to body
            val json = headersOf(HttpHeaders.ContentType, "application/json")
            fun ok(value: TodoView?) = respond(Serialization.json.encodeToString(TodoSessionView(value)), HttpStatusCode.OK, json)
            refuse?.takeIf { route.endsWith("/proof") }?.let { (code, message) ->
                // The server keeps the proof of a refusal on the item; the client only carries the error code.
                todo = todo?.copy(items = todo!!.items.map { it.copy(proof = TodoProofView(1, false, message)) })
                return@MockEngine respond("""{"error":{"type":"$code","message":"$message"}}""", HttpStatusCode.Conflict, json)
            }
            when {
                route == "GET /api/v1/todos/session" -> ok(todo)
                route == "POST /api/v1/todos/session" -> { todo = view(item("a", checks = listOf(TodoCheckView("pr_merged", repo = repo)))); ok(todo) }
                route.endsWith("/start") -> respond(Serialization.json.encodeToString(StartTodoItemView(todo!!, "<!-- teamcode-todo: 7f3a9c10/a -->")), HttpStatusCode.OK, json)
                else -> ok(todo)
            }
        }) { config(this) } }
        /** `gh` and the shell, as the agent's machine answers them. */
        val answers = mutableListOf<Pair<(List<String>) -> Boolean, Captured>>()
        val ran = mutableListOf<List<String>>()
        val mcp = TodoMcpServer(client, session, TodoChecks({ command, arguments, _, _ ->
            ran += listOf(command) + arguments
            answers.firstOrNull { it.first(arguments) }?.second
        }, session))
        fun gh(match: (List<String>) -> Boolean, output: String, exit: Int = 0) { answers += match to Captured(exit, output) }
    }

    private fun request(method: String, params: JsonObject = JsonObject(emptyMap()), id: Int = 1) =
        buildJsonObject { put("jsonrpc", "2.0"); put("id", id); put("method", method); put("params", params) }.toString()
    private suspend fun Server.tool(name: String, arguments: JsonObject = JsonObject(emptyMap())): Pair<Boolean, String> {
        val answer = Json.parseToJsonElement(mcp.handle(request("tools/call", buildJsonObject { put("name", name); put("arguments", arguments) }))!!).jsonObject["result"]!!.jsonObject
        return (answer["isError"]?.jsonPrimitive?.boolean == true) to answer["content"]!!.jsonArray.single().jsonObject["text"]!!.jsonPrimitive.content
    }
    private fun pr(state: String, sha: String? = null, body: String = "Does the thing.\n\n<!-- teamcode-todo: 7f3a9c10/a -->") = """[{"number":1310,"state":"$state","url":"https://github.com/$repo/pull/1310","author":{"login":"ada"},"body":${JsonPrimitive(body)},"mergeCommit":${if (sha == null) "null" else "{\"oid\":\"$sha\"}"}}]"""

    @Test fun speaksMcpAndServesTheNineTools() = runBlocking {
        val server = Server(null)
        val init = Json.parseToJsonElement(server.mcp.handle(request("initialize", buildJsonObject { put("protocolVersion", "2025-06-18") }))!!).jsonObject["result"]!!.jsonObject
        assertEquals("teamcode-todo", init["serverInfo"]!!.jsonObject["name"]!!.jsonPrimitive.content)
        assertEquals(TodoDocs.INSTRUCTIONS, init["instructions"]!!.jsonPrimitive.content)
        val tools = Json.parseToJsonElement(server.mcp.handle(request("tools/list"))!!).jsonObject["result"]!!.jsonObject["tools"]!!.jsonArray
        assertEquals(setOf("todo_create", "todo_show", "todo_start", "todo_complete", "todo_link", "todo_help", "todo_add", "todo_note", "todo_drop"), tools.map { it.jsonObject["name"]!!.jsonPrimitive.content }.toSet())
        tools.forEach { assertTrue(it.jsonObject["description"]!!.jsonPrimitive.content.length > 40, "every tool says when to call it") }
        assertNull(server.mcp.handle("""{"jsonrpc":"2.0","method":"notifications/initialized"}"""))
        assertTrue("Unknown tool" in server.mcp.handle(request("tools/call", buildJsonObject { put("name", "ask_human") }))!!)
        assertEquals(emptyList(), server.requests)
        val (error, help) = server.tool("todo_help")
        assertFalse(error); assertTrue("EXAMPLE 1" in help && "EXAMPLE 2" in help && "EXAMPLE 3" in help && "artifact_published" in help)
        assertEquals(emptyList(), server.requests, "the help is local")
    }

    @Test fun theInstructionsNeverTeachAGuessedNumber() {
        val text = TodoDocs.INSTRUCTIONS + TodoDocs.HELP
        assertTrue("never a number you expect" in TodoDocs.INSTRUCTIONS || "Never write a PR number" in TodoDocs.HELP)
        assertFalse(Regex("pulls?/\\d+|#\\d{2,}").containsMatchIn(text.replace("\${pr:", "")), "no example names a PR number")
    }

    @Test fun createsOnceForItsOwnSessionAndShowsTheList() = runBlocking {
        val server = Server(null)
        assertTrue("no todo yet" in server.tool("todo_show").second)
        val items = buildJsonArray { addJsonObject {
            put("id", "a"); put("name", "Item a"); put("description", description)
            putJsonObject("doneWhen") { put("text", "Merged"); putJsonArray("checks") { addJsonObject { put("type", "pr_merged"); put("repo", repo) } } }
        } }
        val (error, text) = server.tool("todo_create", buildJsonObject { put("reference", "The plan"); put("items", items) })
        assertFalse(error); assertContains(text, "Todo created with 1 items"); assertContains(text, "Plan reference")
        val sent = Json.parseToJsonElement(server.requests.last().second).jsonObject
        assertEquals(session, sent["sessionId"]!!.jsonPrimitive.content)
        // A malformed item is refused here with the format to follow; a server refusal carries its action.
        val (bad, why) = server.tool("todo_create", buildJsonObject { put("reference", "x"); put("items", buildJsonArray { addJsonObject { put("id", "a") } }) })
        assertTrue(bad && "todo_help" in why, why)
        assertEquals(1, server.requests.count { it.first == "POST /api/v1/todos/session" }, "nothing is sent for a malformed list")
    }

    @Test fun startReturnsTheMarkerForAPrItem() = runBlocking {
        val server = Server(view(item("a", checks = listOf(TodoCheckView("pr_merged", repo = repo)))))
        val (error, text) = server.tool("todo_start", buildJsonObject { put("id", "a") })
        assertFalse(error); assertContains(text, "<!-- teamcode-todo: 7f3a9c10/a -->"); assertContains(text, "body of the pull request")
        assertEquals("POST /api/v1/todos/session/items/a/start", server.requests.single().first)
    }

    @Test fun completeRunsTheStoredChecksLinksThePrAndReportsByPosition() = runBlocking {
        val server = Server(view(item("a", "doing", listOf(TodoCheckView("pr_merged", repo = repo)))))
        server.gh({ it.take(2) == listOf("pr", "list") }, pr("OPEN"))
        server.gh({ it.take(2) == listOf("api", "user") }, "ada\n")
        server.refuse = "not_done" to "PR #1310 OPEN"
        val (refused, why) = server.tool("todo_complete", buildJsonObject { put("id", "a") })
        assertTrue(refused); assertContains(why, "PR #1310 OPEN"); assertContains(why, "call todo_complete again")
        var proof = Json.parseToJsonElement(server.requests.last { it.first.endsWith("/proof") }.second).jsonObject["checks"]!!.jsonArray.single().jsonObject
        assertEquals(0, proof["index"]!!.jsonPrimitive.int); assertEquals(false, proof["passed"]!!.jsonPrimitive.boolean)
        // The search is scoped to the item's repo and narrowed by the list prefix and the item id; the exact marker decides.
        val search = server.ran.first { it.take(3) == listOf("gh", "pr", "list") }
        assertContains(search, "acme/backend"); assertContains(search, "7f3a9c10 a in:body")

        server.answers.clear(); server.refuse = null; server.ran.clear()
        server.gh({ it.take(2) == listOf("pr", "list") }, pr("MERGED", sha = "4e1c9a2f00"))
        server.gh({ it.take(2) == listOf("api", "user") }, "ada\n")
        val (done, text) = server.tool("todo_complete", buildJsonObject { put("id", "a") })
        assertFalse(done, text); assertContains(text, "Item a is done")
        val link = server.requests.last { it.first.endsWith("/a/link") }
        val sent = Json.parseToJsonElement(link.second).jsonObject
        assertEquals("pr", sent["kind"]!!.jsonPrimitive.content); assertEquals("https://github.com/$repo/pull/1310", sent["ref"]!!.jsonPrimitive.content)
        assertEquals(1310, sent["number"]!!.jsonPrimitive.int); assertEquals("4e1c9a2f00", sent["sha"]!!.jsonPrimitive.content, "the merge commit completes the link")
        proof = Json.parseToJsonElement(server.requests.last().second).jsonObject["checks"]!!.jsonArray.single().jsonObject
        assertEquals(true, proof["passed"]!!.jsonPrimitive.boolean)
        assertTrue(server.requests.indexOf(link) < server.requests.indexOfLast { it.first.endsWith("/proof") }, "the link is recorded before the proof")
    }

    @Test fun aPrFromAnotherAuthorOrAnotherListIsNeverTaken() = runBlocking {
        val server = Server(view(item("a", "doing", listOf(TodoCheckView("pr_merged", repo = repo)))))
        server.gh({ it.take(2) == listOf("api", "user") }, "ada\n")
        server.gh({ it.take(2) == listOf("pr", "list") }, pr("MERGED", "abc", body = "<!-- teamcode-todo: 99999999/a -->"))
        server.tool("todo_complete", buildJsonObject { put("id", "a") })
        var report = Json.parseToJsonElement(server.requests.last().second).jsonObject["checks"]!!.jsonArray.single().jsonObject
        assertEquals(false, report["passed"]!!.jsonPrimitive.boolean, "another list's marker")
        server.answers.clear()
        server.gh({ it.take(2) == listOf("api", "user") }, "mallory\n")
        server.gh({ it.take(2) == listOf("pr", "list") }, pr("MERGED", "abc"))
        server.tool("todo_complete", buildJsonObject { put("id", "a") })
        report = Json.parseToJsonElement(server.requests.last().second).jsonObject["checks"]!!.jsonArray.single().jsonObject
        assertEquals(false, report["passed"]!!.jsonPrimitive.boolean, "a PR that someone else opened")
    }

    @Test fun ghDownOrSlowIsUnknownNeverDone() = runBlocking {
        val server = Server(view(item("a", "doing", listOf(TodoCheckView("pr_merged", repo = repo)))))
        server.gh({ true }, "boom", exit = 1)
        server.tool("todo_complete", buildJsonObject { put("id", "a") })
        val report = Json.parseToJsonElement(server.requests.last().second).jsonObject["checks"]!!.jsonArray.single().jsonObject
        assertEquals(JsonNull, report["passed"], "unknown")
        assertTrue("unknown" in report["observed"]!!.jsonPrimitive.content)
    }

    @Test fun aReleaseIsTheEarliestPublishedOneThatContainsTheMerge() = runBlocking {
        val linkedPr = TodoLinkView("pr", "https://github.com/$repo/pull/1310", 1310, "4e1c9a2f00")
        val server = Server(view(
            item("a", "done", listOf(TodoCheckView("pr_merged", repo = repo)), links = listOf(linkedPr)),
            item("r", "doing", listOf(TodoCheckView("release_contains", repo = repo, of = "a")), dependsOn = listOf("a")),
        ))
        server.gh({ it.take(2) == listOf("release", "list") }, """[{"tagName":"v1.3.0"},{"tagName":"v1.2.0"},{"tagName":"v1.1.0"}]""")
        server.gh({ "compare/4e1c9a2f00...v1.3.0" in it.joinToString(" ") }, "ahead\n")
        server.gh({ "compare/4e1c9a2f00...v1.2.0" in it.joinToString(" ") }, "ahead\n")
        server.gh({ "compare/4e1c9a2f00...v1.1.0" in it.joinToString(" ") }, "behind\n")
        server.tool("todo_complete", buildJsonObject { put("id", "r") })
        val link = server.requests.first { it.first.endsWith("/r/link") }
        val sent = Json.parseToJsonElement(link.second).jsonObject
        assertEquals("release", sent["kind"]!!.jsonPrimitive.content); assertEquals("v1.2.0", sent["tag"]!!.jsonPrimitive.content, "the earliest one, whatever the newer releases")
        assertEquals(true, Json.parseToJsonElement(server.requests.last().second).jsonObject["checks"]!!.jsonArray.single().jsonObject["passed"]!!.jsonPrimitive.boolean)
        // No release contains it yet: false, not unknown.
        server.answers.clear(); server.requests.clear()
        server.gh({ it.take(2) == listOf("release", "list") }, """[{"tagName":"v1.1.0"}]""")
        server.gh({ "compare" in it.joinToString(" ") }, "behind\n")
        server.tool("todo_complete", buildJsonObject { put("id", "r") })
        assertEquals(false, Json.parseToJsonElement(server.requests.last().second).jsonObject["checks"]!!.jsonArray.single().jsonObject["passed"]!!.jsonPrimitive.boolean)
    }

    @Test fun aCommandCitesWhatWasLinkedAndWaitsForWhatWasNot() = runBlocking {
        val check = TodoCheckView("command", run = "test \"\${release:r.tag}\" = v1.2.0 && echo PR \${pr:a.number}")
        val linked = Server(view(
            item("a", "done", listOf(TodoCheckView("pr_merged", repo = repo)), links = listOf(TodoLinkView("pr", "https://github.com/$repo/pull/1310", 1310, "abc"))),
            item("r", "done", listOf(TodoCheckView("release_contains", repo = repo, of = "a")), links = listOf(TodoLinkView("release", "https://github.com/$repo/releases/tag/v1.2.0", tag = "v1.2.0"))),
            item("c", "doing", listOf(check)),
        ))
        linked.answers += { _: List<String> -> true } to Captured(0, "PR 1310\n")
        linked.tool("todo_complete", buildJsonObject { put("id", "c") })
        assertEquals(listOf("sh", "-c", "test \"v1.2.0\" = v1.2.0 && echo PR 1310"), linked.ran.single(), "the values are cited, then the shell runs it")
        assertEquals(true, Json.parseToJsonElement(linked.requests.last().second).jsonObject["checks"]!!.jsonArray.single().jsonObject["passed"]!!.jsonPrimitive.boolean)

        val unlinked = Server(view(item("r", checks = listOf(TodoCheckView("release_contains", repo = repo, of = "a"))), item("c", "doing", listOf(check))))
        unlinked.tool("todo_complete", buildJsonObject { put("id", "c") })
        assertEquals(emptyList(), unlinked.ran, "nothing runs while a cited value is not linked")
        val report = Json.parseToJsonElement(unlinked.requests.last().second).jsonObject["checks"]!!.jsonArray.single().jsonObject
        assertEquals(JsonNull, report["passed"]); assertContains(report["observed"]!!.jsonPrimitive.content, "waiting for the release of item r")
    }

    @Test fun aCommandThatFailsOrOutputsASecretIsReportedMasked() = runBlocking {
        val server = Server(view(item("c", "doing", listOf(TodoCheckView("command", run = "make check", expectExit = 0, timeoutSeconds = 5)))))
        server.answers += { _: List<String> -> true } to Captured(2, "token ghp_abcdefghijklmnopqrstuvwxyz0123456789 rejected\n")
        server.tool("todo_complete", buildJsonObject { put("id", "c") })
        val report = Json.parseToJsonElement(server.requests.last().second).jsonObject["checks"]!!.jsonArray.single().jsonObject
        assertEquals(false, report["passed"]!!.jsonPrimitive.boolean)
        val observed = report["observed"]!!.jsonPrimitive.content
        assertContains(observed, "exited 2"); assertContains(observed, "[REDACTED]"); assertFalse("ghp_" in observed)
    }

    @Test fun anAttestedCheckNeedsProofText() = runBlocking {
        val server = Server(view(item("w", "doing", listOf(TodoCheckView("attested", what = "Watched staging for a day")))))
        server.tool("todo_complete", buildJsonObject { put("id", "w") })
        assertEquals(false, Json.parseToJsonElement(server.requests.last().second).jsonObject["checks"]!!.jsonArray.single().jsonObject["passed"]!!.jsonPrimitive.boolean)
        server.tool("todo_complete", buildJsonObject { put("id", "w"); putJsonObject("proof") { put("text", "No error in 24 hours"); putJsonArray("urls") { add("https://grafana.example/d/1"); add("http://insecure.example") } } })
        val report = Json.parseToJsonElement(server.requests.last().second).jsonObject["checks"]!!.jsonArray.single().jsonObject
        assertEquals(true, report["passed"]!!.jsonPrimitive.boolean)
        assertEquals(listOf("https://grafana.example/d/1"), report["urls"]!!.jsonArray.map { it.jsonPrimitive.content }, "only https URLs are kept")
    }

    @Test fun linkDeducesItsKindFromAGitHubAddress() = runBlocking {
        val server = Server(view(item("a", "doing", listOf(TodoCheckView("pr_merged", repo = repo)))))
        server.tool("todo_link", buildJsonObject { put("id", "a"); put("ref", "https://github.com/$repo/pull/77") })
        val sent = Json.parseToJsonElement(server.requests.last().second).jsonObject
        assertEquals("pr", sent["kind"]!!.jsonPrimitive.content); assertEquals(77, sent["number"]!!.jsonPrimitive.int)
        server.tool("todo_link", buildJsonObject { put("id", "a"); put("ref", "art-1"); put("kind", "artifact") })
        assertEquals("artifact", Json.parseToJsonElement(server.requests.last().second).jsonObject["kind"]!!.jsonPrimitive.content)
        val (error, why) = server.tool("todo_link", buildJsonObject { put("id", "a"); put("ref", "art-1") })
        assertTrue(error && "kind" in why)
    }

    @Test fun theRelayAllowsTheTodoRoutesAndNothingElse() {
        assertEquals(mapOf(HttpMethod.Get to 1, HttpMethod.Post to 5), TODO_RELAY_ROUTES.mapValues { it.value.size })
        val posts = TODO_RELAY_ROUTES.getValue(HttpMethod.Post)
        for (path in listOf("/api/v1/todos/session", "/api/v1/todos/session/items", "/api/v1/todos/session/items/a/start", "/api/v1/todos/session/items/a-b/proof", "/api/v1/todos/session/items/a/link", "/api/v1/todos/session/items/a/drop", "/api/v1/todos/session/stop", "/api/v1/todos/session/hook-error"))
            assertTrue(posts.any { it.matches(path) }, path)
        for (path in listOf("/api/v1/todos", "/api/v1/todos/session/items/A/start", "/api/v1/todos/session/items/a/delete", "/api/v1/waits/x/answer", "/api/v1/todos/session/../waits"))
            assertTrue(posts.none { it.matches(path) }, path)
    }
}
