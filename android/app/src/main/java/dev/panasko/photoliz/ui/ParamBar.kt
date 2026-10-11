package dev.panasko.photoliz.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import kotlin.math.min

/** One exposure readout (ISO / SHUTTER / F / ND). Tap selects its dial, double tap returns to AUTO. */
@SuppressLint("ViewConstructor")
class ParamCell(context: Context, private val ui: Ui, var palette: Palette, val key: String, private val title: String) : AnimatedView(context) {
    private var value = ""
    private var oldValue: String? = null
    private var auto = false
    private val swap = spring(1f, 560f, 0.9f)
    private val sel = spring(0f, 520f, 0.85f)
    private val rotation = spring(0f, 260f, 0.78f)
    private val press = spring(1f, 900f, 0.6f)

    /** Read-only cells show hardware values the user cannot change on this sensor. */
    var readOnly = false
        set(v) { field = v; invalidate() }

    var chosen = false
        set(v) { if (field != v) { field = v; sel.target = if (v) 1f else 0f; kick() } }

    var onTap: ((String) -> Unit)? = null
    var onDoubleTap: ((String) -> Unit)? = null

    private val bg = Paint(Paint.ANTI_ALIAS_FLAG)
    private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val badgePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    private val detector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean { press.target = 0.93f; kick(); return true }
        override fun onSingleTapConfirmed(e: MotionEvent): Boolean { Haptics.press(this@ParamCell); onTap?.invoke(key); return true }
        override fun onDoubleTap(e: MotionEvent): Boolean { Haptics.confirm(this@ParamCell); onDoubleTap?.invoke(key); return true }
    })

    fun setValue(text: String, isAuto: Boolean) {
        if (text == value && isAuto == auto) return
        if (text != value && isLaidOut && value.isNotEmpty()) {
            oldValue = value
            swap.snap(0f); swap.target = 1f
        }
        value = text
        auto = isAuto
        kick()
    }

    fun setContentRotation(deg: Float) { rotation.target = Motion.nearestAngle(rotation.value, deg); kick() }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (readOnly) return false
        detector.onTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) { press.target = 1f; kick() }
        return true
    }

    override fun onDraw(canvas: Canvas) {
        advance()
        val w = width.toFloat()
        val h = height.toFloat()
        val cx = w / 2f
        val cy = h / 2f
        canvas.save()
        canvas.scale(press.value, press.value, cx, cy)
        val s = sel.value
        val pad = ui.dp(4f)
        rect.set(pad, pad, w - pad, h - pad)
        if (s > 0.01f) {
            bg.color = Palette.withAlpha(palette.accent, 0.92f * s)
            canvas.drawRoundRect(rect, ui.dp(8f), ui.dp(8f), bg)
        }
        canvas.rotate(rotation.value, cx, cy)
        val fg = Palette.mix(palette.text, palette.onAccent, s)
        val sideways = (Math.round(rotation.value / 90f) % 2) != 0
        val boxH = if (sideways) w else h
        titlePaint.typeface = ui.condensed
        titlePaint.textSize = ui.sp(9f)
        titlePaint.letterSpacing = 0.12f
        titlePaint.color = Palette.withAlpha(fg, if (readOnly) 0.45f else 0.7f)
        canvas.drawText(title, cx, cy - boxH * 0.17f, titlePaint)

        valuePaint.typeface = ui.mono
        var vs = ui.sp(15f)
        valuePaint.textSize = vs
        val maxW = (if (sideways) h else w) - ui.dp(10f)
        val shown = if (auto) "A $value".trim() else value
        val mw = valuePaint.measureText(shown)
        if (mw > maxW) { vs *= maxW / mw; valuePaint.textSize = vs }
        val baseY = cy + boxH * 0.22f
        val t = swap.value
        val shift = ui.dp(10f)
        oldValue?.let { ov ->
            if (t < 1f) {
                valuePaint.color = Palette.withAlpha(fg, (1f - t) * if (readOnly) 0.6f else 1f)
                canvas.drawText(if (auto) "A $ov" else ov, cx, baseY - shift * t, valuePaint)
            }
        }
        valuePaint.color = Palette.withAlpha(fg, t * if (readOnly) 0.6f else 1f)
        if (auto) {
            // "A" badge in a box, value beside it.
            val full = valuePaint.measureText(shown)
            val aw = valuePaint.measureText("A ")
            val startX = cx - full / 2f
            badgePaint.color = Palette.withAlpha(fg, 0.9f * t)
            badgePaint.style = Paint.Style.STROKE
            badgePaint.strokeWidth = ui.dp(1.2f)
            val bw = valuePaint.measureText("A")
            val top = baseY + shift * (1f - t) - vs * 0.86f
            canvas.drawRect(startX - ui.dp(2f), top, startX + bw + ui.dp(2f), baseY + shift * (1f - t) + vs * 0.16f, badgePaint)
            valuePaint.textAlign = Paint.Align.LEFT
            canvas.drawText("A", startX, baseY + shift * (1f - t), valuePaint)
            canvas.drawText(value, startX + aw, baseY + shift * (1f - t), valuePaint)
            valuePaint.textAlign = Paint.Align.CENTER
        } else {
            canvas.drawText(value, cx, baseY + shift * (1f - t), valuePaint)
        }
        if (t >= 1f) oldValue = null
        canvas.restore()
    }
}

