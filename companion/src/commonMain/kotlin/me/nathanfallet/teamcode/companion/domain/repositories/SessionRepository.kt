package me.nathanfallet.teamcode.companion.domain.repositories

import kotlinx.coroutines.flow.Flow
import me.nathanfallet.teamcode.companion.domain.models.CloudSession
import me.nathanfallet.teamcode.companion.domain.models.LiveUpdate
import me.nathanfallet.teamcode.companion.domain.models.Profile
import me.nathanfallet.teamcode.companion.domain.models.SessionChange
import me.nathanfallet.teamcode.companion.domain.models.SessionGroup
import me.nathanfallet.teamcode.companion.domain.models.SessionPage
import me.nathanfallet.teamcode.companion.domain.models.SessionScope

/** Cloud sessions of an account's server. Failures are [me.nathanfallet.teamcode.companion.domain.models.CompanionException]s. */
interface SessionRepository {
    /** Newest first, at most 50 per page; [before] is the previous page's cursor. */
    suspend fun sessions(accountId: String, scope: SessionScope, group: SessionGroup, before: String?): SessionPage
    suspend fun session(accountId: String, sessionId: String): CloudSession
    /** The profiles the member may start a session from. */
    suspend fun profiles(accountId: String): List<Profile>

    /**
     * The caller-wide feed (`cloud.events.v1`), session events only, reconnecting by itself. The first connection
     * starts from now and reports `Opened(resumed = false)`: load the list then, not before.
     */
    fun sessionChanges(accountId: String): Flow<LiveUpdate<SessionChange>>
}
