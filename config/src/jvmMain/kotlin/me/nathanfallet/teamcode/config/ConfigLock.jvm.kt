package me.nathanfallet.teamcode.config

import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.*
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock

// Closing any channel on a file can release every lock the JVM holds on it, so one thread at a time opens the lock
// file: this in-process lock comes first, then the operating-system lock other processes see.
private val holders = ConcurrentHashMap<Path, ReentrantLock>()

actual fun <T> withFileLock(path: String, timeoutMillis: Long, block: () -> T): T {
    val file = Path.of(path).toAbsolutePath().normalize()
    val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
    val local = holders.computeIfAbsent(file) { ReentrantLock() }
    if (local.isHeldByCurrentThread) lockBusy(path)
    if (!local.tryLock(timeoutMillis, TimeUnit.MILLISECONDS)) lockBusy(path)
    try {
        JvmPrivateFiles.ensurePrivateDirectory(requireNotNull(file.parent))
        if (Files.isSymbolicLink(file)) throw FileSystemException(path, null, "The configuration lock cannot be a symbolic link.")
        val options = setOf(StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)
        val channel = if ("posix" in FileSystems.getDefault().supportedFileAttributeViews())
            FileChannel.open(file, options, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
        else FileChannel.open(file, options) // Inherits the directory's owner-only entry.
        channel.use {
            var lock: FileLock? = null
            while (lock == null) {
                lock = try { channel.tryLock() } catch (_: OverlappingFileLockException) { null }
                if (lock == null) {
                    if (System.nanoTime() >= deadline) lockBusy(path)
                    Thread.sleep(25)
                }
            }
            try { return block() } finally { lock.release() }
        }
    } finally { local.unlock() }
}
