package me.nathanfallet.teamcode.companion.desktop

import io.ktor.client.HttpClient
import io.ktor.client.engine.java.Java
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import me.nathanfallet.teamcode.api.models.ReleaseFormat
import me.nathanfallet.teamcode.client.ReleaseClient
import me.nathanfallet.teamcode.client.ReleaseDownloadException
import me.nathanfallet.teamcode.companion.domain.services.LocalHostException

/**
 * "Install" for a missing CLI: the install script of the app's own version (`install.sh`, or `install.ps1` on Windows),
 * never the latest alias, is downloaded without credentials, then run only when its SHA-256 is the one this build was
 * given ([expectedSha256], compiled in by the release pipeline). The script then makes its own checks: the signed
 * manifest, the binary's hash, the native installer. A build without a known hash ([available] false) only shows the
 * command. The script's output is passed on line by line.
 */
class CliInstaller(
    private val environment: DesktopEnvironment,
    private val appVersion: String = DesktopBuildInfo.VERSION,
    private val expectedSha256: String? = if (environment.os == DesktopEnvironment.Os.WINDOWS) DesktopBuildInfo.INSTALL_PS1_SHA256 else DesktopBuildInfo.INSTALL_SH_SHA256,
    private val download: suspend (version: String, name: String) -> ByteArray = { version, name ->
        ReleaseClient { configure -> HttpClient(Java) { configure(this) } }.use { it.installScript(version, name) }
    },
    private val timeoutMillis: Long = 15 * 60_000L,
) {
    val available: Boolean get() = expectedSha256 != null && ReleaseFormat.validVersion(appVersion)

    private val windows get() = environment.os == DesktopEnvironment.Os.WINDOWS
    private val scriptName get() = if (windows) "install.ps1" else "install.sh"

    /** Runs the script; returns once it exited. A refusal (download, hash, exit code) is a [LocalHostException]. */
    suspend fun install(output: (String) -> Unit) {
        val expected = expectedSha256?.takeIf { available } ?: throw LocalHostException("This build of TeamCode cannot install the CLI: use the command shown.")
        val script = try { download(appVersion, scriptName) } catch (error: ReleaseDownloadException) {
            throw LocalHostException("The install script of TeamCode $appVersion could not be downloaded (${error.code})." +
                (error.detail?.let { " Cause: $it." } ?: "") + " Check your network, VPN or proxy, or run the command shown in a terminal.")
        }
        if (sha256(script) != expected) throw LocalHostException("The downloaded install script is not the one this app was built with. Nothing was run.")
        withContext(Dispatchers.IO) {
            val directory = if (windows) Files.createTempDirectory("teamcode-install")
                else Files.createTempDirectory("teamcode-install", PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
            try {
                val file = directory.resolve(scriptName)
                Files.write(file, script, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
                val code = run(command(file.toString()), output)
                    ?: throw LocalHostException("The installer is still running after ${timeoutMillis / 60_000} minutes; check again once it finishes.")
                if (code != 0) throw LocalHostException("The installer stopped with exit code $code: see its output above.")
            } finally {
                directory.toFile().deleteRecursively()
            }
        }
    }

    /** The interpreter and the script, as an argument list (no shell parses the path). */
    fun command(script: String): List<String> =
        if (windows) listOf("powershell.exe", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-File", script)
        else listOf("/bin/sh", script)

    private suspend fun run(command: List<String>, output: (String) -> Unit): Int? {
        val builder = ProcessBuilder(command).redirectErrorStream(true)
            .redirectInput(ProcessBuilder.Redirect.from(File(if (windows) "NUL" else "/dev/null")))
        builder.environment().putAll(environment.cliVariables())
        val process = builder.start()
        val reader = Thread {
            process.inputStream.bufferedReader().useLines { lines -> lines.forEach { output(it.take(MAX_LINE)) } }
        }.apply { isDaemon = true; start() }
        // A script still running after the delay is left to finish on its own: the app never signals it.
        val exited = runInterruptible { process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS) }
        if (!exited) return null
        reader.join(2_000)
        return process.exitValue()
    }

    companion object {
        private const val MAX_LINE = 2_000

        fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
