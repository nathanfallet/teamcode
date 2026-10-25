package me.nathanfallet.teamcode.cli

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import me.nathanfallet.teamcode.api.models.*
import me.nathanfallet.teamcode.cli.cloud.RunnerCalls
import me.nathanfallet.teamcode.cli.cloud.RunnerInference
import me.nathanfallet.teamcode.cli.cloud.RunnerLoop
import me.nathanfallet.teamcode.cli.cloud.dollars
import me.nathanfallet.teamcode.cli.cloud.printed
import me.nathanfallet.teamcode.cli.cloud.shown
import me.nathanfallet.teamcode.cli.cloud.tailStart
import me.nathanfallet.teamcode.cli.cloud.render
import me.nathanfallet.teamcode.cli.cloud.agent.*
import me.nathanfallet.teamcode.cli.platform.AgentProcess
import me.nathanfallet.teamcode.cli.platform.hostOs
import me.nathanfallet.teamcode.cli.platform.pathStatus
import okio.ByteString.Companion.toByteString
import okio.FileSystem
import kotlin.time.Clock
import kotlin.uuid.Uuid
import me.nathanfallet.teamcode.client.TeamCodeException
import kotlin.test.*

class CloudAgentTest {
    private val claude = AgentSettings("claude", "2.1", "opus", "high")
    private val codex = AgentSettings("codex", "0.163", "gpt-5", "medium")
    private fun obj(line: String) = Json.parseToJsonElement(line).jsonObject
    private fun JsonObject.str(name: String) = (this[name] as JsonPrimitive).content

    @Test fun claudeCountsTheLiveBackgroundTasksItReports() {
        // `background_tasks_changed` carries the full live set; ambient tasks are Claude Code's own, not the session's work
        // (https://code.claude.com/docs/en/agent-sdk/typescript#sdkbackgroundtaskschangedmessage).
        val adapter = ClaudeStreamJsonAdapter(claude)
        assertEquals(0, adapter.backgroundTasks, "Nothing is emitted at startup: an agent process starts with none")
        fun changed(tasks: String) = adapter.parse("""{"type":"system","subtype":"background_tasks_changed","tasks":[$tasks],"uuid":"u","session_id":"c1"}""")
        val output = changed("""{"task_id":"a1","task_type":"local_agent","subagent_type":"general-purpose","description":"Review"},""" +
            """{"task_id":"b1","task_type":"local_bash","description":"npm test"},{"task_id":"w1","task_type":"local_bash","description":"watch","ambient":true}""")
        assertTrue(output.events.isEmpty() && output.replies.isEmpty(), "Nothing reaches the transcript")
        assertEquals(2, adapter.backgroundTasks)
        assertFalse(adapter.turnActive, "Background work is not a turn")
        changed("""{"task_id":"w1","task_type":"local_bash","description":"watch","ambient":true}""")
        assertEquals(0, adapter.backgroundTasks)
        adapter.parse("""{"type":"system","subtype":"background_tasks_changed","uuid":"u","session_id":"c1"}""")
        assertEquals(0, adapter.backgroundTasks, "A malformed list counts as none")
        assertEquals(0, CodexAppServerAdapter(codex, "1").backgroundTasks)
    }

    @Test fun claudeStreamJsonIsBypassResumableAndDropsThinking() {
        val adapter = ClaudeStreamJsonAdapter(claude)
        assertTrue(adapter.takeEvents().isEmpty())
        assertEquals(listOf("-p", "--input-format", "stream-json", "--output-format", "stream-json", "--verbose", "--dangerously-skip-permissions",
            "--model", "opus", "--effort", "high", "--resume", "c1"), adapter.command("c1").arguments)
        assertTrue(adapter.interrupt().isEmpty(), "No turn, nothing to interrupt")
        val message = obj(adapter.message("Fix it").single())
        assertEquals("user", message.str("type"))
        assertTrue(adapter.turnActive)
        assertEquals("started", adapter.takeEvents().single().data.str("phase"), "The first message starts a turn")
        adapter.message("More"); assertTrue(adapter.takeEvents().isEmpty(), "A message during a turn starts none")
        assertEquals("interrupt", obj(adapter.interrupt().single())["request"]!!.jsonObject.str("subtype"))

        val init = adapter.parse("""{"type":"system","subtype":"init","session_id":"c1","model":"opus"}""").events.single()
        assertEquals("resumable", init.data.str("conversation")); assertEquals("c1", adapter.conversationId)
        assertTrue(adapter.parse("""{"type":"system","subtype":"init","session_id":"c1"}""").events.isEmpty(), "Reported once")
        val assistant = adapter.parse("""{"type":"assistant","message":{"content":[{"type":"thinking","thinking":"secret"},{"type":"text","text":"Done"},{"type":"tool_use","id":"t1","name":"Bash","input":{"command":"ls"}}]}}""").events
        assertEquals(listOf(CloudEventTypes.AGENT_MESSAGE, CloudEventTypes.TOOL_CALL), assistant.map { it.type })
        assertFalse("secret" in assistant.toString())
        val result = adapter.parse("""{"type":"user","message":{"content":[{"type":"tool_result","tool_use_id":"t1","content":[{"type":"text","text":"a.txt"}],"is_error":false}]}}""").events.single()
        assertEquals("a.txt", result.data.str("output"))
        assertEquals("completed", adapter.parse("""{"type":"result","subtype":"success","is_error":false,"duration_ms":5}""").events.single().data.str("phase"))
        assertFalse(adapter.turnActive, "One result answers every message Claude Code read, the one written during the turn included")
        // A message Claude Code reads only after that result runs as a turn of its own, seen from its first output.
        val next = adapter.parse("""{"type":"assistant","message":{"content":[{"type":"text","text":"And more"}]}}""").events
        assertEquals(listOf(CloudEventTypes.TURN, CloudEventTypes.AGENT_MESSAGE), next.map { it.type })
        assertEquals("started", next.first().data.str("phase"))
        assertTrue(adapter.turnActive)
        adapter.parse("""{"type":"result","subtype":"success","is_error":false}""")
        assertFalse(adapter.turnActive)
        assertTrue(adapter.parse("not json").events.isEmpty(), "Stray output is ignored")
    }

