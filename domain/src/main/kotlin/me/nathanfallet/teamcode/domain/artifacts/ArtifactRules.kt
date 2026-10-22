package me.nathanfallet.teamcode.domain.artifacts

import java.security.MessageDigest
import me.nathanfallet.teamcode.domain.cloud.CloudConversationRules
import me.nathanfallet.teamcode.domain.skills.SkillArtifacts
import me.nathanfallet.teamcode.domain.team.TeamFailure

/** Who can see an artifact beyond its owner and invited members. */
enum class ArtifactAccess(val wire: String) {
    PERSONAL("personal"), TEAM("team"), PUBLIC("public");
    companion object { fun parse(value: String) = entries.singleOrNull { it.wire == value } }
}

/** What an invited member may do: read, or also publish versions and invite. */
enum class ArtifactMemberRole(val wire: String) {
    VIEWER("viewer"), EDITOR("editor");
    companion object { fun parse(value: String) = entries.singleOrNull { it.wire == value } }
}

/**
 * The limits and rules of a publication, checked by the server whatever the client (MCP server, CLI or API). The
 * limits are the ones agents already write for: 15 MiB per binary file, 16 MiB per page or text file, 255 files and
 * 64 MiB sent per publication, 511 files and 256 MiB per version.
 */
object ArtifactRules {
    const val CHUNK_BYTES = 1024 * 1024
    const val MAX_TEXT_FILE_BYTES = 16L * 1024 * 1024
    const val MAX_BINARY_FILE_BYTES = 15L * 1024 * 1024
    const val MAX_PUBLICATION_FILES = 255
    const val MAX_PUBLICATION_BYTES = 64L * 1024 * 1024
    const val MAX_VERSION_FILES = 511
    const val MAX_VERSION_BYTES = 256L * 1024 * 1024
    const val UPLOAD_TTL_MILLIS = 60 * 60_000L
    /** A commit replayed this soon (same credential, artifact, base version and manifest) returns the same version. */
    const val REPLAY_MILLIS = 10 * 60_000L
    /** A reserved id never committed is deleted after this long. */
    const val DRAFT_TTL_MILLIS = 24 * 60 * 60_000L
    /** How long a deleted artifact waits before its folder is removed, so no upload still in flight lands after the proof. */
    const val DELETION_GRACE_MILLIS = 5 * 60_000L
    const val MAX_TITLE = 200
    const val MAX_DESCRIPTION = 300
    /** Uploads a member may hold open at once; their chunks wait in MySQL. */
    const val MAX_OPEN_UPLOADS = 16L
    /** Unpublished new artifacts a member may hold before they expire. */
    const val MAX_DRAFTS = 20L
    const val MAX_LIST = 50

    private val sha256Pattern = Regex("[0-9a-f]{64}")
    private val iconPattern = Regex("[a-z][a-z0-9-]{0,39}")
    private val entryExtensions = setOf("html", "htm", "md")

    /** Only these types are served as such; everything else is `application/octet-stream`, and only `.html`/`.htm` is HTML. */
    private val contentTypes = mapOf(
        "html" to "text/html; charset=utf-8", "htm" to "text/html; charset=utf-8", "css" to "text/css; charset=utf-8",
        "js" to "text/javascript; charset=utf-8", "mjs" to "text/javascript; charset=utf-8", "json" to "application/json",
        "svg" to "image/svg+xml", "png" to "image/png", "jpg" to "image/jpeg", "jpeg" to "image/jpeg", "webp" to "image/webp",
        "gif" to "image/gif", "avif" to "image/avif", "woff2" to "font/woff2", "mp4" to "video/mp4", "webm" to "video/webm",
        "csv" to "text/csv; charset=utf-8", "txt" to "text/plain; charset=utf-8", "md" to "text/markdown; charset=utf-8",
    )

    fun failure(code: String, message: String, status: Int = 400) = TeamFailure(code, status, message)

    fun validateSha(sha256: String) {
        if (!sha256Pattern.matches(sha256)) throw failure("invalid_artifact_upload", "Name each file by its lowercase hexadecimal SHA-256.")
    }

    /** Number of chunks of a blob of [size] bytes; an empty file is one empty chunk. */
    fun chunks(size: Long): Int = if (size == 0L) 1 else ((size + CHUNK_BYTES - 1) / CHUNK_BYTES).toInt()
    fun chunkSize(size: Long, number: Int): Int = if (number < chunks(size) - 1) CHUNK_BYTES else (size - number.toLong() * CHUNK_BYTES).toInt()

    /** The distinct blobs of a version, as announced to start an upload. */
    fun validateAnnouncement(files: List<UploadFile>) {
        if (files.isEmpty() || files.size > MAX_VERSION_FILES) throw failure("invalid_artifact_upload", "Announce 1–$MAX_VERSION_FILES distinct files.")
        if (files.map { it.sha256 }.toSet().size != files.size) throw failure("invalid_artifact_upload", "Announce each file once.")
        var total = 0L
        for (file in files) {
            validateSha(file.sha256)
            if (file.size !in 0..MAX_TEXT_FILE_BYTES) throw failure("artifact_file_too_large", "A file is limited to 16 MiB (15 MiB for a binary file).", 413)
            total += file.size
        }
        if (total > MAX_VERSION_BYTES) throw failure("artifact_version_too_large", "A version is limited to 256 MiB.", 413)
    }

