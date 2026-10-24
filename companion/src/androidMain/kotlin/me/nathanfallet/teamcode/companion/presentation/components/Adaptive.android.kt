package me.nathanfallet.teamcode.companion.presentation.components

import android.view.KeyCharacterMap
import androidx.compose.ui.input.key.KeyEvent

actual val pointerFirstPlatform: Boolean = false

/** Soft keyboards send their keys from the virtual keyboard device, or flag them as soft. */
actual fun KeyEvent.isFromHardwareKeyboard(): Boolean =
    nativeKeyEvent.deviceId != KeyCharacterMap.VIRTUAL_KEYBOARD && (nativeKeyEvent.flags and android.view.KeyEvent.FLAG_SOFT_KEYBOARD) == 0
