@file:OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)

package me.nathanfallet.teamcode.companion.presentation.files

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.uikit.LocalUIViewController
import kotlin.concurrent.AtomicInt
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.useContents
import kotlinx.cinterop.usePinned
import kotlinx.io.Buffer
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import me.nathanfallet.teamcode.companion.domain.models.FilePolicy
import me.nathanfallet.teamcode.companion.domain.models.PickedFile
import me.nathanfallet.teamcode.companion.domain.usecases.files.PhotoSizing
import platform.CoreGraphics.CGRectMake
import platform.CoreGraphics.CGSizeMake
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileSize
import platform.Foundation.NSNumber
import platform.Foundation.NSURL
import platform.PhotosUI.PHPickerConfiguration
import platform.PhotosUI.PHPickerFilter
import platform.PhotosUI.PHPickerResult
import platform.PhotosUI.PHPickerViewController
import platform.PhotosUI.PHPickerViewControllerDelegateProtocol
import platform.UIKit.UIDocumentPickerDelegateProtocol
import platform.UIKit.UIDocumentPickerViewController
import platform.UIKit.UIGraphicsBeginImageContextWithOptions
import platform.UIKit.UIGraphicsEndImageContext
import platform.UIKit.UIGraphicsGetImageFromCurrentImageContext
import platform.UIKit.UIImage
import platform.UIKit.UIImageJPEGRepresentation
import platform.UIKit.UIImagePickerController
import platform.UIKit.UIImagePickerControllerDelegateProtocol
import platform.UIKit.UIImagePickerControllerOriginalImage
import platform.UIKit.UIImagePickerControllerSourceType
import platform.UIKit.UINavigationControllerDelegateProtocol
import platform.UIKit.UIViewController
import platform.UniformTypeIdentifiers.UTTypeCommaSeparatedText
import platform.UniformTypeIdentifiers.UTTypeGIF
import platform.UniformTypeIdentifiers.UTTypeImage
import platform.UniformTypeIdentifiers.UTTypeJSON
import platform.UniformTypeIdentifiers.UTTypePDF
import platform.UniformTypeIdentifiers.UTTypeText
import platform.darwin.NSObject
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue
import platform.posix.memcpy

/**
 * iOS: the photo picker (PHPicker, no permission), the camera (UIImagePickerController, asking for the camera the
 * first time) and the document picker, which copies each file into the app's temporary folder. Photos are reduced to
 * 2048 px on their longer side and sent as JPEG (a GIF stays a GIF); documents are read from their copy when sent.
 */
@Composable
actual fun rememberFilePicker(onPicked: (List<PickedFile>) -> Unit, onFailed: () -> Unit): FilePicker {
    val controller = LocalUIViewController.current
    val picked by rememberUpdatedState(onPicked)
    val failed by rememberUpdatedState(onFailed)
    return remember(controller) {
        IosFilePicker(controller, { picked(it) }, { failed() })
    }
}

@Composable
actual fun FileDropArea(enabled: Boolean, onDropped: (List<PickedFile>) -> Unit, modifier: Modifier, content: @Composable (dropping: Boolean) -> Unit) {
    Box(modifier) { content(false) }
}

private class IosFilePicker(
    private val controller: UIViewController,
    private val picked: (List<PickedFile>) -> Unit,
    private val failed: () -> Unit,
) : FilePicker {
    // The pickers hold their delegates weakly: these keep them alive.
    private val photoDelegate = PhotoDelegate(::deliver)
    private val cameraDelegate = CameraDelegate(::deliver)
    private val documentDelegate = DocumentDelegate(::deliver)

    override val sources: List<FileSource> = buildList {
        add(FileSource.PHOTOS)
        if (UIImagePickerController.isSourceTypeAvailable(UIImagePickerControllerSourceType.UIImagePickerControllerSourceTypeCamera)) add(FileSource.CAMERA)
        add(FileSource.DOCUMENTS)
    }

    override fun launch(source: FileSource) {
        val picker: UIViewController = when (source) {
            FileSource.PHOTOS -> PHPickerViewController(PHPickerConfiguration().apply {
                selectionLimit = FilePolicy.MAX_FILES.toLong()
                filter = PHPickerFilter.imagesFilter
            }).apply { delegate = photoDelegate }
            FileSource.CAMERA -> UIImagePickerController().apply {
                sourceType = UIImagePickerControllerSourceType.UIImagePickerControllerSourceTypeCamera
                delegate = cameraDelegate
            }
            FileSource.DOCUMENTS -> UIDocumentPickerViewController(
                forOpeningContentTypes = listOf(UTTypeImage, UTTypePDF, UTTypeText, UTTypeCommaSeparatedText, UTTypeJSON),
                asCopy = true,
            ).apply {
                allowsMultipleSelection = true
                delegate = documentDelegate
            }
        }
        topmost(controller).presentViewController(picker, animated = true, completion = null)
    }

    /** On the main thread: the files read, and a failure for any that could not be. */
    private fun deliver(files: List<PickedFile?>) {
        dispatch_async(dispatch_get_main_queue()) {
            if (files.any { it == null }) failed()
            files.filterNotNull().takeIf { it.isNotEmpty() }?.let(picked)
        }
    }

    private fun topmost(root: UIViewController): UIViewController {
        var top = root
        while (true) top = top.presentedViewController ?: return top
    }
}

