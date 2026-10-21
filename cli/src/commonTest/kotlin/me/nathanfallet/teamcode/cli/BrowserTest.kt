package me.nathanfallet.teamcode.cli

import kotlinx.coroutines.test.runTest
import me.nathanfallet.teamcode.cli.platform.browserCommand
import me.nathanfallet.teamcode.cli.platform.openBrowser
import kotlin.test.*

class BrowserTest {
    private val url = "https://claude.ai/oauth/authorize?code=true&state=abc"
    private fun env(vararg values: Pair<String, String>): (String) -> String? = values.toMap()::get

    @Test fun eachPlatformOpensTheLinkWithoutAShell() {
        assertEquals(listOf("open", url), browserCommand("macos", url, env()))
        assertEquals(listOf("rundll32.exe", "url.dll,FileProtocolHandler", url), browserCommand("windows", url, env()))
        assertEquals(listOf("xdg-open", url), browserCommand("linux", url, env("DISPLAY" to ":0")))
        assertEquals(listOf("xdg-open", url), browserCommand("linux", url, env("WAYLAND_DISPLAY" to "wayland-0")))
    }

    @Test fun remoteTerminalsAndUnsafeLinksOnlyPrintTheLink() {
        assertNull(browserCommand("linux", url, env()), "Linux without a display has no browser to open")
        assertNull(browserCommand("macos", url, env("SSH_CONNECTION" to "10.0.0.1 22 10.0.0.2 22")))
        assertNull(browserCommand("windows", url, env("SSH_TTY" to "/dev/pts/1")))
        assertNull(browserCommand("macos", "http://example.test/", env()))
        assertNull(browserCommand("macos", "file:///etc/passwd", env()))
        assertNull(browserCommand("macos", "https://example.test/a b", env()))
    }

    @Test fun anOpenerThatFailsFallsBackToThePrintedLink() = runTest {
        assertTrue(openBrowser(listOf("open", url)) { name, arguments -> assertEquals("open", name); assertEquals(listOf(url), arguments); 0 })
        assertFalse(openBrowser(listOf("xdg-open", url)) { _, _ -> 3 })
        assertFalse(openBrowser(listOf("xdg-open", url)) { _, _ -> error("Install the requested executable and make it available on PATH.") })
        assertFalse(openBrowser(null) { _, _ -> fail("No opener must start when the terminal is remote") })
    }
}
