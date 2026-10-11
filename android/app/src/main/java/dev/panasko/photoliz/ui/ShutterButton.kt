package dev.panasko.photoliz.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.view.MotionEvent
import kotlin.math.min

/**
 * The shutter. Tap = one frame, hold = burst (photo). In video mode a tap starts / stops recording.
 */
@SuppressLint("ViewConstructor")
class ShutterButton(context: Context, private val ui: Ui, var palette: Palette) : AnimatedView(context) {
    var onShoot: (() -> Unit)? = null
    var onBurstStart: (() -> Unit)? = null
    var onBurstEnd: (() -> Unit)? = null

    private val press = spring(1f, 900f, 0.55f)
    private val videoAmt = spring(0f, 380f, 0.85f)
    private val recAmt = spring(0f, 420f, 0.8f)
    private val burstAmt = spring(0f, 420f, 0.8f)

    var video = false
        set(v) { if (field != v) { field = v; videoAmt.target = if (v) 1f else 0f; kick() } }

    var recording = false
        set(v) { if (field != v) { field = v; recAmt.target = if (v) 1f else 0f; kick() } }

    /** AF / processing in progress: shows a spinning segment. */
    var busy = false
        set(v) { if (field != v) { field = v; kick() } }

    var burstCount = 0
        set(v) { field = v; invalidate() }

    private var bursting = false
    private var downAt = 0L
    private var downInside = false
    private val burstRunnable = Runnable {
        if (!video && downInside) {
            bursting = true
            burstAmt.target = 1f
            kick()
            Haptics.heavy(this)
            onBurstStart?.invoke()
        }
    }

    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val disc = Paint(Paint.ANTI_ALIAS_FLAG)
    private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val rect = RectF()

    init { isClickable = true }

    override fun keepAnimating(): Boolean = busy || bursting

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (!isEnabled) return false
        val r = min(width, height) / 2f
        val dx = e.x - width / 2f
        val dy = e.y - height / 2f
        val inside = dx * dx + dy * dy <= r * r * 1.6f
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (!inside) return false
                downInside = true
                downAt = SystemClock.uptimeMillis()
                press.target = 0.88f; kick()
                postDelayed(burstRunnable, 380)
                return true
            }
            MotionEvent.ACTION_MOVE -> if (!inside && !bursting) { downInside = false; removeCallbacks(burstRunnable); press.target = 1f; kick() }
            MotionEvent.ACTION_UP -> {
                removeCallbacks(burstRunnable)
                press.target = 1f; kick()
                if (bursting) {
                    bursting = false
                    burstAmt.target = 0f
                    onBurstEnd?.invoke()
                } else if (downInside) {
                    Haptics.confirm(this)
                    performClick()
                }
                downInside = false
            }
            MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(burstRunnable)
                press.target = 1f; kick()
                if (bursting) { bursting = false; burstAmt.target = 0f; onBurstEnd?.invoke() }
                downInside = false
            }
        }
        return true
    }

    /** Hardware key (volume / camera button) path. */
    fun keyDown(longPress: Boolean) {
        if (longPress && !video && !bursting) { bursting = true; burstAmt.target = 1f; kick(); onBurstStart?.invoke() }
    }

    fun keyUp(): Boolean {
        if (bursting) { bursting = false; burstAmt.target = 0f; kick(); onBurstEnd?.invoke(); return true }
        return false
    }

    override fun performClick(): Boolean {
        super.performClick()
        onShoot?.invoke()
        return true
    }

    override fun onDraw(canvas: Canvas) {
        advance()
        val cx = width / 2f
        val cy = height / 2f
        val r = min(width, height) / 2f - ui.dp(2f)
        val p = palette
        // Outer ring
        ring.strokeWidth = ui.dp(3.5f)
        ring.color = p.text
        canvas.drawCircle(cx, cy, r - ring.strokeWidth / 2f, ring)

        val v = videoAmt.value
        val rec = recAmt.value
        val inner = (r - ui.dp(8f)) * press.value
        disc.color = Palette.mix(p.body, p.alert, v)
        // Photo: full disc. Video: smaller red dot. Recording: rounded square.
        val dotR = inner * (1f - 0.42f * v)
        val sq = inner * 0.52f
        val half = dotR + (sq - dotR) * rec
        val corner = half * (1f - 0.7f * rec)
        rect.set(cx - half, cy - half, cx + half, cy + half)
        canvas.drawRoundRect(rect, corner, corner, disc)

        if (busy || burstAmt.value > 0.01f) {
            arc.strokeWidth = ui.dp(3.5f)
            arc.color = p.accent
            val t = (SystemClock.uptimeMillis() % 900L) / 900f
            rect.set(cx - r + arc.strokeWidth / 2f, cy - r + arc.strokeWidth / 2f, cx + r - arc.strokeWidth / 2f, cy + r - arc.strokeWidth / 2f)
            canvas.drawArc(rect, t * 360f - 90f, if (burstAmt.value > 0.01f) 300f * burstAmt.value else 80f, false, arc)
        }
        if (burstCount > 0 && burstAmt.value > 0.01f) {
            text.typeface = ui.mono
            text.textSize = ui.sp(16f)
            text.color = Palette.withAlpha(p.onBody, burstAmt.value)
            canvas.drawText(burstCount.toString(), cx, cy + text.textSize * 0.35f, text)
        }
    }
}
