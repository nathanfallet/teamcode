package me.nathanfallet.teamcode.domain.cloud

import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import me.nathanfallet.teamcode.domain.artifacts.ArtifactObjectStore
import me.nathanfallet.teamcode.domain.artifacts.ArtifactRules
import me.nathanfallet.teamcode.domain.artifacts.ArtifactStorageConnection
import me.nathanfallet.teamcode.domain.artifacts.ObjectStoreException
import me.nathanfallet.teamcode.domain.team.Principal
import me.nathanfallet.teamcode.domain.team.TeamFailure
import me.nathanfallet.teamcode.domain.team.requireCloudRead

/**
 * A file a member sent to a session, kept in the team's bucket until the session's host received it (or for a day).
 * [contentType], [sha256] and [completedAt] are null while it is being uploaded; [size] is then the announced size, or
 * 0. [inputId] is the message that carries it to the agent; [deletingSince] says its bytes are being removed.
 */
data class CloudFile(
    val id: String,
    val teamId: String,
    val sessionId: String,
    val authorId: String,
    val credentialId: String,
    val name: String,
    val contentType: String?,
    val size: Long,
    val sha256: String?,
    val clientKey: String?,
    val requestDigest: String?,
    val inputId: String?,
    val createdAt: Long,
    val completedAt: Long?,
    val expiresAt: Long,
    val consumedAt: Long? = null,
    val deletingSince: Long? = null,
)

/** What a message tells its host about a file: enough to download it, check it and name it. */
data class CloudFileRef(val id: String, val name: String, val size: Long, val contentType: String, val sha256: String)

sealed interface CloudFileBegin {
    data object Started : CloudFileBegin
    /** The idempotency key names a file already uploaded (or being uploaded) by this author to this session. */
    data class Existing(val file: CloudFile) : CloudFileBegin
}

sealed interface CloudFileOutcome {
    val file: CloudFile
    data class Created(override val file: CloudFile) : CloudFileOutcome
    data class Replayed(override val file: CloudFile) : CloudFileOutcome
}

/** Whether the caller's team can receive files now, and its limits; [reason] says why not. */
data class CloudFilePolicy(val available: Boolean, val reason: String?, val maxFileBytes: Long, val maxMessageBytes: Long, val maxFilesPerMessage: Int, val types: List<String>)

/** The request body, read in parts: [next] returns up to `max` bytes (fewer only at the end), null once it is exhausted. */
fun interface CloudFileBody { suspend fun next(max: Int): ByteArray? }

/** A file the runner may download, and its bytes part by part. */
class RunnerFileDownload(val file: CloudFile, val parts: suspend (emit: suspend (ByteArray) -> Unit) -> Unit)

/** The team's bucket, as artifacts use it. [active] only when its current version passed a connection test. */
interface CloudFileStorage {
    suspend fun active(teamId: String): ArtifactStorageConnection?
    /** Any stored connection, tested or not, so files can still be removed after the bucket failed a test. */
    suspend fun any(teamId: String): ArtifactStorageConnection?
}

/** Files of sessions in shared persistence. Writes that count a session's files lock the session row. */
interface CloudFileStore {
    suspend fun now(): Long
    /**
     * Under the session row lock: refuses a session that ended or changed owner since [expectedOwnerId], returns the file of
     * the same author, session and idempotency key when there is one, then checks the session's buffer and unsent files and
     * the author's day with [announcedBytes] (`429 upload_quota`), and inserts [file].
     */
    suspend fun begin(file: CloudFile, expectedOwnerId: String, announcedBytes: Long): CloudFileBegin
    /**
     * Under the session row lock again: rechecks the quotas with the real [size], completes the file, counts it in the
     * author's day and audits `cloud.file.uploaded` with its name, size and type.
     */
    suspend fun complete(id: String, name: String, contentType: String, size: Long, sha256: String, requestDigest: String?): CloudFile
    /** Marks a file whose upload failed for deletion. */
    suspend fun abandon(id: String)
    /**
     * The file a runner of [epoch] may download: complete, not being deleted, in [sessionId], and carried by a message
     * delivered to that very epoch and not acknowledged yet.
     */
    suspend fun deliverable(sessionId: String, epoch: Int, id: String): CloudFile?
    /** Marks files past their expiry for deletion; returns how many. */
    suspend fun expire(limit: Int): Int
    /** Files marked for deletion, oldest mark first. */
    suspend fun deleting(limit: Int): List<CloudFile>
    /** Deletes the rows of files whose bytes are gone. */
    suspend fun forget(ids: List<String>)
    /** Deletes per-member daily counters of days before [day] (days since the epoch, UTC). */
    suspend fun purgeUsage(day: Long): Int
}

