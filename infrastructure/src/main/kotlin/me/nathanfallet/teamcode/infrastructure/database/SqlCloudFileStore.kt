package me.nathanfallet.teamcode.infrastructure.database

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.nathanfallet.teamcode.domain.cloud.*
import me.nathanfallet.teamcode.domain.team.TeamFailure
import me.nathanfallet.teamcode.infrastructure.database.tables.*
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*

/**
 * Metadata of files sent to sessions, in MySQL; the bytes are in the team's bucket. Every write that counts a session's
 * files locks the session row first (as inputs do), and the author's daily counter is locked after it.
 */
class SqlCloudFileStore(private val database: Database) : CloudFileStore {
    override suspend fun now(): Long = database.query { nowMillis() }

    override suspend fun begin(file: CloudFile, expectedOwnerId: String, announcedBytes: Long): CloudFileBegin = database.query {
        val session = lockSession(file.sessionId)
        if (session[CloudSessions.ownerId] != expectedOwnerId) throw TeamFailure("session_busy", 409, "The cloud session changed owner. Retry the request.")
        if (CloudSessionState.parse(session[CloudSessions.state]).terminal || DesiredState.parse(session[CloudSessions.desiredState]) == DesiredState.TERMINATED)
            throw TeamFailure("session_not_running", 409, "The cloud session is terminated or being deleted.")
        val now = nowMillis()
        if (file.clientKey != null) {
            val existing = CloudSessionFiles.selectAll().where {
                (CloudSessionFiles.sessionId eq file.sessionId) and (CloudSessionFiles.authorId eq file.authorId) and (CloudSessionFiles.clientKey eq file.clientKey)
            }.singleOrNull()
            if (existing != null) {
                // An upload that failed leaves its key free for the retry; its parts are still removed by the sweep.
                if (existing[CloudSessionFiles.completedAt] == null && existing[CloudSessionFiles.deletingSince] != null)
                    CloudSessionFiles.update({ CloudSessionFiles.id eq existing[CloudSessionFiles.id] }) { it[clientKey] = null }
                else return@query CloudFileBegin.Existing(readFile(existing))
            }
        }
        checkSession(file.sessionId, null, announcedBytes, unsent = true)
        checkDay(file.teamId, file.authorId, now, announcedBytes, newFile = true, lock = false)
        CloudSessionFiles.insert {
            it[id] = file.id; it[teamId] = file.teamId; it[sessionId] = file.sessionId; it[authorId] = file.authorId; it[credentialId] = file.credentialId
            it[name] = file.name; it[size] = announcedBytes; it[clientKey] = file.clientKey; it[createdAt] = now
            it[expiresAt] = now + CloudFileService.UPLOAD_MILLIS
        }
        CloudFileBegin.Started
    }

    override suspend fun complete(id: String, name: String, contentType: String, size: Long, sha256: String, requestDigest: String?): CloudFile = database.query {
        val sessionId = CloudSessionFiles.select(CloudSessionFiles.sessionId).where { CloudSessionFiles.id eq id }.singleOrNull()?.get(CloudSessionFiles.sessionId) ?: throw gone()
        lockSession(sessionId)
        val row = CloudSessionFiles.selectAll().where { CloudSessionFiles.id eq id }.forUpdate().singleOrNull() ?: throw gone()
        if (row[CloudSessionFiles.deletingSince] != null || row[CloudSessionFiles.completedAt] != null) throw gone()
        val now = nowMillis()
        checkSession(sessionId, id, size, unsent = false)
        checkDay(row[CloudSessionFiles.teamId], row[CloudSessionFiles.authorId], now, size, newFile = true, lock = true)
        val day = now / CloudFileRules.DAY_MILLIS
        CloudFileUsage.update({ (CloudFileUsage.teamId eq row[CloudSessionFiles.teamId]) and (CloudFileUsage.memberId eq row[CloudSessionFiles.authorId]) and (CloudFileUsage.day eq day) }) {
            it[CloudFileUsage.bytes] = CloudFileUsage.bytes + size; it[CloudFileUsage.files] = CloudFileUsage.files + 1
        }
        CloudSessionFiles.update({ CloudSessionFiles.id eq id }) {
            it[CloudSessionFiles.name] = name; it[CloudSessionFiles.contentType] = contentType; it[CloudSessionFiles.size] = size
            it[CloudSessionFiles.sha256] = sha256; it[CloudSessionFiles.requestDigest] = requestDigest; it[completedAt] = now
            it[expiresAt] = row[CloudSessionFiles.createdAt] + CloudFileRules.TTL_MILLIS
        }
        // The name, size and type, never the content: the audit says who sent what to which session.
        val details = buildJsonObject { put("sessionId", sessionId); put("name", name); put("size", size); put("contentType", contentType) }.toString()
        audit(row[CloudSessionFiles.teamId], row[CloudSessionFiles.authorId], "cloud.file.uploaded", id, credential = row[CloudSessionFiles.credentialId], details = details)
        readFile(CloudSessionFiles.selectAll().where { CloudSessionFiles.id eq id }.single())
    }

