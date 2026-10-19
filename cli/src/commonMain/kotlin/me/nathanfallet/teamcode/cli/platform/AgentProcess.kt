package me.nathanfallet.teamcode.cli.platform

/**
 * A long-lived child process with line-oriented stdin and stdout (JSON lines). Its stderr is discarded: agent
 * diagnostics may contain prompts and are never logged. Only the process this object started is ever signalled.
 */
interface AgentProcess {
    /** Writes one line (a newline is appended); false once the child stopped reading. */
    suspend fun writeLine(line: String): Boolean
    /** The next stdout line, or null at end of stream. Lines longer than [maxLine] bytes are cut. */
    suspend fun readLine(maxLine: Int = 4 * 1024 * 1024): String?
    /** Closes stdin so the agent finishes and exits by itself. */
    fun closeInput()
    /** The exit code once the child exited, without waiting. */
    fun exitCode(): Int?
    /** Waits up to [graceMillis] after closing stdin, then terminates and finally kills the child; returns its exit code. */
    suspend fun stop(graceMillis: Long): Int
}

/** Starts [command] in [workingDirectory] with the current environment changed by [overrides] (null removes a variable). */
expect fun startAgentProcess(command: String, arguments: List<String>, overrides: Map<String, String?>, workingDirectory: String): AgentProcess
