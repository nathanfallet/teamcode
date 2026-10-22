package me.nathanfallet.teamcode.cli

import com.github.ajalt.clikt.command.*
import com.github.ajalt.clikt.core.*
import com.github.ajalt.clikt.parameters.options.*
import com.github.ajalt.clikt.parameters.types.int
import io.ktor.client.HttpClient
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import me.nathanfallet.teamcode.cli.config.*
import me.nathanfallet.teamcode.cli.platform.*
import me.nathanfallet.teamcode.cli.proxy.proxyServer
import me.nathanfallet.teamcode.client.TeamCodeClient
import com.github.ajalt.mordant.terminal.Terminal
import me.nathanfallet.teamcode.cli.accounts.Accounts
import me.nathanfallet.teamcode.cli.agents.Agents
import me.nathanfallet.teamcode.cli.launch.Run
import me.nathanfallet.teamcode.cli.sessions.Sessions
import me.nathanfallet.teamcode.cli.status.Status
import me.nathanfallet.teamcode.cli.service.Service
import me.nathanfallet.teamcode.cli.identity.*
import me.nathanfallet.teamcode.cli.update.VerifyRelease
import me.nathanfallet.teamcode.cli.update.Update

const val VERSION = me.nathanfallet.teamcode.api.BuildInfo.VERSION

class Cli : SuspendingNoOpCliktCommand(name = "teamcode") {
    override fun help(context: Context) = "A self-hosted team gateway. First use: teamcode login."
    // Read by main() before parsing, so a failure in any command can print it; declared here for --help.
    @Suppress("unused") private val debug by option("--debug", help = "On failure, also print the error type, HTTP status and codes (never secrets). Same as TEAMCODE_DEBUG=1.").flag()
    init { subcommands(Login(), Logout(), Configure(), Status(), Version(), Proxy(), Accounts(), Agents(), Run(), Sessions(), Service(), WhoAmI(), Auth(), Devices(), ServiceKeys(), VerifyRelease(), Update(), me.nathanfallet.teamcode.cli.update.Install(), me.nathanfallet.teamcode.cli.cloud.Cloud(), me.nathanfallet.teamcode.cli.github.GitHub(), me.nathanfallet.teamcode.cli.hooks.Hooks()) }
}

private class Version : SuspendingCliktCommand(name = "version") {
    override suspend fun run() { echo("TeamCode $VERSION") }
}

private class Login : SuspendingCliktCommand(name = "login") {
    private val server by option("--server", help = "Your self-hosted server origin; saved after login.")
    private val username by option("--username", help = "Your TeamCode username; prompted when omitted.")
    private val passwordStdin by option("--password-stdin", help = "Read passwords from stdin for automation.").flag()
    private val device by option("--device", help = "Device label for a new CLI installation; existing names are preserved.").default("TeamCode CLI")
    override suspend fun run() {
        val terminal by lazy { Terminal() }
        fun read(label: String, secret: Boolean = false): String {
            echo("$label: ", trailingNewline = false, err = true)
            return (if (passwordStdin) readlnOrNull() else terminal.readLineOrNull(hideInput = secret)) ?: throw CliUserException("Input ended before $label was entered.")
        }
        val origin = validateServerUrl(server ?: ConfigStore().load()?.serverUrl ?: read("TeamCode server URL"))
        val name = username ?: read("Username")
        var password = read("Password", secret = true)
        val installation = InstallationStore().id()
        var session = sdk(CliConfig(origin, "unauthenticated")).use { it.login(name, password, device, installation) }
        if (session.member.passwordChangeRequired) {
            echo("Choose a new password with at least 14 characters. This will sign out all existing sessions.")
            val replacement = read("New password", secret = true)
            if (replacement != read("Confirm new password", secret = true)) throw CliUserException("Passwords do not match. Run teamcode login again.")
            sdk(CliConfig(origin, session.accessToken)).use { it.changePassword(password, replacement) }
            password = replacement
            session = sdk(CliConfig(origin, "unauthenticated")).use { it.login(name, password, device, installation) }
        }
        ConfigStore().save(CliConfig(origin, session.accessToken))
        echo("Signed in as ${session.member.username}. Configuration saved to ${configPath()}.")
    }
}
private class Logout : SuspendingCliktCommand(name = "logout") {
    override suspend fun run() {
        sdk(loadConfig()).use { it.logout() }
        ConfigStore().delete()
        echo("Session revoked and local configuration removed.")
    }
}

