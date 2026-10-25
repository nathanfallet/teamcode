package me.nathanfallet.teamcode.companion

import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.awt.datatransfer.Transferable
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.File
import javax.imageio.ImageIO
import kotlinx.io.buffered
import kotlinx.io.readByteArray
import me.nathanfallet.teamcode.companion.presentation.files.pastedFiles
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PastedFilesTest {
    private class Clip(private vararg val data: Pair<DataFlavor, Any>) : Transferable {
        override fun getTransferDataFlavors() = data.map { it.first }.toTypedArray()
        override fun isDataFlavorSupported(flavor: DataFlavor) = data.any { it.first.equals(flavor) }
        override fun getTransferData(flavor: DataFlavor): Any = data.first { it.first.equals(flavor) }.second
    }

    private val image = BufferedImage(8, 6, BufferedImage.TYPE_INT_ARGB)

    @Test fun textIsLeftToTheField() {
        assertNull(pastedFiles(StringSelection("hello")))
        assertNull(pastedFiles(Clip()))
    }

    @Test fun copiedFilesAreAttachedLikeThePickersOnes() {
        val dir = createTempDirectory().toFile()
        val file = File(dir, "notes.txt").apply { writeText("abc") }
        val folder = File(dir, "folder").apply { mkdir() }
        val files = pastedFiles(Clip(DataFlavor.javaFileListFlavor to listOf(file, folder), DataFlavor.stringFlavor to "notes.txt"))!!
        assertEquals(listOf("notes.txt"), files.map { it.name })
        assertEquals(3L, files.single().size)
        assertEquals("abc", files.single().open().use { String(it.buffered().readByteArray()) })
    }

    @Test fun aCopiedFolderAloneStillCountsAsFilesPastedSoNothingIsTypedInTheField() {
        val dir = createTempDirectory().toFile()
        assertEquals(emptyList(), pastedFiles(Clip(DataFlavor.javaFileListFlavor to listOf(dir))))
    }

    @Test fun aScreenshotBecomesAPngNamedFileSentFromMemory() {
        val files = pastedFiles(Clip(DataFlavor.imageFlavor to image), now = { 1_700_000_000_000L })!!
        val file = files.single()
        assertTrue(file.name.startsWith("pasted-image-") && file.name.endsWith(".png"), file.name)
        assertEquals("image/png", file.type)
        val bytes = file.open().use { it.buffered().readByteArray() }
        assertEquals(bytes.size.toLong(), file.size)
        val decoded = ImageIO.read(ByteArrayInputStream(bytes))
        assertEquals(8 to 6, decoded.width to decoded.height)
        // Read again on a retry.
        assertEquals(bytes.size, file.open().use { it.buffered().readByteArray() }.size)
    }

    @Test fun anImageThatComesWithTextIsATableOrARichSelectionSoTheTextWins() {
        assertNull(pastedFiles(Clip(DataFlavor.imageFlavor to image, DataFlavor.stringFlavor to "a\tb")))
    }
}
