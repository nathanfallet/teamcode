package me.nathanfallet.teamcode.companion.presentation.files

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import java.io.ByteArrayOutputStream
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.io.Buffer
import kotlinx.io.asSource
import me.nathanfallet.teamcode.companion.domain.models.FilePolicy
import me.nathanfallet.teamcode.companion.domain.models.PickedFile
import me.nathanfallet.teamcode.companion.domain.usecases.files.PhotoSizing

/**
 * Android: the system photo picker (no permission), the camera app (through a file this app shares with it, no camera
 * permission) and the document picker. Photos are reduced to 2048 px on their longer side and sent as JPEG, unless one
 * is already that small in a type the server takes (a GIF stays a GIF); documents are read as they are when sent.
 */
@Composable
actual fun rememberFilePicker(onPicked: (List<PickedFile>) -> Unit, onFailed: () -> Unit): FilePicker {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val picked by rememberUpdatedState(onPicked)
    val failed by rememberUpdatedState(onFailed)
    // The photo the camera writes to, kept across the activity being recreated while the camera app is in front.
    var capture by rememberSaveable { mutableStateOf<String?>(null) }

    fun deliver(read: suspend () -> List<PickedFile?>) {
        scope.launch {
            val files = try { withContext(Dispatchers.IO) { read() } } catch (error: CancellationException) { throw error } catch (_: Exception) { listOf(null) }
            if (files.any { it == null }) failed()
            files.filterNotNull().takeIf { it.isNotEmpty() }?.let(picked)
        }
    }

    val photos = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(FilePolicy.MAX_FILES)) { uris ->
        if (uris.isNotEmpty()) deliver { uris.map { photo(context, it, nameOf(context.contentResolver, it)) } }
    }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { taken ->
        val file = capture?.let(::File)
        capture = null
        if (file == null) return@rememberLauncherForActivityResult
        if (!taken) { file.delete(); return@rememberLauncherForActivityResult }
        deliver { listOf(photo(context, Uri.fromFile(file), "photo-${file.nameWithoutExtension.substringAfter('-')}.jpg").also { file.delete() }) }
    }
    val documents = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) deliver { uris.map { document(context.contentResolver, it) } }
    }
    return remember(photos, camera, documents) {
        object : FilePicker {
            override val sources = listOf(FileSource.PHOTOS, FileSource.CAMERA, FileSource.DOCUMENTS)
            override fun launch(source: FileSource) {
                try {
                    when (source) {
                        FileSource.PHOTOS -> photos.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                        FileSource.CAMERA -> {
                            // In the cache, shared with the camera app through the provider declared by the app.
                            val directory = File(context.cacheDir, CAMERA_DIRECTORY).apply { mkdirs() }
                            val file = File(directory, "photo-${System.currentTimeMillis()}.jpg")
                            capture = file.path
                            camera.launch(FileProvider.getUriForFile(context, context.packageName + PROVIDER_SUFFIX, file))
                        }
                        FileSource.DOCUMENTS -> documents.launch(DOCUMENT_TYPES)
                    }
                } catch (_: Exception) {
                    // No camera app, no document picker on this device.
                    capture = null
                    failed()
                }
            }
        }
    }
}

@Composable
actual fun FileDropArea(enabled: Boolean, onDropped: (List<PickedFile>) -> Unit, modifier: Modifier, content: @Composable (dropping: Boolean) -> Unit) {
    Box(modifier) { content(false) }
}

/** The document picker's filter: the kinds of files a team may take. */
private val DOCUMENT_TYPES = arrayOf("image/*", "application/pdf", "text/*", "application/json")
private const val CAMERA_DIRECTORY = "camera"
/** The authority of the app's FileProvider is its package name followed by this. */
internal const val PROVIDER_SUFFIX = ".files"
private const val JPEG_QUALITY = 85
/** A document whose size the provider does not tell is read into memory, at most this much (above every team's limit). */
private const val UNKNOWN_SIZE_CAP = 100L * 1024 * 1024 + 1

