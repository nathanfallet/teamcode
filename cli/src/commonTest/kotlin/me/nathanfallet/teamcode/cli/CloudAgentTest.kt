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
import me.nathanfallet.teamcode.cli.cloud.agent.*
import me.nathanfallet.teamcode.cli.platform.AgentProcess
import me.nathanfallet.teamcode.client.TeamCodeException
import kotlin.test.*

class CloudAgentTest {
    private val claude = AgentSettings("claude", "2.1", "opus", "high")
    private val codex = AgentSettings("codex", "0.163", "gpt-5", "medium")
    private fun obj(line: String) = Json.parseToJsonElement(line).jsonObject
    private fun JsonObject.str(name: String) = (this[name] as JsonPrimitive).content

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
        assertTrue(adapter.turnActive, "The queued message runs next")
        adapter.parse("""{"type":"result","subtype":"success","is_error":false}""")
        assertFalse(adapter.turnActive)
        assertTrue(adapter.parse("not json").events.isEmpty(), "Stray output is ignored")
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
        override suspend fun writeLine(line: String): Boolean { if (exit != null) return false; written += line; return true }
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
    /** Refuses, like a 413, any batch that holds a sequence listed here. */
    val poisoned = mutableSetOf<Long>()
    var attempts = 0
        override suspend fun agent() = RunnerAgentView("claude", "2.1", "opus", null, "none")
        override suspend fun events(events: List<RunnerEventView>): RunnerEventsAckView {
            refuseEvents?.let { throw it }
            attempts++
            if (events.any { it.sequence in poisoned }) throw TeamCodeException(413, "too large", "invalid_events")
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
        val channel = FakeChannel().apply { poisoned += 3L }
        val started = mutableListOf<Pair<AgentCommand, FakeProcess>>()
        val host = host(channel, MemoryState(), started)
        val job = launch { host.run() }
        channel.inputs.send(listOf(RunnerInputView("in-1", "message", "Fix it", 1)))
        until { started.isNotEmpty() }
        val process = started.single().second
        process.output.send("""{"type":"system","subtype":"init","session_id":"c1"}""")
        process.output.send("""{"type":"assistant","message":{"content":[{"type":"text","text":"Done"}]}}""")
        process.output.send("""{"type":"result","subtype":"success","is_error":false}""")
        until { channel.uploaded.size == 4 }
        assertEquals(listOf(1L, 2L, 3L, 4L), channel.uploaded.map { it.sequence }, "Sequence numbers stay consecutive")
        assertEquals(CloudEventTypes.ERROR, channel.uploaded[2].type)
        assertEquals("transcript_event_rejected", channel.uploaded[2].data["code"]!!.jsonPrimitive.content)
        assertEquals(CloudEventTypes.TURN, channel.uploaded[3].type, "The events after the refused one are still uploaded")
        assertFalse(host.fenced)
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
}
