package me.nathanfallet.teamcode.cli

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import me.nathanfallet.teamcode.api.models.*
import me.nathanfallet.teamcode.cli.cloud.*
import me.nathanfallet.teamcode.client.TeamCodeException
import kotlin.test.*

class CloudPreparationTest {
    private val token = "ghu_synthetic_user_token"
    private val recipe = CloudEnvironmentView(
        repositories = listOf(CloudRepositoryView("https://github.com/example/api.git", "api", "main", "task-1")),
        workingDirectory = "api", setup = CloudSetupView(create = listOf("npm ci"), start = listOf("npm run db:migrate")),
    )
    private fun environment(github: RunnerGitHubView = RunnerGitHubView("linked", token, 1)) =
        RunnerEnvironmentView(recipe, RunnerGitIdentityView("octo", "42+octo@users.noreply.github.com"), mapOf("NPM_TOKEN" to "npm_synthetic_value"), emptyList(), github)

    private class Calls(val prepared: () -> RunnerEnvironmentView) : PreparationCalls {
        val phases = mutableListOf<String>()
        val failures = mutableListOf<RunnerPreparationFailureView>()
        override suspend fun environment() = prepared()
        override suspend fun progress(phase: String, failure: RunnerPreparationFailureView?): RunnerInstructionView {
            if (failure != null) failures += failure else phases += phase
            return RunnerInstructionView("running", null, 1)
        }
    }
    /** A workspace where only the listed paths exist; every command is recorded with its environment. */
    private class Shell(val existing: MutableSet<String> = mutableSetOf(), val exit: (List<String>) -> Int = { 0 }) : CommandRunner {
        data class Call(val arguments: List<String>, val directory: String, val environment: Map<String, String?>, val secrets: Set<String>)
        val calls = mutableListOf<Call>()
        override suspend fun run(arguments: List<String>, directory: String, environment: Map<String, String?>, secrets: Set<String>): Int {
            calls += Call(arguments, directory, environment, secrets)
            if (arguments.first() == "test") return if (arguments.last() in existing) 0 else 1
            if (arguments.first() == "touch") existing += arguments.last()
            if (arguments.take(2) == listOf("/bin/sh", "-c") && arguments.size == 6) existing += arguments.last() + "/.git"
            return exit(arguments)
        }
        fun commands() = calls.map { it.arguments }.filter { it.first() != "test" }
    }

    @Test fun firstExecutionClonesChecksOutAndRunsSetupWithCredentialsOnlyInChildProcesses() = runTest {
        val calls = Calls { environment() }
        val shell = Shell(mutableSetOf("/workspace/api"))
        assertTrue(EnvironmentPreparation(calls, shell, sleep = {}).run())
        val commands = shell.commands()
        assertEquals(listOf("git", "config", "--global", "user.name", "octo"), commands[1])
        assertEquals(listOf("git", "clone", "--", "https://github.com/example/api.git", "/workspace/.teamcode/clone-0"), commands[4])
        assertEquals(listOf("git", "checkout", "--quiet", "main", "--"), commands[5]); assertEquals("/workspace/.teamcode/clone-0", shell.calls.first { it.arguments == commands[5] }.directory)
        assertEquals(listOf("git", "switch", "--quiet", "-c", "task-1"), commands[6])
        assertEquals(listOf("/workspace/.teamcode/clone-0", "/workspace/api"), commands[7].takeLast(2))
        val setup = shell.calls.filter { it.arguments.take(2) == listOf("/bin/sh", "-c") && it.arguments.size == 3 }
        assertEquals(listOf("npm ci", "npm run db:migrate"), setup.map { it.arguments[2] })
        assertTrue(setup.all { it.directory == "/workspace/api" }, "Setup runs in the working directory")
        assertEquals(listOf("configuring_git", "cloning_repositories", "running_setup"), calls.phases)
        for (call in shell.calls) {
            assertEquals(token, call.environment["GH_TOKEN"]); assertEquals("npm_synthetic_value", call.environment["NPM_TOKEN"])
            assertTrue(call.environment.containsKey("TEAMCODE_CLOUD_TOKEN_FILE") && call.environment["TEAMCODE_CLOUD_TOKEN_FILE"] == null, "Children never see the runner token path")
            assertEquals(setOf(token, "npm_synthetic_value"), call.secrets, "Known secrets are masked in the log")
            assertTrue(call.arguments.none { token in it }, "The token is never an argument, a remote URL or a config value")
        }
        val helper = shell.calls.first().environment
        assertEquals("credential.https://github.com.helper", helper["GIT_CONFIG_KEY_1"])
        assertEquals("!f() { test \"\$1\" = get && printf 'username=x-access-token\\npassword=%s\\n' \"\$TEAMCODE_GITHUB_TOKEN\"; }; f", helper["GIT_CONFIG_VALUE_1"])
        assertEquals("", helper["GIT_CONFIG_VALUE_0"], "Helpers from the image are reset first")
    }

