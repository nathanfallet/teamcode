package me.nathanfallet.teamcode.companion.presentation.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.regular.ArrowsClockwise
import me.nathanfallet.teamcode.companion.domain.models.NotificationKind
import me.nathanfallet.teamcode.companion.domain.models.PushTestOutcome
import me.nathanfallet.teamcode.companion.presentation.components.Banner
import me.nathanfallet.teamcode.companion.presentation.components.BusyRow
import me.nathanfallet.teamcode.companion.presentation.components.ButtonKind
import me.nathanfallet.teamcode.companion.presentation.components.ContentWidth
import me.nathanfallet.teamcode.companion.presentation.components.Group
import me.nathanfallet.teamcode.companion.presentation.components.GroupDivider
import me.nathanfallet.teamcode.companion.presentation.components.GroupRow
import me.nathanfallet.teamcode.companion.presentation.components.NavBar
import me.nathanfallet.teamcode.companion.presentation.components.SectionTitle
import me.nathanfallet.teamcode.companion.presentation.components.TcButton
import me.nathanfallet.teamcode.companion.presentation.components.TcIconButton
import me.nathanfallet.teamcode.companion.presentation.components.Tone
import me.nathanfallet.teamcode.companion.presentation.theme.TeamCode
import me.nathanfallet.teamcode.companion.presentation.viewmodels.NotificationPreferencesViewModel
import me.nathanfallet.teamcode.companion.presentation.viewmodels.words
import me.nathanfallet.teamcode.companion.resources.Res
import me.nathanfallet.teamcode.companion.resources.account_title
import me.nathanfallet.teamcode.companion.resources.action_retry
import me.nathanfallet.teamcode.companion.resources.notifications_kind_session_failed
import me.nathanfallet.teamcode.companion.resources.notifications_kind_session_failed_hint
import me.nathanfallet.teamcode.companion.resources.notifications_kind_turn_completed
import me.nathanfallet.teamcode.companion.resources.notifications_kind_turn_completed_hint
import me.nathanfallet.teamcode.companion.resources.notifications_kind_turn_failed
import me.nathanfallet.teamcode.companion.resources.notifications_kind_turn_failed_hint
import me.nathanfallet.teamcode.companion.resources.notifications_kind_waiting
import me.nathanfallet.teamcode.companion.resources.notifications_kind_waiting_hint
import me.nathanfallet.teamcode.companion.resources.notifications_note
import me.nathanfallet.teamcode.companion.resources.notifications_push_section
import me.nathanfallet.teamcode.companion.resources.notifications_refresh
import me.nathanfallet.teamcode.companion.resources.notifications_test_button
import me.nathanfallet.teamcode.companion.resources.notifications_test_hint
import me.nathanfallet.teamcode.companion.resources.notifications_test_no_phone
import me.nathanfallet.teamcode.companion.resources.notifications_test_off
import me.nathanfallet.teamcode.companion.resources.notifications_test_section
import me.nathanfallet.teamcode.companion.resources.notifications_test_sent
import me.nathanfallet.teamcode.companion.resources.notifications_test_undelivered
import me.nathanfallet.teamcode.companion.resources.notifications_title
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/**
 * What the member is notified about on their phones, one switch per kind, and a test push. The switches are kept by
 * the server for the member: they hold on all their phones, whichever device changed them.
 */
