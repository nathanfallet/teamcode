package me.nathanfallet.teamcode.cli

import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import me.nathanfallet.teamcode.api.models.EnvironmentVariablesView
import me.nathanfallet.teamcode.cli.cloud.agent.EnvironmentBroker
import me.nathanfallet.teamcode.cli.cloud.agent.WakeupBroker
import me.nathanfallet.teamcode.cli.cloud.agent.RunnerVariables
import me.nathanfallet.teamcode.cli.cloud.agent.requestEnvironment
import me.nathanfallet.teamcode.cli.cloud.agent.reportWakeups
import me.nathanfallet.teamcode.cli.cloud.agent.scheduledWakeups
import me.nathanfallet.teamcode.cli.launch.AgentEnvironment
import me.nathanfallet.teamcode.cli.launch.EnvironmentRefresh
import me.nathanfallet.teamcode.cli.platform.listenUnix
import me.nathanfallet.teamcode.cli.platform.writePrivateFile
import kotlin.test.*

/** The scripts as a real shell runs them: what Claude Code does with `CLAUDE_ENV_FILE` before each Bash command. */
class AgentEnvironmentShellTest {
    /** Runs [command] in `bash -c` with [envFile] evaluated first, the way Claude Code prefixes each command. */
    private fun bash(envFile: String, command: String, environment: Map<String, String> = emptyMap()): String {
        val builder = ProcessBuilder("bash", "-c", ". '$envFile'; $command").redirectErrorStream(true)
        builder.environment().putAll(environment)
        val process = builder.start()
        val output = process.inputStream.readAllBytes().decodeToString()
        assertEquals(0, process.waitFor(), output)
        return output
    }

    private val tricky = mapOf(
        "PLAIN_VALUE" to "abc",
        "QUOTED_VALUE" to "it's \$HOME `id` \"q\" \\ \$(id) ; rm -rf /",
        "MULTILINE_VALUE" to "first\nsecond 'x'",
        "SPACES_VALUE" to "  padded  ",
    )