private fun nameOf(resolver: ContentResolver, uri: Uri): String? = try {
    resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) cursor.getString(0) else null
    }
} catch (_: Exception) { null }

private fun sizeOf(resolver: ContentResolver, uri: Uri): Long? = try {
    resolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else null
    }
} catch (_: Exception) { null }

/** A document, read from its provider each time it is sent; null when it cannot be read. */
private fun document(resolver: ContentResolver, uri: Uri): PickedFile? {
    val name = nameOf(resolver, uri)?.substringAfterLast('/')?.takeIf { it.isNotBlank() } ?: "file"
    val type = resolver.getType(uri)
    val size = sizeOf(resolver, uri)
    if (size != null && size >= 0) return PickedFile(name, size, type) { (resolver.openInputStream(uri) ?: error("unreadable")).asSource() }
    // No size from the provider: read it once, up to the cap; a larger file is refused by its size before any upload.
    val bytes = resolver.openInputStream(uri)?.use { input ->
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        while (out.size() < UNKNOWN_SIZE_CAP) { val read = input.read(buffer); if (read < 0) break; out.write(buffer, 0, read) }
        out.toByteArray()
    } ?: return null
    return PickedFile(name, bytes.size.toLong(), type) { Buffer().apply { write(bytes) } }
}

/** A photo, reduced and re-encoded as JPEG unless it is already small enough in a type the server takes; null when unreadable. */
private fun photo(context: Context, uri: Uri, name: String?): PickedFile? {
    val resolver = context.contentResolver
    val type = resolver.getType(uri) ?: if (uri.scheme == ContentResolver.SCHEME_FILE) "image/jpeg" else null
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) } ?: return null
    val (width, height) = bounds.outWidth to bounds.outHeight
    val kept = type in setOf("image/png", "image/jpeg", "image/webp", "image/gif") && width in 1..FilePolicy.PHOTO_MAX_PIXELS && height in 1..FilePolicy.PHOTO_MAX_PIXELS
    // A GIF may be animated: re-encoding would keep one frame. A small one goes as it is, a large one is reduced like any photo.
    if (kept && (type == "image/gif" || uri.scheme != ContentResolver.SCHEME_FILE)) {
        val bytes = resolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
        return PickedFile(name?.takeIf { it.isNotBlank() } ?: "photo", bytes.size.toLong(), type) { Buffer().apply { write(bytes) } }
    }
    val bitmap = decodeReduced(resolver, uri, width, height) ?: return null
    val bytes = ByteArrayOutputStream().use { out -> bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out); out.toByteArray() }
    bitmap.recycle()
    return PickedFile(PhotoSizing.jpegName(name), bytes.size.toLong(), "image/jpeg") { Buffer().apply { write(bytes) } }
}

/** The photo upright, its longer side at most 2048 px. */
private fun decodeReduced(resolver: ContentResolver, uri: Uri, width: Int, height: Int): Bitmap? {
    if (width <= 0 || height <= 0) return null
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        // ImageDecoder applies the photo's orientation; its target size is in upright pixels.
        val source = ImageDecoder.createSource(resolver, uri)
        return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            val (w, h) = PhotoSizing.target(info.size.width, info.size.height)
            decoder.setTargetSize(w, h)
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
    }
    val sampled = resolver.openInputStream(uri)?.use {
        BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = PhotoSizing.sampleSize(width, height) })
    } ?: return null
    val (w, h) = PhotoSizing.target(sampled.width, sampled.height)
    val rotation = resolver.openInputStream(uri)?.use {
        when (ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
    } ?: 0f
    val matrix = Matrix().apply { postScale(w.toFloat() / sampled.width, h.toFloat() / sampled.height); postRotate(rotation) }
    return Bitmap.createBitmap(sampled, 0, 0, sampled.width, sampled.height, matrix, true).also { if (it !== sampled) sampled.recycle() }
}
