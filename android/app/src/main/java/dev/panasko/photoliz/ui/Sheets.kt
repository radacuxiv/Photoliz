package dev.panasko.photoliz.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import kotlin.math.max
import kotlin.math.min

/** Transient one-line message at the top of the viewfinder. */
@SuppressLint("ViewConstructor")
class Toaster(context: Context, private val ui: Ui, var palette: Palette) : AnimatedView(context) {
    private var message = ""
    private val amt = spring(0f, 520f, 0.8f)
    private val rot = spring(0f, 260f, 0.78f)
    private val bg = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val hide = Runnable { amt.target = 0f; kick() }
    var anchorY = 0f

    init { isClickable = false }

    fun show(msg: String, millis: Long = 1800) {
        message = msg
        amt.target = 1f
        kick()
        removeCallbacks(hide)
        postDelayed(hide, millis)
    }

    fun setContentRotation(deg: Float) { rot.target = Motion.nearestAngle(rot.value, deg); kick() }

    override fun onTouchEvent(event: MotionEvent?): Boolean = false

    override fun onDraw(canvas: Canvas) {
        advance()
        val a = amt.value
        if (a < 0.01f || message.isEmpty()) return
        text.typeface = ui.mono
        text.textSize = ui.sp(11.5f)
        val tw = min(text.measureText(message) + ui.dp(24f), width - ui.dp(24f))
        val th = ui.dp(30f)
        val cx = width / 2f
        val cy = anchorY + th / 2f + ui.dp(10f) - (1f - a) * ui.dp(14f)
        canvas.save()
        canvas.rotate(rot.value, cx, cy)
        bg.color = Palette.withAlpha(palette.surface, 0.94f * a)
        canvas.drawRoundRect(cx - tw / 2f, cy - th / 2f, cx + tw / 2f, cy + th / 2f, th / 2f, th / 2f, bg)
        bg.color = Palette.withAlpha(palette.accent, a)
        canvas.drawRect(cx - tw / 2f + th / 2f, cy + th / 2f - ui.dp(2f), cx + tw / 2f - th / 2f, cy + th / 2f, bg)
        text.color = Palette.withAlpha(palette.text, a)
        var shown = message
        while (text.measureText(shown) > tw - ui.dp(16f) && shown.length > 4) shown = shown.dropLast(2)
        if (shown != message) shown = shown.dropLast(1) + "…"
        canvas.drawText(shown, cx, cy + text.textSize * 0.36f, text)
        canvas.restore()
    }
}

/** A menu / list row in the old-camera style: pressed rows light up in the accent color. */
@SuppressLint("ViewConstructor")
class MenuRow(context: Context, private val ui: Ui, var palette: Palette) : AnimatedView(context) {
    enum class Style { TOGGLE, TEXT, ACTION, HEADER }

    var title = ""
    var value = ""
    var note = ""
    var on = false
    var style = Style.TEXT
    var icon: PixelIcon? = null
    var iconColor: Int? = null
    var onTap: (() -> Unit)? = null

