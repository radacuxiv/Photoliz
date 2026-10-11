package dev.panasko.photoliz.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.util.TypedValue
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * UI palettes. Each one maps the user's color sets (main / secondary / contrast / highlights) onto roles.
 */
class Palette(
    val name: String,
    /** Window background around the viewfinder. */
    val bg: Int,
    /** Letterbox / deepest black. */
    val deep: Int,
    /** Panels (menus, chips). */
    val surface: Int,
    val surface2: Int,
    /** Camera "body" color: shutter, knobs. */
    val body: Int,
    val bodyLight: Int,
    val bodyDark: Int,
    /** Selection highlight. */
    val accent: Int,
    /** Recording / warnings. */
    val alert: Int,
    val warn: Int,
    /** Dark ink used on light fills. */
    val ink: Int,
    val text: Int,
    val textDim: Int,
    /** The palette exactly as defined (main, secondary, contrast, highlights). */
    val swatches: IntArray,
) {
    val line: Int = withAlpha(text, 0.24f)
    val lineStrong: Int = withAlpha(text, 0.55f)
    val onAccent: Int = if (contrast(accent, ink) >= contrast(accent, text)) ink else text
    val onBody: Int = if (contrast(body, ink) >= contrast(body, text)) ink else text
    val scrim: Int = withAlpha(deep, 0.72f)
    val mask: Int = withAlpha(deep, 0.86f)

    companion object {
        fun c(hex: String): Int = Color.parseColor(hex)

        fun withAlpha(color: Int, a: Float): Int = (color and 0x00FFFFFF) or ((a.coerceIn(0f, 1f) * 255f + 0.5f).toInt() shl 24)

        fun mix(a: Int, b: Int, t: Float): Int {
            val r = (Color.red(a) + (Color.red(b) - Color.red(a)) * t).toInt()
            val g = (Color.green(a) + (Color.green(b) - Color.green(a)) * t).toInt()
            val bl = (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t).toInt()
            return Color.rgb(r, g, bl)
        }

        private fun lin(c: Int): Double { val v = c / 255.0; return if (v <= 0.04045) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4) }
        fun luminance(color: Int) = 0.2126 * lin(Color.red(color)) + 0.7152 * lin(Color.green(color)) + 0.0722 * lin(Color.blue(color))
        fun contrast(a: Int, b: Int): Double { val la = luminance(a); val lb = luminance(b); return (max(la, lb) + 0.05) / (min(la, lb) + 0.05) }
    }
}

object Palettes {
    private fun c(h: String) = Palette.c(h)
    private fun arr(vararg h: String) = IntArray(h.size) { c(h[it]) }

    val CANONICAL = Palette(
        name = "Canonical",
        bg = c("#111214"), deep = c("#050505"), surface = c("#26282B"), surface2 = c("#333333"),
        body = c("#B3B5B8"), bodyLight = c("#D0D2D5"), bodyDark = c("#8A8D91"),
        accent = c("#1C61B2"), alert = c("#D32F2F"), warn = c("#333333"), ink = c("#111214"),
        text = c("#FFFFFF"), textDim = c("#EAEBED"),
        swatches = arr("#B3B5B8", "#8A8D91", "#D0D2D5", "#1C61B2", "#D32F2F", "#333333", "#111214", "#050505", "#26282B", "#FFFFFF", "#EAEBED"),
    )

    val EUROPE = Palette(
        name = "Europe",
        bg = c("#313638"), deep = Palette.mix(c("#313638"), Color.BLACK, 0.55f), surface = Palette.mix(c("#313638"), c("#F9FCFB"), 0.08f),
        surface2 = Palette.mix(c("#313638"), c("#A4B0B4"), 0.25f),
        body = c("#5B93C7"), bodyLight = c("#86B5DF"), bodyDark = c("#3D73A9"),
        accent = c("#E67300"), alert = c("#C4520A"), warn = c("#F39C12"), ink = c("#313638"),
        text = c("#F9FCFB"), textDim = c("#D1D8DD"),
        swatches = arr("#5B93C7", "#86B5DF", "#3D73A9", "#E67300", "#C4520A", "#F39C12", "#313638", "#A4B0B4", "#8B3E1A", "#F9FCFB", "#D1D8DD"),
    )

    val GAEA = Palette(
        name = "Gaea",
        bg = c("#14061A"), deep = Palette.mix(c("#14061A"), Color.BLACK, 0.5f), surface = c("#2C0D32"), surface2 = c("#471D50"),
        body = c("#C0366D"), bodyLight = c("#DE6F95"), bodyDark = c("#892A4E"),
        accent = c("#E8E0D2"), alert = c("#DE6F95"), warn = c("#EEDCCB"), ink = c("#2C0D32"),
        text = c("#F6F4EE"), textDim = c("#DBD3C9"),
        swatches = arr("#C0366D", "#DE6F95", "#892A4E", "#E8E0D2", "#A6927D", "#EEDCCB", "#2C0D32", "#471D50", "#14061A", "#F6F4EE", "#DBD3C9"),
    )

    val ISLAND = Palette(
        name = "Island",
        bg = c("#131818"), deep = c("#0A0D0D"), surface = c("#222B2A"), surface2 = c("#2C3E3B"),
        body = c("#3D524E"), bodyLight = c("#68766B"), bodyDark = c("#1E2B29"),
        accent = c("#87948B"), alert = c("#C6D0CA"), warn = c("#68766B"), ink = c("#0A0D0D"),
        text = c("#C6D0CA"), textDim = c("#A8B4AC"),
        swatches = arr("#2C3E3B", "#3D524E", "#1E2B29", "#4C5B52", "#68766B", "#87948B", "#131818", "#0A0D0D", "#222B2A", "#A8B4AC", "#C6D0CA"),
    )

    val ALL = listOf(CANONICAL, EUROPE, GAEA, ISLAND)

    fun byName(name: String?) = ALL.firstOrNull { it.name == name } ?: CANONICAL
}

/** Shared typefaces and metrics. */
class Ui(context: Context) {
    private val metrics = context.resources.displayMetrics
    val density: Float = metrics.density

    fun dp(v: Float): Float = v * density
    fun dp(v: Int): Int = (v * density + 0.5f).toInt()

    /** Font size honoring the user's font scale, capped so the camera layout cannot break. */
    fun sp(v: Float): Float = min(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, metrics), v * density * 1.3f)

    val mono: Typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    val monoRegular: Typeface = Typeface.MONOSPACE
    val condensed: Typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
}