    @Test fun claudeRelaysPermissionRequestsAndTheirAnswers() {
        assertFalse("--permission-prompt-tool" in ClaudeStreamJsonAdapter(claude).command(null).arguments, "Only a server that takes the requests gets them")
        val adapter = ClaudeStreamJsonAdapter(claude.copy(permissionRelay = true))
        val arguments = adapter.command(null).arguments
        assertEquals(listOf("--dangerously-skip-permissions", "--permission-prompt-tool", "stdio"), arguments.subList(arguments.indexOf("--dangerously-skip-permissions"), arguments.indexOf("--dangerously-skip-permissions") + 3))
        adapter.message("Touch it, then ask me")

        val ask = adapter.parse("""{"type":"control_request","request_id":"r1","request":{"subtype":"can_use_tool","tool_name":"Bash","input":{"command":"touch c.txt"},"description":"Create c.txt","decision_reason_type":"rule","tool_use_id":"t1","permission_suggestions":[{"type":"addRules"}]}}""").events.single()
        assertEquals(CloudEventTypes.PERMISSION_REQUEST, ask.type)
        assertEquals(listOf("r1", "Bash", "t1", "Create c.txt", "rule"), listOf("requestId", "toolName", "toolUseId", "description", "reason").map { ask.data.str(it) })
        assertEquals("touch c.txt", ask.data["input"]!!.jsonObject.str("command")); assertEquals(false, ask.data["question"]!!.jsonPrimitive.boolean)
        assertNull(ask.data["permission_suggestions"], "Only the request itself is uploaded")
        val denied = obj(adapter.answer("r1", false, "Use echo instead.", emptyList()).lines.single())
        assertEquals("control_response", denied.str("type"))
        val deniedResponse = denied["response"]!!.jsonObject
        assertEquals("r1", deniedResponse.str("request_id"))
        assertEquals(buildJsonObject { put("behavior", "deny"); put("message", "Use echo instead.") }, deniedResponse["response"])
        assertEquals(AnswerOutcome(emptyList(), AnswerOutcome.UNKNOWN), adapter.answer("r1", true, null, emptyList()), "A request is answered once")

        val question = adapter.parse("""{"type":"control_request","request_id":"r2","request":{"subtype":"can_use_tool","tool_name":"AskUserQuestion","input":{"questions":[{"question":"Red or blue?","options":[{"label":"Red"},{"label":"Blue"}]},{"question":"Which sizes?","multiSelect":true,"options":[{"label":"S"},{"label":"M"},{"label":"L"}]}]},"tool_use_id":"t2","requires_user_interaction":true}}""").events.single()
        assertEquals(true, question.data["question"]!!.jsonPrimitive.boolean)
        assertTrue(CloudEventView(1, question.type, 0, 1, question.data).render().endsWith("\n    ? 0: \"Red or blue?\" (0: \"Red\" / 1: \"Blue\")\n    ? 1: \"Which sizes?\" (0: \"S\" / 1: \"M\" / 2: \"L\") several"),
            "Each question and option is printed with the index an answer takes")
        val large = adapter.parse("""{"type":"control_request","request_id":"r9","request":{"subtype":"can_use_tool","tool_name":"Write","input":{"content":"${"x".repeat(30_000)}"}}}""").events.single()
        assertEquals("r9", large.data.str("requestId")); assertNull(large.data["input"]); assertEquals(true, large.data["inputTruncated"]!!.jsonPrimitive.boolean)
        assertEquals(2_000, large.data.str("inputPreview").length, "Whoever decides still sees the start of the input")
        val wide = adapter.parse("""{"type":"control_request","request_id":"r10","request":{"subtype":"can_use_tool","tool_name":"Write","input":{"content":"${"é".repeat(12_000)}"}}}""").events.single()
        assertNull(wide.data["input"], "The limit counts UTF-8 bytes, as the server does"); adapter.answer("r10", false, null, emptyList())
        val spoof = adapter.parse("""{"type":"control_request","request_id":"r12","request":{"subtype":"can_use_tool","tool_name":"AskUserQuestion","description":"${"d".repeat(5_000)}","input":{"questions":[{"question":"Fine?\u001b[2K\rApprove\u202e deploy?","options":[{"label":"Yes\u001b]0;x\u0007\u200b"}]}]}}}""").events.single()
        assertEquals(2_000, spoof.data.str("description").length, "Every field of the event is bounded")
        val shown = CloudEventView(1, spoof.type, 0, 1, spoof.data).render()
        assertFalse(shown.any { it == '\u001b' || it == '\r' || it == '\u0007' || it == '\u202e' || it == '\u200b' }, "Escape sequences and invisible marks never reach the approver's terminal")
        assertFalse("\"questions\"" in shown, "A question is printed by its indexed lines, not as raw JSON")
        val nested = adapter.parse("""{"type":"control_request","request_id":"r13","request":{"subtype":"can_use_tool","tool_name":"mcp__db__write","input":{"rows":[${(1..250).joinToString(",")}]}}}""").events.single()
        assertNull(nested.data["input"], "An input the server would cut (more than 200 entries) is left out with a preview rather than shown cut")
        assertEquals("requestId", nested.data.keys.first(), "The server finds a request by the head of its stored event, which starts with its id")
        adapter.answer("r13", false, null, emptyList())
        val smuggled = adapter.parse("""{"type":"control_request","request_id":"r15","request":{"subtype":"can_use_tool","tool_name":"AskUserQuestion","input":{"questions":[{"question":"Go? 🙂\udb40\udc41","options":[{"label":"Safe) (1: \"Prod"}]}]}}}""").events.single()
        val smuggledLine = CloudEventView(1, smuggled.type, 0, 1, smuggled.data).render()
        assertFalse("\udb40\udc41" in smuggledLine, "Tag characters outside the basic plane are hidden too")
        assertTrue("Go? 🙂" in smuggledLine, "but other characters outside it (emoji) are kept")
        assertTrue(smuggledLine.endsWith("(0: \"Safe) (1: \\\"Prod\")"), "A label is quoted, so it cannot pass for another option")
        adapter.answer("r15", false, null, emptyList())
        val forged = adapter.parse("""{"type":"control_request","request_id":"r16","request":{"subtype":"can_use_tool","tool_name":"Bash","input":{"command":"deploy","questions":[{"question":"Fine?","options":[{"label":"Yes"}]}]}}}""").events.single()
        assertFalse("\n    ?" in CloudEventView(1, forged.type, 0, 1, forged.data).render(), "Only a question request gets indexed question lines")
        assertEquals("00:00:00 #1 [permission p1 consumed] allow r16 by m1", CloudEventView(1, CloudEventTypes.INPUT, 0, 1, buildJsonObject {
            put("inputId", "p1"); put("kind", "permission"); put("status", "consumed"); put("requestId", "r16"); put("behavior", "allow"); put("authorId", "m1")
        }).render(), "An answer's notice says which request, how and who")
        adapter.answer("r16", false, null, emptyList())
        val oddId = adapter.parse("""{"type":"control_request","request_id":"has space","request":{"subtype":"can_use_tool","tool_name":"Bash","input":{}}}""")
        assertTrue(oddId.events.isEmpty(), "A request id no member could send back is not relayed")
        assertEquals("deny", obj(oddId.replies.single())["response"]!!.jsonObject["response"]!!.jsonObject.str("behavior"), "but denied at once")
        val reasoning = adapter.parse("""{"type":"control_request","request_id":"r14","request":{"subtype":"can_use_tool","tool_name":"mcp__notes__save","input":{"note":{"type":"thinking","text":"x"}}}}""").events.single()
        assertNull(reasoning.data["input"], "A nested object the server drops is left out too")
        adapter.answer("r14", false, null, emptyList())
        adapter.answer("r12", false, null, emptyList())
        val plan = adapter.parse("""{"type":"control_request","request_id":"r11","request":{"subtype":"can_use_tool","tool_name":"ExitPlanMode","input":{"plan":"x"},"requires_user_interaction":true}}""").events.single()
        assertEquals(false, plan.data["question"]!!.jsonPrimitive.boolean, "Only AskUserQuestion is a question; another interactive tool is a tool use to approve")
        adapter.answer("r11", false, null, emptyList())
        assertTrue("x".repeat(30_000) in adapter.answer("r9", true, null, emptyList()).lines.single(), "The agent still gets its full input back")
        for (wrong in listOf(listOf(CloudQuestionAnswer(0, listOf(2))), listOf(CloudQuestionAnswer(2, listOf(0))), listOf(CloudQuestionAnswer(0, listOf(0, 1)))))
            assertEquals(AnswerOutcome(emptyList(), AnswerOutcome.INVALID), adapter.answer("r2", true, null, wrong), "An index the request does not have: $wrong")
        val allowed = obj(adapter.answer("r2", true, null, listOf(CloudQuestionAnswer(0, listOf(1)), CloudQuestionAnswer(1, listOf(0, 2)))).lines.single())["response"]!!.jsonObject["response"]!!.jsonObject
        assertEquals("allow", allowed.str("behavior"))
        val updated = allowed["updatedInput"]!!.jsonObject
        assertEquals("Blue", updated["answers"]!!.jsonObject.str("Red or blue?"), "A refused answer left the request open; indexes become Claude Code's texts")
        assertEquals("S, L", updated["answers"]!!.jsonObject.str("Which sizes?")); assertNotNull(updated["questions"], "The original input is passed back")
        adapter.parse("""{"type":"control_request","request_id":"r6","request":{"subtype":"can_use_tool","tool_name":"AskUserQuestion","input":{"questions":[{"question":"Name?","options":[{"label":"A"}]}]},"requires_user_interaction":true}}""")
        assertTrue(adapter.permissionOpen("r6")); assertFalse(adapter.permissionOpen("r2"))
        val free = obj(adapter.answer("r6", true, null, listOf(CloudQuestionAnswer(0, text = "Ada"))).lines.single())["response"]!!.jsonObject["response"]!!.jsonObject
        assertEquals("Ada", free["updatedInput"]!!.jsonObject["answers"]!!.jsonObject.str("Name?"), "A free text is passed as is")
        adapter.parse("""{"type":"control_request","request_id":"r7","request":{"subtype":"can_use_tool","tool_name":"AskUserQuestion","input":{"questions":[{"question":"Same?","options":[{"label":"A"}]},{"question":"Same?","options":[{"label":"B"}]}]}}}""")
        assertEquals(AnswerOutcome.INVALID, adapter.answer("r7", true, null, listOf(CloudQuestionAnswer(0, listOf(0)), CloudQuestionAnswer(1, listOf(0)))).refusal,
            "Two questions with one text cannot both be answered in Claude Code's format")
        adapter.answer("r7", false, null, emptyList())

        adapter.parse("""{"type":"control_request","request_id":"r3","request":{"subtype":"can_use_tool","tool_name":"Bash","input":{}}}""")
        adapter.parse("""{"type":"control_cancel_request","request_id":"r3"}""")
        assertTrue(adapter.answer("r3", true, null, emptyList()).lines.isEmpty(), "A withdrawn request takes no answer")
        adapter.parse("""{"type":"control_request","request_id":"r4","request":{"subtype":"can_use_tool","tool_name":"Bash","input":{}}}""")
        adapter.parse("""{"type":"result","subtype":"success","is_error":false}""")
        assertTrue(adapter.answer("r4", true, null, emptyList()).lines.isEmpty(), "A request does not outlive its turn")
        val other = adapter.parse("""{"type":"control_request","request_id":"r5","request":{"subtype":"hook_callback"}}""")
        assertTrue(other.events.isEmpty(), "Other control requests are not permission requests")
        val refusal = obj(other.replies.single())["response"]!!.jsonObject
        assertEquals(listOf("error", "r5"), listOf(refusal.str("subtype"), refusal.str("request_id")), "but get a reply, so Claude Code never waits for one")
    }

    @Test fun hostDeliversPermissionAnswersOnlyToTheProcessThatAsked() = runTest {
        val channel = FakeChannel().apply { relay = true }
        val started = mutableListOf<Pair<AgentCommand, FakeProcess>>()
        val job = launch { host(channel, MemoryState(), started).run() }
        val answer = """{"requestId":"r1","behavior":"allow","answers":[{"question":0,"options":[1]}]}"""
        channel.inputs.send(listOf(RunnerInputView("p-0", "permission", answer, 1)))
        until { channel.acks.isNotEmpty() }
        assertEquals(RunnerInputAckView("p-0", CloudInputStatuses.REJECTED, "unknown_permission_request"), channel.acks.single())
        assertTrue(started.isEmpty(), "An answer never starts the agent")

        channel.inputs.send(listOf(RunnerInputView("m-1", "message", "Ask me", 1)))
        until { started.isNotEmpty() }
        val (command, process) = started.single()
        assertTrue("--permission-prompt-tool" in command.arguments)
        process.output.send("""{"type":"control_request","request_id":"r1","request":{"subtype":"can_use_tool","tool_name":"AskUserQuestion","input":{"questions":[{"question":"Red or blue?","options":[{"label":"Red"},{"label":"Blue"}]}]},"requires_user_interaction":true}}""")
        until { channel.uploaded.any { it.type == CloudEventTypes.PERMISSION_REQUEST } }
        channel.inputs.send(listOf(RunnerInputView("p-4", "permission", """{"requestId":"r1","behavior":"allow","answers":[{"question":0,"options":[7]}]}""", 1)))
        until { channel.acks.size == 3 }
        assertEquals(RunnerInputAckView("p-4", CloudInputStatuses.REJECTED, "invalid_answer"), channel.acks.last(), "An index the request does not have")
        process.failWrites = true
        channel.inputs.send(listOf(RunnerInputView("p-5", "permission", answer, 1)))
        until { channel.acks.size == 4 }
        assertEquals(RunnerInputAckView("p-5", CloudInputStatuses.REJECTED, "agent_unavailable"), channel.acks.last(), "An answer that could not be written")
        process.failWrites = false
        channel.inputs.send(listOf(RunnerInputView("p-1", "permission", answer, 1), RunnerInputView("p-2", "permission", "not json", 1)))
        until { channel.acks.size == 6 }
        assertEquals(listOf(CloudInputStatuses.CONSUMED, CloudInputStatuses.REJECTED), channel.acks.takeLast(2).map { it.status })
        assertEquals("invalid_input", channel.acks.last().reason)
        val response = obj(process.written.last())
        assertEquals("r1", response["response"]!!.jsonObject.str("request_id"))
        assertEquals("Blue", response["response"]!!.jsonObject["response"]!!.jsonObject["updatedInput"]!!.jsonObject["answers"]!!.jsonObject.str("Red or blue?"))
        channel.inputs.send(listOf(RunnerInputView("p-3", "permission", answer, 1)))
        until { channel.acks.size == 7 }
        assertEquals("unknown_permission_request", channel.acks.last().reason, "Answered once (after the unwritten answer left it open)")
        job.cancel()
    }

    @Test fun hostDeniesOpenRequestsWhenTheSessionPauses() = runTest {
        val channel = FakeChannel().apply { relay = true }
        val started = mutableListOf<Pair<AgentCommand, FakeProcess>>()
        val host = host(channel, MemoryState(), started)
        val job = launch { host.run() }
        channel.inputs.send(listOf(RunnerInputView("m-1", "message", "Deploy it", 1)))
        until { started.isNotEmpty() }
        val process = started.single().second
        process.output.send("""{"type":"control_request","request_id":"r1","request":{"subtype":"can_use_tool","tool_name":"Bash","input":{"command":"deploy"}}}""")
        until { channel.uploaded.any { it.type == CloudEventTypes.PERMISSION_REQUEST } }
        val draining = launch { host.drain(null) }
        until { process.written.any { "\"r1\"" in it } }
        val denial = obj(process.written.first { "\"r1\"" in it })["response"]!!.jsonObject["response"]!!.jsonObject
        assertEquals("The session is pausing: the request was denied.", denial.str("message"), "No answer reaches a pausing session, so the turn is let go at once")
        process.output.send("""{"type":"control_request","request_id":"r2","request":{"subtype":"can_use_tool","tool_name":"Bash","input":{"command":"deploy again"}}}""")
        until { process.written.any { "\"r2\"" in it } }
        assertTrue("pausing" in process.written.first { "\"r2\"" in it }, "and so is a request raised while it drains")
        draining.cancel(); job.cancel()
    }

    @Test fun hostDeniesARequestTheServerRefusedToRecord() = runTest {
        val channel = FakeChannel().apply { relay = true }
        val started = mutableListOf<Pair<AgentCommand, FakeProcess>>()
        val job = launch { host(channel, MemoryState(), started).run() }
        channel.inputs.send(listOf(RunnerInputView("m-1", "message", "Deploy it", 1)))
        until { started.isNotEmpty() && channel.acks.isNotEmpty() }
        delay(5_000)
        val process = started.single().second
        channel.poisoned += channel.uploaded.size + 1L
        process.output.send("""{"type":"control_request","request_id":"r1","request":{"subtype":"can_use_tool","tool_name":"Bash","input":{"command":"deploy"}}}""")
        until { process.written.any { "\"r1\"" in it } }
        val denial = obj(process.written.last())["response"]!!.jsonObject
        assertEquals("deny", denial["response"]!!.jsonObject.str("behavior"), "A request no member can see is denied, not left waiting")
        assertEquals("transcript_event_rejected", channel.uploaded.last().data["code"]!!.jsonPrimitive.content)
        job.cancel()
    }

