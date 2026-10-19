@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package me.nathanfallet.teamcode.cli.platform

import kotlinx.cinterop.*
import kotlinx.coroutines.*
import me.nathanfallet.teamcode.cli.interop.process.*

private const val NEWLINE = '\n'.code.toByte()

private class PosixAgentProcess(private val pid: Int, private var input: Int, private var output: Int) : AgentProcess {
    private var exit: Int? = null
    private var pending = ByteArray(65_536)
    private var size = 0
    /** Bytes kept of the last, unfinished line in [pending]. */
    private var lineLength = 0
    private var outputClosed = false
    /** The output descriptor is closed once, by whichever of [readLine] and [stop] finishes last, so it is never reused under a reader. */
    private var reading = false
    private var stopped = false
    private fun closeOutput() { if (output >= 0) { teamcode_pipe_close(output); output = -1 } }

    private fun take(length: Int, consumed: Int, maxLine: Int): String {
        val line = pending.decodeToString(0, minOf(length, maxLine))
        pending.copyInto(pending, 0, consumed, size); size -= consumed
        return line
    }

    override suspend fun writeLine(line: String): Boolean {
        if (input < 0) return false
        val bytes = (line + "\n").encodeToByteArray()
        var offset = 0
        while (offset < bytes.size) {
            val written = bytes.usePinned { teamcode_pipe_write(input, it.addressOf(offset).reinterpret(), bytes.size - offset) }
            when {
                written >= 0 -> offset += written
                written == -1 -> { if (exitCode() != null) return false; delay(10) }
                else -> return false
            }
        }
        return true
    }

    override suspend fun readLine(maxLine: Int): String? {
        reading = true
        try { return readBounded(maxLine) } finally {
            reading = false
            if (stopped || outputClosed) closeOutput()
        }
    }

    private suspend fun readBounded(maxLine: Int): String? {
        val buffer = ByteArray(65_536)
        while (true) {
            val newline = (0 until size).firstOrNull { pending[it] == NEWLINE }
            if (newline != null) return take(newline, newline + 1, maxLine)
            if (outputClosed || output < 0) { outputClosed = true; return if (size == 0) null else take(size, size, maxLine) }
            val count = buffer.usePinned { teamcode_pipe_read(output, it.addressOf(0).reinterpret(), buffer.size) }
            when {
                count > 0 -> for (i in 0 until count) {
                    // Bound memory: past maxLine bytes, the rest of the line is dropped until its newline.
                    if (buffer[i] == NEWLINE) lineLength = 0
                    else if (lineLength >= maxLine) continue
                    else lineLength++
                    if (size == pending.size) pending = pending.copyOf(pending.size * 2)
                    pending[size++] = buffer[i]
                }
                count == 0 || count == -2 -> outputClosed = true
                // Once stopped, a descendant still holding the pipe open must not keep the reader waiting.
                else -> if (stopped) outputClosed = true else delay(20)
            }
        }
    }

    override fun closeInput() { if (input >= 0) { teamcode_pipe_close(input); input = -1 } }

    override fun exitCode(): Int? {
        exit?.let { return it }
        memScoped {
            val code = alloc<IntVar>()
            when (teamcode_poll(pid, code.ptr)) {
                1 -> exit = code.value
                -1 -> exit = -1
            }
        }
        return exit
    }

    override suspend fun stop(graceMillis: Long): Int = withContext(NonCancellable) {
        closeInput()
        var waited = 0L
        while (exitCode() == null && waited < graceMillis) { delay(50); waited += 50 }
        if (exitCode() == null) { teamcode_terminate(pid, 0); repeat(100) { if (exitCode() == null) delay(50) } }
        if (exitCode() == null) { teamcode_terminate(pid, 1); repeat(100) { if (exitCode() == null) delay(10) } }
        stopped = true
        if (!reading) closeOutput()
        exitCode() ?: -1
    }
}

actual fun startAgentProcess(command: String, arguments: List<String>, overrides: Map<String, String?>, workingDirectory: String): AgentProcess {
    teamcode_ignore_sigpipe()
    // posix_spawn has no portable chdir action on the glibc baseline: a fixed shell prologue changes directory, then execs.
    val argv = listOf("/bin/sh", "-c", "cd \"\$0\" && exec \"\$@\"", workingDirectory, command) + arguments
    return memScoped {
        val values = mutableMapOf<String, String>()
        val inherited = teamcode_environment()!!
        var index = 0
        while (true) {
            val entry = inherited[index++]?.toKString() ?: break
            val separator = entry.indexOf('=')
            if (separator > 0) values[entry.substring(0, separator)] = entry.substring(separator + 1)
        }
        overrides.forEach { (name, value) -> if (value == null) values.remove(name) else values[name] = value }
        fun vector(strings: List<String>): CPointer<CPointerVar<ByteVar>> {
            require(strings.none { '\u0000' in it })
            return allocArray<CPointerVar<ByteVar>>(strings.size + 1).also { array ->
                strings.forEachIndexed { i, text -> array[i] = text.cstr.ptr }
                array[strings.size] = null
            }
        }
        val pid = alloc<IntVar>(); val input = alloc<IntVar>(); val output = alloc<IntVar>()
        val result = teamcode_spawn_piped("/bin/sh", vector(argv), vector(values.map { "${it.key}=${it.value}" }), pid.ptr, input.ptr, output.ptr)
        check(result == 0) { "Cannot start the agent process." }
        PosixAgentProcess(pid.value, input.value, output.value)
    }
}
