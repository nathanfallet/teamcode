package me.nathanfallet.teamcode.companion.presentation.files

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import me.nathanfallet.teamcode.companion.domain.models.PickedFile

/** Where the paperclip takes a file from; each platform offers the ones it has. */
enum class FileSource { PHOTOS, CAMERA, DOCUMENTS }

/** Opens the platform's pickers; what they hand back reaches the `onPicked` given to [rememberFilePicker]. */
interface FilePicker {
    /** The sources this device offers, in the order the paperclip's menu lists them. */
    val sources: List<FileSource>
    fun launch(source: FileSource)
}

/**
 * The platform's pickers: the photo library, the camera and documents on Android and iOS, a file dialog on the desktop.
 * Photos come back reduced to [me.nathanfallet.teamcode.companion.domain.models.FilePolicy.PHOTO_MAX_PIXELS] on their
 * longer side; documents as they are. [onFailed] is told when a picked item could not be read.
 */
@Composable
expect fun rememberFilePicker(onPicked: (List<PickedFile>) -> Unit, onFailed: () -> Unit): FilePicker

/**
 * The screen's content, taking files dropped on it when [enabled] (the desktop's drag-and-drop; elsewhere nothing),
 * with [dropping] telling the content whether files are being dragged over it.
 */
@Composable
expect fun FileDropArea(enabled: Boolean, onDropped: (List<PickedFile>) -> Unit, modifier: Modifier, content: @Composable (dropping: Boolean) -> Unit)