    private val pressed = spring(0f, 700f, 0.9f)
    private val onAmt = spring(0f, 520f, 0.85f)
    private val bg = Paint(Paint.ANTI_ALIAS_FLAG)
    private val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.RIGHT }
    private val iconPaint = Paint()
    private val rect = RectF()
    private var noteLayout: StaticLayout? = null
    private var noteWidth = -1

    fun refresh() {
        onAmt.target = if (on) 1f else 0f
        noteWidth = -1
        requestLayout()
        kick()
    }

    private fun buildNote(width: Int): StaticLayout? {
        if (note.isEmpty() || width <= 0) return null
        if (noteWidth == width && noteLayout != null) return noteLayout
        val p = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = ui.monoRegular; textSize = ui.sp(10.5f) }
        noteLayout = StaticLayout.Builder.obtain(note, 0, note.length, p, width).setAlignment(Layout.Alignment.ALIGN_NORMAL).setLineSpacing(0f, 1.1f).build()
        noteWidth = width
        return noteLayout
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val base = if (style == Style.HEADER) ui.dp(40) else ui.dp(54)
        val nl = buildNote(w - ui.dp(36))
        setMeasuredDimension(w, base + (nl?.height?.plus(ui.dp(8)) ?: 0))
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (onTap == null || style == Style.HEADER || !isEnabled) return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { pressed.target = 1f; kick() }
            MotionEvent.ACTION_UP -> { pressed.target = 0f; kick(); if (e.y in 0f..height.toFloat()) { Haptics.press(this); performClick() } }
            MotionEvent.ACTION_CANCEL -> { pressed.target = 0f; kick() }
        }
        return true
    }

    override fun performClick(): Boolean { super.performClick(); onTap?.invoke(); return true }

    override fun onDraw(canvas: Canvas) {
        advance()
        val p = palette
        val w = width.toFloat()
        val pr = pressed.value
        val pad = ui.dp(18f)
        val base = if (style == Style.HEADER) ui.dp(40f) else ui.dp(54f)
        if (pr > 0.01f) {
            bg.color = Palette.withAlpha(p.accent, pr)
            rect.set(ui.dp(6f), ui.dp(2f), w - ui.dp(6f), height - ui.dp(2f))
            canvas.drawRoundRect(rect, ui.dp(8f), ui.dp(8f), bg)
        }
        val fg = Palette.mix(p.text, p.onAccent, pr)
        val alpha = if (isEnabled) 1f else 0.35f
        if (style == Style.HEADER) {
            titlePaint.typeface = ui.condensed
            titlePaint.textSize = ui.sp(11f)
            titlePaint.letterSpacing = 0.18f
            titlePaint.color = Palette.withAlpha(p.accent.takeIf { Palette.contrast(it, p.surface) > 2.2 } ?: p.textDim, 1f)
            canvas.drawText(title, pad, base * 0.72f, titlePaint)
            bg.color = p.line
            canvas.drawRect(pad, base - ui.dp(1f), w - pad, base, bg)
            return
        }
        var x = pad
        icon?.let {
            iconPaint.color = Palette.withAlpha(iconColor ?: fg, alpha)
            it.draw(canvas, x + ui.dp(9f), base / 2f, ui.dp(18f), iconPaint)
            x += ui.dp(28f)
        }
        titlePaint.typeface = ui.mono
        titlePaint.textSize = ui.sp(13.5f)
        titlePaint.letterSpacing = 0f
        titlePaint.color = Palette.withAlpha(fg, alpha)
        canvas.drawText(title, x, base / 2f + titlePaint.textSize * 0.35f, titlePaint)

        val vx = w - pad
        valuePaint.typeface = ui.mono
        valuePaint.textSize = ui.sp(12f)
        when (style) {
            Style.TOGGLE -> {
                val o = onAmt.value
                val bw = ui.dp(48f)
                val bh = ui.dp(24f)
                rect.set(vx - bw, base / 2f - bh / 2f, vx, base / 2f + bh / 2f)
                bg.color = Palette.withAlpha(Palette.mix(p.surface2, p.accent, o), alpha)
                canvas.drawRoundRect(rect, ui.dp(4f), ui.dp(4f), bg)
                valuePaint.textAlign = Paint.Align.CENTER
                valuePaint.color = Palette.withAlpha(Palette.mix(p.textDim, p.onAccent, o), alpha)
                canvas.drawText(if (on) "ON" else "OFF", rect.centerX(), rect.centerY() + valuePaint.textSize * 0.36f, valuePaint)
                valuePaint.textAlign = Paint.Align.RIGHT
            }
            Style.TEXT, Style.ACTION -> {
                valuePaint.color = Palette.withAlpha(Palette.mix(p.textDim, p.onAccent, pr), alpha)
                var v = value
                val maxW = w - x - titlePaint.measureText(title) - pad * 2.5f
                while (v.length > 3 && valuePaint.measureText(v) > maxW) v = v.drop(2)
                if (v != value && v.length > 1) v = "…" + v.drop(1)
                val suffix = if (style == Style.ACTION) " ›" else ""
                canvas.drawText(v + suffix, vx, base / 2f + valuePaint.textSize * 0.36f, valuePaint)
            }
            Style.HEADER -> Unit
        }
        buildNote((w - pad * 2).toInt())?.let { nl ->
            canvas.save()
            canvas.translate(pad, base - ui.dp(6f))
            nl.paint.color = Palette.withAlpha(Palette.mix(p.textDim, p.onAccent, pr), 0.75f * alpha)
            nl.draw(canvas)
            canvas.restore()
        }
        bg.color = p.line
        canvas.drawRect(pad, height - ui.dp(1f), w - pad, height.toFloat(), bg)
    }
}

