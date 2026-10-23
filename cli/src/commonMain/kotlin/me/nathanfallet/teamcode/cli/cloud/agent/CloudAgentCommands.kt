package me.nathanfallet.teamcode.cli.cloud.agent

import com.github.ajalt.clikt.command.SuspendingCliktCommand
import com.github.ajalt.clikt.command.SuspendingNoOpCliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import me.nathanfallet.teamcode.cli.launch.AgentEnvironment
import me.nathanfallet.teamcode.cli.SystemExit
import okio.FileSystem
import okio.Path.Companion.toPath
import me.nathanfallet.teamcode.cli.platform.environment
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
        val code = supervisor.serve()
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
        echo(AgentEnvironment.script(answer.variables, answer.withdrawn.toSet()), trailingNewline = false)
    }
}

