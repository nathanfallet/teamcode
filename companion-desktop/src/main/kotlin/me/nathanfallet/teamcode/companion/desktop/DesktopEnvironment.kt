package me.nathanfallet.teamcode.companion.desktop

import java.io.File
import java.util.concurrent.TimeUnit
import me.nathanfallet.teamcode.config.configPath

/**
 * The environment the app shares with the CLI. An app opened from the Finder or a desktop launcher inherits neither the
 * shell's `PATH` nor its `XDG_CONFIG_HOME`: without care, the CLI and the app would read two different `config.json`.
 * So the app reads them once from a login shell ([loginShell]), and the CLI it runs gets the same values back.
 * [configOverride] is the `config.json` path given on the command line (`--config-path`, as `teamcode ui` passes it).
 */
class DesktopEnvironment(
    private val process: Map<String, String>,
    private val loginShell: Map<String, String> = emptyMap(),
    private val configOverride: String? = null,
    val os: Os = Os.current(),
) {
    enum class Os {
        MACOS, LINUX, WINDOWS;

        companion object {
            fun current(name: String = System.getProperty("os.name").orEmpty()): Os = when {
                name.startsWith("Mac", ignoreCase = true) -> MACOS
                name.startsWith("Windows", ignoreCase = true) -> WINDOWS
                else -> LINUX
            }
        }
    }

    /** A variable of the process, else of the login shell. */
    operator fun get(name: String): String? = process[name]?.takeIf { it.isNotEmpty() } ?: loginShell[name]?.takeIf { it.isNotEmpty() }

    /** The CLI's `config.json`, resolved as the CLI resolves it (`:config`), unless the command line gave it. */
    val configPath: String by lazy { configOverride ?: configPath { get(it) } }

    /** Every `PATH` entry, the process's first, then those only the login shell adds. */
    val path: List<String> get() = listOfNotNull(process["PATH"], loginShell["PATH"])
        .flatMap { it.split(File.pathSeparatorChar) }.filter { it.isNotBlank() }.distinct()

    /**
     * What the CLI the app starts is given on top of the app's own environment: the merged `PATH`, and the
     * `XDG_CONFIG_HOME` that makes it read the same `config.json` as the app.
     */
    fun cliVariables(): Map<String, String> = buildMap {
        put("PATH", path.joinToString(File.pathSeparator))
        val xdg = configOverride?.let { File(it).parentFile?.takeIf { dir -> dir.name == "teamcode" && File(it).name == "config.json" }?.parentFile?.path }
            ?: this@DesktopEnvironment["XDG_CONFIG_HOME"]
        xdg?.let { put("XDG_CONFIG_HOME", it) }
    }

    companion object {
        private const val MARKER = "__teamcode_environment__"

        /** The app's environment: its arguments, its process, and on macOS and Linux a login shell read once. */
        fun detect(args: Array<String>, process: Map<String, String> = System.getenv()): DesktopEnvironment {
            val override = args.indexOf("--config-path").takeIf { it >= 0 }?.let { args.getOrNull(it + 1) }?.takeIf { File(it).isAbsolute }
            val os = Os.current()
            // Started from a terminal, the app already has the shell's variables: no shell to start.
            val shell = if (os == Os.WINDOWS || process.containsKey("XDG_CONFIG_HOME") || process["TERM_PROGRAM"] != null) emptyMap()
            else readLoginShell(process["SHELL"]?.takeIf { File(it).isAbsolute } ?: "/bin/sh")
            return DesktopEnvironment(process, shell, override, os)
        }

        /**
         * `XDG_CONFIG_HOME` and `PATH` as a login shell sets them, bounded to [timeoutMillis]; empty when the shell
         * fails or is slow. Only these two values are read, between markers, so a profile that prints does no harm.
         */
        fun readLoginShell(shell: String, timeoutMillis: Long = 3_000): Map<String, String> = try {
            val script = "printf '\\n$MARKER%s\\n$MARKER%s\\n' \"\${XDG_CONFIG_HOME-}\" \"\${PATH-}\""
            val process = ProcessBuilder(shell, "-l", "-c", script)
                .redirectInput(ProcessBuilder.Redirect.from(File(if (Os.current() == Os.WINDOWS) "NUL" else "/dev/null")))
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start()
            val output = StringBuilder()
            val reader = Thread { output.append(process.inputStream.bufferedReader().readText().take(65_536)) }.apply { isDaemon = true; start() }
            // A shell still running after the delay is left to finish on its own; nothing waits for it.
            if (!process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)) emptyMap() else {
                reader.join(500)
                parseLoginShell(output.toString())
            }
        } catch (_: Exception) {
            emptyMap()
        }

        internal fun parseLoginShell(output: String): Map<String, String> {
            val values = output.lines().filter { it.startsWith(MARKER) }.map { it.removePrefix(MARKER) }
            if (values.size != 2) return emptyMap()
            return buildMap {
                values[0].takeIf { it.isNotBlank() && File(it).isAbsolute }?.let { put("XDG_CONFIG_HOME", it) }
                values[1].takeIf { it.isNotBlank() }?.let { put("PATH", it) }
            }
        }
    }
}
