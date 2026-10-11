package dev.panasko.photoliz.util

import android.content.Context
import android.view.OrientationEventListener

/**
 * Physical device orientation in 90° steps (0, 90, 180, 270), independent of the rotation lock,
 * with hysteresis so the UI does not flicker near the diagonals.
 */
class OrientationTracker(context: Context, private val onChange: (Int) -> Unit) {
    var orientation = 0
        private set
    var raw = 0
        private set

    private val listener = object : OrientationEventListener(context.applicationContext) {
        override fun onOrientationChanged(deg: Int) {
            if (deg == ORIENTATION_UNKNOWN) return
            raw = deg
            val next = snap(deg, orientation)
            if (next != orientation) {
                orientation = next
                onChange(next)
            }
        }
    }

    private fun snap(deg: Int, current: Int): Int {
        // Stay in the current quadrant until we are 20° past the diagonal.
        var diff = deg - current
        while (diff > 180) diff -= 360
        while (diff < -180) diff += 360
        if (kotlin.math.abs(diff) < 65) return current
        return ((deg + 45) / 90 * 90) % 360
    }

    fun start() { if (listener.canDetectOrientation()) listener.enable() }
    fun stop() = listener.disable()
}
