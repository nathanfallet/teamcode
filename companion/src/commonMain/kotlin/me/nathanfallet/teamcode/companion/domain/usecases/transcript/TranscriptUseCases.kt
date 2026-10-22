package me.nathanfallet.teamcode.companion.domain.usecases.transcript

import kotlinx.coroutines.flow.Flow
import me.nathanfallet.teamcode.companion.domain.models.CompanionException
import me.nathanfallet.teamcode.companion.domain.models.LiveUpdate
import me.nathanfallet.teamcode.companion.domain.models.TranscriptEvent
import me.nathanfallet.teamcode.companion.domain.models.TranscriptPage
import me.nathanfallet.teamcode.companion.domain.repositories.Features
import me.nathanfallet.teamcode.companion.domain.repositories.FeaturesRepository
import me.nathanfallet.teamcode.companion.domain.repositories.TranscriptRepository
import me.nathanfallet.teamcode.companion.domain.usecases.auth.AccountGuard

class LoadTranscriptUseCase(private val transcripts: TranscriptRepository, private val features: FeaturesRepository, private val guard: AccountGuard) {
    suspend operator fun invoke(accountId: String, sessionId: String, before: Long? = null): TranscriptPage = guard(accountId) {
        if (Features.TRANSCRIPT !in features.features(accountId)) throw CompanionException.Unsupported(Features.TRANSCRIPT)
        transcripts.page(accountId, sessionId, before)
    }
}

class FollowTranscriptUseCase(private val transcripts: TranscriptRepository, private val guard: AccountGuard) {
    operator fun invoke(accountId: String, sessionId: String, after: Long): Flow<LiveUpdate<TranscriptEvent>> =
        guard.flow(accountId, transcripts.follow(accountId, sessionId, after))
}

/**
 * The events a session screen shows: history pages and live events merged by sequence, each once, in order.
 * Pure: every merge returns a new transcript.
 */
data class Transcript(
    val events: List<TranscriptEvent> = emptyList(),
    /** The cursor of the next older page; null when everything kept was loaded (or nothing yet). */
    val nextBefore: Long? = null,
    /** The newest sequence known, from events or from a page's `lastSeq`: following resumes after it. */
    val newestSeq: Long = 0,
) {
    /** The first page: the newest events. */
    fun withLatest(page: TranscriptPage): Transcript = merged(page.events).copy(nextBefore = page.nextBefore).let { it.copy(newestSeq = maxOf(it.newestSeq, page.lastSeq)) }

    fun withOlder(page: TranscriptPage): Transcript = merged(page.events).copy(nextBefore = page.nextBefore)

    fun withLive(event: TranscriptEvent): Transcript = merged(listOf(event))

    private fun merged(incoming: List<TranscriptEvent>): Transcript {
        if (incoming.isEmpty()) return this
        val last = events.lastOrNull()?.seq ?: 0
        val sorted = incoming.sortedBy { it.seq }
        val combined = if (sorted.first().seq > last) {
            events + sorted.distinctBy { it.seq }
        } else {
            // Known sequences keep the first copy: an event never changes once written.
            (events + sorted).distinctBy { it.seq }.sortedBy { it.seq }
        }
        return copy(events = combined, newestSeq = maxOf(newestSeq, combined.last().seq))
    }
}
