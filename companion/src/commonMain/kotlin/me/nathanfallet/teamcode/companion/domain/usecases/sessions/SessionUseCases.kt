package me.nathanfallet.teamcode.companion.domain.usecases.sessions

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import me.nathanfallet.teamcode.companion.domain.models.CloudSession
import me.nathanfallet.teamcode.companion.domain.models.CompanionException
import me.nathanfallet.teamcode.companion.domain.models.SessionFilter
import me.nathanfallet.teamcode.companion.domain.models.LiveUpdate
import me.nathanfallet.teamcode.companion.domain.models.SessionChange
import me.nathanfallet.teamcode.companion.domain.models.SessionKind
import me.nathanfallet.teamcode.companion.domain.models.SessionPage
import me.nathanfallet.teamcode.companion.domain.models.SessionScope
import me.nathanfallet.teamcode.companion.domain.repositories.Features
import me.nathanfallet.teamcode.companion.domain.repositories.FeaturesRepository
import me.nathanfallet.teamcode.companion.domain.repositories.SessionRepository
import me.nathanfallet.teamcode.companion.domain.usecases.auth.AccountGuard

/**
 * One page of the list, of every kind: a server that announces remote sessions is asked for both kinds, an older one is
 * asked as before and answers its cloud sessions.
 */
class LoadSessionsUseCase(private val sessions: SessionRepository, private val features: FeaturesRepository, private val guard: AccountGuard) {
    suspend operator fun invoke(accountId: String, scope: SessionScope, filter: SessionFilter, before: String? = null): SessionPage = guard(accountId) {
        val announced = features.features(accountId)
        if (Features.SESSIONS !in announced) throw CompanionException.Unsupported(Features.SESSIONS)
        when {
            Features.REMOTE_SESSIONS in announced -> sessions.sessions(accountId, scope, filter, before, SessionKind.entries.toSet())
            else -> sessions.sessions(accountId, scope, filter, before)
        }
    }
}

class GetSessionUseCase(private val sessions: SessionRepository, private val guard: AccountGuard) {
    suspend operator fun invoke(accountId: String, sessionId: String): CloudSession = guard(accountId) { sessions.session(accountId, sessionId) }
}

/**
 * What the sessions list does next: load everything again, apply one change, or show the connection's state.
 * [RemoteSessions] comes first, once the server's capabilities are read: whether it has remote sessions.
 */
sealed interface SessionListSignal {
    data class RemoteSessions(val supported: Boolean) : SessionListSignal
    data object Reload : SessionListSignal
    data class Changed(val change: SessionChange) : SessionListSignal
    data class Connection(val reconnecting: Boolean) : SessionListSignal
}

/**
 * Keeps the list current: the caller-wide feed when the server has it (`cloud.events.v1`), a reload every
 * [pollMillis] otherwise. The first [SessionListSignal.Reload] comes once the feed is open (or at once when polling),
 * so nothing changed between loading the list and following the feed is missed; a gap reloads too.
 */
class FollowSessionListUseCase(
    private val sessions: SessionRepository,
    private val features: FeaturesRepository,
    private val guard: AccountGuard,
    private val pollMillis: Long = 15_000,
) {
    operator fun invoke(accountId: String): Flow<SessionListSignal> = guard.flow(accountId, flow {
        val announced = featuresWhenReachable(accountId)
        val remote = Features.REMOTE_SESSIONS in announced
        emit(SessionListSignal.RemoteSessions(remote))
        if (Features.EVENTS in announced) {
            // Every kind: the list filters the events itself, so changing its filter never reopens the feed.
            sessions.sessionChanges(accountId, if (remote) SessionKind.entries.toSet() else emptySet()).collect { update ->
                when (update) {
                    is LiveUpdate.Opened -> {
                        emit(SessionListSignal.Connection(reconnecting = false))
                        if (!update.resumed) emit(SessionListSignal.Reload)
                    }
                    LiveUpdate.Gap -> emit(SessionListSignal.Reload)
                    is LiveUpdate.Item -> emit(SessionListSignal.Changed(update.value))
                    is LiveUpdate.Reconnecting -> emit(SessionListSignal.Connection(reconnecting = true))
                    // The feed never ends; were it to, the list stays as it is.
                    LiveUpdate.Ended -> Unit
                }
            }
        } else {
            // Reached at last after retries at start: "reconnecting" ends here, as an opened feed would end it.
            emit(SessionListSignal.Connection(reconnecting = false))
            while (true) {
                emit(SessionListSignal.Reload)
                delay(pollMillis)
            }
        }
    })

    /** Offline or a failing server at start: waits and asks again, as a cut stream does, instead of ending the list. */
    private suspend fun FlowCollector<SessionListSignal>.featuresWhenReachable(accountId: String): Set<String> {
        var wait = FIRST_RETRY_MILLIS
        while (true) {
            try {
                return features.features(accountId, refresh = true)
            } catch (error: Exception) {
                val transient = error is CompanionException.Unreachable || error is CompanionException.RateLimited ||
                    (error is CompanionException.Server && (error.statusCode >= 500 || error.statusCode == 408))
                if (!transient) throw error
                emit(SessionListSignal.Connection(reconnecting = true))
                delay(wait)
                wait = minOf(wait * 2, MAX_RETRY_MILLIS)
            }
        }
    }

    private companion object {
        const val FIRST_RETRY_MILLIS = 2_000L
        const val MAX_RETRY_MILLIS = 30_000L
    }
}
