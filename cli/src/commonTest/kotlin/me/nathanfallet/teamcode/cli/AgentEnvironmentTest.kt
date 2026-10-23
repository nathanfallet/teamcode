package me.nathanfallet.teamcode.cli

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import me.nathanfallet.teamcode.api.models.*
import me.nathanfallet.teamcode.cli.cloud.agent.*
import me.nathanfallet.teamcode.cli.launch.AgentEnvironment
import me.nathanfallet.teamcode.cli.launch.EnvironmentRefresh
import me.nathanfallet.teamcode.cli.platform.AgentProcess
import me.nathanfallet.teamcode.cli.platform.UnixConnection
import me.nathanfallet.teamcode.cli.platform.UnixListener
import me.nathanfallet.teamcode.client.TeamCodeException
import kotlin.test.*

class AgentEnvironmentTest {
    @Test fun reservedAndInvalidNamesNeverReachTheAgent() {
        val offered = mapOf("NPM_TOKEN" to "a", "PATH" to "/evil", "LD_PRELOAD" to "x", "TEAMCODE_MCP_TOKEN" to "x", "CLAUDE_ENV_FILE" to "/x",
            "GH_CONFIG_DIR" to "/x", "BASH_ENV" to "/x", "lower" to "x", "BAD-NAME" to "x", "NUL_VALUE" to "a\u0000b", "GIT_CONFIG_COUNT" to "9")
        assertEquals(mapOf("NPM_TOKEN" to "a"), AgentEnvironment.accepted(offered))
        assertEquals(offered.keys.minus("NPM_TOKEN").sorted(), AgentEnvironment.refused(offered))
    }

    @Test fun scriptExportsQuotedValuesAndUnsetsWithdrawnNames() {
        val script = AgentEnvironment.script(mapOf("B_VALUE" to "it's \$HOME `x` \"q\" \\", "A_VALUE" to "plain value"), withdrawn = setOf("GONE", "A_VALUE", "PATH"))
        val lines = script.lines().filter { it.isNotBlank() && !it.startsWith("#") }
        assertEquals(listOf("unset GONE", "export A_VALUE='plain value'", "export B_VALUE=\"it's \\\$HOME \\`x\\` \\\"q\\\" \\\\\""), lines,
            "Sorted, a still assigned name is not unset, and a reserved name is never touched")
        assertEquals("'a b'", AgentEnvironment.quote("a b"))
    }

    @Test fun bootstrapScriptsHoldNoValue() {
        val local = AgentEnvironment.localScript("/home/me/.config/teamcode/environment/x.values.sh", chained = "/home/me/venv.sh")
        val lines = local.lines().filter { it.isNotBlank() && !it.startsWith("#") }
        assertEquals(listOf("[ -f '/home/me/venv.sh' ] && . '/home/me/venv.sh'", "[ -f '/home/me/.config/teamcode/environment/x.values.sh' ] && . '/home/me/.config/teamcode/environment/x.values.sh'"), lines,
            "The user's own script runs first, so team values win")
        val cloud = AgentEnvironment.cloudScript("/opt/teamcode/teamcode", "/run/teamcode/environment.sock")
        assertTrue("eval \"\$('/opt/teamcode/teamcode' cloud environment --socket '/run/teamcode/environment.sock' 2>/dev/null)\"" in cloud, cloud)
    }

    @Test fun refreshRewritesOnlyOnChangeAndKeepsValuesWhenTheServerFails() = runTest {
        val written = mutableListOf<String>()
        val logs = mutableListOf<String>()
        var answer: () -> EnvironmentVariablesView = { EnvironmentVariablesView(mapOf("NPM_TOKEN" to "one")) }
        val refresh = EnvironmentRefresh({ answer() }, { written += it }, mapOf("NPM_TOKEN" to "one", "OLD_VALUE" to "x"), log = { logs += it })
        refresh.start()
        assertTrue("export OLD_VALUE='x'" in written.single())
        // OLD_VALUE was removed from the assignments since the start: the next commands unset it.
        assertTrue(refresh.refresh())
        assertTrue("unset OLD_VALUE" in written.last() && "export NPM_TOKEN='one'" in written.last(), written.last())
        assertFalse(refresh.refresh(), "Nothing changed: the file is not rewritten")
        answer = { throw TeamCodeException(503, "down") }
        assertFalse(refresh.refresh()); assertFalse(refresh.refresh())
        assertEquals(1, logs.count { "could not be refreshed" in it }, "A failure streak is reported once")
        answer = { EnvironmentVariablesView(mapOf("NPM_TOKEN" to "two")) }
        assertTrue(refresh.refresh())
        assertTrue("export NPM_TOKEN='two'" in written.last())
        assertEquals(3, written.size)
        assertTrue(logs.none { "one" in it || "two" in it }, "Values are never logged: $logs")
    }

