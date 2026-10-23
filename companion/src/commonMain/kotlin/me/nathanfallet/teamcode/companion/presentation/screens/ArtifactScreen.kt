package me.nathanfallet.teamcode.companion.presentation.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.regular.Browser
import com.adamglin.phosphoricons.regular.CaretLeft
import com.adamglin.phosphoricons.regular.Copy
import me.nathanfallet.teamcode.companion.presentation.components.ArtifactContentView
import me.nathanfallet.teamcode.companion.presentation.components.BusyRow
import me.nathanfallet.teamcode.companion.presentation.components.ConfirmingLinks
import me.nathanfallet.teamcode.companion.presentation.components.ErrorText
import me.nathanfallet.teamcode.companion.presentation.components.embedsArtifacts
import me.nathanfallet.teamcode.companion.presentation.viewmodels.ArtifactViewModel
import me.nathanfallet.teamcode.companion.resources.Res
import me.nathanfallet.teamcode.companion.resources.action_back
import me.nathanfallet.teamcode.companion.resources.action_retry
import me.nathanfallet.teamcode.companion.resources.artifact_copied
import me.nathanfallet.teamcode.companion.resources.artifact_copy_link
import me.nathanfallet.teamcode.companion.resources.artifact_desktop
import me.nathanfallet.teamcode.companion.resources.artifact_missing_page
import me.nathanfallet.teamcode.companion.resources.artifact_restart
import me.nathanfallet.teamcode.companion.resources.artifact_newer
import me.nathanfallet.teamcode.companion.resources.artifact_open_browser
import me.nathanfallet.teamcode.companion.resources.artifact_show_newer
import me.nathanfallet.teamcode.companion.resources.artifact_version_item
import me.nathanfallet.teamcode.companion.resources.artifact_versions
import me.nathanfallet.teamcode.companion.resources.artifacts_title
import org.jetbrains.compose.resources.stringResource

/**
 * One artifact, full screen under a native bar: title, version picker, copy link and open in the browser (the viewer
 * page, where access is managed). On iOS and Android the content shows in a sandboxed web view; the desktop opens the
 * viewer page in the browser. A link inside the content opens outside only after its URL is shown.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Suppress("DEPRECATION") // LocalClipboardManager: plain text is all the link needs, on every platform.
@Composable
fun ArtifactScreen(viewModel: ArtifactViewModel, onBack: () -> Unit) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LifecycleStartEffect(viewModel) {
        viewModel.start()
        onStopOrDispose { viewModel.stop() }
    }
    val clipboard = LocalClipboardManager.current
    val browser = LocalUriHandler.current
    var copied by remember { mutableStateOf(false) }
    val now = rememberNow()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(state.artifact?.title ?: stringResource(Res.string.artifacts_title), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(PhosphorIcons.Regular.CaretLeft, contentDescription = stringResource(Res.string.action_back), modifier = Modifier.size(22.dp)) }
                },
                actions = {
                    state.artifact?.takeIf { state.versionUrl != null }?.let { artifact ->
                        IconButton(onClick = { clipboard.setText(AnnotatedString(state.versionUrl!!)); copied = true }, modifier = Modifier.testTag("artifact-copy")) {
                            Icon(PhosphorIcons.Regular.Copy, contentDescription = stringResource(Res.string.artifact_copy_link), modifier = Modifier.size(22.dp))
                        }
                        IconButton(onClick = { browser.openUri(state.versionUrl!!) }, modifier = Modifier.testTag("artifact-browser")) {
                            Icon(PhosphorIcons.Regular.Browser, contentDescription = stringResource(Res.string.artifact_open_browser), modifier = Modifier.size(22.dp))
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding), horizontalAlignment = Alignment.CenterHorizontally) {
            Column(Modifier.widthIn(max = 820.dp).fillMaxWidth().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    VersionPicker(state.version, state.versions.map { it.number to relativeTime(it.createdAt, now) }, viewModel::selectVersion)
                    if (copied) Text(stringResource(Res.string.artifact_copied), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                state.newerVersion?.let { newer ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(Res.string.artifact_newer, newer), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
                        TextButton(onClick = viewModel::showNewer, modifier = Modifier.testTag("artifact-newer")) { Text(stringResource(Res.string.artifact_show_newer)) }
                    }
                }
                if (state.missingPage) Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(Res.string.artifact_missing_page), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary, modifier = Modifier.weight(1f))
                    TextButton(onClick = viewModel::restart, modifier = Modifier.testTag("artifact-restart")) { Text(stringResource(Res.string.artifact_restart)) }
                }
                ErrorText(state.error)
                if (state.error != null && !state.gone) TextButton(onClick = viewModel::load) { Text(stringResource(Res.string.action_retry)) }
                BusyRow(state.loading)
            }
            val opened = state.opened
            when {
                !embedsArtifacts -> state.artifact?.let { artifact ->
                    Column(Modifier.widthIn(max = 820.dp).fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(stringResource(Res.string.artifact_desktop), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (state.versionUrl != null) Button(onClick = { browser.openUri(state.versionUrl!!) }, modifier = Modifier.testTag("artifact-open-desktop")) { Text(stringResource(Res.string.artifact_open_browser)) }
                    }
                }
                opened != null -> ConfirmingLinks {
                    val confirm = LocalUriHandler.current
                    ArtifactContentView(state.page ?: opened.ticket.contentUrl, opened.prefix, state.reload, onExternal = confirm::openUri, onMissing = viewModel::contentMissing, onCrashed = viewModel::contentCrashed,
                        modifier = Modifier.fillMaxWidth().weight(1f).testTag("artifact-content"))
                }
            }
        }
    }
}

@Composable
private fun VersionPicker(version: Int?, versions: List<Pair<Int, String>>, onSelect: (Int) -> Unit) {
    if (version == null) return
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { open = true }, enabled = versions.size > 1, modifier = Modifier.testTag("artifact-versions")) { Text(stringResource(Res.string.artifact_versions, version)) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            for ((number, age) in versions) {
                DropdownMenuItem(text = { Text(stringResource(Res.string.artifact_version_item, number, age)) }, onClick = { open = false; onSelect(number) },
                    modifier = Modifier.testTag("artifact-version-$number"))
            }
        }
    }
}