    @Test fun hostDeniesARequestNobodyAnsweredBeforeTheTeamDeadline() = runTest {
        val channel = FakeChannel().apply { relay = true; deadline = 2 }
        val started = mutableListOf<Pair<AgentCommand, FakeProcess>>()
        val job = launch { host(channel, MemoryState(), started).run() }
        channel.inputs.send(listOf(RunnerInputView("m-1", "message", "Deploy it", 1)))
        until { started.isNotEmpty() }
        val process = started.single().second
        fun request(id: String) = """{"type":"control_request","request_id":"$id","request":{"subtype":"can_use_tool","tool_name":"Bash","input":{"command":"deploy"}}}"""
        process.output.send(request("r1"))
        process.output.send(request("r2"))
        until { channel.uploaded.count { it.type == CloudEventTypes.PERMISSION_REQUEST } == 2 }
        channel.inputs.send(listOf(RunnerInputView("p-1", "permission", """{"requestId":"r2","behavior":"allow"}""", 1)))
        until { channel.acks.size == 2 }
        val written = process.written.size

        delay(2 * 60_000L)
        until { channel.uploaded.any { it.type == CloudEventTypes.PERMISSION_EXPIRED } }
        val expired = channel.uploaded.single { it.type == CloudEventTypes.PERMISSION_EXPIRED }.data
        assertEquals("r1", expired.str("requestId")); assertEquals(2, expired["deadlineMinutes"]!!.jsonPrimitive.int)
        val denial = obj(process.written.last())["response"]!!.jsonObject
        assertEquals("r1", denial.str("request_id"))
        assertEquals(buildJsonObject { put("behavior", "deny"); put("message", "No answer within 2 minutes: the request was denied.") }, denial["response"], "The agent reads why")
        assertEquals(written + 1, process.written.size, "The request answered in time is not denied again")
        assertEquals("00:00:00 #1 [expired r1] No answer within 2 minutes: the request was denied.", CloudEventView(1, CloudEventTypes.PERMISSION_EXPIRED, 0, 1, expired).render())
        channel.inputs.send(listOf(RunnerInputView("p-2", "permission", """{"requestId":"r1","behavior":"allow"}""", 1)))
        until { channel.acks.size == 3 }
        assertEquals("unknown_permission_request", channel.acks.last().reason, "An expired request takes no answer")
        job.cancel()
    }

    @Test fun claudeTurnsCarryTheirCostAndTokens() {
        val adapter = ClaudeStreamJsonAdapter(claude)
        adapter.startup(null, "/workspace")
        fun turn(line: String) = adapter.parse(line).events.single().also { assertEquals(CloudEventTypes.TURN, it.type) }.data
        fun JsonObject.long(name: String) = (this[name] as? JsonPrimitive)?.longOrNull
        adapter.message("One")
        val first = turn("""{"type":"result","subtype":"success","is_error":false,"total_cost_usd":0.0144917,"usage":{"input_tokens":2,"cache_creation_input_tokens":72450,"cache_read_input_tokens":0,"output_tokens":3,"server_tool_use":{"web_search_requests":0}}}""")
        assertEquals(14_492, first.long("costMicros")); assertEquals(14_492, first.long("totalCostMicros"))
        assertEquals(2, first.long("inputTokens")); assertEquals(3, first.long("outputTokens"))
        assertEquals(72_450, first.long("cacheCreationInputTokens")); assertEquals(0, first.long("cacheReadInputTokens"))
        assertNull(first["usage"], "Only the four token counts are kept")
        adapter.message("Two")
        val second = turn("""{"type":"result","subtype":"success","is_error":false,"total_cost_usd":0.01774523,"usage":{"input_tokens":4,"cache_creation_input_tokens":13300,"cache_read_input_tokens":59163,"output_tokens":3}}""")
        assertEquals(3_254, second.long("costMicros"), "The running total becomes the turn's own cost")
        assertEquals("00:00:00 #1 -- turn completed, \$0.003254 --", CloudEventView(1, CloudEventTypes.TURN, 0, 1, second).render())
        assertEquals(17_745, second.long("totalCostMicros")); assertEquals(59_163, second.long("cacheReadInputTokens"))
        adapter.message("Three")
        val interrupted = turn("""{"type":"result","subtype":"error_during_execution","is_error":true,"total_cost_usd":0.01800523,"usage":{"input_tokens":1,"output_tokens":0}}""")
        assertEquals("interrupted", interrupted.str("phase")); assertEquals(260, interrupted.long("costMicros")); assertEquals(1, interrupted.long("inputTokens"))
        adapter.message("Four")
        val unpriced = turn("""{"type":"result","subtype":"error_during_execution","is_error":true}""")
        assertNull(unpriced["costMicros"]); assertNull(unpriced["totalCostMicros"]); assertNull(unpriced["inputTokens"])
        assertEquals("00:00:00 #1 -- turn interrupted --", CloudEventView(1, CloudEventTypes.TURN, 0, 1, unpriced).render())
        adapter.message("Five")
        assertEquals(1_000, turn("""{"type":"result","subtype":"success","is_error":false,"total_cost_usd":0.01900523}""").long("costMicros"),
            "A turn without a reported cost is counted in the next priced one")
        adapter.message("Six")
        val restarted = turn("""{"type":"result","subtype":"success","is_error":false,"total_cost_usd":0.0001}""")
        assertNull(restarted["costMicros"], "A total that went down gives no turn cost rather than a wrong one"); assertEquals(100, restarted.long("totalCostMicros"))
        assertNull(ClaudeStreamJsonAdapter(claude).parse("""{"type":"result","subtype":"success","total_cost_usd":0.5}""").events.single().data["costMicros"],
            "Before startup the baseline is unknown")
        assertNull(dollars(-5)); assertEquals("\$12.000001", dollars(12_000_001))

        val resumed = ClaudeStreamJsonAdapter(claude)
        resumed.startup("c1", "/workspace")
        resumed.message("Four")
        val afterResume = resumed.parse("""{"type":"result","subtype":"success","is_error":false,"total_cost_usd":0.01847456,"usage":{"input_tokens":4,"output_tokens":3}}""").events.single().data
        assertNull(afterResume["costMicros"], "The previous process's total is unknown, so the first resumed turn has no cost of its own")
        assertEquals(18_475, afterResume.long("totalCostMicros"))
        resumed.message("Five")
        assertEquals(1_000, resumed.parse("""{"type":"result","subtype":"success","is_error":false,"total_cost_usd":0.01947456}""").events.single().data.long("costMicros"))
    }

    @Test fun claudeSubagentEventsKeepTheirParentToolCall() {
        val adapter = ClaudeStreamJsonAdapter(claude)
        adapter.message("Delegate it")
        val spawn = adapter.parse("""{"type":"assistant","parent_tool_use_id":null,"message":{"content":[{"type":"tool_use","id":"a1","name":"Agent","input":{"prompt":"List files"}}]}}""").events.single()
        assertNull(spawn.data["parentToolUseId"], "The main agent's events have no parent")
        val call = adapter.parse("""{"type":"assistant","parent_tool_use_id":"a1","message":{"content":[{"type":"tool_use","id":"t1","name":"Bash","input":{"command":"ls"}}]}}""").events.single()
        assertEquals(CloudEventTypes.TOOL_CALL, call.type); assertEquals("a1", call.data.str("parentToolUseId"))
        val result = adapter.parse("""{"type":"user","parent_tool_use_id":"a1","message":{"content":[{"type":"tool_result","tool_use_id":"t1","content":"a.txt"}]}}""").events.single()
        assertEquals(CloudEventTypes.TOOL_RESULT, result.type); assertEquals("a1", result.data.str("parentToolUseId"))
        val text = adapter.parse("""{"type":"assistant","parent_tool_use_id":"a1","message":{"content":[{"type":"text","text":"Found a.txt"}]}}""").events.single()
        assertEquals(CloudEventTypes.AGENT_MESSAGE, text.type); assertEquals("a1", text.data.str("parentToolUseId"))
        val done = adapter.parse("""{"type":"user","parent_tool_use_id":null,"message":{"content":[{"type":"tool_result","tool_use_id":"a1","content":"Found a.txt"}]}}""").events.single()
        assertNull(done.data["parentToolUseId"])

        fun rendered(event: AgentEvent) = CloudEventView(1, event.type, 0, 1, event.data).render()
        assertEquals("00:00:00 #1   ↳ [tool] Bash {\"command\":\"ls\"}", rendered(call), "teamcode cloud logs indents a subagent's steps")
        assertEquals("00:00:00 #1 [result] Found a.txt", rendered(done))
    }

    /**
     * Lines shaped like Claude Code 2.1.309's `--include-partial-messages` output (captured locally, text replaced): a
     * text block streamed by `text_delta`, its `assistant` line before its `content_block_stop`, then a tool use whose
     * input streams as `input_json_delta`.
     */
    private fun stream(event: String, message: String = "msg_1", parent: String? = null) =
        """{"type":"stream_event","event":$event,"session_id":"c1","parent_tool_use_id":${parent?.let { "\"$it\"" } ?: "null"},"uuid":"u","api_message_id":"$message"}"""
    private fun textDelta(index: Int, text: String, message: String = "msg_1", parent: String? = null) =
        stream("""{"type":"content_block_delta","index":$index,"delta":{"type":"text_delta","text":${JsonPrimitive(text)}}}""", message, parent)
    private fun blockStart(index: Int, type: String, message: String = "msg_1", parent: String? = null) =
        stream("""{"type":"content_block_start","index":$index,"content_block":{"type":"$type","text":""}}""", message, parent)
    private fun blockStop(index: Int, message: String = "msg_1", parent: String? = null) = stream("""{"type":"content_block_stop","index":$index}""", message, parent)

