package me.nathanfallet.teamcode.cli.cloud

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import me.nathanfallet.teamcode.api.models.*
import me.nathanfallet.teamcode.cli.cloud.agent.CLOUD_AGENT_HOME_NAME
import me.nathanfallet.teamcode.cli.platform.AgentProcess
import me.nathanfallet.teamcode.cli.platform.runInteractiveProcess
import me.nathanfallet.teamcode.client.TeamCodeException
import okio.ByteString.Companion.encodeUtf8
import okio.FileSystem
import okio.Path.Companion.toPath
import okio.buffer

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
 * 0. on a workspace another member worked on since the owner last prepared it (a transfer,
 *    [RunnerEnvironmentView.untrustedWorkspace]), the agent's home is moved to a quarantine directory, keeping only
 *    the agents' conversation transcripts, before anything runs with the new owner's credentials;
 * 1. the owner's Git identity (rewritten on every execution, so a new owner commits as themselves);
 * 2. each repository cloned on the first execution (into a staging directory, then moved in place, so an
 *    interrupted clone is retried rather than mistaken for a workspace) and only fetched afterwards: work on the
 *    workspace is never reset or re-cloned. On an untrusted workspace each repository's `.git/config`, worktree
 *    config and hooks are moved to the quarantine first and the config is rebuilt from the recipe's URL;
 * 3. the working directory check;
 * 4. `create` setup commands once per workspace (a marker per command on the workspace), then `start` commands on
 *    every execution.
 *
 * Variables and the GitHub token reach these child processes only, through their environment: never the pod spec,
 * a remote URL, a Git config file or the workspace. Git gets the token from a credential helper scoped to
 * `https://github.com`; `gh` reads `GH_TOKEN`. Every `git` the preparation runs ignores hooks and fsmonitor, uses
 * HTTPS only, never recurses into submodules and asks no credential helper but its own. A failure is reported as a
 * step, an index and an exit code; the session then stays unready and the server fails it or blocks the resume.
 *
 * In a session pod the [commands] run in the agent's container ([SupervisedCommandRunner]), never in the runner's,
 * which holds the runner token: the workspace is written by the agent, so nothing on it may run where that token is.
 */
