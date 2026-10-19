package me.nathanfallet.teamcode.cli.github

import com.github.ajalt.clikt.command.SuspendingCliktCommand
import com.github.ajalt.clikt.command.SuspendingNoOpCliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import kotlin.time.Instant
import kotlinx.coroutines.delay
import me.nathanfallet.teamcode.api.Serialization
import me.nathanfallet.teamcode.api.models.GitHubLinkStates
import me.nathanfallet.teamcode.api.models.GitHubLinkView
import me.nathanfallet.teamcode.cli.loadConfig
import me.nathanfallet.teamcode.cli.sdk
import me.nathanfallet.teamcode.client.TeamCodeException

/** `teamcode github`: the member's GitHub App link, used only by their own cloud sessions. */
class GitHub : SuspendingNoOpCliktCommand(name = "github") {
    override fun help(context: Context) = "Link your GitHub account for your cloud sessions' Git and gh access. Requires an interactive login."
    init { subcommands(GitHubLinkCommand(), GitHubStatus(), GitHubUnlink()) }
}

internal fun GitHubLinkView.describe() = when (state) {
    GitHubLinkStates.LINKED -> "Linked as $login" + (linkedAt?.let { " since ${Instant.fromEpochMilliseconds(it)}" } ?: "") + "."
    GitHubLinkStates.RECONNECT_REQUIRED -> "GitHub access for $login must be renewed: run teamcode github link."
    else -> if (appConfigured) "Not linked. Run teamcode github link." else "Not linked. An administrator has not configured the team's GitHub App yet."
}

/** The server offers GitHub links only with cloud sessions; say so instead of failing on a missing route. */
private suspend fun SuspendingCliktCommand.requireGitHub(client: me.nathanfallet.teamcode.client.TeamCodeClient) {
    if ("github.link.v1" !in client.capabilities().features) {
        echo("This TeamCode server does not offer GitHub links (they come with cloud sessions).", err = true)
        throw ProgramResult(1)
    }
}

private class GitHubLinkCommand : SuspendingCliktCommand(name = "link") {
    override fun help(context: Context) = "Link your GitHub account with GitHub's device flow. The token stays on the TeamCode server."
    override suspend fun run() {
        val config = loadConfig()
        sdk(config).use { client ->
            requireGitHub(client)
            val device = try { client.startGitHubDeviceLink() } catch (error: TeamCodeException) {
                if (error.statusCode != 409) throw error
                echo("The team's GitHub App is not configured or does not allow device flow. Link from ${config.serverUrl.trimEnd('/')}/dashboard/github instead.", err = true)
                throw ProgramResult(1)
            }
            echo("Open ${device.verificationUri} and enter the code ${device.userCode}")
            echo("Waiting for GitHub (the code expires at ${Instant.fromEpochMilliseconds(device.expiresAt)}). Press Ctrl-C to cancel.", err = true)
            var interval = device.interval
            while (true) {
                delay(interval.coerceIn(1, 60) * 1000L)
                val poll = client.pollGitHubDeviceLink(device.id)
                when (poll.status) {
                    "pending" -> interval = poll.interval ?: interval
                    "linked" -> { echo(poll.link?.describe() ?: "Linked."); return }
                    "denied" -> { echo("GitHub authorization was cancelled.", err = true); throw ProgramResult(1) }
                    else -> { echo("The code expired. Run teamcode github link again.", err = true); throw ProgramResult(1) }
                }
            }
        }
    }
}

private class GitHubStatus : SuspendingCliktCommand(name = "status") {
    private val member by option("--member", help = "Another member's link; administrators only.")
    private val json by option("--json").flag()
    override suspend fun run() {
        val link = sdk(loadConfig()).use { requireGitHub(it); it.githubLink(member) }
        echo(if (json) Serialization.json.encodeToString(link) else link.describe())
    }
}

private class GitHubUnlink : SuspendingCliktCommand(name = "unlink") {
    override fun help(context: Context) = "Remove the link and revoke its current token at GitHub. Your cloud sessions then lose GitHub access."
    private val member by option("--member", help = "Unlink another member; administrators only.")
    override suspend fun run() {
        sdk(loadConfig()).use { requireGitHub(it); it.unlinkGitHub(member) }
        echo("GitHub unlinked.")
    }
}
