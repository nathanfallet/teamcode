package me.nathanfallet.teamcode.companion.desktop

import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.awtEventOrNull
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import java.awt.Window
import java.awt.event.MouseEvent
import java.io.IOException
import javax.swing.RootPaneContainer

/**
 * macOS's title bar, drawn over the app instead of above it: the window keeps its own buttons (close, minimize, zoom and
 * fullscreen), its shadow, its resizing and its Spaces, and the app's sidebar reaches the top edge, its header beside the
 * buttons. The JDK's client properties, read when the window is created and applied again when they change:
 * `apple.awt.fullWindowContent` lays the app under the bar, `apple.awt.transparentTitleBar` stops drawing the bar, and
 * `apple.awt.windowTitleVisible` hides its title (Java 17 and later). macOS still moves the window from a drag anywhere in
 * the bar's height, over the app too; only the double-click is left to the app ([onTitleBarDoubleClick]).
 */
internal fun Window.drawUnderMacTitleBar() {
    val root = (this as? RootPaneContainer)?.rootPane ?: return
    root.putClientProperty("apple.awt.fullWindowContent", true)
    root.putClientProperty("apple.awt.transparentTitleBar", true)
    root.putClientProperty("apple.awt.windowTitleVisible", false)
}

/** What a double-click on the title bar does, as the member chose in System Settings (Desktop & Dock). */
internal enum class TitleBarDoubleClick { ZOOM, MINIMIZE, NONE }

/**
 * The member's choice from the global `AppleActionOnDoubleClick` default: Minimize, None, or a zoom (Maximize, and Fill on
 * macOS 15, which zooms too here). Missing, macOS zooms.
 */
internal fun titleBarDoubleClick(setting: String?): TitleBarDoubleClick = when (setting?.trim()) {
    "Minimize" -> TitleBarDoubleClick.MINIMIZE
    "None" -> TitleBarDoubleClick.NONE
    else -> TitleBarDoubleClick.ZOOM
}

/** Reads the choice when the member double-clicks (it may have changed since the app started), off the UI thread: it runs `defaults`. */
internal fun readTitleBarDoubleClick(): TitleBarDoubleClick = titleBarDoubleClick(
    try {
        val process = ProcessBuilder("defaults", "read", "-g", "AppleActionOnDoubleClick").redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        if (process.waitFor() == 0) output else null
    } catch (_: IOException) { null }
)

/**
 * A double-click in the title bar's [height] that no control took (a button, a field): what the bar does on its own, which
 * macOS no longer sees once the app covers the bar. Watched after the app's controls, never consumed.
 */
internal fun Modifier.onTitleBarDoubleClick(height: Dp, onDoubleClick: () -> Unit): Modifier = pointerInput(height) {
    awaitPointerEventScope {
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Final)
            val mouse = event.awtEventOrNull
            if (event.type != PointerEventType.Release || mouse?.button != MouseEvent.BUTTON1 || mouse.clickCount != 2) continue
            val change = event.changes.firstOrNull() ?: continue
            if (!change.isConsumed && change.position.y < height.toPx()) onDoubleClick()
        }
    }
}
