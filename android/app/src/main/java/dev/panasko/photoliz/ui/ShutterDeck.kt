package dev.panasko.photoliz.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import kotlin.math.max
import kotlin.math.min

/** Last photo, pops in after every save. */
@SuppressLint("ViewConstructor")
class ThumbView(context: Context, private val ui: Ui, var palette: Palette) : AnimatedView(context) {
    private var bitmap: Bitmap? = null
    private val pop = spring(1f, 520f, 0.62f)
    private val press = spring(1f, 900f, 0.6f)
    private val rotation = spring(0f, 260f, 0.78f)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val rect = RectF()
    private val m = Matrix()
    var onTap: (() -> Unit)? = null

    init { isClickable = true }

    fun show(b: Bitmap?) {
        bitmap = b
        paint.shader = b?.let { BitmapShader(it, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP) }
        pop.snap(0.3f)
        pop.target = 1f
        kick()
    }

    fun setContentRotation(deg: Float) { rotation.target = Motion.nearestAngle(rotation.value, deg); kick() }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { press.target = 0.88f; kick() }
            MotionEvent.ACTION_UP -> { press.target = 1f; kick(); if (bitmap != null) { Haptics.press(this); performClick() } }
            MotionEvent.ACTION_CANCEL -> { press.target = 1f; kick() }
        }
        return true
    }

    override fun performClick(): Boolean { super.performClick(); onTap?.invoke(); return true }

    override fun onDraw(canvas: Canvas) {
        advance()
        val side = min(width, height).toFloat() - ui.dp(6f)
        val cx = width / 2f
        val cy = height / 2f
        val s = pop.value * press.value
        canvas.save()
        canvas.scale(s, s, cx, cy)
        canvas.rotate(rotation.value, cx, cy)
        rect.set(cx - side / 2f, cy - side / 2f, cx + side / 2f, cy + side / 2f)
        val r = ui.dp(8f)
        val b = bitmap
        if (b != null && !b.isRecycled) {
            val scale = max(side / b.width, side / b.height)
            m.reset()
            m.postScale(scale, scale)
            m.postTranslate(cx - b.width * scale / 2f, cy - b.height * scale / 2f)
            paint.shader?.setLocalMatrix(m)
            canvas.drawRoundRect(rect, r, r, paint)
        } else {
            paint.shader = null
            paint.color = Palette.withAlpha(palette.surface, 0.7f)
            canvas.drawRoundRect(rect, r, r, paint)
        }
        border.strokeWidth = ui.dp(1.5f)
        border.color = Palette.withAlpha(palette.text, 0.5f)
        canvas.drawRoundRect(rect, r, r, border)
        canvas.restore()
    }
}

/**
 * Bottom (portrait) or right (landscape) control deck: mode, AF, shutter, aspect, thumbnail, and the
 * curved focus / zoom sliders. Positions are computed in a portrait frame and rotated for landscape.
 */
class ShutterDeck(context: Context, private val ui: Ui) : ViewGroup(context) {
    lateinit var arcs: ArcControls
    lateinit var shutter: ShutterButton
    lateinit var modeChip: ChipButton
    lateinit var afChip: ChipButton
    lateinit var aspectChip: ChipButton
    lateinit var thumb: ThumbView

    /** Landscape: deck is a column on the right; arcs open towards the viewfinder (left). */
    var vertical = false
        set(v) { if (field != v) { field = v; requestLayout() } }

    var focusArc = true
        private set
    var zoomArc = false
        private set

    fun setArcs(focusOn: Boolean, zoomOn: Boolean, animate: Boolean) {
        if (focusArc == focusOn && zoomArc == zoomOn) return
        focusArc = focusOn
        zoomArc = zoomOn
        arcs.setShown(focusOn, zoomOn, animate)
        requestLayout()
    }

    val shutterSize get() = ui.dp(78)
    val chip get() = ui.dp(50)

    /** Height (portrait) / width (landscape) the deck wants. */
    fun preferredThickness(): Int = if (focusArc || zoomArc) ui.dp(200) else ui.dp(148)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        arcs.measure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY))
        val ss = MeasureSpec.makeMeasureSpec(shutterSize, MeasureSpec.EXACTLY)
        shutter.measure(ss, ss)
        val cs = MeasureSpec.makeMeasureSpec(chip, MeasureSpec.EXACTLY)
        for (v in listOf<View>(modeChip, afChip, aspectChip, thumb)) v.measure(cs, cs)
        setMeasuredDimension(w, h)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val w = r - l
        val h = b - t
        // Virtual portrait frame.
        val vw = if (vertical) h else w
        val vh = if (vertical) w else h
        val anyArc = focusArc || zoomArc
        val half = shutterSize / 2f
        val radius = half + ui.dp(34f)
        val vcx = vw / 2f
        val vcy = if (anyArc) vh - half - ui.dp(16f) else vh / 2f
        val chipY = if (anyArc) vcy + ui.dp(22f) else vcy
        val edge = ui.dp(14f) + chip / 2f
        val inner = if (anyArc) radius + ui.dp(6f) else max(vw / 5f, half + chip / 2f + ui.dp(14f))

        fun place(v: View, px: Float, py: Float) {
            val (x, y) = if (vertical) py to (vw - px) else px to py
            val oldL = v.left
            val oldT = v.top
            val laid = v.isLaidOut && v.width > 0
            val hw = v.measuredWidth / 2f
            val hh = v.measuredHeight / 2f
            v.layout((x - hw).toInt(), (y - hh).toInt(), (x + hw).toInt(), (y + hh).toInt())
            if (laid) v.glideFrom(oldL, oldT)
        }

        arcs.layout(0, 0, w, h)
        place(shutter, vcx, vcy)
        place(afChip, vcx - inner, chipY)
        place(aspectChip, vcx + inner, chipY)
        place(modeChip, min(edge, vcx - inner - chip - ui.dp(4f)).coerceAtLeast(chip / 2f), chipY)
        place(thumb, max(vw - edge, vcx + inner + chip + ui.dp(4f)).coerceAtMost(vw - chip / 2f), chipY)

        val (ax, ay) = if (vertical) vcy to (vw - vcx) else vcx to vcy
        arcs.cx = ax
        arcs.cy = ay
        arcs.radius = radius
        val rot = if (vertical) 90f else 0f
        if (focusArc && zoomArc) {
            arcs.focus.start = 172f + rot; arcs.focus.end = 100f + rot
            arcs.zoom.start = 80f + rot; arcs.zoom.end = 8f + rot
        } else {
            arcs.focus.start = 165f + rot; arcs.focus.end = 15f + rot
            arcs.zoom.start = 165f + rot; arcs.zoom.end = 15f + rot
        }
        arcs.invalidate()
    }
}
