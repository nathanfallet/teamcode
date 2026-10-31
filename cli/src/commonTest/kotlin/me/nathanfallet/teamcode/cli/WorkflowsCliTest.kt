package me.nathanfallet.teamcode.cli

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import me.nathanfallet.teamcode.api.Serialization
import me.nathanfallet.teamcode.api.models.*
import me.nathanfallet.teamcode.cli.workflows.*
import me.nathanfallet.teamcode.client.TeamCodeClient
import me.nathanfallet.teamcode.client.TeamCodeException
import kotlin.test.*

/** `teamcode workflows`: input checks, local definition checks, rendering, following a run and the calls made, against a fake server. */
class WorkflowsCliTest {
    private class Seen(val method: String, val path: String, val query: String, val body: String, val key: String?)

    private fun agent(id: String, to: String?) = WorkflowNodeView(id, "agent", "Step $id", instruction = "Do it {{inputs.topic}}", deliverable = WorkflowDeliverableView("text"),
        transitions = listOf(WorkflowTransitionView("delivered", to)))
    private val review = WorkflowNodeView("review", "human", "Review", instruction = "Look", actions = listOf(WaitActionView("ok", "OK"), WaitActionView("redo", "Redo")),
        transitions = listOf(WorkflowTransitionView("ok", null), WorkflowTransitionView("redo", "draft")))
    private val draftNode = agent("draft", "review")
    private val inputs = listOf(WorkflowInputView("topic", "Topic", required = true, maxLength = 10), WorkflowInputView("tone", "Tone", required = false), WorkflowInputView("lang", "Lang", required = true, default = "en"))
    private val workflow = WorkflowView("w1", "Write", null, 3, "draft", inputs = inputs, nodes = listOf(draftNode, review), createdAt = 0, updatedAt = 0)
    private val draft = WorkflowDraft("Write", null, "draft", inputs = inputs, nodes = listOf(draftNode, review))
    private fun run(status: String, vararg visits: WorkflowVisitView, failure: String? = null, output: String? = null) =
        WorkflowRunView("r1", "w1", "Write", 3, status, "m1", currentNodeId = visits.lastOrNull()?.nodeId, failure = failure, output = output, visits = visits.toList(), createdAt = 0, updatedAt = 0)
    private fun visit(seq: Int, node: String, status: String, kind: String = "agent", outcome: String? = null, waitId: String? = null) =
        WorkflowVisitView(seq, node, kind, status, outcome, waitId = waitId, startedAt = 0)

