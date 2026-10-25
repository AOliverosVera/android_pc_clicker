package dev.playground.touchpad.input

import dev.playground.touchpad.input.TouchpadGestures.Finger
import dev.playground.touchpad.input.TouchpadGestures.Mode
import dev.playground.touchpad.input.TouchpadGestures.Motion
import org.junit.Assert.assertEquals
import org.junit.Test

class TouchpadGesturesTest {
    /** A 300 dp wide pad: the edge strip is x >= 252. */
    private val g = TouchpadGestures(slopDp = 8f, edgeWidthDp = 48f)
    private val width = 300f

    private fun f(id: Long, x: Float, y: Float, down: Boolean = true) = Finger(id, x, y, down)

    @Test fun oneFingerMovesThePointer() {
        g.start(1, 100f, 100f, width, edgeScroll = true)
        assertEquals(Motion(moveX = 5f, moveY = -2f), g.update(listOf(f(1, 105f, 98f))))
        assertEquals(Motion(moveX = 1f, moveY = 0f), g.update(listOf(f(1, 106f, 98f))))
        assertEquals(Mode.POINTER, g.mode)
    }

    @Test fun twoFingersScrollOnceTheyMovePastTheSlop() {
        g.start(1, 100f, 100f, width, edgeScroll = true)
        assertEquals(Motion.NONE, g.update(listOf(f(1, 100f, 100f), f(2, 150f, 100f))))
        assertEquals(Mode.TWO_FINGER_PENDING, g.mode)
        // Small movement: could still be a two-finger tap.
        assertEquals(Motion.NONE, g.update(listOf(f(1, 100f, 104f), f(2, 150f, 104f))))
        // Past the slop: everything moved so far is scrolled at once.
        assertEquals(Motion(scrollY = 10f), g.update(listOf(f(1, 100f, 110f), f(2, 150f, 110f))))
        assertEquals(Mode.TWO_FINGER_SCROLL, g.mode)
        assertEquals(Motion(scrollX = -3f, scrollY = 5f), g.update(listOf(f(1, 97f, 115f), f(2, 147f, 115f))))
    }

    @Test fun twoFingerScrollUsesTheCentreOfTheFingers() {
        g.start(1, 100f, 100f, width, edgeScroll = true)
        g.update(listOf(f(1, 100f, 100f), f(2, 150f, 100f)))
        g.update(listOf(f(1, 100f, 120f), f(2, 150f, 120f))) // starts scrolling
        // Only one finger moves 20 dp: the centre moves 10.
        assertEquals(Motion(scrollY = 10f), g.update(listOf(f(1, 100f, 140f), f(2, 150f, 120f))))
    }

    @Test fun liftingOneOfTwoFingersNeverMovesThePointer() {
        g.start(1, 100f, 100f, width, edgeScroll = true)
        g.update(listOf(f(1, 100f, 100f), f(2, 150f, 100f)))
        g.update(listOf(f(1, 100f, 130f), f(2, 150f, 130f)))
        assertEquals(Motion.NONE, g.update(listOf(f(1, 100f, 130f, down = false), f(2, 150f, 140f))))
        assertEquals(Motion.NONE, g.update(listOf(f(2, 150f, 160f))))
        // A finger landing again resumes scrolling from where it is, without a jump.
        assertEquals(Motion.NONE, g.update(listOf(f(2, 150f, 160f), f(3, 90f, 160f))))
        assertEquals(Motion(scrollY = 2f), g.update(listOf(f(2, 150f, 162f), f(3, 90f, 162f))))
    }

    @Test fun aSecondFingerStopsThePointer() {
        g.start(1, 100f, 100f, width, edgeScroll = true)
        g.update(listOf(f(1, 110f, 100f)))
        assertEquals(Motion.NONE, g.update(listOf(f(1, 112f, 100f), f(2, 160f, 100f))))
        assertEquals(Mode.TWO_FINGER_PENDING, g.mode)
    }

    @Test fun rightEdgeScrollsVertically() {
        g.start(1, 280f, 100f, width, edgeScroll = true)
        assertEquals(Mode.EDGE_PENDING, g.mode)
        assertEquals(Motion.NONE, g.update(listOf(f(1, 281f, 104f))))
        assertEquals(Motion(scrollY = 12f), g.update(listOf(f(1, 282f, 112f))))
        assertEquals(Mode.EDGE_SCROLL, g.mode)
        // Sideways drift is ignored, even off the strip.
        assertEquals(Motion(scrollY = -5f), g.update(listOf(f(1, 200f, 107f))))
    }

    @Test fun slidingSidewaysOutOfTheEdgeMovesThePointer() {
        g.start(1, 280f, 100f, width, edgeScroll = true)
        assertEquals(Motion(moveX = -12f, moveY = 2f), g.update(listOf(f(1, 268f, 102f))))
        assertEquals(Mode.POINTER, g.mode)
        assertEquals(Motion(moveX = -3f, moveY = 0f), g.update(listOf(f(1, 265f, 102f))))
    }

    @Test fun edgeIsOffWhenDisabledAndCappedOnNarrowPads() {
        g.start(1, 290f, 100f, width, edgeScroll = false)
        assertEquals(Mode.POINTER, g.mode)
        // 100 dp wide pad: the strip is 20 dp, not 48.
        g.start(1, 75f, 100f, 100f, edgeScroll = true)
        assertEquals(Mode.POINTER, g.mode)
        g.start(1, 85f, 100f, 100f, edgeScroll = true)
        assertEquals(Mode.EDGE_PENDING, g.mode)
    }

    @Test fun liftingTheEdgeFingerStopsScrolling() {
        g.start(1, 280f, 100f, width, edgeScroll = true)
        g.update(listOf(f(1, 280f, 120f)))
        assertEquals(Mode.EDGE_SCROLL, g.mode)
        assertEquals(Motion.NONE, g.update(listOf(f(1, 280f, 120f, down = false))))
    }

    @Test fun eachTouchStartsFresh() {
        g.start(1, 100f, 100f, width, edgeScroll = true)
        g.update(listOf(f(1, 100f, 100f), f(2, 150f, 100f)))
        g.update(listOf(f(1, 100f, 130f), f(2, 150f, 130f)))
        g.start(3, 100f, 100f, width, edgeScroll = true)
        assertEquals(Mode.POINTER, g.mode)
        assertEquals(Motion(moveX = 4f, moveY = 0f), g.update(listOf(f(3, 104f, 100f))))
    }
}