    @Test fun resumeOnlyFetchesAndSkipsCompletedCreateSteps() = runTest {
        val shell = Shell(mutableSetOf("/workspace/api"))
        assertTrue(EnvironmentPreparation(Calls { environment() }, shell, sleep = {}).run())
        val calls = Calls { environment(RunnerGitHubView("not_linked")) }
        val resumed = Shell(shell.existing)
        assertTrue(EnvironmentPreparation(calls, resumed, sleep = {}).run())
        val commands = resumed.commands()
        assertTrue(commands.none { it.first() == "rm" || it.getOrNull(1) == "clone" || it.getOrNull(1) == "checkout" }, "Work on the workspace is never re-cloned or reset")
        assertTrue(listOf("git", "fetch", "--prune", "origin") in commands)
        assertEquals(listOf("npm run db:migrate"), resumed.calls.filter { it.arguments.size == 3 && it.arguments[0] == "/bin/sh" }.map { it.arguments[2] }, "Create steps ran once")
        assertNull(resumed.calls.first().environment["GH_TOKEN"], "Without a linked account no GitHub credential is configured")
        assertNull(resumed.calls.first().environment["GIT_CONFIG_COUNT"])
        assertEquals(listOf("configuring_git", "fetching_repositories", "running_setup"), calls.phases)
    }

    @Test fun aFailedStepIsReportedAsFieldsAndNothingRunsAfterIt() = runTest {
        val calls = Calls { environment() }
        val shell = Shell { if (it.getOrNull(1) == "clone") 128 else 0 }
        assertFalse(EnvironmentPreparation(calls, shell, sleep = {}).run())
        assertEquals(listOf(RunnerPreparationFailureView("clone", 0, 128)), calls.failures)
        assertTrue(shell.commands().none { it.getOrNull(1) == "-c" && it.size == 3 }, "No setup command runs after a failed clone")

        val setup = Calls { environment() }
        assertFalse(EnvironmentPreparation(setup, Shell(mutableSetOf("/workspace/api")) { if (it.lastOrNull() == "npm ci") 1 else 0 }, sleep = {}).run())
        assertEquals(listOf(RunnerPreparationFailureView("setup_create", 0, 1)), setup.failures)

        val missing = Calls { environment() }
        assertFalse(EnvironmentPreparation(missing, Shell(), sleep = {}).run(), "The working directory must exist")
        assertEquals("working_directory", missing.failures.single().step)
    }

    @Test fun ownerVariablesNeverSteerTheRunnersOwnCommands() = runTest {
        val hostile = RunnerEnvironmentView(recipe, RunnerGitIdentityView("o", "o@example.test"),
            mapOf("HOME" to "/tmp/x", "PATH" to "/tmp/bin", "LD_PRELOAD" to "/tmp/x.so", "GIT_CONFIG_COUNT" to "1", "GIT_CONFIG_KEY_0" to "core.sshCommand", "BAD=NAME" to "x", "NPM_TOKEN" to "kept-value"),
            emptyList(), RunnerGitHubView("not_linked"))
        val shell = Shell(mutableSetOf("/workspace/api"))
        assertTrue(EnvironmentPreparation(Calls { hostile }, shell, sleep = {}).run())
        val environment = shell.calls.first().environment
        assertEquals("kept-value", environment["NPM_TOKEN"])
        for (name in listOf("HOME", "PATH", "LD_PRELOAD", "GIT_CONFIG_COUNT", "GIT_CONFIG_KEY_0", "BAD=NAME")) assertFalse(name in environment, name)
    }

