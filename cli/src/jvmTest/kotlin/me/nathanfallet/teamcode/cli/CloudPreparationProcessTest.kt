package me.nathanfallet.teamcode.cli

import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import me.nathanfallet.teamcode.api.models.*
import me.nathanfallet.teamcode.cli.cloud.*
import kotlin.io.path.*
import kotlin.test.*

/** Real `/bin/sh` and `git` against a local repository: the shell wrapper, staging clone, markers and log masking. */
class CloudPreparationProcessTest {
    private fun git(directory: java.nio.file.Path, vararg arguments: String) {
        val process = ProcessBuilder(listOf("git", "-c", "user.name=t", "-c", "user.email=t@example.test") + arguments).directory(directory.toFile()).redirectErrorStream(true).start()
        process.inputStream.readAllBytes()
        assertEquals(0, process.waitFor(), "git ${arguments.joinToString(" ")}")
    }

    @Test fun preparesAWorkspaceTwiceWithoutRecloning() = runBlocking {
        val root = Files.createTempDirectory("teamcode-preparation-")
        try {
            val origin = root.resolve("origin").createDirectories()
            git(origin, "init", "-q", "-b", "main"); git(origin, "commit", "-q", "--allow-empty", "-m", "one")
            git(origin, "switch", "-q", "-c", "release"); git(origin, "commit", "-q", "--allow-empty", "-m", "two"); git(origin, "switch", "-q", "main")
            val workspace = root.resolve("workspace").createDirectories()
            workspace.resolve("app").createDirectories() // An empty directory (for example a mount point) is replaced.
            val secret = "synthetic-variable-value-0123"
            val recipe = CloudEnvironmentView(listOf(CloudRepositoryView("file://$origin", "app", "release", "task-7")), "app",
                CloudSetupView(create = listOf("echo created >> ../created.txt"), start = listOf("echo \"value=\$SAMPLE_SECRET\"; pwd")))
            val prepared = RunnerEnvironmentView(recipe, RunnerGitIdentityView("Owner Name", "owner@example.test"), mapOf("SAMPLE_SECRET" to secret), emptyList(), RunnerGitHubView("not_linked"))
            val calls = object : PreparationCalls {
                val failures = mutableListOf<RunnerPreparationFailureView>()
                override suspend fun environment() = prepared
                override suspend fun progress(phase: String, failure: RunnerPreparationFailureView?): RunnerInstructionView {
                    failure?.let { failures += it }; return RunnerInstructionView("running", null, 1)
                }
            }
            // HOME points into the workspace like in the pod, so `git config --global` writes there.
            val home = mapOf("HOME" to "$workspace/.home", "GIT_CONFIG_NOSYSTEM" to "1")
            val shell = ShellCommandRunner(EnvironmentPreparation.logFile(workspace.toString(), 1))
            val commands = CommandRunner { arguments, directory, environment, secrets -> shell.run(arguments, directory, environment + home, secrets) }
            assertTrue(EnvironmentPreparation(calls, commands, workspace.toString(), sleep = {}).run(), "Failures: ${calls.failures}")
            assertTrue(workspace.resolve("app/.git").isDirectory())
            assertTrue(workspace.resolve(".home/.gitconfig").readText().contains("Owner Name"))
            val head = ProcessBuilder("git", "rev-parse", "--abbrev-ref", "HEAD").directory(workspace.resolve("app").toFile()).start().inputStream.readAllBytes().decodeToString().trim()
            assertEquals("task-7", head)
            // Work in progress survives a second preparation (a resume): fetched, never re-cloned or reset.
            workspace.resolve("app/work.txt").writeText("unsaved work")
            assertTrue(EnvironmentPreparation(calls, commands, workspace.toString(), sleep = {}).run())
            assertEquals("unsaved work", workspace.resolve("app/work.txt").readText())
            assertEquals("created\n", workspace.resolve("created.txt").readText(), "Create steps run once per workspace")
            val log = Path(EnvironmentPreparation.logFile(workspace.toString(), 1)).readText()
            assertFalse(secret in log, "Variable values are masked in the preparation log")
            assertTrue("value=[REDACTED]" in log && "$workspace/app" in log)
            // A failing start command is reported with its exit code.
            val failing = RunnerEnvironmentView(recipe.copy(setup = CloudSetupView(start = listOf("exit 3"))), prepared.git, emptyMap(), emptyList(), prepared.github)
            val failed = object : PreparationCalls by calls { override suspend fun environment() = failing }
            assertFalse(EnvironmentPreparation(failed, commands, workspace.toString(), sleep = {}).run())
            assertEquals(RunnerPreparationFailureView("setup_start", 0, 3), calls.failures.last())
            // Without any secret the log is still bounded.
            val chatty = RunnerEnvironmentView(recipe.copy(setup = CloudSetupView(start = listOf("head -c 6000000 /dev/zero | tr '\\0' x"))), prepared.git, emptyMap(), emptyList(), prepared.github)
            assertTrue(EnvironmentPreparation(object : PreparationCalls by calls { override suspend fun environment() = chatty }, commands, workspace.toString(), sleep = {}).run())
            assertTrue(Path(EnvironmentPreparation.logFile(workspace.toString(), 1)).fileSize() <= 4L * 1024 * 1024)
        } finally { root.toFile().deleteRecursively() }
    }
}
