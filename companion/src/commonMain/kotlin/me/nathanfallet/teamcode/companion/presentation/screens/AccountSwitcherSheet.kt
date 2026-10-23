package me.nathanfallet.teamcode.companion.presentation.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.regular.Check
import com.adamglin.phosphoricons.regular.Plus
import me.nathanfallet.teamcode.companion.domain.models.Account
import me.nathanfallet.teamcode.companion.presentation.components.Avatar
import me.nathanfallet.teamcode.companion.presentation.components.ButtonKind
import me.nathanfallet.teamcode.companion.presentation.components.Chip
import me.nathanfallet.teamcode.companion.presentation.components.Group
import me.nathanfallet.teamcode.companion.presentation.components.GroupDivider
import me.nathanfallet.teamcode.companion.presentation.components.SheetHeader
import me.nathanfallet.teamcode.companion.presentation.components.TcButton
import me.nathanfallet.teamcode.companion.presentation.components.TcSheet
import me.nathanfallet.teamcode.companion.presentation.components.Tone
import me.nathanfallet.teamcode.companion.presentation.components.hostOf
import me.nathanfallet.teamcode.companion.presentation.theme.MetaStyle
import me.nathanfallet.teamcode.companion.presentation.theme.TeamCode
import me.nathanfallet.teamcode.companion.resources.Res
import me.nathanfallet.teamcode.companion.resources.accounts_manage
import me.nathanfallet.teamcode.companion.resources.accounts_sign_in
import me.nathanfallet.teamcode.companion.resources.accounts_signed_out
import me.nathanfallet.teamcode.companion.resources.accounts_title
import me.nathanfallet.teamcode.companion.resources.settings_add_account
import org.jetbrains.compose.resources.stringResource

/** Every signed-in account, the active one checked; a tap makes another one active. */
@Composable
fun AccountSwitcherSheet(
    accounts: List<Account>,
    activeAccountId: String?,
    onSwitch: (Account) -> Unit,
    onSignInAgain: (Account) -> Unit,
    onAddAccount: () -> Unit,
    onManage: () -> Unit,
    onDismiss: () -> Unit,
) {
    TcSheet(onDismiss) {
        SheetHeader(stringResource(Res.string.accounts_title), trailing = {
            TcButton(stringResource(Res.string.accounts_manage), onClick = onManage, kind = ButtonKind.GHOST, small = true, modifier = Modifier.testTag("accounts-manage"))
        })
        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            Group {
                accounts.forEachIndexed { index, account ->
                    if (index > 0) GroupDivider()
                    AccountOption(account, active = account.id == activeAccountId, onSwitch = { onSwitch(account) }, onSignInAgain = { onSignInAgain(account) })
                }
            }
        }
        Box(Modifier.navigationBarsPadding().padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 20.dp)) {
            TcButton(stringResource(Res.string.settings_add_account), onClick = onAddAccount, kind = ButtonKind.SECONDARY, icon = PhosphorIcons.Regular.Plus, modifier = Modifier.testTag("accounts-add"))
        }
    }
}

@Composable
private fun AccountOption(account: Account, active: Boolean, onSwitch: () -> Unit, onSignInAgain: () -> Unit) {
    val colors = TeamCode.colors
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .then(if (!active) Modifier.clickable(onClick = onSwitch) else Modifier)
            .semantics { selected = active }
            .padding(horizontal = 14.dp, vertical = 10.dp)
            .testTag("switch-${account.username}-${hostOf(account.origin)}"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Avatar(account.username, dimmed = !account.signedIn)
        Column(Modifier.weight(1f)) {
            Text(account.username, color = colors.text, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(hostOf(account.origin), style = MetaStyle, color = colors.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (account.signedIn) {
            Chip(account.role.lowercase())
            Box(Modifier.size(20.dp)) {
                if (active) Icon(PhosphorIcons.Regular.Check, contentDescription = null, tint = colors.accentText, modifier = Modifier.size(20.dp))
            }
        } else {
            Chip(stringResource(Res.string.accounts_signed_out), tone = Tone.WARN)
            TcButton(stringResource(Res.string.accounts_sign_in), onClick = onSignInAgain, kind = ButtonKind.SECONDARY, small = true)
        }
    }
}
