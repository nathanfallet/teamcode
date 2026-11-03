@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlin.experimental.ExperimentalNativeApi::class)
package me.nathanfallet.teamcode.cli.platform

import kotlinx.cinterop.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.nathanfallet.teamcode.cli.interop.windows.*

private const val NEWLINE = '\n'.code.toByte()

/**
 * An agent child on Windows: anonymous pipes for stdin and stdout, stderr on NUL, the child and its descendants in a
 * private job object that [stop] terminates (so no `node` or `claude` grandchild outlives the session).
 *
 * Reads poll the pipe without blocking, like the POSIX implementation. A write may block while the child's 1 MiB stdin
 * buffer is full, so it runs on [Dispatchers.IO]; [stop] terminates the job first, which breaks the pipe and frees it.
 * The native handle is released once, by [stop], after the reader and the writer let go of it.
 */
private class WindowsAgentProcess(private val handle: COpaquePointer) : AgentProcess {
    private val readLock = Mutex()
    private val writeLock = Mutex()
    private var exit: Int? = null
    private var pending = ByteArray(65_536)
    private var size = 0
    /** Bytes kept of the last, unfinished line in [pending]. */
    private var lineLength = 0
    private var outputClosed = false
    @kotlin.concurrent.Volatile private var stopped = false
    @kotlin.concurrent.Volatile private var released = false
    @kotlin.concurrent.Volatile private var inputClosed = false

    private fun take(length: Int, consumed: Int, maxLine: Int): String {
        val line = pending.decodeToString(0, minOf(length, maxLine))
        pending.copyInto(pending, 0, consumed, size); size -= consumed
        return line
    }

    override suspend fun writeLine(line: String): Boolean {
        val bytes = (line + "\n").encodeToByteArray()
        return writeLock.withLock {
            if (inputClosed || released) return@withLock false
            val delivered = withContext(Dispatchers.IO) {
                var offset = 0
                while (offset < bytes.size) {
                    val written = bytes.usePinned { teamcode_windows_agent_write(handle, it.addressOf(offset), bytes.size - offset) }
                    // Negative: the child no longer reads. Zero would spin on a pipe that accepts nothing.
                    if (written <= 0) return@withContext false
                    offset += written
                }
                true
            }
            if (inputClosed && !released) closeInputNow()
            delivered
        }
    }

    override suspend fun readLine(maxLine: Int): String? = readLock.withLock {
        if (released) return@withLock null
        readBounded(maxLine)
    }

    private suspend fun readBounded(maxLine: Int): String? {
        val buffer = ByteArray(65_536)
        while (true) {
            val newline = (0 until size).firstOrNull { pending[it] == NEWLINE }
            if (newline != null) return take(newline, newline + 1, maxLine)
            if (outputClosed) return if (size == 0) null else take(size, size, maxLine)
            val count = buffer.usePinned { teamcode_windows_agent_read(handle, it.addressOf(0), buffer.size) }
            when {
                count > 0 -> for (i in 0 until count) {
                    // Bound memory: past maxLine bytes, the rest of the line is dropped until its newline.
                    if (buffer[i] == NEWLINE) lineLength = 0
                    else if (lineLength >= maxLine) continue
                    else lineLength++
                    if (size == pending.size) pending = pending.copyOf(pending.size * 2)
                    pending[size++] = buffer[i]
                }
                count < 0 -> outputClosed = true
                // Nothing yet. Once stopped, a descendant still holding the pipe must not keep the reader waiting.
                else -> if (stopped) outputClosed = true else delay(20)
            }
        }
    }

    private fun closeInputNow() {
        inputClosed = true
        teamcode_windows_agent_close_input(handle)
    }

    /** Closes stdin so the agent finishes by itself; deferred to the writer in flight, which closes it when its write ends. */
    override fun closeInput() {
        if (released) return
        inputClosed = true
        if (writeLock.tryLock()) try { if (!released) teamcode_windows_agent_close_input(handle) } finally { writeLock.unlock() }
    }

    override fun exitCode(): Int? {
        exit?.let { return it }
        if (released) return -1
        memScoped {
            val code = alloc<UIntVar>()
            when (teamcode_windows_poll(handle, code.ptr)) {
                1 -> exit = code.value.toInt()
                -1 -> exit = -1
            }
        }
        return exit
    }

    override suspend fun stop(graceMillis: Long): Int = withContext(NonCancellable) {
        closeInput()
        var waited = 0L
        while (exitCode() == null && waited < graceMillis) { delay(50); waited += 50 }
        // The job holds the child and its descendants: terminating it ends all of them and breaks both pipes.
        if (exitCode() == null) { teamcode_windows_terminate(handle); repeat(100) { if (exitCode() == null) delay(50) } }
        stopped = true
        val code = exitCode() ?: -1
        exit = code
        // The reader leaves within one poll once stopped, and a blocked writer failed with the broken pipe.
        readLock.withLock { writeLock.withLock { if (!released) { released = true; teamcode_windows_agent_close(handle) } } }
        code
    }
}

actual fun startAgentProcess(command: String, arguments: List<String>, overrides: Map<String, String?>, workingDirectory: String, scrubPrefixes: List<String>): AgentProcess {
    val (application, actualArguments) = resolve(command, arguments)
    val line = (listOf(application) + actualArguments).joinToString(" ", transform = ::quoteWindowsArgument)
    if (line.length >= 32_767) throw ProcessStartException(ProcessStartException.Reason.TooLong, command)
    val handle = memScoped {
        val block = windowsEnvironmentBlock(overrides, scrubPrefixes)
        teamcode_windows_spawn_piped(application.wcstr.ptr, line.wcstr.ptr, block.wcstr.ptr, workingDirectory.wcstr.ptr)
    } ?: throw ProcessStartException(ProcessStartException.Reason.CannotLaunch, command)
    return WindowsAgentProcess(handle)
}