    @Test fun claudeStreamsAgentTextLiveInCoalescedDeltas() {
        assertFalse("--include-partial-messages" in ClaudeStreamJsonAdapter(claude).command(null).arguments, "Only a server that takes deltas gets them")
        var clock = 0L
        val adapter = ClaudeStreamJsonAdapter(claude.copy(liveText = true), now = { clock })
        assertTrue("--include-partial-messages" in adapter.command(null).arguments)
        adapter.message("Tell me about the sea, then list files")
        adapter.takeEvents()
        assertTrue(adapter.parse(stream("""{"type":"message_start","message":{"id":"msg_1","type":"message","role":"assistant","content":[]}}""")).events.isEmpty())
        assertTrue(adapter.parse(blockStart(0, "text")).events.isEmpty())
        clock = 100
        assertTrue(adapter.parse(textDelta(0, "The sea ")).events.isEmpty(), "Held: less than 500 ms since the block started")
        clock = 600
        val first = adapter.parse(textDelta(0, "is wide. ")).events.single()
        assertEquals(CloudEventTypes.AGENT_MESSAGE_DELTA, first.type)
        assertEquals(buildJsonObject { put("messageId", "msg_1"); put("index", 0); put("text", "The sea is wide. ") }, first.data, "What was added since the last delta of this block")
        clock = 700
        assertTrue(adapter.parse(textDelta(0, "It is deep.")).events.isEmpty())
        // Claude Code writes the block's `assistant` line before its `content_block_stop`: the final text replaces the deltas.
        val final = adapter.parse("""{"type":"assistant","message":{"id":"msg_1","type":"message","role":"assistant","content":[{"type":"text","text":"The sea is wide. It is deep."}]},"parent_tool_use_id":null,"session_id":"c1"}""").events.single()
        assertEquals(CloudEventTypes.AGENT_MESSAGE, final.type)
        assertEquals(buildJsonObject { put("text", "The sea is wide. It is deep."); put("messageId", "msg_1"); put("index", 0) }, final.data)
        assertTrue(adapter.parse(blockStop(0)).events.isEmpty(), "The final message replaced what was held: no delta after it")

        // A tool use's streamed input and a thinking block never become deltas.
        assertTrue(adapter.parse(stream("""{"type":"content_block_start","index":1,"content_block":{"type":"tool_use","id":"t1","name":"Bash","input":{}}}""")).events.isEmpty())
        clock = 5_000
        assertTrue(adapter.parse(stream("""{"type":"content_block_delta","index":1,"delta":{"type":"input_json_delta","partial_json":"{\"command\":\"ls\"}"}}""")).events.isEmpty())
        assertEquals(CloudEventTypes.TOOL_CALL, adapter.parse("""{"type":"assistant","message":{"id":"msg_1","content":[{"type":"tool_use","id":"t1","name":"Bash","input":{"command":"ls"}}]}}""").events.single().type)
        assertTrue(adapter.parse(blockStop(1)).events.isEmpty())
        assertTrue(adapter.parse(blockStart(2, "thinking")).events.isEmpty())
        clock = 9_000
        assertTrue(adapter.parse(stream("""{"type":"content_block_delta","index":2,"delta":{"type":"thinking_delta","thinking":"secret"}}""")).events.isEmpty())
        assertTrue(adapter.parse(stream("""{"type":"content_block_delta","index":2,"delta":{"type":"signature_delta","signature":"sig"}}""")).events.isEmpty())
        assertTrue(adapter.parse(blockStop(2)).events.isEmpty())
        assertTrue(adapter.parse(stream("""{"type":"message_delta","delta":{"stop_reason":"tool_use"}}""")).events.isEmpty())
        assertTrue(adapter.parse(stream("""{"type":"message_stop"}""")).events.isEmpty())

        // About 2,000 characters go at once; what remains at the block's end goes with its stop when no final came first.
        adapter.parse(blockStart(0, "text", message = "msg_2", parent = "a1"))
        val long = adapter.parse(textDelta(0, "x".repeat(2_000), message = "msg_2", parent = "a1")).events.single()
        assertEquals(2_000, long.data.getValue("text").jsonPrimitive.content.length)
        assertEquals("a1", long.data.str("parentToolUseId"), "A subagent's deltas keep their parent tool call")
        assertTrue(adapter.parse(textDelta(0, "tail", message = "msg_2", parent = "a1")).events.isEmpty())
        val tail = adapter.parse(blockStop(0, message = "msg_2", parent = "a1")).events.single()
        assertEquals("tail", tail.data.str("text")); assertEquals("msg_2", tail.data.str("messageId"))

        // An interrupted turn: what was held is dropped with the turn, never sent after its end.
        adapter.parse(blockStart(0, "text", message = "msg_3"))
        adapter.parse(textDelta(0, "Half a", message = "msg_3"))
        assertEquals(listOf(CloudEventTypes.TURN), adapter.parse("""{"type":"result","subtype":"error_during_execution","is_error":true}""").events.map { it.type })
        assertTrue(adapter.parse(blockStop(0, message = "msg_3")).events.isEmpty())
        assertTrue(adapter.parse("""{"type":"stream_event","event":"not an object"}""").events.isEmpty(), "A malformed line is ignored")
    }

    @Test fun claudeFinalMessagesCarryTheirMessageIdWithoutLiveText() {
        val adapter = ClaudeStreamJsonAdapter(claude)
        adapter.message("Hi")
        // Without partial messages nothing streams, and the index of a block is unknown: the final replaces all of its message.
        assertTrue(adapter.parse(blockStart(0, "text")).events.isEmpty())
        assertTrue(adapter.parse(textDelta(0, "Hel")).events.isEmpty(), "Deltas only with liveText")
        val events = adapter.parse("""{"type":"assistant","message":{"id":"msg_9","content":[{"type":"text","text":"One"},{"type":"text","text":"Two"}]}}""").events
        assertEquals(listOf("msg_9", "msg_9"), events.map { it.data.str("messageId") })
        assertTrue(events.none { "index" in it.data }, "Two text blocks in one line: no index")
        assertEquals(buildJsonObject { put("text", "Done") }, adapter.parse("""{"type":"assistant","message":{"content":[{"type":"text","text":"Done"}]}}""").events.single().data, "No id, no messageId")
    }

    @Test fun liveTextNamesTheMessageFromItsStartWhenLinesDoNot() {
        var clock = 0L
        val adapter = ClaudeStreamJsonAdapter(claude.copy(liveText = true), now = { clock })
        fun bare(event: String) = """{"type":"stream_event","event":$event,"session_id":"c1","parent_tool_use_id":null,"uuid":"u"}"""
        adapter.parse(bare("""{"type":"message_start","message":{"id":"msg_9","type":"message","role":"assistant","content":[]}}"""))
        adapter.parse(bare("""{"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}"""))
        clock = 600
        val delta = adapter.parse(bare("""{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"Hi"}}""")).events.single()
        assertEquals(buildJsonObject { put("messageId", "msg_9"); put("index", 0); put("text", "Hi") }, delta.data)
    }

    @Test fun tailCountsOnlyTheEventsShown() = runTest {
        // 1..30: every third event is a final message, the others live text deltas.
        val all = (1L..30L).map { CloudEventView(it, if (it % 3 == 0L) CloudEventTypes.AGENT_MESSAGE else CloudEventTypes.AGENT_MESSAGE_DELTA, 0, 1, buildJsonObject { put("text", "t") }) }
        val asked = mutableListOf<Long>()
        val page: suspend (Long) -> CloudEventPageView = { before ->
            asked += before
            val rows = all.filter { it.seq < before }.takeLast(4)
            CloudEventPageView(rows, 1, 30, null, rows.firstOrNull()?.seq?.takeIf { it > 1 })
        }
        assertEquals(23, tailStart(3, 30, { it.shown() }, page), "Three final messages: 24, 27 and 30")
        assertEquals(listOf(31L, 27L), asked, "Walked back page by page")
        assertEquals(27, tailStart(3, 30, { true }, page), "--json counts every event")
        assertEquals(0, tailStart(50, 30, { it.shown() }, page), "Fewer shown events than asked: from the start")
        assertEquals(30, tailStart(0, 30, { it.shown() }, page))
    }

    @Test fun cloudLogsSkipLiveDeltas() {
        val delta = CloudEventView(3, CloudEventTypes.AGENT_MESSAGE_DELTA, 0, 1, buildJsonObject { put("messageId", "msg_1"); put("index", 0); put("text", "Half") })
        assertNull(delta.printed(), "logs and attach print the final message only")
        assertEquals("00:00:00 #4 Done", CloudEventView(4, CloudEventTypes.AGENT_MESSAGE, 0, 1, buildJsonObject { put("text", "Done"); put("messageId", "msg_1") }).printed())
    }

    @Test fun codexAppServerHoldsSteersRetriesAndFallsBack() {
        val adapter = CodexAppServerAdapter(codex, "1.0")
        val startup = adapter.startup("th-old", "/workspace").map(::obj)
        assertEquals(listOf("initialize", "initialized", "thread/resume"), startup.map { it.str("method") })
        assertFalse("jsonrpc" in startup[0])
        assertEquals("never", startup[2]["params"]!!.jsonObject.str("approvalPolicy"))
        assertEquals("danger-full-access", startup[2]["params"]!!.jsonObject.str("sandbox"))
        assertTrue(adapter.message("first").isEmpty(), "Held until the thread is ready")

        // The stored thread is gone: a fresh thread starts and the conversation is transcript only.
        val failed = adapter.parse("""{"id":${startup[2].str("id")},"error":{"code":-1,"message":"no thread"}}""")
        assertEquals("transcript_only", failed.events.single().data.str("conversation"))
        val restart = obj(failed.replies.single())
        assertEquals("thread/start", restart.str("method"))
        val ready = adapter.parse("""{"id":${restart.str("id")},"result":{"thread":{"id":"th-new"},"model":"gpt-5"}}""")
        assertEquals("th-new", adapter.conversationId)
        val turnStart = obj(ready.replies.single())
        assertEquals("turn/start", turnStart.str("method"))
        assertEquals("first", turnStart["params"]!!.jsonObject["input"]!!.jsonArray[0].jsonObject.str("text"))
        assertTrue(adapter.message("second").isEmpty(), "Held while the turn is starting")
        val started = adapter.parse("""{"id":${turnStart.str("id")},"result":{"turn":{"id":"turn-1"}}}""")
        val steer = obj(started.replies.single())
        assertEquals("turn/steer", steer.str("method"))
        assertEquals("turn-1", steer["params"]!!.jsonObject.str("expectedTurnId"))
        assertEquals("turn/interrupt", obj(adapter.interrupt().single()).str("method"))

        // The turn ended before the steer landed: the message starts the next turn instead of being lost.
        assertEquals("completed", adapter.parse("""{"method":"turn/completed","params":{"turn":{"id":"turn-1","status":"completed"}}}""").events.single().data.str("phase"))
        val retried = obj(adapter.parse("""{"id":${steer.str("id")},"error":{"code":-1,"message":"no active turn"}}""").replies.single())
        assertEquals("turn/start", retried.str("method"))
        assertEquals("second", retried["params"]!!.jsonObject["input"]!!.jsonArray[0].jsonObject.str("text"))

        val command = adapter.parse("""{"method":"item/completed","params":{"item":{"type":"commandExecution","id":"i1","command":"ls","aggregatedOutput":"x","exitCode":2,"status":"completed"}}}""").events
        assertEquals(listOf(CloudEventTypes.TOOL_CALL, CloudEventTypes.TOOL_RESULT), command.map { it.type })
        assertEquals(true, (command[1].data["isError"] as JsonPrimitive).boolean)
        assertTrue(adapter.parse("""{"method":"item/completed","params":{"item":{"type":"reasoning","id":"r1","summary":["hidden"]}}}""").events.isEmpty())
        val refusal = obj(adapter.parse("""{"id":99,"method":"item/commandExecution/requestApproval","params":{}}""").replies.single())
        assertEquals(99, (refusal["id"] as JsonPrimitive).int); assertTrue("error" in refusal)
    }

    private class FakeProcess : AgentProcess {
        val written = mutableListOf<String>()
        val output = Channel<String?>(Channel.UNLIMITED)
        var exit: Int? = null
        var stops = 0
        var failWrites = false
        override suspend fun writeLine(line: String): Boolean { if (exit != null || failWrites) return false; written += line; return true }
        override suspend fun readLine(maxLine: Int) = if (exit != null) null else output.receive()
        override fun closeInput() = Unit
        override fun exitCode() = exit
        override suspend fun stop(graceMillis: Long): Int { stops++; if (exit == null) { exit = 0; output.trySend(null) }; return exit!! }
    }

