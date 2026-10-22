package me.nathanfallet.teamcode.companion.domain.usecases.sessions

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import me.nathanfallet.teamcode.companion.domain.models.CloudSession
import me.nathanfallet.teamcode.companion.domain.models.CompanionException
import me.nathanfallet.teamcode.companion.domain.models.LiveUpdate
import me.nathanfallet.teamcode.companion.domain.models.SessionChange
import me.nathanfallet.teamcode.companion.domain.models.SessionGroup
import me.nathanfallet.teamcode.companion.domain.models.SessionPage
import me.nathanfallet.teamcode.companion.domain.models.SessionScope
import me.nathanfallet.teamcode.companion.domain.repositories.Features
import me.nathanfallet.teamcode.companion.domain.repositories.FeaturesRepository
import me.nathanfallet.teamcode.companion.domain.repositories.SessionRepository
import me.nathanfallet.teamcode.companion.domain.usecases.auth.AccountGuard

class LoadSessionsUseCase(private val sessions: SessionRepository, private val features: FeaturesRepository, private val guard: AccountGuard) {
    suspend operator fun invoke(accountId: String, scope: SessionScope, group: SessionGroup, before: String? = null): SessionPage = guard(accountId) {
        if (Features.SESSIONS !in features.features(accountId)) throw CompanionException.Unsupported(Features.SESSIONS)
        sessions.sessions(accountId, scope, group, before)
    }
}

class GetSessionUseCase(private val sessions: SessionRepository, private val guard: AccountGuard) {
    suspend operator fun invoke(accountId: String, sessionId: String): CloudSession = guard(accountId) { sessions.session(accountId, sessionId) }
}

/** What the sessions list does next: load everything again, apply one change, or show the connection's state. */
sealed interface SessionListSignal {
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
        if (Features.EVENTS in featuresWhenReachable(accountId)) {
            sessions.sessionChanges(accountId).collect { update ->
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
