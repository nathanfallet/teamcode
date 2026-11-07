package me.nathanfallet.teamcode.companion.infrastructure.repositories

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import me.nathanfallet.teamcode.companion.domain.models.KnownAgent
import me.nathanfallet.teamcode.companion.domain.models.LiveUpdate
import me.nathanfallet.teamcode.companion.domain.models.TranscriptEvent
import me.nathanfallet.teamcode.companion.domain.models.TranscriptPage
import me.nathanfallet.teamcode.companion.domain.repositories.TranscriptRepository
import me.nathanfallet.teamcode.companion.infrastructure.stream.ReconnectPolicy
import me.nathanfallet.teamcode.companion.infrastructure.stream.reconnecting

class RemoteTranscriptRepository(private val calls: AccountCalls, private val policy: ReconnectPolicy) : TranscriptRepository {
    override suspend fun page(accountId: String, sessionId: String, before: Long?): TranscriptPage = feedPage(accountId, sessionId, before, null)

    override suspend fun feedPage(accountId: String, sessionId: String, before: Long?, feed: String?): TranscriptPage =
        // Without a cursor the server pages from the oldest event: the newest page is the one before any sequence.
        calls.withAccount(accountId, resource = true) { it.cloudEvents(sessionId, before = before ?: NEWEST, limit = PAGE, feed = feed).domain() }

    override suspend fun agents(accountId: String, sessionId: String): List<KnownAgent> =
        calls.withAccount(accountId, resource = true) { it.cloudSubagents(sessionId).agents.map { agent -> agent.domain() } }

    override fun follow(accountId: String, sessionId: String, after: Long): Flow<LiveUpdate<TranscriptEvent>> = flow {
        // One cursor per collection: each reconnection resumes after the last event delivered.
        var cursor = after
        emitAll(reconnecting(policy, resource = true) { send, progress ->
            val client = calls.client(accountId)
            client.followCloudEvents(sessionId, cursor, onOpen = { progress.opened(); send(LiveUpdate.Opened(resumed = true)) }) { event ->
                send(LiveUpdate.Item(event.domain()))
                cursor = event.seq
                progress.itemSent()
            }
        })
    }

    private companion object {
        const val PAGE = 100
        const val NEWEST = Long.MAX_VALUE
    }
}
