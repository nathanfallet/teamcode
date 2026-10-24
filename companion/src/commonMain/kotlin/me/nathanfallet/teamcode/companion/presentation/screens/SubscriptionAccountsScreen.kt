package me.nathanfallet.teamcode.companion.presentation.screens

import me.nathanfallet.teamcode.companion.presentation.viewmodels.words
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.regular.ArrowSquareOut
import com.adamglin.phosphoricons.regular.ArrowsClockwise
import com.adamglin.phosphoricons.regular.Plus
import com.adamglin.phosphoricons.regular.UserCircle
import me.nathanfallet.teamcode.companion.domain.models.AddedSubscriptionAccount
import me.nathanfallet.teamcode.companion.domain.models.EnrollmentAccess
import me.nathanfallet.teamcode.companion.domain.models.SubscriptionAccount
import me.nathanfallet.teamcode.companion.presentation.components.Banner
import me.nathanfallet.teamcode.companion.presentation.components.BusyRow
import me.nathanfallet.teamcode.companion.presentation.components.ButtonKind
import me.nathanfallet.teamcode.companion.presentation.components.Chip
import me.nathanfallet.teamcode.companion.presentation.components.ContentWidth
import me.nathanfallet.teamcode.companion.presentation.components.EmptyState
import me.nathanfallet.teamcode.companion.presentation.components.ErrorText
import me.nathanfallet.teamcode.companion.presentation.components.Group
import me.nathanfallet.teamcode.companion.presentation.components.GroupDivider
import me.nathanfallet.teamcode.companion.presentation.components.GroupRow
import me.nathanfallet.teamcode.companion.presentation.components.NavBar
import me.nathanfallet.teamcode.companion.presentation.components.SectionTitle
import me.nathanfallet.teamcode.companion.presentation.components.TcButton
import me.nathanfallet.teamcode.companion.presentation.components.TcCard
import me.nathanfallet.teamcode.companion.presentation.components.TcIconButton
import me.nathanfallet.teamcode.companion.presentation.components.TcTextField
import me.nathanfallet.teamcode.companion.presentation.components.dismissKeyboardOnTap
import me.nathanfallet.teamcode.companion.presentation.components.Tone
import me.nathanfallet.teamcode.companion.presentation.theme.MetaStyle
import me.nathanfallet.teamcode.companion.presentation.theme.TeamCode
import me.nathanfallet.teamcode.companion.presentation.viewmodels.AddAccountStep
import me.nathanfallet.teamcode.companion.presentation.viewmodels.SubscriptionAccountsUiState
import me.nathanfallet.teamcode.companion.presentation.viewmodels.SubscriptionAccountsViewModel
import me.nathanfallet.teamcode.companion.resources.Res
import me.nathanfallet.teamcode.companion.resources.account_title
import me.nathanfallet.teamcode.companion.resources.action_cancel
import me.nathanfallet.teamcode.companion.resources.subscriptions_add
import me.nathanfallet.teamcode.companion.resources.subscriptions_added
import me.nathanfallet.teamcode.companion.resources.subscriptions_administrators_only
import me.nathanfallet.teamcode.companion.resources.subscriptions_choose_type
import me.nathanfallet.teamcode.companion.resources.subscriptions_disabled
import me.nathanfallet.teamcode.companion.resources.subscriptions_empty_body
import me.nathanfallet.teamcode.companion.resources.subscriptions_empty_title
import me.nathanfallet.teamcode.companion.resources.subscriptions_members_hidden
import me.nathanfallet.teamcode.companion.resources.subscriptions_open_again
import me.nathanfallet.teamcode.companion.resources.subscriptions_paste_label
import me.nathanfallet.teamcode.companion.resources.subscriptions_pending_handover
import me.nathanfallet.teamcode.companion.resources.subscriptions_refresh
import me.nathanfallet.teamcode.companion.resources.subscriptions_serving
import me.nathanfallet.teamcode.companion.resources.subscriptions_session_cannot_enroll
import me.nathanfallet.teamcode.companion.resources.subscriptions_sign_in_body
import me.nathanfallet.teamcode.companion.resources.subscriptions_sign_in_title
import me.nathanfallet.teamcode.companion.resources.subscriptions_start_again
import me.nathanfallet.teamcode.companion.resources.subscriptions_submit
import me.nathanfallet.teamcode.companion.resources.subscriptions_title
import me.nathanfallet.teamcode.companion.resources.subscriptions_type_unavailable
import me.nathanfallet.teamcode.companion.resources.subscriptions_updated
import org.jetbrains.compose.resources.stringResource

