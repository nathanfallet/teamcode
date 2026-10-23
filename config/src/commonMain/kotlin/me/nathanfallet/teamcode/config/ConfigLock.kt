package me.nathanfallet.teamcode.config

/**
 * Run [block] while holding the advisory lock on [path] (an empty file, created when missing), shared by the native CLI
 * and the JVM desktop app: POSIX record locks (`fcntl`, the lock the JVM's `FileChannel.lock` takes), `LockFileEx` on
 * Windows. The kernel releases the lock when its holder exits, so the file is never deleted to "recover" from a holder
 * presumed dead. Waits up to [timeoutMillis], then refuses with a [UserFacingException]. Not reentrant: one acquisition
 * per process at a time, and never around a network call.
 */
expect fun <T> withFileLock(path: String, timeoutMillis: Long = DEFAULT_LOCK_TIMEOUT_MILLIS, block: () -> T): T

const val DEFAULT_LOCK_TIMEOUT_MILLIS = 10_000L

internal fun lockBusy(path: String): Nothing =
    throw UserFacingException("Another TeamCode command is changing the configuration (lock $path). Wait for it to finish, then try again.")
