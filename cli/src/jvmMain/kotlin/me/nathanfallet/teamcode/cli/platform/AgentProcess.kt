package me.nathanfallet.teamcode.cli.platform

import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*

private class JvmAgentProcess(private val process: Process) : AgentProcess {
    private val reader = process.inputStream.bufferedReader()
    private val writer = process.outputStream.bufferedWriter()
    override suspend fun writeLine(line: String): Boolean = withContext(Dispatchers.IO) {
        try { writer.write(line); writer.newLine(); writer.flush(); true } catch (_: java.io.IOException) { false }
    }
    override suspend fun readLine(maxLine: Int): String? = withContext(Dispatchers.IO) {
        try { reader.readLine()?.let { if (it.length > maxLine) it.take(maxLine) else it } } catch (_: java.io.IOException) { null }
    }
    override fun closeInput() { try { writer.close() } catch (_: java.io.IOException) {} }
    override fun exitCode(): Int? = if (process.isAlive) null else process.exitValue()
    override suspend fun stop(graceMillis: Long): Int = withContext(NonCancellable + Dispatchers.IO) {
        closeInput()
        if (!process.waitFor(graceMillis, TimeUnit.MILLISECONDS)) {
            process.destroy()
            if (!process.waitFor(5, TimeUnit.SECONDS)) { process.destroyForcibly(); process.waitFor(5, TimeUnit.SECONDS) }
        }
        if (process.isAlive) -1 else process.exitValue()
    }
}

actual fun startAgentProcess(command: String, arguments: List<String>, overrides: Map<String, String?>, workingDirectory: String, scrubPrefixes: List<String>): AgentProcess {
    val builder = ProcessBuilder(listOf(command) + arguments).directory(File(workingDirectory)).redirectError(ProcessBuilder.Redirect.DISCARD)
    val kept = agentInheritedEnvironment(builder.environment().toMap(), scrubPrefixes)
    builder.environment().keys.retainAll(kept.keys)
    overrides.forEach { (name, value) -> if (value == null) builder.environment().remove(name) else builder.environment()[name] = value }
    return JvmAgentProcess(builder.start())
}
