package me.nathanfallet.teamcode.cli

import me.nathanfallet.teamcode.cli.cloud.AttachFileCommands
import me.nathanfallet.teamcode.cli.cloud.FileCommand
import me.nathanfallet.teamcode.cli.cloud.expandHome
import me.nathanfallet.teamcode.cli.cloud.sizeLabel
import kotlin.test.*

/** `teamcode cloud attach`: the file commands, read without touching the disk or the server. */
class AttachFileCommandsTest {
    private fun parse(line: String) = AttachFileCommands.parse(line)

    @Test fun attachAndItsAliasTakeAPathAndAnOptionalMessage() {
        assertEquals(FileCommand.Attach("a.png", null), parse("/attach a.png"))
        assertEquals(FileCommand.Attach("a.png", null), parse("  /file   a.png  "))
        assertEquals(FileCommand.Attach("a.png", "what is wrong here?"), parse("/attach a.png \"what is wrong here?\""))
        assertEquals(FileCommand.Attach("a.png", "what is wrong here?"), parse("/attach a.png what is wrong here?"))
        assertEquals(FileCommand.Attach("a.png", "it's 'quoted'"), parse("/attach a.png it's 'quoted'"))
        // Empty quotes send the files with no text.
        assertEquals(FileCommand.Attach("a.png", ""), parse("/attach a.png \"\""))
    }

    @Test fun pathsWithSpacesAreQuotedOrEscapedAndWindowsBackslashesAreKept() {
        assertEquals(FileCommand.Attach("/tmp/my shot.png", "look"), parse("/attach \"/tmp/my shot.png\" look"))
        assertEquals(FileCommand.Attach("/tmp/my shot.png", null), parse("/attach '/tmp/my shot.png'"))
        assertEquals(FileCommand.Attach("/Users/me/My Shot.png", "look"), parse("/attach /Users/me/My\\ Shot.png look"))
        assertEquals(FileCommand.Attach("C:\\Users\\me\\a.png", null), parse("/attach C:\\Users\\me\\a.png"))
        assertEquals(FileCommand.Attach("~/a.png", null), parse("/attach ~/a.png"))
    }

    @Test fun aWrongFileCommandSaysHowToTypeIt() {
        for (line in listOf("/attach", "/file   ", "/attach \"never closed.png", "/attach \"\" text")) assertEquals(AttachFileCommands.USAGE, assertIs<FileCommand.Usage>(parse(line)).text, line)
        assertIs<FileCommand.Usage>(parse("/files now"))
        assertIs<FileCommand.Usage>(parse("/unattach a.png"))
    }

    @Test fun theListAndTheClearCommands() {
        assertEquals(FileCommand.Show, parse("/files"))
        assertEquals(FileCommand.Clear, parse(" /unattach "))
    }

    @Test fun otherLinesAreNotFileCommands() {
        for (line in listOf("hello", "/attachments are broken", "/message /attach a.png", "/detach", "/interrupt", "/fileserver", "")) assertNull(parse(line), line)
    }

    @Test fun aLoneDroppedPathIsOfferedOnlyWithADirectoryAndAKnownExtension() {
        assertEquals("/Users/me/shot.png", AttachFileCommands.pastedPath("/Users/me/shot.png"))
        assertEquals("/Users/me/My Shot.PNG", AttachFileCommands.pastedPath("  /Users/me/My\\ Shot.PNG "))
        assertEquals("/Users/me/My Shot.pdf", AttachFileCommands.pastedPath("'/Users/me/My Shot.pdf'"))
        assertEquals("~/notes.md", AttachFileCommands.pastedPath("~/notes.md"))
        assertEquals("C:\\Users\\me\\data.csv", AttachFileCommands.pastedPath("C:\\Users\\me\\data.csv"))
        for (line in listOf("notes.md", "/Users/me/build.log", "/Users/me/shot.png please", "look at /Users/me/shot.png", "/attach /Users/me/shot.png", "/Users/me/")) assertNull(AttachFileCommands.pastedPath(line), line)
    }

    @Test fun homeIsExpandedOnlyForATildeAlone() {
        assertEquals("/home/me/a.png", expandHome("~/a.png", "/home/me/"))
        assertEquals("C:\\Users\\me\\a.png", expandHome("~\\a.png", "C:\\Users\\me"))
        assertEquals("/home/me", expandHome("~", "/home/me"))
        assertEquals("~bob/a.png", expandHome("~bob/a.png", "/home/me"))
        assertEquals("/abs/a.png", expandHome("/abs/a.png", null))
        assertNull(expandHome("~/a.png", null))
    }

    @Test fun sizesAreShortAndInPowersOf1024() {
        assertEquals("512 B", sizeLabel(512)); assertEquals("245 KB", sizeLabel(245 * 1024L + 100)); assertEquals("1.2 MB", sizeLabel(1_258_291))
    }
}
