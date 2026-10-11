package dev.panasko.photoliz.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.SystemClock
import android.util.Size
import android.view.MotionEvent
import android.view.TextureView
import android.view.ViewGroup
import dev.panasko.photoliz.camera.CropMath
import dev.panasko.photoliz.camera.FocusPhase
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * TextureView sized to the exact sensor aspect (fit, never cropped by the screen) plus an overlay for crop
 * masks, the AF reticle and LCD-style info. Photo zoom is a transform of the TextureView (a crop of the full
 * sensor frame, exactly what the developed JPEG will contain).
 */
@SuppressLint("ViewConstructor")
class ViewfinderView(context: Context, private val ui: Ui, palette: Palette) : ViewGroup(context) {
    val texture = TextureView(context)
    val overlay = ViewfinderOverlay(context, ui, palette)

    var previewSize: Size? = null
    var sensorOrientation = 90
    var displayRotation = 0
    var zoom = 1f
        set(v) { if (field != v) { field = v; applyTransform() } }

    /** (natural-orientation normalized x, y, view x, view y) */
    var onTap: ((Float, Float, Float, Float) -> Unit)? = null

    val contentRect = RectF()
    private val matrix = Matrix()
    private val inverse = Matrix()
    private var downX = 0f
    private var downY = 0f
    private var downTime = 0L

    init {
        addView(texture)
        addView(overlay)
        setBackgroundColor(palette.deep)
    }

    fun setPalette(p: Palette) {
        setBackgroundColor(p.deep)
        overlay.palette = p
        overlay.invalidate()
    }

    /** Displayed width / height of the camera image after all rotations. */
    fun displayedAspect(): Float {
        val ps = previewSize ?: return 3f / 4f
        val natW: Float
        val natH: Float
        if (sensorOrientation % 180 == 90) { natW = ps.height.toFloat(); natH = ps.width.toFloat() } else { natW = ps.width.toFloat(); natH = ps.height.toFloat() }
        return if (displayRotation % 180 == 90) natH / natW else natW / natH
    }

    fun configure(size: Size, sensorOrientation: Int, displayRotation: Int) {
        val changed = size != previewSize || sensorOrientation != this.sensorOrientation || displayRotation != this.displayRotation
        previewSize = size
        this.sensorOrientation = sensorOrientation
        this.displayRotation = displayRotation
        if (changed) requestLayout()
        applyTransform()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        computeContent(w, h)
        texture.measure(MeasureSpec.makeMeasureSpec(contentRect.width().toInt().coerceAtLeast(1), MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(contentRect.height().toInt().coerceAtLeast(1), MeasureSpec.EXACTLY))
        overlay.measure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY))
        setMeasuredDimension(w, h)
    }

    /**
     * Strip reserved for the exposure bar at the bottom (portrait) or right (landscape). The image is centered
     * in the remaining space when it fits there; otherwise the bar overlays the image.
     */
    var reserveBottom = 0
    var reserveRight = 0

    private fun computeContent(w: Int, h: Int) {
        val aspect = displayedAspect()
        var cw = w.toFloat()
        var ch = cw / aspect
        if (ch > h) { ch = h.toFloat(); cw = ch * aspect }
        val freeV = h - ch
        val freeH = w - cw
        val top = if (freeV >= reserveBottom) (freeV - reserveBottom) / 2f else freeV / 2f
        val left = if (freeH >= reserveRight) (freeH - reserveRight) / 2f else freeH / 2f
        contentRect.set(left, top, left + cw, top + ch)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        computeContent(r - l, b - t)
        texture.layout(contentRect.left.toInt(), contentRect.top.toInt(), contentRect.left.toInt() + texture.measuredWidth, contentRect.top.toInt() + texture.measuredHeight)
        overlay.layout(0, 0, r - l, b - t)
        val oldW = overlay.content.width()
        val oldH = overlay.content.height()
        overlay.content.set(texture.left.toFloat(), texture.top.toFloat(), texture.right.toFloat(), texture.bottom.toFloat())
        if (oldW != overlay.content.width() || oldH != overlay.content.height()) overlay.relayoutAspect()
        applyTransform()
        overlay.invalidate()
    }

    fun applyTransform() {
        val tw = texture.width.toFloat()
        val th = texture.height.toFloat()
        if (tw <= 0f || th <= 0f) return
        val cx = tw / 2f
        val cy = th / 2f
        matrix.reset()
        if (displayRotation % 180 == 90) matrix.setScale(th / tw, tw / th, cx, cy)
        matrix.postRotate(-displayRotation.toFloat(), cx, cy)
        matrix.postScale(zoom, zoom, cx, cy)
        texture.setTransform(matrix)
        texture.invalidate()
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x; downY = e.y; downTime = SystemClock.uptimeMillis()
                return contentRect.contains(e.x, e.y)
            }
            MotionEvent.ACTION_UP -> {
                val moved = abs(e.x - downX) > ui.dp(12f) || abs(e.y - downY) > ui.dp(12f)
                if (!moved && SystemClock.uptimeMillis() - downTime < 500 && contentRect.contains(e.x, e.y)) {
                    val pts = floatArrayOf(e.x - texture.left, e.y - texture.top)
                    if (matrix.invert(inverse)) {
                        inverse.mapPoints(pts)
                        val nx = (pts[0] / texture.width).coerceIn(0f, 1f)
                        val ny = (pts[1] / texture.height).coerceIn(0f, 1f)
                        performClick()
                        onTap?.invoke(nx, ny, e.x, e.y)
                    }
                }
            }
        }
        return true
    }

    override fun performClick(): Boolean { super.performClick(); return true }
}