    override suspend fun abandon(id: String) = database.query {
        val now = nowMillis()
        CloudSessionFiles.update({ (CloudSessionFiles.id eq id) and CloudSessionFiles.deletingSince.isNull() }) { it[deletingSince] = now }
        Unit
    }

    override suspend fun deliverable(sessionId: String, epoch: Int, id: String): CloudFile? = database.query {
        CloudSessionFiles.join(CloudSessionInputs, JoinType.INNER, CloudSessionFiles.inputId, CloudSessionInputs.id).select(CloudSessionFiles.columns).where {
            (CloudSessionFiles.id eq id) and (CloudSessionFiles.sessionId eq sessionId) and CloudSessionFiles.completedAt.isNotNull() and
                CloudSessionFiles.deletingSince.isNull() and (CloudSessionInputs.sessionId eq sessionId) and
                (CloudSessionInputs.status eq CloudInputStatus.DELIVERED.wire) and (CloudSessionInputs.deliveredEpoch eq epoch)
        }.singleOrNull()?.let(::readFile)
    }

    override suspend fun expire(limit: Int): Int = database.query {
        val now = nowMillis()
        CloudSessionFiles.update({ CloudSessionFiles.deletingSince.isNull() and (CloudSessionFiles.expiresAt less now) }, limit = limit) { it[deletingSince] = now }
    }

    override suspend fun deleting(limit: Int): List<CloudFile> = database.query {
        CloudSessionFiles.selectAll().where { CloudSessionFiles.deletingSince.isNotNull() }
            .orderBy(CloudSessionFiles.deletingSince to SortOrder.ASC).limit(limit).map(::readFile)
    }

    override suspend fun forget(ids: List<String>) = database.query {
        if (ids.isNotEmpty()) CloudSessionFiles.deleteWhere { (CloudSessionFiles.id inList ids) and CloudSessionFiles.deletingSince.isNotNull() }
        Unit
    }

    override suspend fun purgeUsage(day: Long): Int = database.query { CloudFileUsage.deleteWhere(limit = 1_000) { CloudFileUsage.day less day } }

    /**
     * The session's own bounds: files no message names yet (when a new one is [unsent]) and bytes its host has not
     * received, [bytes] more included ([except] is the file being completed, counted with its real size).
     */
    private fun checkSession(sessionId: String, except: String?, bytes: Long, unsent: Boolean) {
        val live = (CloudSessionFiles.sessionId eq sessionId) and CloudSessionFiles.deletingSince.isNull() and
            (if (except != null) CloudSessionFiles.id neq except else Op.TRUE)
        if (unsent) {
            val waiting = CloudSessionFiles.select(CloudSessionFiles.id).where { live and CloudSessionFiles.inputId.isNull() }
                .limit(CloudFileRules.MAX_UNSENT_FILES_PER_SESSION).count()
            if (waiting >= CloudFileRules.MAX_UNSENT_FILES_PER_SESSION)
                throw CloudFileRules.quota("This session already holds ${CloudFileRules.MAX_UNSENT_FILES_PER_SESSION} files no message names yet. Send them, or wait for them to expire.")
        }
        val total = CloudSessionFiles.size.sum()
        val held = CloudSessionFiles.select(total).where { live }.single()[total] ?: 0L
        if (held + bytes > CloudFileRules.SESSION_BUFFER_BYTES)
            throw CloudFileRules.quota("This session already holds ${held / CloudFileRules.MEBIBYTE} MiB of files its host has not received; it holds ${CloudFileRules.SESSION_BUFFER_BYTES / CloudFileRules.MEBIBYTE} MiB at most.")
    }