/** A row of small toggle chips (extra crops, palettes). */
@SuppressLint("ViewConstructor")
class ChoiceRow(context: Context, private val ui: Ui, var palette: Palette) : AnimatedView(context) {
    class Choice(val id: String, val label: String, val swatches: IntArray? = null)

    var choices: List<Choice> = emptyList()
    var selected: Set<String> = emptySet()
    var onToggle: ((String) -> Unit)? = null

    private val bg = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val rects = ArrayList<RectF>()
    private var pressedIndex = -1

    private fun chipW(c: Choice): Float {
        text.typeface = ui.mono
        text.textSize = ui.sp(12f)
        return max(ui.dp(64f), text.measureText(c.label) + ui.dp(22f))
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        rects.clear()
        val pad = ui.dp(18f)
        val gap = ui.dp(8f)
        val ch = if (choices.any { it.swatches != null }) ui.dp(58f) else ui.dp(36f)
        var x = pad
        var y = ui.dp(6f)
        for (c in choices) {
            val cw = chipW(c)
            if (x + cw > w - pad && x > pad) { x = pad; y += ch + gap }
            rects.add(RectF(x, y, x + cw, y + ch))
            x += cw + gap
        }
        val h = (rects.lastOrNull()?.bottom ?: 0f) + ui.dp(12f)
        setMeasuredDimension(w, h.toInt())
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        val idx = rects.indexOfFirst { it.contains(e.x, e.y) }
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { pressedIndex = idx; invalidate(); return idx >= 0 }
            MotionEvent.ACTION_UP -> {
                if (idx >= 0 && idx == pressedIndex) { Haptics.press(this); onToggle?.invoke(choices[idx].id) }
                pressedIndex = -1; invalidate()
            }
            MotionEvent.ACTION_CANCEL -> { pressedIndex = -1; invalidate() }
        }
        return true
    }

    override fun onDraw(canvas: Canvas) {
        advance()
        val p = palette
        choices.forEachIndexed { i, c ->
            val r = rects.getOrNull(i) ?: return@forEachIndexed
            val sel = c.id in selected
            bg.style = Paint.Style.FILL
            bg.color = when {
                i == pressedIndex -> Palette.withAlpha(p.accent, 0.6f)
                sel -> p.accent
                else -> p.surface2
            }
            canvas.drawRoundRect(r, ui.dp(6f), ui.dp(6f), bg)
            text.typeface = ui.mono
            text.textSize = ui.sp(12f)
            text.color = if (sel) p.onAccent else p.text
            val sw = c.swatches
            if (sw != null) {
                val n = min(sw.size, 6)
                val s = ui.dp(9f)
                val total = n * s + (n - 1) * ui.dp(2f)
                var sx = r.centerX() - total / 2f
                for (k in 0 until n) {
                    bg.color = sw[k]
                    canvas.drawRect(sx, r.top + ui.dp(9f), sx + s, r.top + ui.dp(9f) + s, bg)
                    sx += s + ui.dp(2f)
                }
                canvas.drawText(c.label, r.centerX(), r.bottom - ui.dp(12f), text)
            } else {
                canvas.drawText(c.label, r.centerX(), r.centerY() + text.textSize * 0.36f, text)
            }
        }
    }
}

