package me.nathanfallet.teamcode.companion.domain.usecases.shake

import kotlin.math.sqrt

/**
 * Tells a deliberate shake from the rest of a phone's life (resting on a table, walking, a single bump), from the raw
 * accelerometer samples of either platform, in g with gravity included (a phone at rest reads 1 g).
 *
 * A peak is the magnitude rising above [thresholdG]; it counts again only once the magnitude fell back under
 * [rearmG], so jitter around the threshold is one peak, not several. A shake is [peaks] peaks within [windowMillis]:
 * walking or running stays under the threshold, a bump or a drop is one peak. After a shake the samples are ignored
 * for [cooldownMillis], so one long shake opens one office.
 *
 * Not thread-safe: each platform feeds it from one sensor callback thread.
 */
class ShakeRecognizer(
    private val thresholdG: Double = 2.2,
    private val rearmG: Double = 1.6,
    private val peaks: Int = 3,
    private val windowMillis: Long = 1_000,
    private val cooldownMillis: Long = 1_500,
) {
    init {
        require(rearmG < thresholdG) { "rearmG must be under thresholdG" }
        require(peaks >= 1 && windowMillis > 0 && cooldownMillis >= 0)
    }

    private val peakTimes = ArrayDeque<Long>()
    private var armed = true
    private var quietUntil = Long.MIN_VALUE

    /** Feeds one sample taken at [timeMillis] (any monotonic clock); true when it completes a shake. */
    fun onSample(timeMillis: Long, x: Double, y: Double, z: Double): Boolean {
        val magnitude = sqrt(x * x + y * y + z * z)
        if (timeMillis < quietUntil) return false
        if (!armed) {
            if (magnitude < rearmG) armed = true
            return false
        }
        if (magnitude <= thresholdG) return false
        armed = false
        peakTimes.addLast(timeMillis)
        while (peakTimes.isNotEmpty() && timeMillis - peakTimes.first() > windowMillis) peakTimes.removeFirst()
        if (peakTimes.size < peaks) return false
        peakTimes.clear()
        quietUntil = timeMillis + cooldownMillis
        // The shake may still be going when the cooldown ends: a new peak needs the magnitude to fall back first.
        armed = false
        return true
    }
}
