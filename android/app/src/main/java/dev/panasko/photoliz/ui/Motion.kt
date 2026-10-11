package dev.panasko.photoliz.ui

import android.animation.TimeInterpolator
import android.content.Context
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.animation.PathInterpolator
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sqrt

/** Damped spring, integrated with small fixed sub-steps (stable at any frame rate). */
class Spring(initial: Float, var stiffness: Float = 420f, var damping: Float = 0.86f, private val epsilon: Float = 0.001f) {
    var value = initial
        private set
    var target = initial
    var velocity = 0f

    val isMoving: Boolean get() = value != target || velocity != 0f

    fun snap(v: Float) { value = v; target = v; velocity = 0f }

    /** @return true while still moving. */
    fun step(dtSeconds: Float): Boolean {
        if (!isMoving) return false
        var dt = dtSeconds.coerceIn(0f, 0.064f)
        val c = 2f * damping * sqrt(stiffness)
        while (dt > 0f) {
            val h = min(dt, 1f / 240f)
            val a = -stiffness * (value - target) - c * velocity
            velocity += a * h
            value += velocity * h
            dt -= h
        }
        val scale = maxOf(1f, abs(target))
        if (abs(value - target) < epsilon * scale && abs(velocity) < epsilon * 20f * scale) {
            value = target
            velocity = 0f
            return false
        }
        return true
    }
}

/** Base for custom-drawn views that animate springs on the display's frame clock. */
abstract class AnimatedView(context: Context) : View(context) {
    private val springs = ArrayList<Spring>()
    private var lastFrameNs = 0L

    protected fun spring(initial: Float, stiffness: Float = 420f, damping: Float = 0.86f): Spring =
        Spring(initial, stiffness, damping).also { springs.add(it) }

    protected fun kick() {
        if (lastFrameNs == 0L) lastFrameNs = System.nanoTime()
        postInvalidateOnAnimation()
    }

    /** Call first thing in onDraw. */
    protected fun advance() {
        val now = System.nanoTime()
        val dt = if (lastFrameNs == 0L) 0f else (now - lastFrameNs) / 1e9f
        lastFrameNs = now
        var moving = false
        for (s in springs) if (s.step(dt)) moving = true
        if (moving || keepAnimating()) postInvalidateOnAnimation() else lastFrameNs = 0L
    }

    /** Override to request frames for time-based effects (spinners, blinking). */
    protected open fun keepAnimating(): Boolean = false
}

object Motion {
    /** "Emphasized" curves: fast start, long soft landing. */
    val EMPHASIZED: TimeInterpolator = PathInterpolator(0.2f, 0f, 0f, 1f)
    val DECELERATE: TimeInterpolator = PathInterpolator(0.05f, 0.7f, 0.1f, 1f)
    val ACCELERATE: TimeInterpolator = PathInterpolator(0.3f, 0f, 0.8f, 0.15f)
    val OVERSHOOT: TimeInterpolator = PathInterpolator(0.34f, 1.36f, 0.64f, 1f)

    const val FAST = 160L
    const val MEDIUM = 260L
    const val SLOW = 380L

    /** Shortest-path angle target, so rotations never spin the long way round. */
    fun nearestAngle(current: Float, target: Float): Float {
        var t = target
        while (t - current > 180f) t -= 360f
        while (t - current < -180f) t += 360f
        return t
    }
}

object Haptics {
    fun tick(v: View) { v.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK) }
    fun confirm(v: View) { v.performHapticFeedback(HapticFeedbackConstants.CONFIRM) }
    fun reject(v: View) { v.performHapticFeedback(HapticFeedbackConstants.REJECT) }
    fun heavy(v: View) { v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS) }
    fun press(v: View) { v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY) }
}
