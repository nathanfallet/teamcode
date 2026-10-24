package me.nathanfallet.teamcode.companion.domain.repositories

import me.nathanfallet.teamcode.companion.domain.models.GitHubDeviceLink
import me.nathanfallet.teamcode.companion.domain.models.GitHubLink
import me.nathanfallet.teamcode.companion.domain.models.GitHubPoll

/**
 * The member's GitHub link on an account's server, linked with GitHub's device flow as `teamcode github link` does.
 * The token exchange happens on the server: the app never sees a GitHub token. Failures are
 * [me.nathanfallet.teamcode.companion.domain.models.CompanionException]s.
 */
interface GitHubLinkRepository {
    /** Whether the server offers GitHub links (`github.link.v1`), read again from the server. */
    suspend fun offered(accountId: String): Boolean
    suspend fun link(accountId: String): GitHubLink
    /** Starts the device flow; a `409` (App missing, device flow off) is a [me.nathanfallet.teamcode.companion.domain.models.CompanionException.GitHubRefused]. */
    suspend fun start(accountId: String): GitHubDeviceLink
    suspend fun poll(accountId: String, id: String): GitHubPoll
    /** Removes the link; also when there is none. */
    suspend fun unlink(accountId: String)
}