@Composable
fun NotificationPreferencesScreen(viewModel: NotificationPreferencesViewModel, onBack: (() -> Unit)?, backLabel: String = stringResource(Res.string.account_title)) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val colors = TeamCode.colors
    Column(Modifier.fillMaxSize().background(colors.background), horizontalAlignment = Alignment.CenterHorizontally) {
        NavBar(backLabel, onBack, stringResource(Res.string.notifications_title)) {
            TcIconButton(PhosphorIcons.Regular.ArrowsClockwise, stringResource(Res.string.notifications_refresh), onClick = viewModel::refresh, enabled = !state.loading,
                modifier = Modifier.testTag("notifications-refresh"))
        }
        Column(
            Modifier.widthIn(max = ContentWidth).fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            state.error?.let { error ->
                Banner(error.words(), Tone.DANGER, Modifier.testTag("notifications-error"), action = if (state.switches == null) {
                    { TcButton(stringResource(Res.string.action_retry), onClick = viewModel::refresh, kind = ButtonKind.SECONDARY, small = true, modifier = Modifier.testTag("notifications-retry")) }
                } else null)
            }
            BusyRow(state.loading && state.switches == null)
            state.switches?.let { switches ->
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SectionTitle(stringResource(Res.string.notifications_push_section))
                    Group(Modifier.testTag("notifications-switches")) {
                        NotificationKind.entries.forEachIndexed { index, kind ->
                            if (index > 0) GroupDivider()
                            GroupRow {
                                Column(Modifier.weight(1f)) {
                                    Text(stringResource(kind.title()), color = colors.text, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                                    Text(stringResource(kind.hint()), color = colors.muted, fontSize = 12.5.sp, lineHeight = 17.sp)
                                }
                                Switch(switches[kind] ?: true, { viewModel.toggle(kind, it) }, enabled = kind !in state.saving,
                                    modifier = Modifier.testTag("notifications-switch-${kind.wire}"))
                            }
                        }
                    }
                    Text(stringResource(Res.string.notifications_note), color = colors.muted, fontSize = 12.5.sp, lineHeight = 17.sp, modifier = Modifier.testTag("notifications-note"))
                }
                if (state.canTest) Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SectionTitle(stringResource(Res.string.notifications_test_section))
                    Group {
                        GroupRow {
                            Text(stringResource(Res.string.notifications_test_hint), color = colors.muted, fontSize = 12.5.sp, lineHeight = 17.sp, modifier = Modifier.weight(1f))
                        }
                        GroupDivider()
                        GroupRow {
                            TcButton(stringResource(Res.string.notifications_test_button), onClick = viewModel::test, kind = ButtonKind.SECONDARY, busy = state.testing,
                                enabled = !state.testing, modifier = Modifier.weight(1f).testTag("notifications-test"))
                        }
                    }
                    state.test?.let { outcome ->
                        val (text, tone) = when (outcome) {
                            is PushTestOutcome.Sent -> if (outcome.delivered > 0) stringResource(Res.string.notifications_test_sent, outcome.delivered) to Tone.NEUTRAL
                                else stringResource(Res.string.notifications_test_undelivered) to Tone.WARN
                            PushTestOutcome.Off -> stringResource(Res.string.notifications_test_off) to Tone.WARN
                            PushTestOutcome.NoPhone -> stringResource(Res.string.notifications_test_no_phone) to Tone.WARN
                        }
                        if (tone == Tone.NEUTRAL) Banner(text, tone, Modifier.testTag("notifications-test-result"), icon = null)
                        else Banner(text, tone, Modifier.testTag("notifications-test-result"))
                    }
                }
            }
        }
    }
}

private fun NotificationKind.title(): StringResource = when (this) {
    NotificationKind.WAITING -> Res.string.notifications_kind_waiting
    NotificationKind.TURN_COMPLETED -> Res.string.notifications_kind_turn_completed
    NotificationKind.TURN_FAILED -> Res.string.notifications_kind_turn_failed
    NotificationKind.SESSION_FAILED -> Res.string.notifications_kind_session_failed
}

private fun NotificationKind.hint(): StringResource = when (this) {
    NotificationKind.WAITING -> Res.string.notifications_kind_waiting_hint
    NotificationKind.TURN_COMPLETED -> Res.string.notifications_kind_turn_completed_hint
    NotificationKind.TURN_FAILED -> Res.string.notifications_kind_turn_failed_hint
    NotificationKind.SESSION_FAILED -> Res.string.notifications_kind_session_failed_hint
}
