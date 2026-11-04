@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package me.nathanfallet.teamcode.cli.platform

import kotlinx.cinterop.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.nathanfallet.teamcode.cli.interop.windows.*

private const val NEWLINE = '\n'.code.toByte()

/**
 * Unix stream sockets over Winsock (AF_UNIX, Windows 10 1803 and later), polled without blocking like the POSIX ones.
 * The wake-up channel of a session host uses them: the agent's hooks reach the host through a file in its private directory.
 */
private class WindowsUnixConnection(private val socket: Long) : UnixConnection {
    private val readLock = Mutex()
    private val writeLock = Mutex()
    private var pending = ByteArray(65_536)
    private var size = 0
    private var lineLength = 0
    private var eof = false
    @kotlin.concurrent.Volatile private var closed = false
    @kotlin.concurrent.Volatile private var released = false

    /** Released once, by whichever of the reader, the writer and [close] sees the others gone, so the handle is never reused under one. */
    private fun release() {
        if (!closed || released) return
        if (!readLock.tryLock()) return
        try {
            if (!writeLock.tryLock()) return
            try { if (!released) { released = true; teamcode_wsock_close(socket) } } finally { writeLock.unlock() }
        } finally { readLock.unlock() }
    }

    override suspend fun readLine(maxLine: Int): String? {
        try { return readLock.withLock { if (released) null else readBounded(maxLine) } } finally { release() }
    }

    private suspend fun readBounded(maxLine: Int): String? {
        val buffer = ByteArray(65_536)
        while (true) {
            val newline = (0 until size).firstOrNull { pending[it] == NEWLINE }
            if (newline != null) return take(newline, newline + 1, maxLine)
            if (eof || closed) { eof = true; return if (size == 0) null else take(size, size, maxLine) }
            val count = buffer.usePinned { teamcode_wsock_read(socket, it.addressOf(0), buffer.size) }
            when {
                count > 0 -> for (i in 0 until count) {
                    // Bound memory: past maxLine bytes, the rest of the line is dropped until its newline.
                    if (buffer[i] == NEWLINE) lineLength = 0
                    else if (lineLength >= maxLine) continue
                    else lineLength++
                    if (size == pending.size) pending = pending.copyOf(pending.size * 2)
                    pending[size++] = buffer[i]
                }
                count < 0 -> eof = true
                else -> delay(10)
            }
        }
    }

    private fun take(length: Int, consumed: Int, maxLine: Int): String {
        val line = pending.decodeToString(0, minOf(length, maxLine))
        pending.copyInto(pending, 0, consumed, size); size -= consumed
        return line
    }

    override suspend fun writeLine(line: String): Boolean {
        if (closed) return false
        try {
            return writeLock.withLock {
                if (closed || released) return@withLock false
                val bytes = (line + "\n").encodeToByteArray()
                var offset = 0
                while (offset < bytes.size) {
                    if (closed) return@withLock false
                    val written = bytes.usePinned { teamcode_wsock_write(socket, it.addressOf(offset), bytes.size - offset) }
                    when {
                        written > 0 -> offset += written
                        written == 0 -> delay(5)
                        else -> return@withLock false
                    }
                }
                true
            }
        } finally { release() }
    }

    override fun close() { closed = true; release() }
}

private class WindowsUnixListener(private val socket: Long, private val path: String) : UnixListener {
    private val acceptLock = Mutex()
    @kotlin.concurrent.Volatile private var closed = false
    @kotlin.concurrent.Volatile private var released = false

    private fun release() {
        if (!closed || released || !acceptLock.tryLock()) return
        try {
            if (!released) { released = true; teamcode_wsock_close(socket); memScoped { teamcode_wsock_remove(path.wcstr.ptr) } }
        } finally { acceptLock.unlock() }
    }

    override suspend fun accept(): UnixConnection? {
        try {
            return acceptLock.withLock {
                while (!closed && !released) {
                    val next = memScoped {
                        val out = alloc<LongVar>()
                        when (teamcode_wsock_accept(socket, out.ptr)) { 1 -> out.value; 0 -> -1L; else -> -2L }
                    }
                    when {
                        next >= 0 -> return@withLock WindowsUnixConnection(next)
                        next == -1L -> delay(10)
                        else -> return@withLock null
                    }
                }
                null
            }
        } finally { release() }
    }

    override fun close() { closed = true; release() }
}

actual fun listenUnix(path: String): UnixListener {
    val socket = memScoped {
        val out = alloc<LongVar>()
        val result = teamcode_wsock_listen(path.wcstr.ptr, out.ptr)
        check(result == 0) { "Cannot listen on the socket." }
        out.value
    }
    return WindowsUnixListener(socket, path)
}

actual fun connectUnix(path: String): UnixConnection {
    val socket = memScoped {
        val out = alloc<LongVar>()
        val result = teamcode_wsock_connect(path.wcstr.ptr, out.ptr)
        check(result == 0) { "Cannot connect to the socket." }
        out.value
    }
    return WindowsUnixConnection(socket)
}
