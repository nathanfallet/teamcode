package me.nathanfallet.teamcode.companion.presentation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfoV2
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.utf16CodePoint
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.DpOffset
import androidx.window.core.layout.WindowSizeClass
import me.nathanfallet.teamcode.companion.presentation.theme.TeamCode

/**
 * The window is at least medium-wide (600 dp, Material's breakpoint): a sidebar and a content panel instead of tabs
 * and pushed screens. Read from Compose's window size class, the same on the desktop, iPad and Android tablets.
 */
@Composable
fun isWideWindow(): Boolean =
    currentWindowAdaptiveInfoV2().windowSizeClass.isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_MEDIUM_LOWER_BOUND)

/** Provided by the app: true when the window shows the sidebar layout ([isWideWindow]). Sheets become dialogs then. */
val LocalWideLayout = compositionLocalOf { false }

/** Provided by the app: a mouse-first platform (the desktop), where controls are denser than touch targets. */
val LocalDense = compositionLocalOf { false }

/** The desktop runs on a mouse and keyboard; phones and tablets are touch-first, even with a trackpad attached. */
expect val pointerFirstPlatform: Boolean

/** A key event from a physical keyboard; false for an on-screen keyboard (Android injects those as key events too). */
expect fun KeyEvent.isFromHardwareKeyboard(): Boolean

/** The hand cursor over something clickable (no effect on touch screens). */
fun Modifier.handCursor(): Modifier = pointerHoverIcon(PointerIcon.Hand)

/** A tinted background while the pointer is over the element, in [shape]. */
fun Modifier.hoverHighlight(shape: Shape, color: Color? = null): Modifier = composed {
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    val tint = color ?: TeamCode.colors.hover
    this.hoverable(source).then(if (hovered) Modifier.clip(shape).background(tint, shape) else Modifier)
}

/** What Enter does in a text field that submits ([enterAction]). */
enum class EnterAction { SUBMIT, NEWLINE, PASS }

/**
 * Enter sends, Shift+Enter and Alt+Enter break the line (Ctrl+Enter and Cmd+Enter send too), only from a physical
 * keyboard: an on-screen keyboard's Enter keeps breaking the line. A single-line field has no line to break.
 */
fun enterAction(event: KeyEvent, singleLine: Boolean, hardware: Boolean = event.isFromHardwareKeyboard()): EnterAction {
    if (!hardware || !event.isEnter()) return EnterAction.PASS
    return when {
        event.isCtrlPressed || event.isMetaPressed -> EnterAction.SUBMIT
        event.isShiftPressed || event.isAltPressed -> if (singleLine) EnterAction.PASS else EnterAction.NEWLINE
        else -> EnterAction.SUBMIT
    }
}

/** The Enter key, or the line feed it types (the desktop delivers the typed character as its own event). */
internal fun KeyEvent.isEnter(): Boolean =
    key == Key.Enter || key == Key.NumPadEnter || (type == KeyEventType.Unknown && (utf16CodePoint == '\n'.code || utf16CodePoint == '\r'.code))

/**
 * A secondary click (right click, two-finger click) on [content] opens a menu where the pointer is. CMP's
 * `ContextMenuArea` exists on the desktop only and serves text selection; this one runs on every platform that has a
 * pointer and opens Material's [DropdownMenu].
 */
@Composable
fun ContextMenuBox(
    modifier: Modifier = Modifier,
    menu: @Composable ColumnScope.(close: () -> Unit) -> Unit,
    content: @Composable () -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    var offset by remember { mutableStateOf(DpOffset.Zero) }
    val density = LocalDensity.current
    Box(
        modifier.pointerInput(Unit) {
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent()
                    if (event.type == PointerEventType.Press && event.buttons.isSecondaryPressed) {
                        val position = event.changes.first().position
                        offset = with(density) { DpOffset(position.x.toDp(), position.y.toDp() - size.height.toDp()) }
                        event.changes.forEach { it.consume() }
                        open = true
                    }
                }
            }
        },
    ) {
        content()
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, offset = offset, containerColor = TeamCode.colors.panel) {
            menu { open = false }
        }
    }
}
