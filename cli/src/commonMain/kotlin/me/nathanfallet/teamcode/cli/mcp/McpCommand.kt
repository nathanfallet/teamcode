package me.nathanfallet.teamcode.cli.mcp

import com.github.ajalt.clikt.command.SuspendingCliktCommand
import com.github.ajalt.clikt.command.SuspendingNoOpCliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import io.ktor.client.HttpClient
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.header
import kotlinx.serialization.builtins.ListSerializer
import me.nathanfallet.teamcode.api.Serialization
import me.nathanfallet.teamcode.api.models.McpServerView
import me.nathanfallet.teamcode.api.models.RunIdentifiers
import me.nathanfallet.teamcode.cli.CliUserException
import me.nathanfallet.teamcode.cli.artifacts.ArtifactSources
import me.nathanfallet.teamcode.cli.artifacts.ensurePrivateDirectory
import me.nathanfallet.teamcode.cli.artifacts.flushStandardOutput
import me.nathanfallet.teamcode.cli.config.configPath
import me.nathanfallet.teamcode.cli.loadConfig
import me.nathanfallet.teamcode.cli.platform.environment
import me.nathanfallet.teamcode.cli.sdk
import me.nathanfallet.teamcode.client.TeamCodeClient
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath

/** Where a launcher may put the local token, after the server's own `<prefix>_TOKEN`. */
private val TOKEN_VARIABLES = listOf("TEAMCODE_MCP_TOKEN", "TEAMCODE_LOCAL_TOKEN")

class Mcp : SuspendingNoOpCliktCommand(name = "mcp") {
    override fun help(context: Context) = "MCP servers: the team's servers assigned to you, and the ones TeamCode runs beside an agent, over stdio."
    init { subcommands(McpList(), ArtifactsMcpCommand(), WaitsMcpCommand()) }
}

/** One line of `teamcode mcp list`: the name agents see, the transport, and a hosted command's state. */
internal fun mcpServerLine(server: McpServerView): String {
    val transport = when (server.transport) { "stdio" -> "hosted command"; "http" -> "remote (HTTPS)"; null -> "-"; else -> server.transport!! }
    val state = when {
        server.transport != "stdio" -> "-"
        server.state == "failed" || server.state == "backoff" -> "unavailable (${server.failure ?: server.state})"
        else -> server.state ?: "unknown"
    }
    return "${me.nathanfallet.teamcode.cli.launch.mcpLabel(server.name)}\t$transport\t$state"
}

private class McpList : SuspendingCliktCommand(name = "list") {
    private val json by option("--json", help = "Print the JSON answer.").flag()
    override fun help(context: Context) = "List the team's MCP servers assigned to you, as agents see them, with their transport and, for a hosted command, " +
        "the state of its process (refreshed about once a minute). Administrators declare servers in the dashboard."
    override suspend fun run() {
        sdk(loadConfig()).use { client ->
            if ("mcp.gateway.v1" !in client.capabilities().features) throw CliUserException("This TeamCode server has no MCP gateway.")
            val servers = client.mcpServers()
            if (json) return echo(Serialization.json.encodeToString(ListSerializer(McpServerView.serializer()), servers))
            if (servers.isEmpty()) return echo("No MCP server is assigned to you.")
            servers.forEach { echo(mcpServerLine(it)) }
        }
    }
}