/** Wrapped paragraph text. */
@SuppressLint("ViewConstructor")
class TextBlock(context: Context, private val ui: Ui, var palette: Palette) : View(context) {
    var text: CharSequence = ""
        set(v) { field = v; layout = null; requestLayout() }
    var size = 12.5f
    var dim = false
    private var layout: StaticLayout? = null

    private fun build(w: Int): StaticLayout? {
        if (w <= 0) return null
        val l = layout
        if (l != null && l.width == w) return l
        val p = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = ui.monoRegular; textSize = ui.sp(size) }
        return StaticLayout.Builder.obtain(text, 0, text.length, p, w).setLineSpacing(0f, 1.18f).build().also { layout = it }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val l = build(w - ui.dp(36))
        setMeasuredDimension(w, (l?.height ?: 0) + ui.dp(16))
    }

    override fun onDraw(canvas: Canvas) {
        val l = build(width - ui.dp(36)) ?: return
        l.paint.color = if (dim) Palette.withAlpha(palette.textDim, 0.75f) else palette.text
        canvas.save()
        canvas.translate(ui.dp(18f), ui.dp(8f))
        l.draw(canvas)
        canvas.restore()
    }
}

/** Big confirmation button. */
@SuppressLint("ViewConstructor")
class WideButton(context: Context, private val ui: Ui, var palette: Palette) : AnimatedView(context) {
    var label = ""
    var onTap: (() -> Unit)? = null
    private val press = spring(1f, 900f, 0.6f)
    private val bg = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val rect = RectF()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), ui.dp(76))
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { press.target = 0.95f; kick() }
            MotionEvent.ACTION_UP -> { press.target = 1f; kick(); if (e.y in 0f..height.toFloat()) { Haptics.confirm(this); performClick() } }
            MotionEvent.ACTION_CANCEL -> { press.target = 1f; kick() }
        }
        return true
    }

    override fun performClick(): Boolean { super.performClick(); onTap?.invoke(); return true }

    override fun onDraw(canvas: Canvas) {
        advance()
        val s = press.value
        canvas.save()
        canvas.scale(s, s, width / 2f, height / 2f)
        rect.set(ui.dp(18f), ui.dp(12f), width - ui.dp(18f), height - ui.dp(12f))
        bg.color = palette.accent
        canvas.drawRoundRect(rect, ui.dp(10f), ui.dp(10f), bg)
        text.typeface = ui.mono
        text.textSize = ui.sp(14f)
        text.letterSpacing = 0.1f
        text.color = palette.onAccent
        canvas.drawText(label, rect.centerX(), rect.centerY() + text.textSize * 0.36f, text)
        canvas.restore()
    }
}

/**
 * Modal sheet: scrim + panel sliding in from the bottom (portrait) or the right (landscape).
 * Content rows go into [list]; an optional [footer] stays pinned.
 */
@SuppressLint("ViewConstructor")
class Sheet(context: Context, private val ui: Ui, var palette: Palette, title: String, private val icon: PixelIcon?) : ViewGroup(context) {
    var dismissible = true
    var onDismiss: (() -> Unit)? = null
    var vertical = false
        set(v) { field = v; requestLayout() }

    private val header = SheetHeader(context, ui, palette, title, icon)
    val scroll = ScrollView(context)
    val list = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    var footer: View? = null
        set(v) {
            field?.let { removeView(it) }
            field = v
            v?.let { addView(it) }
        }

    private val panelRect = RectF()
    private val scrim = Paint()
    private val panelPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var shownAmt = 0f
    var isShowing = false
        private set

