package me.nathanfallet.teamcode.cli.platform

/**
 * A path as the host sees it before writing a message file under it, read without following a link: whether it is a
 * directory or a link, whether the current user owns it, and its permission bits (0 where the platform has none).
 */
data class PathStatus(val directory: Boolean, val symlink: Boolean, val ownedByCurrentUser: Boolean, val mode: Int)

expect fun pathStatus(path: String): PathStatus?

/** Creates the directory [path] with [mode] (left as it is when it exists); returns whether it was created. */
expect fun createModeDirectory(path: String, mode: Int): Boolean

/** Sets the permission bits of [path], without following a link; nothing on a platform without them. */
expect fun setPathMode(path: String, mode: Int)

/** A file being written by the host; [close] once, after the last [write]. */
interface ExclusiveFile {
    fun write(bytes: ByteArray, count: Int)
    fun close()
}

/**
 * Creates [path] for writing only when nothing exists there (`O_CREAT | O_EXCL | O_NOFOLLOW`), with exactly [mode];
 * null when the path exists, a link included.
 */
expect fun createExclusiveFile(path: String, mode: Int): ExclusiveFile?
