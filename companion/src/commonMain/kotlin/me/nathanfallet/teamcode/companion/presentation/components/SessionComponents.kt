package me.nathanfallet.teamcode.companion.presentation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.unit.dp
import me.nathanfallet.teamcode.companion.domain.models.SessionState
import me.nathanfallet.teamcode.companion.presentation.theme.MonospaceStyle
import me.nathanfallet.teamcode.companion.resources.Res
import me.nathanfallet.teamcode.companion.resources.action_cancel
import me.nathanfallet.teamcode.companion.resources.link_open_confirm
import me.nathanfallet.teamcode.companion.resources.link_open_refused
import me.nathanfallet.teamcode.companion.resources.link_open_title
import me.nathanfallet.teamcode.companion.resources.state_active
import me.nathanfallet.teamcode.companion.resources.state_creating
import me.nathanfallet.teamcode.companion.resources.state_failed
import me.nathanfallet.teamcode.companion.resources.state_paused
import me.nathanfallet.teamcode.companion.resources.state_pausing
import me.nathanfallet.teamcode.companion.resources.state_preparing
import me.nathanfallet.teamcode.companion.resources.state_ready
import me.nathanfallet.teamcode.companion.resources.state_resuming
import me.nathanfallet.teamcode.companion.resources.state_terminated
import me.nathanfallet.teamcode.companion.resources.state_transferring
import me.nathanfallet.teamcode.companion.resources.state_unknown
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

fun SessionState.label(): StringResource = when (this) {
    SessionState.CREATING -> Res.string.state_creating
    SessionState.PREPARING -> Res.string.state_preparing
    SessionState.READY -> Res.string.state_ready
    SessionState.ACTIVE -> Res.string.state_active
    SessionState.PAUSING -> Res.string.state_pausing
    SessionState.PAUSED -> Res.string.state_paused
    SessionState.RESUMING -> Res.string.state_resuming
    SessionState.TRANSFERRING -> Res.string.state_transferring
    SessionState.TERMINATED -> Res.string.state_terminated
    SessionState.FAILED -> Res.string.state_failed
    SessionState.UNKNOWN -> Res.string.state_unknown
}

@Composable
fun StateChip(state: SessionState, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val color = when (state) {
        SessionState.ACTIVE -> colors.primary
        SessionState.READY -> colors.secondary
        SessionState.FAILED -> colors.error
        SessionState.PAUSED, SessionState.PAUSING -> colors.tertiary
        SessionState.TERMINATED -> colors.outline
        else -> colors.tertiary
    }
    Box(modifier.background(color.copy(alpha = 0.16f), RoundedCornerShape(50)).padding(horizontal = 10.dp, vertical = 3.dp)) {
        Text(stringResource(state.label()), color = color, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
fun Dot(color: Color, modifier: Modifier = Modifier) {
    Box(modifier.size(9.dp).background(color, CircleShape))
}

/**
 * Links from agent text open only after the member saw the whole URL, and only `http` or `https` ones, in the
 * system browser: an agent's Markdown can name any target behind any label.
 */
@Composable
fun ConfirmingLinks(content: @Composable () -> Unit) {
    val system = LocalUriHandler.current
    var pending by remember { mutableStateOf<String?>(null) }
    val handler = remember(system) { object : UriHandler { override fun openUri(uri: String) { pending = uri } } }
    CompositionLocalProvider(LocalUriHandler provides handler) { content() }
    pending?.let { uri ->
        val allowed = isWebLink(uri)
        AlertDialog(
            onDismissRequest = { pending = null },
            title = { Text(stringResource(Res.string.link_open_title)) },
            text = { Text(if (allowed) uri else stringResource(Res.string.link_open_refused), style = MonospaceStyle) },
            confirmButton = {
                if (allowed) TextButton(onClick = { pending = null; system.openUri(uri) }) { Text(stringResource(Res.string.link_open_confirm)) }
            },
            dismissButton = { TextButton(onClick = { pending = null }) { Text(stringResource(Res.string.action_cancel)) } },
        )
    }
}

/**
 * An absolute `http` or `https` URL with a host, without control characters, nor format characters (bidirectional
 * overrides, zero-width marks) that would make the URL shown for confirmation read differently from the one opened.
 */
fun isWebLink(uri: String): Boolean {
    if (uri.any { it.isISOControl() || it.isWhitespace() || it.category == CharCategory.FORMAT }) return false
    val scheme = uri.substringBefore("://", "").lowercase()
    if (scheme != "http" && scheme != "https") return false
    val host = uri.substringAfter("://").substringBefore('/').substringBefore('?').substringBefore('#').substringAfterLast('@')
    return host.isNotEmpty()
}
