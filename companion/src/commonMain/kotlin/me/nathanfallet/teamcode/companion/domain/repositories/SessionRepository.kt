package me.nathanfallet.teamcode.companion.domain.repositories

import kotlinx.coroutines.flow.Flow
import me.nathanfallet.teamcode.companion.domain.models.CloudSession
import me.nathanfallet.teamcode.companion.domain.models.InputReceipt
import me.nathanfallet.teamcode.companion.domain.models.LiveUpdate
import me.nathanfallet.teamcode.companion.domain.models.NewDeviceSession
import me.nathanfallet.teamcode.companion.domain.models.NewSession
import me.nathanfallet.teamcode.companion.domain.models.PermissionAnswer
import me.nathanfallet.teamcode.companion.domain.models.Profile
import me.nathanfallet.teamcode.companion.domain.models.SessionChange
import me.nathanfallet.teamcode.companion.domain.models.SessionFilter
import me.nathanfallet.teamcode.companion.domain.models.SessionKind
import me.nathanfallet.teamcode.companion.domain.models.SessionPage
import me.nathanfallet.teamcode.companion.domain.models.SessionScope

/** Cloud sessions of an account's server. Failures are [me.nathanfallet.teamcode.companion.domain.models.CompanionException]s. */
interface SessionRepository {
    /**
     * Newest first, at most 50 per page; [before] is the previous page's cursor. [kinds]: the kinds asked for (only to
     * a server that announces remote sessions); empty for the server's default, cloud sessions only.
     */
    suspend fun sessions(accountId: String, scope: SessionScope, filter: SessionFilter, before: String?, kinds: Set<SessionKind> = emptySet()): SessionPage
    suspend fun session(accountId: String, sessionId: String): CloudSession
    /** The profiles the member may start a session from. */
    suspend fun profiles(accountId: String): List<Profile>

    /** [idempotencyKey]: a retry with the same key and body returns the session the first attempt created. */
    suspend fun create(accountId: String, session: NewSession, idempotencyKey: String): CloudSession
    /**
     * Starts a remote session on one of the member's online devices, in a folder it published
     * (`cloud.sessions.remote.daemon.v1`); the device's daemon hosts it. [idempotencyKey] as for [create].
     */
    suspend fun createOnDevice(accountId: String, session: NewDeviceSession, idempotencyKey: String): CloudSession
    /**
     * [clientMessageId]: a retry returns the first acknowledgement instead of queueing the message twice. [fileIds]: files
     * uploaded to this session and not sent yet (`cloud.files.v1`); with files, [text] may be empty.
     */
    suspend fun send(accountId: String, sessionId: String, text: String, clientMessageId: String, fileIds: List<String> = emptyList()): InputReceipt
    /** Answers a permission request; [idempotencyKey]: a retry returns the first acknowledgement, even once consumed. */
    suspend fun answer(accountId: String, sessionId: String, answer: PermissionAnswer, idempotencyKey: String): InputReceipt
    suspend fun interrupt(accountId: String, sessionId: String, idempotencyKey: String): InputReceipt
    /** Stops the background task [taskId] of the session's running agent (`cloud.sessions.background-tasks.v1`). */
    suspend fun stopTask(accountId: String, sessionId: String, taskId: String, idempotencyKey: String): InputReceipt
    suspend fun pause(accountId: String, sessionId: String): CloudSession
    suspend fun resume(accountId: String, sessionId: String): CloudSession
    /** Renames the session (`cloud.sessions.rename.v1`); the server applies the creation's name rules. */
    suspend fun rename(accountId: String, sessionId: String, name: String): CloudSession
    /** Ends the session and deletes its workspace (`DELETE /cloud/sessions/{id}`); idempotent. */
    suspend fun end(accountId: String, sessionId: String)

    /**
     * The caller-wide feed (`cloud.events.v1`), session events only, reconnecting by itself. The first connection
     * starts from now and reports `Opened(resumed = false)`: load the list then, not before. [kinds] as for [sessions].
     * [waiting]: also `cloud.session.waiting` and `cloud.session.answered`, only to a server that announces
     * `cloud.sessions.waiting.v1` (an older one refuses a type it does not know). [waits]: also `cloud.wait.*`, only to a
     * server that announces `waits.v1`. [renames]: also `cloud.session.renamed`, only to a server that announces
     * `cloud.sessions.rename.v1`. Screens follow it through
     * [me.nathanfallet.teamcode.companion.domain.usecases.sessions.SessionFeed], which shares one connection.
     */
    fun sessionChanges(accountId: String, kinds: Set<SessionKind> = emptySet(), waiting: Boolean = false, waits: Boolean = false, renames: Boolean = false): Flow<LiveUpdate<SessionChange>>
}
