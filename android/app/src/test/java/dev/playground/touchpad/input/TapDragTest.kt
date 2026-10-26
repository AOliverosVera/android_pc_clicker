package dev.playground.touchpad.input

import dev.playground.touchpad.input.TapDetector.Tap
import dev.playground.touchpad.input.TapDrag.Action.LEFT_CLICK
import dev.playground.touchpad.input.TapDrag.Action.LEFT_DOWN
import dev.playground.touchpad.input.TapDrag.Action.LEFT_UP
import dev.playground.touchpad.input.TapDrag.Action.RIGHT_CLICK
import dev.playground.touchpad.input.TapDrag.State
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TapDragTest {
    private val wait = TapDrag.TAP_DRAG_WAIT_MS
    private val hold = TapDrag.HOLD_TO_DRAG_MS
    private val lock = TapDrag.DRAG_LOCK_MS
    private val d = TapDrag()

    /** A quick tap at [t]: touch and lift. */
    private fun tap(t: Long, which: Tap = Tap.LEFT) = d.onTouchStart(t) + d.onTouchEnd(which, t + 50)

    @Test fun aSingleTapIsAClickWhoseReleaseWaitsForAPossibleDrag() {
        assertEquals(listOf(LEFT_DOWN), tap(0))
        assertEquals(State.TAPPED, d.state)
        assertEquals(50 + wait, d.deadline)
        assertEquals(emptyList<TapDrag.Action>(), d.onTimeout(50 + wait - 1)) // too early
        assertEquals(listOf(LEFT_UP), d.onTimeout(50 + wait))
        assertEquals(State.IDLE, d.state)
        assertNull(d.deadline)
    }

    @Test fun withTapToDragOffATapClicksImmediately() {
        d.tapToDrag = false
        assertEquals(listOf(LEFT_CLICK), tap(0))
        assertFalse(d.holding)
    }

    @Test fun twoFingerTapIsARightClick() {
        assertEquals(listOf(RIGHT_CLICK), tap(0, Tap.RIGHT))
        assertFalse(d.holding)
    }

    @Test fun tapThenTouchAndMoveDragsUntilTheFingerLifts() {
        tap(0)
        assertEquals(emptyList<TapDrag.Action>(), d.onTouchStart(100))
        assertTrue("the drag touch only moves the pointer", d.pointerOnly)
        d.onMoved()
        assertEquals(State.DRAGGING, d.state)
        assertTrue(d.holding)
        assertNull(d.deadline)
        // The button stays down for the whole drag, then lifting drops it.
        assertEquals(listOf(LEFT_UP), d.onTouchEnd(null, 2_000))
        assertFalse(d.holding)
    }

    @Test fun tapThenTouchAndHoldStillAlsoDrags() {
        tap(0)
        d.onTouchStart(100)
        assertEquals(emptyList<TapDrag.Action>(), d.onTimeout(100 + hold))
        assertEquals(State.DRAGGING, d.state)
    }

    @Test fun twoQuickTapsAreADoubleClickWithNoExtraPress() {
        val actions = tap(0) + tap(120) + d.onTimeout(170 + wait)
        assertEquals(listOf(LEFT_DOWN, LEFT_UP, LEFT_DOWN, LEFT_UP), actions)
        assertFalse(d.holding)
    }

    @Test fun threeQuickTapsAreATripleClick() {
        val actions = tap(0) + tap(120) + tap(240) + d.onTimeout(290 + wait)
        assertEquals(listOf(LEFT_DOWN, LEFT_UP, LEFT_DOWN, LEFT_UP, LEFT_DOWN, LEFT_UP), actions)
    }

    @Test fun aTouchAfterTheWaitIsJustANewTouch() {
        tap(0)
        d.onTimeout(50 + wait) // the click completed
        d.onTouchStart(1_000)
        assertFalse(d.pointerOnly)
        d.onMoved()
        assertFalse(d.holding)
    }

    @Test fun twoFingerTapDuringTheWaitFinishesTheClickThenRightClicks() {
        tap(0)
        d.onTouchStart(100)
        assertEquals(listOf(LEFT_UP, RIGHT_CLICK), d.onTouchEnd(Tap.RIGHT, 150))
        assertFalse(d.holding)
    }

    @Test fun timeoutDragLockSurvivesALiftAndDropsAfterTheTimeout() {
        d.dragLock = DragLock.TIMEOUT
        tap(0)
        d.onTouchStart(100)
        d.onMoved()
        assertEquals(emptyList<TapDrag.Action>(), d.onTouchEnd(null, 1_000))
        assertEquals(State.LOCKED, d.state)
        assertTrue(d.holding)
        assertEquals(listOf(LEFT_UP), d.onTimeout(1_000 + lock))
    }

    @Test fun timeoutDragLockContinuesWhenTheFingerComesBackAndMoves() {
        d.dragLock = DragLock.TIMEOUT
        tap(0)
        d.onTouchStart(100)
        d.onMoved()
        d.onTouchEnd(null, 1_000)
        d.onTouchStart(1_200) // back within the lock time
        assertNull("the lock timer stops while touching", d.deadline)
        d.onMoved()
        assertEquals(State.DRAGGING, d.state)
        assertEquals(emptyList<TapDrag.Action>(), d.onTimeout(1_000 + lock))
        assertTrue(d.holding)
    }

    @Test fun aTapDuringDragLockDropsImmediately() {
        d.dragLock = DragLock.TIMEOUT
        tap(0)
        d.onTouchStart(100)
        d.onMoved()
        d.onTouchEnd(null, 1_000)
        d.onTouchStart(1_100)
        assertEquals(listOf(LEFT_UP), d.onTouchEnd(Tap.LEFT, 1_150))
        assertFalse(d.holding)
    }

    @Test fun stickyDragLockHasNoTimeoutAndEndsWithATap() {
        d.dragLock = DragLock.STICKY
        tap(0)
        d.onTouchStart(100)
        d.onMoved()
        d.onTouchEnd(null, 1_000)
        assertNull(d.deadline)
        assertTrue(d.holding)
        d.onTouchStart(60_000)
        assertEquals(listOf(LEFT_UP), d.onTouchEnd(Tap.LEFT, 60_050))
    }

    @Test fun threeFingerDragHoldsWhileTheFingersAreDown() {
        d.onTouchStart(0)
        assertEquals(listOf(LEFT_DOWN), d.onThreeFingerDrag())
        assertTrue(d.dragging)
        assertEquals(listOf(LEFT_UP), d.onTouchEnd(null, 900))
    }

    @Test fun threeFingerDragIsIgnoredWhileAlreadyHolding() {
        tap(0)
        d.onTouchStart(100)
        assertEquals(emptyList<TapDrag.Action>(), d.onThreeFingerDrag())
    }

    @Test fun cancelAlwaysReleasesAHeldButton() {
        for (lockMode in DragLock.entries) {
            val m = TapDrag().apply { dragLock = lockMode }
            m.onTouchStart(0)
            m.onTouchEnd(Tap.LEFT, 50)
            m.onTouchStart(100)
            m.onMoved()
            m.onTouchEnd(null, 500)
            val released = m.cancel()
            assertEquals(lockMode == DragLock.OFF, released.isEmpty()) // OFF already dropped on lift
            assertFalse(m.holding)
        }
        assertEquals(emptyList<TapDrag.Action>(), TapDrag().cancel())
    }

    @Test fun everyPressIsMatchedByARelease() {
        // Whatever the sequence, the machine never leaves the button down at rest.
        d.dragLock = DragLock.TIMEOUT
        val all = tap(0) + tap(100) + d.onTouchStart(200) + d.onMoved() + d.onTouchEnd(null, 900) +
            d.onTimeout(900 + lock) + tap(2_000, Tap.RIGHT) + d.cancel()
        assertEquals(all.count { it == LEFT_DOWN }, all.count { it == LEFT_UP })
        assertFalse(d.holding)
    }
}

class ButtonHoldsTest {
    @Test fun pressesOnFirstHolderAndReleasesOnLast() {
        val h = ButtonHolds()
        assertTrue(h.hold("touchpad"))
        assertFalse("already held: no second press", h.hold("button"))
        assertFalse("still held by the button", h.release("touchpad"))
        assertTrue(h.release("button"))
        assertFalse(h.held)
    }

    @Test fun repeatedHoldOrReleaseBySameHolderIsHarmless() {
        val h = ButtonHolds()
        assertTrue(h.hold("a"))
        assertFalse(h.hold("a"))
        assertTrue(h.release("a"))
        assertFalse("not held: nothing to release", h.release("a"))
    }
}
