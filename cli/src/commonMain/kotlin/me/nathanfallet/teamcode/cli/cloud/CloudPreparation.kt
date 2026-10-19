package me.nathanfallet.teamcode.cli.cloud

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import me.nathanfallet.teamcode.api.models.*
import me.nathanfallet.teamcode.cli.platform.runInteractiveProcess
import me.nathanfallet.teamcode.client.TeamCodeException
import okio.ByteString.Companion.encodeUtf8
import okio.FileSystem
import okio.Path.Companion.toPath

/** The preparation calls of the runner channel, bound to one session epoch. */
internal interface PreparationCalls {
    suspend fun environment(): RunnerEnvironmentView
    suspend fun progress(phase: String, failure: RunnerPreparationFailureView? = null): RunnerInstructionView
}

/** Runs one command for the preparation; output goes to the preparation log, never to the server. */
internal fun interface CommandRunner {
    /**
     * Runs [arguments] in [directory] with [environment] applied over the runner's own (a null value removes the
     * variable) and returns the exit code. [secrets] are masked in the log.
     */
    suspend fun run(arguments: List<String>, directory: String, environment: Map<String, String?>, secrets: Set<String>): Int
}

/**
 * Prepares the session environment inside the pod before the runner reports ready:
 *
 * 1. the owner's Git identity (rewritten on every execution, so a new owner commits as themselves);
 * 2. each repository cloned on the first execution (into a staging directory, then moved in place, so an
 *    interrupted clone is retried rather than mistaken for a workspace) and only fetched afterwards: work on the
 *    workspace is never reset or re-cloned;
 * 3. the working directory check;
 * 4. `create` setup commands once per workspace (a marker per command on the workspace), then `start` commands on
 *    every execution.
 *
 * Variables and the GitHub token reach these child processes only, through their environment: never the pod spec,
 * a remote URL, a Git config file or the workspace. Git gets the token from a credential helper scoped to
 * `https://github.com`; `gh` reads `GH_TOKEN`. A failure is reported as a step, an index and an exit code; the
 * session then stays unready and the server fails it or blocks the resume.
 */
