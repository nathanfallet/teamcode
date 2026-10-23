package me.nathanfallet.teamcode.cli.platform

import java.io.File
import me.nathanfallet.teamcode.cli.CliOperationException

/** The JVM build only serves tests: the child gets its own stdin from nowhere and appends to the log. */
actual fun startDetachedProcess(command: String, arguments: List<String>, log: String): DetachedProcess {
    val child = try {
        ProcessBuilder(listOf(command) + arguments).redirectInput(ProcessBuilder.Redirect.from(File(if (System.getProperty("os.name").startsWith("Windows")) "NUL" else "/dev/null")))
            .redirectOutput(ProcessBuilder.Redirect.appendTo(File(log))).redirectErrorStream(true).start()
    } catch (_: java.io.IOException) { throw CliOperationException("detached_spawn_io") }
    return object : DetachedProcess { override fun exitCode(): Int? = if (child.isAlive) null else child.exitValue() }
}
