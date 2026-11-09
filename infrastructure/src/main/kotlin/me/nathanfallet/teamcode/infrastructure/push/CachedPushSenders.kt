package me.nathanfallet.teamcode.infrastructure.push

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.nathanfallet.teamcode.domain.notifications.*

/**
 * The senders of the teams whose push is on, from their stored settings (read on every call, one query for all the
 * teams asked). A relay sender is shared by every team using the same relay; a team's Firebase sender is kept while its
 * settings version is unchanged and closed when they change. A sender closed while a push is in flight makes that push
 * a retry.
 */
class CachedPushSenders(
    private val settings: PushSettingsStore,
    private val relay: (relayUrl: String) -> PushSender = { url -> error("No relay sender for $url") },
    private val firebase: (serviceAccountJson: String) -> PushSender = { error("No Firebase sender") },
) : PushSenders, AutoCloseable {
    private val mutex = Mutex()
    private val relays = mutableMapOf<String, PushSender>()
    private val teams = mutableMapOf<String, Pair<Int, PushSender>>()

    override suspend fun senders(teamIds: Set<String>): Map<String, PushSender> {
        val connections = settings.connections(teamIds)
        return mutex.withLock {
            connections.mapValues { (team, connection) ->
                when (connection.provider) {
                    PushProvider.RELAY -> { closeTeam(team); relays.getOrPut(connection.relayUrl) { relay(connection.relayUrl) } }
                    else -> teams[team]?.takeIf { it.first == connection.version }?.second
                        ?: firebase(connection.firebaseServiceAccount!!).also { closeTeam(team); teams[team] = connection.version to it }
                }
            }.also { active -> (teams.keys intersect teamIds).filter { it !in active }.forEach(::closeTeam) }
        }
    }

    private fun closeTeam(team: String) { teams.remove(team)?.second?.let { (it as? AutoCloseable)?.close() } }

    override fun close() {
        (relays.values + teams.values.map { it.second }).forEach { (it as? AutoCloseable)?.close() }
        relays.clear(); teams.clear()
    }
}
