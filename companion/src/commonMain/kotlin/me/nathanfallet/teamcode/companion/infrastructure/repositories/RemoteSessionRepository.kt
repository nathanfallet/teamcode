package me.nathanfallet.teamcode.companion.infrastructure.repositories

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import me.nathanfallet.teamcode.companion.domain.models.CloudSession
import me.nathanfallet.teamcode.companion.domain.models.LiveUpdate
import me.nathanfallet.teamcode.companion.domain.models.Profile
import me.nathanfallet.teamcode.companion.domain.models.SessionChange
import me.nathanfallet.teamcode.companion.domain.models.SessionGroup
import me.nathanfallet.teamcode.companion.domain.models.SessionPage
import me.nathanfallet.teamcode.companion.domain.models.SessionScope
import me.nathanfallet.teamcode.companion.domain.repositories.SessionRepository
import me.nathanfallet.teamcode.companion.infrastructure.stream.ReconnectPolicy
import me.nathanfallet.teamcode.companion.infrastructure.stream.reconnecting

class RemoteSessionRepository(private val calls: AccountCalls, private val policy: ReconnectPolicy) : SessionRepository {
    override suspend fun sessions(accountId: String, scope: SessionScope, group: SessionGroup, before: String?): SessionPage = calls.withAccount(accountId) { client ->
        val page = client.cloudSessions(owner = if (scope == SessionScope.TEAM) "all" else null, states = group.states.map { it.wire }, before = before, limit = 50)
        SessionPage(page.sessions.map { it.domain() }, page.nextBefore)
    }

    override suspend fun session(accountId: String, sessionId: String): CloudSession =
        calls.withAccount(accountId, resource = true) { it.cloudSession(sessionId).domain() }

    override suspend fun profiles(accountId: String): List<Profile> = calls.withAccount(accountId) { client -> client.cloudProfiles().map { it.domain() } }

    override fun sessionChanges(accountId: String): Flow<LiveUpdate<SessionChange>> = flow {
        // One cursor per collection, shared by its attempts: a reconnection resumes after the last event seen, any subject.
        var cursor: Long? = null
        emitAll(reconnecting(policy) { send, itemSent ->
            val client = calls.client(accountId)
            val resumed = cursor != null
            client.followTeamEvents(
                after = cursor,
                onGap = { send(LiveUpdate.Gap) },
                onOpen = { send(LiveUpdate.Opened(resumed)) },
            ) { event ->
                event.sessionChange()?.let { send(LiveUpdate.Item(it)) }
                cursor = event.seq
                itemSent()
            }
        })
    }
}
