package me.nathanfallet.teamcode.companion.domain.usecases.github

import me.nathanfallet.teamcode.companion.domain.models.CompanionException
import me.nathanfallet.teamcode.companion.domain.models.GitHubDeviceLink
import me.nathanfallet.teamcode.companion.domain.models.GitHubLink
import me.nathanfallet.teamcode.companion.domain.models.GitHubPoll
import me.nathanfallet.teamcode.companion.domain.repositories.AccountRepository
import me.nathanfallet.teamcode.companion.domain.repositories.GitHubLinkRepository
import me.nathanfallet.teamcode.companion.domain.usecases.auth.AccountGuard

/** What the GitHub screen shows: the member's link, or null when the server does not offer GitHub links. */
class LoadGitHubLinkUseCase(
    private val repository: GitHubLinkRepository,
    private val accounts: AccountRepository,
    private val guard: AccountGuard,
) {
    suspend operator fun invoke(accountId: String): GitHubLink? {
        accounts.accounts.value.firstOrNull { it.id == accountId }?.takeIf { it.signedIn } ?: throw CompanionException.Unauthorized()
        return guard(accountId) { if (repository.offered(accountId)) repository.link(accountId) else null }
    }
}

/** Starts GitHub's device flow; the server refuses (`409`) when the team's GitHub App is missing or forbids it. */
class StartGitHubLinkUseCase(private val repository: GitHubLinkRepository, private val guard: AccountGuard) {
    suspend operator fun invoke(accountId: String): GitHubDeviceLink = guard(accountId) { repository.start(accountId) }
}

/** One poll of the device flow; the screen waits the interval between two. */
class PollGitHubLinkUseCase(private val repository: GitHubLinkRepository, private val guard: AccountGuard) {
    suspend operator fun invoke(accountId: String, id: String): GitHubPoll = guard(accountId) { repository.poll(accountId, id) }
}

/** Removes the link and revokes its token at GitHub (the server does both). */
class UnlinkGitHubUseCase(private val repository: GitHubLinkRepository, private val guard: AccountGuard) {
    suspend operator fun invoke(accountId: String) = guard(accountId) { repository.unlink(accountId) }
}
