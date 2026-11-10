package me.nathanfallet.teamcode.companion.domain.usecases.push

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import me.nathanfallet.teamcode.companion.domain.models.Account
import me.nathanfallet.teamcode.companion.domain.models.LiveUpdate
import me.nathanfallet.teamcode.companion.domain.models.SessionChange
import me.nathanfallet.teamcode.companion.domain.models.SessionState
import me.nathanfallet.teamcode.companion.domain.models.SessionWaiting
import me.nathanfallet.teamcode.companion.domain.models.WaitKind
import me.nathanfallet.teamcode.companion.domain.models.WaitResolver
import me.nathanfallet.teamcode.companion.domain.models.WaitStatus
import me.nathanfallet.teamcode.companion.domain.repositories.AccountRepository
import me.nathanfallet.teamcode.companion.domain.repositories.SessionRepository
import me.nathanfallet.teamcode.companion.domain.repositories.WaitRepository
import me.nathanfallet.teamcode.companion.domain.usecases.links.NotificationLinks
import me.nathanfallet.teamcode.companion.domain.usecases.sessions.SessionFeed

/** What a desktop notification says; the screen layer words it in the app's language. */
enum class ActivityKind { PERMISSION, QUESTION, WAIT, TURN_COMPLETED, TURN_FAILED, SESSION_FAILED }

/** Something for the member, seen on an account's event feed: [title] is the session's name, [detail] a wait's title. */
data class ActivityNotice(val accountId: String, val kind: ActivityKind, val title: String?, val detail: String?, val link: String)

/**
 * The desktop's notifications (docs/notifications.md, "Desktop"): the desktop has no push, so it follows the caller-wide
 * event feed of every signed-in account and keeps what the server would push to the member, by the same rules
 * (`NotificationRules`): their session's agent waits for them, finished its turn, or their session failed; a wait a
 * person resolves is assigned to them. On an administrator's feed, other members' sessions are left out. The feed is
 * shared with the screens ([SessionFeed]); a feed that failed (a revoked token) is followed again a minute later.
 */
class WatchActivityUseCase(
    private val accounts: AccountRepository,
    private val feed: SessionFeed,
    private val sessions: SessionRepository,
    private val waits: WaitRepository,
    /** Accounts left out: the demo account, whose feed is made up. */
    private val skip: (String) -> Boolean = { false },
    private val retryMillis: Long = 60_000,
) {
    @OptIn(ExperimentalCoroutinesApi::class)
    operator fun invoke(): Flow<ActivityNotice> = accounts.accounts
        .map { list -> list.filter { it.signedIn && !skip(it.id) } }
        .distinctUntilChanged { old, new -> old.map { it.id to it.memberId } == new.map { it.id to it.memberId } }
        .flatMapLatest { signedIn -> if (signedIn.isEmpty()) emptyFlow() else merge(*signedIn.map(::watch).toTypedArray()) }

    private fun watch(account: Account): Flow<ActivityNotice> = flow {
        while (true) {
            try {
                feed.follow(account.id).collect { update ->
                    if (update is LiveUpdate.Item) notice(account, update.value)?.let { emit(it) }
                }
            } catch (error: CancellationException) { throw error } catch (_: Exception) { }
            delay(retryMillis)
        }
    }

    /** Null when the change is not for this member, or when what it refers to could not be read. */
    internal suspend fun notice(account: Account, change: SessionChange): ActivityNotice? {
        if (change.memberId != account.memberId) return null
        val kind = when (change.type) {
            SessionChange.WAITING -> if (change.waitingReason == SessionWaiting.QUESTION) ActivityKind.QUESTION else ActivityKind.PERMISSION
            SessionChange.TURN -> if (change.turnPhase == "completed") ActivityKind.TURN_COMPLETED else return null
            "cloud.session.ended" -> if (change.state == SessionState.FAILED) ActivityKind.SESSION_FAILED else return null
            SessionChange.WAIT_CREATED -> ActivityKind.WAIT
            else -> return null
        }
        return try {
            if (kind == ActivityKind.WAIT) {
                val waitId = change.waitId ?: return null
                // The event names the wait only: its title, kind and resolver come from the open waits' first page.
                val wait = waits.list(account.id, WaitStatus.OPEN).waits.firstOrNull { it.id == waitId } ?: return null
                if (wait.resolver != WaitResolver.HUMAN) return null
                val failed = wait.kind == WaitKind.TURN_FAILED
                ActivityNotice(account.id, if (failed) ActivityKind.TURN_FAILED else ActivityKind.WAIT, wait.sessionName, if (failed) null else wait.title,
                    NotificationLinks.link(account.origin, account.memberId, wait.sessionId, wait.id))
            } else {
                val name = change.sessionId.takeIf { it.isNotEmpty() }?.let { sessions.session(account.id, it).name } ?: return null
                ActivityNotice(account.id, kind, name, null, NotificationLinks.link(account.origin, account.memberId, change.sessionId, null))
            }
        } catch (error: CancellationException) { throw error } catch (_: Exception) { null }
    }
}
