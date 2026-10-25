package me.nathanfallet.teamcode.companion.desktop

import me.nathanfallet.teamcode.companion.presentation.DesktopLabel
import me.nathanfallet.teamcode.companion.presentation.desktopLabel
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyShortcut
import androidx.compose.ui.res.loadImageBitmap
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.MenuBar
import androidx.compose.ui.window.Tray
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowPosition
import com.russhwolf.settings.Settings
import java.awt.GraphicsEnvironment
import kotlin.math.roundToInt
import androidx.compose.ui.window.rememberWindowState
import java.awt.Desktop
import java.awt.Dimension
import java.awt.SystemTray
import java.awt.Taskbar
import javax.imageio.ImageIO
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.collectLatest
import me.nathanfallet.teamcode.companion.App
import me.nathanfallet.teamcode.companion.presentation.components.LocalWindowTitleBar
import me.nathanfallet.teamcode.companion.presentation.components.WindowTitleBar
import me.nathanfallet.teamcode.companion.presentation.navigation.AppCommand
import me.nathanfallet.teamcode.companion.presentation.navigation.AppCommands
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
    val macBar = WindowTitleBar.forMac(System.getProperty("os.version"))
    /** Cmd on macOS, Ctrl elsewhere. */
    fun shortcut(key: Key) = if (mac) KeyShortcut(key, meta = true) else KeyShortcut(key, ctrl = true)
    val tray = try { SystemTray.isSupported() } catch (_: Throwable) { false }
    // The window opens where it was left, at the size it had: checked against the screens connected now.
    val placement = WindowPlacementStore(koin.get<Settings>())
    val screens = try {
        GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices.map { it.defaultConfiguration.bounds }
    } catch (_: Throwable) { emptyList() }
    val saved = placement.load(screens)
    application {
        var visible by remember { mutableStateOf(true) }
        LaunchedEffect(Unit) {
            // macOS: a click on the Dock icon brings the hidden window back.
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.APP_EVENT_REOPENED))
                Desktop.getDesktop().addAppEventListener(java.awt.desktop.AppReopenedListener { visible = true })
        }
        if (tray) Tray(icon = icon, tooltip = "TeamCode", onAction = { visible = true }, menu = {
            Item(desktopLabel(DesktopLabel.TRAY_OPEN), onClick = { visible = true })
            Separator()
            Item(desktopLabel(DesktopLabel.TRAY_QUIT), onClick = ::exitApplication)
        })
        val windowState = rememberWindowState(
            placement = if (saved?.maximized == true) WindowPlacement.Maximized else WindowPlacement.Floating,
            position = saved?.let { s -> if (s.x != null && s.y != null) WindowPosition(s.x.dp, s.y.dp) else null } ?: WindowPosition(Alignment.Center),
            size = saved?.let { DpSize(it.width.dp, it.height.dp) } ?: DpSize(1200.dp, 800.dp),
        )
        // The window not saved yet, written at once when the app quits before it rests.
        val unsaved = remember { arrayOfNulls<SavedWindow>(1) }
        LaunchedEffect(windowState) {
            // Once the window rests: a drag or a resize emits every frame, and a switch to maximized can pass through a
            // floating window at the maximized size. Fullscreen is not kept: the next launch opens the window it came from.
            snapshotFlow { Triple(windowState.placement, windowState.position, windowState.size) }.collectLatest { (mode, position, size) ->
                if (mode == WindowPlacement.Fullscreen) return@collectLatest
                val floating = mode == WindowPlacement.Floating
                val window = SavedWindow(
                    x = position.takeIf { floating && it.isSpecified }?.x?.value?.roundToInt(), y = position.takeIf { floating && it.isSpecified }?.y?.value?.roundToInt(),
                    width = size.width.value.roundToInt(), height = size.height.value.roundToInt(), maximized = mode == WindowPlacement.Maximized,
                )
                unsaved[0] = window
                delay(500)
                placement.save(window)
                unsaved[0] = null
            }
        }
        DisposableEffect(Unit) { onDispose { unsaved[0]?.let(placement::save) } }
        Window(
            onCloseRequest = { if (tray) visible = false else exitApplication() },
            visible = visible,
            title = "TeamCode",
            icon = appIcon,
            state = windowState,
        ) {
            // macOS: the app reaches the window's top edge, under the title bar and its buttons (MacTitleBar.kt); set before
            // the window first shows. Windows and Linux keep their own bar: drawing it needs the JetBrains Runtime (Jewel's
            // DecoratedWindow), which the app does not ship, and an undecorated window loses snapping, resize borders and the shadow.
            if (mac) remember { window.drawUnderMacTitleBar() }
            // Wide enough for the sidebar and a readable panel: the window never falls back to the phone layout.
            LaunchedEffect(Unit) { window.minimumSize = Dimension(WindowPlacementStore.MIN_WIDTH, WindowPlacementStore.MIN_HEIGHT) }
            MenuBar {
                Menu(desktopLabel(DesktopLabel.MENU_FILE)) {
                    Item(desktopLabel(DesktopLabel.MENU_NEW_SESSION), shortcut = shortcut(Key.N), onClick = { AppCommands.send(AppCommand.NEW_SESSION) })
                    Separator()
                    Item(desktopLabel(DesktopLabel.MENU_CLOSE_WINDOW), shortcut = shortcut(Key.W), onClick = { if (tray) visible = false else exitApplication() })
                }
                Menu(desktopLabel(DesktopLabel.MENU_VIEW)) {
                    Item(desktopLabel(DesktopLabel.MENU_SESSIONS), shortcut = shortcut(Key.One), onClick = { AppCommands.send(AppCommand.SHOW_SESSIONS) })
                    Item(desktopLabel(DesktopLabel.MENU_ARTIFACTS), shortcut = shortcut(Key.Two), onClick = { AppCommands.send(AppCommand.SHOW_ARTIFACTS) })
                    Item(desktopLabel(DesktopLabel.MENU_COMMAND_PALETTE), shortcut = shortcut(Key.K), onClick = { AppCommands.send(AppCommand.COMMAND_PALETTE) })
                    Separator()
                    Item(desktopLabel(DesktopLabel.MENU_SETTINGS), shortcut = shortcut(Key.Comma), onClick = { AppCommands.send(AppCommand.SHOW_ACCOUNT) })
                }
            }
            // macOS hides the bar in fullscreen (the green button too: Compose reads the window's fullscreen from AppKit):
            // the header goes back to its own place there.
            val titleBar = if (mac && windowState.placement != WindowPlacement.Fullscreen) macBar else WindowTitleBar()
            val scope = rememberCoroutineScope()
            CompositionLocalProvider(LocalWindowTitleBar provides titleBar) {
                Box(if (titleBar.height > 0.dp) Modifier.onTitleBarDoubleClick(titleBar.height) {
                    scope.launch {
                        when (withContext(Dispatchers.IO) { readTitleBarDoubleClick() }) {
                            TitleBarDoubleClick.ZOOM -> windowState.placement =
                                if (windowState.placement == WindowPlacement.Maximized) WindowPlacement.Floating else WindowPlacement.Maximized
                            TitleBarDoubleClick.MINIMIZE -> windowState.isMinimized = true
                            TitleBarDoubleClick.NONE -> Unit
                        }
                    }
                } else Modifier) {
                    App(menuShortcuts = true)
                }
            }
        }
    }
}
