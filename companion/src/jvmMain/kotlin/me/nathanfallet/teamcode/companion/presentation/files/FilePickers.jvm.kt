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
import java.awt.FileDialog
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
