package dev.panasko.photoliz.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Focus and zoom sliders, curved around the shutter. Values are 0..1 along each arc; moving clockwise
 * always increases (near → infinity, wide → tele).
 */
@SuppressLint("ViewConstructor")
class ArcControls(context: Context, private val ui: Ui, var palette: Palette) : AnimatedView(context) {

    class Arc(val key: String) {
        var start = 0f   // math degrees (0 = right, counter-clockwise)
        var end = 0f
        var value = 0f
        var enabled = true
        var startLabel = ""
        var endLabel = ""
        var bubble: (Float) -> String = { "" }
        /** Values (0..1) that produce a haptic tick when crossed. */
        var detents: FloatArray = FloatArray(0)
    }

    val focus = Arc("focus")
    val zoom = Arc("zoom")

    private val focusShown = spring(0f, 300f, 0.9f)
    private val zoomShown = spring(0f, 300f, 0.9f)
    private val bubbleAmt = spring(0f, 500f, 0.9f)
    private val labelRot = spring(0f, 260f, 0.78f)

    var cx = 0f
    var cy = 0f
    var radius = 0f

    var onChange: ((Arc, Float, Boolean) -> Unit)? = null

    private var active: Arc? = null
    private var bubbleArc: Arc? = null

    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val tick = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND }
    private val knob = Paint(Paint.ANTI_ALIAS_FLAG)
    private val knobRing = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val bubbleBg = Paint(Paint.ANTI_ALIAS_FLAG)
    private val oval = RectF()

    fun setShown(focusOn: Boolean, zoomOn: Boolean, animate: Boolean) {
        if (animate) { focusShown.target = if (focusOn) 1f else 0f; zoomShown.target = if (zoomOn) 1f else 0f; kick() }
        else { focusShown.snap(if (focusOn) 1f else 0f); zoomShown.snap(if (zoomOn) 1f else 0f); invalidate() }
    }

    fun setContentRotation(deg: Float) { labelRot.target = Motion.nearestAngle(labelRot.value, deg); kick() }

    fun setValue(a: Arc, v: Float) {
        if (active === a) return
        a.value = v.coerceIn(0f, 1f)
        invalidate()
    }

    private fun angleOf(a: Arc, t: Float) = a.start + (a.end - a.start) * t
    private fun shownOf(a: Arc) = if (a === focus) focusShown.value else zoomShown.value

    private fun point(deg: Float, r: Float): Pair<Float, Float> {
        val rad = Math.toRadians(deg.toDouble())
        return (cx + r * cos(rad)).toFloat() to (cy - r * sin(rad)).toFloat()
    }

    /** Signed angular distance from [from] to [to] in (-180, 180]. */
    private fun delta(from: Float, to: Float): Float {
        var d = (to - from) % 360f
        if (d > 180f) d -= 360f
        if (d <= -180f) d += 360f
        return d
    }

    private fun tFromAngle(a: Arc, deg: Float): Float {
        val span = delta(a.start, a.end)
        if (abs(span) < 1e-3f) return 0f
        return (delta(a.start, deg) / span).coerceIn(0f, 1f)
    }

    private fun hit(x: Float, y: Float): Arc? {
        val r = hypot(x - cx, y - cy)
        if (abs(r - radius) > ui.dp(30f)) return null
        val deg = Math.toDegrees(atan2(-(y - cy).toDouble(), (x - cx).toDouble())).toFloat()
        for (a in listOf(focus, zoom)) {
            if (shownOf(a) < 0.5f || !a.enabled) continue
            val span = delta(a.start, a.end)
            if (abs(span) < 1f) continue
            val t = delta(a.start, deg) / span
            val margin = 9f / abs(span)
            if (t >= -margin && t <= 1f + margin) return a
        }
        return null
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val a = hit(e.x, e.y) ?: return false
                parent?.requestDisallowInterceptTouchEvent(true)
                active = a
                bubbleArc = a
                bubbleAmt.target = 1f
                update(a, e.x, e.y, false)
                return true
            }
            MotionEvent.ACTION_MOVE -> active?.let { update(it, e.x, e.y, false) }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                // Release first so the listener may snap the knob (setValue ignores the arc being dragged).
                val a = active
                active = null
                a?.let { update(it, e.x, e.y, true) }
                bubbleAmt.target = 0f
                kick()
            }
        }
        return true
    }

    private fun update(a: Arc, x: Float, y: Float, final: Boolean) {
        val deg = Math.toDegrees(atan2(-(y - cy).toDouble(), (x - cx).toDouble())).toFloat()
        val t = tFromAngle(a, deg)
        val old = a.value
        for (d in a.detents) if ((old - d) * (t - d) < 0f || (t == d && old != d)) { Haptics.tick(this); break }
        a.value = t
        onChange?.invoke(a, t, final)
        kick()
    }

    override fun onDraw(canvas: Canvas) {
        advance()
        if (radius <= 0f) return
        drawArc(canvas, focus, focusShown.value)
        drawArc(canvas, zoom, zoomShown.value)
    }

    private fun drawArc(canvas: Canvas, a: Arc, shown: Float) {
        if (shown < 0.01f) return
        val p = palette
        val span = delta(a.start, a.end) * shown
        oval.set(cx - radius, cy - radius, cx + radius, cy + radius)
        track.strokeWidth = ui.dp(2f)
        track.color = Palette.withAlpha(p.text, (if (a.enabled) 0.32f else 0.14f) * shown)
        // Canvas angles run clockwise from +x; math angles counter-clockwise.
        canvas.drawArc(oval, -a.start, -span, false, track)
        // Progress from start to knob
        if (a.enabled) {
            track.color = Palette.withAlpha(p.accent, 0.95f * shown)
            track.strokeWidth = ui.dp(3f)
            canvas.drawArc(oval, -a.start, -span * a.value, false, track)
        }
        // Ticks
        tick.strokeWidth = ui.dp(1.2f)
        for (i in 0..10) {
            val tt = i / 10f
            if (tt > shown + 1e-3f) break
            val deg = a.start + delta(a.start, a.end) * tt
            val major = i == 0 || i == 10 || i == 5
            val (x0, y0) = point(deg, radius + ui.dp(7f))
            val (x1, y1) = point(deg, radius + ui.dp(if (major) 13f else 10f))
            tick.color = Palette.withAlpha(p.text, (if (major) 0.6f else 0.3f) * shown)
            canvas.drawLine(x0, y0, x1, y1, tick)
        }
        // End labels
        text.typeface = ui.mono
        text.textSize = ui.sp(9f)
        text.color = Palette.withAlpha(p.textDim, 0.8f * shown * shown)
        labelAt(canvas, a.startLabel, a.start, radius + ui.dp(22f))
        if (shown > 0.98f) labelAt(canvas, a.endLabel, a.end, radius + ui.dp(22f))

        if (!a.enabled) return
        // Knob
        val deg = angleOf(a, a.value.coerceAtMost(shown))
        val (kx, ky) = point(deg, radius)
        val big = if (active === a) 1.25f else 1f
        knob.color = Palette.withAlpha(p.body, shown)
        canvas.drawCircle(kx, ky, ui.dp(9f) * big, knob)
        knobRing.strokeWidth = ui.dp(2f)
        knobRing.color = Palette.withAlpha(p.text, shown)
        canvas.drawCircle(kx, ky, ui.dp(9f) * big, knobRing)

        // Value bubble
        if (bubbleArc === a && bubbleAmt.value > 0.01f) {
            val label = a.bubble(a.value)
            if (label.isNotEmpty()) {
                val (bx, by) = point(deg, radius + ui.dp(36f))
                text.textSize = ui.sp(12f)
                val tw = text.measureText(label) + ui.dp(12f)
                val th = ui.dp(22f)
                val s = bubbleAmt.value
                canvas.save()
                canvas.rotate(labelRot.value, bx, by)
                canvas.scale(s, s, bx, by)
                bubbleBg.color = p.accent
                canvas.drawRoundRect(bx - tw / 2f, by - th / 2f, bx + tw / 2f, by + th / 2f, th / 2f, th / 2f, bubbleBg)
                text.color = p.onAccent
                canvas.drawText(label, bx, by + text.textSize * 0.35f, text)
                canvas.restore()
            }
        }
    }

    private fun labelAt(canvas: Canvas, s: String, deg: Float, r: Float) {
        if (s.isEmpty()) return
        val (x, y) = point(deg, r)
        canvas.save()
        canvas.rotate(labelRot.value, x, y)
        canvas.drawText(s, x, y + text.textSize * 0.35f, text)
        canvas.restore()
    }
}