    /** What an upload actually sends (the blobs the artifact does not hold yet). */
    fun validateSent(files: List<UploadFile>) {
        if (files.size > MAX_PUBLICATION_FILES || files.sumOf { it.size } > MAX_PUBLICATION_BYTES)
            throw failure("artifact_publication_too_large", "A publication sends at most $MAX_PUBLICATION_FILES new files and 64 MiB; publish the rest in a second version.", 413)
    }

    /** Text is valid UTF-8 without NUL bytes; it is scanned for secrets and may be up to 16 MiB. */
    fun isText(bytes: ByteArray): Boolean {
        if (bytes.any { it == 0.toByte() }) return false
        return try {
            java.nio.charset.StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)); true
        } catch (_: java.nio.charset.CharacterCodingException) { false }
    }

    /** Checks an assembled blob: its hash and size, the binary limit, and secrets in text. Returns the secret kind, if any. */
    fun inspect(bytes: ByteArray, sha256: String, size: Long): String? {
        if (bytes.size.toLong() != size || sha256(bytes) != sha256) throw failure("artifact_blob_mismatch", "The received bytes do not match the announced SHA-256 and size. Send the file again.", 422)
        if (!isText(bytes)) {
            if (size > MAX_BINARY_FILE_BYTES) throw failure("artifact_file_too_large", "A binary file is limited to 15 MiB.", 413)
            return null
        }
        return CloudConversationRules.detect(bytes.decodeToString())
    }

    /** A version's manifest: paths, entry page, counts and sizes. Returns the files sorted by path. */
    fun validateManifest(entry: String, files: Map<String, UploadFile>): List<ArtifactFile> {
        if (files.isEmpty() || files.size > MAX_VERSION_FILES) throw failure("invalid_artifact_manifest", "A version holds 1–$MAX_VERSION_FILES files.")
        var total = 0L
        for ((path, file) in files) {
            SkillArtifacts.validatePath(path, "artifact")
            validateSha(file.sha256)
            if (file.size !in 0..MAX_TEXT_FILE_BYTES) throw failure("artifact_file_too_large", "A file is limited to 16 MiB (15 MiB for a binary file).", 413)
            total += file.size
        }
        if (total > MAX_VERSION_BYTES) throw failure("artifact_version_too_large", "A version is limited to 256 MiB.", 413)
        // Case-insensitive file systems and URLs would merge these paths; a file cannot also be a directory.
        val folded = files.keys.map { it.lowercase() }
        if (folded.toSet().size != folded.size) throw failure("invalid_artifact_manifest", "File paths must be unique, ignoring case.")
        val directories = folded.flatMap { path -> path.split('/').dropLast(1).runningReduce { a, b -> "$a/$b" } }.toSet()
        if (folded.any { it in directories }) throw failure("invalid_artifact_manifest", "A path cannot be both a file and a directory.")
        if (entry !in files || extension(entry) !in entryExtensions) throw failure("invalid_artifact_entry", "The entry page is one of the files and ends in .html, .htm or .md.")
        return files.entries.sortedBy { it.key }.map { (path, file) -> ArtifactFile(path, file.sha256, file.size, contentType(path), role(path, entry)) }
    }

    fun contentType(path: String): String = contentTypes[extension(path)] ?: "application/octet-stream"
    fun role(path: String, entry: String) = when {
        path == entry -> "page"
        extension(path) in setOf("html", "htm") -> "html"
        else -> "asset"
    }
    private fun extension(path: String) = path.substringAfterLast('/').substringAfterLast('.', "").lowercase()

    /** SHA-256 over one `<path>\u0000<sha256>\u0000<size>\n` line per file sorted by path, as lowercase hex. */
    fun digest(files: List<ArtifactFile>): String =
        sha256(files.sortedBy { it.path }.joinToString("") { "${it.path}\u0000${it.sha256}\u0000${it.size}\n" }.encodeToByteArray())

    fun validateDetails(title: String?, description: String?, icon: String?) {
        if (title != null && (title.isBlank() || title.length > MAX_TITLE || title.any { it.isISOControl() }))
            throw failure("invalid_artifact_details", "Use a title of 1–$MAX_TITLE characters without control characters.")
        if (description != null && (description.length > MAX_DESCRIPTION || description.any { it.isISOControl() }))
            throw failure("invalid_artifact_details", "Use a description of at most $MAX_DESCRIPTION characters without control characters.")
        if (icon != null && !iconPattern.matches(icon)) throw failure("invalid_artifact_details", "Use one short lowercase word for the icon, such as chart or report.")
    }

    /** The title used when the publisher gives none: the entry's file name without its extension. */
    fun defaultTitle(entry: String) = entry.substringAfterLast('/').substringBeforeLast('.').ifEmpty { entry }.take(MAX_TITLE)

    fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