    private class FakeChannel : AgentChannel {
        val inputs = Channel<List<RunnerInputView>>(Channel.UNLIMITED)
        val acks = mutableListOf<RunnerInputAckView>()
        val uploaded = mutableListOf<RunnerEventView>()
        var refuseEvents: TeamCodeException? = null
        /** Refuses, like a 413, any batch that holds the original event at a sequence listed here; its marker passes. */
        val poisoned = mutableSetOf<Long>()
        var attempts = 0
        var relay = false
        var deadline = 0
        var live = false
        override suspend fun agent() = RunnerAgentView("claude", "2.1", "opus", null, "none", permissionRelay = relay, permissionDeadlineMinutes = deadline, liveText = live)
        override suspend fun events(events: List<RunnerEventView>): RunnerEventsAckView {
            // An Error, not an Exception: the host cannot swallow it, so a retry loop without pause fails the test.
            if (++attempts > 10_000) throw AssertionError("The host retries the upload without pause")
            refuseEvents?.let { throw it }
            if (events.any { it.sequence in poisoned && it.type != CloudEventTypes.ERROR }) throw TeamCodeException(413, "too large", "invalid_events")
            events.filter { event -> uploaded.none { it.sequence == event.sequence } }.forEach { uploaded += it }
            return RunnerEventsAckView(events.last().sequence, uploaded.size.toLong())
        }
        override suspend fun inputs(waitMillis: Long) = inputs.receive()
        override suspend fun acknowledge(acks: List<RunnerInputAckView>) { this.acks += acks }
    }

    private class MemoryState(var value: AgentState? = null) : AgentStateStore {
        override fun read() = value
        override fun write(state: AgentState) { value = state }
        override fun clear() { value = null }
    }

    private fun TestScope.host(channel: FakeChannel, state: MemoryState, started: MutableList<Pair<AgentCommand, FakeProcess>>) = AgentHost(channel, { command, environment, _ ->
        assertTrue(environment.containsKey("TEAMCODE_CLOUD_TOKEN_FILE") && environment["TEAMCODE_CLOUD_TOKEN_FILE"] == null, "The token path never reaches the agent")
        FakeProcess().also { started += command to it }
    }, state, "/workspace", "1.0", now = { testScheduler.currentTime })

    private suspend fun TestScope.until(condition: () -> Boolean) { repeat(1_000) { if (condition()) return; delay(100) }; fail("Condition not reached") }

    @Test fun hostStreamsLiveTextWhenTheServerTakesIt() = runTest {
        val channel = FakeChannel().apply { live = true }
        val started = mutableListOf<Pair<AgentCommand, FakeProcess>>()
        val job = launch { host(channel, MemoryState(), started).run() }
        channel.inputs.send(listOf(RunnerInputView("in-1", "message", "Hi", 1)))
        until { started.isNotEmpty() }
        val (command, process) = started.single()
        assertTrue("--include-partial-messages" in command.arguments)
        process.output.send(blockStart(0, "text"))
        process.output.send(textDelta(0, "Hel"))
        delay(600) // The host's clock: the next delta goes out, with what was held.
        process.output.send(textDelta(0, "lo"))
        until { channel.uploaded.any { it.type == CloudEventTypes.AGENT_MESSAGE_DELTA } }
        assertEquals("Hello", channel.uploaded.single { it.type == CloudEventTypes.AGENT_MESSAGE_DELTA }.data.str("text"))
        job.cancel()
    }

    @Test fun hostStartsLazilyAcknowledgesUploadsAndResumes() = runTest {
        val channel = FakeChannel()
        val state = MemoryState()
        val started = mutableListOf<Pair<AgentCommand, FakeProcess>>()
        val host = host(channel, state, started)
        val job = launch { host.run() }
        channel.inputs.send(listOf(RunnerInputView("in-0", "interrupt", null, 1)))
        until { channel.acks.isNotEmpty() }
        assertEquals(RunnerInputAckView("in-0", CloudInputStatuses.REJECTED, "no_turn"), channel.acks.single())
        assertTrue(started.isEmpty(), "Nothing starts the agent before a message")

        channel.inputs.send(listOf(RunnerInputView("in-1", "message", "Fix it", 1)))
        until { channel.acks.size == 2 }
        assertEquals(CloudInputStatuses.CONSUMED, channel.acks[1].status)
        val (command, process) = started.single()
        assertFalse("--resume" in command.arguments)
        assertTrue("Fix it" in process.written.single())
        channel.inputs.send(listOf(RunnerInputView("in-1", "message", "Fix it", 2)))
        until { channel.acks.size == 3 }
        assertEquals(channel.acks[1], channel.acks[2], "A redelivered input is acknowledged again")
        assertEquals(1, process.written.size, "and never written twice")
        process.output.send("""{"type":"system","subtype":"init","session_id":"c1"}""")
        process.output.send("""{"type":"assistant","message":{"content":[{"type":"thinking","thinking":"secret"},{"type":"text","text":"Done"}]}}""")
        process.output.send("""{"type":"result","subtype":"success","is_error":false}""")
        until { channel.uploaded.size == 4 }
        assertEquals(listOf(1L, 2L, 3L, 4L), channel.uploaded.map { it.sequence })
        assertEquals(listOf(CloudEventTypes.TURN, CloudEventTypes.STATUS, CloudEventTypes.AGENT_MESSAGE, CloudEventTypes.TURN), channel.uploaded.map { it.type })
        assertEquals(listOf("started", "completed"), channel.uploaded.filter { it.type == CloudEventTypes.TURN }.map { it.data["phase"]!!.jsonPrimitive.content })
        assertEquals(AgentState("claude", "2.1", "c1"), state.value)

        // A pause drains: nothing runs, so the agent stops at once and the transcript is flushed.
        assertTrue(host.drain(testScheduler.currentTime + 120_000))
        assertNotNull(process.exit, "The agent process stopped")
        channel.inputs.send(listOf(RunnerInputView("in-2", "message", "Later", 1)))
        delay(5_000)
        assertEquals(3, channel.acks.size, "A draining host leaves a message unacknowledged, so the server offers it to the next execution")
        job.cancel()

        // The next pod resumes the stored conversation.
        val next = host(channel, state, started)
        val nextJob = launch { next.run() }
        channel.inputs.send(listOf(RunnerInputView("in-3", "message", "Again", 1)))
        until { started.size == 2 }
        assertEquals(listOf("--resume", "c1"), started[1].first.arguments.takeLast(2))
        nextJob.cancel()
    }

    @Test fun messagesAnsweredByOneResultDoNotHoldThePause() = runTest {
        // Session eef7f7f7: two messages delivered together, one Claude Code turn and one result. Counting a result per
        // message left a turn "running" forever, so the drain waited for the pause deadline instead of stopping at once.
        val channel = FakeChannel()
        val started = mutableListOf<Pair<AgentCommand, FakeProcess>>()
        val host = host(channel, MemoryState(), started)
        val job = launch { host.run() }
        channel.inputs.send(listOf(RunnerInputView("in-1", "message", "Review the repository", 1), RunnerInputView("in-2", "message", "Put it in an artifact", 1)))
        until { channel.acks.size == 2 }
        val process = started.single().second
        process.output.send("""{"type":"assistant","message":{"content":[{"type":"text","text":"Done"}]}}""")
        process.output.send("""{"type":"result","subtype":"success","is_error":false}""")
        until { channel.uploaded.count { it.type == CloudEventTypes.TURN } == 2 }
        val pausedAt = testScheduler.currentTime
        assertTrue(host.drain(pausedAt + 10 * 60_000), "Nothing runs: a clean stop")
        assertTrue(testScheduler.currentTime - pausedAt < 1_000, "The drain did not wait for a turn that had ended (${testScheduler.currentTime - pausedAt} ms)")
        assertFalse(process.written.any { "\"interrupt\"" in it })
        job.cancel()
    }

    @Test fun aPauseWaitsForBackgroundWorkBeforeStoppingTheAgent() = runTest {
        // A pause (explicit, idle, or the device daemon stopping for `teamcode update`) stopped the agent as soon as its turn
        // ended, killing the subagents and background commands it still ran: the drain now waits for them, up to its deadline.
        val channel = FakeChannel()
        val started = mutableListOf<Pair<AgentCommand, FakeProcess>>()
        val host = host(channel, MemoryState(), started)
        val job = launch { host.run() }
        channel.inputs.send(listOf(RunnerInputView("in-1", "message", "Run the suite in the background", 1)))
        until { channel.acks.isNotEmpty() }
        val process = started.single().second
        process.output.send("""{"type":"system","subtype":"background_tasks_changed","tasks":[{"task_id":"b1","task_type":"local_bash","description":"npm test"}]}""")
        process.output.send("""{"type":"result","subtype":"success","is_error":false}""")
        until { channel.uploaded.count { it.type == CloudEventTypes.TURN } == 2 && host.backgroundWork }
        val pausedAt = testScheduler.currentTime
        val drained = async { host.drain(pausedAt + 10 * 60_000) }
        delay(120_000)
        assertEquals(0, process.stops, "The agent keeps running while its background work does")
        assertFalse(drained.isCompleted)
        // The command ends: Claude Code reports it in a turn of its own, which the drain lets finish.
        process.output.send("""{"type":"system","subtype":"background_tasks_changed","tasks":[]}""")
        process.output.send("""{"type":"assistant","message":{"content":[{"type":"text","text":"The suite passed"}]}}""")
        process.output.send("""{"type":"result","subtype":"success","is_error":false}""")
        assertTrue(drained.await(), "Background work that ended in time is a clean stop")
        assertTrue(testScheduler.currentTime - pausedAt < 130_000)
        job.cancel()
    }

    @Test fun scheduledWakeupsAreBackgroundWorkOfTheCurrentAgentProcessOnly() = runTest {
        val channel = FakeChannel()
        val started = mutableListOf<Pair<AgentCommand, FakeProcess>>()
        val host = host(channel, MemoryState(), started)
        host.scheduledWakeups(2)
        assertFalse(host.backgroundWork, "No agent process, nothing scheduled to fire")
        val job = launch { host.run() }
        channel.inputs.send(listOf(RunnerInputView("in-1", "message", "Check the deploy every 5 minutes", 1)))
        until { channel.acks.isNotEmpty() }
        val process = started.single().second
        process.output.send("""{"type":"result","subtype":"success","is_error":false}""")
        until { channel.uploaded.count { it.type == CloudEventTypes.TURN } == 2 }
        assertFalse(host.backgroundWork, "A new process starts with none")
        host.scheduledWakeups(1)
        assertTrue(host.backgroundWork, "A /loop or a reminder keeps the session from the idle pause")
        assertFalse(host.backgroundTasksInFlight, "But it is not work a stop kills: --resume restores it")
        host.scheduledWakeups(0)
        assertFalse(host.backgroundWork)
        job.cancel()
    }

    @Test fun backgroundWorkStillRunningAtThePauseDeadlineIsStoppedAndSaid() = runTest {
        val channel = FakeChannel()
        val started = mutableListOf<Pair<AgentCommand, FakeProcess>>()
        val host = host(channel, MemoryState(), started)
        val job = launch { host.run() }
        channel.inputs.send(listOf(RunnerInputView("in-1", "message", "Tail the logs", 1)))
        until { channel.acks.isNotEmpty() }
        val process = started.single().second
        process.output.send("""{"type":"system","subtype":"background_tasks_changed","tasks":[{"task_id":"b1","task_type":"local_bash","description":"tail -f"},{"task_id":"a1","task_type":"local_agent","description":"Review"}]}""")
        process.output.send("""{"type":"result","subtype":"success","is_error":false}""")
        until { host.backgroundWork }
        val deadline = testScheduler.currentTime + 10 * 60_000
        assertFalse(host.drain(deadline), "Background work stopped by the pause is not a clean stop")
        assertTrue(testScheduler.currentTime <= deadline, "Within the pause deadline")
        assertNotNull(process.exit, "The agent is stopped")
        until { channel.uploaded.any { it.type == CloudEventTypes.STATUS && (it.data["agent"] as? JsonPrimitive)?.content == "background_stopped" } }
        assertEquals(2, (channel.uploaded.single { (it.data["agent"] as? JsonPrimitive)?.content == "background_stopped" }.data["tasks"] as JsonPrimitive).int,
            "The transcript says how much background work the pause stopped")
        job.cancel()
    }

