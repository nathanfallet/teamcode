package me.nathanfallet.teamcode.companion.domain.usecases.sessions

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import me.nathanfallet.teamcode.companion.domain.models.CompanionException
import me.nathanfallet.teamcode.companion.domain.models.LiveUpdate
import me.nathanfallet.teamcode.companion.domain.models.SessionChange
import me.nathanfallet.teamcode.companion.domain.models.SessionKind
import me.nathanfallet.teamcode.companion.domain.repositories.Features
import me.nathanfallet.teamcode.companion.domain.repositories.FeaturesRepository
import me.nathanfallet.teamcode.companion.domain.repositories.SessionRepository

/**
 * The caller-wide feed (`cloud.events.v1`) of an account, held once for the whole app: every screen that follows it
 * (the sessions list, the sidebar, a list of what waits for the member) shares one connection, with every session kind
 * and, from a server with `cloud.sessions.waiting.v1`, the waiting events. The connection opens with the first
 * follower and closes [lingerMillis] after the last one left (a screen replaced by the next one does not reconnect);
 * it reconnects by itself after a drop, resuming after the last event ([SessionRepository.sessionChanges]).
 *
 * Each follower starts with `Opened(resumed = false)` once the feed is open, at once when it already was: that is
 * when it loads what it shows, so nothing changed since is missed. A `Gap`, or another `Opened(resumed = false)` (a
 * connection that could not resume), means "load again". A failure of the connection (a refusal, a revoked token)
 * ends every follower with it; the next follower opens a new connection.
 */
class SessionFeed(
    private val sessions: SessionRepository,
    private val features: FeaturesRepository,
    private val scope: CoroutineScope,
    private val lingerMillis: Long = 5_000,
) {
    private sealed interface Element {
        data class Update(val update: LiveUpdate<SessionChange>) : Element
        /** The connection ended: with [error], or normally (the feed never ends; were it to, its followers end). */
        data class End(val error: Exception?) : Element
    }

    private class Connection {
        /** Each update is handed to every follower before the next is read: a slow screen slows the reading, not memory. */
        val elements = MutableSharedFlow<Element>(extraBufferCapacity = 64)
        var followers = 0
        var open = false
        var end: Element.End? = null
        var job: Job? = null
        var closing: Job? = null
    }

    private val lock = Mutex()
    private val connections = mutableMapOf<String, Connection>()

    /** The account's session events, reconnecting by themselves; ends only with the connection's failure. */
    fun follow(accountId: String): Flow<LiveUpdate<SessionChange>> = flow {
        val connection = acquire(accountId)
        try {
            var loaded = false
            emitAll(connection.elements
                .onSubscription {
                    // Subscribed before reading the state: an update published meanwhile is received, never lost.
                    val end = connection.end
                    when {
                        end != null -> emit(end)
                        connection.open -> emit(Element.Update(LiveUpdate.Opened(resumed = false)))
                    }
                }
                .transformWhile { element ->
                    when (element) {
                        is Element.End -> { element.error?.let { throw it }; false }
                        is Element.Update -> {
                            val update = element.update
                            // A follower that joined during a reconnection loads at the reconnection's Opened, resumed or not.
                            emit(if (update is LiveUpdate.Opened && !loaded) LiveUpdate.Opened(resumed = false) else update)
                            if (update is LiveUpdate.Opened) loaded = true
                            true
                        }
                    }
                })
        } finally {
            withContext(NonCancellable) { release(accountId, connection) }
        }
    }

    /** How many connections are open now, for tests. */
    internal suspend fun openConnections(): Int = lock.withLock { connections.size }

    private suspend fun acquire(accountId: String): Connection = lock.withLock {
        val connection = connections[accountId] ?: Connection().also { created ->
            connections[accountId] = created
            created.job = scope.launch { run(accountId, created) }
        }
        connection.followers++
        connection.closing?.cancel(); connection.closing = null
        connection
    }

    private suspend fun release(accountId: String, connection: Connection) = lock.withLock {
        connection.followers--
        if (connection.followers > 0 || connections[accountId] !== connection) return@withLock
        connection.closing = scope.launch {
            delay(lingerMillis)
            lock.withLock {
                if (connection.followers > 0 || connections[accountId] !== connection) return@launch
                connections.remove(accountId)
                connection.job?.cancel()
            }
        }
    }

    private suspend fun run(accountId: String, connection: Connection) {
        val error: Exception? = try {
            // Read by the follower just before (refreshed), so from the cache here.
            val announced = features.features(accountId)
            val kinds = if (Features.REMOTE_SESSIONS in announced) SessionKind.entries.toSet() else emptySet()
            sessions.sessionChanges(accountId, kinds, waiting = Features.WAITING in announced, waits = Features.WAITS in announced).collect { update ->
                when (update) {
                    is LiveUpdate.Opened -> connection.open = true
                    is LiveUpdate.Reconnecting -> connection.open = false
                    else -> Unit
                }
                connection.elements.emit(Element.Update(update))
            }
            null
        } catch (cancelled: CancellationException) {
            // Closed on purpose (no follower left): nothing to tell. A cancellation from elsewhere (an HTTP client closed
            // under the stream) ends the connection like a network failure, so its followers never wait for nothing.
            if (!currentCoroutineContext().isActive) throw cancelled
            CompanionException.Unreachable()
        } catch (failure: Exception) {
            failure
        }
        val end = Element.End(error)
        lock.withLock {
            connection.end = end
            connection.open = false
            if (connections[accountId] === connection) connections.remove(accountId)
        }
        connection.elements.emit(end)
    }
}
