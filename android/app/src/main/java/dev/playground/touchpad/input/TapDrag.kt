package dev.playground.touchpad.input

/** What happens when the finger lifts in the middle of a drag. */
enum class DragLock {
    /** The item is dropped (Windows' and libinput's default). */
    OFF,
    /** The drag survives a short lift, to reposition the finger; a tap drops it sooner. */
    TIMEOUT,
    /** The drag continues until the next tap (macOS "with drag lock"). */
    STICKY,
}

/**
 * Click-and-hold without a physical button, the way laptop touchpads do it.
 *
 * **Tap-and-drag** (Windows "tap twice and drag", libinput, macOS): tap, then
 * touch again within [waitMs] and move. The left button stays held while the
 * finger moves and is released when it lifts.
 *
 * The first tap presses the button immediately but delays the release by
 * [waitMs], as libinput does. Clicking at once would make the second touch a
 * double-click on Windows, which opens icons and maximizes windows instead of
 * dragging them. A quick second tap still gives a normal double-click.
 *
 * **Drag lock** ([DragLock]) lets a drag survive lifting the finger.
 * **Three-finger drag** (macOS) holds the button while three fingers move;
 * [TouchpadGestures] detects it and reports it through [onThreeFingerDrag].
 *
 * Pure logic with explicit time. The caller sends the returned [Action]s, and
 * calls [onTimeout] once the clock reaches [deadline].
 */
class TapDrag(
    private val waitMs: Long = TAP_DRAG_WAIT_MS,
    private val holdMs: Long = HOLD_TO_DRAG_MS,
    private val lockMs: Long = DRAG_LOCK_MS,
) {
    enum class Action { LEFT_DOWN, LEFT_UP, LEFT_CLICK, RIGHT_CLICK }

    enum class State {
        IDLE,
        /** A tap pressed the button; waiting to see if another touch follows. */
        TAPPED,
        /** Touching again after a tap: a drag if it moves or holds, a double-click if it lifts. */
        TAP_TOUCH,
        DRAGGING,
        /** Drag lock: the finger lifted mid-drag and the button is still held. */
        LOCKED,
        /** Touching again during drag lock: moving continues, a tap drops. */
        LOCKED_TOUCH,
        THREE_FINGER_DRAG,
    }

    var state = State.IDLE
        private set

    /** When [onTimeout] must be called (uptime ms), or null if nothing is pending. */
    var deadline: Long? = null
        private set

    /** Turn tap-and-drag off and taps click immediately, with no added delay. */
    var tapToDrag = true
    var dragLock = DragLock.OFF

    /** True while this machine holds the left button down. */
    val holding: Boolean get() = state != State.IDLE

    /** True if the current touch may only move the pointer (it is, or may become, a drag). */
    val pointerOnly: Boolean get() = state == State.TAP_TOUCH || state == State.LOCKED_TOUCH || state == State.DRAGGING

    val dragging: Boolean get() = state == State.DRAGGING || state == State.THREE_FINGER_DRAG

    /** The first finger of a new touch landed. */
    fun onTouchStart(now: Long): List<Action> {
        when (state) {
            State.TAPPED -> go(State.TAP_TOUCH, now + holdMs)
            State.LOCKED -> go(State.LOCKED_TOUCH, null)
            else -> Unit
        }
        return emptyList()
    }

    /** The current touch's finger moved further than the tap slop. */
    fun onMoved(): List<Action> {
        if (state == State.TAP_TOUCH || state == State.LOCKED_TOUCH) go(State.DRAGGING, null)
        return emptyList()
    }

    /** Three fingers started moving together (three-finger drag is on). */
    fun onThreeFingerDrag(): List<Action> {
        if (state != State.IDLE) return emptyList()
        go(State.THREE_FINGER_DRAG, null)
        return listOf(Action.LEFT_DOWN)
    }

    /** Every finger lifted. [tap] is what [TapDetector] made of the touch. */
    fun onTouchEnd(tap: TapDetector.Tap?, now: Long): List<Action> = when (state) {
        State.IDLE -> when (tap) {
            TapDetector.Tap.LEFT -> if (tapToDrag) {
                go(State.TAPPED, now + waitMs)
                listOf(Action.LEFT_DOWN)
            } else {
                listOf(Action.LEFT_CLICK)
            }
            TapDetector.Tap.RIGHT -> listOf(Action.RIGHT_CLICK)
            null -> emptyList()
        }
        State.TAP_TOUCH -> when (tap) {
            // Second quick tap: finish the first click and start a second one,
            // which Windows reads as a double-click. Stay ready for a third.
            TapDetector.Tap.LEFT -> {
                go(State.TAPPED, now + waitMs)
                listOf(Action.LEFT_UP, Action.LEFT_DOWN)
            }
            TapDetector.Tap.RIGHT -> release(Action.RIGHT_CLICK)
            null -> endDrag(now)
        }
        State.DRAGGING, State.THREE_FINGER_DRAG -> endDrag(now)
        // A tap during drag lock drops the item; anything longer keeps the lock.
        State.LOCKED_TOUCH -> if (tap != null) release() else endDrag(now)
        State.TAPPED, State.LOCKED -> emptyList() // no touch was in progress
    }

    fun onTimeout(now: Long): List<Action> {
        val due = deadline ?: return emptyList()
        if (now < due) return emptyList()
        return when (state) {
            State.TAPPED, State.LOCKED -> release() // the click completes / the lock expires
            State.TAP_TOUCH -> {
                // Held still after a tap: that's a press-and-hold, so it drags.
                go(State.DRAGGING, null)
                emptyList()
            }
            else -> {
                deadline = null
                emptyList()
            }
        }
    }

    /** The touchpad went away (app backgrounded, screen rotated): never leave the button held. */
    fun cancel(): List<Action> = if (holding) release() else emptyList()

    private fun endDrag(now: Long): List<Action> = when (dragLock) {
        DragLock.OFF -> release()
        DragLock.TIMEOUT -> {
            go(State.LOCKED, now + lockMs)
            emptyList()
        }
        DragLock.STICKY -> {
            go(State.LOCKED, null)
            emptyList()
        }
    }

    private fun release(then: Action? = null): List<Action> {
        go(State.IDLE, null)
        return listOfNotNull(Action.LEFT_UP, then)
    }

    private fun go(next: State, until: Long?) {
        state = next
        deadline = until
    }

    companion object {
        /** After a tap, a new touch within this time starts a drag or a double-click (libinput: 180 ms). */
        const val TAP_DRAG_WAIT_MS = 200L
        /** A second touch held still this long becomes a drag. */
        const val HOLD_TO_DRAG_MS = 200L
        /** Drag lock (timeout mode): how long the finger may be lifted. */
        const val DRAG_LOCK_MS = 600L
    }
}

/**
 * The left button can be held by several things at once (the on-screen Left
 * button and a touchpad drag). The PC sees a press when the first one holds it
 * and a release when the last one lets go, so neither cancels the other.
 */
class ButtonHolds {
    private val holders = HashSet<String>()

    /** Returns true if this is the first holder, i.e. the button must be pressed now. */
    fun hold(who: String): Boolean {
        val wasFree = holders.isEmpty()
        holders.add(who)
        return wasFree
    }

    /** Returns true if this was the last holder, i.e. the button must be released now. */
    fun release(who: String): Boolean = holders.remove(who) && holders.isEmpty()

    val held: Boolean get() = holders.isNotEmpty()
}
