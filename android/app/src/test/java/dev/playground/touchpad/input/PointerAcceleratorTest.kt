package dev.playground.touchpad.input

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PointerAcceleratorTest {

    @Test fun slowMovementUsesTheMinimumGain() =
        assertEquals(PointerAccelerator.MIN_GAIN, PointerAccelerator.gain(0.05f), 0.0001f)

    @Test fun fastMovementUsesTheMaximumGain() =
        assertEquals(PointerAccelerator.MAX_GAIN, PointerAccelerator.gain(5f), 0.0001f)

    @Test fun gainNeverDecreasesWithSpeed() {
        var previous = 0f
        var speed = 0f
        while (speed <= 3f) {
            val gain = PointerAccelerator.gain(speed)
            assertTrue("gain dropped at $speed", gain >= previous)
            previous = gain
            speed += 0.01f
        }
    }

    @Test fun smoothingRampsUpInsteadOfJumping() {
        val accelerator = PointerAccelerator()
        // A sudden fast sample after rest should not hit full gain immediately.
        val first = accelerator.gainFor(dxDp = 30f, dyDp = 0f, dtMs = 8)
        assertTrue(first < PointerAccelerator.MAX_GAIN)
        repeat(30) { accelerator.gainFor(30f, 0f, 8) }
        assertEquals(PointerAccelerator.MAX_GAIN, accelerator.gainFor(30f, 0f, 8), 0.01f)
    }

    @Test fun resetForgetsPreviousSpeed() {
        val accelerator = PointerAccelerator()
        repeat(30) { accelerator.gainFor(30f, 0f, 8) }
        accelerator.reset()
        assertEquals(PointerAccelerator.MIN_GAIN, accelerator.gainFor(0.1f, 0f, 8), 0.0001f)
    }

    @Test fun zeroOrHugeTimeStepsAreClamped() {
        val accelerator = PointerAccelerator()
        val gain = accelerator.gainFor(1f, 1f, dtMs = 0)
        assertTrue(gain.isFinite())
    }
}
