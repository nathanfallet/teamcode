package me.nathanfallet.teamcode.companion.domain.usecases.files

import kotlinx.coroutines.CancellationException
import me.nathanfallet.teamcode.companion.domain.models.CompanionException
import me.nathanfallet.teamcode.companion.domain.models.FilePolicy
import me.nathanfallet.teamcode.companion.domain.models.FileProblem
import me.nathanfallet.teamcode.companion.domain.models.PendingFile
import me.nathanfallet.teamcode.companion.domain.models.PickedFile
import me.nathanfallet.teamcode.companion.domain.models.UploadOutcome
import me.nathanfallet.teamcode.companion.domain.repositories.Features
import me.nathanfallet.teamcode.companion.domain.repositories.FeaturesRepository
import me.nathanfallet.teamcode.companion.domain.repositories.SessionFileRepository
import me.nathanfallet.teamcode.companion.domain.services.IdGenerator
import me.nathanfallet.teamcode.companion.domain.usecases.auth.AccountGuard

/**
 * Files sent with the next message (`cloud.files.v1`): whether the server and the team take them ([policy]), the checks
 * made before any upload ([check]), and the upload itself ([upload]), whose failures come back as a [FileProblem].
 */
class SessionFilesUseCase(
    private val files: SessionFileRepository,
    private val features: FeaturesRepository,
    private val guard: AccountGuard,
    private val ids: IdGenerator,
) {
    /** A new id: a pending file's local id, or the idempotency key of its upload, kept across its retries. */
    fun newId(): String = ids.newId()

    /** The team's policy, or null when the server does not take files at all (no `cloud.files.v1`): nothing to show then. */
    suspend fun policy(accountId: String): FilePolicy? = guard(accountId) {
        if (Features.FILES !in features.features(accountId)) null else files.policy(accountId)
    }

    /**
     * Why [file] cannot join [pending] under [policy], or null when it can: checked before any upload, so a refusal
     * costs no request. Every chip counts, a failed one too, since it still waits to be retried or removed.
     */
    fun check(file: PickedFile, pending: List<PendingFile>, policy: FilePolicy): FileProblem? {
        if (!policy.available) return FileProblem.StorageNotConfigured
        if (pending.size >= policy.maxFiles) return FileProblem.TooMany(policy.maxFiles)
        if (file.size <= 0L) return FileProblem.Empty
        if (file.size > policy.maxFileBytes) return FileProblem.TooLarge(policy.maxFileBytes)
        if (pending.sumOf { it.size } + file.size > policy.maxMessageBytes) return FileProblem.MessageTooLarge(policy.maxMessageBytes)
        val type = typeOf(file, policy)
        if (type != null && type !in policy.types) return FileProblem.TypeNotAllowed(policy.types)
        return null
    }

    /**
     * The type sent with [file]: by its extension first, then the picker's type. Any text type is sent as `text/plain`
     * when the team takes it (a `.log` is text). Null when nothing says: the server recognizes the type from the bytes.
     */
    fun typeOf(file: PickedFile, policy: FilePolicy): String? {
        files.typeFor(file.name)?.let { return it }
        val reported = file.type?.substringBefore(';')?.trim()?.lowercase()?.takeIf { it.isNotEmpty() && it != OCTET_STREAM } ?: return null
        return if (reported !in policy.types && reported.startsWith("text/") && TEXT in policy.types) TEXT else reported
    }

    /** Uploads [file] under [idempotencyKey] (the same on every retry of this file); never throws except for cancellation. */
    suspend fun upload(accountId: String, sessionId: String, file: PickedFile, policy: FilePolicy, idempotencyKey: String, onProgress: (sent: Long, total: Long) -> Unit): UploadOutcome = try {
        UploadOutcome.Uploaded(guard(accountId) { files.upload(accountId, sessionId, file, typeOf(file, policy), idempotencyKey, onProgress) })
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        UploadOutcome.Failed(problemOf(error, policy))
    }

    private fun problemOf(error: Exception, policy: FilePolicy): FileProblem = when (error) {
        is CompanionException.FileRefused -> refusal(error, policy)
        is CompanionException.NotFound -> FileProblem.SessionGone
        is CompanionException.Unreachable -> FileProblem.Unreachable
        is CompanionException.Unsupported -> FileProblem.Unsupported
        is CompanionException.RateLimited -> FileProblem.Other("rate_limited", transient = true)
        is CompanionException.Server -> FileProblem.Other(error.code, transient = error.statusCode >= 500 || error.statusCode == 408)
        else -> FileProblem.Other(null, transient = false)
    }

    companion object {
        const val FILE_TOO_LARGE = "file_too_large"
        const val FILE_TYPE_NOT_ALLOWED = "file_type_not_allowed"
        const val UPLOAD_QUOTA = "upload_quota"
        const val STORAGE_NOT_CONFIGURED = "file_storage_not_configured"
        const val STORAGE_UNAVAILABLE = "file_storage_unavailable"
        const val FILE_INTEGRITY_FAILED = "file_integrity_failed"
        /** Not the server's: the picked file could not be read, or changed while it was sent. */
        const val FILE_UNREADABLE = "file_unreadable"

        /** The problem the server's refusal [error] names. */
        fun refusal(error: CompanionException.FileRefused, policy: FilePolicy): FileProblem = when (error.code) {
            FILE_TOO_LARGE -> FileProblem.TooLarge(policy.maxFileBytes)
            FILE_TYPE_NOT_ALLOWED -> FileProblem.TypeNotAllowed(policy.types)
            UPLOAD_QUOTA -> FileProblem.Quota(error.retryAfterSeconds)
            STORAGE_NOT_CONFIGURED -> FileProblem.StorageNotConfigured
            STORAGE_UNAVAILABLE -> FileProblem.StorageUnavailable
            FILE_INTEGRITY_FAILED -> FileProblem.Integrity
            FILE_UNREADABLE -> FileProblem.Unreadable
            "empty_file" -> FileProblem.Empty
            "cloud_permission_denied", "forbidden" -> FileProblem.Denied
            else -> if (error.statusCode == 403) FileProblem.Denied
                else FileProblem.Other(error.code, transient = error.statusCode >= 500 || error.statusCode == 408 || error.statusCode == 429)
        }

        private const val TEXT = "text/plain"
        private const val OCTET_STREAM = "application/octet-stream"
    }
}

