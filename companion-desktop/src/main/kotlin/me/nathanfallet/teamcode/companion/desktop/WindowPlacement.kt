package me.nathanfallet.teamcode.companion.desktop

import com.russhwolf.settings.Settings
import java.awt.Rectangle

/**
 * Where the window was at its last change: its floating size and position (in the window system's units, the same as
 * Compose's dp for a window) and whether it was maximized. A maximized window keeps the floating bounds it returns to.
 */
data class SavedWindow(val x: Int?, val y: Int?, val width: Int, val height: Int, val maximized: Boolean = false)

/**
 * The window's size and position, kept between launches in the app's settings. Compose Multiplatform's `WindowState`
 * holds them for one run only; this saves its floating bounds and the maximized flag as they change, and gives them
 * back at launch, checked against the screens connected then.
 */
class WindowPlacementStore(private val settings: Settings) {
    /**
     * The saved window, fitted to [screens] (their bounds): no larger than the largest one and no smaller than
     * [minimum]; its position dropped (the system places it) when its title bar would not be on any screen, as after a
     * monitor was unplugged. Null when nothing was saved, or for a malformed value.
     */
    fun load(screens: List<Rectangle>, minimum: Pair<Int, Int> = MIN_WIDTH to MIN_HEIGHT): SavedWindow? {
        val width = settings.getIntOrNull(WIDTH) ?: return null
        val height = settings.getIntOrNull(HEIGHT) ?: return null
        if (width <= 0 || height <= 0) return null
        val maxWidth = screens.maxOfOrNull { it.width } ?: width
        val maxHeight = screens.maxOfOrNull { it.height } ?: height
        val fitted = SavedWindow(
            x = settings.getIntOrNull(X), y = settings.getIntOrNull(Y),
            width = width.coerceIn(minimum.first, maxOf(minimum.first, maxWidth)),
            height = height.coerceIn(minimum.second, maxOf(minimum.second, maxHeight)),
            maximized = settings.getBoolean(MAXIMIZED, false),
        )
        val x = fitted.x
        val y = fitted.y
        if (x == null || y == null) return fitted.copy(x = null, y = null)
        // The title bar, where the window is dragged from, must be reachable: its first TITLE_BAR points of height and a
        // good part of its width on one screen.
        val titleBar = Rectangle(x, y, fitted.width, TITLE_BAR)
        val reachable = screens.any { screen -> screen.intersection(titleBar).let { !it.isEmpty && it.width >= minOf(VISIBLE_WIDTH, fitted.width) && it.height >= TITLE_BAR / 2 } }
        return if (reachable) fitted else fitted.copy(x = null, y = null)
    }

    /** Saves the floating bounds; [maximized] alone when the window is maximized, so it returns to the bounds it had. */
    fun save(window: SavedWindow) {
        settings.putBoolean(MAXIMIZED, window.maximized)
        if (window.maximized) return
        if (window.width <= 0 || window.height <= 0) return
        settings.putInt(WIDTH, window.width)
        settings.putInt(HEIGHT, window.height)
        if (window.x != null && window.y != null) {
            settings.putInt(X, window.x)
            settings.putInt(Y, window.y)
        }
    }

    companion object {
        const val MIN_WIDTH = 760
        const val MIN_HEIGHT = 520
        private const val TITLE_BAR = 32
        private const val VISIBLE_WIDTH = 120
        private const val X = "window.x"
        private const val Y = "window.y"
        private const val WIDTH = "window.width"
        private const val HEIGHT = "window.height"
        private const val MAXIMIZED = "window.maximized"
    }
}