/**
 * The subscription accounts of the account's server (administrators see the pool) and "Add a subscription account":
 * a kind of account, the provider's page in the system browser, the pasted code sent to the server. The pasted code is
 * typed into a hidden field and never shown back.
 */
@Composable
fun SubscriptionAccountsScreen(viewModel: SubscriptionAccountsViewModel, onBack: (() -> Unit)?, backLabel: String = stringResource(Res.string.account_title)) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val colors = TeamCode.colors
    Column(Modifier.fillMaxSize().background(colors.background).dismissKeyboardOnTap(), horizontalAlignment = Alignment.CenterHorizontally) {
        NavBar(backLabel, onBack, stringResource(Res.string.subscriptions_title)) {
            TcIconButton(PhosphorIcons.Regular.ArrowsClockwise, stringResource(Res.string.subscriptions_refresh), onClick = viewModel::refresh, enabled = !state.loading,
                modifier = Modifier.testTag("subscriptions-refresh"))
        }
        Column(
            Modifier.widthIn(max = ContentWidth).fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            state.error?.let { Banner(it.words(), Tone.DANGER, Modifier.testTag("subscriptions-error")) }
            state.added?.let { AddedBanner(it) }
            when (state.access) {
                EnrollmentAccess.ADMINISTRATORS_ONLY -> Banner(stringResource(Res.string.subscriptions_administrators_only), Tone.WARN, Modifier.testTag("enrollment-refused"))
                EnrollmentAccess.SESSION_CANNOT_ENROLL -> Banner(stringResource(Res.string.subscriptions_session_cannot_enroll), Tone.WARN, Modifier.testTag("enrollment-refused"))
                EnrollmentAccess.ALLOWED, null -> Unit
            }
            when (val step = state.step) {
                null -> if (state.access == EnrollmentAccess.ALLOWED) TcButton(stringResource(Res.string.subscriptions_add), onClick = viewModel::startAdding, enabled = state.canAdd,
                    icon = PhosphorIcons.Regular.Plus, modifier = Modifier.fillMaxWidth().testTag("add-subscription"))
                AddAccountStep.ChooseType -> ChooseType(state, viewModel)
                is AddAccountStep.SignIn -> SignIn(step, state, viewModel)
            }
            BusyRow(state.loading)
            Pool(state)
        }
    }
}

@Composable
private fun AddedBanner(added: AddedSubscriptionAccount) {
    val name = added.account.label
    val first = stringResource(if (added.created) Res.string.subscriptions_added else Res.string.subscriptions_updated, name)
    val then = stringResource(if (added.pendingHandover) Res.string.subscriptions_pending_handover else Res.string.subscriptions_serving)
    Banner("$first $then", if (added.pendingHandover) Tone.WARN else Tone.NEUTRAL, Modifier.testTag("subscription-added"), icon = null)
}

@Composable
private fun ChooseType(state: SubscriptionAccountsUiState, viewModel: SubscriptionAccountsViewModel) {
    val colors = TeamCode.colors
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionTitle(stringResource(Res.string.subscriptions_choose_type))
        Group(Modifier.testTag("account-types")) {
            state.types.forEachIndexed { index, type ->
                if (index > 0) GroupDivider()
                val enabled = type.available && !state.starting
                GroupRow(Modifier.testTag("type-${type.id}"), onClick = if (enabled) ({ viewModel.choose(type) }) else null) {
                    Column(Modifier.weight(1f)) {
                        Text(type.label, color = if (type.available) colors.text else colors.muted, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                        if (!type.available) Text(stringResource(Res.string.subscriptions_type_unavailable), color = colors.muted, fontSize = 12.5.sp, modifier = Modifier.testTag("unavailable-${type.id}"))
                    }
                }
            }
        }
        BusyRow(state.starting)
        TcButton(stringResource(Res.string.action_cancel), onClick = viewModel::cancelAdding, kind = ButtonKind.SECONDARY, modifier = Modifier.fillMaxWidth().testTag("cancel-add"))
    }
}