    @Test fun anAgentOutputThatNeverEndsDoesNotHoldTheCheckpoint() = runTest {
        // The process stops, but its output stream stays open (a descendant keeps it): the drain gives up on the reader.
        val output = Channel<String?>(Channel.UNLIMITED)
        var exit: Int? = null
        val stuck = object : AgentProcess {
            override suspend fun writeLine(line: String) = exit == null
            override suspend fun readLine(maxLine: Int) = output.receive()
            override fun closeInput() = Unit
            override fun exitCode() = exit
            override suspend fun stop(graceMillis: Long): Int { exit = 0; return 0 }
        }
        val channel = FakeChannel()
        val host = AgentHost(channel, { _, _, _ -> stuck }, MemoryState(), "/workspace", "1.0", now = { testScheduler.currentTime })
        val job = launch { host.run() }
        channel.inputs.send(listOf(RunnerInputView("in-1", "message", "Go", 1)))
        until { channel.acks.isNotEmpty() }
        output.send("""{"type":"result","subtype":"success","is_error":false}""")
        until { channel.uploaded.count { it.type == CloudEventTypes.TURN } == 2 }
        val pausedAt = testScheduler.currentTime
        assertFalse(host.drain(pausedAt + 10 * 60_000), "Reported as an unclean stop")
        assertTrue(testScheduler.currentTime - pausedAt <= AgentHost.READER_END_MILLIS + 1_000, "Bounded by the reader timeout")
        job.cancel()
    }

    @Test fun theInputPollCarriesTheDesiredStateAndSignalsAChange() = runTest {
        val channel = FakeChannel()
        val sent = mutableListOf<String>()
        val answers = Channel<String>(Channel.UNLIMITED)
        val polling = object : AgentChannel by channel {
            override suspend fun poll(waitMillis: Long, desiredState: String, acceptsFiles: Boolean): RunnerInputsView { sent += desiredState; return RunnerInputsView(emptyList(), answers.receive()) }
        }
        var changes = 0
        val host = AgentHost(polling, { _, _, _ -> FakeProcess() }, MemoryState(), "/workspace", "1.0", now = { testScheduler.currentTime })
        host.onInstructionChange { changes++ }
        val job = launch { host.run() }
        answers.send("running"); answers.send("paused"); answers.send("paused"); answers.send("running")
        until { sent.size == 5 }
        assertEquals(listOf("running", "running", "paused", "paused", "running"), sent, "Each poll sends the state the previous one returned")
        assertEquals(2, changes, "The pause and its cancellation each reached the runner loop once")
        job.cancel()
    }

    @Test fun drainInterruptsALongTurnAndFencingStopsTheHost() = runTest {
        val channel = FakeChannel()
        val started = mutableListOf<Pair<AgentCommand, FakeProcess>>()
        val host = host(channel, MemoryState(AgentState("claude", "1.9", "old")), started)
        val job = launch { host.run() }
        channel.inputs.send(listOf(RunnerInputView("in-1", "message", "Long task", 1)))
        until { started.isNotEmpty() }
        assertFalse("--resume" in started.single().first.arguments, "Another agent version never resumes")
        val process = started.single().second
        val deadline = testScheduler.currentTime + 90_000
        assertFalse(host.drain(deadline), "An interrupted turn is not a clean stop")
        assertTrue(process.written.any { "\"interrupt\"" in it })
        assertTrue(testScheduler.currentTime <= deadline, "The drain finished before the pod's deadline")
        until { channel.uploaded.isNotEmpty() }
        assertEquals("transcript_only", channel.uploaded.first().data["conversation"]?.jsonPrimitive?.content)

        host.resumeAfterDrain()
        channel.refuseEvents = TeamCodeException(409, "fenced", "execution_fenced")
        channel.inputs.send(listOf(RunnerInputView("in-2", "message", "After", 1)))
        until { started.size == 2 }
        started[1].second.output.send("""{"type":"assistant","message":{"content":[{"type":"text","text":"x"}]}}""")
        until { host.fenced }
        channel.inputs.send(emptyList())
        withTimeout(60_000) { job.join() }
    }

    @Test fun aPermanentlyRefusedEventIsReplacedByAMarkerInsteadOfRetriedForever() = runTest {
        // The server is briefly unavailable, so the four events wait in one batch; the third is too large for it.
        val channel = FakeChannel().apply { poisoned += 3L; refuseEvents = TeamCodeException(503, "unavailable", null) }
        val started = mutableListOf<Pair<AgentCommand, FakeProcess>>()
        val host = host(channel, MemoryState(), started)
        val job = launch { host.run() }
        channel.inputs.send(listOf(RunnerInputView("in-1", "message", "Fix it", 1)))
        until { started.isNotEmpty() }
        val process = started.single().second
        process.output.send("""{"type":"system","subtype":"init","session_id":"c1"}""")
        process.output.send("""{"type":"assistant","message":{"content":[{"type":"text","text":"Done"}]}}""")
        process.output.send("""{"type":"result","subtype":"success","is_error":false}""")
        delay(5_000)
        channel.refuseEvents = null
        until { channel.uploaded.size == 4 }
        assertEquals(listOf(1L, 2L, 3L, 4L), channel.uploaded.map { it.sequence }, "Sequence numbers stay consecutive")
        assertEquals(CloudEventTypes.ERROR, channel.uploaded[2].type)
        assertEquals("transcript_event_rejected", channel.uploaded[2].data["code"]!!.jsonPrimitive.content)
        assertEquals(CloudEventTypes.TURN, channel.uploaded[3].type, "The events after the refused one are still uploaded")
        assertFalse(host.fenced)
        job.cancel()
    }

    @Test fun aRefusalThatIsNotAboutTheContentBacksOffInsteadOfReplacingTheTranscript() = runTest {
        // Every batch is refused, the marker too: the refusal is not caused by one event.
        val channel = FakeChannel().apply { refuseEvents = TeamCodeException(400, "refused", "invalid_events") }
        val started = mutableListOf<Pair<AgentCommand, FakeProcess>>()
        val host = host(channel, MemoryState(), started)
        val job = launch { host.run() }
        channel.inputs.send(listOf(RunnerInputView("in-1", "message", "Fix it", 1)))
        until { started.isNotEmpty() }
        val process = started.single().second
        process.output.send("""{"type":"system","subtype":"init","session_id":"c1"}""")
        process.output.send("""{"type":"assistant","message":{"content":[{"type":"text","text":"Done"}]}}""")
        delay(60_000)
        assertTrue(channel.attempts < 100, "The host backs off, ${channel.attempts} attempts in a minute")
        channel.refuseEvents = null
        until { channel.uploaded.size == 3 }
        assertEquals(listOf(1L, 2L, 3L), channel.uploaded.map { it.sequence })
        assertEquals(1, channel.uploaded.count { it.data["code"]?.jsonPrimitive?.content == "transcript_event_rejected" }, "Only the first event was given up")
        assertFalse(host.fenced)
        job.cancel()
    }

    @Test fun aRefusalOfAnotherKindIsRetriedWithoutGivingUpAnyEvent() = runTest {
        val channel = FakeChannel().apply { refuseEvents = TeamCodeException(410, "gone", null) }
        val started = mutableListOf<Pair<AgentCommand, FakeProcess>>()
        val host = host(channel, MemoryState(), started)
        val job = launch { host.run() }
        channel.inputs.send(listOf(RunnerInputView("in-1", "message", "Fix it", 1)))
        until { started.isNotEmpty() }
        started.single().second.output.send("""{"type":"system","subtype":"init","session_id":"c1"}""")
        delay(60_000)
        assertTrue(channel.attempts < 100, "The host backs off, ${channel.attempts} attempts in a minute")
        channel.refuseEvents = null
        until { channel.uploaded.size == 2 }
        assertTrue(channel.uploaded.none { it.type == CloudEventTypes.ERROR }, "No event was replaced by a marker")
        job.cancel()
    }

    @Test fun runnerKeepsHeartbeatingWhileTheAgentDrains() = runTest {
        val calls = mutableListOf<String>()
        val agent = object : RunnerAgent {
            override val fenced = false
            var running = false
            var stopped = false
            override suspend fun run() { running = true; awaitCancellation() }
            override suspend fun drain(deadline: Long?): Boolean { calls += "drain:$deadline"; delay(40_000); calls += "drained"; return false }
            override fun resumeAfterDrain() { calls += "resume" }
            override suspend fun stop() { stopped = true }
        }
        val instructions = ArrayDeque(listOf("running", "paused", "running", "paused", "paused", "paused", "paused", "paused", "paused", "terminated"))
        val loop = RunnerLoop(object : RunnerCalls {
            override suspend fun register() = RunnerInstructionView("running", null, 1)
            override suspend fun heartbeat(ready: Boolean): RunnerInstructionView {
                calls += "heartbeat"
                val state = instructions.removeFirst()
                return RunnerInstructionView(state, if (state == "paused") 600_000 else null, 1)
            }
            override suspend fun checkpoint(completed: Boolean): RunnerInstructionView { calls += "checkpoint:$completed"; return RunnerInstructionView("paused", 600_000, 1) }
        }, agent = agent)
        assertEquals(0, loop.run())
        assertTrue(agent.running && agent.stopped, "The agent ran beside the lifecycle and was stopped on the way out")
        val firstDrain = calls.indexOf("drain:600000")
        val nextBeat = firstDrain + 1 + calls.drop(firstDrain + 1).indexOf("heartbeat")
        assertEquals("resume", calls[nextBeat + 1], "A cancelled pause lets the agent take messages again")
        val drained = calls.indexOf("drained")
        assertTrue(calls.subList(calls.lastIndexOf("drain:600000"), drained).count { it == "heartbeat" } >= 2, "Heartbeats continued during the drain: $calls")
        assertEquals("checkpoint:false", calls.drop(drained).first { it.startsWith("checkpoint") }, "An interrupted drain is reported as such")
        assertEquals(1, calls.count { it.startsWith("checkpoint") })
    }

