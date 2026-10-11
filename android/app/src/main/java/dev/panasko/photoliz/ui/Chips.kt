package dev.panasko.photoliz.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import kotlin.math.max
import kotlin.math.min

/** Small square camera button: pixel icon + LCD label. Content rotates with the device. */
@SuppressLint("ViewConstructor")
class ChipButton(context: Context, private val ui: Ui, var palette: Palette) : AnimatedView(context) {
    var icon: PixelIcon? = null
        set(v) { if (field !== v) { field = v; invalidate() } }

    private var labelText: String = ""
    private var oldLabel: String? = null
    private val labelSwap = spring(1f, 520f, 0.92f)
    private val press = spring(1f, 900f, 0.55f)
    private val activeAmt = spring(0f, 520f, 0.9f)
    private val rotation = spring(0f, 260f, 0.78f)

    var label: String
        get() = labelText
        set(v) {
            if (v == labelText) return
            oldLabel = labelText.takeIf { it.isNotEmpty() && isLaidOut }
            labelText = v
            if (oldLabel != null) { labelSwap.snap(0f); labelSwap.target = 1f; kick() } else invalidate()
        }

    var active: Boolean = false
        set(v) { if (field != v) { field = v; activeAmt.target = if (v) 1f else 0f; kick() } }

    var dimmed: Boolean = false
        set(v) { if (field != v) { field = v; invalidate() } }

    /** Degrees the content must rotate to look upright to the user. */
    fun setContentRotation(deg: Float, animate: Boolean = true) {
        val t = Motion.nearestAngle(rotation.value, deg)
        if (animate) { rotation.target = t; kick() } else rotation.snap(t)
    }

    var onTap: (() -> Unit)? = null
    var onLongTap: (() -> Unit)? = null

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val iconPaint = Paint()
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val rect = RectF()
    private var longFired = false
    private val longRunnable = Runnable {
        if (onLongTap != null) { longFired = true; Haptics.heavy(this); onLongTap?.invoke() }
    }

    init {
        isClickable = true
        isFocusable = true
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (!isEnabled) return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                press.target = 0.86f; kick()
                longFired = false
                if (onLongTap != null) postDelayed(longRunnable, 480)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val inside = e.x in -width * 0.25f..width * 1.25f && e.y in -height * 0.25f..height * 1.25f
                if (!inside) { removeCallbacks(longRunnable); press.target = 1f; kick() }
            }
            MotionEvent.ACTION_UP -> {
                removeCallbacks(longRunnable)
                val inside = e.x in -width * 0.25f..width * 1.25f && e.y in -height * 0.25f..height * 1.25f
                press.target = 1f; kick()
                if (inside && !longFired) { Haptics.press(this); performClick() }
            }
            MotionEvent.ACTION_CANCEL -> { removeCallbacks(longRunnable); press.target = 1f; kick() }
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        onTap?.invoke()
        return true
    }

    override fun onDraw(canvas: Canvas) {
        advance()
        val w = width.toFloat()
        val h = height.toFloat()
        val cx = w / 2f
        val cy = h / 2f
        val side = min(w, h)
        val s = press.value
        canvas.save()
        canvas.scale(s, s, cx, cy)
        val a = activeAmt.value
        val inset = ui.dp(2f)
        rect.set(cx - side / 2f + inset, cy - side / 2f + inset, cx + side / 2f - inset, cy + side / 2f - inset)
        val r = ui.dp(10f)
        fill.color = Palette.mix(Palette.withAlpha(palette.surface, 1f), palette.accent, a)
        fill.alpha = (255 * (0.62f + 0.38f * a)).toInt()
        canvas.drawRoundRect(rect, r, r, fill)
        stroke.strokeWidth = ui.dp(1.2f)
        stroke.color = Palette.withAlpha(palette.text, 0.22f * (1f - a))
        if (a < 0.99f) canvas.drawRoundRect(rect, r, r, stroke)

        canvas.rotate(rotation.value, cx, cy)
        val fg = Palette.mix(palette.text, palette.onAccent, a)
        val alpha = if (dimmed) 0.38f else 1f
        val hasLabel = labelText.isNotEmpty()
        icon?.let {
            iconPaint.color = Palette.withAlpha(fg, alpha)
            it.draw(canvas, cx, if (hasLabel) cy - side * 0.12f else cy, side * 0.40f, iconPaint)
        }
        if (hasLabel) {
            textPaint.typeface = ui.mono
            val maxW = side - ui.dp(8f)
            var size = ui.sp(9.5f)
            textPaint.textSize = size
            val longest = max(textPaint.measureText(labelText), oldLabel?.let { textPaint.measureText(it) } ?: 0f)
            if (longest > maxW) { size *= maxW / longest; textPaint.textSize = size }
            val baseY = if (icon != null) cy + side * 0.30f else cy + size * 0.35f
            val t = labelSwap.value
            val shift = ui.dp(8f)
            oldLabel?.let { ol ->
                if (t < 1f) {
                    textPaint.color = Palette.withAlpha(fg, alpha * (1f - t))
                    canvas.drawText(ol, cx, baseY - shift * t, textPaint)
                }
            }
            textPaint.color = Palette.withAlpha(fg, alpha * t)
            canvas.drawText(labelText, cx, baseY + shift * (1f - t), textPaint)
            if (t >= 1f) oldLabel = null
        }
        canvas.restore()
    }
}

