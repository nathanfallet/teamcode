package me.nathanfallet.teamcode.companion.presentation.files

import me.nathanfallet.teamcode.companion.resources.Res
import me.nathanfallet.teamcode.companion.resources.files_attach
import org.jetbrains.compose.resources.stringResource
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.awtTransferable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import java.awt.FileDialog
import java.awt.Toolkit
import java.awt.datatransfer.Transferable
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.io.Buffer
import java.awt.Frame
import java.awt.datatransfer.DataFlavor
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import kotlinx.io.asSource
import me.nathanfallet.teamcode.companion.domain.models.PickedFile

/** The desktop: a native file dialog (several files at once). Photos and the camera are the phone's. */
@Composable
actual fun rememberFilePicker(onPicked: (List<PickedFile>) -> Unit, onFailed: () -> Unit): FilePicker {
    val picked by rememberUpdatedState(onPicked)
    val title = stringResource(Res.string.files_attach)
    return remember(title) {
        object : FilePicker {
            override val sources = listOf(FileSource.DOCUMENTS)
            override fun launch(source: FileSource) {
                val dialog = FileDialog(null as Frame?, title, FileDialog.LOAD).apply { isMultipleMode = true }
                dialog.isVisible = true
                val chosen = dialog.files.orEmpty().toList()
                dialog.dispose()
                picked(desktopFiles(chosen))
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalComposeUiApi::class)
@Composable
actual fun FileDropArea(enabled: Boolean, onDropped: (List<PickedFile>) -> Unit, modifier: Modifier, content: @Composable (dropping: Boolean) -> Unit) {
    var dropping by remember { mutableStateOf(false) }
    val dropped by rememberUpdatedState(onDropped)
    val target = remember {
        object : DragAndDropTarget {
            override fun onEntered(event: DragAndDropEvent) { dropping = true }
            override fun onExited(event: DragAndDropEvent) { dropping = false }
            override fun onEnded(event: DragAndDropEvent) { dropping = false }
            override fun onDrop(event: DragAndDropEvent): Boolean {
                dropping = false
                val files = droppedFiles(event) ?: return false
                dropped(desktopFiles(files))
                return true
            }
        }
    }
    Box(if (enabled) modifier.dragAndDropTarget(shouldStartDragAndDrop = { droppedFiles(it) != null }, target = target) else modifier) {
        content(enabled && dropping)
    }
}

@OptIn(ExperimentalComposeUiApi::class)
private fun droppedFiles(event: DragAndDropEvent): List<File>? = try {
    val transferable = event.awtTransferable
    if (!transferable.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) null
    else (transferable.getTransferData(DataFlavor.javaFileListFlavor) as? List<*>)?.filterIsInstance<File>()
} catch (_: Exception) {
    null
}

/**
 * Regular files only, never followed through a link (the file sent is the one shown) and never a folder; each is
 * read from disk when sent, so a retry reads it again.
 */
internal fun desktopFiles(files: List<File>): List<PickedFile> = files.filter { file ->
    val path = file.toPath()
    Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
}.map { file -> PickedFile(file.name, file.length()) { file.inputStream().asSource() } }

/**
 * The desktop: Ctrl+V (Cmd+V on macOS, Shift+Insert elsewhere) with files or an image on the clipboard attaches them, as
 * the paperclip's file dialog would; with anything else the key goes to the field, which pastes its text.
 */
@Composable
actual fun Modifier.pasteFiles(enabled: Boolean, onPasted: (List<PickedFile>) -> Unit, onFailed: () -> Unit): Modifier {
    if (!enabled) return this
    val pasted by rememberUpdatedState(onPasted)
    val failed by rememberUpdatedState(onFailed)
    val scope = rememberCoroutineScope()
    val mac = remember { System.getProperty("os.name").orEmpty().startsWith("Mac") }
    return onPreviewKeyEvent { event ->
        val paste = event.type == KeyEventType.KeyDown && !event.isAltPressed && when {
            event.key == Key.V -> if (mac) event.isMetaPressed && !event.isCtrlPressed else event.isCtrlPressed && !event.isMetaPressed
            event.key == Key.Insert -> !mac && event.isShiftPressed && !event.isCtrlPressed
            else -> false
        } && !(event.key == Key.V && event.isShiftPressed)
        if (!paste) return@onPreviewKeyEvent false
        val clip = try { Toolkit.getDefaultToolkit().systemClipboard.getContents(null) } catch (_: Exception) { null }
        if (clip == null || !pastesFiles(clip)) return@onPreviewKeyEvent false
        scope.launch {
            val files = try { withContext(Dispatchers.IO) { pastedFiles(clip) } } catch (error: CancellationException) { throw error } catch (_: Exception) { null }
            if (files.isNullOrEmpty()) failed() else pasted(files)
        }
        true
    }
}

/** Whether the clipboard holds files, or an image alone: what a paste attaches rather than types. */
internal fun pastesFiles(clip: Transferable): Boolean = try {
    clip.isDataFlavorSupported(DataFlavor.javaFileListFlavor) ||
        // An image that comes with text is a copied table or a rich selection: the text is what was meant.
        (clip.isDataFlavorSupported(DataFlavor.imageFlavor) && !clip.isDataFlavorSupported(DataFlavor.stringFlavor))
} catch (_: Exception) { false }

/**
 * The files a paste attaches, null when the clipboard holds neither files nor an image alone ([pastesFiles]). Copied files
 * go through [desktopFiles] (regular files only, read when sent); an image becomes a PNG named after [now], kept in memory
 * so that a retry sends it again and no temporary file is left behind.
 */
internal fun pastedFiles(clip: Transferable, now: () -> Long = System::currentTimeMillis): List<PickedFile>? {
    if (!pastesFiles(clip)) return null
    if (clip.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
        return desktopFiles((clip.getTransferData(DataFlavor.javaFileListFlavor) as? List<*>).orEmpty().filterIsInstance<File>())
    }
    val image = clip.getTransferData(DataFlavor.imageFlavor) as? java.awt.Image ?: return emptyList()
    val width = image.getWidth(null)
    val height = image.getHeight(null)
    if (width <= 0 || height <= 0) return emptyList()
    val buffered = image as? BufferedImage ?: BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB).also { copy ->
        copy.createGraphics().apply { drawImage(image, 0, 0, null); dispose() }
    }
    val bytes = ByteArrayOutputStream().use { out -> if (!ImageIO.write(buffered, "png", out)) return emptyList(); out.toByteArray() }
    return listOf(PickedFile("pasted-image-${now()}.png", bytes.size.toLong(), "image/png") { Buffer().apply { write(bytes) } })
}