    @Test fun agentInferenceGoesThroughTheRunnerProxyWithALocalToken() = runTest {
        val channel = FakeChannel()
        val environments = mutableListOf<Map<String, String?>>()
        val started = mutableListOf<AgentCommand>()
        val local = "l".repeat(64)
        val host = AgentHost(channel, { command, environment, _ -> environments += environment; started += command; FakeProcess() },
            MemoryState(), "/workspace", "1.0", now = { testScheduler.currentTime }, inference = { AgentInferenceEndpoint(41234, local) })
        val job = launch { host.run() }
        channel.inputs.send(listOf(RunnerInputView("in-1", "message", "Go", 1)))
        until { started.isNotEmpty() }
        val environment = environments.single()
        assertEquals("http://127.0.0.1:41234", environment["ANTHROPIC_BASE_URL"])
        assertEquals(local, environment["ANTHROPIC_AUTH_TOKEN"], "The agent holds only the proxy's local token")
        for (name in listOf("ANTHROPIC_API_KEY", "CLAUDE_CODE_OAUTH_TOKEN", "CLAUDE_CODE_USE_BEDROCK", "TEAMCODE_CLOUD_TOKEN_FILE", "TEAMCODE_SERVER_URL"))
            assertTrue(environment.containsKey(name) && environment[name] == null, "$name is removed from the agent")
        assertFalse(environment.values.any { it?.startsWith("tcex_") == true }, "The execution credential never reaches the agent")
        assertEquals("--dangerously-skip-permissions" in started.single().arguments, true)
        job.cancel()

        // Codex: the same proxy through a model provider override and its token variable.
        val codexChannel = object : AgentChannel by channel { override suspend fun agent() = RunnerAgentView("codex", "0.163", "gpt-5", null, "none") }
        val codexStarted = mutableListOf<Pair<AgentCommand, Map<String, String?>>>()
        val codexHost = AgentHost(codexChannel, { command, environment, _ -> codexStarted += command to environment; FakeProcess() },
            MemoryState(), "/workspace", "1.0", now = { testScheduler.currentTime }, inference = { AgentInferenceEndpoint(41234, local) })
        val codexJob = launch { codexHost.run() }
        channel.inputs.send(listOf(RunnerInputView("in-2", "message", "Go", 1)))
        until { codexStarted.isNotEmpty() }
        val (codexCommand, codexEnvironment) = codexStarted.single()
        assertEquals("app-server", codexCommand.arguments.first())
        assertTrue(codexCommand.arguments.any { "base_url=\"http://127.0.0.1:41234/v1\"" in it }, codexCommand.arguments.toString())
        assertEquals(local, codexEnvironment["TEAMCODE_LOCAL_TOKEN"])
        codexJob.cancel()
    }

    @Test fun runnerInferenceCredentialIsRenewedAndDroppedWhenRefused() = runTest {
        var clock = 0L
        var fetches = 0
        var refuse: Exception? = null
        val hour = 3_600_000L
        val inference = RunnerInference({
            refuse?.let { throw it }
            fetches++
            RunnerInferenceCredentialView("tcex_credential-$fetches", 999_999_999, hour)
        }, now = { clock })
        assertEquals("tcex_credential-1", inference.token())
        clock += 60_000
        assertEquals("tcex_credential-1", inference.token(), "Reused until renewal is due")
        assertEquals(1, fetches)
        clock = hour / 3
        assertEquals("tcex_credential-2", inference.token(), "Renewed after a third of its lifetime, from the lifetime the server returned")
        // A network failure keeps the current credential while it is still usable, and retries later.
        clock += hour / 3
        refuse = IllegalStateException("offline")
        assertEquals("tcex_credential-2", inference.token())
        assertTrue(inference.available())
        clock = hour / 3 + hour - RunnerInference.SAFETY_MILLIS
        assertNull(inference.token(), "Never offered in its last minutes")
        assertFalse(inference.available(), "The proxy answers 503 instead of forwarding without a credential")
        // A refusal by the server (fenced execution) drops it at once; a missing route marks the server as unsupported.
        refuse = null
        clock += RunnerInference.RETRY_MILLIS
        assertEquals("tcex_credential-3", inference.token())
        clock += hour / 3
        refuse = TeamCodeException(409, "fenced", "execution_fenced")
        assertNull(inference.token())
        assertFalse(inference.unsupported)
        clock += RunnerInference.RETRY_MILLIS
        refuse = TeamCodeException(404, "missing", "not_found")
        assertNull(inference.token())
        assertTrue(inference.unsupported)
    }

    /** A runner channel that serves message files, counting the downloads, and records what the host announced. */
    private class FileChannel(val channel: FakeChannel, val kind: String = "claude", val mode: String? = null) : AgentChannel by channel {
        val bytes = mutableMapOf<String, ByteArray>()
        var downloads = 0
        var failure: Exception? = null
        val announced = mutableListOf<Boolean>()
        override suspend fun agent() = RunnerAgentView(kind, "1", null, null, "none", permissionMode = mode)
        override suspend fun poll(waitMillis: Long, desiredState: String, acceptsFiles: Boolean): RunnerInputsView {
            announced += acceptsFiles
            return RunnerInputsView(channel.inputs.receive())
        }
        override suspend fun file(fileId: String, maxBytes: Long, receive: (ByteArray, Int) -> Unit) {
            downloads++
            failure?.let { throw it }
            val content = bytes.getValue(fileId)
            // Two chunks, as the client streams them.
            val half = content.size / 2
            receive(content.copyOfRange(0, half), half)
            receive(content.copyOfRange(half, content.size), content.size - half)
        }
        fun serve(id: String, name: String, content: ByteArray, type: String = "image/png", sha256: String = content.toByteString().sha256().hex()): RunnerFileView {
            bytes[id] = content
            return RunnerFileView(id, name, content.size.toLong(), type, sha256)
        }
    }

    /** A fresh directory standing for the user's temporary directory, and another for the session's working directory. */
    private class Scratch {
        val root = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "teamcode-files-test-${Uuid.random()}"
        val temporary = (root / "tmp").also { FileSystem.SYSTEM.createDirectories(it) }
        val workspace = (root / "workspace").also { FileSystem.SYSTEM.createDirectories(it) }
        fun close() = FileSystem.SYSTEM.deleteRecursively(root, mustExist = false)
    }

    private val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
    private val windows get() = hostOs() == "windows"

    private fun TestScope.fileHost(channel: FileChannel, scratch: Scratch, started: MutableList<Pair<AgentCommand, FakeProcess>>, uploads: SessionUploads) =
        AgentHost(channel, { command, _, _ -> FakeProcess().also { started += command to it } }, MemoryState(), scratch.workspace.toString(), "1.0",
            now = { testScheduler.currentTime }, uploads = uploads)

    @Test fun messageFilesLandInTheUserTemporaryDirectoryPrivatelyAndTheAgentReadsTheirPath() = runTest {
        val scratch = Scratch()
        try {
            val channel = FileChannel(FakeChannel())
            val started = mutableListOf<Pair<AgentCommand, FakeProcess>>()
            val uploads = SessionUploads.remote("s-1", "501", scratch.temporary)
            val job = launch { fileHost(channel, scratch, started, uploads).run() }
            val file = channel.serve("f1", "shot.png", png)
            channel.channel.inputs.send(listOf(RunnerInputView("in-1", "message", "Look at this", 1, listOf(file))))
            until { channel.channel.acks.isNotEmpty() }
            assertEquals(CloudInputStatuses.CONSUMED, channel.channel.acks.single().status)
            assertEquals(true, channel.announced.first(), "The host announces files once its directory is ready")

            val directory = assertNotNull(uploads.directory)
            assertEquals(FileSystem.SYSTEM.canonicalize(scratch.temporary / "teamcode-501" / "s-1"), directory)
            val written = directory / "f1-shot.png"
            assertContentEquals(png, FileSystem.SYSTEM.read(written) { readByteArray() })
            assertTrue(FileSystem.SYSTEM.listRecursively(scratch.workspace).toList().isEmpty(), "Nothing under the working directory")
            if (!windows) {
                assertEquals(0x1C0, pathStatus((scratch.temporary / "teamcode-501").toString())!!.mode, "The user's directory is 0700")
                assertEquals(0x1C0, pathStatus(directory.toString())!!.mode, "The session's directory is 0700")
                assertEquals(0x180, pathStatus(written.toString())!!.mode, "The file is 0600")
            }

            // The stream-json line the agent reads: the text, then one line per file with its absolute path.
            val (command, process) = started.single()
            val text = obj(process.written.single())["message"]!!.jsonObject["content"]!!.jsonArray.single().jsonObject.str("text")
            assertEquals("Look at this\n\n[Attached file: $written (image/png, 8 bytes)]", text)
            assertTrue(written.isAbsolute)
            // Claude Code reads the directory without a permission request.
            val addDir = command.arguments.indexOf("--add-dir")
            assertTrue(addDir >= 0, command.arguments.toString())
            assertEquals(directory.toString(), command.arguments[addDir + 1])
            job.cancel()
        } finally { scratch.close() }
    }

    @Test fun aRedeliveredMessageWritesItsFilesOnce() = runTest {
        val scratch = Scratch()
        try {
            val channel = FileChannel(FakeChannel())
            val started = mutableListOf<Pair<AgentCommand, FakeProcess>>()
            val job = launch { fileHost(channel, scratch, started, SessionUploads.remote("s-1", "501", scratch.temporary)).run() }
            val input = RunnerInputView("in-1", "message", "", 1, listOf(channel.serve("f1", "a.txt", "hello".encodeToByteArray(), "text/plain")))
            channel.channel.inputs.send(listOf(input))
            until { channel.channel.acks.size == 1 }
            channel.channel.inputs.send(listOf(input.copy(attempt = 2)))
            until { channel.channel.acks.size == 2 }
            assertEquals(channel.channel.acks[0], channel.channel.acks[1], "Acknowledged again")
            assertEquals(1, channel.downloads, "Downloaded and written once")
            assertEquals(1, started.single().second.written.size, "Handed to the agent once")
            assertEquals("[Attached file: ", obj(started.single().second.written.single())["message"]!!.jsonObject["content"]!!.jsonArray.single().jsonObject.str("text").take(16),
                "A message of files alone carries their lines only")
            job.cancel()
        } finally { scratch.close() }
    }

    @Test fun aFileThatDoesNotMatchOrCannotBeDownloadedRejectsTheMessageAndStartsNoTurn() = runTest {
        val scratch = Scratch()
        try {
            val channel = FileChannel(FakeChannel())
            val started = mutableListOf<Pair<AgentCommand, FakeProcess>>()
            val uploads = SessionUploads.remote("s-1", "501", scratch.temporary)
            val job = launch { fileHost(channel, scratch, started, uploads).run() }
            val forged = channel.serve("f1", "shot.png", png, sha256 = "0".repeat(64))
            channel.channel.inputs.send(listOf(RunnerInputView("in-1", "message", "Look", 1, listOf(forged))))
            until { channel.channel.acks.size == 1 }
            assertEquals(RunnerInputAckView("in-1", CloudInputStatuses.REJECTED, "file_integrity_failed"), channel.channel.acks.single())
            assertTrue(FileSystem.SYSTEM.list(uploads.directory!!).isEmpty(), "The mismatching file is removed")

            channel.failure = IllegalStateException("network")
            channel.channel.inputs.send(listOf(RunnerInputView("in-2", "message", "Again", 1, listOf(channel.serve("f2", "b.png", png)))))
            until { channel.channel.acks.size == 2 }
            assertEquals(RunnerInputAckView("in-2", CloudInputStatuses.REJECTED, "file_download_failed"), channel.channel.acks[1])
            assertEquals(1 + AgentHost.FILE_ATTEMPTS, channel.downloads, "A failing download is retried, then the message is rejected")

            channel.failure = TeamCodeException(404, "gone", "not_found")
            channel.channel.inputs.send(listOf(RunnerInputView("in-3", "message", "Once more", 1, listOf(channel.serve("f3", "c.png", png)))))
            until { channel.channel.acks.size == 3 }
            assertEquals(RunnerInputAckView("in-3", CloudInputStatuses.REJECTED, "file_unavailable"), channel.channel.acks[2])
            assertTrue(started.isEmpty(), "No agent process, so no turn")
            assertTrue(channel.channel.uploaded.none { it.type == CloudEventTypes.TURN }, "No turn event")
            job.cancel()
        } finally { scratch.close() }
    }