    var title: String
        get() = header.title
        set(v) { header.title = v; header.invalidate() }

    init {
        setWillNotDraw(false)
        scroll.isVerticalScrollBarEnabled = false
        scroll.overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
        scroll.addView(list)
        addView(header)
        addView(scroll)
        header.onClose = { if (dismissible) dismiss() }
        visibility = View.GONE
    }

    fun setPaletteAll(p: Palette) {
        palette = p
        header.palette = p
        header.invalidate()
        for (i in 0 until list.childCount) {
            when (val c = list.getChildAt(i)) {
                is MenuRow -> { c.palette = p; c.invalidate() }
                is ChoiceRow -> { c.palette = p; c.invalidate() }
                is TextBlock -> { c.palette = p; c.invalidate() }
                is WideButton -> { c.palette = p; c.invalidate() }
            }
        }
        (footer as? WideButton)?.let { it.palette = p; it.invalidate() }
        invalidate()
    }

    fun show() {
        if (isShowing) return
        isShowing = true
        visibility = View.VISIBLE
        header.showClose = dismissible
        scroll.scrollTo(0, 0)
        animateTo(1f)
    }

    fun dismiss() {
        if (!isShowing) return
        isShowing = false
        animateTo(0f) { visibility = View.GONE }
        onDismiss?.invoke()
    }

    private var animator: android.animation.ValueAnimator? = null

    private fun animateTo(target: Float, end: (() -> Unit)? = null) {
        animator?.cancel()
        val a = android.animation.ValueAnimator.ofFloat(shownAmt, target)
        a.duration = if (target > shownAmt) Motion.SLOW else Motion.MEDIUM
        a.interpolator = if (target > shownAmt) Motion.EMPHASIZED else Motion.ACCELERATE
        a.addUpdateListener {
            shownAmt = it.animatedValue as Float
            applyProgress()
        }
        a.addListener(object : android.animation.AnimatorListenerAdapter() {
            private var cancelled = false
            override fun onAnimationCancel(animation: android.animation.Animator) { cancelled = true }
            override fun onAnimationEnd(animation: android.animation.Animator) { if (!cancelled) end?.invoke() }
        })
        animator = a
        a.start()
    }

    private fun applyProgress() {
        val off = 1f - shownAmt
        val dx = if (vertical) panelRect.width() * off else 0f
        val dy = if (vertical) 0f else panelRect.height() * off
        for (v in listOfNotNull(header, scroll, footer)) { v.translationX = dx; v.translationY = dy }
        invalidate()
    }

