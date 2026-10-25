package dev.playground.touchpad.input

import dev.playground.touchpad.input.TapDetector.Tap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TapDetectorTest {
    private val taps = TapDetector(slopPx = 10f, maxDurationMs = 250)

    @Test fun quickOneFingerTouchIsALeftClick() {
        taps.onDown(1, 100f, 100f, 0)
        taps.onMove(1, 103f, 98f) // a little jitter is fine
        assertEquals(Tap.LEFT, taps.onUp(1, 120))
    }

    @Test fun quickTwoFingerTouchIsARightClick() {
        taps.onDown(1, 100f, 100f, 0)
        taps.onDown(2, 160f, 100f, 30)
        assertNull("one finger still down", taps.onUp(1, 110))
        assertEquals(Tap.RIGHT, taps.onUp(2, 130))
    }

    @Test fun slidingIsNotATap() {
        taps.onDown(1, 100f, 100f, 0)
        taps.onMove(1, 115f, 100f)
        taps.onMove(1, 101f, 100f) // coming back doesn't undo the slide
        assertNull(taps.onUp(1, 100))
    }

    @Test fun slidingOneOfTwoFingersIsNotATap() {
        taps.onDown(1, 100f, 100f, 0)
        taps.onDown(2, 160f, 100f, 10)
        taps.onMove(2, 160f, 130f)
        taps.onUp(1, 100)
        assertNull(taps.onUp(2, 120))
    }

    @Test fun holdingTooLongIsNotATap() {
        taps.onDown(1, 100f, 100f, 0)
        assertNull(taps.onUp(1, 400))
    }

    @Test fun threeFingersDoNothing() {
        taps.onDown(1, 0f, 0f, 0)
        taps.onDown(2, 50f, 0f, 5)
        taps.onDown(3, 100f, 0f, 10)
        taps.onUp(1, 80)
        taps.onUp(2, 85)
        assertNull(taps.onUp(3, 90))
    }

    @Test fun eachTouchStartsFresh() {
        taps.onDown(1, 100f, 100f, 0)
        taps.onMove(1, 200f, 100f)
        assertNull(taps.onUp(1, 100))
        // The next touch isn't affected by the previous slide.
        taps.onDown(2, 100f, 100f, 1_000)
        assertEquals(Tap.LEFT, taps.onUp(2, 1_100))
        // Nor by the previous touch's finger count.
        taps.onDown(3, 0f, 0f, 2_000)
        taps.onDown(4, 50f, 0f, 2_010)
        taps.onUp(3, 2_100)
        assertEquals(Tap.RIGHT, taps.onUp(4, 2_110))
        taps.onDown(5, 0f, 0f, 3_000)
        assertEquals(Tap.LEFT, taps.onUp(5, 3_100))
    }

    @Test fun cancelledTouchIsNotATap() {
        taps.onDown(1, 100f, 100f, 0)
        taps.cancel()
        assertNull(taps.onUp(1, 50))
    }
}
