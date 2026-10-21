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
    init { subcommands(Login(), Logout(), Configure(), Status(), Version(), Proxy(), Accounts(), Agents(), Run(), Sessions(), Service(), WhoAmI(), Auth(), Devices(), ServiceKeys(), VerifyRelease(), Update(), me.nathanfallet.teamcode.cli.update.Install(), me.nathanfallet.teamcode.cli.cloud.Cloud(), me.nathanfallet.teamcode.cli.github.GitHub()) }
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
            return (if (passwordStdin) readlnOrNull() else terminal.readLineOrNull(hideInput = secret)) ?: error("Input ended.")
        }
        val origin = validateServerUrl(server ?: ConfigStore().load()?.serverUrl ?: read("TeamCode server URL"))
        val name = username ?: read("Username")
        var password = read("Password", secret = true)
        val installation = InstallationStore().id()
        var session = sdk(CliConfig(origin, "unauthenticated")).use { it.login(name, password, device, installation) }
        if (session.member.passwordChangeRequired) {
            echo("Choose a new password with at least 14 characters. This will sign out all existing sessions.")
            val replacement = read("New password", secret = true)
            require(replacement == read("Confirm new password", secret = true)) { "Passwords do not match." }
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
        try {
            sdk(config).use { client -> client.capabilities() } // Verify before saving a credential.
        } catch (error: CancellationException) { throw error }
        catch (error: Exception) { throw CliOperationException("server_verification_${error::class.simpleName.orEmpty().filter { it.isLetterOrDigit() }}") }
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
        require(port in 1..65535) { "Port must be between 1 and 65535." }
        val localToken = requireNotNull(environment("TEAMCODE_LOCAL_TOKEN")) { "Set TEAMCODE_LOCAL_TOKEN (at least 32 characters)." }
        require(localToken.length >= 32) { "The local token must contain at least 32 characters." }
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

internal fun loadConfig() = ConfigStore().load() ?: error("No configuration found. Run teamcode login first.")
internal fun sdk(config: CliConfig) = TeamCodeClient(config.serverUrl, { config.accessToken }) { configure ->
    HttpClient() { configure(this) }
}

fun main(args: Array<String>): Unit = runBlocking {
    try {
        val arguments = platformArguments(args)
        if (arguments.firstOrNull() == "--teamcode-installation-probe") {
            SystemExit.exit(if (arguments.toList() == listOf("--teamcode-installation-probe", VERSION, "1")) 0 else 1)
        }
        me.nathanfallet.teamcode.cli.update.dispatchInstalled(arguments)
        Cli().main(arguments)
    }
    catch (error: CancellationException) { throw error }
    catch (error: CliOperationException) {
        if (error.code.startsWith("release_")) SystemExit.fail(releaseFailureMessage(error.code))
        SystemExit.fail("TeamCode command failed (${error.code}). Check server connectivity, authentication and private file permissions.")
    }
    catch (_: Exception) {
        // Never print exception chains from TLS, JSON, file paths or remote servers: they may carry credentials.
        SystemExit.fail("TeamCode command failed. Check the server URL, authentication, file permissions and server availability.")
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

/** Safe operation identifiers only; never print raw platform/HTTP exception messages. */
class CliOperationException(val code: String) : Exception() {
    init { require(Regex("[a-zA-Z0-9_]{1,120}").matches(code)) }
}
