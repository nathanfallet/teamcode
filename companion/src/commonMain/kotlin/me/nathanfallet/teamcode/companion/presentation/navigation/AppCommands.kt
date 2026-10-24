package me.nathanfallet.teamcode.companion.presentation.navigation

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/** What the keyboard shortcuts and the desktop's menu bar ask of the app. */
enum class AppCommand { NEW_SESSION, SHOW_SESSIONS, SHOW_ARTIFACTS, SHOW_ACCOUNT, COMMAND_PALETTE }

/**
 * Commands from outside the composition: the desktop's native menu bar sends them with their shortcuts
 * (Cmd+N, Cmd+1, Cmd+2, Cmd+, and Cmd+K); without a menu bar (iPad and Android with a keyboard) the app's own key handler does.
 */
object AppCommands {
    private val flow = MutableSharedFlow<AppCommand>(extraBufferCapacity = 8)
    val commands: SharedFlow<AppCommand> = flow.asSharedFlow()

    fun send(command: AppCommand) { flow.tryEmit(command) }
}