    /** The author's UTC day: at most [CloudFileRules.MEMBER_DAILY_FILES] files and [CloudFileRules.MEMBER_DAILY_BYTES]; [lock] creates and locks its counter. */
    private fun checkDay(teamId: String, memberId: String, now: Long, bytes: Long, newFile: Boolean, lock: Boolean) {
        val day = now / CloudFileRules.DAY_MILLIS
        val match = (CloudFileUsage.teamId eq teamId) and (CloudFileUsage.memberId eq memberId) and (CloudFileUsage.day eq day)
        if (lock) CloudFileUsage.insertIgnore { it[CloudFileUsage.teamId] = teamId; it[CloudFileUsage.memberId] = memberId; it[CloudFileUsage.day] = day; it[CloudFileUsage.bytes] = 0; it[files] = 0 }
        val usage = CloudFileUsage.selectAll().where { match }.let { if (lock) it.forUpdate() else it }.singleOrNull()
        val usedBytes = usage?.get(CloudFileUsage.bytes) ?: 0L
        val usedFiles = usage?.get(CloudFileUsage.files) ?: 0
        if ((newFile && usedFiles >= CloudFileRules.MEMBER_DAILY_FILES) || usedBytes + bytes > CloudFileRules.MEMBER_DAILY_BYTES) {
            val retry = ((day + 1) * CloudFileRules.DAY_MILLIS - now + 999) / 1000
            throw CloudFileRules.quota("You have sent your ${CloudFileRules.MEMBER_DAILY_FILES} files or ${CloudFileRules.MEMBER_DAILY_BYTES / CloudFileRules.MEBIBYTE} MiB for today. Try again tomorrow (UTC).", retry)
        }
    }

    private fun lockSession(sessionId: String): ResultRow =
        CloudSessions.select(CloudSessions.id, CloudSessions.ownerId, CloudSessions.state, CloudSessions.desiredState)
            .where { CloudSessions.id eq sessionId }.forUpdate().singleOrNull() ?: throw TeamFailure("not_found", 404, "Cloud session not found.")

    private fun gone() = TeamFailure("file_expired", 410, "The upload expired before it completed. Send the file again.")
}

internal fun readFile(row: ResultRow) = CloudFile(
    row[CloudSessionFiles.id], row[CloudSessionFiles.teamId], row[CloudSessionFiles.sessionId], row[CloudSessionFiles.authorId], row[CloudSessionFiles.credentialId],
    row[CloudSessionFiles.name], row[CloudSessionFiles.contentType], row[CloudSessionFiles.size], row[CloudSessionFiles.sha256], row[CloudSessionFiles.clientKey],
    row[CloudSessionFiles.requestDigest], row[CloudSessionFiles.inputId], row[CloudSessionFiles.createdAt], row[CloudSessionFiles.completedAt],
    row[CloudSessionFiles.expiresAt], row[CloudSessionFiles.consumedAt], row[CloudSessionFiles.deletingSince],
)

/** The files each of [inputIds] carries, in one indexed read (never one per input). */
internal fun filesOf(inputIds: Collection<String>): Map<String, List<CloudFileRef>> {
    if (inputIds.isEmpty()) return emptyMap()
    return CloudSessionFiles.select(CloudSessionFiles.id, CloudSessionFiles.inputId, CloudSessionFiles.name, CloudSessionFiles.size, CloudSessionFiles.contentType, CloudSessionFiles.sha256)
        .where { (CloudSessionFiles.inputId inList inputIds) and CloudSessionFiles.completedAt.isNotNull() }
        .orderBy(CloudSessionFiles.createdAt to SortOrder.ASC)
        .groupBy({ it[CloudSessionFiles.inputId]!! }) { CloudFileRef(it[CloudSessionFiles.id], it[CloudSessionFiles.name], it[CloudSessionFiles.size], it[CloudSessionFiles.contentType].orEmpty(), it[CloudSessionFiles.sha256].orEmpty()) }
}

/** The files of a finished input are no longer needed: the sweep removes their bytes, then their rows. */
internal fun releaseFiles(inputId: String, consumed: Boolean, now: Long) {
    CloudSessionFiles.update({ (CloudSessionFiles.inputId eq inputId) and CloudSessionFiles.deletingSince.isNull() }) {
        it[deletingSince] = now
        if (consumed) it[consumedAt] = now
    }
}