@Composable
private fun SignIn(step: AddAccountStep.SignIn, state: SubscriptionAccountsUiState, viewModel: SubscriptionAccountsViewModel) {
    val colors = TeamCode.colors
    val browser = LocalUriHandler.current
    // Opened once per attempt; "Open the sign-in page again" is there if the browser did not come up.
    LaunchedEffect(step.signIn) {
        if (!step.opened) {
            viewModel.browserOpened()
            runCatching { browser.openUri(step.signIn.url) }
        }
    }
    TcCard(Modifier.testTag("sign-in")) {
        Text(stringResource(Res.string.subscriptions_sign_in_title, step.signIn.providerName), color = colors.text, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
        Text(stringResource(Res.string.subscriptions_sign_in_body), color = colors.muted, fontSize = 13.5.sp)
        Text(step.signIn.pasteInstructions, color = colors.muted, fontSize = 13.5.sp)
        TcButton(stringResource(Res.string.subscriptions_open_again), onClick = { runCatching { browser.openUri(step.signIn.url) } }, kind = ButtonKind.SECONDARY, small = true,
            icon = PhosphorIcons.Regular.ArrowSquareOut, modifier = Modifier.testTag("open-sign-in"))
        if (!step.spent) {
            TcTextField(
                step.paste, viewModel::onPaste, label = stringResource(Res.string.subscriptions_paste_label), password = true, mono = true, enabled = !step.sending,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done), keyboardActions = KeyboardActions(onDone = { viewModel.submit() }), tag = "paste-code",
            )
        }
        ErrorText(step.error, Modifier.testTag("sign-in-error"))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TcButton(stringResource(Res.string.action_cancel), onClick = viewModel::cancelAdding, kind = ButtonKind.SECONDARY, enabled = !step.sending, modifier = Modifier.weight(1f).testTag("cancel-add"))
            if (step.spent) TcButton(stringResource(Res.string.subscriptions_start_again), onClick = { viewModel.choose(step.signIn.type) }, enabled = !state.starting, busy = state.starting,
                modifier = Modifier.weight(1f).testTag("start-again"))
            else TcButton(stringResource(Res.string.subscriptions_submit), onClick = viewModel::submit, enabled = step.canSubmit, busy = step.sending, modifier = Modifier.weight(1f).testTag("submit-code"))
        }
    }
}

@Composable
private fun Pool(state: SubscriptionAccountsUiState) {
    val colors = TeamCode.colors
    if (state.loading && state.accounts == null) return
    val list = state.accounts
    when {
        state.access == null -> Unit
        list == null -> Text(stringResource(Res.string.subscriptions_members_hidden), color = colors.muted, fontSize = 13.sp, modifier = Modifier.testTag("pool-hidden"))
        list.isEmpty() -> EmptyState(PhosphorIcons.Regular.UserCircle, stringResource(Res.string.subscriptions_empty_title), stringResource(Res.string.subscriptions_empty_body),
            modifier = Modifier.testTag("pool-empty"))
        else -> Group(Modifier.testTag("pool")) {
            list.forEachIndexed { index, account ->
                if (index > 0) GroupDivider()
                PoolRow(account)
            }
        }
    }
}

@Composable
private fun PoolRow(account: SubscriptionAccount) {
    val colors = TeamCode.colors
    GroupRow(Modifier.testTag("subscription-${account.id}")) {
        Column(Modifier.weight(1f)) {
            Text(account.label, color = colors.text, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(account.provider, style = MetaStyle, color = colors.muted)
        }
        if (account.disabled) Chip(stringResource(Res.string.subscriptions_disabled), tone = Tone.MUTED)
        Chip(account.state.lowercase().replace('_', ' '), tone = if (account.state == "READY") Tone.NEUTRAL else Tone.WARN)
    }
}
