package me.nathanfallet.teamcode.client

import kotlinx.io.Buffer
import kotlinx.io.RawSource

/**
 * The bytes of a file sent to a session (`cloud.files.v1`, [TeamCodeClient.uploadCloudFile]): exactly [size] bytes,
 * read from the start each time [open] is called, since a retry sends them again. Each source is closed after its attempt.
 */
class CloudFileSource(val size: Long, val open: () -> RawSource) {
    init { require(size >= 0) { "A file size cannot be negative." } }

    companion object {
        /** Bytes already in memory. */
        fun of(bytes: ByteArray) = CloudFileSource(bytes.size.toLong()) { Buffer().apply { write(bytes) } }
    }
}

/** The [CloudFileSource] could not be read, or did not hold its announced size; an upload never retries it. */
class CloudFileReadException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * The types a team may allow for files sent to sessions, as `GET /cloud/files/policy` lists them, by file extension.
 * The server recognizes the type from the bytes; this only guesses it before sending, for a local check and the
 * request's `Content-Type`. Any other UTF-8 text is sent as `text/plain`.
 */
object CloudFileTypes {
    private val byExtension = mapOf(
        "png" to "image/png", "jpg" to "image/jpeg", "jpeg" to "image/jpeg", "webp" to "image/webp", "gif" to "image/gif",
        "pdf" to "application/pdf", "txt" to "text/plain", "md" to "text/markdown", "markdown" to "text/markdown",
        "csv" to "text/csv", "json" to "application/json",
    )

    /** The extensions [forName] knows, lowercase and without the dot. */
    val extensions: Set<String> = byExtension.keys

    /** The type of [name] by its extension (any case), or null for an extension this list does not know. */
    fun forName(name: String): String? = name.substringAfterLast('.', "").takeIf { it.isNotEmpty() && it != name }?.lowercase()?.let(byExtension::get)

    /** Whether [type] is read from magic bytes (an image or a PDF) rather than being text. */
    fun isBinary(type: String) = type.startsWith("image/") || type == "application/pdf"

    /** Whether [bytes] are text the server takes: valid UTF-8 without a NUL byte. */
    fun isText(bytes: ByteArray): Boolean = 0.toByte() !in bytes &&
        try { bytes.decodeToString(throwOnInvalidSequence = true); true } catch (_: CharacterCodingException) { false }
}
