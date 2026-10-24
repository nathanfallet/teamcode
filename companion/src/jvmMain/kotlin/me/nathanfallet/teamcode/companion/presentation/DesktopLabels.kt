package me.nathanfallet.teamcode.companion.presentation

import androidx.compose.runtime.Composable
import me.nathanfallet.teamcode.companion.resources.*
import org.jetbrains.compose.resources.stringResource

/** The texts of the desktop shell (menu bar, tray), which lives in `:companion-desktop` while the resources stay here. */
enum class DesktopLabel {
    TRAY_OPEN, TRAY_QUIT, MENU_FILE, MENU_NEW_SESSION, MENU_CLOSE_WINDOW, MENU_VIEW, MENU_SESSIONS, MENU_ARTIFACTS,
    MENU_COMMAND_PALETTE, MENU_SETTINGS,
}

/** The text of [label] in the language of the system. */
@Composable
fun desktopLabel(label: DesktopLabel): String = stringResource(
    when (label) {
        DesktopLabel.TRAY_OPEN -> Res.string.tray_open
        DesktopLabel.TRAY_QUIT -> Res.string.tray_quit
        DesktopLabel.MENU_FILE -> Res.string.menu_file
        DesktopLabel.MENU_NEW_SESSION -> Res.string.menu_new_session
        DesktopLabel.MENU_CLOSE_WINDOW -> Res.string.menu_close_window
        DesktopLabel.MENU_VIEW -> Res.string.menu_view
        DesktopLabel.MENU_SESSIONS -> Res.string.menu_sessions
        DesktopLabel.MENU_ARTIFACTS -> Res.string.menu_artifacts
        DesktopLabel.MENU_COMMAND_PALETTE -> Res.string.menu_command_palette
        DesktopLabel.MENU_SETTINGS -> Res.string.menu_settings
    },
)