internal class EnvironmentPreparation(
    private val calls: PreparationCalls,
    private val commands: CommandRunner,
    private val workspace: String = WORKSPACE,
    private val sleep: suspend (Long) -> Unit = { delay(it) },
    private val log: (String) -> Unit = {},
    /** Transports `git` may use. The recipe only names HTTPS repositories; tests add `file`. */
    private val protocols: Set<String> = setOf("https"),
    /** The agent's `HOME`, as the session pod sets it in the agent's container. */
    private val agentHome: String = "$workspace/$CLOUD_AGENT_HOME_NAME",
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
        catch (error: CancellationException) { throw error }
        catch (_: Exception) {
            // The runner itself could not proceed (workspace unwritable, full volume): fail now rather than at the timeout.
            log("Preparation stopped on a runner error.")
            report(RunnerPreparationFailureView("runner"))
            false
        }
    }

    private suspend fun prepare(prepared: RunnerEnvironmentView) {
        val spec = prepared.environment
        val token = prepared.github.token?.takeIf { prepared.github.status == "linked" }
        val secrets = (prepared.variables.values + listOfNotNull(token)).filter { it.length >= MIN_MASKED }.toSet()
        val ignored = prepared.variables.keys.filter { !variableName.matches(it) || (it in RESERVED_VARIABLES || RESERVED_PREFIXES.any(it::startsWith)) }
        if (ignored.isNotEmpty()) log("Ignored variables that would change how preparation runs: ${ignored.sorted().joinToString(", ")}.")
        val environment = buildMap<String, String?> {
            // Owner values first, without names that steer the shell, the loader, Git or the runner; ours always win.
            putAll(prepared.variables - ignored.toSet())
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
        // Options that win over any configuration the workspace holds: no hook, no fsmonitor, no transport but ours, no
        // submodule, and no credential helper from the repository or the agent's home (ours is added back last).
        val hardened = buildList {
            add("git")
            listOf("core.hooksPath=/dev/null", "core.fsmonitor=false", "protocol.allow=never").forEach { add("-c"); add(it) }
            protocols.sorted().forEach { add("-c"); add("protocol.$it.allow=always") }
            listOf("fetch.recurseSubmodules=false", "submodule.recurse=false", "credential.helper=").forEach { add("-c"); add(it) }
            if (token != null) { add("-c"); add("credential.https://github.com.helper=$CREDENTIAL_HELPER") }
        }
        suspend fun git(step: String, index: Int?, directory: String, vararg arguments: String) = run(step, index, directory, *(hardened + arguments).toTypedArray())
        val quarantine = "$workspace/$STATE_DIR/$QUARANTINE_DIR"

        if (prepared.untrustedWorkspace) {
            // Before anything runs with this owner's credentials: settings, hooks, MCP servers, shell profiles and Git
            // configuration the former owner left in the agent's home never run as the new owner.
            phase("securing_workspace")
            run("workspace", null, workspace, "/bin/sh", "-c", RESET_HOME, "sh", agentHome, "$quarantine/agent-home")
        }

        phase("configuring_git")
        run("git_identity", null, workspace, "/bin/sh", "-c", "mkdir -p -- \"\$HOME\" \"\$1\"", "sh", "$workspace/$STATE_DIR/setup")
        run("git_identity", null, workspace, "git", "config", "--global", "user.name", prepared.git.name)
        run("git_identity", null, workspace, "git", "config", "--global", "user.email", prepared.git.email)

        for ((index, repository) in spec.repositories.withIndex()) {
            val target = "$workspace/${repository.path.trimEnd('/')}"
            if (succeeds("test", "-e", "$target/.git") || succeeds("test", "-L", "$target/.git")) {
                if (prepared.untrustedWorkspace) {
                    phase("securing_workspace")
                    run("workspace", index, target, "/bin/sh", "-c", RESET_REPOSITORY, "sh", "$quarantine/repository-$index", repository.url)
                }
                phase("fetching_repositories")
                git("fetch", index, target, "fetch", "--prune", "origin")
                continue
            }
            phase("cloning_repositories")
            val staging = "$workspace/$STATE_DIR/clone-$index"
            run("clone", index, workspace, "rm", "-rf", staging)
            git("clone", index, workspace, "clone", "--", repository.url, staging)
            repository.ref?.let { git("checkout", index, staging, "checkout", "--quiet", it, "--") }
            repository.branch?.let { git("checkout", index, staging, "switch", "--quiet", "-c", it) }
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
        catch (error: TeamCodeException) { if (error.code in STOP_CODES) throw Stopped() }
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

    /**
     * Fencing, an ended preparation or a rejected request. Timeouts, rate limits, concurrent writes and a `401`
     * during token rotation are retried; a real `401` also stops the heartbeats, which end the runner.
     */
    private val TeamCodeException.refusal get() = code in STOP_CODES || statusCode in setOf(400, 403, 404, 413, 422)

    companion object {
        const val WORKSPACE = "/workspace"
        /** Runner-owned state on the workspace: clone staging, setup markers and preparation logs. */
        const val STATE_DIR = ".teamcode"
        const val TOKEN_VARIABLE = "TEAMCODE_GITHUB_TOKEN"
        /** Below [STATE_DIR]: what an untrusted workspace's reset moved aside, replaced at the next reset. */
        const val QUARANTINE_DIR = "quarantine"
        /** The agent's conversation transcripts, kept across a reset of its home: data the agent reads, never runs. */
        val KEPT_AGENT_DATA = listOf(".claude/projects", ".codex/sessions")
        private const val MIN_MASKED = 4
        private val STOP_CODES = setOf("execution_fenced", "not_preparing", "preparation_failed")
        private val variableName = Regex("[A-Za-z_][A-Za-z0-9_]{0,127}")
        /** Names the runner's own commands depend on; an owner variable never overrides them. */
        private val RESERVED_VARIABLES = setOf("PATH", "HOME", "SHELL", "IFS", "ENV", "BASH_ENV", "XDG_CONFIG_HOME")
        /** Families that steer the loader, Git, the GitHub CLI, SSH or the runner itself. */
        private val RESERVED_PREFIXES = listOf("LD_", "DYLD_", "GIT_", "GH_", "TEAMCODE_", "SSH_")
        /** Answers `get` with the token from its own environment; `store` and `erase` do nothing. */
        const val CREDENTIAL_HELPER = "!f() { test \"\$1\" = get && printf 'username=x-access-token\\npassword=%s\\n' \"\$$TOKEN_VARIABLE\"; }; f"
        /**
         * `$1` the agent's home, `$2` its quarantine: the previous quarantine is removed, the home moved there whole (a
         * link is moved, never followed) and recreated empty, then the transcripts are moved back when they are plain
         * directories.
         */
        val RESET_HOME = "home=\$1; q=\$2; " +
            "rm -rf -- \"\$q\" && mkdir -p -- \"\$(dirname -- \"\$q\")\" || exit 1; " +
            "if [ -e \"\$home\" ] || [ -L \"\$home\" ]; then mv -- \"\$home\" \"\$q\" || exit 1; fi; " +
            "mkdir -p -- \"\$home\" || exit 1; " +
            "for keep in ${KEPT_AGENT_DATA.joinToString(" ")}; do parent=\${keep%/*}; " +
            "if [ -d \"\$q/\$keep\" ] && [ ! -L \"\$q/\$keep\" ] && [ ! -L \"\$q/\$parent\" ]; then " +
            "mkdir -p -- \"\$home/\$parent\" && mv -- \"\$q/\$keep\" \"\$home/\$keep\" || exit 1; fi; done"
        /**
         * In a repository, `$1` its quarantine, `$2` the recipe's URL. `.git` must be a plain directory without
         * `commondir` (exit 3 otherwise). Its `config`, `config.worktree` and `hooks` move to the quarantine; a new
         * `config` keeps the repository format and extensions and the branches' `origin` tracking, with `origin` set
         * to the recipe's URL and the standard refspec. Reading the old file with `--file` follows no `include`.
         */
        val RESET_REPOSITORY = "q=\$1; url=\$2; " +
            "{ [ -d .git ] && [ ! -L .git ] && [ ! -e .git/commondir ] && [ ! -L .git/commondir ]; } || exit 3; " +
            "rm -rf -- \"\$q\" && mkdir -p -- \"\$q\" || exit 1; " +
            "for f in config config.worktree hooks; do if [ -e \".git/\$f\" ] || [ -L \".git/\$f\" ]; then mv -- \".git/\$f\" \"\$q/\$f\" || exit 1; fi; done; " +
            "old=\$q/config; { [ -f \"\$old\" ] && [ ! -L \"\$old\" ]; } || old=/dev/null; new=.git/config; " +
            "v=\$(git config --file \"\$old\" --get core.repositoryformatversion) || v=0; case \"\$v\" in 0|1) ;; *) v=0 ;; esac; " +
            "git config --file \"\$new\" core.repositoryformatversion \"\$v\" && git config --file \"\$new\" core.bare false && " +
            "git config --file \"\$new\" core.logallrefupdates true && git config --file \"\$new\" remote.origin.url \"\$url\" && " +
            "git config --file \"\$new\" remote.origin.fetch '+refs/heads/*:refs/remotes/origin/*' || exit 1; " +
            "git config --file \"\$old\" --get-regexp '^extensions\\.' | while read -r key value; do " +
            "case \"\$key\$value\" in *[!A-Za-z0-9._-]*) exit 1 ;; esac; git config --file \"\$new\" \"\$key\" \"\$value\" || exit 1; done || exit 1; " +
            "git config --file \"\$old\" --get-regexp '^branch\\..*\\.(remote|merge)\$' | while read -r key value; do " +
            "case \"\$value\" in origin|refs/heads/*) ;; *) continue ;; esac; case \"\$key\$value\" in *[!A-Za-z0-9._/-]*) continue ;; esac; " +
            "git config --file \"\$new\" --add \"\$key\" \"\$value\" || exit 1; done || exit 1"
        private const val MOVE_INTO_PLACE = "mkdir -p \"\$(dirname \"\$2\")\" && { [ ! -e \"\$2\" ] || rmdir \"\$2\"; } && mv \"\$1\" \"\$2\""
        fun logFile(workspace: String, epoch: Int) = "$workspace/$STATE_DIR/logs/prepare-e$epoch.log"
    }
}

/**
 * [CommandRunner] without an agent container (a runner started by hand, tests): `/bin/sh` changes into the directory
 * and appends the command's output to [logFile] (stdin closed), and known secrets are masked in that log after each
 * command. A session pod uses [SupervisedCommandRunner] instead.
 */
internal class ShellCommandRunner(private val logFile: String, private val files: FileSystem = FileSystem.SYSTEM) : CommandRunner {
    override suspend fun run(arguments: List<String>, directory: String, environment: Map<String, String?>, secrets: Set<String>): Int {
        val log = logFile.toPath()
        log.parent?.let(files::createDirectories)
        val before = files.metadataOrNull(log)?.size ?: 0
        val code = try {
            runInteractiveProcess("/bin/sh", listOf("-c", WRAPPER, "sh", directory, logFile) + arguments, environment, quiet = true)
        } catch (error: CancellationException) { throw error } catch (_: Exception) { 127 }
        tidy(secrets, before)
        return code
    }

    /** Masks known secrets in what the command wrote and keeps the log bounded, replacing the file atomically. */
    private fun tidy(secrets: Set<String>, before: Long) {
        val path = logFile.toPath()
        val size = files.metadataOrNull(path)?.size ?: return
        if (size == before && size <= MAX_LOG_BYTES) return // Nothing written: nothing to mask or trim.
        if (secrets.isEmpty() && size <= MAX_LOG_BYTES) return
        val text = files.read(path) { if (size > MAX_LOG_BYTES) skip(size - MAX_LOG_BYTES); readUtf8() }
        val masked = secrets.fold(text) { current, secret -> current.replace(secret, "[REDACTED]") }
        if (masked == text && size <= MAX_LOG_BYTES) return
        val temporary = "$logFile.tmp".toPath()
        files.write(temporary) { writeUtf8(masked) }
        files.atomicMove(temporary, path)
    }

    private companion object {
        const val WRAPPER = "cd -- \"\$1\" || exit 125; log=\$2; shift 2; exec \"\$@\" >>\"\$log\" 2>&1 </dev/null"
        const val MAX_LOG_BYTES = 4L * 1024 * 1024
    }
}

/** Where a preparation command's output goes, line by line, already masked. */
internal interface PreparationLogSink {
    suspend fun append(line: String)
    suspend fun close()
}

/**
 * [CommandRunner] for a session pod: each command starts in the agent's container through the exec supervisor
 * ([start]), under the agent's UID, where the runner's projected token is not mounted, so nothing the agent wrote on
 * the workspace (a repository's Git configuration, a dependency's install script) ever runs beside that token. The
 * command's output (stdout and stderr) comes back over the supervisor's socket; the runner masks known secrets in
 * each line and hands it to [log], a writer that runs in the agent's container too: the runner never opens a file on
 * the workspace that the agent could have replaced by a link.
 */
internal class SupervisedCommandRunner(
    private val workspace: String,
    private val start: suspend (command: String, arguments: List<String>, environment: Map<String, String?>, directory: String) -> AgentProcess,
    private val log: suspend () -> PreparationLogSink?,
) : CommandRunner {
    override suspend fun run(arguments: List<String>, directory: String, environment: Map<String, String?>, secrets: Set<String>): Int {
        val sink = try { log() } catch (error: CancellationException) { throw error } catch (_: Exception) { null }
        try {
            val process = try { start("/bin/sh", listOf("-c", WRAPPER, "sh", directory) + arguments, environment, workspace) }
                catch (error: CancellationException) { throw error } catch (_: Exception) { return START_FAILED }
            try {
                process.closeInput()
                while (true) {
                    val line = process.readLine() ?: break
                    sink?.append(secrets.fold(line) { current, secret -> current.replace(secret, "[REDACTED]") })
                }
                return process.exitCode() ?: process.stop(EXIT_GRACE_MILLIS)
            } finally {
                withContext(NonCancellable) { if (process.exitCode() == null) process.stop(EXIT_GRACE_MILLIS) }
            }
        } finally {
            withContext(NonCancellable) { try { sink?.close() } catch (_: Exception) {} }
        }
    }

    private companion object {
        /** The posix process prologue changes into the workspace; this one into the command's directory. */
        const val WRAPPER = "cd -- \"\$1\" || exit 125; shift; exec \"\$@\" 2>&1 </dev/null"
        const val START_FAILED = 127
        const val EXIT_GRACE_MILLIS = 5_000L
    }
}

/** A [PreparationLogSink] backed by `teamcode cloud preparation-log`, started in the agent's container. */
internal class ProcessLogSink(private val process: AgentProcess) : PreparationLogSink {
    override suspend fun append(line: String) { process.writeLine(line) }
    override suspend fun close() {
        process.closeInput()
        while (process.readLine() != null) Unit
        if (process.exitCode() == null) process.stop(CLOSE_GRACE_MILLIS)
    }
    private companion object { const val CLOSE_GRACE_MILLIS = 5_000L }
}

/**
 * The preparation log file, written by `teamcode cloud preparation-log` under the agent's UID: lines are appended
 * and the file keeps its last [maxBytes] (trimmed when it reaches twice that, and on [close]), rewritten atomically.
 */
internal class PreparationLogFile(private val path: String, private val files: FileSystem = FileSystem.SYSTEM, private val maxBytes: Long = MAX_LOG_BYTES) {
    private val file = path.toPath()
    private var sink: okio.BufferedSink? = null
    private var size = 0L

    fun append(line: String) {
        val open = sink ?: run {
            file.parent?.let(files::createDirectories)
            size = files.metadataOrNull(file)?.size ?: 0
            files.appendingSink(file, mustExist = false).buffer().also { sink = it }
        }
        val bytes = line.encodeUtf8()
        open.write(bytes).writeByte('\n'.code)
        size += bytes.size + 1
        if (size >= 2 * maxBytes) trim()
    }

    fun close() { trim() }

    private fun trim() {
        sink?.let { it.flush(); it.close() }; sink = null
        val current = files.metadataOrNull(file)?.size ?: return
        size = current
        if (current <= maxBytes) return
        val temporary = "$path.tmp".toPath()
        files.read(file) { val source = this; skip(current - maxBytes); files.write(temporary) { writeAll(source) } }
        files.atomicMove(temporary, file)
        size = maxBytes
    }

    companion object { const val MAX_LOG_BYTES = 4L * 1024 * 1024 }
}
