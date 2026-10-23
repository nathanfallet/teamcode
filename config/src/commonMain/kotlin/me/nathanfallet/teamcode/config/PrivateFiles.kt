package me.nathanfallet.teamcode.config

/**
 * Writes files only their owner can read: mode 0600 in a 0700 directory on POSIX, a single owner-only ACL entry on
 * Windows. Both operations write a complete temporary file first, so a reader sees the old or the new content, never a
 * partial one. The native CLI supplies its own implementation (`writePrivateFile`); the JVM uses [JvmPrivateFiles].
 */
interface PrivateFiles {
    /** Atomically replace [path] with [content]. */
    fun write(path: String, content: String)
    /** Atomically publish [content] at [path] only if nothing exists there; false when it already exists. */
    fun create(path: String, content: String): Boolean
}
