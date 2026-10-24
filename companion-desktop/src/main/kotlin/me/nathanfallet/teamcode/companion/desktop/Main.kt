package me.nathanfallet.teamcode.companion.desktop

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.res.loadImageBitmap
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Tray
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import java.awt.Desktop
import java.awt.SystemTray
import kotlinx.coroutines.CoroutineScope
import me.nathanfallet.teamcode.companion.App
import me.nathanfallet.teamcode.companion.di.initKoin

/**
 * The desktop app. Closing the window keeps it in the menu bar (the notification area on Windows and Linux); Quit leaves
 * the hosts of this device's remote sessions running, and says so: the device's daemon starts and wakes them, not the
 * app. Without a menu bar icon (a Linux desktop without a tray), closing the window quits.
 */
fun main(args: Array<String>) {
    val environment = DesktopEnvironment.detect(args)
    val koin = initKoin(desktopModule(environment)).koin
    val scope = koin.get<CoroutineScope>()
    koin.get<CliAccountSync>().start(scope)
    val icon = BitmapPainter(Thread.currentThread().contextClassLoader.getResourceAsStream("teamcode-icon.png")!!.use(::loadImageBitmap))
    val tray = try { SystemTray.isSupported() } catch (_: Throwable) { false }
    application {
        var visible by remember { mutableStateOf(true) }
        LaunchedEffect(Unit) {
            // macOS: a click on the Dock icon brings the hidden window back.
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.APP_EVENT_REOPENED))
                Desktop.getDesktop().addAppEventListener(java.awt.desktop.AppReopenedListener { visible = true })
        }
        if (tray) Tray(icon = icon, tooltip = "TeamCode", onAction = { visible = true }, menu = {
            Item("Open TeamCode", onClick = { visible = true })
            Separator()
            Item("Quit TeamCode (sessions on this device keep running)", onClick = ::exitApplication)
        })
        Window(
            onCloseRequest = { if (tray) visible = false else exitApplication() },
            visible = visible,
            title = "TeamCode",
            icon = icon,
            state = rememberWindowState(width = 1000.dp, height = 720.dp),
        ) {
            App()
        }
    }
}
