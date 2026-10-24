package me.nathanfallet.teamcode.companion.presentation.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.regular.ArrowSquareOut
import com.adamglin.phosphoricons.regular.ArrowsClockwise
import com.adamglin.phosphoricons.regular.GithubLogo
import me.nathanfallet.teamcode.companion.domain.models.GitHubLinkState
import me.nathanfallet.teamcode.companion.presentation.components.Banner
import me.nathanfallet.teamcode.companion.presentation.components.BusyRow
import me.nathanfallet.teamcode.companion.presentation.components.ButtonKind
import me.nathanfallet.teamcode.companion.presentation.components.Chip
import me.nathanfallet.teamcode.companion.presentation.components.ContentWidth
import me.nathanfallet.teamcode.companion.presentation.components.Group
import me.nathanfallet.teamcode.companion.presentation.components.GroupRow
import me.nathanfallet.teamcode.companion.presentation.components.NavBar
import me.nathanfallet.teamcode.companion.presentation.components.TcButton
import me.nathanfallet.teamcode.companion.presentation.components.TcCard
import me.nathanfallet.teamcode.companion.presentation.components.TcIconButton
import me.nathanfallet.teamcode.companion.presentation.components.Tone
import me.nathanfallet.teamcode.companion.presentation.theme.MonospaceStyle
import me.nathanfallet.teamcode.companion.presentation.theme.TeamCode
import me.nathanfallet.teamcode.companion.presentation.viewmodels.GitHubDeviceStep
import me.nathanfallet.teamcode.companion.presentation.viewmodels.GitHubLinkEnd
import me.nathanfallet.teamcode.companion.presentation.viewmodels.GitHubLinkUiState
import me.nathanfallet.teamcode.companion.presentation.viewmodels.GitHubLinkViewModel
import me.nathanfallet.teamcode.companion.resources.Res
import me.nathanfallet.teamcode.companion.resources.account_title
import me.nathanfallet.teamcode.companion.resources.action_cancel
import me.nathanfallet.teamcode.companion.resources.github_app_not_configured
import me.nathanfallet.teamcode.companion.resources.github_code_body
import me.nathanfallet.teamcode.companion.resources.github_code_title
import me.nathanfallet.teamcode.companion.resources.github_copied
import me.nathanfallet.teamcode.companion.resources.github_copy_code
import me.nathanfallet.teamcode.companion.resources.github_ended_denied
import me.nathanfallet.teamcode.companion.resources.github_ended_expired
import me.nathanfallet.teamcode.companion.resources.github_just_linked
import me.nathanfallet.teamcode.companion.resources.github_link
import me.nathanfallet.teamcode.companion.resources.github_link_again
import me.nathanfallet.teamcode.companion.resources.github_linked_as
import me.nathanfallet.teamcode.companion.resources.github_not_linked
import me.nathanfallet.teamcode.companion.resources.github_not_offered
import me.nathanfallet.teamcode.companion.resources.github_open_again
import me.nathanfallet.teamcode.companion.resources.github_reconnect_required
import me.nathanfallet.teamcode.companion.resources.github_refresh
import me.nathanfallet.teamcode.companion.resources.github_title
import me.nathanfallet.teamcode.companion.resources.github_unlink
import me.nathanfallet.teamcode.companion.resources.github_unlink_hint
import me.nathanfallet.teamcode.companion.resources.github_waiting
import org.jetbrains.compose.resources.stringResource

/**
 * The member's GitHub link: which GitHub account is linked, and linking one as `teamcode github link` does. The device
 * flow's code is copied and GitHub's page opened in the system browser once; the member pastes the code there.
 */
@Composable
fun GitHubLinkScreen(viewModel: GitHubLinkViewModel, onBack: (() -> Unit)?, backLabel: String = stringResource(Res.string.account_title)) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val colors = TeamCode.colors
    Column(Modifier.fillMaxSize().background(colors.background), horizontalAlignment = Alignment.CenterHorizontally) {
        NavBar(backLabel, onBack, stringResource(Res.string.github_title)) {
            TcIconButton(PhosphorIcons.Regular.ArrowsClockwise, stringResource(Res.string.github_refresh), onClick = viewModel::refresh, enabled = !state.loading && state.step == null,
                modifier = Modifier.testTag("github-refresh"))
        }
        Column(
            Modifier.widthIn(max = ContentWidth).fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            state.error?.let { Banner(it, Tone.DANGER, Modifier.testTag("github-error")) }
            state.justLinked?.let { Banner(stringResource(Res.string.github_just_linked, it), Tone.NEUTRAL, Modifier.testTag("github-linked"), icon = null) }
            state.ended?.let {
                Banner(stringResource(if (it == GitHubLinkEnd.EXPIRED) Res.string.github_ended_expired else Res.string.github_ended_denied), Tone.WARN, Modifier.testTag("github-ended"))
            }
            when (val step = state.step) {
                null -> Status(state, viewModel)
                else -> DeviceCode(step, viewModel)
            }
            BusyRow(state.loading || state.starting || state.unlinking)
        }
    }
}

