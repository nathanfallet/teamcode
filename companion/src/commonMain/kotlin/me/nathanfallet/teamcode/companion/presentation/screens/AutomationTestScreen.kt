package me.nathanfallet.teamcode.companion.presentation.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.nathanfallet.teamcode.companion.presentation.components.Banner
import me.nathanfallet.teamcode.companion.presentation.components.BusyRow
import me.nathanfallet.teamcode.companion.presentation.components.ButtonKind
import me.nathanfallet.teamcode.companion.presentation.components.Chip
import me.nathanfallet.teamcode.companion.presentation.components.ContentWidth
import me.nathanfallet.teamcode.companion.presentation.components.NavBar
import me.nathanfallet.teamcode.companion.presentation.components.TcButton
import me.nathanfallet.teamcode.companion.presentation.components.TcCard
import me.nathanfallet.teamcode.companion.presentation.components.TcTextField
import me.nathanfallet.teamcode.companion.presentation.components.Tone
import me.nathanfallet.teamcode.companion.presentation.components.dismissKeyboardOnTap
import me.nathanfallet.teamcode.companion.presentation.theme.MetaStyle
import me.nathanfallet.teamcode.companion.presentation.theme.TeamCode
import me.nathanfallet.teamcode.companion.presentation.viewmodels.AutomationTestViewModel
import me.nathanfallet.teamcode.companion.presentation.viewmodels.words
import me.nathanfallet.teamcode.companion.resources.*
import org.jetbrains.compose.resources.stringResource

/**
 * The test of one saved automation. A webhook automation takes a JSON payload, pre-filled with its saved sample; a
 * preview renders the prompt without starting anything; "Run test" starts a real session on the owner's quota after a
 * confirmation, and shows the run with a link to its session. An administrator only previews another member's.
 */
@Composable
fun AutomationTestScreen(viewModel: AutomationTestViewModel, onBack: (() -> Unit)?, onOpenSession: (String) -> Unit, backLabel: String = stringResource(Res.string.action_back)) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val colors = TeamCode.colors
    var confirm by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().background(colors.background).imePadding().dismissKeyboardOnTap(), horizontalAlignment = Alignment.CenterHorizontally) {
        NavBar(backLabel, onBack, stringResource(if (state.canRun) Res.string.automation_test else Res.string.automation_preview))
        Column(
            Modifier.widthIn(max = ContentWidth).fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp).testTag("automation-test-page"),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            BusyRow(state.loading)
            state.error?.let { error ->
                Banner(error.words(), Tone.DANGER, Modifier.testTag("automation-test-error"), action = {
                    TcButton(stringResource(Res.string.action_retry), onClick = viewModel::load, kind = ButtonKind.SECONDARY, small = true, modifier = Modifier.testTag("automation-test-retry"))
                })
            }
            val automation = state.automation
            if (automation != null) {
                Text(automation.name, color = colors.text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                if (!state.canRun) Banner(stringResource(Res.string.automation_read_only, automation.ownerName), Tone.NEUTRAL, Modifier.testTag("automation-test-read-only"), icon = null)
                if (state.webhook) {
                    TcTextField(state.payload, viewModel::setPayload, label = stringResource(Res.string.automation_test_payload), hint = stringResource(Res.string.automation_test_payload_hint),
                        mono = true, singleLine = false, minLines = 5, keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false), tag = "automation-test-payload")
                } else if (state.canRun) {
                    Text(stringResource(Res.string.automation_test_cron_body), color = colors.muted, fontSize = 13.sp, lineHeight = 18.sp)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TcButton(stringResource(Res.string.automation_preview_prompt), onClick = viewModel::preview, kind = ButtonKind.SECONDARY, busy = state.previewing, modifier = Modifier.weight(1f).testTag("automation-test-preview"))
                    if (state.canRun) TcButton(stringResource(Res.string.automation_run_test), onClick = { confirm = true }, busy = state.running, modifier = Modifier.weight(1f).testTag("automation-test-run"))
                }
                if (state.canRun) Text(stringResource(Res.string.automation_test_cost), color = colors.muted, fontSize = 12.5.sp, lineHeight = 17.sp)
                state.previewError?.let { Banner(it.words(), Tone.DANGER, Modifier.testTag("automation-test-preview-error")) }
                state.preview?.let { TcCard { PreviewView(it) } }
                state.runError?.let { Banner(it.words(), Tone.DANGER, Modifier.testTag("automation-test-run-error")) }
                state.run?.let { run ->
                    TcCard(Modifier.testTag("automation-test-result"), spacing = 8.dp) {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Chip(runSourceWords(run.source), tone = Tone.WARN)
                            Chip(runStatusWords(run.status), tone = runStatusTone(run.status), modifier = Modifier.testTag("automation-test-status"))
                        }
                        runReasonWords(run.reason)?.let { Text(it, color = colors.muted, fontSize = 13.sp, modifier = Modifier.testTag("automation-test-reason")) }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            run.sessionId?.let { TcButton(stringResource(Res.string.automation_open_session), onClick = { onOpenSession(it) }, small = true, modifier = Modifier.testTag("automation-test-session")) }
                            if (!run.final) TcButton(stringResource(Res.string.automations_refresh), onClick = viewModel::refreshRun, kind = ButtonKind.SECONDARY, small = true, modifier = Modifier.testTag("automation-test-refresh"))
                        }
                    }
                }
            }
        }
    }
    if (confirm) ConfirmDialog(stringResource(Res.string.automation_run_test_title), stringResource(Res.string.automation_run_test_body), stringResource(Res.string.automation_run_test),
        onConfirm = { confirm = false; viewModel.run() }, onDismiss = { confirm = false }, tag = "automation-test-confirm")
}