private class ArtifactsMcpCommand : SuspendingCliktCommand(name = "artifacts") {
    private val root by option("--root", help = "The directory files are published from (default: the current directory).").default(".")
    private val scratch by option("--scratch", help = "A private directory for the files read and merged (default: under the TeamCode configuration directory).")
    private val run by option("--run", help = "The agent run this server belongs to, recorded as the artifacts' origin.")
    private val configDir by option("--config-dir", help = "The TeamCode configuration directory, never published (default: the one this process finds).")
    private val refuse by option("--refuse", help = "A directory below the roots whose files are never published. Repeatable.").multiple()
    override fun help(context: Context) = "The stdio MCP server agents publish artifacts with. teamcode run adds it to Claude Code and Codex; " +
        "outside it, it uses your stored teamcode login."
    override suspend fun run() {
        run?.let { if (!RunIdentifiers.isValid(it)) throw CliUserException("--run must be a run UUID.") }
        // The launcher names its configuration directory: an agent passes a stdio server only some variables, so this
        // process may not see the XDG_CONFIG_HOME the launcher used.
        // Null when this environment names no home at all: then no login can be stored here either.
        val configurationDirectory = configDir?.toPath() ?: try { configPath().toPath().parent } catch (_: CliUserException) { null }
        val directory = scratch?.toPath() ?: ((configurationDirectory ?: throw CliUserException("No configuration directory: pass --scratch."))
            / "artifacts" / (run ?: "local")).also { default ->
            // The default's parent holds every run's scratch directory: private too.
            val parent = default.parent!!
            // A configuration directory created here will hold the login later: private from the start, like `teamcode login` makes it.
            parent.parent?.let { configuration -> if (FileSystem.SYSTEM.metadataOrNull(configuration) == null) {
                configuration.parent?.let { FileSystem.SYSTEM.createDirectories(it) }
                privateDirectory(configuration)
            } }
            privateDirectory(parent)
        }
        privateDirectory(directory)
        val scratchPath = FileSystem.SYSTEM.canonicalize(directory)
        // The TeamCode configuration directory holds the member's login: never published, even when the root contains it.
        val configuration = configurationDirectory?.let { FileSystem.SYSTEM.metadataOrNull(it)?.let { _ -> FileSystem.SYSTEM.canonicalize(it) } ?: it }
        val client = client()
        client.use {
            val sources = ArtifactSources(listOf(root, scratchPath.toString()), refuse, refusedExcept = listOfNotNull(configuration?.let { dir -> dir.toString() to scratchPath.toString() }))
            val server = ArtifactsMcpServer(it, sources, scratchPath)
            while (true) {
                val line = readlnOrNull() ?: break
                if (line.isBlank()) continue
                val answer = server.handle(line) ?: continue
                print(answer + "\n")
                flushStandardOutput()
            }
        }
    }

    private fun client(): TeamCodeClient = relayClient("TEAMCODE_ARTIFACTS", run)

    private fun privateDirectory(directory: Path) {
        if (!ensurePrivateDirectory(directory.toString())) throw CliUserException("$directory must be a directory of yours readable by you only (mode 0700), or not exist yet.")
    }
}

private class WaitsMcpCommand : SuspendingCliktCommand(name = "waits") {
    override val hiddenFromHelp = true
    private val session by option("--session", help = "The session the waits belong to.").required()
    private val run by option("--run", help = "The agent run this server belongs to.")
    override fun help(context: Context) = "The stdio MCP server a cloud or remote session's agent asks people with (ask_human). " +
        "The session runner adds it to Claude Code and Codex; it never answers a wait."
    override suspend fun run() {
        if (!Regex("[a-zA-Z0-9-]{1,36}").matches(session)) throw CliUserException("--session must be a session id.")
        run?.let { if (!RunIdentifiers.isValid(it)) throw CliUserException("--run must be a run UUID.") }
        relayClient("TEAMCODE_WAITS", run).use {
            val server = WaitsMcpServer(it, session)
            while (true) {
                val line = readlnOrNull() ?: break
                if (line.isBlank()) continue
                val answer = server.handle(line) ?: continue
                print(answer + "\n")
                flushStandardOutput()
            }
        }
    }
}

/**
 * Beside an agent, the launcher's loopback relay (`<prefix>_URL`) with the local token only; otherwise the stored CLI
 * login. The relay address must be loopback, so the local token never leaves the computer.
 */
private fun relayClient(prefix: String, runId: String?): TeamCodeClient {
    val url = environment("${prefix}_URL") ?: return sdk(loadConfig())
    if (!Regex("http://127\\.0\\.0\\.1:[0-9]{1,5}").matches(url)) throw CliUserException("${prefix}_URL must be the local relay, http://127.0.0.1:<port>.")
    // The launcher names the variable that holds this launch's token, so a token inherited from an outer launch is
    // never picked first. Without it, the known variables in turn; an unexpanded ${…} placeholder is not a token.
    val known = listOf("${prefix}_TOKEN") + TOKEN_VARIABLES
    val names = environment("${prefix}_TOKEN_VAR")?.takeIf { it in known }?.let(::listOf) ?: known
    val token = names.firstNotNullOfOrNull { name ->
        environment(name)?.takeIf { it.length >= 32 && "\${" !in it }
    } ?: throw CliUserException("No local TeamCode token: start the agent with teamcode run, or in a TeamCode session.")
    return TeamCodeClient(url, { token }) { configure ->
        HttpClient {
            configure(this)
            if (runId != null) defaultRequest { header("x-teamcode-run-id", runId) }
        }
    }
}
