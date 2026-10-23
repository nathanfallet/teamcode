package me.nathanfallet.teamcode.domain.cloud

import me.nathanfallet.teamcode.domain.team.TeamFailure
import kotlin.test.*

/** What a client's file name and bytes may become on a host's disk. */
class CloudFileRulesTest {
    private fun clean(raw: String?) = CloudFileRules.sanitizeName(raw)

    @Test fun aNameIsNeverAPath() {
        assertEquals("passwd", clean("../../etc/passwd"))
        assertEquals("passwd", clean("/etc/passwd"))
        assertEquals("evil.txt", clean("..\\..\\Windows\\System32\\evil.txt"))
        assertEquals("evil.txt", clean("C:\\Users\\me\\evil.txt"))
        assertEquals("share.txt", clean("\\\\server\\share.txt"))
        assertEquals("file", clean(".."))
        assertEquals("file", clean("../"))
        assertEquals("file", clean("."))
        assertEquals("file", clean(""))
        assertEquals("file", clean(null))
        assertEquals("file", clean("///"))
        // Whatever is left contains no separator, no dot segment and nothing a shell or Windows reads specially.
        for (raw in listOf("a/../../b", "..\\..", "x/./y", "/", "\\", "a\u0000/../b", "....//....//etc")) {
            val name = clean(raw)
            assertFalse('/' in name || '\\' in name || name == "." || name == ".." || name.startsWith("."), "$raw -> $name")
        }
    }

    @Test fun controlFormatAndForbiddenCharactersAreRemoved() {
        assertEquals("ab.png", clean("a\u0000b.png"), "NUL")
        assertEquals("ab.txt", clean("a\nb\r.txt"))
        assertEquals("invoice_gpj.exe", clean("invoice_\u202Egpj.exe"), "A right-to-left override cannot disguise the extension")
        assertEquals("ab.txt", clean("a\u200Bb.txt"), "Zero-width space")
        assertEquals("a_b_c_d_e_f_g.txt", clean("a<b>c:d\"e|f?g.txt"))
        assertEquals("a_.txt", clean("a*.txt"))
        assertEquals("rf.txt", clean("-rf.txt"), "Never read as an option")
        assertEquals("hidden", clean(".hidden"))
        assertEquals("report", clean("report. . ."), "Windows drops trailing dots and spaces")
        assertEquals("a b.txt", clean("  a \t  b.txt  "))
    }

    @Test fun unicodeIsKeptNormalized() {
        assertEquals("Été ✓.md", clean("Été ✓.md"))
        // A decomposed é becomes the composed one, so one name is one file on every file system.
        assertEquals("\u00e9t\u00e9.txt", clean("e\u0301te\u0301.txt"))
        assertEquals("日本語のファイル.pdf", clean("日本語のファイル.pdf"))
        assertEquals("a.txt", clean("a\uD800.txt"), "A lone surrogate is dropped")
    }

    @Test fun windowsDeviceNamesArePrefixed() {
        for (device in listOf("CON", "con", "PRN", "AUX", "NUL", "nul.txt", "COM1", "com9.log", "LPT1", "LPT¹", "CONIN$", "CON .txt"))
            assertTrue(clean(device).startsWith("_"), "$device -> ${clean(device)}")
        assertEquals("CONSOLE.txt", clean("CONSOLE.txt"))
        assertEquals("COM10", clean("COM10"))
    }

    @Test fun aLongNameIsCutOnACharacterKeepingItsExtension() {
        val name = clean("a".repeat(500) + ".png")
        assertEquals(CloudFileRules.MAX_NAME_BYTES, name.encodeToByteArray().size); assertTrue(name.endsWith(".png"))
        val wide = clean("é".repeat(300) + ".pdf")
        assertTrue(wide.encodeToByteArray().size <= CloudFileRules.MAX_NAME_BYTES); assertTrue(wide.endsWith(".pdf")); assertTrue(wide.removeSuffix(".pdf").all { it == 'é' })
        val emoji = clean("😀".repeat(100))
        assertTrue(emoji.encodeToByteArray().size <= CloudFileRules.MAX_NAME_BYTES); assertEquals(emoji, String(emoji.encodeToByteArray()), "No split character")
        assertEquals("invalid_file_name", assertFailsWith<TeamFailure> { clean("a".repeat(CloudFileRules.MAX_RAW_NAME_CHARS + 1)) }.code)
    }

