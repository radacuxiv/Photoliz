package dev.panasko.photoliz.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.view.MotionEvent
import android.view.VelocityTracker
import kotlin.math.abs

class DialStop(val pos: Float, val label: String?, val major: Boolean)

/**
 * A ruler. Positions are abstract units; [unitDp] is the on-screen spacing of one unit.
 * Discrete dials snap to stops; continuous dials (ISO) glide freely and only snap magnetically.
 */
class DialModel(
    val key: String,
    val stops: List<DialStop>,
    val continuous: Boolean,
    val autoPos: Float?,
    val unitDp: Float,
    val snapThreshold: Float,
) {
    val minPos: Float = autoPos ?: stops.firstOrNull()?.pos ?: 0f
    val maxPos: Float = stops.lastOrNull()?.pos ?: 0f

    fun settle(projected: Float): Float {
        val p = projected.coerceIn(minPos, maxPos)
        val first = stops.firstOrNull() ?: return autoPos ?: 0f
        if (autoPos != null && p < (autoPos + first.pos) / 2f) return autoPos
        val nearest = stops.minByOrNull { abs(it.pos - p) } ?: return p
        return if (!continuous || abs(nearest.pos - p) <= snapThreshold) nearest.pos else p
    }

    /** Index of the detent at [p] (auto = -1), used for haptic ticks. */
    fun detent(p: Float): Int {
        val first = stops.firstOrNull() ?: return -1
        if (autoPos != null && p < (autoPos + first.pos) / 2f) return -1
        if (continuous) {
            val nearest = stops.withIndex().minByOrNull { abs(it.value.pos - p) } ?: return -2
            return if (abs(nearest.value.pos - p) <= snapThreshold * 0.5f) nearest.index else -2
        }
        return stops.withIndex().minByOrNull { abs(it.value.pos - p) }?.index ?: -2
    }
}

@SuppressLint("ViewConstructor")
class DialStrip(context: Context, private val ui: Ui, var palette: Palette) : AnimatedView(context) {
    var vertical = false
        set(v) { field = v; invalidate() }

    var model: DialModel? = null
        private set

    /** (position, settled) */
    var onScrub: ((Float, Boolean) -> Unit)? = null

    private val pos = spring(0f, 260f, 1.0f)
    private val labelRot = spring(0f, 260f, 0.78f)
    private var dragging = false
    private var settling = false
    private var lastTouch = 0f
    private var downTouch = 0f
    private var moved = false
    private var lastDetent = Int.MIN_VALUE
    private var tracker: VelocityTracker? = null

    private val tick = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val bg = Paint()
    private val marker = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()

    val position: Float get() = pos.target
    val isDragging: Boolean get() = dragging

    fun setModel(m: DialModel?, position: Float) {
        model = m
        pos.snap(m?.let { position.coerceIn(it.minPos, it.maxPos) } ?: 0f)
        lastDetent = m?.detent(pos.value) ?: Int.MIN_VALUE
        settling = false
        invalidate()
    }

    /** External value change (e.g. AUTO via double tap): glide there without reporting back. */
    fun moveTo(position: Float) {
        val m = model ?: return
        if (dragging) return
        settling = false
        pos.target = position.coerceIn(m.minPos, m.maxPos)
        kick()
    }

    fun setContentRotation(deg: Float) { labelRot.target = Motion.nearestAngle(labelRot.value, deg); kick() }

