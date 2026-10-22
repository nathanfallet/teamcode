package me.nathanfallet.teamcode.companion.presentation.screens

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.regular.Gear
import me.nathanfallet.teamcode.companion.domain.models.Account
import me.nathanfallet.teamcode.companion.presentation.components.BusyRow
import me.nathanfallet.teamcode.companion.presentation.components.ErrorText
import me.nathanfallet.teamcode.companion.presentation.components.FormScaffold
import me.nathanfallet.teamcode.companion.presentation.components.InfoCard
import me.nathanfallet.teamcode.companion.presentation.theme.MonospaceStyle
import me.nathanfallet.teamcode.companion.presentation.viewmodels.HomeViewModel
import me.nathanfallet.teamcode.companion.resources.Res
import me.nathanfallet.teamcode.companion.resources.app_name
import me.nathanfallet.teamcode.companion.resources.home_role
import me.nathanfallet.teamcode.companion.resources.home_server
import me.nathanfallet.teamcode.companion.resources.home_sessions_soon
import me.nathanfallet.teamcode.companion.resources.home_sign_in_again
import me.nathanfallet.teamcode.companion.resources.home_signed_in_as
import me.nathanfallet.teamcode.companion.resources.home_signed_out
import me.nathanfallet.teamcode.companion.resources.settings_title
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel

/** The active account; the sessions list replaces the placeholder in the next step. */
@Composable
fun HomeScreen(
    onOpenSettings: () -> Unit,
    onSignInAgain: (Account) -> Unit,
    viewModel: HomeViewModel = koinViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    FormScaffold(
        title = stringResource(Res.string.app_name),
        onBack = null,
        actions = {
            IconButton(onClick = onOpenSettings, modifier = Modifier.testTag("settings")) {
                Icon(PhosphorIcons.Regular.Gear, contentDescription = stringResource(Res.string.settings_title), modifier = Modifier.size(22.dp))
            }
        },
    ) {
        val account = state.account ?: return@FormScaffold
        InfoCard(Modifier.testTag("account")) {
            Text(stringResource(Res.string.home_signed_in_as, account.username), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(Res.string.home_role, account.role.lowercase()), style = MaterialTheme.typography.bodyMedium)
            Text(stringResource(Res.string.home_server, account.serverVersion, account.origin), style = MonospaceStyle)
        }
        if (!account.signedIn) {
            Text(stringResource(Res.string.home_signed_out, account.origin), color = MaterialTheme.colorScheme.tertiary)
            Button(onClick = { onSignInAgain(account) }, modifier = Modifier.fillMaxWidth().testTag("sign-in-again")) {
                Text(stringResource(Res.string.home_sign_in_again))
            }
        } else {
            Text(stringResource(Res.string.home_sessions_soon), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        ErrorText(state.error)
        BusyRow(state.refreshing)
    }
}
