package me.nathanfallet.teamcode.cli.cloud.agent

import kotlin.time.Clock
import me.nathanfallet.teamcode.api.models.RunnerFileView
import me.nathanfallet.teamcode.cli.platform.createExclusiveFile
import me.nathanfallet.teamcode.cli.platform.createModeDirectory
import me.nathanfallet.teamcode.cli.platform.pathStatus
import me.nathanfallet.teamcode.cli.platform.setPathMode
import okio.Buffer
import okio.FileSystem
import okio.HashingSink
import okio.Path
import okio.Path.Companion.toPath
import okio.blackholeSink

/**
 * Where a session's host writes the files members attach to their messages (`cloud.files.v1`), for the agent to read.
 * Never under the session's working directory: on a member's device, `<user temp>/teamcode-<uid>/<sessionId>/`
 * ([remote], directories 0700, files 0600); in a pod, the `uploads` volume the runner writes and the agent's container
 * mounts read-only ([pod], files 0640: the agent runs under another UID, in the pod's group).
 *
 * Each file is written once, by file id, at `<fileId>-<name>` with `O_EXCL`, checked against its size and SHA-256,
 * and must resolve inside the directory. Files live for the execution: [prepare] removes what a previous one left,
 * and anything older than [MAX_AGE_MILLIS] in [root]; [clear] removes them when the host ends without being fenced.
 */
