package me.nathanfallet.teamcode.companion.presentation.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.regular.ArrowsClockwise
import com.adamglin.phosphoricons.regular.TerminalWindow
import me.nathanfallet.teamcode.companion.domain.models.McpReason
import me.nathanfallet.teamcode.companion.domain.models.McpServer
import me.nathanfallet.teamcode.companion.domain.models.McpStatus
import me.nathanfallet.teamcode.companion.domain.models.McpTransport
import me.nathanfallet.teamcode.companion.presentation.components.Banner
import me.nathanfallet.teamcode.companion.presentation.components.BusyRow
import me.nathanfallet.teamcode.companion.presentation.components.ButtonKind
import me.nathanfallet.teamcode.companion.presentation.components.Chip
import me.nathanfallet.teamcode.companion.presentation.components.ContentWidth
import me.nathanfallet.teamcode.companion.presentation.components.EmptyState
import me.nathanfallet.teamcode.companion.presentation.components.NavBar
import me.nathanfallet.teamcode.companion.presentation.components.TcButton
import me.nathanfallet.teamcode.companion.presentation.components.TcCard
import me.nathanfallet.teamcode.companion.presentation.components.TcIconButton
import me.nathanfallet.teamcode.companion.presentation.components.Tone
import me.nathanfallet.teamcode.companion.presentation.theme.MetaStyle
import me.nathanfallet.teamcode.companion.presentation.theme.TeamCode
import me.nathanfallet.teamcode.companion.presentation.viewmodels.McpServersViewModel
import me.nathanfallet.teamcode.companion.presentation.viewmodels.words
import me.nathanfallet.teamcode.companion.resources.Res
import me.nathanfallet.teamcode.companion.resources.account_title
import me.nathanfallet.teamcode.companion.resources.action_retry
import me.nathanfallet.teamcode.companion.resources.mcp_admin_note
import me.nathanfallet.teamcode.companion.resources.mcp_empty_body
import me.nathanfallet.teamcode.companion.resources.mcp_empty_title
import me.nathanfallet.teamcode.companion.resources.mcp_reason_backoff
import me.nathanfallet.teamcode.companion.resources.mcp_reason_capacity
import me.nathanfallet.teamcode.companion.resources.mcp_reason_invalid_spec
import me.nathanfallet.teamcode.companion.resources.mcp_reason_memory
import me.nathanfallet.teamcode.companion.resources.mcp_reason_start_failed
import me.nathanfallet.teamcode.companion.resources.mcp_reason_stopped
import me.nathanfallet.teamcode.companion.resources.mcp_refresh
import me.nathanfallet.teamcode.companion.resources.mcp_state_idle
import me.nathanfallet.teamcode.companion.resources.mcp_state_ready
import me.nathanfallet.teamcode.companion.resources.mcp_state_starting
import me.nathanfallet.teamcode.companion.resources.mcp_title
import me.nathanfallet.teamcode.companion.resources.mcp_type_http
import me.nathanfallet.teamcode.companion.resources.mcp_type_stdio
import me.nathanfallet.teamcode.companion.resources.mcp_unavailable
import org.jetbrains.compose.resources.stringResource

/**
 * The MCP servers assigned to the member, with their type and state. Read-only: administrators declare servers and
 * read their logs in the web dashboard.
 */
@Composable
fun McpServersScreen(viewModel: McpServersViewModel, onBack: (() -> Unit)?, backLabel: String = stringResource(Res.string.account_title)) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val colors = TeamCode.colors
    Column(Modifier.fillMaxSize().background(colors.background), horizontalAlignment = Alignment.CenterHorizontally) {
        NavBar(backLabel, onBack, stringResource(Res.string.mcp_title)) {
            TcIconButton(PhosphorIcons.Regular.ArrowsClockwise, stringResource(Res.string.mcp_refresh), onClick = viewModel::refresh, enabled = !state.loading, modifier = Modifier.testTag("mcp-refresh"))
        }
        LazyColumn(
            Modifier.widthIn(max = ContentWidth).fillMaxWidth().weight(1f).testTag("mcp-list"),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            state.error?.let { error ->
                item(key = "error") {
                    Banner(error.words(), Tone.DANGER, Modifier.testTag("mcp-error"), action = {
                        TcButton(stringResource(Res.string.action_retry), onClick = viewModel::refresh, kind = ButtonKind.SECONDARY, small = true, modifier = Modifier.testTag("mcp-retry"))
                    })
                }
            }
            items(state.servers.orEmpty(), key = { it.name }) { server -> McpServerCard(server) }
            item(key = "end") {
                BusyRow(state.loading)
                if (state.empty) {
                    EmptyState(PhosphorIcons.Regular.TerminalWindow, stringResource(Res.string.mcp_empty_title), stringResource(Res.string.mcp_empty_body), modifier = Modifier.testTag("mcp-empty"))
                }
            }
            if (state.servers != null) item(key = "note") {
                Text(stringResource(Res.string.mcp_admin_note), color = colors.muted, fontSize = 12.5.sp, lineHeight = 17.sp, modifier = Modifier.testTag("mcp-admin-note"))
            }
        }
    }
}

@Composable
private fun McpServerCard(server: McpServer) {
    val colors = TeamCode.colors
    TcCard(Modifier.testTag("mcp-${server.name}")) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(server.name, color = colors.text, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            when (server.transport) {
                McpTransport.STDIO -> Chip(stringResource(Res.string.mcp_type_stdio), tone = Tone.MUTED, modifier = Modifier.testTag("mcp-type-${server.name}"))
                McpTransport.HTTP -> Chip(stringResource(Res.string.mcp_type_http), tone = Tone.MUTED, modifier = Modifier.testTag("mcp-type-${server.name}"))
                McpTransport.UNKNOWN -> Unit
            }
        }
        Text(server.agentName, style = MetaStyle, color = colors.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        McpStatusLine(server)
    }
}

@Composable
private fun McpStatusLine(server: McpServer) {
    val colors = TeamCode.colors
    val tag = Modifier.testTag("mcp-state-${server.name}")
    when (val status = server.status) {
        McpStatus.Untracked -> Unit
        McpStatus.Ready -> Chip(stringResource(Res.string.mcp_state_ready), tone = Tone.ACCENT, dot = true, modifier = tag)
        McpStatus.Starting -> Chip(stringResource(Res.string.mcp_state_starting), tone = Tone.WARN, dot = true, modifier = tag)
        McpStatus.Idle -> Chip(stringResource(Res.string.mcp_state_idle), tone = Tone.MUTED, dot = true, modifier = tag)
        is McpStatus.Other -> Chip(status.state, tone = Tone.MUTED, modifier = tag)
        is McpStatus.Unavailable -> {
            val reason = status.reason?.let { stringResource(it.words()) } ?: status.code.orEmpty()
            Text(stringResource(Res.string.mcp_unavailable, reason), color = colors.danger, fontSize = 13.sp, lineHeight = 18.sp, modifier = tag)
        }
    }
}

private fun McpReason.words() = when (this) {
    McpReason.START_FAILED -> Res.string.mcp_reason_start_failed
    McpReason.STOPPED -> Res.string.mcp_reason_stopped
    McpReason.MEMORY -> Res.string.mcp_reason_memory
    McpReason.BACKOFF -> Res.string.mcp_reason_backoff
    McpReason.CAPACITY -> Res.string.mcp_reason_capacity
    McpReason.INVALID_SPEC -> Res.string.mcp_reason_invalid_spec
}
