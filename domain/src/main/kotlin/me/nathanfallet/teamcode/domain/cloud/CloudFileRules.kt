package me.nathanfallet.teamcode.domain.cloud

import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CharsetDecoder
import java.nio.charset.CodingErrorAction
import java.text.Normalizer
import me.nathanfallet.teamcode.domain.artifacts.ArtifactRules
import me.nathanfallet.teamcode.domain.team.TeamFailure

/**
 * The limits and checks of files sent to a session (`cloud.files.v1`): what a member may upload, how much, and what the
 * host may name the file on its disk. The server decides the type from the bytes, never from what the client says.
 */
object CloudFileRules {
    const val MEBIBYTE = 1024L * 1024
    /** Defaults of the team settings, and the most a team may set. */
    const val DEFAULT_FILE_MEBIBYTES = 20
    const val DEFAULT_MESSAGE_MEBIBYTES = 25
    const val MAX_MEBIBYTES = 100
    const val MAX_FILES_PER_MESSAGE = 5
    /** Bytes a session holds at most in files not yet received by its host (uploaded, sent or not, and not expired). */
    const val SESSION_BUFFER_BYTES = 100 * MEBIBYTE
    /** Files a session holds at most that no message names yet: the upload counterpart of the pending-message bound. */
    const val MAX_UNSENT_FILES_PER_SESSION = 20
    /** What one member may upload per UTC day, all sessions together. */
    const val MEMBER_DAILY_BYTES = 1024 * MEBIBYTE
    const val MEMBER_DAILY_FILES = 500
    /** A file is kept at most this long, sent or not, received by its host or not. */
    const val TTL_MILLIS = 24 * 60 * 60_000L
    const val DAY_MILLIS = 24 * 60 * 60_000L
    /** Objects of 1 MiB in the bucket, as artifact upload chunks: a download streams them one by one. */
    const val PART_BYTES = ArtifactRules.CHUNK_BYTES
    /** Longest name a client may send before it is cleaned, in characters. */
    const val MAX_RAW_NAME_CHARS = 1_024
    /**
     * Longest cleaned name, in UTF-8 bytes: the host writes `<fileId>-<name>`, 37 + 120 bytes, well under the 255 bytes
     * file systems allow.
     */
    const val MAX_NAME_BYTES = 120
    private const val MAX_EXTENSION_CHARS = 10

    /** The types a session accepts, by the type the server recognized. */
    val ALLOWED_TYPES = listOf("image/png", "image/jpeg", "image/webp", "image/gif", "application/pdf", "text/plain", "text/markdown", "text/csv", "application/json")

    /** Extensions a binary type keeps; any other gets the first one appended, so the agent never reads a PNG as `.exe`. */
    private val BINARY_EXTENSIONS = mapOf(
        "image/png" to listOf("png"), "image/jpeg" to listOf("jpg", "jpeg"), "image/webp" to listOf("webp"),
        "image/gif" to listOf("gif"), "application/pdf" to listOf("pdf"),
    )
    private val BINARY_EXTENSION_SET = BINARY_EXTENSIONS.values.flatten().toSet()

    fun fileLimit(settings: CloudSettings) = settings.fileMaxMebibytes * MEBIBYTE
    fun messageLimit(settings: CloudSettings) = settings.messageFileMebibytes * MEBIBYTE

    fun tooLarge(limit: Long) = TeamFailure("file_too_large", 413, "A file is limited to ${limit / MEBIBYTE} MiB on this team.")
    fun typeNotAllowed() = TeamFailure("file_type_not_allowed", 415, "Send an image (PNG, JPEG, WebP, GIF), a PDF, or UTF-8 text (plain, Markdown, CSV, JSON).")
    fun quota(message: String, retryAfterSeconds: Long? = null) = TeamFailure("upload_quota", 429, message, retryAfterSeconds)

    /** The binary type the first bytes announce, or null (text is told apart by [TextCheck]). */
    fun magicType(head: ByteArray): String? {
        fun starts(vararg bytes: Int, at: Int = 0) = head.size >= at + bytes.size && bytes.indices.all { head[at + it] == bytes[it].toByte() }
        return when {
            starts(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) -> "image/png"
            starts(0xFF, 0xD8, 0xFF) -> "image/jpeg"
            starts(0x47, 0x49, 0x46, 0x38, 0x37, 0x61) || starts(0x47, 0x49, 0x46, 0x38, 0x39, 0x61) -> "image/gif"
            starts(0x52, 0x49, 0x46, 0x46) && starts(0x57, 0x45, 0x42, 0x50, at = 8) -> "image/webp"
            starts(0x25, 0x50, 0x44, 0x46, 0x2D) -> "application/pdf"
            else -> null
        }
    }

    /** A text file's type, from its cleaned name: Markdown, CSV and JSON by extension, plain text otherwise. */
    fun textType(name: String) = when (extension(name)) {
        "md", "markdown" -> "text/markdown"
        "csv" -> "text/csv"
        "json" -> "application/json"
        else -> "text/plain"
    }

    /**
     * The name the file gets once its type is known: a binary file keeps its extension only when it is one of its type's,
     * and text never keeps a binary type's extension; otherwise the type's extension is appended.
     */
    fun finalName(name: String, contentType: String): String {
        val extension = extension(name)
        val wanted = BINARY_EXTENSIONS[contentType]
        val suffix = when {
            wanted != null -> if (extension in wanted) null else wanted.first()
            extension in BINARY_EXTENSION_SET -> "txt"
            else -> null
        } ?: return name
        val base = fit(name, MAX_NAME_BYTES - suffix.length - 1)
        return "$base.$suffix"
    }

