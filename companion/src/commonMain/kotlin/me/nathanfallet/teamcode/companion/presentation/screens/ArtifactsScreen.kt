package me.nathanfallet.teamcode.companion.presentation.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.regular.FileText
import com.adamglin.phosphoricons.regular.Files
import com.adamglin.phosphoricons.regular.Link
import com.adamglin.phosphoricons.regular.MagnifyingGlass
import me.nathanfallet.teamcode.companion.domain.models.Artifact
import me.nathanfallet.teamcode.companion.domain.models.ArtifactScope
import me.nathanfallet.teamcode.companion.presentation.components.Banner
import me.nathanfallet.teamcode.companion.presentation.components.BusyRow
import me.nathanfallet.teamcode.companion.presentation.components.ButtonKind
import me.nathanfallet.teamcode.companion.presentation.components.Chip
import me.nathanfallet.teamcode.companion.presentation.components.ContentWidth
import me.nathanfallet.teamcode.companion.presentation.components.EmptyState
import me.nathanfallet.teamcode.companion.presentation.components.IconButtonKind
import me.nathanfallet.teamcode.companion.presentation.components.IconTile
import me.nathanfallet.teamcode.companion.presentation.components.LargeTitle
import me.nathanfallet.teamcode.companion.presentation.components.NavBar
import me.nathanfallet.teamcode.companion.presentation.components.Segmented
import me.nathanfallet.teamcode.companion.presentation.components.SheetHeader
import me.nathanfallet.teamcode.companion.presentation.components.TcButton
import me.nathanfallet.teamcode.companion.presentation.components.TcCard
import me.nathanfallet.teamcode.companion.presentation.components.TcIconButton
import me.nathanfallet.teamcode.companion.presentation.components.TcSheet
import me.nathanfallet.teamcode.companion.presentation.components.TcTextField
import me.nathanfallet.teamcode.companion.presentation.components.Tone
import me.nathanfallet.teamcode.companion.presentation.theme.MetaStyle
import me.nathanfallet.teamcode.companion.presentation.theme.TeamCode
import me.nathanfallet.teamcode.companion.presentation.viewmodels.ArtifactsViewModel
import me.nathanfallet.teamcode.companion.resources.Res
import me.nathanfallet.teamcode.companion.resources.action_back
import me.nathanfallet.teamcode.companion.resources.action_cancel
import me.nathanfallet.teamcode.companion.resources.action_retry
import me.nathanfallet.teamcode.companion.resources.artifacts_card_details
import me.nathanfallet.teamcode.companion.resources.artifacts_empty
import me.nathanfallet.teamcode.companion.resources.artifacts_empty_body
import me.nathanfallet.teamcode.companion.resources.artifacts_link_hint
import me.nathanfallet.teamcode.companion.resources.artifacts_link_label
import me.nathanfallet.teamcode.companion.resources.artifacts_link_submit
import me.nathanfallet.teamcode.companion.resources.artifacts_link_unknown
import me.nathanfallet.teamcode.companion.resources.artifacts_load_more
import me.nathanfallet.teamcode.companion.resources.artifacts_mine
import me.nathanfallet.teamcode.companion.resources.artifacts_no_match
import me.nathanfallet.teamcode.companion.resources.artifacts_open_link
import me.nathanfallet.teamcode.companion.resources.artifacts_search
import me.nathanfallet.teamcode.companion.resources.artifacts_session_empty_body
import me.nathanfallet.teamcode.companion.resources.artifacts_session_title
import me.nathanfallet.teamcode.companion.resources.artifacts_team
import me.nathanfallet.teamcode.companion.resources.artifacts_title
import org.jetbrains.compose.resources.stringResource

/**
 * The gallery: the member's own artifacts or the team's, newest first, searched in the loaded titles. As the
 * Artifacts tab it has the "open a link" sheet; pushed from a session ([forSession]) it lists that session's. A pasted
 * link opens through [onOpenLink], which says false when it is not an artifact link.
 */
