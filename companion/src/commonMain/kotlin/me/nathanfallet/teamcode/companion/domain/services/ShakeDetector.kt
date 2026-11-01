package me.nathanfallet.teamcode.companion.domain.services

import kotlinx.coroutines.flow.Flow

/**
 * The device being shaken: the phone layout's way into the office. Only the platforms with a motion sensor (Android,
 * iOS) declare one; the app reads it with `getOrNull`, so the desktop and the test shells have no shake at all.
 */
interface ShakeDetector {
    /** One element per shake ([me.nathanfallet.teamcode.companion.domain.usecases.shake.ShakeRecognizer]); the sensor is on only while this is collected. */
    val shakes: Flow<Unit>
}
