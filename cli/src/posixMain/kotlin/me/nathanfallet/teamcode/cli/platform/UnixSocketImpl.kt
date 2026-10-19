@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package me.nathanfallet.teamcode.cli.platform

import kotlinx.cinterop.*
import kotlinx.coroutines.delay
import me.nathanfallet.teamcode.cli.interop.process.*

private const val NEWLINE = '\n'.code.toByte()

private class PosixUnixConnection(private val fd: Int) : UnixConnection {
    private var pending = ByteArray(65_536)
    private var size = 0
    private var lineLength = 0
    private var eof = false
    private var closed = false
    private var released = false
    /** The descriptor is released once, by whichever of [close] and the last running operation finishes last, so it is never reused under one. */
    private var reading = false
    private var writing = false

    private fun release() { if (!released && closed && !reading && !writing) { released = true; teamcode_pipe_close(fd) } }

    override suspend fun readLine(maxLine: Int): String? {
        reading = true
        try { return readBounded(maxLine) } finally { reading = false; release() }
    }

    private suspend fun readBounded(maxLine: Int): String? {
        val buffer = ByteArray(65_536)
        while (true) {
            val newline = (0 until size).firstOrNull { pending[it] == NEWLINE }
            if (newline != null) return take(newline, newline + 1, maxLine)
            if (eof || closed) { eof = true; return if (size == 0) null else take(size, size, maxLine) }
            val count = buffer.usePinned { teamcode_pipe_read(fd, it.addressOf(0).reinterpret(), buffer.size) }
            when {
                count > 0 -> for (i in 0 until count) {
                    // Bound memory: past maxLine bytes, the rest of the line is dropped until its newline.
                    if (buffer[i] == NEWLINE) lineLength = 0
                    else if (lineLength >= maxLine) continue
                    else lineLength++
                    if (size == pending.size) pending = pending.copyOf(pending.size * 2)
                    pending[size++] = buffer[i]
                }
                count == 0 || count == -2 -> eof = true
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
        writing = true
        try {
            val bytes = (line + "\n").encodeToByteArray()
            var offset = 0
            while (offset < bytes.size) {
                if (closed) return false
                val written = bytes.usePinned { teamcode_pipe_write(fd, it.addressOf(offset).reinterpret(), bytes.size - offset) }
                when {
                    written >= 0 -> offset += written
                    written == -1 -> delay(5)
                    else -> return false
                }
            }
            return true
        } finally { writing = false; release() }
    }

    override fun close() { closed = true; release() }
}

private class PosixUnixListener(private val fd: Int, private val path: String) : UnixListener {
    private var closed = false
    private var accepting = false
    private var released = false
    private fun release() { if (!released && closed && !accepting) { released = true; teamcode_pipe_close(fd); teamcode_unix_remove(path) } }

    override suspend fun accept(): UnixConnection? {
        accepting = true
        try {
            while (!closed) {
                val next = teamcode_unix_accept(fd)
                when {
                    next >= 0 -> return PosixUnixConnection(next)
                    next == -1 -> delay(10)
                    else -> return null
                }
            }
            return null
        } finally { accepting = false; release() }
    }
    override fun close() { closed = true; release() }
}

actual fun listenUnix(path: String): UnixListener {
    teamcode_ignore_sigpipe()
    val fd = teamcode_unix_listen(path)
    check(fd >= 0) { "Cannot listen on the socket." }
    return PosixUnixListener(fd, path)
}

actual fun connectUnix(path: String): UnixConnection {
    teamcode_ignore_sigpipe()
    val fd = teamcode_unix_connect(path)
    check(fd >= 0) { "Cannot connect to the socket." }
    return PosixUnixConnection(fd)
}
