package me.nathanfallet.teamcode.companion.presentation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import me.nathanfallet.teamcode.companion.domain.models.CloudSession
import me.nathanfallet.teamcode.companion.domain.models.SessionHost
import me.nathanfallet.teamcode.companion.domain.models.SessionState
import me.nathanfallet.teamcode.companion.presentation.theme.MonospaceStyle
import me.nathanfallet.teamcode.companion.presentation.theme.Radii
import me.nathanfallet.teamcode.companion.presentation.theme.TeamCode
import me.nathanfallet.teamcode.companion.resources.session_waiting_host_chip
import me.nathanfallet.teamcode.companion.resources.session_host_refused_chip
import me.nathanfallet.teamcode.companion.resources.session_refusal_folder_not_configured
import me.nathanfallet.teamcode.companion.resources.session_refusal_folder_missing
import me.nathanfallet.teamcode.companion.resources.session_refusal_folder_moved
import me.nathanfallet.teamcode.companion.resources.session_refusal_folder_refused
import me.nathanfallet.teamcode.companion.resources.session_refusal_folder_mismatch
import me.nathanfallet.teamcode.companion.resources.session_refusal_agent_unsupported
import me.nathanfallet.teamcode.companion.resources.session_refusal_host_failed
import me.nathanfallet.teamcode.companion.resources.session_refusal_none
import me.nathanfallet.teamcode.companion.resources.session_refusal_unknown
import me.nathanfallet.teamcode.companion.resources.session_host_refused
import me.nathanfallet.teamcode.companion.resources.session_phase_waiting_host
import me.nathanfallet.teamcode.companion.resources.Res
import me.nathanfallet.teamcode.companion.resources.action_cancel
import me.nathanfallet.teamcode.companion.resources.link_open_confirm
import me.nathanfallet.teamcode.companion.resources.link_open_hint
import me.nathanfallet.teamcode.companion.resources.link_open_refused
import me.nathanfallet.teamcode.companion.resources.link_open_title
import me.nathanfallet.teamcode.companion.resources.session_host_offline_chip
import me.nathanfallet.teamcode.companion.resources.session_host_removed
import me.nathanfallet.teamcode.companion.resources.session_host_unnamed
import me.nathanfallet.teamcode.companion.resources.session_kind_cloud
import me.nathanfallet.teamcode.companion.resources.session_kind_remote
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

/** Working shows the accent dot; passing states are muted, paused amber, failed red. */
@Composable
fun StateChip(state: SessionState, modifier: Modifier = Modifier) {
    val tone = when (state) {
        SessionState.ACTIVE -> Tone.ACCENT
        SessionState.READY -> Tone.NEUTRAL
        SessionState.PAUSED -> Tone.WARN
        SessionState.FAILED -> Tone.DANGER
        else -> Tone.MUTED
    }
    Chip(stringResource(state.label()), modifier, tone = tone, dot = state == SessionState.ACTIVE)
}

/** A remote session's device as the member knows it: its name, or that it was removed or never named. */
@Composable
fun hostName(host: SessionHost?): String = when {
    host == null || host.removed -> stringResource(Res.string.session_host_removed)
    else -> host.deviceName?.takeIf { it.isNotBlank() } ?: stringResource(Res.string.session_host_unnamed)
}

/** The system a device reports, worded as its vendor does; null until the device reported it. */
fun systemOf(host: SessionHost): String? = systemOf(host.platform, host.osVersion)

fun systemOf(platform: String?, osVersion: String?): String? {
    val name = when (platform) {
        null -> return null
        "macos" -> "macOS"
        "windows" -> "Windows"
        "linux" -> "Linux"
        "ios" -> "iOS"
        "android" -> "Android"
        else -> platform
    }
    return listOfNotNull(name, osVersion?.takeIf { it.isNotBlank() }).joinToString(" ")
}

/** Where the session runs: "Cloud", or "Remote · <device>"; a device that stopped answering is marked offline next to it. */
@Composable
fun KindChips(session: CloudSession, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        val text = if (session.remote) stringResource(Res.string.session_kind_remote, hostName(session.host)) else stringResource(Res.string.session_kind_cloud)
        Chip(text, Modifier.testTag("where-${session.id}"), tone = Tone.MUTED)
        if (session.hostOffline) Chip(stringResource(Res.string.session_host_offline_chip), Modifier.testTag("offline-${session.id}"), tone = Tone.WARN)
        if (session.waitingForHost) Chip(stringResource(Res.string.session_waiting_host_chip), Modifier.testTag("waiting-host-${session.id}"), tone = Tone.ACCENT)
        if (session.hostRefused) Chip(stringResource(Res.string.session_host_refused_chip), Modifier.testTag("host-refused-${session.id}"), tone = Tone.DANGER)
    }
}

/** Why the device refused to start the session, worded for each reason its daemon gives. */
@Composable
fun refusalText(session: CloudSession): String {
    val reason = session.refusalReason
    val why = when (reason) {
        "folder_not_configured" -> stringResource(Res.string.session_refusal_folder_not_configured)
        "folder_missing" -> stringResource(Res.string.session_refusal_folder_missing)
        "folder_moved" -> stringResource(Res.string.session_refusal_folder_moved)
        "folder_refused" -> stringResource(Res.string.session_refusal_folder_refused)
        "folder_mismatch" -> stringResource(Res.string.session_refusal_folder_mismatch)
        "agent_unsupported" -> stringResource(Res.string.session_refusal_agent_unsupported)
        "host_failed" -> stringResource(Res.string.session_refusal_host_failed)
        null -> stringResource(Res.string.session_refusal_none)
        else -> stringResource(Res.string.session_refusal_unknown, reason)
    }
    return stringResource(Res.string.session_host_refused, hostName(session.host), why)
}

/** The server's progress detail, worded when the app knows it (`waiting_host`), as is otherwise. */
@Composable
fun phaseLabel(session: CloudSession): String? = when {
    session.phase == null -> null
    session.waitingForHost -> stringResource(Res.string.session_phase_waiting_host)
    else -> session.phase
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
        Dialog(onDismissRequest = { pending = null }) {
            val colors = TeamCode.colors
            Column(
                Modifier.widthIn(max = 420.dp).fillMaxWidth().clip(Radii.card).background(colors.panel).border(1.dp, colors.line, Radii.card).padding(18.dp).testTag("link-confirm"),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(stringResource(Res.string.link_open_title), color = colors.text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    if (allowed) uri else stringResource(Res.string.link_open_refused),
                    style = MonospaceStyle,
                    color = colors.text,
                    modifier = Modifier.fillMaxWidth().clip(Radii.control).background(colors.panelAlt).border(1.dp, colors.line, Radii.control).padding(10.dp),
                )
                if (allowed) Text(stringResource(Res.string.link_open_hint), color = colors.muted, fontSize = 12.5.sp, lineHeight = 17.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TcButton(stringResource(Res.string.action_cancel), onClick = { pending = null }, kind = ButtonKind.SECONDARY, modifier = Modifier.weight(1f))
                    if (allowed) TcButton(stringResource(Res.string.link_open_confirm), onClick = { pending = null; system.openUri(uri) }, modifier = Modifier.weight(1f).testTag("link-open"))
                }
            }
        }
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

/** The text shown for a server: its host, without the scheme. */
fun hostOf(origin: String): String = origin.substringAfter("://")
