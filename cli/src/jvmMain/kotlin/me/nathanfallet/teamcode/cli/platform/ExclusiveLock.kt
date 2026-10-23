package me.nathanfallet.teamcode.cli.platform

import java.nio.channels.FileChannel
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/** The JVM build only serves tests: the same contract with `FileChannel.tryLock`. */
actual fun holdExclusiveLock(path: String): AutoCloseable? {
    val channel = FileChannel.open(Path.of(path), StandardOpenOption.CREATE, StandardOpenOption.WRITE)
    val lock = try { channel.tryLock() } catch (_: OverlappingFileLockException) { null }
    if (lock == null) { channel.close(); return null }
    return AutoCloseable { lock.release(); channel.close() }
}

/** The JVM cannot change its own environment; tests never need it. */
actual fun setEnvironment(name: String, value: String) = Unit