    private fun client(features: List<String> = listOf(Workflows.CAPABILITY), seen: MutableList<Seen> = mutableListOf(), answer: (String) -> Pair<HttpStatusCode, String>) =
        TeamCodeClient("https://team.example", { "synthetic-token" }) { configure ->
            HttpClient(MockEngine { request ->
                val path = request.url.encodedPath
                seen += Seen(request.method.value, path, request.url.encodedQuery, request.body.toByteArray().decodeToString(), request.headers["Idempotency-Key"])
                val (status, body) = if (path.endsWith("/capabilities")) HttpStatusCode.OK to Serialization.json.encodeToString(Capabilities(serverVersion = "1.0.0", mode = "team", features = features)) else answer(path)
                respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))
            }) { configure(this) }
        }
    private fun message(status: Int, code: String) = describeFailure(TeamCodeException(status, "TeamCode returned HTTP $status.", code), "https://team.example").message

    @Test fun `the group lists every subcommand`() {
        val group = Cli().registeredSubcommands().single { it.commandName == "workflows" }
        assertEquals(setOf("list", "show", "create", "update", "delete", "settings", "run", "runs", "run-show", "cancel"), group.registeredSubcommands().map { it.commandName }.toSet())
    }

    @Test fun `a server without workflows is told so`() = runTest {
        val error = assertFailsWith<CliUserException> { client(features = emptyList()) { error("no call") }.use { requireWorkflows(it) } }
        assertContains(error.message.orEmpty(), Workflows.CAPABILITY); assertContains(error.message.orEmpty(), "too old")
        client { error("no call") }.use { requireWorkflows(it) }
    }

    @Test fun `inputs are checked against the definition and named`() {
        assertEquals(mapOf("topic" to "cats"), resolveInputs(workflow, listOf("topic" to "cats")))
        assertEquals(mapOf("topic" to "a=b", "tone" to ""), resolveInputs(workflow, listOf("topic" to "a=b", "tone" to "")))
        assertContains(assertFailsWith<CliUserException> { resolveInputs(workflow, listOf("topic" to "x", "mood" to "y")) }.message.orEmpty(), "\"mood\"")
        assertContains(assertFailsWith<CliUserException> { resolveInputs(workflow, listOf("tone" to "x")) }.message.orEmpty(), "--input topic=")
        assertContains(assertFailsWith<CliUserException> { resolveInputs(workflow, listOf("topic" to "x", "topic" to "y")) }.message.orEmpty(), "twice")
        assertContains(assertFailsWith<CliUserException> { resolveInputs(workflow, listOf("topic" to "x".repeat(11))) }.message.orEmpty(), "topic")
        assertContains(assertFailsWith<CliUserException> { resolveInputs(workflow.copy(inputs = emptyList()), listOf("a" to "b")) }.message.orEmpty(), "no inputs")
    }

    @Test fun `a definition is read as the api takes it and from show output`() {
        val text = Serialization.json.encodeToString(draft)
        assertEquals(draft, parseDefinition(text))
        assertEquals(draft, parseDefinition(Serialization.json.encodeToString(workflow)))
        assertEquals(draft, parseDefinition("{\"workflow\":$text}"))
        assertContains(assertFailsWith<CliUserException> { parseDefinition("{oops").also { } }.message.orEmpty(), "not valid JSON")
        assertContains(assertFailsWith<CliUserException> { parseDefinition("[1]") }.message.orEmpty(), "JSON object")
        assertContains(assertFailsWith<CliUserException> { parseDefinition("{\"name\":\"x\"}") }.message.orEmpty(), "not a workflow")
    }

    @Test fun `a valid definition has no local problem`() = assertEquals(emptyList(), checkDefinition(draft))

    @Test fun `problems name the node`() {
        val broken = draft.copy(start = "nope", nodes = listOf(
            draftNode.copy(transitions = listOf(WorkflowTransitionView("delivered", "ghost")), instruction = "Use {{inputs.nothing}} and {{nodes.draft.output}}", deliverable = null),
            review.copy(transitions = listOf(WorkflowTransitionView("ok", null), WorkflowTransitionView("maybe", null))),
            agent("island", null)))
        val problems = checkDefinition(broken).joinToString("\n")
        listOf("The start node \"nope\" does not exist", "Node \"draft\" goes on \"delivered\" to \"ghost\"", "Node \"draft\" uses {{inputs.nothing}}", "Node \"draft\" reads its own output",
            "Node \"draft\" is an agent step and needs a deliverable", "Node \"review\" has no transition for \"redo\"", "Node \"review\" has a transition for \"maybe\"")
            .forEach { assertContains(problems, it) }
        assertContains(checkDefinition(draft.copy(nodes = draft.nodes + agent("island", null))).joinToString("\n"), "Node \"island\" cannot be reached")
    }

    @Test fun `a workflow that never ends or starts with a person is refused`() {
        assertContains(checkDefinition(draft.copy(nodes = listOf(draftNode.copy(transitions = listOf(WorkflowTransitionView("delivered", "draft")))), start = "draft")).joinToString(), "No node ends")
        assertContains(checkDefinition(draft.copy(start = "review")).joinToString(), "human step")
        assertFailsWith<CliUserException> { requireValidDefinition(draft.copy(nodes = emptyList())) }
    }

    @Test fun `run is sent with the checked inputs and the profile and a fresh key each time`() = runTest {
        val seen = mutableListOf<Seen>()
        val started = run("running")
        client(seen = seen) { HttpStatusCode.Created to Serialization.json.encodeToString(started) }.use { c ->
            c.startWorkflowRun("w1", StartWorkflowRunRequest(mapOf("topic" to "cats"), "p1")); c.startWorkflowRun("w1", StartWorkflowRunRequest())
        }
        val posts = seen.filter { it.method == "POST" }
        assertEquals("/api/v1/workflows/w1/runs", posts[0].path); assertContains(posts[0].body, "\"topic\":\"cats\""); assertContains(posts[0].body, "\"profileId\":\"p1\"")
        assertTrue(posts[0].key.orEmpty().length >= 16); assertNotEquals(posts[0].key, posts[1].key)
    }

    @Test fun `runs ask for all and the filters and follow the cursor to the limit`() = runTest {
        val seen = mutableListOf<Seen>()
        var page = 0
        val (runs, more) = client(seen = seen) {
            page++
            HttpStatusCode.OK to Serialization.json.encodeToString(WorkflowRunPageView(listOf(run("running").copy(id = "a$page"), run("running").copy(id = "b$page")), "c$page"))
        }.use { listRuns(it, "w1", "failed", "start", 3, true) }
        assertEquals(listOf("a1", "b1", "a2"), runs.map { it.id }); assertEquals("c2", more)
        val first = seen.filter { it.path.endsWith("/runs") }.first().query
        listOf("workflow=w1", "status=failed", "before=start", "all=true", "limit=3").forEach { assertContains(first, it) }
    }

    @Test fun `a list without all does not send it`() = runTest {
        val seen = mutableListOf<Seen>()
        client(seen = seen) { HttpStatusCode.OK to Serialization.json.encodeToString(WorkflowRunPageView(emptyList())) }.use { listRuns(it, null, null, null, 10, false) }
        assertFalse("all=" in seen.last().query)
    }

    @Test fun `following prints each change once and ends with the run`() = runTest {
        val states = ArrayDeque(listOf(
            run("running", visit(1, "draft", "active")),
            run("running", visit(1, "draft", "active")),
            run("waiting", visit(1, "draft", "done", outcome = "delivered"), visit(2, "review", "active", "human", waitId = "wt1")),
            run("succeeded", visit(1, "draft", "done", outcome = "delivered"), visit(2, "review", "done", "human", outcome = "ok"), output = "fin")))
        val lines = mutableListOf<String>(); val pauses = mutableListOf<Long>()
        val followed = client { HttpStatusCode.OK to Serialization.json.encodeToString(states.removeFirst()) }.use { follow(it, "r1", 3000, 600_000, emit = lines::add, pause = { ms -> pauses += ms }) }
        assertEquals("succeeded", followed.run.status); assertFalse(followed.timedOut); assertEquals(0, exitCode(followed.run.status))
        assertEquals(listOf("step 1: draft (agent) started", "step 1: draft done (delivered)", "step 2: review (human) started", "step 2: review waits for a person: teamcode waits show wt1", "step 2: review done (ok)"), lines)
        assertEquals(List(3) { 3000L }, pauses)
    }

    @Test fun `following stops at the timeout without busy looping`() = runTest {
        val pauses = mutableListOf<Long>()
        val followed = client { HttpStatusCode.OK to Serialization.json.encodeToString(run("running", visit(1, "draft", "active"))) }.use { follow(it, "r1", 2000, 7000, emit = {}, pause = { ms -> pauses += ms }) }
        assertTrue(followed.timedOut); assertEquals(3, exitCode(followed.run.status)); assertTrue(pauses.all { it == 2000L }); assertEquals(4, pauses.size)
    }

    @Test fun `exit codes follow the final status`() {
        assertEquals(listOf(0, 1, 2), listOf("succeeded", "failed", "cancelled").map(::exitCode))
    }

    @Test fun `a poll that fails for a passing reason is tried again and a refusal is not`() = runTest {
        var calls = 0
        val followed = client { calls++; if (calls == 1) HttpStatusCode.BadGateway to "{}" else HttpStatusCode.OK to Serialization.json.encodeToString(run("failed", failure = "session_failed")) }
            .use { follow(it, "r1", 2000, 60_000, emit = {}, pause = {}) }
        assertEquals("failed", followed.run.status)
        assertFailsWith<TeamCodeException> { client { HttpStatusCode.NotFound to "{}" }.use { follow(it, "r1", 2000, 60_000, emit = {}, pause = {}) } }
    }

    @Test fun `cancelling the follow says the run is left running`() = runTest {
        var told = false
        assertFailsWith<CancellationException> {
            client { HttpStatusCode.OK to Serialization.json.encodeToString(run("running")) }.use { follow(it, "r1", 2000, 60_000, emit = {}, onCancel = { told = true }, pause = { throw CancellationException("ctrl-c") }) }
        }
        assertTrue(told)
    }

    @Test fun `show lists inputs and nodes and where outcomes lead`() {
        val text = workflow.details()
        listOf("Write  [w1]  v3", "topic\tTopic (required, max 10)", "draft\tagent\tStep draft  (start)", "delivers: text", "on delivered -> review", "on ok -> end", "on redo -> draft", "action redo\tRedo", "asks: the starter")
            .forEach { assertContains(text, it) }
    }

    @Test fun `run-show says what the run waits for and why it failed`() {
        val waiting = run("waiting", visit(1, "draft", "done", outcome = "delivered"), visit(2, "review", "active", "human", waitId = "wt1")).details(60_000)
        assertContains(waiting, "Current step: review"); assertContains(waiting, "teamcode waits show wt1"); assertContains(waiting, "wait wt1")
        val failed = run("failed", visit(1, "draft", "failed"), failure = "max_visits:review").details(0)
        assertContains(failed, "Failure: the loop came back to step \"review\""); assertFalse("Current step" in failed)
        assertContains(failureWords("ended_without_delivery"), "without delivering"); assertContains(failureWords("strange"), "(strange)")
    }

    @Test fun `rows are tab separated and the settings read`() {
        assertEquals("w1\tWrite\tv3\t2 nodes\t3 inputs\t2d", workflow.line(2 * 86_400_000L))
        assertContains(run("failed", failure = "session_failed").line(0), "failed: the agent's session failed")
        assertContains(WorkflowSettingsView(WorkflowEditorsView("members", listOf("m1")), true).render(), "m1")
        assertContains(WorkflowSettingsView(WorkflowEditorsView("admins")).render(), "cannot edit")
    }

    @Test fun `server refusals read as sentences`() {
        assertContains(message(409, "workflow_conflict"), "--expected-version")
        assertContains(message(409, "workflow_in_use"), "calls this one")
        assertContains(message(403, "workflow_editors_only"), "settings")
        assertContains(message(400, "profile_required"), "--profile")
        assertContains(message(409, "run_limit"), "unfinished runs")
        assertContains(message(400, "invalid_workflow"), "node")
    }
}