@Composable
private fun Status(state: GitHubLinkUiState, viewModel: GitHubLinkViewModel) {
    val colors = TeamCode.colors
    val link = state.link
    if (!state.offered) {
        if (!state.loading) Banner(stringResource(Res.string.github_not_offered), Tone.WARN, Modifier.testTag("github-not-offered"))
        return
    }
    if (link == null) return
    Group(Modifier.testTag("github-status")) {
        GroupRow {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                when (link.state) {
                    GitHubLinkState.LINKED -> Text(stringResource(Res.string.github_linked_as, link.login.orEmpty()), color = colors.text, fontWeight = FontWeight.SemiBold, fontSize = 15.sp,
                        modifier = Modifier.testTag("github-login"))
                    GitHubLinkState.RECONNECT_REQUIRED -> Text(stringResource(Res.string.github_reconnect_required, link.login.orEmpty()), color = colors.text, fontSize = 14.sp,
                        modifier = Modifier.testTag("github-reconnect"))
                    GitHubLinkState.NOT_LINKED -> Text(stringResource(if (link.appConfigured) Res.string.github_not_linked else Res.string.github_app_not_configured), color = colors.muted, fontSize = 14.sp,
                        modifier = Modifier.testTag("github-not-linked"))
                }
            }
            if (link.state == GitHubLinkState.LINKED) Chip("GitHub", tone = Tone.ACCENT)
        }
    }
    if (state.canLink) TcButton(stringResource(if (link.state == GitHubLinkState.NOT_LINKED) Res.string.github_link else Res.string.github_link_again), onClick = viewModel::startLinking,
        icon = PhosphorIcons.Regular.GithubLogo, modifier = Modifier.fillMaxWidth().testTag("github-start"))
    if (link.state != GitHubLinkState.NOT_LINKED) {
        TcButton(stringResource(Res.string.github_unlink), onClick = viewModel::unlinkAccount, enabled = state.canUnlink, kind = ButtonKind.DANGER, modifier = Modifier.fillMaxWidth().testTag("github-unlink"))
        Text(stringResource(Res.string.github_unlink_hint), color = colors.muted, fontSize = 12.5.sp)
    }
}

@Composable
@Suppress("DEPRECATION") // LocalClipboardManager: plain text is all the code needs, on every platform.
private fun DeviceCode(step: GitHubDeviceStep, viewModel: GitHubLinkViewModel) {
    val colors = TeamCode.colors
    val browser = LocalUriHandler.current
    val clipboard = LocalClipboardManager.current
    var copied by remember(step.code) { mutableStateOf(false) }
    // Once per attempt, as the subscription sign-in opens its page: the code is copied, then GitHub's page opens.
    LaunchedEffect(step.code) {
        if (!step.opened) {
            viewModel.browserOpened()
            clipboard.setText(AnnotatedString(step.code))
            copied = true
            runCatching { browser.openUri(step.verificationUri) }
        }
    }
    TcCard(Modifier.testTag("github-device")) {
        Text(stringResource(Res.string.github_code_title), color = colors.text, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
        Text(step.code, style = MonospaceStyle, color = colors.text, fontSize = 28.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.testTag("github-code"))
        Text(stringResource(Res.string.github_code_body), color = colors.muted, fontSize = 13.5.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TcButton(stringResource(if (copied) Res.string.github_copied else Res.string.github_copy_code), onClick = { clipboard.setText(AnnotatedString(step.code)); copied = true },
                kind = ButtonKind.SECONDARY, small = true, modifier = Modifier.testTag("github-copy"))
            TcButton(stringResource(Res.string.github_open_again), onClick = { runCatching { browser.openUri(step.verificationUri) } }, kind = ButtonKind.SECONDARY, small = true,
                icon = PhosphorIcons.Regular.ArrowSquareOut, modifier = Modifier.testTag("github-open"))
        }
        Text(stringResource(Res.string.github_waiting), color = colors.muted, fontSize = 13.sp, modifier = Modifier.testTag("github-waiting"))
        TcButton(stringResource(Res.string.action_cancel), onClick = viewModel::cancelLinking, kind = ButtonKind.SECONDARY, modifier = Modifier.fillMaxWidth().testTag("github-cancel"))
    }
}
