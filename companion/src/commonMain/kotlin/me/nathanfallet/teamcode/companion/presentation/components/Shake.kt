package me.nathanfallet.teamcode.companion.presentation.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.lifecycle.compose.LifecycleStartEffect
import kotlinx.coroutines.launch
import me.nathanfallet.teamcode.companion.domain.services.ShakeDetector
import org.koin.compose.currentKoinScope

/**
 * Calls [onShake] on each shake of the device while [enabled] and the screen is started: in the foreground and, in a
 * navigation destination, on top of its stack. The sensor is listened to only then. Nothing on a platform without a
 * [ShakeDetector] (the desktop).
 */
@Composable
fun OnShake(enabled: Boolean, onShake: () -> Unit) {
    // The composition's Koin scope, as koinInject reads it (an optional definition: a platform may have no sensor).
    val koin = currentKoinScope()
    val detector = remember(koin) { koin.getOrNull<ShakeDetector>() } ?: return
    val shaken by rememberUpdatedState(onShake)
    val scope = rememberCoroutineScope()
    if (enabled) LifecycleStartEffect(detector) {
        val listening = scope.launch { detector.shakes.collect { shaken() } }
        onStopOrDispose { listening.cancel() }
    }
}
