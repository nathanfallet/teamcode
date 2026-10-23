package me.nathanfallet.teamcode.cli.platform

/**
 * A process started to outlive this one (a remote session's host): its own session or process group, so a Ctrl-C in
 * the terminal or closing it does not reach it, stdin closed, stdout and stderr appended to a private log. This object
 * only reads its exit status; nothing ever signals it (it stops through the server, like any host).
 */
interface DetachedProcess {
    /** The exit code once the child exited, without waiting; null while it runs. */
    fun exitCode(): Int?
}

/**
 * Starts [command] with [arguments] and the current environment, its output appended to [log], which must already
 * exist (created privately by the caller). Throws [me.nathanfallet.teamcode.cli.CliOperationException] when it cannot start.
 */
expect fun startDetachedProcess(command: String, arguments: List<String>, log: String): DetachedProcess
