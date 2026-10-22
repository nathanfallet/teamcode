package me.nathanfallet.teamcode.cli.mcp

import com.github.ajalt.clikt.command.SuspendingCliktCommand
import com.github.ajalt.clikt.command.SuspendingNoOpCliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
import io.ktor.client.HttpClient
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.header
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

private val TOKEN_VARIABLES = listOf("TEAMCODE_ARTIFACTS_TOKEN", "TEAMCODE_MCP_TOKEN", "TEAMCODE_LOCAL_TOKEN")

class Mcp : SuspendingNoOpCliktCommand(name = "mcp") {
    override fun help(context: Context) = "MCP servers TeamCode runs beside an agent, over stdio."
    init { subcommands(ArtifactsMcpCommand()) }
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
        val configurationDirectory = configDir?.toPath() ?: configPath().toPath().parent!!
        val directory = scratch?.toPath() ?: (configurationDirectory / "artifacts" / (run ?: "local")).also { default ->
            // The default's parent holds every run's scratch directory: private too.
            val parent = default.parent!!
            parent.parent?.let { FileSystem.SYSTEM.createDirectories(it) }
            privateDirectory(parent)
        }
        privateDirectory(directory)
        val scratchPath = FileSystem.SYSTEM.canonicalize(directory)
        // The TeamCode configuration directory holds the member's login: never published, even when the root contains it.
        val configuration = configurationDirectory.let { FileSystem.SYSTEM.metadataOrNull(it)?.let { _ -> FileSystem.SYSTEM.canonicalize(it) } ?: it }
        val client = client()
        client.use {
            val sources = ArtifactSources(listOf(root, scratchPath.toString()), refuse, refusedExcept = listOf(configuration.toString() to scratchPath.toString()))
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

    /**
     * Inside `teamcode run`, the launcher's loopback relay with the local token only; otherwise the stored CLI login.
     * The relay address must be loopback, so the local token never leaves the computer.
     */
    private fun client(): TeamCodeClient {
        val url = environment("TEAMCODE_ARTIFACTS_URL") ?: return sdk(loadConfig())
        if (!Regex("http://127\\.0\\.0\\.1:[0-9]{1,5}").matches(url)) throw CliUserException("TEAMCODE_ARTIFACTS_URL must be the local relay, http://127.0.0.1:<port>.")
        // The launcher names the variable that holds this launch's token, so a token inherited from an outer launch is
        // never picked first. Without it, the known variables in turn; an unexpanded ${…} placeholder is not a token.
        val names = environment("TEAMCODE_ARTIFACTS_TOKEN_VAR")?.takeIf { it in TOKEN_VARIABLES }?.let(::listOf) ?: TOKEN_VARIABLES
        val token = names.firstNotNullOfOrNull { name ->
            environment(name)?.takeIf { it.length >= 32 && "\${" !in it }
        } ?: throw CliUserException("No local TeamCode token: start the agent with teamcode run.")
        val runId = run
        return TeamCodeClient(url, { token }) { configure ->
            HttpClient {
                configure(this)
                if (runId != null) defaultRequest { header("x-teamcode-run-id", runId) }
            }
        }
    }

    private fun privateDirectory(directory: Path) {
        if (!ensurePrivateDirectory(directory.toString())) throw CliUserException("$directory must be a directory of yours readable by you only (mode 0700), or not exist yet.")
    }
}
