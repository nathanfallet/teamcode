package me.nathanfallet.teamcode.companion

import me.nathanfallet.teamcode.companion.domain.models.GitHubDeviceLink
import me.nathanfallet.teamcode.companion.domain.models.GitHubLink
import me.nathanfallet.teamcode.companion.domain.models.GitHubLinkState
import me.nathanfallet.teamcode.companion.domain.models.GitHubPoll
import me.nathanfallet.teamcode.companion.domain.repositories.GitHubLinkRepository

fun gitHubLink(state: GitHubLinkState = GitHubLinkState.LINKED, login: String? = "octocat", appConfigured: Boolean = true) =
    GitHubLink(state, appConfigured, login, linkedAt = 1_700_000_000_000L)

/** The member's GitHub link held in memory; every call is recorded in [calls]. */
class FakeGitHubLinkRepository : GitHubLinkRepository {
    val calls = mutableListOf<String>()
    var offered = true
    var link = gitHubLink(GitHubLinkState.NOT_LINKED, login = null)
    var device = GitHubDeviceLink("d1", "ABCD-1234", "https://github.com/login/device", expiresAt = 1_900_000_000_000L, interval = 5)
    /** The answers to successive polls; the last one repeats. */
    var polls: List<() -> GitHubPoll> = listOf({ GitHubPoll.Pending(null) })
    var startFailure: Exception? = null
    private var polled = 0

    override suspend fun offered(accountId: String): Boolean { calls += "offered"; return offered }
    override suspend fun link(accountId: String): GitHubLink { calls += "link"; return link }
    override suspend fun start(accountId: String): GitHubDeviceLink { calls += "start"; startFailure?.let { throw it }; return device }
    override suspend fun poll(accountId: String, id: String): GitHubPoll {
        calls += "poll $id"
        val answer = polls[minOf(polled++, polls.lastIndex)]()
        if (answer is GitHubPoll.Linked) link = answer.link
        return answer
    }
    override suspend fun unlink(accountId: String) { calls += "unlink"; link = gitHubLink(GitHubLinkState.NOT_LINKED, login = null) }
}
