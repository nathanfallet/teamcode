package me.nathanfallet.teamcode.companion.presentation.components

import me.nathanfallet.teamcode.companion.resources.*
import org.jetbrains.compose.resources.stringResource
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
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
 * "Update available" at the top of the main screen's content (the content panel when wide, above the tab when narrow):
 * one click updates the CLI and gets the new app ready, with the download's progress, then reopens the app on it.
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
                    is UpdateBannerState.Available -> stringResource(Res.string.update_available_title, state.version) to
                        stringResource(Res.string.update_available_detail)
                    is UpdateBannerState.Updating -> stringResource(Res.string.update_updating_title, state.version) to state.output.lastOrNull()
                    is UpdateBannerState.Restarting -> stringResource(Res.string.update_restarting_title, state.version) to
                        stringResource(if (state.installsAfterQuit) Res.string.update_restarting_detail_installer else Res.string.update_restarting_detail)
                    is UpdateBannerState.Failed -> (state.version?.let { stringResource(Res.string.update_failed_title_version, it) } ?: stringResource(Res.string.update_failed_title)) to (state.message ?: stringResource(Res.string.update_failed_unexpected))
                    UpdateBannerState.Hidden -> return@Row
                }
                Text(title, color = if (failed) colors.danger else colors.text, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                detail?.let {
                    val output = state is UpdateBannerState.Updating
                    Text(it, color = if (failed) colors.danger else colors.muted, fontSize = if (output) 12.sp else 12.5.sp, lineHeight = 17.sp,
                        fontFamily = if (output) FontFamily.Monospace else null, maxLines = if (failed) 4 else 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.testTag("update-banner-detail"))
                }
                (state as? UpdateBannerState.Updating)?.progress?.let { share ->
                    LinearProgressIndicator(progress = { share }, color = colors.accentText, trackColor = colors.line,
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp).height(3.dp).testTag("update-banner-progress"))
                }
            }
            when (state) {
                is UpdateBannerState.Available -> {
                    TcButton(stringResource(Res.string.update_action), onUpdate, small = true, fill = false, modifier = Modifier.testTag("update-banner-update"))
                    TcIconButton(PhosphorIcons.Regular.X, stringResource(Res.string.update_not_now), onDismiss, modifier = Modifier.testTag("update-banner-dismiss"))
                }
                is UpdateBannerState.Failed -> {
                    if (state.version != null) TcButton(stringResource(Res.string.update_try_again), onUpdate, small = true, fill = false, kind = ButtonKind.SECONDARY, modifier = Modifier.testTag("update-banner-retry"))
                    TcIconButton(PhosphorIcons.Regular.X, stringResource(Res.string.action_close), onDismiss, modifier = Modifier.testTag("update-banner-dismiss"))
                }
                else -> Unit
            }
        }
        HorizontalDivider(color = colors.line)
    }
}
