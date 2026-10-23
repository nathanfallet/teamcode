package me.nathanfallet.teamcode.companion.presentation.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.regular.CaretLeft
import me.nathanfallet.teamcode.companion.domain.models.Artifact
import me.nathanfallet.teamcode.companion.domain.models.ArtifactScope
import me.nathanfallet.teamcode.companion.presentation.components.BusyRow
import me.nathanfallet.teamcode.companion.presentation.components.ErrorText
import me.nathanfallet.teamcode.companion.presentation.viewmodels.ArtifactsViewModel
import me.nathanfallet.teamcode.companion.resources.Res
import me.nathanfallet.teamcode.companion.resources.action_back
import me.nathanfallet.teamcode.companion.resources.action_retry
import me.nathanfallet.teamcode.companion.resources.artifacts_card_details
import me.nathanfallet.teamcode.companion.resources.artifacts_empty
import me.nathanfallet.teamcode.companion.resources.artifacts_link_unknown
import me.nathanfallet.teamcode.companion.resources.artifacts_load_more
import me.nathanfallet.teamcode.companion.resources.artifacts_mine
import me.nathanfallet.teamcode.companion.resources.artifacts_no_match
import me.nathanfallet.teamcode.companion.resources.artifacts_open_link
import me.nathanfallet.teamcode.companion.resources.artifacts_open_link_hint
import me.nathanfallet.teamcode.companion.resources.artifacts_search
import me.nathanfallet.teamcode.companion.resources.artifacts_session_title
import me.nathanfallet.teamcode.companion.resources.artifacts_team
import me.nathanfallet.teamcode.companion.resources.artifacts_title
import org.jetbrains.compose.resources.stringResource

/**
 * The gallery: the member's own artifacts or the team's, newest first, searched in the loaded titles; or one cloud
 * session's ([forSession]). A pasted link opens through [onOpenLink], which says false when it is not an artifact link.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArtifactsScreen(viewModel: ArtifactsViewModel, forSession: Boolean, onBack: () -> Unit, onOpen: (Artifact) -> Unit, onOpenLink: (String) -> Boolean) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val now = rememberNow()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(if (forSession) Res.string.artifacts_session_title else Res.string.artifacts_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(PhosphorIcons.Regular.CaretLeft, contentDescription = stringResource(Res.string.action_back), modifier = Modifier.size(22.dp)) }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding), horizontalAlignment = Alignment.CenterHorizontally) {
            Column(Modifier.widthIn(max = 720.dp).fillMaxWidth().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for ((value, label) in listOf(ArtifactScope.MINE to Res.string.artifacts_mine, ArtifactScope.TEAM to Res.string.artifacts_team)) {
                        FilterChip(selected = state.scope == value, onClick = { viewModel.setScope(value) }, label = { Text(stringResource(label)) }, modifier = Modifier.testTag("artifacts-${value.wire}"))
                    }
                }
                OutlinedTextField(state.query, viewModel::onQuery, label = { Text(stringResource(Res.string.artifacts_search)) }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("artifacts-search"))
                if (!forSession) LinkField(onOpenLink)
                ErrorText(state.error)
                if (state.error != null) TextButton(onClick = viewModel::reload) { Text(stringResource(Res.string.action_retry)) }
            }
            val listState = rememberLazyListState()
            val nearEnd by remember { derivedStateOf { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index?.let { it >= listState.layoutInfo.totalItemsCount - 3 } == true } }
            // Searching filters what is loaded; it never pulls every page.
            LaunchedEffect(nearEnd, state.nextBefore) { if (nearEnd && state.query.isBlank()) viewModel.loadMore() }
            val shown = state.shown
            LazyColumn(Modifier.widthIn(max = 720.dp).fillMaxWidth().weight(1f).testTag("artifacts"), state = listState) {
                items(shown, key = { it.id }) { artifact ->
                    ArtifactRow(artifact, now) { onOpen(artifact) }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
                item {
                    BusyRow(state.loading || state.loadingMore)
                    if (!state.loading && state.error == null && shown.isEmpty()) {
                        Text(stringResource(if (state.artifacts.isEmpty()) Res.string.artifacts_empty else Res.string.artifacts_no_match),
                            modifier = Modifier.fillMaxWidth().padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (state.nextBefore != null && !state.loadingMore && !state.loading) {
                        TextButton(onClick = viewModel::loadMore, modifier = Modifier.fillMaxWidth().testTag("artifacts-more")) { Text(stringResource(Res.string.artifacts_load_more)) }
                    }
                }
            }
        }
    }
}

/** A pasted link, opened only on request: the clipboard is never read by itself. */
@Composable
private fun LinkField(onOpenLink: (String) -> Boolean) {
    var link by remember { mutableStateOf("") }
    var refused by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(link, { link = it; refused = false }, label = { Text(stringResource(Res.string.artifacts_open_link_hint)) }, singleLine = true,
            modifier = Modifier.weight(1f).testTag("artifact-link"))
        TextButton(onClick = { refused = !onOpenLink(link); if (!refused) link = "" }, enabled = link.isNotBlank(), modifier = Modifier.testTag("artifact-link-open")) {
            Text(stringResource(Res.string.artifacts_open_link))
        }
    }
    if (refused) ErrorText(stringResource(Res.string.artifacts_link_unknown))
}

@Composable
private fun ArtifactRow(artifact: Artifact, now: Long, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp).testTag("artifact-${artifact.id}"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(artifact.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (artifact.description.isNotBlank()) {
            Text(artifact.description, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Text(stringResource(Res.string.artifacts_card_details, artifact.ownerName, artifact.access, artifact.currentVersion) + " · " + relativeTime(artifact.updatedAt, now),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
