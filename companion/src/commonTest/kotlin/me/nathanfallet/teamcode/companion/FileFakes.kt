package me.nathanfallet.teamcode.companion

import kotlinx.coroutines.CompletableDeferred
import kotlinx.io.Buffer
import me.nathanfallet.teamcode.companion.domain.models.FilePolicy
import me.nathanfallet.teamcode.companion.domain.models.PickedFile
import me.nathanfallet.teamcode.companion.domain.models.UploadedFile
import me.nathanfallet.teamcode.companion.domain.repositories.SessionFileRepository

fun policy(available: Boolean = true, maxFileBytes: Long = 20L * 1024 * 1024, maxMessageBytes: Long = 25L * 1024 * 1024, maxFiles: Int = 5,
    types: List<String> = listOf("image/png", "image/jpeg", "image/webp", "image/gif", "application/pdf", "text/plain", "text/markdown", "text/csv", "application/json")) =
    FilePolicy(available, if (available) null else "file_storage_not_configured", maxFileBytes, maxMessageBytes, maxFiles, types)

fun picked(name: String, size: Long = 100, type: String? = null) = PickedFile(name, size, type) { Buffer().apply { write(ByteArray(size.toInt())) } }

/** Uploads answered by [result]; [gate] holds each upload until completed, to look at it in flight. */
class FakeFileRepository(var filePolicy: FilePolicy = policy()) : SessionFileRepository {
    val uploads = mutableListOf<Pair<String, String>>()
    val contentTypes = mutableListOf<String?>()
    var policyReads = 0
    var policyFailure: Exception? = null
    var gate: CompletableDeferred<Unit>? = null
    /** Bytes reported as sent before [gate] is awaited. */
    var progressBefore: Long? = null
    var result: (PickedFile, String) -> UploadedFile = { file, key -> UploadedFile("f-$key", file.name, file.size, "image/png") }

    override suspend fun policy(accountId: String): FilePolicy { policyReads++; policyFailure?.let { throw it }; return filePolicy }

    override suspend fun upload(accountId: String, sessionId: String, file: PickedFile, contentType: String?, idempotencyKey: String,
        onProgress: (sent: Long, total: Long) -> Unit): UploadedFile {
        uploads += file.name to idempotencyKey
        contentTypes += contentType
        onProgress(0, file.size)
        progressBefore?.let { onProgress(it, file.size) }
        gate?.await()
        onProgress(file.size, file.size)
        return result(file, idempotencyKey)
    }

    override fun typeFor(name: String): String? = mapOf("png" to "image/png", "jpg" to "image/jpeg", "md" to "text/markdown", "pdf" to "application/pdf", "txt" to "text/plain")[name.substringAfterLast('.', "").lowercase()]
}