    @Test fun aFileNameOrIdCannotLeaveTheUploadsDirectory() = runTest {
        val scratch = Scratch()
        try {
            val channel = FileChannel(FakeChannel())
            val started = mutableListOf<Pair<AgentCommand, FakeProcess>>()
            val uploads = SessionUploads.remote("s-1", "501", scratch.temporary)
            val job = launch { fileHost(channel, scratch, started, uploads).run() }
            val names = listOf("../../workspace/evil.png", "..\\..\\evil.png", "/etc/passwd", "..", "-rf", ".hidden\u0000.png")
            names.forEachIndexed { index, name ->
                channel.channel.inputs.send(listOf(RunnerInputView("in-$index", "message", "x", 1, listOf(channel.serve("f$index", name, png)))))
            }
            until { channel.channel.acks.size == names.size }
            assertTrue(channel.channel.acks.all { it.status == CloudInputStatuses.CONSUMED }, channel.channel.acks.toString())
            val directory = uploads.directory!!
            val files = FileSystem.SYSTEM.listRecursively(scratch.root).filter { FileSystem.SYSTEM.metadata(it).isRegularFile }.toList()
            assertEquals(names.size, files.size)
            assertTrue(files.all { FileSystem.SYSTEM.canonicalize(it).parent == directory }, "Every file stays in the session's directory: $files")
            assertEquals(listOf("f0-evil.png", "f1-evil.png", "f2-passwd", "f3-file", "f4-rf", "f5-hidden.png"), files.map { it.name }.sorted())
            assertTrue(FileSystem.SYSTEM.listRecursively(scratch.workspace).toList().isEmpty())

            // An id that is a path is refused, and a link planted at the target is never followed.
            channel.channel.inputs.send(listOf(RunnerInputView("in-id", "message", "x", 1, listOf(channel.serve("../f", "a.png", png)))))
            until { channel.channel.acks.size == names.size + 1 }
            assertEquals("invalid_file", channel.channel.acks.last().reason)
            if (!windows) {
                val outside = scratch.workspace / "target.png"
                FileSystem.SYSTEM.createSymlink(directory / "fl-link.png", outside)
                channel.channel.inputs.send(listOf(RunnerInputView("in-link", "message", "x", 1, listOf(channel.serve("fl", "link.png", png)))))
                until { channel.channel.acks.size == names.size + 2 }
                assertEquals("file_write_failed", channel.channel.acks.last().reason)
                assertFalse(FileSystem.SYSTEM.exists(outside), "Nothing written through the link")
            }
            job.cancel()
        } finally { scratch.close() }
    }

    @Test fun aMessageFileSurvivesAPauseAndAResumeOnTheRemoteHost() = runTest {
        val scratch = Scratch()
        try {
            val root = scratch.temporary / "teamcode-501"
            val first = FileChannel(FakeChannel())
            val host = fileHost(first, scratch, mutableListOf(), SessionUploads.remote("s-1", "501", scratch.temporary))
            val job = launch { host.run() }
            first.channel.inputs.send(listOf(RunnerInputView("in-1", "message", "x", 1, listOf(first.serve("f1", "a.png", png)))))
            until { first.channel.acks.isNotEmpty() }
            // The pause: the agent drains, the host checkpoints and stops (as RunnerLoop does on its way out).
            host.drain(null)
            host.stop()
            job.cancel()
            assertTrue(FileSystem.SYSTEM.exists(root / "s-1" / "f1-a.png"), "A pause keeps the file")

            // The resume: a new execution of the same session finds it, and still writes the next file next to it.
            val second = FileChannel(FakeChannel())
            val started = mutableListOf<Pair<AgentCommand, FakeProcess>>()
            val resumed = fileHost(second, scratch, started, SessionUploads.remote("s-1", "501", scratch.temporary))
            val resumedJob = launch { resumed.run() }
            second.channel.inputs.send(listOf(RunnerInputView("in-2", "message", "y", 1, listOf(second.serve("f2", "b.png", png)))))
            until { second.channel.acks.isNotEmpty() }
            assertTrue(FileSystem.SYSTEM.exists(root / "s-1" / "f1-a.png"), "The resumed conversation can read the first file again")
            assertTrue(FileSystem.SYSTEM.exists(root / "s-1" / "f2-b.png"))
            // A message an earlier execution received but never acknowledged, offered again: the same bytes are reused, never rewritten.
            second.channel.inputs.send(listOf(RunnerInputView("in-1", "message", "x", 2, listOf(second.serve("f1", "a.png", png)))))
            until { second.channel.acks.size == 2 }
            assertEquals(CloudInputStatuses.CONSUMED, second.channel.acks[1].status)
            resumed.stop()
            resumedJob.cancel()
            assertTrue(FileSystem.SYSTEM.exists(root / "s-1" / "f2-b.png"), "Stopping without the session ending keeps the files")
        } finally { scratch.close() }
    }

    @Test fun messageFilesAreRemovedWhenTheSessionEnds() = runTest {
        val scratch = Scratch()
        try {
            val root = scratch.temporary / "teamcode-501"
            val channel = FileChannel(FakeChannel())
            val host = fileHost(channel, scratch, mutableListOf(), SessionUploads.remote("s-1", "501", scratch.temporary))
            val heartbeats = Channel<String>(Channel.UNLIMITED)
            val loop = RunnerLoop(object : RunnerCalls {
                override suspend fun register() = RunnerInstructionView("running", null, 1)
                override suspend fun heartbeat(ready: Boolean) = RunnerInstructionView(heartbeats.receive(), null, 1)
                override suspend fun checkpoint(completed: Boolean) = RunnerInstructionView("paused", null, 1)
            }, agent = host)
            val job = async { loop.run() }
            heartbeats.send("running")
            channel.channel.inputs.send(listOf(RunnerInputView("in-1", "message", "x", 1, listOf(channel.serve("f1", "a.png", png)))))
            until { channel.channel.acks.isNotEmpty() }
            assertTrue(FileSystem.SYSTEM.exists(root / "s-1" / "f1-a.png"))
            // The server terminated the session: the runner loop exits and the session's directory goes with it.
            heartbeats.send("terminated")
            assertEquals(RunnerLoop.EXIT_FENCED_OR_DONE, job.await())
            assertFalse(FileSystem.SYSTEM.exists(root / "s-1"), "Removed at the end of the session")

            // A fenced host never removes anything, even for a session that ends: the execution that fenced it may use the directory.
            val fencedChannel = FileChannel(FakeChannel())
            val fencedHost = fileHost(fencedChannel, scratch, mutableListOf(), SessionUploads.remote("s-1", "501", scratch.temporary))
            val fencedJob = launch { fencedHost.run() }
            fencedChannel.channel.inputs.send(listOf(RunnerInputView("in-1", "message", "x", 1, listOf(fencedChannel.serve("f2", "b.png", png)))))
            until { fencedChannel.channel.acks.isNotEmpty() }
            fencedChannel.failure = TeamCodeException(409, "fenced", "execution_fenced")
            fencedChannel.channel.inputs.send(listOf(RunnerInputView("in-2", "message", "x", 1, listOf(fencedChannel.serve("f3", "c.png", png)))))
            until { fencedHost.fenced }
            fencedJob.join()
            fencedHost.sessionEnded(); fencedHost.stop()
            assertTrue(FileSystem.SYSTEM.exists(root / "s-1" / "f2-b.png"), "A fenced host leaves the files")
            assertEquals(1, fencedChannel.channel.acks.size, "and acknowledges nothing more")
        } finally { scratch.close() }
    }

    @Test fun uploadsOlderThanAWeekAreSweptAtStart() = runTest {
        val scratch = Scratch()
        try {
            val root = scratch.temporary / "teamcode-501"
            // Left by earlier executions: this session's own files, and another session's (which may have ended while no host ran).
            FileSystem.SYSTEM.createDirectories(root / "s-1"); FileSystem.SYSTEM.write(root / "s-1" / "old-a.txt") { writeUtf8("a") }
            FileSystem.SYSTEM.createDirectories(root / "s-2"); FileSystem.SYSTEM.write(root / "s-2" / "old-b.txt") { writeUtf8("b") }
            assertTrue(SessionUploads.remote("s-1", "501", scratch.temporary).prepare {})
            assertTrue(FileSystem.SYSTEM.exists(root / "s-1" / "old-a.txt"), "This session's recent files stay")
            assertTrue(FileSystem.SYSTEM.exists(root / "s-2" / "old-b.txt"), "Another session's recent files stay: its host may be running")
            val later = Clock.System.now().toEpochMilliseconds() + SessionUploads.MAX_AGE_MILLIS + 60_000
            assertTrue(SessionUploads.remote("s-1", "501", scratch.temporary, now = { later }).prepare {})
            assertFalse(FileSystem.SYSTEM.exists(root / "s-2"), "Anything older than a week is removed")
            assertFalse(FileSystem.SYSTEM.exists(root / "s-1" / "old-a.txt"), "This session's too: a week at most")
            assertTrue(FileSystem.SYSTEM.exists(root / "s-1"), "and its directory is ready again")
        } finally { scratch.close() }
    }

    @Test fun aHostWithoutAUsableUploadsDirectoryAnnouncesNoFiles() = runTest {
        val scratch = Scratch()
        try {
            // Another user's (or a planted) path where this user's directory should be: files are off, never written there.
            FileSystem.SYSTEM.write(scratch.temporary / "teamcode-501") { writeUtf8("not a directory") }
            val channel = FileChannel(FakeChannel())
            val job = launch { fileHost(channel, scratch, mutableListOf(), SessionUploads.remote("s-1", "501", scratch.temporary)).run() }
            channel.channel.inputs.send(listOf(RunnerInputView("in-1", "message", "x", 1)))
            until { channel.channel.acks.isNotEmpty() }
            assertEquals(false, channel.announced.first())
            job.cancel()
        } finally { scratch.close() }
    }

    @Test fun aPodWritesItsFilesInTheUploadsVolumeReadableByTheAgentsGroup() = runTest {
        val scratch = Scratch()
        try {
            val volume = (scratch.root / "uploads").also { FileSystem.SYSTEM.createDirectories(it) }
            val channel = FileChannel(FakeChannel())
            val started = mutableListOf<Pair<AgentCommand, FakeProcess>>()
            val uploads = SessionUploads.pod(volume.toString())
            val job = launch { fileHost(channel, scratch, started, uploads).run() }
            channel.channel.inputs.send(listOf(RunnerInputView("in-1", "message", "x", 1, listOf(channel.serve("f1", "a.png", png)))))
            until { channel.channel.acks.isNotEmpty() }
            val written = FileSystem.SYSTEM.canonicalize(volume) / "f1-a.png"
            assertTrue(FileSystem.SYSTEM.exists(written))
            if (!windows) assertEquals(0x1A0, pathStatus(written.toString())!!.mode, "0640: the agent's UID reads it through the pod's group")
            job.cancel()
        } finally { scratch.close() }
    }

    @Test fun codexInAskModeKeepsItsWorkspaceSandboxForMessageFiles() {
        // workspace-write restricts writes only: the uploads directory is readable without an extra root, and never writable.
        val adapter = agentAdapter(AgentSettings("codex", "0.163", null, null, permissionMode = RemotePermissionModes.ASK), "1.0", readableDirectories = listOf("/tmp/teamcode-501/s-1"))
        val command = adapter.command(null).arguments
        assertTrue("sandbox_mode=\"workspace-write\"" in command, command.toString())
        assertFalse(command.any { "/tmp/teamcode-501" in it }, "No writable root for the uploads")
        val claude = agentAdapter(AgentSettings("claude", "2.1", null, null, permissionMode = RemotePermissionModes.ASK), "1.0", readableDirectories = listOf("/tmp/teamcode-501/s-1"))
        val arguments = claude.command(null).arguments
        assertEquals(listOf("--add-dir", "/tmp/teamcode-501/s-1"), arguments.subList(arguments.indexOf("--add-dir"), arguments.indexOf("--add-dir") + 2))
    }
}