    @Test fun valuesReadBackExactlyAndNothingIsExpanded() {
        val directory = Files.createTempDirectory("teamcode-env")
        try {
            val values = directory.resolve("values.sh").toString()
            writePrivateFile(values, AgentEnvironment.script(tricky))
            val script = directory.resolve("env.sh").toString()
            writePrivateFile(script, AgentEnvironment.localScript(values))
            for ((name, value) in tricky) assertEquals(value, bash(script, "printf %s \"\$$name\""), name)
            assertEquals(setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE), Files.getPosixFilePermissions(directory.resolve("values.sh")))
        } finally { directory.toFile().deleteRecursively() }
    }

    @Test fun aRewrittenValuesFileReachesTheNextCommandAndUnsetsWithdrawnNames() = runBlocking {
        val directory = Files.createTempDirectory("teamcode-env")
        try {
            val values = directory.resolve("values.sh").toString()
            val script = directory.resolve("env.sh").toString()
            val user = directory.resolve("user.sh").also { Files.writeString(it, "export USER_OWN='mine'\nexport NPM_TOKEN='user'\n") }.toString()
            var answer = EnvironmentVariablesView(mapOf("NPM_TOKEN" to "one", "EXTRA_VALUE" to "x"))
            val refresh = EnvironmentRefresh({ answer }, { writePrivateFile(values, it) }, answer.variables)
            refresh.start()
            // Written once, like Claude Code reads it once; only the values file changes afterwards.
            writePrivateFile(script, AgentEnvironment.localScript(values, chained = user))
            // The agent process started with the first values in its environment.
            val started = mapOf("NPM_TOKEN" to "one", "EXTRA_VALUE" to "x")
            assertEquals("one x mine", bash(script, "printf '%s %s %s' \"\$NPM_TOKEN\" \"\$EXTRA_VALUE\" \"\$USER_OWN\"", started), "Team values win over the user's script")
            answer = EnvironmentVariablesView(mapOf("NPM_TOKEN" to "two"))
            assertTrue(refresh.refresh())
            assertEquals("two <unset>", bash(script, "printf '%s %s' \"\$NPM_TOKEN\" \"\${EXTRA_VALUE-<unset>}\"", started))
        } finally { directory.toFile().deleteRecursively() }
    }

    @Test fun cloudScriptAsksTheRunnerOverItsSocket() = runBlocking {
        val directory = Files.createTempDirectory("tc-env")
        try {
            val socket = directory.resolve("environment.sock").toString()
            var answer = EnvironmentVariablesView(mapOf("NPM_TOKEN" to "it's one"))
            var clock = 0L
            val variables = RunnerVariables({ answer }, now = { clock })
            val listener = listenUnix(socket)
            val server = launch(Dispatchers.IO) { EnvironmentBroker(listener, variables).serve() }
            try {
                variables.forStart()
                val first = withContext(Dispatchers.IO) { requestEnvironment(socket) }
                assertEquals(mapOf("NPM_TOKEN" to "it's one"), first?.variables)
                clock += RunnerVariables.MAX_AGE_MILLIS
                answer = EnvironmentVariablesView(mapOf("OTHER_VALUE" to "two"))
                val second = withContext(Dispatchers.IO) { requestEnvironment(socket) }!!
                val values = directory.resolve("values.sh").toString()
                writePrivateFile(values, AgentEnvironment.script(second.variables, second.withdrawn.toSet()))
                assertEquals("<unset> two", bash(values, "printf '%s %s' \"\${NPM_TOKEN-<unset>}\" \"\$OTHER_VALUE\"", mapOf("NPM_TOKEN" to "it's one")))
                assertNull(withContext(Dispatchers.IO) { requestEnvironment(directory.resolve("missing.sock").toString()) }, "No runner: no answer, no failure")
            } finally { listener.close(); server.cancel() }
        } finally { directory.toFile().deleteRecursively() }
    }

    @Test fun theAgentsStopHookHandsItsScheduledWakeupsToTheRunner() = runBlocking {
        // Claude Code's Stop input lists the session's scheduled wake-ups (https://code.claude.com/docs/en/hooks#stop-input).
        val stop = """{"hook_event_name":"Stop","last_assistant_message":"secret","background_tasks":[],"session_crons":[""" +
            """{"id":"c1","schedule":"*/5 * * * *","recurring":true,"prompt":"check the build"},{"id":"c2","schedule":"3 9 * * *","recurring":false,"prompt":"remind"}]}"""
        assertEquals(2, scheduledWakeups(stop))
        assertEquals(0, scheduledWakeups("""{"hook_event_name":"Stop","session_crons":[]}"""))
        assertEquals(0, scheduledWakeups("""{"hook_event_name":"Stop"}"""), "An older Claude Code reports none")
        assertEquals(0, scheduledWakeups("not json"))
        val big = "x".repeat(1_100_000)
        assertEquals(1, scheduledWakeups("""{"last_assistant_message":"$big","session_crons":[{"id":"c1"}]}"""), "Too large to parse: a non-empty list as a key")
        assertEquals(0, scheduledWakeups("""{"last_assistant_message":"\"session_crons\":[{$big","session_crons":[]}"""))
        val directory = Files.createTempDirectory("tc-env")
        try {
            val socket = directory.resolve("environment.sock").toString()
            val reported = mutableListOf<Int>()
            val listener = listenUnix(socket)
            val server = launch(Dispatchers.IO) { EnvironmentBroker(listener, RunnerVariables({ EnvironmentVariablesView(emptyMap()) }), wakeups = { synchronized(reported) { reported += it } }).serve() }
            try {
                assertTrue(withContext(Dispatchers.IO) { reportWakeups(socket, 2) })
                assertTrue(withContext(Dispatchers.IO) { reportWakeups(socket, 1_000_000) }, "Written, but not a count: ignored")
                assertTrue(withContext(Dispatchers.IO) { reportWakeups(socket, 0) })
                repeat(100) { if (synchronized(reported) { reported.size } < 2) Thread.sleep(20) }
                assertEquals(setOf(2, 0), synchronized(reported) { reported.toSet() })
                assertEquals(2, synchronized(reported) { reported.size })
                assertFalse(withContext(Dispatchers.IO) { reportWakeups(directory.resolve("missing.sock").toString(), 1) }, "No runner: nothing, no failure")
            } finally { listener.close(); server.cancel() }
        } finally { directory.toFile().deleteRecursively() }
    }

    @Test fun aRemoteHostTakesTheStopHooksCountOnItsOwnSocket() = runBlocking {
        val directory = Files.createTempDirectory("tc-wake")
        try {
            val socket = directory.resolve("a.wake.sock").toString()
            val reported = mutableListOf<Int>()
            val listener = listenUnix(socket)
            val server = launch(Dispatchers.IO) { WakeupBroker(listener, { synchronized(reported) { reported += it } }).serve() }
            try {
                assertTrue(withContext(Dispatchers.IO) { reportWakeups(socket, 3) })
                repeat(100) { if (synchronized(reported) { reported.isEmpty() }) Thread.sleep(20) }
                assertEquals(listOf(3), synchronized(reported) { reported.toList() })
            } finally { listener.close(); server.cancel() }
        } finally { directory.toFile().deleteRecursively() }
    }
}