/** Evenly spaced exposure readouts, on a soft scrim so they stay readable over the image. */
class ParamBar(context: Context) : ViewGroup(context) {
    var vertical = false
        set(v) { if (field != v) { field = v; requestLayout(); invalidate() } }

    var scrimColor = 0
        set(v) { field = v; invalidate() }

    private val scrim = android.graphics.Paint()

    init { setWillNotDraw(false) }

    override fun onDraw(canvas: android.graphics.Canvas) {
        if (android.graphics.Color.alpha(scrimColor) == 0) return
        val w = width.toFloat()
        val h = height.toFloat()
        val clear = scrimColor and 0x00FFFFFF
        scrim.shader = if (vertical) android.graphics.LinearGradient(0f, 0f, w, 0f, clear, scrimColor, android.graphics.Shader.TileMode.CLAMP)
        else android.graphics.LinearGradient(0f, 0f, 0f, h, clear, scrimColor, android.graphics.Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, w, h, scrim)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        val shown = (0 until childCount).map { getChildAt(it) }.filter { it.visibility != View.GONE }
        val n = shown.size.coerceAtLeast(1)
        val cw = if (vertical) w else min(w / n, (h * 2.6f).toInt())
        val ch = if (vertical) min(h / n, (w * 0.9f).toInt()) else h
        for (c in shown) c.measure(MeasureSpec.makeMeasureSpec(cw, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(ch, MeasureSpec.EXACTLY))
        setMeasuredDimension(w, h)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val shown = (0 until childCount).map { getChildAt(it) }.filter { it.visibility != View.GONE }
        val n = shown.size
        if (n == 0) return
        val w = r - l
        val h = b - t
        val slot = (if (vertical) h else w).toFloat() / n
        shown.forEachIndexed { i, c ->
            val center = slot * (i + 0.5f)
            val oldL = c.left
            val oldT = c.top
            val wasLaid = c.isLaidOut && c.width > 0
            if (vertical) {
                val top = (center - c.measuredHeight / 2f).toInt()
                c.layout((w - c.measuredWidth) / 2, top, (w - c.measuredWidth) / 2 + c.measuredWidth, top + c.measuredHeight)
            } else {
                val left = (center - c.measuredWidth / 2f).toInt()
                c.layout(left, (h - c.measuredHeight) / 2, left + c.measuredWidth, (h - c.measuredHeight) / 2 + c.measuredHeight)
            }
            if (wasLaid) c.glideFrom(oldL, oldT)
        }
    }
}