/** Object keys: `<prefix>/session-files/<team>/<session>/<file>/<part>`, apart from artifacts' `<prefix>/<team>/`. */
object CloudFileKeys {
    fun root(prefix: String, teamId: String, sessionId: String, fileId: String) = (if (prefix.isEmpty()) "" else "$prefix/") + "session-files/$teamId/$sessionId/$fileId/"
    fun part(prefix: String, teamId: String, sessionId: String, fileId: String, number: Int) = root(prefix, teamId, sessionId, fileId) + number
}

/**
 * Files sent to sessions (`cloud.files.v1`). An upload is authorized exactly like a message ([CloudConversationService.authorizeMessage]);
 * its bytes go to the team's artifact bucket in 1 MiB parts while the type is recognized from them; its row only holds
 * metadata. A message names it by id; its host downloads it on the runner channel, and once the message is acknowledged
 * (or after a day) the [sweep] removes the bytes, then the row.
 */
class CloudFileService(
    private val files: CloudFileStore,
    private val sessions: CloudSessionStore,
    private val conversation: CloudConversationService,
    private val storage: CloudFileStorage,
    private val objects: ArtifactObjectStore,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {
    suspend fun policy(principal: Principal): CloudFilePolicy {
        principal.requireCloudRead()
        sessions.revalidate(principal)
        val settings = sessions.settings(principal.teamId)
        val available = storage.active(principal.teamId) != null
        return CloudFilePolicy(available, if (available) null else NOT_CONFIGURED, CloudFileRules.fileLimit(settings), CloudFileRules.messageLimit(settings),
            CloudFileRules.MAX_FILES_PER_MESSAGE, CloudFileRules.ALLOWED_TYPES)
    }

    /**
     * Receives a file for [sessionId]. [announcedBytes] (the `Content-Length`) refuses a file too large before any byte is
     * read. With [clientKey], a retry of the same file (same name and bytes) returns the first upload; other bytes under
     * the same key are `422 idempotency_conflict`.
     */
    suspend fun upload(principal: Principal, sessionId: String, rawName: String?, announcedBytes: Long?, clientKey: String?, body: CloudFileBody): CloudFileOutcome {
        val session = conversation.authorizeMessage(principal, sessionId)
        val name = CloudFileRules.sanitizeName(rawName)
        val connection = storage.active(principal.teamId) ?: throw notConfigured()
        val limit = CloudFileRules.fileLimit(sessions.settings(principal.teamId))
        if (announcedBytes != null && announcedBytes > limit) throw CloudFileRules.tooLarge(limit)
        val now = files.now()
        val draft = CloudFile(newId(), principal.teamId, session.id, principal.member.id, principal.tokenId, name, null, announcedBytes ?: 0, null,
            clientKey, null, null, now, null, now + UPLOAD_MILLIS)
        when (val begun = files.begin(draft, session.ownerId, announcedBytes ?: 0)) {
            is CloudFileBegin.Existing -> return replay(begun.file, name, body, limit)
            CloudFileBegin.Started -> Unit
        }
        try {
            val received = receive(connection, draft, body, limit)
            val digest = clientKey?.let { requestDigest(name, received.sha256, received.size) }
            return CloudFileOutcome.Created(files.complete(draft.id, CloudFileRules.finalName(name, received.contentType), received.contentType, received.size, received.sha256, digest))
        } catch (error: Throwable) {
            // Whatever ended the upload (a refusal, the client leaving, the bucket), its parts are removed by the sweep.
            withContext(NonCancellable) { try { files.abandon(draft.id) } catch (_: Exception) { } }
            throw error
        }
    }

    /** The file a runner downloads; fenced like every runner call, then `404` unless a message delivered to this epoch carries it. */
    suspend fun runnerFile(teamId: String, sessionId: String, epoch: Int, fileId: String): RunnerFileDownload {
        liveExecution(sessions, teamId, sessionId, epoch)
        val file = files.deliverable(sessionId, epoch, fileId) ?: throw TeamFailure("not_found", 404, "File not found.")
        val connection = storage.any(teamId) ?: throw TeamFailure("not_found", 404, "File not found.")
        val count = ArtifactRules.chunks(file.size)
        return RunnerFileDownload(file) { emit ->
            for (number in 0 until count) {
                val part = try { objects.get(connection, CloudFileKeys.part(connection.prefix, teamId, sessionId, file.id, number), maxBytes = CloudFileRules.PART_BYTES) }
                    catch (error: ObjectStoreException) { throw unavailable() }
                emit((part ?: throw TeamFailure("file_expired", 410, "The file's bytes are gone.")).bytes)
            }
        }
    }

    /**
     * Marks expired files, then removes the bytes of files marked for deletion (acknowledged, expired or abandoned) and
     * forgets their rows. A file whose bytes could not be removed is retried at the next pass. Returns the rows forgotten.
     */
    suspend fun sweep(limit: Int = 200): Int {
        files.expire(limit)
        var forgotten = 0
        for ((teamId, due) in files.deleting(limit).groupBy { it.teamId }) {
            val connection = storage.any(teamId)
            // No bucket any more: there is nothing left to remove.
            if (connection == null) { files.forget(due.map { it.id }); forgotten += due.size; continue }
            val removed = mutableListOf<String>()
            val keys = mutableMapOf<String, String>()
            for (file in due) {
                try {
                    // A complete file's parts follow from its size; an unfinished upload's are listed.
                    val parts = if (file.completedAt != null) (0 until ArtifactRules.chunks(file.size)).map { CloudFileKeys.part(connection.prefix, teamId, file.sessionId, file.id, it) }
                        else objects.list(connection, CloudFileKeys.root(connection.prefix, teamId, file.sessionId, file.id)).keys.map { it.key }
                    parts.forEach { keys[it] = file.id }
                    if (parts.isEmpty()) removed += file.id
                } catch (error: CancellationException) { throw error } catch (_: Exception) { }
            }
            val failed = mutableSetOf<String>()
            for (batch in keys.keys.chunked(1_000)) {
                try { objects.delete(connection, batch).forEach { key -> keys[key]?.let(failed::add) } }
                catch (error: CancellationException) { throw error } catch (_: Exception) { batch.forEach { key -> keys[key]?.let(failed::add) } }
            }
            removed += keys.values.toSet() - failed
            files.forget(removed.distinct())
            forgotten += removed.distinct().size
        }
        files.purgeUsage(files.now() / CloudFileRules.DAY_MILLIS - 1)
        return forgotten
    }

    private class Received(val size: Long, val sha256: String, val contentType: String)

    private suspend fun receive(connection: ArtifactStorageConnection, file: CloudFile, body: CloudFileBody, limit: Long): Received {
        val digest = MessageDigest.getInstance("SHA-256")
        val text = CloudFileRules.TextCheck()
        var size = 0L
        var number = 0
        var binary: String? = null
        while (true) {
            val part = body.next(CloudFileRules.PART_BYTES) ?: break
            if (part.isEmpty()) continue
            size += part.size
            if (size > limit) throw CloudFileRules.tooLarge(limit)
            // The first part holds the whole head: the body is read in full parts until its end.
            if (number == 0) binary = CloudFileRules.magicType(part)
            if (binary == null) { text.feed(part); if (!text.valid) throw CloudFileRules.typeNotAllowed() }
            digest.update(part)
            try { objects.put(connection, CloudFileKeys.part(connection.prefix, file.teamId, file.sessionId, file.id, number), part) }
            catch (_: ObjectStoreException) { throw unavailable() }
            number++
        }
        if (size == 0L) throw TeamFailure("empty_file", 400, "The file is empty.")
        val contentType = binary ?: if (text.finish()) CloudFileRules.textType(file.name) else throw CloudFileRules.typeNotAllowed()
        return Received(size, digest.digest().joinToString("") { "%02x".format(it) }, contentType)
    }

    /** A retry under the same key: the bytes are read again (not stored) and must be the same file. */
    private suspend fun replay(existing: CloudFile, name: String, body: CloudFileBody, limit: Long): CloudFileOutcome {
        if (existing.completedAt == null) throw TeamFailure("file_upload_in_progress", 409, "A file with this idempotency key is still being uploaded.")
        val digest = MessageDigest.getInstance("SHA-256")
        var size = 0L
        while (true) {
            val part = body.next(CloudFileRules.PART_BYTES) ?: break
            size += part.size
            if (size > limit) throw CloudFileRules.tooLarge(limit)
            digest.update(part)
        }
        if (existing.requestDigest != requestDigest(name, digest.digest().joinToString("") { "%02x".format(it) }, size))
            throw TeamFailure("idempotency_conflict", 422, "This idempotency key was already used with a different file.")
        return CloudFileOutcome.Replayed(existing)
    }

    companion object {
        /** An upload that does not complete within this time is removed (a server that stopped in the middle). */
        const val UPLOAD_MILLIS = 60 * 60_000L
        const val NOT_CONFIGURED = "file_storage_not_configured"
        fun requestDigest(name: String, sha256: String, size: Long): String =
            MessageDigest.getInstance("SHA-256").digest("file\n$name\n$sha256\n$size".encodeToByteArray()).joinToString("") { "%02x".format(it) }
        private fun notConfigured() = TeamFailure(NOT_CONFIGURED, 409, "This team has no tested bucket for files. An administrator sets one up on Dashboard → Artifacts.")
        private fun unavailable() = TeamFailure("file_storage_unavailable", 503, "The team's bucket did not answer. Send the file again.")
    }
}
