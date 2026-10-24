package me.nathanfallet.teamcode.companion.infrastructure.repositories

import me.nathanfallet.teamcode.api.models.GitHubDeviceLinkView
import me.nathanfallet.teamcode.api.models.GitHubLinkStates
import me.nathanfallet.teamcode.api.models.GitHubLinkView
import me.nathanfallet.teamcode.client.TeamCodeException
import me.nathanfallet.teamcode.companion.domain.models.CompanionException
import me.nathanfallet.teamcode.companion.domain.models.GitHubDeviceLink
import me.nathanfallet.teamcode.companion.domain.models.GitHubLink
import me.nathanfallet.teamcode.companion.domain.models.GitHubLinkState
import me.nathanfallet.teamcode.companion.domain.models.GitHubPoll
import me.nathanfallet.teamcode.companion.domain.repositories.Features
import me.nathanfallet.teamcode.companion.domain.repositories.FeaturesRepository
import me.nathanfallet.teamcode.companion.domain.repositories.GitHubLinkRepository

/**
 * `teamcode github link`, `status` and `unlink` on the device: the same typed client calls and routes as the CLI
 * (`/api/v1/github/link`, `…/link/device`). The server holds the GitHub token; the app only shows the device flow's code.
 */
class RemoteGitHubLinkRepository(
    private val calls: AccountCalls,
    private val features: FeaturesRepository,
) : GitHubLinkRepository {
    override suspend fun offered(accountId: String): Boolean = Features.GITHUB_LINK in features.features(accountId, refresh = true)

    override suspend fun link(accountId: String): GitHubLink = calls.withAccount(accountId) { it.githubLink().domain() }

    override suspend fun start(accountId: String): GitHubDeviceLink = calls.withAccount(accountId) { client ->
        try {
            client.startGitHubDeviceLink().domain()
        } catch (error: TeamCodeException) {
            val code = error.code
            if (error.statusCode == 409 && code != null) throw CompanionException.GitHubRefused(code)
            throw error
        }
    }

    override suspend fun poll(accountId: String, id: String): GitHubPoll = calls.withAccount(accountId) { client ->
        val answer = client.pollGitHubDeviceLink(id)
        when (answer.status) {
            "pending" -> GitHubPoll.Pending(answer.interval)
            "linked" -> answer.link?.let { GitHubPoll.Linked(it.domain()) } ?: GitHubPoll.Linked(client.githubLink().domain())
            "denied" -> GitHubPoll.Denied
            else -> GitHubPoll.Expired
        }
    }

    override suspend fun unlink(accountId: String) = calls.withAccount(accountId) { it.unlinkGitHub() }
}

internal fun GitHubLinkView.domain() = GitHubLink(
    state = when (state) {
        GitHubLinkStates.LINKED -> GitHubLinkState.LINKED
        GitHubLinkStates.RECONNECT_REQUIRED -> GitHubLinkState.RECONNECT_REQUIRED
        else -> GitHubLinkState.NOT_LINKED
    },
    appConfigured = appConfigured,
    login = login,
    linkedAt = linkedAt,
)

private fun GitHubDeviceLinkView.domain() = GitHubDeviceLink(id, userCode, verificationUri, expiresAt, interval)
