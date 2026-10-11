package dev.panasko.photoliz.ui

import android.content.Context
import android.view.View
import android.view.ViewGroup
import kotlin.math.max

/**
 * Lays out the camera screen. Portrait: chip bar on top, viewfinder, exposure bar, deck at the bottom.
 * Landscape (large screens, free-form windows): the same layout turned 90° — chip column on the left,
 * deck on the right — so the viewfinder always gets the largest possible area.
 */
class CameraRootLayout(context: Context, private val ui: Ui) : ViewGroup(context) {
    lateinit var viewfinder: ViewfinderView
    lateinit var topBar: ChipBar
    lateinit var paramBar: ParamBar
    lateinit var dial: DialStrip
    lateinit var deck: ShutterDeck
    lateinit var toaster: Toaster
    val sheets = ArrayList<Sheet>()

    var insetLeft = 0
    var insetTop = 0
    var insetRight = 0
    var insetBottom = 0

    var onOrientationLayout: ((Boolean) -> Unit)? = null
    var landscape = false
        private set

    private var dialShown = false

    fun showDial(show: Boolean) {
        if (dialShown == show) return
        dialShown = show
        dial.animate().cancel()
        if (show) {
            dial.visibility = View.VISIBLE
            dial.alpha = 0f
            if (landscape) dial.translationX = ui.dp(24f) else dial.translationY = ui.dp(24f)
            dial.animate().alpha(1f).translationX(0f).translationY(0f).setDuration(Motion.MEDIUM).setInterpolator(Motion.EMPHASIZED).start()
        } else {
            dial.animate().alpha(0f).translationX(if (landscape) ui.dp(24f) else 0f).translationY(if (landscape) 0f else ui.dp(24f))
                .setDuration(Motion.FAST).setInterpolator(Motion.ACCELERATE)
                .withEndAction { if (!dialShown) dial.visibility = View.INVISIBLE }.start()
        }
    }

    val isDialShown get() = dialShown

    private fun exact(v: Int) = MeasureSpec.makeMeasureSpec(max(0, v), MeasureSpec.EXACTLY)

    private class Box(var l: Int = 0, var t: Int = 0, var r: Int = 0, var b: Int = 0) {
        val w get() = r - l
        val h get() = b - t
    }

    private val vf = Box()
    private val top = Box()
    private val param = Box()
    private val dialBox = Box()
    private val deckBox = Box()

    private fun compute(w: Int, h: Int) {
        landscape = w > h * 1.05f
        val bar = ui.dp(60)
        val paramT = ui.dp(64)
        val dialT = ui.dp(58)
        val deckT = deck.preferredThickness()
        if (!landscape) {
            top.apply { l = insetLeft; t = insetTop; r = w - insetRight; b = insetTop + bar }
            deckBox.apply { l = insetLeft; t = h - insetBottom - deckT; r = w - insetRight; b = h - insetBottom }
            vf.apply { l = 0; t = top.b; r = w; b = deckBox.t }
            param.apply { l = insetLeft; t = vf.b - paramT; r = w - insetRight; b = vf.b }
            dialBox.apply { l = 0; t = param.t - dialT; r = w; b = param.t }
        } else {
            top.apply { l = insetLeft; t = insetTop; r = insetLeft + bar; b = h - insetBottom }
            deckBox.apply { l = w - insetRight - deckT; t = insetTop; r = w - insetRight; b = h - insetBottom }
            vf.apply { l = top.r; t = 0; r = deckBox.l; b = h }
            param.apply { l = vf.r - ui.dp(92); t = insetTop; r = vf.r; b = h - insetBottom }
            dialBox.apply { l = param.l - dialT; t = 0; r = param.l; b = h }
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        val wasLandscape = landscape
        compute(w, h)
        if (wasLandscape != landscape) onOrientationLayout?.invoke(landscape)
        topBar.vertical = landscape
        paramBar.vertical = landscape
        dial.vertical = landscape
        deck.vertical = landscape
        viewfinder.reserveBottom = if (landscape) 0 else param.h
        viewfinder.reserveRight = if (landscape) param.w else 0
        viewfinder.measure(exact(vf.w), exact(vf.h))
        topBar.measure(exact(top.w), exact(top.h))
        paramBar.measure(exact(param.w), exact(param.h))
        dial.measure(exact(dialBox.w), exact(dialBox.h))
        deck.measure(exact(deckBox.w), exact(deckBox.h))
        toaster.measure(exact(w), exact(h))
        for (s in sheets) {
            s.safeLeft = insetLeft; s.safeTop = insetTop; s.safeRight = insetRight; s.safeBottom = insetBottom
            s.vertical = landscape
            s.measure(exact(w), exact(h))
        }
        setMeasuredDimension(w, h)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        compute(r - l, b - t)
        viewfinder.layout(vf.l, vf.t, vf.r, vf.b)
        topBar.layout(top.l, top.t, top.r, top.b)
        paramBar.layout(param.l, param.t, param.r, param.b)
        dial.layout(dialBox.l, dialBox.t, dialBox.r, dialBox.b)
        deck.layout(deckBox.l, deckBox.t, deckBox.r, deckBox.b)
        toaster.layout(0, 0, r - l, b - t)
        toaster.anchorY = vf.t + viewfinder.contentRect.top + ui.dp(36f)
        for (s in sheets) s.layout(0, 0, r - l, b - t)
    }
}
