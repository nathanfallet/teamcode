package me.nathanfallet.teamcode.companion.desktop

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyShortcut
import androidx.compose.ui.res.loadImageBitmap
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.MenuBar
import androidx.compose.ui.window.Notification
import androidx.compose.ui.window.Tray
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberTrayState
import androidx.compose.ui.window.rememberWindowState
import java.awt.Desktop
import java.awt.Dimension
import java.awt.SystemTray
import java.awt.Taskbar
import javax.imageio.ImageIO
import kotlinx.coroutines.CoroutineScope
import me.nathanfallet.teamcode.companion.App
import me.nathanfallet.teamcode.companion.presentation.navigation.AppCommand
import me.nathanfallet.teamcode.companion.presentation.navigation.AppCommands
import me.nathanfallet.teamcode.companion.di.initKoin

/**
 * The desktop app. Closing the window keeps it in the menu bar (the notification area on Windows and Linux), where it
 * keeps waking this device's remote sessions; Quit leaves their hosts running, and says so. Without a menu bar icon
 * (a Linux desktop without a tray), closing the window quits.
 */
fun main(args: Array<String>) {
    val environment = DesktopEnvironment.detect(args)
    val koin = initKoin(desktopModule(environment)).koin
    val scope = koin.get<CoroutineScope>()
    koin.get<CliAccountSync>().start(scope)
    val supervisor = koin.get<RemoteHostSupervisor>()
    supervisor.start(scope)
    val resources = Thread.currentThread().contextClassLoader
    // The menu bar's small icon, and the app's own for the window, the Dock and the taskbar.
    val icon = BitmapPainter(resources.getResourceAsStream("teamcode-icon.png")!!.use(::loadImageBitmap))
    val appIcon = BitmapPainter(resources.getResourceAsStream("teamcode-app-icon.png")!!.use(::loadImageBitmap))
    // The packaged app takes its Dock icon from its bundle; run from Gradle, the Dock would show Java's.
    try {
        if (Taskbar.isTaskbarSupported() && Taskbar.getTaskbar().isSupported(Taskbar.Feature.ICON_IMAGE))
            Taskbar.getTaskbar().iconImage = resources.getResourceAsStream("teamcode-app-icon.png")!!.use(ImageIO::read)
    } catch (_: Exception) { }
    val mac = System.getProperty("os.name").orEmpty().startsWith("Mac")
    /** Cmd on macOS, Ctrl elsewhere. */
    fun shortcut(key: Key) = if (mac) KeyShortcut(key, meta = true) else KeyShortcut(key, ctrl = true)
    val tray = try { SystemTray.isSupported() } catch (_: Throwable) { false }
    application {
        var visible by remember { mutableStateOf(true) }
        val trayState = rememberTrayState()
        LaunchedEffect(Unit) {
            // macOS: a click on the Dock icon brings the hidden window back.
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.APP_EVENT_REOPENED))
                Desktop.getDesktop().addAppEventListener(java.awt.desktop.AppReopenedListener { visible = true })
            supervisor.messages.collect { trayState.sendNotification(Notification("TeamCode", it, Notification.Type.Warning)) }
        }
        if (tray) Tray(icon = icon, state = trayState, tooltip = "TeamCode", onAction = { visible = true }, menu = {
            Item("Open TeamCode", onClick = { visible = true })
            Separator()
            Item("Quit TeamCode (sessions on this device keep running)", onClick = ::exitApplication)
        })
        Window(
            onCloseRequest = { if (tray) visible = false else exitApplication() },
            visible = visible,
            title = "TeamCode",
            icon = appIcon,
            state = rememberWindowState(width = 1200.dp, height = 800.dp),
        ) {
            // Wide enough for the sidebar and a readable panel: the window never falls back to the phone layout.
            LaunchedEffect(Unit) { window.minimumSize = Dimension(760, 520) }
            MenuBar {
                Menu("File") {
                    Item("New Session…", shortcut = shortcut(Key.N), onClick = { AppCommands.send(AppCommand.NEW_SESSION) })
                    Separator()
                    Item("Close Window", shortcut = shortcut(Key.W), onClick = { if (tray) visible = false else exitApplication() })
                }
                Menu("View") {
                    Item("Sessions", shortcut = shortcut(Key.One), onClick = { AppCommands.send(AppCommand.SHOW_SESSIONS) })
                    Item("Artifacts", shortcut = shortcut(Key.Two), onClick = { AppCommands.send(AppCommand.SHOW_ARTIFACTS) })
                    Separator()
                    Item("Settings…", shortcut = shortcut(Key.Comma), onClick = { AppCommands.send(AppCommand.SHOW_ACCOUNT) })
                }
            }
            App(menuShortcuts = true)
        }
    }
}
