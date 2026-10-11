package dev.panasko.photoliz.camera

import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/** Pure, platform independent exposure scales (unit tested). */
object ExposureScales {

    /** Common ISO values in 1/3-stop steps. */
    val ISO_COMMON = intArrayOf(
        25, 32, 40, 50, 64, 80, 100, 125, 160, 200, 250, 320, 400, 500, 640, 800, 1000, 1250,
        1600, 2000, 2500, 3200, 4000, 5000, 6400, 8000, 10000, 12800, 16000, 20000, 25600,
        32000, 40000, 51200, 64000, 80000, 102400,
    )

    class Shutter(val seconds: Double, val label: String) {
        val nanos: Long get() = (seconds * 1e9).roundToLong()
    }

    private fun frac(den: Int) = Shutter(1.0 / den, "1/$den")
    private fun secs(s: Double, label: String) = Shutter(s, label)

    /** Standard shutter speeds, 1/3 stop apart, fastest first. */
    val SHUTTERS: List<Shutter> = listOf(
        frac(32000), frac(25600), frac(20000), frac(16000), frac(12800), frac(10000), frac(8000),
        frac(6400), frac(5000), frac(4000), frac(3200), frac(2500), frac(2000), frac(1600),
        frac(1250), frac(1000), frac(800), frac(640), frac(500), frac(400), frac(320), frac(250),
        frac(200), frac(160), frac(125), frac(100), frac(80), frac(60), frac(50), frac(40),
        frac(30), frac(25), frac(20), frac(15), frac(13), frac(10), frac(8), frac(6), frac(5),
        frac(4), secs(0.3, "0.3\""), secs(0.4, "0.4\""), secs(0.5, "0.5\""), secs(0.6, "0.6\""),
        secs(0.8, "0.8\""), secs(1.0, "1\""), secs(1.3, "1.3\""), secs(1.6, "1.6\""),
        secs(2.0, "2\""), secs(2.5, "2.5\""), secs(3.2, "3.2\""), secs(4.0, "4\""),
        secs(5.0, "5\""), secs(6.0, "6\""), secs(8.0, "8\""), secs(10.0, "10\""),
        secs(13.0, "13\""), secs(15.0, "15\""), secs(20.0, "20\""), secs(25.0, "25\""),
        secs(30.0, "30\""), secs(40.0, "40\""), secs(50.0, "50\""), secs(60.0, "60\""),
    )

    /** Shutter speeds supported inside [minNs, maxNs] (2% tolerance at both ends). */
    fun shuttersIn(minNs: Long, maxNs: Long): List<Shutter> {
        if (maxNs <= 0 || maxNs < minNs) return emptyList()
        val lo = minNs * 0.98
        val hi = maxNs * 1.02
        val list = SHUTTERS.filter { it.nanos >= lo && it.nanos <= hi }
        return list.ifEmpty { listOf(Shutter(maxNs / 1e9, formatShutter(maxNs))) }
    }

    /** Readable label for an arbitrary exposure time. */
    fun formatShutter(ns: Long): String {
        if (ns <= 0) return "--"
        val s = ns / 1e9
        if (s >= 0.3) {
            val nearest = SHUTTERS.minByOrNull { abs(ln(it.seconds / s)) }
            return if (nearest != null && abs(ln(nearest.seconds / s)) < 0.06) nearest.label
            else if (s >= 10) "${s.roundToInt()}\"" else "${(s * 10).roundToInt() / 10.0}\""
        }
        val den = 1.0 / s
        val nearest = SHUTTERS.filter { it.seconds < 0.3 }.minByOrNull { abs(ln(it.seconds / s)) }
        if (nearest != null && abs(ln(nearest.seconds / s)) < 0.06) return nearest.label
        return "1/${den.roundToInt()}"
    }

    /** Common ISO stops inside [min, max]; the exact extremes are added when they are not common. */
    fun isoStopsIn(min: Int, max: Int): IntArray {
        if (max < min || max <= 0) return intArrayOf()
        val set = sortedSetOf<Int>()
        set.add(min)
        set.add(max)
        ISO_COMMON.filter { it in min..max }.forEach { set.add(it) }
        // Drop extremes that sit within 1/12 stop of a common value (avoids 98 next to 100).
        val list = set.toMutableList()
        if (list.size > 2) {
            if (list[0] != list[1] && stopsBetween(list[0].toDouble(), list[1].toDouble()) < 1.0 / 12 && list[1] in ISO_COMMON) list.removeAt(0)
            val n = list.size
            if (n > 2 && stopsBetween(list[n - 2].toDouble(), list[n - 1].toDouble()) < 1.0 / 12 && list[n - 2] in ISO_COMMON) list.removeAt(n - 1)
        }
        return list.toIntArray()
    }