    @Test fun aRunnerErrorIsReportedInsteadOfWaitingForTheTimeout() = runTest {
        val calls = Calls { environment() }
        assertFalse(EnvironmentPreparation(calls, { _, _, _, _ -> throw okio.IOException("No space left on device") }, sleep = {}).run())
        assertEquals(listOf(RunnerPreparationFailureView("runner")), calls.failures)
    }

    @Test fun progressOutagesDoNotStopThePreparation() = runTest {
        var reports = 0
        val calls = object : PreparationCalls {
            override suspend fun environment(): RunnerEnvironmentView = this@CloudPreparationTest.environment()
            override suspend fun progress(phase: String, failure: RunnerPreparationFailureView?): RunnerInstructionView {
                reports++; throw TeamCodeException(if (reports == 1) 401 else 408, "transient")
            }
        }
        assertTrue(EnvironmentPreparation(calls, Shell(mutableSetOf("/workspace/api")), sleep = {}).run())
    }

    @Test fun aRefusedEnvironmentStopsAndOutagesAreRetried() = runTest {
        val refused = Calls { throw TeamCodeException(409, "failed", "preparation_failed") }
        assertFalse(EnvironmentPreparation(refused, Shell(), sleep = {}).run())
        var attempts = 0
        val flaky = Calls { if (attempts++ < 2) throw IllegalStateException("unreachable") else environment() }
        val sleeps = mutableListOf<Long>()
        assertTrue(EnvironmentPreparation(flaky, Shell(mutableSetOf("/workspace/api")), sleep = { sleeps += it }).run())
        assertEquals(listOf(1_000L, 2_000L), sleeps)
    }

    @Test fun theRunnerReportsReadyOnlyAfterPreparationAndKeepsHeartbeating() = runTest {
        val done = CompletableDeferred<Boolean>()
        val heartbeats = mutableListOf<Boolean>()
        var count = 0
        val calls = object : RunnerCalls {
            override suspend fun register() = RunnerInstructionView("running", null, 1)
            override suspend fun heartbeat(ready: Boolean): RunnerInstructionView {
                heartbeats += ready
                if (++count == 2) done.complete(true)
                if (count == 4) throw TeamCodeException(409, "fenced", "execution_fenced")
                return RunnerInstructionView("running", null, 1)
            }
            override suspend fun checkpoint(completed: Boolean) = error("unused")
        }
        assertEquals(0, RunnerLoop(calls, sleep = {}, prepare = { done.await() }).run())
        assertEquals(listOf(false, false, true, true), heartbeats, "Heartbeats continue during preparation; ready only once it succeeded")

        val failed = mutableListOf<Boolean>()
        var n = 0
        val paused = object : RunnerCalls {
            override suspend fun register() = RunnerInstructionView("running", null, 1)
            override suspend fun heartbeat(ready: Boolean): RunnerInstructionView {
                failed += ready
                if (++n == 3) throw TeamCodeException(409, "fenced", "execution_fenced")
                return RunnerInstructionView("paused", 0, 1)
            }
            override suspend fun checkpoint(completed: Boolean) = error("A runner that never prepared has nothing to checkpoint")
        }
        assertEquals(0, RunnerLoop(paused, sleep = {}, prepare = { false }).run())
        assertEquals(listOf(false, false, false), failed)
    }

    @Test fun repositoryOptionsParse() {
        assertEquals(CloudRepositoryView("https://github.com/example/api.git", "api", "main"), parseRepository("https://github.com/example/api.git#main"))
        assertEquals(CloudRepositoryView("https://github.com/example/web", "apps/web", null), parseRepository("apps/web=https://github.com/example/web"))
        assertFailsWith<IllegalArgumentException> { parseRepository("git@github.com:example/api.git") }
    }
}
