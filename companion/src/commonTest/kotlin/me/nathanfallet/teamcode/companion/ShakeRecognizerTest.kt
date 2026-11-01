package me.nathanfallet.teamcode.companion

import me.nathanfallet.teamcode.companion.domain.usecases.shake.ShakeRecognizer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** The shake recognizer on synthetic accelerometer traces (in g, gravity included, one sample every 20 ms as the sensors deliver them). */
class ShakeRecognizerTest {
    /** Feeds [magnitudes] (along z, the other axes at 0) from [start], one every [step] ms; the times at which a shake was recognized. */
    private fun ShakeRecognizer.feed(magnitudes: List<Double>, start: Long = 0, step: Long = 20): List<Long> =
        magnitudes.mapIndexedNotNull { index, g -> (start + index * step).takeIf { onSample(it, 0.0, 0.0, g) } }

    /** One back-and-forth of the hand: up to [peak] g, then down to [trough] g, over 200 ms. */
    private fun swing(peak: Double = 2.8, trough: Double = 0.3) = listOf(1.0, 1.8, peak, peak, 1.8, 1.0, trough, trough, 0.6, 1.0)

    private fun rest(samples: Int) = List(samples) { 1.0 }

    @Test fun aPhoneAtRestIsNotShaken() {
        assertEquals(emptyList(), ShakeRecognizer().feed(rest(500)))
    }

    @Test fun walkingAndRunningStayUnderTheThreshold() {
        // A runner's steps reach about 2 g; many of them, never a shake.
        val steps = List(60) { listOf(1.0, 1.4, 2.0, 1.4, 0.7, 0.5, 0.7, 1.0) }.flatten()
        assertEquals(emptyList(), ShakeRecognizer().feed(steps))
    }

    @Test fun oneBumpOrTwoAreNotAShake() {
        assertEquals(emptyList(), ShakeRecognizer().feed(rest(10) + swing(peak = 4.0) + rest(10)))
        assertEquals(emptyList(), ShakeRecognizer().feed(rest(10) + swing() + swing() + rest(10)))
    }

    @Test fun threeSwingsWithinASecondAreOneShake() {
        // Three swings of 200 ms: peaks at 240, 440 and 640 ms, the shake on the third.
        val shakes = ShakeRecognizer().feed(rest(10) + swing() + swing() + swing() + rest(10))
        assertEquals(listOf(640L), shakes)
    }

    @Test fun axesCountTogether() {
        // 1.4 g on each axis is 2.4 g in all: over the threshold although no axis alone is.
        val recognizer = ShakeRecognizer()
        val hits = (0 until 3).map { swing ->
            val t = swing * 200L
            recognizer.onSample(t, 1.4, 1.4, 1.4).also { recognizer.onSample(t + 100, 0.2, 0.2, 0.2) }
        }
        assertEquals(listOf(false, false, true), hits)
    }

    @Test fun swingsTooFarApartAreNotAShake() {
        // One swing every 600 ms: never three within the 1 s window.
        val slow = List(6) { swing() + rest(20) }.flatten()
        assertEquals(emptyList(), ShakeRecognizer().feed(slow))
    }

    @Test fun jitterAroundTheThresholdIsOnePeak() {
        // Hovering around 2.2 g without falling back under 1.6 g: one peak, however many crossings.
        val hover = List(30) { if (it % 2 == 0) 2.3 else 2.1 }
        assertEquals(emptyList(), ShakeRecognizer().feed(rest(5) + hover + rest(5)))
    }

    @Test fun aLongShakeIsOneShakePerCooldown() {
        // Three seconds of shaking (15 swings): one shake, then the cooldown, then a second one.
        val shakes = ShakeRecognizer().feed(List(15) { swing() }.flatten())
        assertEquals(2, shakes.size, shakes.toString())
        assertEquals(true, shakes[1] - shakes[0] >= 1_500, shakes.toString())
    }

    @Test fun aShakeAfterTheCooldownCountsAgain() {
        val recognizer = ShakeRecognizer()
        val first = recognizer.feed(swing() + swing() + swing())
        val second = recognizer.feed(swing() + swing() + swing(), start = 5_000)
        assertEquals(1, first.size)
        assertEquals(1, second.size)
    }

    @Test fun theRearmLevelMustBeUnderTheThreshold() {
        assertFailsWith<IllegalArgumentException> { ShakeRecognizer(thresholdG = 2.0, rearmG = 2.0) }
    }
}