@Composable
fun ArtifactsScreen(
    viewModel: ArtifactsViewModel,
    forSession: Boolean,
    onBack: () -> Unit,
    onOpen: (Artifact) -> Unit,
    onOpenLink: (String) -> Boolean,
    backLabel: String = stringResource(Res.string.action_back),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val now = rememberNow()
    val colors = TeamCode.colors
    var linkSheet by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().background(colors.background), horizontalAlignment = Alignment.CenterHorizontally) {
        if (forSession) NavBar(backLabel, onBack, stringResource(Res.string.artifacts_session_title))
        Column(
            Modifier.widthIn(max = ContentWidth).fillMaxWidth().then(if (forSession) Modifier else Modifier.windowInsetsPadding(WindowInsets.statusBars))
                .padding(start = 16.dp, end = 16.dp, top = if (forSession) 6.dp else 8.dp, bottom = 10.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (!forSession) LargeTitle(stringResource(Res.string.artifacts_title)) {
                TcIconButton(PhosphorIcons.Regular.Link, stringResource(Res.string.artifacts_open_link), onClick = { linkSheet = true }, kind = IconButtonKind.OUTLINED,
                    modifier = Modifier.testTag("artifact-link-sheet"))
            }
            TcTextField(state.query, viewModel::onQuery, placeholder = stringResource(Res.string.artifacts_search), leading = PhosphorIcons.Regular.MagnifyingGlass, minHeight = 44.dp, tag = "artifacts-search")
            Segmented(
                listOf(ArtifactScope.MINE to stringResource(Res.string.artifacts_mine), ArtifactScope.TEAM to stringResource(Res.string.artifacts_team)),
                selected = state.scope, onSelect = viewModel::setScope, modifier = Modifier.fillMaxWidth(), tag = { "artifacts-${it.wire}" },
            )
            state.error?.let { error ->
                Banner(error, Tone.DANGER, Modifier.testTag("error"), action = { TcButton(stringResource(Res.string.action_retry), onClick = viewModel::reload, kind = ButtonKind.SECONDARY, small = true) })
            }
        }
        val listState = rememberLazyListState()
        val nearEnd by remember { derivedStateOf { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index?.let { it >= listState.layoutInfo.totalItemsCount - 3 } == true } }
        // Searching filters what is loaded; it never pulls every page.
        LaunchedEffect(nearEnd, state.nextBefore) { if (nearEnd && state.query.isBlank()) viewModel.loadMore() }
        val shown = state.shown
        LazyColumn(
            Modifier.widthIn(max = ContentWidth).fillMaxWidth().weight(1f).testTag("artifacts"),
            state = listState,
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(shown, key = { it.id }) { artifact -> ArtifactCard(artifact, now) { onOpen(artifact) } }
            item(key = "end") {
                BusyRow(state.loading || state.loadingMore)
                if (!state.loading && state.error == null && shown.isEmpty()) {
                    if (state.artifacts.isEmpty()) {
                        EmptyState(PhosphorIcons.Regular.Files, stringResource(Res.string.artifacts_empty),
                            stringResource(if (forSession) Res.string.artifacts_session_empty_body else Res.string.artifacts_empty_body), Modifier.testTag("artifacts-empty"))
                    } else {
                        Text(stringResource(Res.string.artifacts_no_match), color = colors.muted, modifier = Modifier.fillMaxWidth().padding(24.dp))
                    }
                }
                if (state.nextBefore != null && !state.loadingMore && !state.loading) {
                    TcButton(stringResource(Res.string.artifacts_load_more), onClick = viewModel::loadMore, kind = ButtonKind.GHOST, modifier = Modifier.testTag("artifacts-more"))
                }
            }
        }
    }
    if (linkSheet) LinkSheet(onOpenLink, onDismiss = { linkSheet = false })
}

/** A pasted link, opened only on request: the clipboard is never read by itself. */
@Composable
private fun LinkSheet(onOpenLink: (String) -> Boolean, onDismiss: () -> Unit) {
    var link by remember { mutableStateOf("") }
    var refused by remember { mutableStateOf(false) }
    val colors = TeamCode.colors
    TcSheet(onDismiss) {
        SheetHeader(stringResource(Res.string.artifacts_open_link), trailing = {
            TcButton(stringResource(Res.string.action_cancel), onClick = onDismiss, kind = ButtonKind.GHOST, small = true)
        })
        Column(Modifier.navigationBarsPadding().imePadding().padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            TcTextField(link, { link = it; refused = false }, label = stringResource(Res.string.artifacts_link_label), placeholder = "https://", mono = true, error = refused,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false), tag = "artifact-link")
            if (refused) Text(stringResource(Res.string.artifacts_link_unknown), color = colors.danger, fontSize = 13.sp, modifier = Modifier.testTag("error"))
            Text(stringResource(Res.string.artifacts_link_hint), color = colors.muted, fontSize = 12.5.sp, lineHeight = 17.sp)
            TcButton(stringResource(Res.string.artifacts_link_submit), onClick = {
                refused = !onOpenLink(link.trim())
                if (!refused) onDismiss()
            }, enabled = link.isNotBlank(), modifier = Modifier.padding(top = 8.dp).testTag("artifact-link-open"))
        }
    }
}

@Composable
private fun ArtifactCard(artifact: Artifact, now: Long, onClick: () -> Unit) {
    val colors = TeamCode.colors
    TcCard(Modifier.testTag("artifact-${artifact.id}"), onClick = onClick) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            IconTile(PhosphorIcons.Regular.FileText)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(artifact.title, color = colors.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (artifact.description.isNotBlank()) Text(artifact.description, color = colors.muted, fontSize = 13.5.sp, lineHeight = 19.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Row(Modifier.padding(top = 3.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Chip(artifact.access)
                    Text(stringResource(Res.string.artifacts_card_details, artifact.currentVersion, artifact.ownerName, relativeTime(artifact.updatedAt, now)),
                        style = MetaStyle, color = colors.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}