internal class SessionUploads(
    directory: String,
    /** The directory holding every session's uploads of this user, swept at start; null in a pod. */
    private val root: String?,
    private val fileMode: Int,
    private val now: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    private val maxBytes: Long = MAX_BYTES,
) {
    private val fileSystem = FileSystem.SYSTEM
    private val configured = directory.toPath()
    /** The directory as the agent reads it, links resolved; known after [prepare]. */
    var directory: Path? = null; private set
    /** Files written in this execution, by file id: a redelivered message never writes one twice. */
    private val written = mutableMapOf<String, Path>()
    private var bytes = 0L

    /** Thrown when a file cannot be handed to the agent; [reason] is the rejection reason the input is acknowledged with. */
    class Refused(val reason: String) : Exception(reason)

    /** Creates the directory and sweeps old uploads; false (with nothing announced) when it is not safe to use. */
    fun prepare(log: (String) -> Unit): Boolean = try {
        if (root != null) {
            val rootPath = root.toPath()
            privateDirectory(rootPath)
            sweep(rootPath, keepNewer = true)
            // A previous execution's files: this one starts empty, like a new pod's volume.
            if (fileSystem.metadataOrNull(configured) != null) fileSystem.deleteRecursively(configured)
            privateDirectory(configured)
        } else {
            val status = pathStatus(configured.toString())
            check(status != null && status.directory && !status.symlink) { "The uploads volume is missing." }
            sweep(configured, keepNewer = false)
        }
        directory = fileSystem.canonicalize(configured)
        true
    } catch (error: Exception) {
        log("Message files are off for this session: the uploads directory cannot be used (${error.message ?: error::class.simpleName}).")
        directory = null
        false
    }

    /**
     * Writes [file] from the bytes [download] streams (at most its size), once per file id, and returns its absolute
     * path. Throws [Refused] when the file is not safe or does not match; nothing is left behind then.
     */
    suspend fun write(file: RunnerFileView, download: suspend (receive: (ByteArray, Int) -> Unit) -> Unit): Path {
        written[file.id]?.let { if (fileSystem.exists(it)) return it }
        val base = directory ?: throw Refused(UNAVAILABLE)
        if (!FILE_ID.matches(file.id) || file.size < 0 || !SHA256.matches(file.sha256)) throw Refused(INVALID)
        root?.let { sweep(it.toPath(), keepNewer = true, keep = configured) }
        sweep(base, keepNewer = true)
        if (bytes + file.size > maxBytes) throw Refused(FULL)
        val target = base / "${file.id}-${safeName(file.name)}"
        val output = try { createExclusiveFile(target.toString(), fileMode) } catch (_: Exception) { throw Refused(WRITE_FAILED) } ?: throw Refused(WRITE_FAILED)
        var complete = false
        try {
            // The name holds no separator and the file was created without following a link; checked once more.
            if (fileSystem.canonicalize(target).parent != base) throw Refused(WRITE_FAILED)
            val hash = HashingSink.sha256(blackholeSink())
            val buffer = Buffer()
            var received = 0L
            try {
                download { chunk, count ->
                    received += count
                    if (received > file.size) throw Refused(INTEGRITY_FAILED)
                    output.write(chunk, count)
                    buffer.write(chunk, 0, count); hash.write(buffer, count.toLong())
                }
            } finally { output.close() }
            if (received != file.size || !hash.hash.hex().equals(file.sha256, ignoreCase = true)) throw Refused(INTEGRITY_FAILED)
            complete = true
        } finally {
            if (!complete) try { fileSystem.delete(target, mustExist = false) } catch (_: Exception) { }
        }
        written[file.id] = target
        bytes += file.size
        return target
    }

    /** Removes this execution's files: the session's directory on a member's device, the volume's content in a pod. */
    fun clear() {
        written.clear(); bytes = 0
        try {
            if (root != null) fileSystem.deleteRecursively(configured, mustExist = false)
            else fileSystem.list(configured).forEach { fileSystem.deleteRecursively(it) }
        } catch (_: Exception) { }
    }

    /** Creates [path] owner-only, or checks that the existing one is a real directory of this user, and makes it owner-only. */
    private fun privateDirectory(path: Path) {
        fileSystem.createDirectories(path.parent ?: path)
        createModeDirectory(path.toString(), DIRECTORY_MODE)
        val status = checkNotNull(pathStatus(path.toString())) { "The directory vanished." }
        check(status.directory && !status.symlink && status.ownedByCurrentUser) { "It is not a directory of this user." }
        if (status.mode != 0 && status.mode != DIRECTORY_MODE) setPathMode(path.toString(), DIRECTORY_MODE)
    }

    /** Removes the entries of [directory] older than [MAX_AGE_MILLIS] (every entry unless [keepNewer]), except [keep]. */
    private fun sweep(directory: Path, keepNewer: Boolean, keep: Path? = null) {
        val limit = now() - MAX_AGE_MILLIS
        val entries = try { fileSystem.list(directory) } catch (_: Exception) { return }
        for (entry in entries) {
            if (entry == keep) continue
            try {
                val modified = fileSystem.metadataOrNull(entry)?.lastModifiedAtMillis ?: continue
                if (keepNewer && modified >= limit) continue
                fileSystem.deleteRecursively(entry)
                written.entries.removeAll { (_, path) -> path == entry }
            } catch (_: Exception) { }
        }
    }

    companion object {
        const val DIRECTORY_MODE = 0x1C0 // 0700
        const val PRIVATE_FILE_MODE = 0x180 // 0600
        const val SHARED_FILE_MODE = 0x1A0 // 0640, read by the agent's UID through the pod's group
        /** A member's device keeps uploads at most a week, however long its session lives. */
        const val MAX_AGE_MILLIS = 7 * 24 * 60 * 60_000L
        /** What one execution holds, under the pod volume's 1 GiB `sizeLimit` (going over it would evict the pod). */
        const val MAX_BYTES = 768L * 1024 * 1024
        const val UNAVAILABLE = "files_unavailable"
        const val INVALID = "invalid_file"
        const val FULL = "uploads_full"
        const val DOWNLOAD_FAILED = "file_download_failed"
        const val INTEGRITY_FAILED = "file_integrity_failed"
        const val WRITE_FAILED = "file_write_failed"
        const val NOT_FOUND = "file_unavailable"
        private val FILE_ID = Regex("[A-Za-z0-9_-]{1,64}")
        private val SESSION_ID = Regex("[A-Za-z0-9_-]{1,128}")
        private val SHA256 = Regex("[0-9a-fA-F]{64}")
        private val CONTENT_TYPE = Regex("[A-Za-z0-9.+-]{1,64}/[A-Za-z0-9.+-]{1,64}")
        private const val MAX_NAME_BYTES = 150
        private val FORBIDDEN = "/\\<>:\"|?*"

        /** On a member's device: `<user temp>/teamcode-<uid>/<sessionId>/`. */
        fun remote(sessionId: String, userId: String, temporary: Path = FileSystem.SYSTEM_TEMPORARY_DIRECTORY,
            now: () -> Long = { Clock.System.now().toEpochMilliseconds() }): SessionUploads {
            require(SESSION_ID.matches(sessionId)) { "Invalid session id." }
            val root = temporary / "teamcode-${userId.filter { it.isLetterOrDigit() || it == '-' }}"
            return SessionUploads((root / sessionId).toString(), root.toString(), PRIVATE_FILE_MODE, now)
        }

        /** In a pod: the `uploads` volume, mounted at [directory] in both containers. */
        fun pod(directory: String, now: () -> Long = { Clock.System.now().toEpochMilliseconds() }) = SessionUploads(directory, null, SHARED_FILE_MODE, now)

        /**
         * The server cleans names already; the host never trusts that: the last path segment only, no control or
         * Windows-forbidden character, no leading dot or dash, at most [MAX_NAME_BYTES] UTF-8 bytes.
         */
        internal fun safeName(name: String): String {
            val segment = name.substringAfterLast('/').substringAfterLast('\\')
            val cleaned = segment.filter { it >= ' ' && it != '\u007F' && it !in FORBIDDEN }.trimStart('.', '-', ' ').trimEnd(' ', '.')
            val result = StringBuilder()
            var size = 0
            var index = 0
            while (index < cleaned.length) {
                val char = cleaned[index]
                // A character outside the BMP is one surrogate pair, kept or cut whole; a lone surrogate is dropped.
                val unit = when {
                    char.isHighSurrogate() && index + 1 < cleaned.length && cleaned[index + 1].isLowSurrogate() -> cleaned.substring(index, index + 2)
                    char.isSurrogate() -> ""
                    else -> char.toString()
                }
                index += maxOf(unit.length, 1)
                val length = unit.encodeToByteArray().size
                if (size + length > MAX_NAME_BYTES) break
                result.append(unit); size += length
            }
            return result.toString().ifEmpty { "file" }
        }

        /** The line the agent reads for one file. */
        fun line(path: Path, file: RunnerFileView) =
            "[Attached file: $path (${file.contentType.takeIf { CONTENT_TYPE.matches(it) } ?: "application/octet-stream"}, ${file.size} bytes)]"
    }
}
