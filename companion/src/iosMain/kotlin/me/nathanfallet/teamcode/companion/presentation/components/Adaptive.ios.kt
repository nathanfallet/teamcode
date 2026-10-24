package me.nathanfallet.teamcode.companion.presentation.components

import androidx.compose.ui.input.key.KeyEvent

actual val pointerFirstPlatform: Boolean = false

/** UIKit types the on-screen keyboard's text through the text input session: key events come from a keyboard. */
actual fun KeyEvent.isFromHardwareKeyboard(): Boolean = true
