package me.nathanfallet.teamcode.companion

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.nathanfallet.teamcode.companion.presentation.components.LocalDense
import me.nathanfallet.teamcode.companion.presentation.components.LocalWideLayout
import me.nathanfallet.teamcode.companion.presentation.components.UpdateBanner
import me.nathanfallet.teamcode.companion.presentation.components.isWideWindow
import me.nathanfallet.teamcode.companion.presentation.components.pointerFirstPlatform
import me.nathanfallet.teamcode.companion.presentation.navigation.AppCommand
import me.nathanfallet.teamcode.companion.presentation.navigation.AppCommands
import me.nathanfallet.teamcode.companion.presentation.navigation.TeamCodeNavHost
import me.nathanfallet.teamcode.companion.presentation.theme.TeamCodeTheme
import me.nathanfallet.teamcode.companion.presentation.viewmodels.RootViewModel
import org.koin.compose.viewmodel.koinViewModel

/**
 * The app. [menuShortcuts]: the platform's menu bar already sends the keyboard shortcuts ([AppCommands], the
 * desktop); otherwise (a tablet or a phone with a keyboard) the app reads them itself.
 */
@Composable
fun App(menuShortcuts: Boolean = false) {
    val root: RootViewModel = koinViewModel()
    val theme by root.theme.collectAsStateWithLifecycle()
    TeamCodeTheme(theme) {
        CompositionLocalProvider(LocalWideLayout provides isWideWindow(), LocalDense provides pointerFirstPlatform) {
            Surface(
                modifier = Modifier.fillMaxSize().then(if (menuShortcuts) Modifier else Modifier.onPreviewKeyEvent { event -> shortcut(event)?.let { AppCommands.send(it); true } ?: false }),
                color = MaterialTheme.colorScheme.background,
            ) {
                Column {
                    UpdateBanner()
                    Box(Modifier.weight(1f)) { TeamCodeNavHost(root) }
                }
            }
        }
    }
}

/** Cmd (or Ctrl) with N, 1, 2 or comma: the same shortcuts as the desktop's menu bar. */
internal fun shortcut(event: KeyEvent): AppCommand? {
    if (event.type != KeyEventType.KeyDown || !(event.isMetaPressed || event.isCtrlPressed)) return null
    return when (event.key) {
        Key.N -> AppCommand.NEW_SESSION
        Key.One -> AppCommand.SHOW_SESSIONS
        Key.Two -> AppCommand.SHOW_ARTIFACTS
        Key.Comma -> AppCommand.SHOW_ACCOUNT
        else -> null
    }
}
