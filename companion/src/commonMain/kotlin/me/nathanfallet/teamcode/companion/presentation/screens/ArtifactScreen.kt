package me.nathanfallet.teamcode.companion.presentation.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.regular.ArrowSquareOut
import com.adamglin.phosphoricons.regular.CaretDown
import com.adamglin.phosphoricons.regular.Check
import com.adamglin.phosphoricons.regular.Copy
import com.adamglin.phosphoricons.regular.FileText
import me.nathanfallet.teamcode.companion.presentation.components.Banner
import me.nathanfallet.teamcode.companion.presentation.components.BusyRow
import me.nathanfallet.teamcode.companion.presentation.components.ButtonKind
import me.nathanfallet.teamcode.companion.presentation.components.Chip
import me.nathanfallet.teamcode.companion.presentation.components.ConfirmingLinks
import me.nathanfallet.teamcode.companion.presentation.components.ContentWidth
import me.nathanfallet.teamcode.companion.presentation.components.EmptyState
import me.nathanfallet.teamcode.companion.presentation.components.Group
import me.nathanfallet.teamcode.companion.presentation.components.GroupDivider
import me.nathanfallet.teamcode.companion.presentation.components.NavBar
import me.nathanfallet.teamcode.companion.presentation.components.SheetHeader
import me.nathanfallet.teamcode.companion.presentation.components.TcButton
import me.nathanfallet.teamcode.companion.presentation.components.TcIconButton
import me.nathanfallet.teamcode.companion.presentation.components.TcSheet
import me.nathanfallet.teamcode.companion.presentation.components.Tone
import me.nathanfallet.teamcode.companion.presentation.components.ArtifactContentView
import me.nathanfallet.teamcode.companion.presentation.components.embedsArtifacts
import me.nathanfallet.teamcode.companion.presentation.theme.MetaStyle
import me.nathanfallet.teamcode.companion.presentation.theme.TeamCode
import me.nathanfallet.teamcode.companion.presentation.viewmodels.ArtifactViewModel
import me.nathanfallet.teamcode.companion.resources.Res
import me.nathanfallet.teamcode.companion.resources.action_done
import me.nathanfallet.teamcode.companion.resources.action_retry
import me.nathanfallet.teamcode.companion.resources.artifact_choose_version
import me.nathanfallet.teamcode.companion.resources.artifact_content
import me.nathanfallet.teamcode.companion.resources.artifact_copied
import me.nathanfallet.teamcode.companion.resources.artifact_copy_link
import me.nathanfallet.teamcode.companion.resources.artifact_desktop
import me.nathanfallet.teamcode.companion.resources.artifact_missing_page
import me.nathanfallet.teamcode.companion.resources.artifact_newer
import me.nathanfallet.teamcode.companion.resources.artifact_open_browser
import me.nathanfallet.teamcode.companion.resources.artifact_restart
import me.nathanfallet.teamcode.companion.resources.artifact_show_newer
import me.nathanfallet.teamcode.companion.resources.artifact_versions
import me.nathanfallet.teamcode.companion.resources.artifact_versions_title
import me.nathanfallet.teamcode.companion.resources.artifacts_title
import org.jetbrains.compose.resources.stringResource

/**
 * One artifact under a native bar: title, copy link and open in the browser (the viewer page, where access is
 * managed), then the version shown, its access and owner. On iOS and Android the content shows in a sandboxed web
 * view; the desktop opens the viewer page in the browser. A link inside the content opens outside only after its URL
 * is shown.
 */
