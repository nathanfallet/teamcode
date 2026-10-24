package me.nathanfallet.teamcode.cli

import me.nathanfallet.teamcode.cli.platform.msiexecCommandLine
import me.nathanfallet.teamcode.cli.platform.quoteWindowsArgument
import kotlin.test.*

class WindowsArgumentsTest {
    @Test fun encodesEmptyArgumentsQuotesAndTrailingBackslashes() {
        assertEquals("\"\"", quoteWindowsArgument(""))
        assertEquals("\"two words\"", quoteWindowsArgument("two words"))
        assertEquals("\"a\\\"b\"", quoteWindowsArgument("a\"b"))
        assertEquals("\"C:\\path with spaces\\\\\"", quoteWindowsArgument("C:\\path with spaces\\"))
        assertEquals("\"& | < > %PATH% !var!\"", quoteWindowsArgument("& | < > %PATH% !var!"))
        assertFailsWith<IllegalArgumentException> { quoteWindowsArgument("a\u0000b") }
    }

    @Test fun msiexecGetsItsSwitchesAndPropertiesUnquoted() {
        assertEquals(
            "\"C:\\Windows\\System32\\msiexec.exe\" /i \"D:\\a b\\TeamCode.msi\" /qn /norestart /l*v \"C:\\t\\m.log\" MSIINSTALLPERUSER=1",
            msiexecCommandLine("C:\\Windows\\System32\\msiexec.exe", listOf("/i", "D:\\a b\\TeamCode.msi", "/qn", "/norestart", "/l*v", "C:\\t\\m.log", "MSIINSTALLPERUSER=1")),
        )
        assertEquals("\"msiexec.exe\" TARGETDIR=\"C:\\a b\"", msiexecCommandLine("msiexec.exe", listOf("TARGETDIR=C:\\a b")))
    }
}