/** Everything drawn over the live image. */
@SuppressLint("ViewConstructor")
class ViewfinderOverlay(context: Context, private val ui: Ui, var palette: Palette) : AnimatedView(context) {
    val content = RectF()

    // Crop mask edges as fractions of the content rect.
    private val cl = spring(0f, 380f, 0.9f)
    private val ct = spring(0f, 380f, 0.9f)
    private val cr = spring(1f, 380f, 0.9f)
    private val cb = spring(1f, 380f, 0.9f)
    private val rot = spring(0f, 260f, 0.8f)
    private val curtain = spring(0f, 1600f, 1f)
    private val afAmt = spring(0f, 420f, 0.75f)
    private val afScale = spring(1f, 300f, 0.7f)

    var infoLeft = ""
        set(v) { if (field != v) { field = v; invalidate() } }
    var infoRight = ""
        set(v) { if (field != v) { field = v; invalidate() } }
    var modeBadge = ""
        set(v) { if (field != v) { field = v; invalidate() } }
    var center = ""
        set(v) { if (field != v) { field = v; invalidate() } }
    var recordingSince = 0L
        set(v) { field = v; kick() }

    private var afX = 0f
    private var afY = 0f
    private var afPhase = FocusPhase.IDLE
    private var afHideAt = 0L