private class PhotoDelegate(private val deliver: (List<PickedFile?>) -> Unit) : NSObject(), PHPickerViewControllerDelegateProtocol {
    override fun picker(picker: PHPickerViewController, didFinishPicking: List<*>) {
        picker.dismissViewControllerAnimated(true, completion = null)
        val results = didFinishPicking.filterIsInstance<PHPickerResult>()
        if (results.isEmpty()) return
        val files = arrayOfNulls<PickedFile>(results.size)
        val left = AtomicInt(results.size)
        results.forEachIndexed { index, result ->
            val provider = result.itemProvider
            val gif = provider.hasItemConformingToTypeIdentifier(UTTypeGIF.identifier)
            val type = if (gif) UTTypeGIF.identifier else UTTypeImage.identifier
            val name = provider.suggestedName
            // Called on a background queue, once per photo; the last one hands them all over.
            provider.loadDataRepresentationForTypeIdentifier(type) { data, _ ->
                files[index] = data?.let { if (gif) gifFile(it, name) else photoFile(UIImage(data = it), name) }
                if (left.decrementAndGet() == 0) deliver(files.toList())
            }
        }
    }
}

private class CameraDelegate(private val deliver: (List<PickedFile?>) -> Unit) : NSObject(), UIImagePickerControllerDelegateProtocol, UINavigationControllerDelegateProtocol {
    override fun imagePickerController(picker: UIImagePickerController, didFinishPickingMediaWithInfo: Map<Any?, *>) {
        picker.dismissViewControllerAnimated(true, completion = null)
        val image = didFinishPickingMediaWithInfo[UIImagePickerControllerOriginalImage] as? UIImage
        deliver(listOf(image?.let { photoFile(it, "photo") }))
    }

    override fun imagePickerControllerDidCancel(picker: UIImagePickerController) {
        picker.dismissViewControllerAnimated(true, completion = null)
    }
}

private class DocumentDelegate(private val deliver: (List<PickedFile?>) -> Unit) : NSObject(), UIDocumentPickerDelegateProtocol {
    override fun documentPicker(controller: UIDocumentPickerViewController, didPickDocumentsAtURLs: List<*>) {
        deliver(didPickDocumentsAtURLs.filterIsInstance<NSURL>().map(::documentFile))
    }
}

/** A copied document, read from its copy each time it is sent; null when it is not a readable regular file. */
private fun documentFile(url: NSURL): PickedFile? {
    val path = url.path ?: return null
    val attributes = NSFileManager.defaultManager.attributesOfItemAtPath(path, error = null) ?: return null
    val size = (attributes[NSFileSize] as? NSNumber)?.longLongValue ?: return null
    val name = url.lastPathComponent ?: "file"
    return PickedFile(name, size) { SystemFileSystem.source(Path(path)) }
}

private fun gifFile(data: NSData, name: String?): PickedFile {
    val bytes = data.toByteArray()
    val base = name?.trim()?.takeIf { it.isNotEmpty() } ?: "image"
    return PickedFile(if (base.endsWith(".gif", ignoreCase = true)) base else "$base.gif", bytes.size.toLong(), "image/gif") { Buffer().apply { write(bytes) } }
}

/** [image] drawn upright with its longer side at most 2048 px, as a JPEG; null when it cannot be encoded. */
private fun photoFile(image: UIImage?, name: String?): PickedFile? {
    image ?: return null
    val (width, height) = image.size.useContents { width.toInt() to height.toInt() }
    if (width <= 0 || height <= 0) return null
    val (w, h) = PhotoSizing.target(width, height)
    // Drawing applies the photo's orientation, so the JPEG is upright without relying on its metadata.
    UIGraphicsBeginImageContextWithOptions(CGSizeMake(w.toDouble(), h.toDouble()), true, 1.0)
    image.drawInRect(CGRectMake(0.0, 0.0, w.toDouble(), h.toDouble()))
    val reduced = UIGraphicsGetImageFromCurrentImageContext()
    UIGraphicsEndImageContext()
    val data = reduced?.let { UIImageJPEGRepresentation(it, JPEG_QUALITY) } ?: return null
    val bytes = data.toByteArray()
    return PickedFile(PhotoSizing.jpegName(name), bytes.size.toLong(), "image/jpeg") { Buffer().apply { write(bytes) } }
}

private const val JPEG_QUALITY = 0.85

private fun NSData.toByteArray(): ByteArray {
    val size = length.toInt()
    val bytes = ByteArray(size)
    if (size > 0) bytes.usePinned { memcpy(it.addressOf(0), this.bytes, length) }
    return bytes
}