/** The size a photo is reduced to before sending: its longer side at most [max] pixels, its proportions kept. */
object PhotoSizing {
    /** The target width and height for a [width] × [height] photo; the same size when it is already small enough. */
    fun target(width: Int, height: Int, max: Int = FilePolicy.PHOTO_MAX_PIXELS): Pair<Int, Int> {
        require(width > 0 && height > 0 && max > 0) { "A photo has a positive size." }
        val longer = maxOf(width, height)
        if (longer <= max) return width to height
        val scale = max.toDouble() / longer
        return maxOf(1, (width * scale).toInt()) to maxOf(1, (height * scale).toInt())
    }

    /** The largest power of two that decodes a [width] × [height] photo at no less than [max] on its longer side (Android's `inSampleSize`). */
    fun sampleSize(width: Int, height: Int, max: Int = FilePolicy.PHOTO_MAX_PIXELS): Int {
        var sample = 1
        while (maxOf(width, height) / (sample * 2) >= max) sample *= 2
        return sample
    }

    /** The name a reduced photo is sent under: [name] with a `.jpg` extension (a HEIC photo becomes a JPEG). */
    fun jpegName(name: String?): String {
        val base = name?.substringAfterLast('/')?.substringBeforeLast('.')?.trim()?.takeIf { it.isNotEmpty() } ?: "photo"
        return "$base.jpg"
    }
}
