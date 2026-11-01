package me.nathanfallet.teamcode.companion.infrastructure.system

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import me.nathanfallet.teamcode.companion.domain.services.ShakeDetector
import me.nathanfallet.teamcode.companion.domain.usecases.shake.ShakeRecognizer
import platform.CoreMotion.CMMotionManager
import platform.Foundation.NSOperationQueue

/**
 * iOS: CoreMotion's accelerometer (no permission needed: only the motion and fitness data asks), started only while
 * [shakes] is collected and stopped as soon as it is not. A device without an accelerometer (the simulator) never shakes.
 */
@OptIn(ExperimentalForeignApi::class)
class IosShakeDetector : ShakeDetector {
    override val shakes: Flow<Unit> = callbackFlow {
        val manager = CMMotionManager()
        if (!manager.accelerometerAvailable) {
            close()
            return@callbackFlow
        }
        val recognizer = ShakeRecognizer()
        manager.accelerometerUpdateInterval = 0.02
        // In g with gravity included, on the main queue (the flow is collected there); the timestamp is in seconds.
        manager.startAccelerometerUpdatesToQueue(NSOperationQueue.mainQueue) { data, _ ->
            val sample = data ?: return@startAccelerometerUpdatesToQueue
            val shaken = sample.acceleration.useContents { recognizer.onSample((sample.timestamp * 1_000).toLong(), x, y, z) }
            if (shaken) trySend(Unit)
        }
        awaitClose { manager.stopAccelerometerUpdates() }
    }
}
