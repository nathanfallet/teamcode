package me.nathanfallet.teamcode.cli.accounts

import com.github.ajalt.clikt.command.*
import com.github.ajalt.clikt.core.*
import com.github.ajalt.clikt.parameters.options.*
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.types.choice
import com.github.ajalt.mordant.terminal.Terminal
import io.ktor.client.HttpClient
import me.nathanfallet.teamcode.api.models.AccountEnrollmentPolicyView
import me.nathanfallet.teamcode.api.models.EnrollAccountRequest
import me.nathanfallet.teamcode.cli.CliUserException
import me.nathanfallet.teamcode.cli.loadConfig
import me.nathanfallet.teamcode.cli.sdk
import me.nathanfallet.teamcode.cli.platform.*
import me.nathanfallet.teamcode.client.*
import okio.ByteString.Companion.toByteString
import okio.FileSystem
import okio.Path.Companion.toPath

class Accounts : SuspendingNoOpCliktCommand(name = "accounts") {
    init { subcommands(ListAccounts(), AddAccount(), ImportAccounts(), Handover()) }
}
private fun randomCode() = secureRandomBytes(32).toByteString().base64().replace('+', '-').replace('/', '_').trimEnd('=')
private class ListAccounts : SuspendingCliktCommand(name = "list") {
    override suspend fun run() { sdk(loadConfig()).use { client ->
        client.accounts().forEach { echo("${it.id} | ${it.label} | ${it.state}${if (it.disabled) " | disabled" else ""}") }
    } }
}
private class AddAccount : SuspendingCliktCommand(name = "add") {
    private val typeName by option("--type", help = "Account type, skipping the selector: ${AccountType.flags.joinToString(", ")}.").choice(*AccountType.flags.toTypedArray())
    private val providerName by option("--provider", help = "Older spelling of --type: claude or claude-max (Anthropic subscription), codex.").choice("claude", "claude-max", "codex")
    private val noBrowser by option("--no-browser", help = "Print the sign-in link without opening a browser.").flag()
    override fun help(context: Context) = "Choose an account type (numbered selector, or --type), sign in locally, then send verified credentials to your TeamCode server. Existing accounts are updated without replacing their settings."
    override suspend fun run() {
        val config = loadConfig()
        // Chosen first: a type the server cannot hold is refused before any network call or sign-in.
        val terminal = Terminal()
        val type = chooseAccountType(typeName, providerName, ::echo) { terminal.readLineOrNull(hideInput = false) }
        sdk(config).use { client ->
            // Check before opening the provider sign-in, with a call every member may make.
            requireEnrollmentAllowed(client.capabilities().features, client::accountEnrollmentPolicy) { client.me().role }
            val provider = if (type == AccountType.CODEX_SUBSCRIPTION) CodexOAuthEnrollment else ClaudeOAuthEnrollment
            val verifier = randomCode()
            val state = randomCode()
            val challenge = verifier.encodeToByteArray().toByteString().sha256().base64().replace('+', '-').replace('/', '_').trimEnd('=')
            val url = provider.authorizationUrl(state, challenge)
            echo("Sign in with the browser. ${provider.pasteInstructions}")
            echo(url)
            if (!noBrowser) {
                val command = browserCommand(hostOs(), url, ::environment)
                if (command == null) echo("No local browser here: open the link above on any device.")
                else if (!openBrowser(command)) echo("Could not open a browser: open the link above by hand.")
            }
            val paste = Terminal().readLineOrNull(hideInput = true)?.trim().orEmpty()
            val code = provider.parseCode(paste, state)
            val input = OAuthEnrollmentClient(provider) { configure -> HttpClient() { configure(this) } }.use { it.exchange(code, state, verifier, randomCode()) }
            val result = try { client.enrollAccount(input) } catch (error: TeamCodeException) { throw enrollmentRefusal(error) ?: error }
            echo("${if (result.created) "Added" else "Updated"} ${result.account.label}, id ${result.account.id}.")
            if (result.account.state == "PENDING_HANDOVER") echo("Stop any other refresher for this account, then run: teamcode accounts handover ${result.account.id} --confirm")
            else echo("TeamCode owns this sign-in: the account serves requests now (state ${result.account.state}).")
        }
    }
}
internal const val ENROLLMENT_POLICY_FEATURE = "accounts.enrollment-policy.v1"
/** TeamCode's own words for the refusals of `accounts add`, safe to print; keyed by the server's error code. */
internal object EnrollmentMessages {
    const val ADMINISTRATORS_ONLY = "Only administrators can add accounts on this server. Ask an administrator, or ask them to allow every member to add accounts (setting \"Who can add accounts\" on the dashboard's Subscription accounts page, or PUT /api/v1/policy/account-enrollment)."
    const val ACCOUNT_IN_USE = "This subscription account is already in the pool and is not waiting to be reconnected. Ask an administrator to replace its sign-in."
    const val SESSION_CANNOT_ENROLL = "This TeamCode session cannot add accounts. Sign in with your own account (teamcode login) and finish any required password change, then try again."
    fun forCode(code: String?): String? = when (code) {
        "account_enrollment_forbidden" -> ADMINISTRATORS_ONLY
        "account_in_use" -> ACCOUNT_IN_USE
        else -> null
    }
}
internal fun enrollmentRefusal(error: TeamCodeException): CliUserException? =
    EnrollmentMessages.forCode(error.code)?.takeIf { error.statusCode == 403 }?.let(::CliUserException)
