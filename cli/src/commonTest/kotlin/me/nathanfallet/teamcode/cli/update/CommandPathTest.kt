package me.nathanfallet.teamcode.cli.update

import me.nathanfallet.teamcode.cli.CliOperationException
import me.nathanfallet.teamcode.cli.platform.secureRandomBytes
import okio.ByteString.Companion.toByteString
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import kotlin.test.*

class CommandPathTest {
    private val fs = FileSystem.SYSTEM
    private val symlinksAvailable = '\\' !in FileSystem.SYSTEM_TEMPORARY_DIRECTORY.toString()

    private fun scratch(block: (home: Path, launcher: Path) -> Unit) {
        val home = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / ("teamcode-path é " + secureRandomBytes(12).toByteString().hex())
        val launcher = home / "data" / "TeamCode" / "bin" / "teamcode"
        try { fs.createDirectories(launcher.parent!!); fs.write(launcher) { writeUtf8("launcher") }; block(home, launcher) }
        finally { if (fs.metadataOrNull(home) != null) fs.deleteRecursively(home) }
    }
    private fun posix(home: Path, launcher: Path, path: String = "/usr/bin") =
        CommandPath("linux-x64", launcher, { mapOf("HOME" to home.toString(), "PATH" to path)[it] })

    @Test fun linksTheStableLauncherAndIsIdempotent() {
        if (!symlinksAvailable) return
        scratch { home, launcher ->
            val link = home / ".local" / "bin" / "teamcode"
            assertIs<CommandPathOutcome.Ready>(posix(home, launcher).ensure())
            assertEquals(launcher, fs.metadata(link).symlinkTarget)
            assertIs<CommandPathOutcome.Ready>(posix(home, launcher).ensure())
            assertEquals(launcher, fs.metadata(link).symlinkTarget)
        }
    }
    @Test fun printsTheExactLineToAddWhenTheDirectoryIsNotOnPath() {
        if (!symlinksAvailable) return
        scratch { home, launcher ->
            val missing = posix(home, launcher, "/usr/bin:/bin").ensure().lines
            assertTrue(missing.any { it == "  export PATH=\"\$HOME/.local/bin:\$PATH\"" }, missing.toString())
            val present = posix(home, launcher, "/usr/bin:$home/.local/bin/:/bin").ensure().lines
            assertTrue(present.none { it.contains("export PATH") } && present.any { it.contains("is on your PATH") }, present.toString())
        }
    }
    @Test fun neverReplacesAFileOrLinkThatIsNotOurs() {
        if (!symlinksAvailable) return
        scratch { home, launcher ->
            val link = home / ".local" / "bin" / "teamcode"
            fs.createDirectories(link.parent!!)
            fs.write(link) { writeUtf8("someone else's program") }
            assertEquals(CommandPathOutcome.Conflict(link), posix(home, launcher).ensure())
            assertEquals("someone else's program", fs.read(link) { readUtf8() })
            fs.delete(link)
            fs.createSymlink(link, home / "elsewhere")
            assertEquals(CommandPathOutcome.Conflict(link), posix(home, launcher).ensure())
            assertEquals(home / "elsewhere", fs.metadata(link).symlinkTarget)
        }
    }
    @Test fun posixRequiresAHomeDirectory() {
        assertFailsWith<CliOperationException> { CommandPath("macos-arm64", "/x/bin/teamcode".toPath(), { null }).ensure() }
    }

    private val launcher = """C:\Users\me\AppData\Local\TeamCode\bin\teamcode.exe""".toPath()
    private fun windows(initial: UserPathValue?, writes: MutableList<UserPathValue>, vararg variables: Pair<String, String>) =
        CommandPath("windows-x64", launcher, { mapOf(*variables)[it] }, fs, { initial }, { writes += it })

    @Test fun appendsTheLauncherDirectoryToTheUserPathOnceKeepingItsType() {
        val directory = """C:\Users\me\AppData\Local\TeamCode\bin"""
        val writes = mutableListOf<UserPathValue>()
        windows(UserPathValue("""C:\Tools;%USERPROFILE%\bin""", expandable = true), writes).ensure()
        assertEquals(listOf(UserPathValue("""C:\Tools;%USERPROFILE%\bin;$directory""", true)), writes)
        windows(UserPathValue("""C:\Tools""", expandable = false), writes).ensure()
        assertEquals(UserPathValue("""C:\Tools;$directory""", false), writes.last())
        windows(null, writes).ensure()
        assertEquals(UserPathValue(directory, true), writes.last())
        assertEquals(3, writes.size)
    }
    @Test fun leavesTheUserPathAloneWhenTheDirectoryIsAlreadyThereInAnyForm() {
        val writes = mutableListOf<UserPathValue>()
        for (existing in listOf("""C:\Users\me\AppData\Local\TeamCode\bin""", """c:\users\ME\appdata\local\teamcode\bin\;C:\Tools""", """%LOCALAPPDATA%\TeamCode\bin""")) {
            windows(UserPathValue(existing, true), writes, "LOCALAPPDATA" to """C:\Users\me\AppData\Local""").ensure()
        }
        assertTrue(writes.isEmpty())
    }
}
