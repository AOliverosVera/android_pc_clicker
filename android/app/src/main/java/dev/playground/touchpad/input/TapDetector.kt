package dev.playground.touchpad.input

import kotlin.math.hypot

/**
 * Recognises taps on the touchpad, like a laptop's: a quick touch with one
 * finger is a left click, with two fingers a right click. A touch only counts
 * if every finger lifted within [maxDurationMs] of the first one landing and
 * none of them slid more than [slopPx], so moving the pointer never clicks.
 */
class TapDetector(private val slopPx: Float, private val maxDurationMs: Long = 250) {

    enum class Tap { LEFT, RIGHT }

    private val startOf = HashMap<Long, Pair<Float, Float>>()
    private val down = HashSet<Long>()
    private var startedAt = 0L
    private var mostFingers = 0
    /** True once any finger of the current touch slid further than the slop. */
    var moved = false
        private set

    fun onDown(id: Long, x: Float, y: Float, time: Long) {
        if (down.isEmpty()) {
            // A new touch begins.
            startOf.clear()
            startedAt = time
            mostFingers = 0
            moved = false
        }
        down += id
        startOf[id] = x to y
        mostFingers = maxOf(mostFingers, down.size)
    }

    fun onMove(id: Long, x: Float, y: Float) {
        val (sx, sy) = startOf[id] ?: return
        if (hypot(x - sx, y - sy) > slopPx) moved = true
    }

    /** Returns the tap once the last finger lifts, or null if it wasn't one. */
    fun onUp(id: Long, time: Long): Tap? {
        if (!down.remove(id) || down.isNotEmpty()) return null
        if (moved || time - startedAt > maxDurationMs) return null
        return when (mostFingers) {
            1 -> Tap.LEFT
            2 -> Tap.RIGHT
            else -> null
        }
    }

    /** The touch was interrupted (e.g. the screen rotated): no tap. */
    fun cancel() {
        down.clear()
        startOf.clear()
    }
}