    fun stopsBetween(a: Double, b: Double): Double = abs(ln(b / a) / ln(2.0))

    /**
     * Magnetic snapping for the continuous ISO dial: returns the closest common stop if [iso] is within
     * [thresholdStops] of it, otherwise the rounded continuous value. Always clamped to [min, max].
     */
    fun snapIso(iso: Double, min: Int, max: Int, thresholdStops: Double): Int {
        val clamped = iso.coerceIn(min.toDouble(), max.toDouble())
        var best = -1
        var bestD = Double.MAX_VALUE
        for (s in isoStopsIn(min, max)) {
            val d = stopsBetween(clamped, s.toDouble())
            if (d < bestD) { bestD = d; best = s }
        }
        return if (best > 0 && bestD <= thresholdStops) best else clamped.roundToInt().coerceIn(min, max)
    }

    /** ISO <-> position (in stops above [min]) used by the continuous dial. */
    fun isoToStops(iso: Double, min: Int): Double = ln(iso / min) / ln(2.0)
    fun stopsToIso(stops: Double, min: Int): Double = min * 2.0.pow(stops)

    /** Index of the shutter speed nearest (in log space) to [ns]. */
    fun nearestShutterIndex(list: List<Shutter>, ns: Long): Int {
        if (list.isEmpty()) return -1
        var best = 0
        var bestD = Double.MAX_VALUE
        list.forEachIndexed { i, s ->
            val d = abs(ln(s.nanos.toDouble() / ns.coerceAtLeast(1)))
            if (d < bestD) { bestD = d; best = i }
        }
        return best
    }

    fun formatAperture(f: Float): String =
        if (f <= 0f) "f/--" else if (f < 10f) "f/" + ((f * 10).roundToInt() / 10.0).toString().removeSuffix(".0") else "f/${f.roundToInt()}"

    fun formatNd(stops: Float): String =
        if (stops <= 0.001f) "ND0" else "ND" + ((stops * 10).roundToInt() / 10.0).toString().removeSuffix(".0")
}

/** Aspect crops. Ratios are long side : short side and follow how the phone is held. */
enum class AspectRatio(val id: String, val label: String, val ratio: Double?, val extra: Boolean) {
    TRUE("TRUE", "TRUE", null, false),
    R16_9("16:9", "16:9", 16.0 / 9.0, false),
    R4_3("4:3", "4:3", 4.0 / 3.0, false),
    R239("2.39:1", "2.39:1", 2.39, true),
    R185("1.85:1", "1.85:1", 1.85, true),
    R2_3("2:3", "2:3", 3.0 / 2.0, true),
    R119("1.19:1", "1.19:1", 1.19, true),
    R1_1("1:1", "1:1", 1.0, true);

    companion object {
        fun byId(id: String?): AspectRatio = entries.firstOrNull { it.id == id } ?: TRUE

        /** Cycle order: TRUE, 16:9, 4:3, then the enabled extras in menu order. */
        fun cycle(extras: Set<String>): List<AspectRatio> =
            entries.filter { !it.extra || it.id in extras }
    }
}

/** Centered crop of a [w]x[h] frame for an aspect ratio (long:short) and a digital zoom factor. */
object CropMath {
    class CropRect(val left: Int, val top: Int, val width: Int, val height: Int)

    fun centered(w: Int, h: Int, ratio: Double?, zoom: Double, align: Int = 2): CropRect {
        var cw = w.toDouble()
        var ch = h.toDouble()
        if (ratio != null && ratio > 0) {
            val landscape = w >= h
            val frameRatio = if (landscape) w.toDouble() / h else h.toDouble() / w
            if (ratio >= frameRatio) {
                // Wider than the frame: keep long side, cut short side.
                if (landscape) ch = w / ratio else cw = h / ratio
            } else {
                // Narrower: keep short side, cut long side.
                if (landscape) cw = h * ratio else ch = w * ratio
            }
        }
        val z = zoom.coerceAtLeast(1.0)
        cw /= z
        ch /= z
        var iw = (cw / align).roundToInt() * align
        var ih = (ch / align).roundToInt() * align
        iw = iw.coerceIn(align, w)
        ih = ih.coerceIn(align, h)
        var left = ((w - iw) / 2 / align) * align
        var top = ((h - ih) / 2 / align) * align
        if (left + iw > w) left = w - iw
        if (top + ih > h) top = h - ih
        return CropRect(left.coerceAtLeast(0), top.coerceAtLeast(0), iw, ih)
    }
}
