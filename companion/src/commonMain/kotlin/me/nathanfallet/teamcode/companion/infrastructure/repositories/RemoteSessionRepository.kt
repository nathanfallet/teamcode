package me.nathanfallet.teamcode.companion.infrastructure.repositories

import kotlinx.coroutines.flow.Flow
import me.nathanfallet.teamcode.api.models.CloudAgentRequest
import me.nathanfallet.teamcode.api.models.CloudInputView
import me.nathanfallet.teamcode.api.models.CreateCloudSessionRequest
import me.nathanfallet.teamcode.api.models.SendCloudMessageRequest
import me.nathanfallet.teamcode.companion.domain.models.InputReceipt
import me.nathanfallet.teamcode.companion.domain.models.NewSession
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

    override suspend fun create(accountId: String, session: NewSession, idempotencyKey: String): CloudSession = calls.withAccount(accountId) { client ->
        val agent = if (session.model != null || session.effort != null) CloudAgentRequest(session.model, session.effort) else null
        client.createCloudSession(CreateCloudSessionRequest(name = session.name, profileId = session.profileId, agent = agent, initialMessage = session.initialMessage), idempotencyKey).domain()
    }

    override suspend fun send(accountId: String, sessionId: String, text: String, clientMessageId: String): InputReceipt =
        calls.withAccount(accountId, resource = true) { it.sendCloudMessage(sessionId, SendCloudMessageRequest(text, wake = true), idempotencyKey = clientMessageId).receipt() }

    override suspend fun interrupt(accountId: String, sessionId: String, idempotencyKey: String): InputReceipt =
        calls.withAccount(accountId, resource = true) { it.interruptCloudSession(sessionId, idempotencyKey).receipt() }

    override suspend fun pause(accountId: String, sessionId: String): CloudSession =
        calls.withAccount(accountId, resource = true) { it.pauseCloudSession(sessionId).domain() }

    override suspend fun resume(accountId: String, sessionId: String): CloudSession =
        calls.withAccount(accountId, resource = true) { it.resumeCloudSession(sessionId).domain() }

    override fun sessionChanges(accountId: String): Flow<LiveUpdate<SessionChange>> = flow {
        // One cursor per collection, shared by its attempts: a reconnection resumes after the last event seen, any subject.
        var cursor: Long? = null
        emitAll(reconnecting(policy) { send, itemSent ->
            val client = calls.client(accountId)
            val resumed = cursor != null
            client.followTeamEvents(
                after = cursor,
                types = SESSION_EVENT_TYPES,
                onGap = { send(LiveUpdate.Gap) },
                onOpen = { itemSent(); send(LiveUpdate.Opened(resumed)) },
            ) { event ->
                event.sessionChange()?.let { send(LiveUpdate.Item(it)) }
                cursor = event.seq
                itemSent()
            }
        })
    }
}

/**
 * The session events of the feed's first version (`cloud.events.v1`): naming a type an older server does not know would
 * be refused, and runs (`run.*`) are not shown.
 */
private val SESSION_EVENT_TYPES = setOf("cloud.session.created", "cloud.session.paused", "cloud.session.woken", "cloud.session.resumed", "cloud.session.ended", "cloud.turn")

private fun CloudInputView.receipt() = InputReceipt(id, status, woke, reason)
