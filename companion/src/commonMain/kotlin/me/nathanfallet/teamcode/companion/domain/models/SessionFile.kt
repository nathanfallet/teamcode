package me.nathanfallet.teamcode.companion.domain.models

import kotlinx.io.RawSource

/**
 * A file the member picked to send with the next message (`cloud.files.v1`): its [name] (no path), its exact [size] in
 * bytes, the [type] the picker reported if any, and [open], which reads it from the start each time it is called, since
 * a retry sends it again. A photo is already reduced when it gets here.
 */
class PickedFile(val name: String, val size: Long, val type: String? = null, val open: () -> RawSource)

/** What the team allows (`GET /cloud/files/policy`): [available] false with [reason] when files are off for the team. */
data class FilePolicy(
    val available: Boolean,
    val reason: String?,
    val maxFileBytes: Long,
    val maxMessageBytes: Long,
    val maxFilesPerMessage: Int,
    val types: List<String>,
) {
    /** The number of files a message may carry: the team's limit, never above the server's own five. */
    val maxFiles: Int get() = maxFilesPerMessage.coerceIn(1, MAX_FILES)

    companion object {
        /** The server's ceiling of files per message, whatever the team sets. */
        const val MAX_FILES = 5
        /** Photos are reduced so that their longer side is at most this many pixels before they are sent. */
        const val PHOTO_MAX_PIXELS = 2048
    }
}

/** A file stored by the server for this session, ready to be named in a message's `fileIds`. */
data class UploadedFile(val id: String, val name: String, val size: Long, val contentType: String)

/** Why a file cannot be attached, or a message carrying files was refused; the screen words each one. */
sealed interface FileProblem {
    /** Trying again can work: the network, the team's storage, a transfer that came out different. */
    val retryable: Boolean get() = false

    data class TooMany(val max: Int) : FileProblem
    data object Empty : FileProblem
    data class TooLarge(val maxBytes: Long) : FileProblem
    /** With this file the message would carry more than the team allows per message. */
    data class MessageTooLarge(val maxBytes: Long) : FileProblem
    /** [types] the team takes, as MIME types. */
    data class TypeNotAllowed(val types: List<String>) : FileProblem
    /** The file could not be read, or changed while it was sent: pick it again. */
    data object Unreadable : FileProblem
    /** The member's daily quota ([retryAfterSeconds] set) or the session's buffer of unsent files is full. */
    data class Quota(val retryAfterSeconds: Long?) : FileProblem { override val retryable get() = retryAfterSeconds != null }
    /** The team has no tested storage: an administrator has to set it up. */
    data object StorageNotConfigured : FileProblem
    data object StorageUnavailable : FileProblem { override val retryable get() = true }
    /** The server stored other bytes than the ones sent. */
    data object Integrity : FileProblem { override val retryable get() = true }
    data object Denied : FileProblem
    data object Unreachable : FileProblem { override val retryable get() = true }
    /** The server does not take files (it lacks `cloud.files.v1`). */
    data object Unsupported : FileProblem
    /** The session is gone or no longer visible. */
    data object SessionGone : FileProblem
    /** Any other refusal; [code] is the server's stable code when it gave one. */
    data class Other(val code: String?, val transient: Boolean) : FileProblem { override val retryable get() = transient }
}

/**
 * A file of the next message as the composer shows it: uploading ([sent] of [size] bytes), [uploaded], or failed with
 * its [problem] (retried with the same idempotency key when [FileProblem.retryable]).
 */
data class PendingFile(
    val localId: String,
    val name: String,
    val size: Long,
    val state: State,
    val sent: Long = 0,
    val uploaded: UploadedFile? = null,
    val problem: FileProblem? = null,
) {
    enum class State { UPLOADING, UPLOADED, FAILED }

    /** Progress from 0 to 1 while uploading. */
    val progress: Float get() = if (size <= 0) 0f else (sent.toFloat() / size).coerceIn(0f, 1f)
}

/** The outcome of one upload: never an exception, except for cancellation. */
sealed interface UploadOutcome {
    data class Uploaded(val file: UploadedFile) : UploadOutcome
    data class Failed(val problem: FileProblem) : UploadOutcome
}