    /** Insets the panel must keep clear (system bars / cutouts). */
    var safeLeft = 0
    var safeTop = 0
    var safeRight = 0
    var safeBottom = 0

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        computePanel(w, h)
        val pw = panelRect.width().toInt()
        val headerH = ui.dp(58)
        header.measure(MeasureSpec.makeMeasureSpec(pw, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(headerH, MeasureSpec.EXACTLY))
        val f = footer
        f?.measure(MeasureSpec.makeMeasureSpec(pw, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
        val footH = f?.measuredHeight ?: 0
        val listH = (panelRect.height().toInt() - headerH - footH - safeBottom).coerceAtLeast(0)
        scroll.measure(MeasureSpec.makeMeasureSpec(pw, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(listH, MeasureSpec.AT_MOST))
        setMeasuredDimension(w, h)
    }

    private fun computePanel(w: Int, h: Int) {
        if (vertical) {
            val pw = min(w * 0.62f, ui.dp(440f)).coerceAtLeast(min(w.toFloat(), ui.dp(300f)))
            panelRect.set(w - pw - safeRight, safeTop.toFloat(), (w - safeRight).toFloat(), h.toFloat())
        } else {
            val maxH = (h - safeTop) * 0.86f
            val pw = min(w.toFloat(), ui.dp(560f))
            // Height follows content, measured lazily.
            val content = ui.dp(58) + (list.measuredHeight.takeIf { it > 0 } ?: (h / 2)) + (footer?.measuredHeight ?: 0) + safeBottom
            val ph = min(maxH, content.toFloat()).coerceAtLeast(min(maxH, ui.dp(240f)))
            panelRect.set((w - pw) / 2f, h - ph, (w + pw) / 2f, h.toFloat())
        }
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        // Measure list once to know its natural height, then lay out the panel.
        list.measure(MeasureSpec.makeMeasureSpec(panelRect.width().toInt(), MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
        computePanel(r - l, b - t)
        val pl = panelRect.left.toInt()
        var y = panelRect.top.toInt()
        header.layout(pl, y, pl + header.measuredWidth, y + header.measuredHeight)
        y += header.measuredHeight
        val f = footer
        val footH = f?.measuredHeight ?: 0
        val listH = (panelRect.bottom.toInt() - safeBottom - footH - y).coerceAtLeast(0)
        scroll.measure(MeasureSpec.makeMeasureSpec(panelRect.width().toInt(), MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(listH, MeasureSpec.EXACTLY))
        scroll.layout(pl, y, pl + scroll.measuredWidth, y + listH)
        f?.layout(pl, y + listH, pl + f.measuredWidth, y + listH + footH)
        applyProgress()
    }

    override fun onDraw(canvas: Canvas) {
        scrim.color = Palette.withAlpha(palette.deep, 0.62f * shownAmt)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), scrim)
        val off = 1f - shownAmt
        val dx = if (vertical) panelRect.width() * off else 0f
        val dy = if (vertical) 0f else panelRect.height() * off
        panelPaint.color = palette.surface
        val r = ui.dp(18f)
        canvas.save()
        canvas.translate(dx, dy)
        canvas.drawRoundRect(panelRect.left, panelRect.top, panelRect.right, panelRect.bottom + r, r, r, panelPaint)
        canvas.restore()
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (!isShowing) return false
        if (e.actionMasked == MotionEvent.ACTION_UP && !panelRect.contains(e.x, e.y) && dismissible) dismiss()
        return true
    }
}

@SuppressLint("ViewConstructor")
class SheetHeader(context: Context, private val ui: Ui, var palette: Palette, var title: String, private val icon: PixelIcon?) : View(context) {
    var showClose = true
    var onClose: (() -> Unit)? = null
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val iconPaint = Paint()

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.actionMasked == MotionEvent.ACTION_UP && showClose && e.x > width - ui.dp(64f)) { Haptics.press(this); onClose?.invoke() }
        return true
    }

    override fun onDraw(canvas: Canvas) {
        val p = palette
        val cy = height / 2f + ui.dp(3f)
        // Grip
        paint.color = p.line
        canvas.drawRoundRect(width / 2f - ui.dp(18f), ui.dp(7f), width / 2f + ui.dp(18f), ui.dp(10f), ui.dp(2f), ui.dp(2f), paint)
        var x = ui.dp(18f)
        icon?.let {
            iconPaint.color = p.accent.takeIf { c -> Palette.contrast(c, p.surface) > 2.2 } ?: p.text
            it.draw(canvas, x + ui.dp(10f), cy, ui.dp(20f), iconPaint)
            x += ui.dp(30f)
        }
        paint.typeface = ui.mono
        paint.textSize = ui.sp(16f)
        paint.letterSpacing = 0.08f
        paint.color = p.text
        paint.textAlign = Paint.Align.LEFT
        canvas.drawText(title, x, cy + paint.textSize * 0.36f, paint)
        if (showClose) {
            iconPaint.color = p.textDim
            PixelIcons.CROSS.draw(canvas, width - ui.dp(34f), cy, ui.dp(18f), iconPaint)
        }
        paint.color = p.line
        canvas.drawRect(ui.dp(18f), height - ui.dp(1f), width - ui.dp(18f), height.toFloat(), paint)
    }
}
