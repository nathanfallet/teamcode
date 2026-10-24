package me.nathanfallet.teamcode.companion.domain.repositories

import me.nathanfallet.teamcode.companion.domain.models.FilePolicy
import me.nathanfallet.teamcode.companion.domain.models.PickedFile
import me.nathanfallet.teamcode.companion.domain.models.UploadedFile

/**
 * Files sent with messages (`cloud.files.v1`). Failures are [me.nathanfallet.teamcode.companion.domain.models.CompanionException]s;
 * a refusal of the file itself (its size, its type, a quota, the team's storage) is
 * [me.nathanfallet.teamcode.companion.domain.models.CompanionException.FileRefused].
 */
interface SessionFileRepository {
    suspend fun policy(accountId: String): FilePolicy

    /**
     * Uploads [file] to [sessionId] as [contentType] (a guess the server checks against the bytes; null to let it tell),
     * retrying network failures and server errors itself under the same
     * [idempotencyKey], so the server never stores it twice; [onProgress] gets the bytes sent so far, from 0 at each attempt.
     */
    suspend fun upload(accountId: String, sessionId: String, file: PickedFile, contentType: String?, idempotencyKey: String, onProgress: (sent: Long, total: Long) -> Unit): UploadedFile

    /** The MIME type [name] has by its extension, among the types files may have; null for any other extension. */
    fun typeFor(name: String): String?
}