private class Configure : SuspendingCliktCommand(name = "configure") {
    private val server by option("--server", help = "Your self-hosted TeamCode origin; prompted when omitted.")
    override fun help(context: Context) = "Save a server URL and an existing access token from stdin. Use teamcode login for ordinary sign-in."
    override suspend fun run() {
        val origin = server ?: run {
            echo("TeamCode server URL: ", trailingNewline = false)
            readlnOrNull()?.trim().orEmpty()
        }
        val normalized = validateServerUrl(origin)
        echo("TeamCode access token (stdin; do not pass secrets as command arguments):", err = true)
        val accessToken = readlnOrNull()?.trim().orEmpty()
        val config = CliConfig(normalized, accessToken)
        sdk(config).use { client -> client.capabilities() } // Verify before saving a credential; main() explains a failure.
        try { ConfigStore().save(config) }
        catch (error: CancellationException) { throw error }
        catch (error: CliOperationException) { throw error }
        catch (error: Exception) { throw CliOperationException("configuration_write_${error::class.simpleName.orEmpty().filter { it.isLetterOrDigit() }}") }
        echo("Configuration saved to ${configPath()}.")
    }
}

private class Proxy : SuspendingNoOpCliktCommand(name = "proxy") {
    init { subcommands(ProxyStart()) }
}
private class ProxyStart : SuspendingCliktCommand(name = "start") {
    private val port by option("--port").int().default(8789)
    override fun help(context: Context) = "Start the authenticated loopback Messages proxy in the foreground. TEAMCODE_LOCAL_TOKEN is required."
    override suspend fun run() {
        if (port !in 1..65535) throw CliUserException("Port must be between 1 and 65535.")
        val localToken = environment("TEAMCODE_LOCAL_TOKEN") ?: throw CliUserException("Set TEAMCODE_LOCAL_TOKEN (at least 32 characters).")
        if (localToken.length < 32) throw CliUserException("The local token must contain at least 32 characters.")
        val client = sdk(loadConfig())
        val server = proxyServer(client, localToken, port) { echo(it, err = true) }
        try {
            server.start(wait = false)
            echo("TeamCode proxy listening on http://127.0.0.1:$port (authentication required).")
            awaitCancellation()
        } finally {
            server.stop(500, 5_000)
            client.close()
        }
    }
}

internal fun loadConfig() = ConfigStore().load() ?: throw CliUserException("No configuration found. Run teamcode login first.")
internal fun sdk(config: CliConfig): TeamCodeClient {
    contactedServer = config.serverUrl
    return TeamCodeClient(config.serverUrl, { config.accessToken }) { configure -> HttpClient() { configure(this) } }
}

/** The origin of the last server this command contacted, named in network failure messages; an origin has no credential. */
@kotlin.concurrent.Volatile internal var contactedServer: String? = null

internal fun debugRequested(arguments: List<String>, env: (String) -> String? = ::environment): Boolean =
    env("TEAMCODE_DEBUG") == "1" || "--debug" in arguments.takeWhile { it.startsWith("-") }

fun main(args: Array<String>): Unit = runBlocking {
    var debug = environment("TEAMCODE_DEBUG") == "1"
    try {
        val arguments = platformArguments(args)
        debug = debugRequested(arguments.toList())
        if (arguments.firstOrNull() == "--teamcode-installation-probe") {
            SystemExit.exit(if (arguments.toList() == listOf("--teamcode-installation-probe", VERSION, "1")) 0 else 1)
        }
        me.nathanfallet.teamcode.cli.update.dispatchInstalled(arguments)
        Cli().main(arguments)
    }
    catch (error: CancellationException) { throw error }
    catch (error: Exception) {
        // describeFailure never prints exception messages or chains from TLS, JSON, files or servers: they may carry credentials.
        val failure = describeFailure(error, contactedServer ?: try { ConfigStore().load()?.serverUrl } catch (_: Exception) { null })
        SystemExit.fail(if (debug) "${failure.message}\n${failure.diagnostic}" else failure.message)
    }
}

/** Actionable text for release failures; the code stays in the output for diagnostics and scripts. */
internal fun releaseFailureMessage(code: String): String = when (code) {
    "release_installation_busy" ->
        "TeamCode installation is busy ($code): an agent session started by TeamCode is still running, or the installation is being modified. Close all agent sessions, then run the command again (for example `teamcode update`)."
    "release_writer_busy" ->
        "TeamCode installation is busy ($code): another TeamCode update or installation change is in progress. Wait for it to finish, then run the command again."
    else ->
        "TeamCode release operation failed ($code). Check connectivity, release files, private permissions and platform crypto libraries."
}

expect object SystemExit { fun fail(message: String): Nothing; fun exit(code: Int): Nothing }

/**
 * Safe operation identifiers only; never print raw platform/HTTP exception messages. The code is also the exception
 * message, so a test or crash report names the failing step (for example `windows_private_file_4`).
 */
class CliOperationException(val code: String) : Exception(code) {
    init { require(Regex("[a-zA-Z0-9_]{1,120}").matches(code)) }
}

/** A failure whose message is written by TeamCode itself (never derived from a remote or platform error), so it is safe and useful to print. */
class CliUserException(override val message: String) : Exception(message)
