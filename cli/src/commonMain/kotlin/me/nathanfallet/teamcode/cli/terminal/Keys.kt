package me.nathanfallet.teamcode.cli.terminal

import com.github.ajalt.mordant.input.KeyboardEvent

/** A key read from the terminal in raw mode (Mordant's [KeyboardEvent]), reduced to what the CLI's pickers use. */
internal sealed interface Key {
    /** Printable text: one character, possibly several UTF-16 units. */
    data class Text(val text: String) : Key
    data object Up : Key
    data object Down : Key
    data object Left : Key
    data object Right : Key
    data object Enter : Key
    data object Backspace : Key
    data object Escape : Key
    /** Ctrl-U. */
    data object ClearLine : Key
    /** Ctrl-C: signals are off in raw mode, so it arrives as a key (cancel, detach). */
    data object Interrupt : Key
    /** Ctrl-D. */
    data object EndOfInput : Key
}

/** The [Key] of a Mordant keyboard event, or null for one the pickers ignore (function keys, Alt combinations). */
internal fun KeyboardEvent.toKey(): Key? = when {
    ctrl -> when (key) { "c" -> Key.Interrupt; "d" -> Key.EndOfInput; "u" -> Key.ClearLine; "h" -> Key.Backspace; "j", "m" -> Key.Enter; else -> null }
    alt -> null
    else -> when (key) {
        "ArrowUp" -> Key.Up
        "ArrowDown" -> Key.Down
        "ArrowLeft" -> Key.Left
        "ArrowRight" -> Key.Right
        "Enter" -> Key.Enter
        "Backspace" -> Key.Backspace
        "Escape" -> Key.Escape
        "Tab" -> null
        // A printable character is the key itself; named keys ("Delete", "F1", "Unidentified") are longer words.
        else -> key.takeIf { it.isNotEmpty() && (it.length == 1 || (it.length == 2 && it[0].isHighSurrogate())) && !it[0].isISOControl() }?.let(Key::Text)
    }
}
