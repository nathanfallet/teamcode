package me.nathanfallet.teamcode.cli.platform

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

/** "macos", "linux" or "windows". */
internal expect fun hostOs(): String

/**
 * The command that opens [url] in the default browser, or null when no browser is worth opening here: an SSH session,
 * or Linux without a display. No shell is involved (Windows uses rundll32 rather than `start`), so nothing in the URL can
 * be read as shell syntax. Only https links are opened.
 */
internal fun browserCommand(os: String, url: String, environment: (String) -> String?): List<String>? {
    if (!url.startsWith("https://") || url.any { it.isWhitespace() || it.isISOControl() }) return null
    if (!environment("SSH_CONNECTION").isNullOrEmpty() || !environment("SSH_TTY").isNullOrEmpty()) return null
    return when (os) {
        "macos" -> listOf("open", url)
        "windows" -> listOf("rundll32.exe", "url.dll,FileProtocolHandler", url)
        else -> if (environment("DISPLAY").isNullOrEmpty() && environment("WAYLAND_DISPLAY").isNullOrEmpty()) null else listOf("xdg-open", url)
    }
}

/**
 * Best effort: true only if the opener exited successfully within ten seconds. Callers print the link first, so a
 * missing opener, a failure or a timeout only costs one line asking to open it by hand.
 */
internal suspend fun openBrowser(command: List<String>?, run: suspend (String, List<String>) -> Int = { name, arguments -> runInteractiveProcess(name, arguments, emptyMap(), quiet = true) }): Boolean {
    if (command == null) return false
    return try { withTimeoutOrNull(10_000) { run(command.first(), command.drop(1)) } == 0 }
    catch (error: CancellationException) { throw error }
    catch (_: Exception) { false }
}
