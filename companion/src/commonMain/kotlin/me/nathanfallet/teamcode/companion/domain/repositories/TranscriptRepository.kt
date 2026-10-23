package me.nathanfallet.teamcode.companion.domain.repositories

import kotlinx.coroutines.flow.Flow
import me.nathanfallet.teamcode.companion.domain.models.LiveUpdate
import me.nathanfallet.teamcode.companion.domain.models.TranscriptEvent
import me.nathanfallet.teamcode.companion.domain.models.TranscriptPage

interface TranscriptRepository {
    /** The newest events before [before] (the newest of all when null), oldest first. */
    suspend fun page(accountId: String, sessionId: String, before: Long?): TranscriptPage

    /** Events after [after], live, reconnecting by itself from the last one delivered; completes after `Ended`. */
    fun follow(accountId: String, sessionId: String, after: Long): Flow<LiveUpdate<TranscriptEvent>>
}