    /**
     * The client's file name, made safe to use as the last segment of a path on any host. It is never trusted as a path:
     * only the part after the last `/` or `\` is kept, normalized to NFC; control, format (bidirectional overrides,
     * zero-width), separator and surrogate characters are dropped; characters Windows refuses are replaced by `_`;
     * leading dots and dashes, trailing dots and spaces are removed; Windows device names (`CON`, `NUL`, `COM1`…) are
     * prefixed by `_`; the result is cut to [MAX_NAME_BYTES] UTF-8 bytes, keeping a short extension. Empty becomes `file`.
     * The host still prefixes it with the file id and checks the final path stays in the session's directory.
     */
    fun sanitizeName(raw: String?): String {
        if (raw != null && raw.length > MAX_RAW_NAME_CHARS) throw TeamFailure("invalid_file_name", 400, "Send a file name of at most $MAX_RAW_NAME_CHARS characters.")
        val last = raw.orEmpty().split('/', '\\').lastOrNull { it.isNotBlank() }.orEmpty()
        val normalized = Normalizer.normalize(last, Normalizer.Form.NFC)
        val cleaned = buildString {
            var index = 0
            while (index < normalized.length) {
                val code = normalized.codePointAt(index)
                index += Character.charCount(code)
                when {
                    Character.getType(code).toByte() in DROPPED_TYPES -> Unit
                    code < 0x20 || code == 0x7F -> Unit
                    code.toChar() in WINDOWS_FORBIDDEN && code < 0x80 -> append('_')
                    Character.isWhitespace(code) || Character.isSpaceChar(code) -> append(' ')
                    else -> appendCodePoint(code)
                }
            }
        }.replace(Regex(" {2,}"), " ").trim(' ', '.').trimStart('-', '.', ' ').trimEnd('.', ' ')
        var name = cleaned.ifEmpty { "file" }
        val stem = name.substringBefore('.').trimEnd(' ').uppercase()
        if (stem in WINDOWS_DEVICES || WINDOWS_NUMBERED.matches(stem)) name = "_$name"
        return fit(name, MAX_NAME_BYTES)
    }

    /** Cuts [name] to [limit] UTF-8 bytes on a character boundary, keeping an extension of up to [MAX_EXTENSION_CHARS] characters. */
    private fun fit(name: String, limit: Int): String {
        if (name.encodeToByteArray().size <= limit) return name
        val extension = name.substringAfterLast('.', "").takeIf { it.isNotEmpty() && it.length <= MAX_EXTENSION_CHARS && it != name }
        val suffix = extension?.let { ".$it" }.orEmpty()
        val stem = if (extension != null) name.removeSuffix(suffix) else name
        val room = limit - suffix.encodeToByteArray().size
        val kept = StringBuilder()
        var bytes = 0
        var index = 0
        while (index < stem.length) {
            val code = stem.codePointAt(index)
            val size = String(Character.toChars(code)).encodeToByteArray().size
            if (bytes + size > room) break
            kept.appendCodePoint(code); bytes += size; index += Character.charCount(code)
        }
        return kept.toString().trimEnd('.', ' ').ifEmpty { "file" } + suffix
    }

    private fun extension(name: String) = name.substringAfterLast('.', "").lowercase()

    /** Control, format, line and paragraph separators, surrogates, private use and unassigned code points. */
    private val DROPPED_TYPES = setOf(Character.CONTROL, Character.FORMAT, Character.LINE_SEPARATOR, Character.PARAGRAPH_SEPARATOR,
        Character.SURROGATE, Character.PRIVATE_USE, Character.UNASSIGNED)
    private val WINDOWS_FORBIDDEN = setOf('<', '>', ':', '"', '|', '?', '*')
    private val WINDOWS_DEVICES = setOf("CON", "PRN", "AUX", "NUL", "CONIN$", "CONOUT$", "CLOCK$")
    private val WINDOWS_NUMBERED = Regex("(COM|LPT)[0-9¹²³]")

    /**
     * Checks that a stream of bytes is UTF-8 text without NUL bytes, chunk by chunk: a character split between two
     * chunks is carried over. A byte-order mark is allowed.
     */
    class TextCheck {
        private val decoder: CharsetDecoder = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
        private var carry = ByteArray(0)
        private val output = CharBuffer.allocate(8_192)
        var valid = true
            private set

        fun feed(bytes: ByteArray) {
            if (!valid) return
            if (bytes.any { it == 0.toByte() }) { valid = false; return }
            decode(carry + bytes, false)
        }

        /** Whether everything fed was text, with no character left incomplete. */
        fun finish(): Boolean {
            if (valid) decode(carry, true)
            return valid && carry.isEmpty()
        }

        private fun decode(bytes: ByteArray, end: Boolean) {
            val input = ByteBuffer.wrap(bytes)
            while (true) {
                output.clear()
                val result = decoder.decode(input, output, end)
                if (result.isError) { valid = false; carry = ByteArray(0); return }
                if (result.isUnderflow) break
            }
            carry = ByteArray(input.remaining()).also { input.get(it) }
            if (end && carry.isNotEmpty()) valid = false
        }
    }
}
