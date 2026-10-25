package dev.playground.touchpad.input

import kotlin.math.abs
import kotlin.math.hypot

/**
 * Decides what a touch on the touchpad does, the way laptop touchpads do:
 *
 * - One finger moves the pointer.
 * - Two fingers scroll (both directions), once they have moved together
 *   further than [slopDp]. Until then they might be a two-finger tap.
 * - A finger that lands in the right-edge strip ([edgeWidthDp] wide) scrolls
 *   vertically once it slides along the edge. If it slides sideways first, it
 *   moves the pointer instead, so the strip never feels like a dead zone
 *   (the same rule libinput uses for edge scrolling).
 *
 * Once a touch has started scrolling it keeps scrolling until every finger
 * lifts, so lifting one of two fingers never makes the pointer jump.
 * Positions are in dp; the caller applies acceleration and scroll speed.
 */
class TouchpadGestures(private val slopDp: Float, private val edgeWidthDp: Float) {

    /** One finger in a pointer event. */
    data class Finger(val id: Long, val x: Float, val y: Float, val down: Boolean)

    /** Finger movement to apply: pointer movement or scrolling, in dp. */
    data class Motion(val moveX: Float = 0f, val moveY: Float = 0f, val scrollX: Float = 0f, val scrollY: Float = 0f) {
        companion object {
            val NONE = Motion()
        }
    }

    enum class Mode { POINTER, EDGE_PENDING, EDGE_SCROLL, TWO_FINGER_PENDING, TWO_FINGER_SCROLL }

    var mode = Mode.POINTER
        private set

    private var tracked = 0L
    private val last = HashMap<Long, Pair<Float, Float>>()
    private var startX = 0f
    private var startY = 0f
    private var centroid: Pair<Float, Float>? = null
    private var centroidIds = emptySet<Long>()
    private var pendingX = 0f
    private var pendingY = 0f

    /**
     * A new touch: its first finger landed at ([x], [y]) on a pad [padWidthDp]
     * wide. With [edgeScroll] off, the right edge behaves like the rest.
     */
    fun start(id: Long, x: Float, y: Float, padWidthDp: Float, edgeScroll: Boolean) {
        last.clear()
        last[id] = x to y
        tracked = id
        startX = x
        startY = y
        centroid = null
        val edge = edgeWidthDp.coerceAtMost(padWidthDp * MAX_EDGE_FRACTION)
        mode = if (edgeScroll && x >= padWidthDp - edge) Mode.EDGE_PENDING else Mode.POINTER
    }

    /** The current state of every finger, after each pointer event. */
    fun update(fingers: List<Finger>): Motion {
        val down = fingers.filter { it.down }
        if (down.size >= 2 && (mode == Mode.POINTER || mode == Mode.EDGE_PENDING)) {
            mode = Mode.TWO_FINGER_PENDING
            centroid = null
            pendingX = 0f
            pendingY = 0f
        }
        val motion = when (mode) {
            Mode.POINTER -> trackedDelta(down)?.let { (dx, dy) -> Motion(moveX = dx, moveY = dy) }
            Mode.EDGE_PENDING -> edgePending(down)
            Mode.EDGE_SCROLL -> trackedDelta(down)?.let { (_, dy) -> Motion(scrollY = dy) }
            Mode.TWO_FINGER_PENDING, Mode.TWO_FINGER_SCROLL -> twoFinger(down)
        }
        last.clear()
        for (f in down) last[f.id] = f.x to f.y
        return motion ?: Motion.NONE
    }

    /** Movement of the tracked finger; hands over to another finger if it lifted. */
    private fun trackedDelta(down: List<Finger>): Pair<Float, Float>? {
        val finger = down.firstOrNull { it.id == tracked } ?: down.firstOrNull()?.also { tracked = it.id } ?: return null
        val (lx, ly) = last[finger.id] ?: return null // a new finger: no jump
        return (finger.x - lx) to (finger.y - ly)
    }

    private fun edgePending(down: List<Finger>): Motion? {
        val finger = down.firstOrNull { it.id == tracked } ?: return null
        val totalX = finger.x - startX
        val totalY = finger.y - startY
        if (hypot(totalX, totalY) <= slopDp) return null
        return if (abs(totalY) >= abs(totalX)) {
            mode = Mode.EDGE_SCROLL
            Motion(scrollY = totalY)
        } else {
            mode = Mode.POINTER
            Motion(moveX = totalX, moveY = totalY)
        }
    }

    private fun twoFinger(down: List<Finger>): Motion? {
        if (down.size < 2) {
            // Down to one finger: wait (no pointer jump) until all lift.
            centroid = null
            return null
        }
        val ids = down.map { it.id }.toSet()
        val now = down.sumOf { it.x.toDouble() }.toFloat() / down.size to down.sumOf { it.y.toDouble() }.toFloat() / down.size
        val previous = centroid
        centroid = now
        // A finger was added or lifted: the centre jumps, so start again from here.
        if (previous == null || ids != centroidIds) {
            centroidIds = ids
            return null
        }
        val dx = now.first - previous.first
        val dy = now.second - previous.second
        if (mode == Mode.TWO_FINGER_PENDING) {
            pendingX += dx
            pendingY += dy
            if (hypot(pendingX, pendingY) <= slopDp) return null
            mode = Mode.TWO_FINGER_SCROLL
            return Motion(scrollX = pendingX, scrollY = pendingY)
        }
        return Motion(scrollX = dx, scrollY = dy)
    }

    companion object {
        /** The edge strip never takes more than this share of the pad. */
        const val MAX_EDGE_FRACTION = 0.2f
    }
}