    @Test fun runnerVariablesAreCachedRetriedAndForgottenWhenFenced() = runTest {
        var clock = 0L
        var reads = 0
        var answer: () -> EnvironmentVariablesView = { EnvironmentVariablesView(mapOf("NPM_TOKEN" to "one", "PATH" to "/evil")) }
        val variables = RunnerVariables({ reads++; answer() }, now = { clock })
        assertEquals(mapOf("NPM_TOKEN" to "one"), variables.forStart())
        assertEquals(1, reads)
        assertEquals(mapOf("NPM_TOKEN" to "one"), variables.forCommand().variables)
        assertEquals(1, reads, "A fresh answer is served from memory")
        clock += RunnerVariables.MAX_AGE_MILLIS
        answer = { EnvironmentVariablesView(mapOf("OTHER_VALUE" to "two")) }
        val changed = variables.forCommand()
        assertEquals(2, reads)
        assertEquals(mapOf("OTHER_VALUE" to "two"), changed.variables)
        assertEquals(listOf("NPM_TOKEN"), changed.withdrawn, "A name the agent was given and lost is unset")
        clock += RunnerVariables.MAX_AGE_MILLIS
        answer = { throw TeamCodeException(503, "down") }
        assertEquals(mapOf("OTHER_VALUE" to "two"), variables.forCommand().variables, "A failed read keeps the last values")
        variables.forCommand()
        assertEquals(3, reads, "Not asked again before the retry delay")
        clock += RunnerVariables.RETRY_MILLIS
        answer = { throw TeamCodeException(409, "fenced", "execution_fenced") }
        assertEquals(emptyMap(), variables.forCommand().variables, "A fenced execution serves nothing")
        clock += RunnerVariables.MAX_AGE_MILLIS
        variables.forCommand()
        assertEquals(4, reads, "Nor asks again")
    }

    @Test fun anOlderServerWithoutTheRouteIsNotAskedAgain() = runTest {
        var reads = 0
        var clock = 0L
        val variables = RunnerVariables({ reads++; throw TeamCodeException(404, "missing") }, now = { clock })
        assertEquals(emptyMap(), variables.forStart())
        clock += RunnerVariables.MAX_AGE_MILLIS
        variables.forCommand()
        assertEquals(1, reads)
    }

    private class Pipe(private val requests: Channel<String?>, private val answers: MutableList<String>) : UnixConnection {
        var closed = false
        override suspend fun readLine(maxLine: Int) = requests.receive()
        override suspend fun writeLine(line: String): Boolean { answers += line; return true }
        override fun close() { closed = true }
    }

    @Test fun brokerAnswersOneRequestPerConnectionAndIgnoresOthers() = runTest {
        val connections = Channel<UnixConnection?>(Channel.UNLIMITED)
        val listener = object : UnixListener {
            override suspend fun accept() = connections.receive()
            override fun close() = Unit
        }
        val variables = RunnerVariables({ EnvironmentVariablesView(mapOf("NPM_TOKEN" to "one")) })
        val broker = EnvironmentBroker(listener, variables)
        val job = launch { broker.serve() }
        val answers = mutableListOf<String>()
        val good = Pipe(Channel<String?>(1).also { it.send(EnvironmentBroker.REQUEST) }, answers)
        val other = Pipe(Channel<String?>(1).also { it.send("protocol=https") }, answers)
        connections.send(good); connections.send(other)
        repeat(100) { if (!(good.closed && other.closed)) delay(10) }
        assertTrue(good.closed && other.closed)
        val answer = EnvironmentBroker.codec.decodeFromString(EnvironmentAnswer.serializer(), answers.single())
        assertEquals(mapOf("NPM_TOKEN" to "one"), answer.variables)
        assertFalse("one" in answer.toString())
        connections.send(null)
        job.join()
    }

    private class FakeProcess : AgentProcess {
        val output = Channel<String?>(Channel.UNLIMITED)
        var exit: Int? = null
        override suspend fun writeLine(line: String) = exit == null
        override suspend fun readLine(maxLine: Int) = if (exit != null) null else output.receive()
        override fun closeInput() = Unit
        override fun exitCode() = exit
        override suspend fun stop(graceMillis: Long): Int { if (exit == null) { exit = 0; output.trySend(null) }; return exit!! }
    }

    private class Channel1(private val kind: String) : AgentChannel {
        val inputs = Channel<List<RunnerInputView>>(Channel.UNLIMITED)
        override suspend fun agent() = RunnerAgentView(kind, "1", "model", null, "none")
        override suspend fun events(events: List<RunnerEventView>) = RunnerEventsAckView(events.last().sequence, events.size.toLong())
        override suspend fun inputs(waitMillis: Long) = inputs.receive()
        override suspend fun acknowledge(acks: List<RunnerInputAckView>) = Unit
    }

    @Test fun theAgentStartsWithTheOwnerVariablesAndTeamCodeSettingsWin() = runTest {
        for (kind in listOf("claude", "codex")) {
            val channel = Channel1(kind)
            val environments = mutableListOf<Map<String, String?>>()
            val asked = mutableListOf<String>()
            val host = AgentHost(channel, { _, environment, _ -> environments += environment; FakeProcess() }, object : AgentStateStore {
                override fun read(): AgentState? = null
                override fun write(state: AgentState) = Unit
                override fun clear() = Unit
            }, "/workspace", "1.0", now = { testScheduler.currentTime }, inference = { AgentInferenceEndpoint(41234, "l".repeat(64)) },
                variables = AgentVariables { agent -> asked += agent; mapOf("NPM_TOKEN" to "owner-value", "ANTHROPIC_BASE_URL" to "https://evil.example") +
                    (if (agent == "claude") mapOf(AgentEnvironment.CLAUDE_ENV_FILE to "/run/teamcode/agent-env.sh") else emptyMap()) })
            val job = launch { host.run() }
            channel.inputs.send(listOf(RunnerInputView("in-1", "message", "Go", 1)))
            repeat(100) { if (environments.isEmpty()) delay(100) }
            val environment = environments.single()
            assertEquals(listOf(kind), asked)
            assertEquals("owner-value", environment["NPM_TOKEN"])
            if (kind == "claude") {
                assertEquals("http://127.0.0.1:41234", environment["ANTHROPIC_BASE_URL"], "TeamCode's own settings always win")
                assertEquals("/run/teamcode/agent-env.sh", environment[AgentEnvironment.CLAUDE_ENV_FILE])
            } else assertFalse(AgentEnvironment.CLAUDE_ENV_FILE in environment)
            job.cancel()
        }
    }
}