/**
 * Refuses before any provider sign-in when the server would. A server that announces [ENROLLMENT_POLICY_FEATURE] says
 * whether the caller may; an older one only accepts administrators, which the caller's own role tells.
 */
internal suspend fun requireEnrollmentAllowed(features: List<String>, policy: suspend () -> AccountEnrollmentPolicyView, role: suspend () -> String) {
    if (ENROLLMENT_POLICY_FEATURE !in features) {
        if (role() !in setOf("OWNER", "ADMIN")) throw CliUserException(EnrollmentMessages.ADMINISTRATORS_ONLY)
        return
    }
    val answer = policy()
    if (answer.allowed == true) return
    // Every member may add accounts, so the refusal is about this session (a service key, a pending password change).
    throw CliUserException(if (answer.policy == "members") EnrollmentMessages.SESSION_CANNOT_ENROLL else EnrollmentMessages.ADMINISTRATORS_ONLY)
}
private class ImportAccounts : SuspendingCliktCommand(name = "import") {
    private val from by option("--from").required()
    private val config by option("--config", help = "Explicit TeamClaude configuration file; otherwise use its XDG location.")
    private val dryRun by option("--dry-run", help = "Parse locally without uploading credentials.").flag()
    private val takeOwnership by option("--take-ownership", help = "Confirm other refreshers have stopped and activate imported accounts.").flag()
    private val accessOnly by option("--access-only", help = "Activate imported access tokens without refreshing them; useful for temporary acceptance tests.").flag()
    override suspend fun run() {
        if (from != "teamclaude") throw UsageError("Only --from teamclaude is supported.")
        if (takeOwnership && accessOnly) throw UsageError("Choose either refresh ownership or access-only activation.")
        val path = (config ?: sourceConfig()).toPath()
        val info = FileSystem.SYSTEM.metadata(path)
        if (!info.isRegularFile || (info.size ?: Long.MAX_VALUE) > MAX_IMPORT_BYTES) throw CliktError("$path is not a regular TeamClaude configuration file of at most $MAX_IMPORT_BYTES bytes.")
        val source = FileSystem.SYSTEM.read(path) { readUtf8((MAX_IMPORT_BYTES + 1L).coerceAtMost(info.size!!)) }
        val entries = parseTeamClaudeConfig(source)
        if (dryRun) {
            entries.forEach { echo("${it.label}: ${if (it is ImportEntry.Ready) "ready for server verification" else (it as ImportEntry.Rejected).reason}") }
            return
        }
        var failed = 0
        sdk(loadConfig()).use { client ->
            client.accounts()
            entries.forEach { entry ->
                if (entry is ImportEntry.Rejected) { failed++; echo("Skipped ${entry.label}: ${entry.reason}"); return@forEach }
                entry as ImportEntry.Ready
                try {
                    val credentials = entry.credentials
                    val result = client.enrollAccount(EnrollAccountRequest(credentials.accessToken, credentials.refreshToken, credentials.expiresAtMillis, randomCode(), credentials.provider))
                    if (takeOwnership || accessOnly) client.handoverAccount(result.account.id, accessOnly)
                    echo("${if (result.created) "Added" else "Updated"} ${result.account.label}, id ${result.account.id}.")
                } catch (error: TeamCodeException) { failed++; echo("Failed ${entry.label}: server returned HTTP ${error.statusCode}. Reconnect expired accounts with accounts add.") }
            }
        }
        echo("Import complete: ${entries.size - failed} succeeded, $failed skipped or failed. Source configuration was not modified.")
        if (accessOnly) echo("Access-only mode: TeamCode will not refresh these tokens. Import again when they expire.")
        else if (!takeOwnership) echo("Stop the source refresher, then activate each account with accounts handover <id> --confirm.")
        if (failed != 0) throw CliktError("Some accounts were not imported.")
    }
    private fun sourceConfig(): String {
        environment("TEAMROUTER_CONFIG")?.let { return it }
        environment("TEAMCLAUDE_CONFIG")?.let { return it }
        val base = environment("XDG_CONFIG_HOME") ?: "${environment("HOME") ?: environment("USERPROFILE") ?: throw CliktError("No home directory.")}/.config"
        return listOf("$base/teamrouter.json", "$base/teamclaude.json").firstOrNull { FileSystem.SYSTEM.exists(it.toPath()) } ?: throw CliktError("No TeamClaude configuration found. Use --config.")
    }
}
private class Handover : SuspendingCliktCommand(name = "handover") {
    private val id by argument()
    private val confirm by option("--confirm", help = "Confirm no other process will refresh this account's tokens.").flag()
    override suspend fun run() {
        if (!confirm) throw CliktError("Stop other refreshers first, then pass --confirm.")
        sdk(loadConfig()).use { client -> client.handoverAccount(id).let { echo("${it.label}, id ${it.id}, is now owned by TeamCode.") } }
    }
}
