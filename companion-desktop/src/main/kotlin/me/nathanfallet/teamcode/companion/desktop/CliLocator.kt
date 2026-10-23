package me.nathanfallet.teamcode.companion.desktop

import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext

/** A TeamCode CLI found on this device, and the version it reports (`teamcode version`). */
data class CliInstallation(val executable: String, val version: String)

/** What a CLI run gave: its exit code (null when it was still running after the delay) and its bounded output. */
data class CliResult(val exitCode: Int?, val stdout: String, val stderr: String)

/**
 * Runs the CLI with an argument list, never through a shell, with the app's environment plus
 * [DesktopEnvironment.cliVariables] (the same `config.json`, the login shell's `PATH`). Standard input is closed and
 * each output is kept to its first [MAX_OUTPUT] characters. A run still going after its delay is left to finish on its
 * own: the app never signals the CLI.
 */
class CliProcesses(private val environment: DesktopEnvironment) {
    suspend fun run(executable: String, arguments: List<String>, timeoutMillis: Long): CliResult = withContext(Dispatchers.IO) {
        val builder = ProcessBuilder(listOf(executable) + arguments)
            .redirectInput(ProcessBuilder.Redirect.from(File(if (environment.os == DesktopEnvironment.Os.WINDOWS) "NUL" else "/dev/null")))
        builder.environment().putAll(environment.cliVariables())
        val process = builder.start()
        val stdout = StringBuilder()
        val stderr = StringBuilder()
        val readers = listOf(process.inputStream to stdout, process.errorStream to stderr).map { (stream, into) ->
            Thread {
                stream.bufferedReader().use { reader ->
                    val buffer = CharArray(8_192)
                    while (true) {
                        val read = reader.read(buffer)
                        if (read < 0) break
                        synchronized(into) { if (into.length < MAX_OUTPUT) into.append(buffer, 0, minOf(read, MAX_OUTPUT - into.length)) }
                    }
                }
            }.apply { isDaemon = true; start() }
        }
        val exited = runInterruptible { process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS) }
        if (exited) readers.forEach { it.join(1_000) }
        CliResult(if (exited) process.exitValue() else null, synchronized(stdout) { stdout.toString() }, synchronized(stderr) { stderr.toString() })
    }

    private companion object { const val MAX_OUTPUT = 262_144 }
}

/**
 * Finds the CLI as the installer places it (docs/bootstrap.md): the `~/.local/bin/teamcode` link and the managed
 * launcher (`~/Library/Application Support/TeamCode/bin`, `~/.local/share/teamcode/bin`, `%LOCALAPPDATA%\TeamCode\bin`,
 * or under `XDG_DATA_HOME`), then every `PATH` entry, the login shell's included. The first one that answers
 * `teamcode version` is the one the app uses.
 */
class CliLocator(private val environment: DesktopEnvironment, private val processes: CliProcesses) {
    fun candidates(): List<String> {
        val home = environment["HOME"] ?: environment["USERPROFILE"]
        val data = environment["XDG_DATA_HOME"]?.takeIf { File(it).isAbsolute }
        val name = if (environment.os == DesktopEnvironment.Os.WINDOWS) "teamcode.exe" else "teamcode"
        val managed = when (environment.os) {
            DesktopEnvironment.Os.MACOS -> listOfNotNull(home?.let { "$it/.local/bin/teamcode" }, (data?.let { "$it/TeamCode" } ?: home?.let { "$it/Library/Application Support/TeamCode" })?.let { "$it/bin/teamcode" })
            DesktopEnvironment.Os.LINUX -> listOfNotNull(home?.let { "$it/.local/bin/teamcode" }, (data?.let { "$it/teamcode" } ?: home?.let { "$it/.local/share/teamcode" })?.let { "$it/bin/teamcode" })
            DesktopEnvironment.Os.WINDOWS -> listOfNotNull(environment["LOCALAPPDATA"]?.let { "$it\\TeamCode\\bin\\teamcode.exe" })
        }
        return (managed + environment.path.map { File(it, name).path }).distinct()
    }

    /** The first candidate that is an executable file and reports its version; null when none does. */
    suspend fun find(): CliInstallation? {
        for (candidate in candidates()) {
            val file = File(candidate)
            if (!file.isFile || !file.canExecute()) continue
            val result = try { processes.run(candidate, listOf("version"), VERSION_TIMEOUT_MILLIS) } catch (error: kotlinx.coroutines.CancellationException) { throw error } catch (_: Exception) { continue }
            if (result.exitCode != 0) continue
            parseVersion(result.stdout)?.let { return CliInstallation(candidate, it) }
        }
        return null
    }

    companion object {
        private const val VERSION_TIMEOUT_MILLIS = 10_000L

        /** `TeamCode 1.2.3` as `teamcode version` prints it. */
        fun parseVersion(output: String): String? = Regex("""TeamCode (\d+\.\d+\.\d+\S*)""").find(output)?.groupValues?.get(1)

        /** Sessions on this device need a CLI of the app's minor version or newer (the two ship under one number). */
        fun compatible(cli: String, app: String): Boolean {
            val (cliMajor, cliMinor) = majorMinor(cli) ?: return false
            val (appMajor, appMinor) = majorMinor(app) ?: return true
            return cliMajor > appMajor || (cliMajor == appMajor && cliMinor >= appMinor)
        }

        private fun majorMinor(version: String): Pair<Int, Int>? =
            Regex("""(\d+)\.(\d+)\.\d+""").find(version)?.groupValues?.let { it[1].toInt() to it[2].toInt() }

        /** The installer of the app's own version (or the latest one for a development build), for this system. */
        fun installCommand(os: DesktopEnvironment.Os, appVersion: String): String {
            val release = if (Regex("""\d+\.\d+\.\d+""").matches(appVersion)) "download/$appVersion" else "latest/download"
            val base = "https://github.com/nathanfallet/teamcode/releases/$release"
            return if (os == DesktopEnvironment.Os.WINDOWS) "& { \$f = Join-Path \$env:TEMP ([IO.Path]::GetRandomFileName() + '.ps1'); try { Invoke-WebRequest -UseBasicParsing " +
                "'$base/install.ps1' -OutFile \$f; & ([scriptblock]::Create([IO.File]::ReadAllText(\$f))) } finally { Remove-Item \$f -ErrorAction SilentlyContinue } }"
            else "curl -fsSL $base/install.sh | sh"
        }
    }
}