@Suppress("DEPRECATION") // LocalClipboardManager: plain text is all the link needs, on every platform.
@Composable
fun ArtifactScreen(viewModel: ArtifactViewModel, onBack: () -> Unit, backLabel: String = stringResource(Res.string.artifacts_title)) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LifecycleStartEffect(viewModel) {
        viewModel.start()
        onStopOrDispose { viewModel.stop() }
    }
    val clipboard = LocalClipboardManager.current
    val browser = LocalUriHandler.current
    var copied by remember { mutableStateOf(false) }
    var versionsOpen by remember { mutableStateOf(false) }
    val now = rememberNow()
    val colors = TeamCode.colors
    Column(Modifier.fillMaxSize().background(colors.background)) {
        NavBar(backLabel, onBack, state.artifact?.title.orEmpty()) {
            val url = state.versionUrl
            if (state.artifact != null && url != null) {
                TcIconButton(if (copied) PhosphorIcons.Regular.Check else PhosphorIcons.Regular.Copy, stringResource(Res.string.artifact_copy_link),
                    onClick = { clipboard.setText(AnnotatedString(url)); copied = true }, modifier = Modifier.testTag("artifact-copy"), tint = if (copied) colors.accentText else null)
                TcIconButton(PhosphorIcons.Regular.ArrowSquareOut, stringResource(Res.string.artifact_open_browser), onClick = { browser.openUri(url) }, modifier = Modifier.testTag("artifact-browser"))
            }
        }
        state.artifact?.let { artifact ->
            Column(Modifier.fillMaxWidth().background(colors.line).padding(bottom = 1.dp)) {
                Row(
                    Modifier.fillMaxWidth().background(colors.background).padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    state.version?.let { version ->
                        val age = state.versions.firstOrNull { it.number == version }?.let { relativeTime(it.createdAt, now) }
                        VersionButton(version, age, enabled = state.versions.size > 1) { versionsOpen = true }
                    }
                    Spacer(Modifier.weight(1f))
                    if (copied) Text(stringResource(Res.string.artifact_copied), color = colors.muted, fontSize = 12.sp)
                    else {
                        Chip(artifact.access)
                        Text(artifact.ownerName, style = MetaStyle, color = colors.muted, maxLines = 1)
                    }
                }
            }
        }
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (state.newerVersion != null || state.missingPage || state.error != null || state.loading) Spacer(Modifier.height(2.dp))
            state.newerVersion?.let { newer ->
                Banner(stringResource(Res.string.artifact_newer, newer), Tone.ACCENT, icon = null, action = {
                    TcButton(stringResource(Res.string.artifact_show_newer), onClick = viewModel::showNewer, small = true, modifier = Modifier.testTag("artifact-newer"))
                })
            }
            if (state.missingPage) Banner(stringResource(Res.string.artifact_missing_page), Tone.WARN, action = {
                TcButton(stringResource(Res.string.artifact_restart), onClick = viewModel::restart, kind = ButtonKind.SECONDARY, small = true, modifier = Modifier.testTag("artifact-restart"))
            })
            state.error?.let { error ->
                Banner(error, Tone.DANGER, Modifier.testTag("error"), action = if (!state.gone) {
                    { TcButton(stringResource(Res.string.action_retry), onClick = viewModel::load, kind = ButtonKind.SECONDARY, small = true) }
                } else null)
            }
            BusyRow(state.loading)
        }
        val opened = state.opened
        val description = stringResource(Res.string.artifact_content)
        when {
            !embedsArtifacts -> state.artifact?.let {
                Box(Modifier.fillMaxWidth().weight(1f).navigationBarsPadding(), contentAlignment = Alignment.TopCenter) {
                    Column(Modifier.widthIn(max = ContentWidth).fillMaxWidth().padding(16.dp)) {
                        EmptyState(PhosphorIcons.Regular.FileText, it.title, stringResource(Res.string.artifact_desktop), dashed = false) {
                            val url = state.versionUrl
                            if (url != null) TcButton(stringResource(Res.string.artifact_open_browser), onClick = { browser.openUri(url) }, icon = PhosphorIcons.Regular.ArrowSquareOut,
                                modifier = Modifier.testTag("artifact-open-desktop"))
                        }
                    }
                }
            }
            opened != null -> ConfirmingLinks {
                val confirm = LocalUriHandler.current
                Box(Modifier.fillMaxWidth().weight(1f).padding(top = 10.dp).background(colors.line).padding(top = 1.dp)) {
                    ArtifactContentView(state.page ?: opened.ticket.contentUrl, opened.prefix, state.reload, onExternal = confirm::openUri, onMissing = viewModel::contentMissing,
                        onCrashed = viewModel::contentCrashed, modifier = Modifier.fillMaxSize().semantics { contentDescription = description }.testTag("artifact-content"))
                }
            }
        }
    }
    if (versionsOpen) {
        val version = state.version
        TcSheet(onDismiss = { versionsOpen = false }) {
            SheetHeader(stringResource(Res.string.artifact_versions_title), trailing = {
                TcButton(stringResource(Res.string.action_done), onClick = { versionsOpen = false }, kind = ButtonKind.GHOST, small = true)
            })
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).navigationBarsPadding().padding(start = 16.dp, end = 16.dp, bottom = 24.dp)) {
                Group(Modifier.selectableGroup()) {
                    state.versions.forEachIndexed { index, item ->
                        if (index > 0) GroupDivider()
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = 56.dp)
                                .selectable(selected = item.number == version, role = Role.RadioButton) { versionsOpen = false; viewModel.selectVersion(item.number) }
                                .padding(horizontal = 14.dp).testTag("artifact-version-${item.number}"),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Text(stringResource(Res.string.artifact_versions, item.number), color = colors.text, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                            Text(relativeTime(item.createdAt, now), style = MetaStyle, color = colors.muted)
                            Box(Modifier.size(20.dp)) {
                                if (item.number == version) Icon(PhosphorIcons.Regular.Check, contentDescription = null, tint = colors.accentText, modifier = Modifier.size(20.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

/** "Version 3 · 2 h ago ⌄": opens the versions sheet when there is more than one. */
@Composable
private fun VersionButton(version: Int, age: String?, enabled: Boolean, onClick: () -> Unit) {
    val colors = TeamCode.colors
    val description = stringResource(Res.string.artifact_choose_version)
    Row(
        Modifier.heightIn(min = 44.dp).clip(RoundedCornerShape(10.dp)).background(colors.panel).border(1.dp, colors.line, RoundedCornerShape(10.dp))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick).semantics { contentDescription = description }.padding(horizontal = 12.dp).testTag("artifact-versions"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(stringResource(Res.string.artifact_versions, version), color = colors.text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        if (age != null) Text(age, style = MetaStyle, color = colors.muted)
        Icon(PhosphorIcons.Regular.CaretDown, contentDescription = null, tint = colors.muted, modifier = Modifier.size(14.dp).alpha(if (enabled) 1f else 0.4f))
    }
}