    @Test fun theTypeComesFromTheBytes() {
        fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }
        assertEquals("image/png", CloudFileRules.magicType(bytes(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0)))
        assertEquals("image/jpeg", CloudFileRules.magicType(bytes(0xFF, 0xD8, 0xFF, 0xE0)))
        assertEquals("image/gif", CloudFileRules.magicType("GIF89a...".encodeToByteArray()))
        assertEquals("image/gif", CloudFileRules.magicType("GIF87a...".encodeToByteArray()))
        assertEquals("image/webp", CloudFileRules.magicType("RIFF\u0000\u0000\u0000\u0000WEBPVP8 ".encodeToByteArray()))
        assertNull(CloudFileRules.magicType("RIFF\u0000\u0000\u0000\u0000WAVEfmt ".encodeToByteArray()), "A RIFF that is not WebP")
        assertEquals("application/pdf", CloudFileRules.magicType("%PDF-1.7\n".encodeToByteArray()))
        assertNull(CloudFileRules.magicType(bytes(0x4D, 0x5A, 0x90, 0x00)), "An executable")
        assertNull(CloudFileRules.magicType(bytes(0x89, 0x50)), "Too short")
        assertEquals("text/markdown", CloudFileRules.textType("notes.MD"))
        assertEquals("text/csv", CloudFileRules.textType("data.csv"))
        assertEquals("application/json", CloudFileRules.textType("a.json"))
        assertEquals("text/plain", CloudFileRules.textType("main.kt"))
    }

    @Test fun theExtensionFollowsTheType() {
        assertEquals("photo.png", CloudFileRules.finalName("photo.png", "image/png"))
        assertEquals("photo.JPEG", CloudFileRules.finalName("photo.JPEG", "image/jpeg"))
        assertEquals("photo.exe.png", CloudFileRules.finalName("photo.exe", "image/png"))
        assertEquals("photo.jpg", CloudFileRules.finalName("photo", "image/jpeg"))
        assertEquals("fake.png.txt", CloudFileRules.finalName("fake.png", "text/plain"), "Text never keeps an image extension")
        assertEquals("main.kt", CloudFileRules.finalName("main.kt", "text/plain"))
        val long = CloudFileRules.finalName(clean("a".repeat(500) + ".exe"), "application/pdf")
        assertTrue(long.endsWith(".pdf")); assertTrue(long.encodeToByteArray().size <= CloudFileRules.MAX_NAME_BYTES)
    }

    @Test fun textIsCheckedAcrossParts() {
        val text = "Été ✓ 😀".encodeToByteArray()
        // Every split, including inside a multi-byte character.
        for (cut in 1 until text.size) {
            val check = CloudFileRules.TextCheck()
            check.feed(text.copyOfRange(0, cut)); check.feed(text.copyOfRange(cut, text.size))
            assertTrue(check.finish(), "cut at $cut")
        }
        assertFalse(CloudFileRules.TextCheck().apply { feed(byteArrayOf(0x61, 0x00, 0x62)) }.finish(), "NUL")
        assertFalse(CloudFileRules.TextCheck().apply { feed(byteArrayOf(0xC3.toByte(), 0x28)) }.finish(), "Malformed")
        assertFalse(CloudFileRules.TextCheck().apply { feed(byteArrayOf(0x61, 0xE2.toByte(), 0x9C.toByte())) }.finish(), "Cut short at the end")
        assertTrue(CloudFileRules.TextCheck().apply { feed(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte(), 0x61)) }.finish(), "A byte-order mark")
    }
}
