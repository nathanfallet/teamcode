package me.nathanfallet.teamcode.companion.presentation.components

import androidx.compose.ui.input.key.KeyEvent

actual val pointerFirstPlatform: Boolean = true

/** The desktop has no on-screen keyboard of its own: every key event comes from a keyboard. */
actual fun KeyEvent.isFromHardwareKeyboard(): Boolean = true
