package me.nathanfallet.teamcode.cli.platform

/**
 * Takes the exclusive advisory lock on [path] (an empty file, created when missing) for as long as the returned handle
 * stays open, without waiting: null when another holder has it. The kernel releases it when the holder exits, and child
 * processes never inherit it. Unlike `config.lock` (held around a short read-modify-write), this lock is held for a
 * process's whole life: it is a BSD `flock` on POSIX, so other locks of the same process never release it, and
 * `LockFileEx` on Windows. A second acquisition in the same process gets null too.
 */
expect fun holdExclusiveLock(path: String): AutoCloseable?

/** Sets a variable of this process's environment, inherited by the processes it starts afterwards. */
expect fun setEnvironment(name: String, value: String)