    private fun unitPx(): Float = ui.dp(model?.unitDp ?: 20f)

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        val m = model ?: return false
        val coord = if (vertical) e.y else e.x
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                tracker?.recycle()
                tracker = VelocityTracker.obtain().also { it.addMovement(e) }
                dragging = true
                settling = false
                moved = false
                lastTouch = coord
                downTouch = coord
                pos.snap(pos.value)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                tracker?.addMovement(e)
                val d = coord - lastTouch
                lastTouch = coord
                if (abs(coord - downTouch) > ui.dp(4f)) moved = true
                var next = pos.value - d / unitPx()
                // Rubber band past the ends.
                if (next < m.minPos) next = m.minPos - (m.minPos - next) * 0.35f
                if (next > m.maxPos) next = m.maxPos + (next - m.maxPos) * 0.35f
                pos.snap(next)
                reportDetent()
                onScrub?.invoke(next.coerceIn(m.minPos, m.maxPos), false)
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                dragging = false
                val t = tracker
                var target: Float
                if (!moved && e.actionMasked == MotionEvent.ACTION_UP) {
                    // Tap: jump to the stop under the finger.
                    val center = if (vertical) height / 2f else width / 2f
                    target = m.settle(pos.value + (coord - center) / unitPx())
                } else {
                    t?.computeCurrentVelocity(1000)
                    val v = if (t == null) 0f else -(if (vertical) t.yVelocity else t.xVelocity) / unitPx()
                    target = m.settle(pos.value + v * 0.16f)
                }
                tracker?.recycle()
                tracker = null
                pos.target = target
                settling = true
                kick()
            }
        }
        return true
    }

    private fun reportDetent() {
        val m = model ?: return
        val d = m.detent(pos.value)
        if (d != lastDetent && d != -2) Haptics.tick(this)
        lastDetent = d
    }

    override fun onDraw(canvas: Canvas) {
        val wasSettling = settling && pos.isMoving
        advance()
        val m = model ?: return
        if (settling) {
            reportDetent()
            if (!pos.isMoving) {
                settling = false
                onScrub?.invoke(pos.value, true)
            } else if (wasSettling) {
                onScrub?.invoke(pos.value.coerceIn(m.minPos, m.maxPos), false)
            }
        }
        val w = width.toFloat()
        val h = height.toFloat()
        val along = if (vertical) h else w
        val across = if (vertical) w else h
        val center = along / 2f
        val unit = unitPx()

        // Fade-to-scrim background.
        val c0 = Palette.withAlpha(palette.deep, 0f)
        val c1 = Palette.withAlpha(palette.deep, 0.78f)
        bg.shader = if (vertical) LinearGradient(w, 0f, 0f, 0f, c0, c1, Shader.TileMode.CLAMP)
        else LinearGradient(0f, 0f, 0f, h, c0, c1, Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, w, h, bg)

        val p = pos.value
        label.typeface = ui.mono
        label.textSize = ui.sp(10.5f)
        val baseLine = across * 0.62f
        fun at(stopPos: Float) = center + (stopPos - p) * unit
        fun fade(x: Float) = (1f - abs(x - center) / (along * 0.5f)).coerceIn(0f, 1f).let { it * it * 0.85f + 0.15f }

        // AUTO notch
        m.autoPos?.let { ap ->
            val x = at(ap)
            if (x > -unit * 3 && x < along + unit * 3) {
                val selected = m.settle(p) == ap
                label.color = Palette.withAlpha(if (selected) palette.text else palette.textDim, fade(x))
                drawLabel(canvas, "AUTO", x, baseLine - ui.dp(14f))
                tick.color = Palette.withAlpha(palette.accent, fade(x))
                tick.strokeWidth = ui.dp(3f)
                drawTick(canvas, x, baseLine - ui.dp(6f), baseLine + ui.dp(8f))
            }
        }
        for (s in m.stops) {
            val x = at(s.pos)
            if (x < -unit * 2 || x > along + unit * 2) continue
            val a = fade(x)
            tick.color = Palette.withAlpha(palette.text, a)
            tick.strokeWidth = ui.dp(if (s.major) 2f else 1.2f)
            val len = ui.dp(if (s.major) 12f else 6f)
            drawTick(canvas, x, baseLine - len / 2f + ui.dp(2f), baseLine + len / 2f + ui.dp(2f))
            if (s.label != null && s.major) {
                label.color = Palette.withAlpha(palette.text, a)
                drawLabel(canvas, s.label, x, baseLine - ui.dp(14f))
            }
        }
        // Center marker
        marker.color = palette.accent
        val mw = ui.dp(6f)
        path.reset()
        if (vertical) {
            path.moveTo(w, center - mw); path.lineTo(w - mw * 1.4f, center); path.lineTo(w, center + mw); path.close()
            canvas.drawPath(path, marker)
            canvas.drawRect(0f, center - ui.dp(1f), w - mw, center + ui.dp(1f), marker.apply { alpha = 120 })
        } else {
            path.moveTo(center - mw, h); path.lineTo(center, h - mw * 1.4f); path.lineTo(center + mw, h); path.close()
            canvas.drawPath(path, marker)
            canvas.drawRect(center - ui.dp(1f), 0f, center + ui.dp(1f), h - mw, marker.apply { alpha = 120 })
        }
        marker.alpha = 255
    }

    private fun drawTick(canvas: Canvas, along: Float, a0: Float, a1: Float) {
        if (vertical) canvas.drawLine(width - a0, along, width - a1, along, tick)
        else canvas.drawLine(along, a0, along, a1, tick)
    }

    private fun drawLabel(canvas: Canvas, text: String, along: Float, acrossPos: Float) {
        val x: Float
        val y: Float
        if (vertical) { x = width - acrossPos; y = along } else { x = along; y = acrossPos }
        canvas.save()
        canvas.rotate(labelRot.value, x, y - label.textSize * 0.35f)
        canvas.drawText(text, x, y, label)
        canvas.restore()
    }
}
