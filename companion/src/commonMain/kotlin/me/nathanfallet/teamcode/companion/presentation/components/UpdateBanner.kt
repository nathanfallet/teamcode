package me.nathanfallet.teamcode.companion.presentation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.regular.ArrowsClockwise
import com.adamglin.phosphoricons.regular.WarningCircle
import com.adamglin.phosphoricons.regular.X
import me.nathanfallet.teamcode.companion.domain.services.AppUpdater
import me.nathanfallet.teamcode.companion.presentation.theme.TeamCode
import me.nathanfallet.teamcode.companion.presentation.viewmodels.UpdateBannerState
import me.nathanfallet.teamcode.companion.presentation.viewmodels.UpdateBannerViewModel
import org.koin.mp.KoinPlatform
import org.koin.compose.viewmodel.koinViewModel

/**
 * "Update available" above the whole app: one click updates the CLI and the app together, then reopens the app on the
 * new version.
 *
 * Desktop only (macOS, Windows, Linux), decided by the platform and never by the window's size: it shows only where
 * the desktop's module binds an [AppUpdater]. The iOS and Android apps, iPad included in its wide or split layout,
 * bind none, so nothing shows there: they ship through their own store release track, separate from the CLI and
 * desktop releases, and the CLI's version must never drive an update prompt on mobile.
 */
@Composable
fun UpdateBanner() {
    // The Koin started for this run: a binding the desktop's module alone provides.
    val available = remember { KoinPlatform.getKoinOrNull()?.getOrNull<AppUpdater>() != null }
    if (!available) return
    val viewModel: UpdateBannerViewModel = koinViewModel()
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    UpdateBannerContent(state, onUpdate = viewModel::update, onDismiss = viewModel::dismiss)
}

@Composable
fun UpdateBannerContent(state: UpdateBannerState, onUpdate: () -> Unit, onDismiss: () -> Unit) {
    if (state == UpdateBannerState.Hidden) return
    val colors = TeamCode.colors
    val failed = state is UpdateBannerState.Failed
    Column(Modifier.fillMaxWidth().background(if (failed) colors.dangerBackground else colors.panel).testTag("update-banner")) {
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val tint = if (failed) colors.danger else colors.accentText
            when (state) {
                is UpdateBannerState.Updating, is UpdateBannerState.Restarting ->
                    CircularProgressIndicator(Modifier.size(16.dp), color = tint, strokeWidth = 2.dp)
                else -> Icon(if (failed) PhosphorIcons.Regular.WarningCircle else PhosphorIcons.Regular.ArrowsClockwise, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                val (title, detail) = when (state) {
                    is UpdateBannerState.Available -> "Update available: TeamCode ${state.version}" to
                        "Updates the CLI and this app, then reopens TeamCode. Agent sessions keep running."
                    is UpdateBannerState.Updating -> "Updating TeamCode to ${state.version}…" to state.output.lastOrNull()
                    is UpdateBannerState.Restarting -> "TeamCode ${state.version} is installed" to "Reopening TeamCode…"
                    is UpdateBannerState.Failed -> (state.version?.let { "The update to TeamCode $it failed" } ?: "The update failed") to state.message
                    UpdateBannerState.Hidden -> return@Row
                }
                Text(title, color = if (failed) colors.danger else colors.text, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                detail?.let {
                    val output = state is UpdateBannerState.Updating
                    Text(it, color = if (failed) colors.danger else colors.muted, fontSize = if (output) 12.sp else 12.5.sp, lineHeight = 17.sp,
                        fontFamily = if (output) FontFamily.Monospace else null, maxLines = if (failed) 4 else 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.testTag("update-banner-detail"))
                }
            }
            when (state) {
                is UpdateBannerState.Available -> {
                    TcButton("Update", onUpdate, small = true, fill = false, modifier = Modifier.testTag("update-banner-update"))
                    TcIconButton(PhosphorIcons.Regular.X, "Not now (hide for this version)", onDismiss, modifier = Modifier.testTag("update-banner-dismiss"))
                }
                is UpdateBannerState.Failed -> {
                    if (state.version != null) TcButton("Try again", onUpdate, small = true, fill = false, kind = ButtonKind.SECONDARY, modifier = Modifier.testTag("update-banner-retry"))
                    TcIconButton(PhosphorIcons.Regular.X, "Close", onDismiss, modifier = Modifier.testTag("update-banner-dismiss"))
                }
                else -> Unit
            }
        }
        HorizontalDivider(color = colors.line)
    }
}
