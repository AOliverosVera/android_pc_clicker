package dev.playground.touchpad.input

/**
 * Turns finger movement (in dp) into mouse counts, like a laptop touchpad:
 * slow movement is precise and fast flicks travel far.
 *
 * Windows applies its own pointer speed and "Enhance pointer precision" on top
 * of this, so the curve is deliberately gentle. Tune the constants here.
 */
class PointerAccelerator {
    private var smoothedSpeed = 0f

    /** Multiplies every movement (the "speed" slider). */
    var speed = 1f

    /** Scales how much extra fast movement gets (the "acceleration" slider); 0 is none. */
    var acceleration = 1f

    fun reset() {
        smoothedSpeed = 0f
    }

    /** Returns the gain to multiply this movement by. [dtMs] is the time since the previous sample. */
    fun gainFor(dxDp: Float, dyDp: Float, dtMs: Long): Float {
        val dt = dtMs.coerceIn(1L, 100L).toFloat()
        val speed = kotlin.math.sqrt(dxDp * dxDp + dyDp * dyDp) / dt // dp per ms
        // Touch samples arrive unevenly; smoothing keeps the gain from flickering.
        smoothedSpeed += (speed - smoothedSpeed) * SMOOTHING
        return this.speed * gain(smoothedSpeed, acceleration)
    }

    companion object {
        const val MIN_GAIN = 1.4f
        const val MAX_GAIN = 4.0f
        /** Below this speed (dp/ms) movement stays at [MIN_GAIN]. */
        const val SLOW = 0.15f
        /** At or above this speed movement reaches [MAX_GAIN]. */
        const val FAST = 1.6f
        private const val SMOOTHING = 0.35f

        fun gain(speedDpPerMs: Float, acceleration: Float = 1f): Float {
            val t = ((speedDpPerMs - SLOW) / (FAST - SLOW)).coerceIn(0f, 1f)
            val eased = t * t * (3 - 2 * t) // smoothstep
            return MIN_GAIN + (MAX_GAIN - MIN_GAIN) * eased * acceleration
        }
    }
}