internal class EnvironmentPreparation(
    private val calls: PreparationCalls,
    private val commands: CommandRunner,
    private val workspace: String = WORKSPACE,
    private val sleep: suspend (Long) -> Unit = { delay(it) },
    private val log: (String) -> Unit = {},
) {
    private class Failed(val failure: RunnerPreparationFailureView) : Exception()
    private class Stopped : Exception()

    /** True once the environment is prepared; false when preparation failed or the server refused it. */
    suspend fun run(): Boolean {
        val prepared = fetch() ?: return false
        return try {
            prepare(prepared)
            true
        } catch (failed: Failed) {
            log("Preparation failed at ${failed.failure.step}" + (failed.failure.index?.let { " ${it + 1}" } ?: "") + (failed.failure.exitCode?.let { " (exit code $it)" } ?: "") + ".")
            report(failed.failure)
            false
        } catch (_: Stopped) { false }
    }

    private suspend fun prepare(prepared: RunnerEnvironmentView) {
        val spec = prepared.environment
        val token = prepared.github.token?.takeIf { prepared.github.status == "linked" }
        val secrets = (prepared.variables.values + listOfNotNull(token)).filter { it.length >= MIN_MASKED }.toSet()
        val environment = buildMap<String, String?> {
            putAll(prepared.variables)
            // Children never learn where the runner's own token is, and Git never prompts.
            put("TEAMCODE_CLOUD_TOKEN_FILE", null)
            put("GIT_TERMINAL_PROMPT", "0")
            if (token != null) {
                put("GH_TOKEN", token)
                put(TOKEN_VARIABLE, token)
                // An empty helper first resets helpers from the image, then ours answers for github.com only.
                put("GIT_CONFIG_COUNT", "2")
                put("GIT_CONFIG_KEY_0", "credential.https://github.com.helper"); put("GIT_CONFIG_VALUE_0", "")
                put("GIT_CONFIG_KEY_1", "credential.https://github.com.helper"); put("GIT_CONFIG_VALUE_1", CREDENTIAL_HELPER)
            }
        }
        suspend fun run(step: String, index: Int?, directory: String, vararg arguments: String) {
            val code = commands.run(arguments.toList(), directory, environment, secrets)
            if (code != 0) throw Failed(RunnerPreparationFailureView(step, index, code.coerceIn(0, 255)))
        }
        suspend fun succeeds(vararg arguments: String) = commands.run(arguments.toList(), workspace, environment, secrets) == 0

        phase("configuring_git")
        run("git_identity", null, workspace, "mkdir", "-p", "$workspace/.home", "$workspace/$STATE_DIR/setup")
        run("git_identity", null, workspace, "git", "config", "--global", "user.name", prepared.git.name)
        run("git_identity", null, workspace, "git", "config", "--global", "user.email", prepared.git.email)

        for ((index, repository) in spec.repositories.withIndex()) {
            val target = "$workspace/${repository.path.trimEnd('/')}"
            if (succeeds("test", "-e", "$target/.git")) {
                phase("fetching_repositories")
                run("fetch", index, target, "git", "fetch", "--prune", "origin")
                continue
            }
            phase("cloning_repositories")
            val staging = "$workspace/$STATE_DIR/clone-$index"
            run("clone", index, workspace, "rm", "-rf", staging)
            run("clone", index, workspace, "git", "clone", "--", repository.url, staging)
            repository.ref?.let { run("checkout", index, staging, "git", "checkout", "--quiet", it, "--") }
            repository.branch?.let { run("checkout", index, staging, "git", "switch", "--quiet", "-c", it) }
            // Move in place; an existing empty directory is replaced, anything else is never overwritten.
            run("clone", index, workspace, "/bin/sh", "-c", MOVE_INTO_PLACE, "sh", staging, target)
        }

        val directory = spec.workingDirectory?.let { "$workspace/${it.trimEnd('/')}" } ?: workspace
        if (!succeeds("test", "-d", directory)) throw Failed(RunnerPreparationFailureView("working_directory"))

        for ((index, command) in spec.setup.create.withIndex()) {
            val marker = "$workspace/$STATE_DIR/setup/create-$index-${command.encodeUtf8().sha256().hex().take(16)}.done"
            if (succeeds("test", "-e", marker)) continue
            phase("running_setup")
            run("setup_create", index, directory, "/bin/sh", "-c", command)
            run("setup_create", index, workspace, "touch", marker)
        }
        for ((index, command) in spec.setup.start.withIndex()) {
            phase("running_setup")
            run("setup_start", index, directory, "/bin/sh", "-c", command)
        }
    }

    private var reported: String? = null
    /** Progress is informational: a failed report never stops the preparation, but fencing does. */
    private suspend fun phase(phase: String) {
        if (reported == phase) return
        reported = phase
        try { calls.progress(phase) }
        catch (error: CancellationException) { throw error }
        catch (error: TeamCodeException) { if (error.refusal) throw Stopped() }
        catch (_: Exception) { log("Could not report preparation progress; continuing.") }
    }

    private suspend fun fetch(): RunnerEnvironmentView? = retry { calls.environment() }
    private suspend fun report(failure: RunnerPreparationFailureView) { retry { calls.progress(reported ?: "configuring_git", failure) } }

    /** Retries network failures and server errors with backoff; a refusal (fenced, not preparing, failed) stops. */
    private suspend fun <T> retry(call: suspend () -> T): T? {
        var wait = 1_000L
        while (true) {
            try { return call() }
            catch (error: CancellationException) { throw error }
            catch (error: TeamCodeException) {
                if (error.refusal) { log("The server refused the preparation (${error.code ?: "HTTP ${error.statusCode}"})."); return null }
                log("The server returned HTTP ${error.statusCode}; retrying.")
            } catch (_: Exception) { log("The server is unreachable; retrying.") }
            sleep(wait)
            wait = (wait * 2).coerceAtMost(RunnerLoop.HEARTBEAT_MILLIS)
        }
    }

    /** Fencing, an ended preparation or a rejected request; a concurrent write (`session_busy`) is retried instead. */
    private val TeamCodeException.refusal get() = statusCode in 400..499 && !(statusCode == 409 && code == "session_busy") && statusCode != 429

    companion object {
        const val WORKSPACE = "/workspace"
        /** Runner-owned state on the workspace: clone staging, setup markers and preparation logs. */
        const val STATE_DIR = ".teamcode"
        const val TOKEN_VARIABLE = "TEAMCODE_GITHUB_TOKEN"
        private const val MIN_MASKED = 4
        /** Answers `get` with the token from its own environment; `store` and `erase` do nothing. */
        const val CREDENTIAL_HELPER = "!f() { test \"\$1\" = get && printf 'username=x-access-token\\npassword=%s\\n' \"\$$TOKEN_VARIABLE\"; }; f"
        private const val MOVE_INTO_PLACE = "mkdir -p \"\$(dirname \"\$2\")\" && { [ ! -e \"\$2\" ] || rmdir \"\$2\"; } && mv \"\$1\" \"\$2\""
        fun logFile(workspace: String, epoch: Int) = "$workspace/$STATE_DIR/logs/prepare-e$epoch.log"
    }
}

/**
 * [CommandRunner] for the pod: `/bin/sh` changes into the directory and appends the command's output to [logFile]
 * (stdin closed), and known secrets are masked in that log after each command.
 */
internal class ShellCommandRunner(private val logFile: String, private val files: FileSystem = FileSystem.SYSTEM) : CommandRunner {
    override suspend fun run(arguments: List<String>, directory: String, environment: Map<String, String?>, secrets: Set<String>): Int {
        val log = logFile.toPath()
        log.parent?.let(files::createDirectories)
        val code = try {
            runInteractiveProcess("/bin/sh", listOf("-c", WRAPPER, "sh", directory, logFile) + arguments, environment, quiet = true)
        } catch (error: CancellationException) { throw error } catch (_: Exception) { 127 }
        mask(secrets)
        return code
    }

    private fun mask(secrets: Set<String>) {
        if (secrets.isEmpty()) return
        val path = logFile.toPath()
        val size = files.metadataOrNull(path)?.size ?: return
        // Keep the log bounded: a long dependency install keeps only its last part.
        var text = files.read(path) { if (size > MAX_LOG_BYTES) skip(size - MAX_LOG_BYTES); readUtf8() }
        val masked = secrets.fold(text) { current, secret -> current.replace(secret, "[REDACTED]") }
        if (masked != text || size > MAX_LOG_BYTES) { text = masked; files.write(path) { writeUtf8(text) } }
    }

    private companion object {
        const val WRAPPER = "cd -- \"\$1\" || exit 125; log=\$2; shift 2; exec \"\$@\" >>\"\$log\" 2>&1 </dev/null"
        const val MAX_LOG_BYTES = 4L * 1024 * 1024
    }
}