/** Appear / disappear with a quick scale-fade. Safe against rapid toggling. */
object Reveal {
    private const val KEY = 0x50_4C_5A_01

    fun set(v: View, shown: Boolean, animate: Boolean = true) {
        val wanted = v.getTag(KEY) as? Boolean
        if (wanted == shown && (shown == (v.visibility == View.VISIBLE))) return
        v.setTag(KEY, shown)
        v.animate().cancel()
        if (!animate || !v.isAttachedToWindow) {
            v.visibility = if (shown) View.VISIBLE else View.GONE
            v.alpha = 1f; v.scaleX = 1f; v.scaleY = 1f
            return
        }
        if (shown) {
            if (v.visibility != View.VISIBLE) {
                v.alpha = 0f; v.scaleX = 0.55f; v.scaleY = 0.55f
                v.visibility = View.VISIBLE
            }
            v.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(Motion.MEDIUM).setInterpolator(Motion.OVERSHOOT).start()
        } else {
            v.animate().alpha(0f).scaleX(0.55f).scaleY(0.55f).setDuration(Motion.FAST).setInterpolator(Motion.ACCELERATE)
                .withEndAction { if (v.getTag(KEY) == false) { v.visibility = View.GONE; v.alpha = 1f; v.scaleX = 1f; v.scaleY = 1f } }
                .start()
        }
    }
}

/** Animates children to their new slots whenever a sibling appears or disappears. */
fun View.glideFrom(oldLeft: Int, oldTop: Int) {
    if (!isLaidOut || visibility != View.VISIBLE) return
    val dx = (oldLeft - left).toFloat()
    val dy = (oldTop - top).toFloat()
    if (dx == 0f && dy == 0f) return
    translationX += dx
    translationY += dy
    animate().translationX(0f).translationY(0f).setDuration(Motion.SLOW).setInterpolator(Motion.EMPHASIZED).start()
}

/** Row (or column) of equally spaced chips. Hidden chips leave no gap. */
class ChipBar(context: Context, private val ui: Ui) : ViewGroup(context) {
    var vertical = false
        set(v) { if (field != v) { field = v; requestLayout() } }

    var chipSize = ui.dp(50)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        val spec = MeasureSpec.makeMeasureSpec(chipSize, MeasureSpec.EXACTLY)
        for (i in 0 until childCount) getChildAt(i).measure(spec, spec)
        setMeasuredDimension(w, h)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val shown = (0 until childCount).map { getChildAt(it) }.filter { it.visibility != View.GONE }
        val n = shown.size
        if (n == 0) return
        val w = r - l
        val h = b - t
        val span = if (vertical) h else w
        val slot = span.toFloat() / n
        shown.forEachIndexed { i, c ->
            val center = slot * (i + 0.5f)
            val oldL = c.left
            val oldT = c.top
            val wasLaid = c.isLaidOut && c.width > 0
            if (vertical) {
                val top = (center - chipSize / 2f).toInt()
                val left = (w - chipSize) / 2
                c.layout(left, top, left + chipSize, top + chipSize)
            } else {
                val left = (center - chipSize / 2f).toInt()
                val top = (h - chipSize) / 2
                c.layout(left, top, left + chipSize, top + chipSize)
            }
            if (wasLaid) c.glideFrom(oldL, oldT)
        }
    }
}
