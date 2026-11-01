package me.nathanfallet.teamcode.companion.presentation.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.compose.LifecycleStartEffect
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.regular.Buildings
import me.nathanfallet.teamcode.companion.domain.models.Account
import me.nathanfallet.teamcode.companion.presentation.components.IconButtonKind
import me.nathanfallet.teamcode.companion.presentation.components.TcIconButton
import me.nathanfallet.teamcode.companion.presentation.navigation.ArtifactsRoute
import me.nathanfallet.teamcode.companion.presentation.theme.TeamCode
import me.nathanfallet.teamcode.companion.presentation.viewmodels.SessionsViewModel
import me.nathanfallet.teamcode.companion.presentation.viewmodels.SettingsViewModel
import me.nathanfallet.teamcode.companion.resources.Res
import me.nathanfallet.teamcode.companion.resources.office_title
import me.nathanfallet.teamcode.companion.resources.sessions_title
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel

/**
 * The office pushed over the phone's tabs (by a shake, or the Sessions title's button): the same [OfficeScreen] as the
 * wide layout's panel, with a way back. [sessions] is the destination's own list, followed while it shows; the tabs'
 * list is not shared, so its start and stop stay the Sessions tab's. A new session (the empty office's button) opens
 * its sheet here, then the session.
 */
@Composable
fun PhoneOfficeScreen(
    onBack: () -> Unit,
    onOpenSession: (accountId: String, sessionId: String) -> Unit,
    onOpenArtifacts: (ArtifactsRoute) -> Unit,
    onSignInAgain: (Account) -> Unit,
    onAddAccount: () -> Unit,
    sessions: SessionsViewModel = koinViewModel(),
    settings: SettingsViewModel = koinViewModel(),
) {
    LifecycleStartEffect(sessions) {
        sessions.start()
        onStopOrDispose { sessions.stop() }
    }
    val sheets = rememberMainSheets()
    Box(Modifier.fillMaxSize().background(TeamCode.colors.background).windowInsetsPadding(WindowInsets.statusBars).navigationBarsPadding()) {
        // The workflows and automations boards lead to tabs of the wide layout's panel: the phone shows the room without them.
        OfficeScreen(
            sessionsViewModel = sessions,
            onOpenSession = onOpenSession,
            onOpenArtifacts = onOpenArtifacts,
            onNewSession = { sheets.newSession(it.id) },
            onBack = onBack,
            backLabel = stringResource(Res.string.sessions_title),
        )
    }
    MainSheetsHost(sheets, settings, onOpenSession, onSignInAgain, onAddAccount, onManageAccounts = onBack)
}

/** The Sessions title's way into the office on the phone layout, for whoever does not or cannot shake the device. */
@Composable
internal fun OfficeButton(onClick: () -> Unit) {
    TcIconButton(PhosphorIcons.Regular.Buildings, stringResource(Res.string.office_title), onClick = onClick, kind = IconButtonKind.OUTLINED, modifier = Modifier.testTag("open-office"))
}
