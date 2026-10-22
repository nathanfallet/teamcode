package me.nathanfallet.teamcode.companion.infrastructure.repositories

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import me.nathanfallet.teamcode.companion.domain.models.LiveUpdate
import me.nathanfallet.teamcode.companion.domain.models.TranscriptEvent
import me.nathanfallet.teamcode.companion.domain.models.TranscriptPage
import me.nathanfallet.teamcode.companion.domain.repositories.TranscriptRepository
import me.nathanfallet.teamcode.companion.infrastructure.stream.ReconnectPolicy
import me.nathanfallet.teamcode.companion.infrastructure.stream.reconnecting

class RemoteTranscriptRepository(private val calls: AccountCalls, private val policy: ReconnectPolicy) : TranscriptRepository {
    override suspend fun page(accountId: String, sessionId: String, before: Long?): TranscriptPage =
        calls.withAccount(accountId, resource = true) { it.cloudEvents(sessionId, before = before, limit = PAGE).domain() }

    override fun follow(accountId: String, sessionId: String, after: Long): Flow<LiveUpdate<TranscriptEvent>> = flow {
        // One cursor per collection: each reconnection resumes after the last event delivered.
        var cursor = after
        emitAll(reconnecting(policy) { send, itemSent ->
            val client = calls.client(accountId)
            client.followCloudEvents(sessionId, cursor, onOpen = { send(LiveUpdate.Opened(resumed = true)) }) { event ->
                send(LiveUpdate.Item(event.domain()))
                cursor = event.seq
                itemSent()
            }
        })
    }

    private companion object {
        const val PAGE = 100
    }
}
