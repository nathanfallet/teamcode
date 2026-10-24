package me.nathanfallet.teamcode.companion.presentation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.regular.Camera
import com.adamglin.phosphoricons.regular.File
import com.adamglin.phosphoricons.regular.FolderOpen
import com.adamglin.phosphoricons.regular.Image
import com.adamglin.phosphoricons.regular.Paperclip
import com.adamglin.phosphoricons.regular.X
import me.nathanfallet.teamcode.companion.domain.models.FileProblem
import me.nathanfallet.teamcode.companion.domain.models.PendingFile
import me.nathanfallet.teamcode.companion.domain.models.UploadedFile
import me.nathanfallet.teamcode.companion.presentation.files.FilePicker
import me.nathanfallet.teamcode.companion.presentation.files.FileSource
import me.nathanfallet.teamcode.companion.presentation.theme.MetaStyle
import me.nathanfallet.teamcode.companion.presentation.theme.TeamCode
import me.nathanfallet.teamcode.companion.presentation.viewmodels.FilesUiState
import me.nathanfallet.teamcode.companion.resources.Res
import me.nathanfallet.teamcode.companion.resources.file_problem_denied
import me.nathanfallet.teamcode.companion.resources.file_problem_empty
import me.nathanfallet.teamcode.companion.resources.file_problem_gone
import me.nathanfallet.teamcode.companion.resources.file_problem_integrity
import me.nathanfallet.teamcode.companion.resources.file_problem_message_too_large
import me.nathanfallet.teamcode.companion.resources.file_problem_other
import me.nathanfallet.teamcode.companion.resources.file_problem_quota_daily
import me.nathanfallet.teamcode.companion.resources.file_problem_quota_session
import me.nathanfallet.teamcode.companion.resources.file_problem_storage_off
import me.nathanfallet.teamcode.companion.resources.file_problem_storage_unavailable
import me.nathanfallet.teamcode.companion.resources.file_problem_too_large
import me.nathanfallet.teamcode.companion.resources.file_problem_too_many
import me.nathanfallet.teamcode.companion.resources.file_problem_type
import me.nathanfallet.teamcode.companion.resources.file_problem_unreachable
import me.nathanfallet.teamcode.companion.resources.file_problem_unreadable
import me.nathanfallet.teamcode.companion.resources.file_problem_unsupported
import me.nathanfallet.teamcode.companion.resources.files_attach
import me.nathanfallet.teamcode.companion.resources.files_remove
import me.nathanfallet.teamcode.companion.resources.files_retry
import me.nathanfallet.teamcode.companion.resources.files_source_camera
import me.nathanfallet.teamcode.companion.resources.files_source_documents
import me.nathanfallet.teamcode.companion.resources.files_source_photos
import me.nathanfallet.teamcode.companion.resources.files_uploaded
import org.jetbrains.compose.resources.stringResource

/**
 * The paperclip beside the message field: the picker at once when the device has one source (the desktop's file
 * dialog), a menu of sources otherwise (photos, camera, files). While no file can be added (files off for the team, or
 * already the most a message carries), a tap explains why instead.
 */
@Composable
fun AttachButton(files: FilesUiState, picker: FilePicker, onUnavailable: () -> Unit, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        TcIconButton(PhosphorIcons.Regular.Paperclip, stringResource(Res.string.files_attach), onClick = {
            when {
                !files.canAttach -> onUnavailable()
                picker.sources.size == 1 -> picker.launch(picker.sources.single())
                else -> open = true
            }
        }, modifier = Modifier.testTag("attach"))
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, containerColor = TeamCode.colors.panel) {
            picker.sources.forEach { source ->
                val (label, icon) = when (source) {
                    FileSource.PHOTOS -> Res.string.files_source_photos to PhosphorIcons.Regular.Image
                    FileSource.CAMERA -> Res.string.files_source_camera to PhosphorIcons.Regular.Camera
                    FileSource.DOCUMENTS -> Res.string.files_source_documents to PhosphorIcons.Regular.FolderOpen
                }
                DropdownMenuItem(text = { Text(stringResource(label)) }, leadingIcon = { Icon(icon, null, Modifier.size(18.dp)) },
                    onClick = { open = false; picker.launch(source) }, modifier = Modifier.testTag("attach-${source.name.lowercase()}"))
            }
        }
    }
}

/** The files of the next message, one chip each: its name, then its progress, its size once uploaded, or its problem. */
@Composable
fun PendingFileChips(files: List<PendingFile>, onRetry: (String) -> Unit, onRemove: (String) -> Unit, modifier: Modifier = Modifier) {
    if (files.isEmpty()) return
    Row(modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).testTag("pending-files"), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        files.forEach { FileChip(it, onRetry, onRemove) }
    }
}