    private val fill = Paint()
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG)
    private val box = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val tmp = RectF()

    override fun keepAnimating(): Boolean = recordingSince > 0L || (afPhase != FocusPhase.IDLE && SystemClock.uptimeMillis() < afHideAt + 400)

    private var ratio: Double? = null

    /** Re-applies the crop after the content rect changed size. */
    fun relayoutAspect() = setAspect(ratio, animate = false)

    fun setAspect(ratio: Double?, animate: Boolean) {
        this.ratio = ratio
        if (content.width() <= 0f || content.height() <= 0f) return
        val w = max(1f, content.width()).toInt()
        val h = max(1f, content.height()).toInt()
        val c = CropMath.centered(w * 4, h * 4, ratio, 1.0, align = 1)
        val l = c.left / (w * 4f)
        val t = c.top / (h * 4f)
        val r = (c.left + c.width) / (w * 4f)
        val b = (c.top + c.height) / (h * 4f)
        if (animate) { cl.target = l; ct.target = t; cr.target = r; cb.target = b; kick() }
        else { cl.snap(l); ct.snap(t); cr.snap(r); cb.snap(b); invalidate() }
    }

    fun setContentRotation(deg: Float) { rot.target = Motion.nearestAngle(rot.value, deg); kick() }

    fun blink() {
        curtain.snap(0f)
        curtain.target = 1f
        kick()
        postDelayed({ curtain.target = 0f; kick() }, 70)
    }

    fun focus(phase: FocusPhase, x: Float, y: Float) {
        afPhase = phase
        afX = x
        afY = y
        when (phase) {
            FocusPhase.SCANNING -> { afAmt.target = 1f; afScale.snap(1.5f); afScale.target = 1f; afHideAt = Long.MAX_VALUE }
            FocusPhase.LOCKED, FocusPhase.FAILED -> { afAmt.target = 1f; afScale.target = 0.92f; afHideAt = SystemClock.uptimeMillis() + 1400 }
            FocusPhase.IDLE -> { afAmt.target = 0f }
        }
        kick()
    }

    override fun onDraw(canvas: Canvas) {
        advance()
        val p = palette
        if (content.width() <= 0f) return
        val w = content.width()
        val h = content.height()
        // Crop masks
        val ml = content.left + cl.value * w
        val mt = content.top + ct.value * h
        val mr = content.left + cr.value * w
        val mb = content.top + cb.value * h
        fill.color = p.mask
        if (mt > content.top + 0.5f) canvas.drawRect(content.left, content.top, content.right, mt, fill)
        if (mb < content.bottom - 0.5f) canvas.drawRect(content.left, mb, content.right, content.bottom, fill)
        if (ml > content.left + 0.5f) canvas.drawRect(content.left, mt, ml, mb, fill)
        if (mr < content.right - 0.5f) canvas.drawRect(mr, mt, content.right, mb, fill)
        if (ml > content.left + 0.5f || mt > content.top + 0.5f || mr < content.right - 0.5f || mb < content.bottom - 0.5f) {
            line.color = Palette.withAlpha(p.text, 0.45f)
            line.strokeWidth = ui.dp(1f)
            canvas.drawRect(ml, mt, mr, mb, line)
        }

        // Corner frame marks (classic LCD)
        line.color = Palette.withAlpha(p.text, 0.55f)
        line.strokeWidth = ui.dp(1.5f)
        val k = ui.dp(14f)
        val inset = ui.dp(10f)
        corner(canvas, ml + inset, mt + inset, k, k)
        corner(canvas, mr - inset, mt + inset, -k, k)
        corner(canvas, ml + inset, mb - inset, k, -k)
        corner(canvas, mr - inset, mb - inset, -k, -k)

        // AF reticle
        val now = SystemClock.uptimeMillis()
        if (afPhase != FocusPhase.IDLE && now > afHideAt) { afAmt.target = 0f; kick() }
        val aa = afAmt.value
        if (aa > 0.01f) {
            val color = when (afPhase) {
                FocusPhase.LOCKED -> p.accent
                FocusPhase.FAILED -> p.alert
                else -> p.text
            }
            line.color = Palette.withAlpha(color, aa)
            line.strokeWidth = ui.dp(2f)
            val s = ui.dp(30f) * afScale.value
            val b = s * 0.35f
            corner(canvas, afX - s, afY - s, b, b)
            corner(canvas, afX + s, afY - s, -b, b)
            corner(canvas, afX - s, afY + s, b, -b)
            corner(canvas, afX + s, afY + s, -b, -b)
            if (afPhase == FocusPhase.SCANNING) {
                fill.color = Palette.withAlpha(color, aa * (0.5f + 0.5f * kotlin.math.sin(now / 90.0).toFloat()))
                canvas.drawCircle(afX, afY, ui.dp(2.5f), fill)
            }
        }

        // Rotated info layer (follows how the phone is held)
        canvas.save()
        val ccx = (ml + mr) / 2f
        val ccy = (mt + mb) / 2f
        canvas.translate(ccx, ccy)
        canvas.rotate(rot.value)
        val sideways = (Math.round(rot.value / 90f) % 2) != 0
        val iw = if (sideways) mb - mt else mr - ml
        val ih = if (sideways) mr - ml else mb - mt
        val pad = ui.dp(14f)
        text.typeface = ui.mono
        text.textSize = ui.sp(10.5f)
        text.setShadowLayer(ui.dp(2f), 0f, 0f, Palette.withAlpha(p.deep, 0.9f))
        text.color = p.text
        text.textAlign = Paint.Align.LEFT
        if (infoLeft.isNotEmpty()) canvas.drawText(infoLeft, -iw / 2f + pad, -ih / 2f + pad + text.textSize, text)
        text.textAlign = Paint.Align.RIGHT
        var rightX = iw / 2f - pad
        if (modeBadge.isNotEmpty()) {
            text.textSize = ui.sp(11f)
            val bw = text.measureText(modeBadge) + ui.dp(10f)
            val top = -ih / 2f + pad - ui.dp(2f)
            box.style = Paint.Style.FILL
            box.color = p.accent
            tmp.set(rightX - bw, top, rightX, top + text.textSize + ui.dp(6f))
            canvas.drawRoundRect(tmp, ui.dp(3f), ui.dp(3f), box)
            text.color = p.onAccent
            text.clearShadowLayer()
            canvas.drawText(modeBadge, rightX - ui.dp(5f), top + text.textSize + ui.dp(1f), text)
            text.setShadowLayer(ui.dp(2f), 0f, 0f, Palette.withAlpha(p.deep, 0.9f))
            text.color = p.text
            rightX -= bw + ui.dp(8f)
            text.textSize = ui.sp(10.5f)
        }
        if (infoRight.isNotEmpty()) canvas.drawText(infoRight, rightX, -ih / 2f + pad + text.textSize, text)

        if (recordingSince > 0L) {
            val secs = ((SystemClock.elapsedRealtime() - recordingSince) / 1000L).coerceAtLeast(0)
            val label = String.format(java.util.Locale.US, "REC %02d:%02d", secs / 60, secs % 60)
            text.textAlign = Paint.Align.CENTER
            text.textSize = ui.sp(13f)
            val y = ih / 2f - pad
            val blink = (SystemClock.elapsedRealtime() / 500L) % 2L == 0L
            fill.color = p.alert
            if (blink) canvas.drawCircle(-text.measureText(label) / 2f - ui.dp(10f), y - text.textSize * 0.35f, ui.dp(5f), fill)
            canvas.drawText(label, 0f, y, text)
        }
        if (center.isNotEmpty()) {
            text.textAlign = Paint.Align.CENTER
            text.textSize = ui.sp(13f)
            val lines = center.split('\n')
            var y = -(lines.size - 1) * text.textSize * 0.75f
            for (ln in lines) { canvas.drawText(ln, 0f, y, text); y += text.textSize * 1.5f }
        }
        text.clearShadowLayer()
        canvas.restore()

        // Shutter curtain
        val c = curtain.value
        if (c > 0.01f) {
            fill.color = p.deep
            val half = (content.height() / 2f) * c
            canvas.drawRect(content.left, content.top, content.right, content.top + half, fill)
            canvas.drawRect(content.left, content.bottom - half, content.right, content.bottom, fill)
        }
    }

    private fun corner(canvas: Canvas, x: Float, y: Float, dx: Float, dy: Float) {
        path.reset()
        path.moveTo(x, y + dy)
        path.lineTo(x, y)
        path.lineTo(x + dx, y)
        canvas.drawPath(path, line)
    }

    fun maskRect(out: RectF) {
        out.set(content.left + cl.target * content.width(), content.top + ct.target * content.height(),
            content.left + cr.target * content.width(), content.top + cb.target * content.height())
    }

    fun clampToContent(x: Float, y: Float): Pair<Float, Float> = min(max(x, content.left), content.right) to min(max(y, content.top), content.bottom)
}
