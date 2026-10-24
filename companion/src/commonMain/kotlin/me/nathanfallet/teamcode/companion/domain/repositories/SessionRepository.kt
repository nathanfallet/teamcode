package me.nathanfallet.teamcode.companion.domain.repositories

import kotlinx.coroutines.flow.Flow
import me.nathanfallet.teamcode.companion.domain.models.CloudSession
import me.nathanfallet.teamcode.companion.domain.models.InputReceipt
import me.nathanfallet.teamcode.companion.domain.models.LiveUpdate
import me.nathanfallet.teamcode.companion.domain.models.NewSession
import me.nathanfallet.teamcode.companion.domain.models.PermissionAnswer
import me.nathanfallet.teamcode.companion.domain.models.Profile
import me.nathanfallet.teamcode.companion.domain.models.SessionChange
import me.nathanfallet.teamcode.companion.domain.models.SessionGroup
import me.nathanfallet.teamcode.companion.domain.models.SessionKind
import me.nathanfallet.teamcode.companion.domain.models.SessionPage
import me.nathanfallet.teamcode.companion.domain.models.SessionScope

/** Cloud sessions of an account's server. Failures are [me.nathanfallet.teamcode.companion.domain.models.CompanionException]s. */
interface SessionRepository {
    /**
     * Newest first, at most 50 per page; [before] is the previous page's cursor. [kinds]: the kinds asked for (only to
     * a server that announces remote sessions); empty for the server's default, cloud sessions only.
     */
    suspend fun sessions(accountId: String, scope: SessionScope, group: SessionGroup, before: String?, kinds: Set<SessionKind> = emptySet()): SessionPage
    suspend fun session(accountId: String, sessionId: String): CloudSession
    /** The profiles the member may start a session from. */
    suspend fun profiles(accountId: String): List<Profile>

    /** [idempotencyKey]: a retry with the same key and body returns the session the first attempt created. */
    suspend fun create(accountId: String, session: NewSession, idempotencyKey: String): CloudSession
    /**
     * [clientMessageId]: a retry returns the first acknowledgement instead of queueing the message twice. [fileIds]: files
     * uploaded to this session and not sent yet (`cloud.files.v1`); with files, [text] may be empty.
     */
    suspend fun send(accountId: String, sessionId: String, text: String, clientMessageId: String, fileIds: List<String> = emptyList()): InputReceipt
    /** Answers a permission request; [idempotencyKey]: a retry returns the first acknowledgement, even once consumed. */
    suspend fun answer(accountId: String, sessionId: String, answer: PermissionAnswer, idempotencyKey: String): InputReceipt
    suspend fun interrupt(accountId: String, sessionId: String, idempotencyKey: String): InputReceipt
    suspend fun pause(accountId: String, sessionId: String): CloudSession
    suspend fun resume(accountId: String, sessionId: String): CloudSession

    /**
     * The caller-wide feed (`cloud.events.v1`), session events only, reconnecting by itself. The first connection
     * starts from now and reports `Opened(resumed = false)`: load the list then, not before. [kinds] as for [sessions].
     */
    fun sessionChanges(accountId: String, kinds: Set<SessionKind> = emptySet()): Flow<LiveUpdate<SessionChange>>
}
