package me.nathanfallet.teamcode.cli.cloud.agent

import com.github.ajalt.clikt.command.SuspendingCliktCommand
import com.github.ajalt.clikt.command.SuspendingNoOpCliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import me.nathanfallet.teamcode.cli.launch.AgentEnvironment
import me.nathanfallet.teamcode.cli.SystemExit
import okio.FileSystem
import okio.Path.Companion.toPath
import me.nathanfallet.teamcode.cli.cloud.PreparationLogFile
import me.nathanfallet.teamcode.cli.cloud.untilTerminated
import me.nathanfallet.teamcode.cli.platform.environment
import me.nathanfallet.teamcode.cli.platform.installServiceSignalHandlers
import me.nathanfallet.teamcode.cli.platform.listenUnix
import me.nathanfallet.teamcode.cli.platform.startAgentProcess

/** `teamcode cloud agent-supervisor`: the entrypoint of the pod's agent container. */
internal class CloudAgentSupervisor : SuspendingCliktCommand(name = "agent-supervisor") {
    override val hiddenFromHelp = true
    override fun help(context: Context) = "Run the in-pod exec supervisor (started by the cluster, not by hand)."
    override suspend fun run() {
        val socket = requireNotNull(environment("TEAMCODE_AGENT_SOCKET")?.takeIf { it.isNotBlank() }) { "Set TEAMCODE_AGENT_SOCKET." }
        val directory = environment("TEAMCODE_CLOUD_WORKDIR")?.takeIf { it.isNotBlank() } ?: "/workspace"
        // HOME is on the workspace volume and belongs to this UID, which creates it.
        environment("HOME")?.takeIf { it.isNotBlank() }?.let { runCatching { FileSystem.SYSTEM.createDirectories(it.toPath()) } }
        val supervisor = AgentSupervisor(listenUnix(socket), { command, arguments, overrides, cwd -> startAgentProcess(command, arguments, overrides, cwd) }, directory,
            log = { echo(it, err = true) })
        // PID 1 of the agent container: without a handler SIGTERM is ignored and the pod waits for its grace period.
        installServiceSignalHandlers()
        // Stopped by a signal, the supervisor's connections stop the processes they started on the way out.
        val code = untilTerminated({ echo(it, err = true) }) { supervisor.serve() }
        if (code != 0) SystemExit.exit(code)
    }
}

/**
 * `teamcode cloud credential get|store|erase`: git's credential-helper protocol, served by the runner over the pod's
 * socket. Without a socket or a credential it prints nothing and succeeds, so git falls through to its next helper.
 */
internal class CloudCredential : SuspendingNoOpCliktCommand(name = "credential") {
    override val hiddenFromHelp = true
    override fun help(context: Context) = "git credential helper backed by the session runner."
    init { subcommands(CredentialGet(), CredentialIgnore("store"), CredentialIgnore("erase")) }
}

private class CredentialGet : SuspendingCliktCommand(name = "get") {
    override fun help(context: Context) = "Print the credential for the remote git describes on standard input."
    override suspend fun run() {
        val socket = environment("TEAMCODE_CREDENTIAL_SOCKET")?.takeIf { it.isNotBlank() } ?: return
        val request = parseCredentialInput(generateSequence(::readlnOrNull))
        // Only a remote git named: a request without a host is never forwarded.
        if (request.host.isNullOrBlank()) return
        echo(formatCredentialOutput(requestCredential(socket, request)), trailingNewline = false)
    }
}

/** `store` and `erase` do nothing: the runner owns the tokens and renews them itself; git must not keep a copy. */
private class CredentialIgnore(name: String) : SuspendingCliktCommand(name = name) {
    override fun help(context: Context) = "Ignored: the runner holds the tokens."
    override suspend fun run() { generateSequence(::readlnOrNull).count() }
}

/**
 * `teamcode cloud environment --socket PATH`: prints shell code setting the session owner's current environment
 * variables, asked of the runner. The agent's `CLAUDE_ENV_FILE` evaluates it before each Bash command. Without an
 * answer it prints nothing and succeeds, so the command runs with what it already has.
 */
internal class CloudEnvironment : SuspendingCliktCommand(name = "environment") {
    override val hiddenFromHelp = true
    override fun help(context: Context) = "Print the session owner's environment variables as shell code (run by the agent's shell, not by hand)."
    private val socket by option("--socket", help = "The runner's environment socket.").required()
    override suspend fun run() {
        val answer = requestEnvironment(socket) ?: return
        echo(AgentEnvironment.script(answer.variables, answer.withdrawn.toSet(), answer.secrets), trailingNewline = false)
    }
}

/**
 * `teamcode cloud agent-state --socket PATH`: the runner's `Stop` hook in a Claude Code session. Reads the hook input on
 * standard input (at most 8 MiB), hands the number of scheduled wake-ups to the runner ([reportWakeups]) and always exits
 * 0 without output, so it never changes the agent's turn. The rest of the input is dropped.
 */
internal class CloudAgentState : SuspendingCliktCommand(name = "agent-state") {
    override val hiddenFromHelp = true
    override fun help(context: Context) = "Report the agent's scheduled wake-ups to the session runner (run by the agent's Stop hook, not by hand)."
    private val socket by option("--socket", help = "The runner's environment socket.").required()
    override suspend fun run() {
        val input = StringBuilder()
        for (line in generateSequence(::readlnOrNull)) {
            if (input.length + line.length > MAX_INPUT_CHARS) break
            input.append(line).append('\n')
        }
        reportWakeups(socket, scheduledWakeups(input.toString()))
    }
    private companion object { const val MAX_INPUT_CHARS = 8 * 1_048_576 }
}

/**
 * `teamcode cloud preparation-log FILE`: appends the lines of standard input to the preparation log, keeping its last
 * 4 MiB. The runner starts it in the agent's container, so the file on the workspace is opened by the agent's UID and
 * never by the runner, and sends it the output of each preparation command with known secrets already masked.
 */
internal class CloudPreparationLog : SuspendingCliktCommand(name = "preparation-log") {
    override val hiddenFromHelp = true
    override fun help(context: Context) = "Append standard input to the preparation log (started by the runner, not by hand)."
    private val file by argument("FILE")
    override suspend fun run() {
        val log = PreparationLogFile(file)
        try { generateSequence(::readlnOrNull).forEach(log::append) } finally { log.close() }
    }
}
