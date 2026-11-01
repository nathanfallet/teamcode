package me.nathanfallet.teamcode.companion.infrastructure.system

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.emptyFlow
import me.nathanfallet.teamcode.companion.domain.services.ShakeDetector
import me.nathanfallet.teamcode.companion.domain.usecases.shake.ShakeRecognizer

/**
 * Android: the accelerometer (no permission needed), registered only while [shakes] is collected and unregistered as
 * soon as it is not. A device without an accelerometer never shakes.
 */
class AndroidShakeDetector(context: Context) : ShakeDetector {
    override val shakes: Flow<Unit> = run {
        val manager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        val accelerometer = manager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        if (manager == null || accelerometer == null) emptyFlow() else callbackFlow {
            val recognizer = ShakeRecognizer()
            val listener = object : SensorEventListener {
                override fun onSensorChanged(event: SensorEvent) {
                    // m/s² with gravity included, to g; the event's clock is in nanoseconds.
                    val g = SensorManager.GRAVITY_EARTH
                    val (x, y, z) = Triple(event.values[0] / g, event.values[1] / g, event.values[2] / g)
                    if (recognizer.onSample(event.timestamp / 1_000_000, x.toDouble(), y.toDouble(), z.toDouble())) trySend(Unit)
                }

                override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
            }
            manager.registerListener(listener, accelerometer, SensorManager.SENSOR_DELAY_GAME)
            awaitClose { manager.unregisterListener(listener) }
        }
    }
}