@Composable
private fun FileChip(file: PendingFile, onRetry: (String) -> Unit, onRemove: (String) -> Unit) {
    val colors = TeamCode.colors
    val failed = file.state == PendingFile.State.FAILED
    val shape = RoundedCornerShape(10.dp)
    Column(
        Modifier.width(196.dp).clip(shape).background(if (failed) colors.dangerBackground else colors.panel)
            .border(1.dp, if (failed) colors.danger else colors.line, shape).padding(start = 10.dp, end = 2.dp, top = 4.dp, bottom = 6.dp).testTag("file-${file.localId}"),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(if (isImage(file.name)) PhosphorIcons.Regular.Image else PhosphorIcons.Regular.File, null, tint = colors.muted, modifier = Modifier.size(16.dp))
            Text(file.name, fontSize = 13.sp, color = colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            TcIconButton(PhosphorIcons.Regular.X, stringResource(Res.string.files_remove, file.name), onClick = { onRemove(file.localId) },
                modifier = Modifier.size(32.dp).testTag("remove-file-${file.localId}"), tint = colors.muted)
        }
        when (file.state) {
            PendingFile.State.UPLOADING -> Column(Modifier.padding(end = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                LinearProgressIndicator(progress = { file.progress }, modifier = Modifier.fillMaxWidth().height(3.dp), color = colors.accent, trackColor = colors.line)
                Text("${sizeLabel(file.sent)} / ${sizeLabel(file.size)}", style = MetaStyle.copy(fontSize = 11.sp), color = colors.muted)
            }
            PendingFile.State.UPLOADED -> Text("${stringResource(Res.string.files_uploaded)} · ${sizeLabel(file.size)}", style = MetaStyle.copy(fontSize = 11.sp), color = colors.muted)
            PendingFile.State.FAILED -> Column(Modifier.padding(end = 8.dp)) {
                Text(file.problem?.let { fileProblemText(it) }.orEmpty(), fontSize = 11.5.sp, color = colors.danger, maxLines = 3, overflow = TextOverflow.Ellipsis)
                if (file.problem?.retryable == true) LinkButton(stringResource(Res.string.files_retry), onClick = { onRetry(file.localId) }, modifier = Modifier.testTag("retry-file-${file.localId}"))
            }
        }
    }
}

/** The files a pending message carries, under its text. */
@Composable
fun MessageFiles(files: List<UploadedFile>, modifier: Modifier = Modifier) {
    if (files.isEmpty()) return
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp), horizontalAlignment = Alignment.End) {
        files.forEach { file ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Icon(PhosphorIcons.Regular.Paperclip, null, tint = TeamCode.colors.muted, modifier = Modifier.size(13.dp))
                Text("${file.name} (${sizeLabel(file.size)})", fontSize = 12.sp, color = TeamCode.colors.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** The sentence for [problem]. */
@Composable
fun fileProblemText(problem: FileProblem): String = when (problem) {
    is FileProblem.TooMany -> stringResource(Res.string.file_problem_too_many, problem.max)
    FileProblem.Empty -> stringResource(Res.string.file_problem_empty)
    is FileProblem.TooLarge -> stringResource(Res.string.file_problem_too_large, sizeLabel(problem.maxBytes))
    is FileProblem.MessageTooLarge -> stringResource(Res.string.file_problem_message_too_large, sizeLabel(problem.maxBytes))
    is FileProblem.TypeNotAllowed -> stringResource(Res.string.file_problem_type, typesLabel(problem.types))
    FileProblem.Unreadable -> stringResource(Res.string.file_problem_unreadable)
    is FileProblem.Quota -> problem.retryAfterSeconds?.let { stringResource(Res.string.file_problem_quota_daily, waitLabel(it)) } ?: stringResource(Res.string.file_problem_quota_session)
    FileProblem.StorageNotConfigured -> stringResource(Res.string.file_problem_storage_off)
    FileProblem.StorageUnavailable -> stringResource(Res.string.file_problem_storage_unavailable)
    FileProblem.Integrity -> stringResource(Res.string.file_problem_integrity)
    FileProblem.Denied -> stringResource(Res.string.file_problem_denied)
    FileProblem.Unreachable -> stringResource(Res.string.file_problem_unreachable)
    FileProblem.Unsupported -> stringResource(Res.string.file_problem_unsupported)
    FileProblem.SessionGone -> stringResource(Res.string.file_problem_gone)
    is FileProblem.Other -> stringResource(Res.string.file_problem_other, problem.code ?: "error")
}

/** A size as `512 B`, `245 KB` or `1.2 MB` (powers of 1024), as the CLI words it. */
fun sizeLabel(bytes: Long): String = when {
    bytes >= 1024L * 1024 -> "${(bytes * 10 + 512 * 1024) / (1024 * 1024) / 10.0} MB"
    bytes >= 1024 -> "${bytes / 1024} KB"
    else -> "$bytes B"
}

/** The types a team takes, by their common names: `PNG, JPEG, PDF, text`. */
fun typesLabel(types: List<String>): String = types.joinToString(", ") { TYPE_LABELS[it] ?: it }

internal fun waitLabel(seconds: Long): String = when {
    seconds >= 3600 -> "${(seconds + 1800) / 3600} h"
    seconds >= 60 -> "${(seconds + 30) / 60} min"
    else -> "$seconds s"
}

private fun isImage(name: String) = name.substringAfterLast('.', "").lowercase() in setOf("png", "jpg", "jpeg", "webp", "gif")

private val TYPE_LABELS = mapOf(
    "image/png" to "PNG", "image/jpeg" to "JPEG", "image/webp" to "WebP", "image/gif" to "GIF", "application/pdf" to "PDF",
    "text/plain" to "text", "text/markdown" to "Markdown", "text/csv" to "CSV", "application/json" to "JSON",
)
