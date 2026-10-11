package dev.panasko.photoliz.processing

import java.nio.ShortBuffer
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/** Lens shading gain map, [R, Geven, Godd, B] per grid point: gains[(row * cols + col) * 4 + ch]. */
class ShadingGrid(val rows: Int, val cols: Int, val gains: FloatArray)

/**
 * Everything the plain RAW develop needs, already resolved from the camera metadata.
 * Coordinates are in raw pixels; [rowStride] is measured in 16-bit samples.
 */
class DevelopParams(
    val width: Int,
    val height: Int,
    val rowStride: Int,
    /** SENSOR_INFO_COLOR_FILTER_ARRANGEMENT: 0 RGGB, 1 GRBG, 2 GBRG, 3 BGGR, 5 MONO, 6 NIR. */
    val cfa: Int,
    /** Black level per 2x2 position in raster order. */
    val black: FloatArray,
    val white: Float,
    val wbMul: FloatArray,
    /** White-balanced camera RGB -> linear sRGB, row-major. */
    val matrix: FloatArray,
    val shading: ShadingGrid?,
    val shadeLeft: Int,
    val shadeTop: Int,
    val shadeWidth: Int,
    val shadeHeight: Int,
    val cropLeft: Int,
    val cropTop: Int,
    val cropWidth: Int,
    val cropHeight: Int,
    /** 2x2 "superpixel" develop at half resolution (used for huge sensors / low memory). */
    val half: Boolean,
)

/**
 * CPU RAW develop: black/white levels, lens shading gain map, as-shot white balance, Malvar-He-Cutler
 * demosaic, sensor color matrix, sRGB transfer curve. Nothing else.
 */
object RawCore {
    const val CFA_MONO = 5
    const val CFA_NIR = 6

    private val PATTERNS = arrayOf(
        intArrayOf(0, 1, 1, 2), // RGGB
        intArrayOf(1, 0, 2, 1), // GRBG
        intArrayOf(1, 2, 0, 1), // GBRG
        intArrayOf(2, 1, 1, 0), // BGGR
    )

    const val LUT_SIZE = 16384
    private const val LUT_MAX = (LUT_SIZE - 1).toFloat()

    val SRGB_LUT: IntArray by lazy {
        IntArray(LUT_SIZE) { i ->
            val v = i / (LUT_SIZE - 1.0)
            val s = if (v <= 0.0031308) 12.92 * v else 1.055 * v.pow(1.0 / 2.4) - 0.055
            (s * 255.0 + 0.5).toInt().coerceIn(0, 255)
        }
    }

    fun supports(cfa: Int) = cfa in 0..3 || cfa == CFA_MONO || cfa == CFA_NIR

    fun outWidth(p: DevelopParams) = if (p.half) p.cropWidth / 2 else p.cropWidth
    fun outHeight(p: DevelopParams) = if (p.half) p.cropHeight / 2 else p.cropHeight

    fun interface RowSink {
        /** Called from worker threads with [count] finished rows starting at output row [y0]. */
        fun rows(y0: Int, count: Int, argb: IntArray, width: Int)
    }

    fun develop(src: ShortBuffer, p: DevelopParams, pool: ExecutorService?, sink: RowSink) {
        require(supports(p.cfa)) { "Unsupported color filter arrangement ${p.cfa}" }
        require(p.cropWidth >= 4 && p.cropHeight >= 4) { "Crop too small" }
        require(p.cropLeft >= 0 && p.cropTop >= 0 && p.cropLeft + p.cropWidth <= p.width && p.cropTop + p.cropHeight <= p.height) { "Crop outside image" }
        val outH = outHeight(p)
        val chunk = if (p.half) 32 else 64
        val tasks = ArrayList<Callable<Unit>>()
        var y = 0
        while (y < outH) {
            val y0 = y
            val y1 = min(outH, y + chunk)
            tasks.add(Callable {
                val sb = src.duplicate()
                if (p.half) developHalf(sb, p, y0, y1, sink) else developFull(sb, p, y0, y1, sink)
            })
            y = y1
        }
        if (pool == null) {
            tasks.forEach { it.call() }
        } else {
            try {
                pool.invokeAll(tasks).forEach { it.get() }
            } catch (e: ExecutionException) {
                throw e.cause ?: e
            }
        }
    }

    private fun mirror(v: Int, size: Int): Int {
        var x = v
        if (x < 0) x = -x
        if (x >= size) x = 2 * size - 2 - x
        return x.coerceIn(0, size - 1)
    }

    private class Shade(p: DevelopParams) {
        val grid = p.shading
        private val cols = grid?.cols ?: 0
        private val rows = grid?.rows ?: 0
        val rowGain = FloatArray(max(1, cols) * 4)
        private val sx = if (cols > 1 && p.shadeWidth > 1) (cols - 1f) / (p.shadeWidth - 1f) else 0f
        private val sy = if (rows > 1 && p.shadeHeight > 1) (rows - 1f) / (p.shadeHeight - 1f) else 0f
        private val left = p.shadeLeft
        private val top = p.shadeTop

        fun colPos(x: Int, c0: IntArray, c1: IntArray, f: FloatArray, i: Int) {
            if (grid == null || cols < 2) { c0[i] = 0; c1[i] = 0; f[i] = 0f; return }
            val g = ((x - left) * sx).coerceIn(0f, cols - 1f)
            val a = min(g.toInt(), cols - 2)
            c0[i] = a; c1[i] = a + 1; f[i] = g - a
        }

        /** Interpolates the 4 channel gains of every grid column for raw row [y] into [rowGain]. */
        fun prepareRow(y: Int) {
            val gr = grid ?: return
            if (rows < 2) {
                System.arraycopy(gr.gains, 0, rowGain, 0, min(rowGain.size, gr.gains.size))
                return
            }
            val g = ((y - top) * sy).coerceIn(0f, rows - 1f)
            val r0 = min(g.toInt(), rows - 2)
            val fy = g - r0
            val base0 = r0 * cols * 4
            val base1 = (r0 + 1) * cols * 4
            for (k in 0 until cols * 4) {
                val a = gr.gains[base0 + k]
                rowGain[k] = a + (gr.gains[base1 + k] - a) * fy
            }
        }
    }

    private fun developFull(sb: ShortBuffer, p: DevelopParams, oy0: Int, oy1: Int, sink: RowSink) {
        val w = p.width
        val h = p.height
        val cw = p.cropWidth
        val bw = cw + 4
        val rows = oy1 - oy0
        val bh = rows + 4
        val lin = FloatArray(bw * bh)
        val x0 = p.cropLeft - 2
        val y0 = p.cropTop + oy0 - 2
        val rxStart = max(0, x0)
        val rxEnd = min(w, x0 + bw)
        val rowBuf = ShortArray(rxEnd - rxStart)
        val mono = p.cfa == CFA_MONO || p.cfa == CFA_NIR
        val pattern = if (mono) intArrayOf(1, 1, 1, 1) else PATTERNS[p.cfa]
        val shade = Shade(p)
        val colRaw = IntArray(bw)
        val colIdx = IntArray(bw)
        val sc0 = IntArray(bw)
        val sc1 = IntArray(bw)
        val sf = FloatArray(bw)
        for (c in 0 until bw) {
            val x = mirror(x0 + c, w)
            colRaw[c] = x
            colIdx[c] = (x - rxStart).coerceIn(0, rowBuf.size - 1)
            shade.colPos(x, sc0, sc1, sf, c)
        }
        val invRange = FloatArray(4) { 1f / max(1f, p.white - p.black[it]) }
        val wb = if (mono) floatArrayOf(1f, 1f, 1f) else p.wbMul
        val hasShade = shade.grid != null
        val rg = shade.rowGain

        for (r in 0 until bh) {
            val y = mirror(y0 + r, h)
            sb.position(y * p.rowStride + rxStart)
            sb.get(rowBuf, 0, rowBuf.size)
            if (hasShade) shade.prepareRow(y)
            val yOdd = y and 1
            val base = r * bw
            for (c in 0 until bw) {
                val x = colRaw[c]
                val pi = (yOdd shl 1) or (x and 1)
                val color = pattern[pi]
                var v = ((rowBuf[colIdx[c]].toInt() and 0xFFFF) - p.black[pi]) * invRange[pi]
                if (hasShade) {
                    val ch = if (mono) 0 else when (color) { 0 -> 0; 2 -> 3; else -> if (yOdd == 0) 1 else 2 }
                    val g0 = rg[sc0[c] * 4 + ch]
                    v *= g0 + (rg[sc1[c] * 4 + ch] - g0) * sf[c]
                }
                v *= wb[color]
                if (v > 1f) v = 1f
                lin[base + c] = v
            }
        }

        val m = p.matrix
        val lut = SRGB_LUT
        val out = IntArray(cw * rows)
        for (j in 0 until rows) {
            val y = p.cropTop + oy0 + j
            val yOdd = y and 1
            val rowBase = (j + 2) * bw + 2
            val outBase = j * cw
            for (i in 0 until cw) {
                val x = p.cropLeft + i
                val pi = (yOdd shl 1) or (x and 1)
                val idx = rowBase + i
                val cC = lin[idx]
                if (mono) {
                    val li = (cC.coerceIn(0f, 1f) * LUT_MAX + 0.5f).toInt()
                    val g = lut[li]
                    out[outBase + i] = -0x1000000 or (g shl 16) or (g shl 8) or g
                    continue
                }
                val color = pattern[pi]
                val n = lin[idx - bw]
                val s = lin[idx + bw]
                val wv = lin[idx - 1]
                val e = lin[idx + 1]
                val nn = lin[idx - 2 * bw]
                val ss = lin[idx + 2 * bw]
                val ww = lin[idx - 2]
                val ee = lin[idx + 2]
                val rr: Float
                val gg: Float
                val bb: Float
                if (color == 1) {
                    val diag = lin[idx - bw - 1] + lin[idx - bw + 1] + lin[idx + bw - 1] + lin[idx + bw + 1]
                    val hv = (5f * cC + 4f * (wv + e) - (ww + ee) - diag + 0.5f * (nn + ss)) * 0.125f
                    val vv = (5f * cC + 4f * (n + s) - (nn + ss) - diag + 0.5f * (ww + ee)) * 0.125f
                    gg = cC
                    if (pattern[pi xor 1] == 0) { rr = hv; bb = vv } else { bb = hv; rr = vv }
                } else {
                    gg = (4f * cC + 2f * (n + s + wv + e) - (nn + ss + ww + ee)) * 0.125f
                    val diag = lin[idx - bw - 1] + lin[idx - bw + 1] + lin[idx + bw - 1] + lin[idx + bw + 1]
                    val other = (6f * cC + 2f * diag - 1.5f * (nn + ss + ww + ee)) * 0.125f
                    if (color == 0) { rr = cC; bb = other } else { bb = cC; rr = other }
                }
                var ro = m[0] * rr + m[1] * gg + m[2] * bb
                var go = m[3] * rr + m[4] * gg + m[5] * bb
                var bo = m[6] * rr + m[7] * gg + m[8] * bb
                if (ro < 0f) ro = 0f else if (ro > 1f) ro = 1f
                if (go < 0f) go = 0f else if (go > 1f) go = 1f
                if (bo < 0f) bo = 0f else if (bo > 1f) bo = 1f
                out[outBase + i] = -0x1000000 or
                    (lut[(ro * LUT_MAX + 0.5f).toInt()] shl 16) or
                    (lut[(go * LUT_MAX + 0.5f).toInt()] shl 8) or
                    lut[(bo * LUT_MAX + 0.5f).toInt()]
            }
        }
        sink.rows(oy0, rows, out, cw)
    }

    private fun developHalf(sb: ShortBuffer, p: DevelopParams, oy0: Int, oy1: Int, sink: RowSink) {
        val ow = p.cropWidth / 2
        val rows = oy1 - oy0
        val out = IntArray(ow * rows)
        val mono = p.cfa == CFA_MONO || p.cfa == CFA_NIR
        val pattern = if (mono) intArrayOf(1, 1, 1, 1) else PATTERNS[p.cfa]
        val shade = Shade(p)
        val rowA = ShortArray(p.cropWidth)
        val rowB = ShortArray(p.cropWidth)
        val sc0 = IntArray(ow)
        val sc1 = IntArray(ow)
        val sf = FloatArray(ow)
        for (i in 0 until ow) shade.colPos(p.cropLeft + 2 * i + 1, sc0, sc1, sf, i)
        val invRange = FloatArray(4) { 1f / max(1f, p.white - p.black[it]) }
        val wb = if (mono) floatArrayOf(1f, 1f, 1f) else p.wbMul
        val m = p.matrix
        val lut = SRGB_LUT
        val hasShade = shade.grid != null
        val rg = shade.rowGain
        val vals = FloatArray(4)
        for (j in 0 until rows) {
            val y = p.cropTop + 2 * (oy0 + j)
            sb.position(y * p.rowStride + p.cropLeft)
            sb.get(rowA, 0, p.cropWidth)
            sb.position((y + 1) * p.rowStride + p.cropLeft)
            sb.get(rowB, 0, p.cropWidth)
            if (hasShade) shade.prepareRow(y + 1)
            for (i in 0 until ow) {
                val xa = 2 * i
                var rSum = 0f
                var gSum = 0f
                var bSum = 0f
                var gN = 0
                for (k in 0 until 4) {
                    val dy = k shr 1
                    val dx = k and 1
                    // crop origin is even, so the 2x2 position equals the CFA phase
                    val raw = (if (dy == 0) rowA[xa + dx] else rowB[xa + dx]).toInt() and 0xFFFF
                    val color = pattern[k]
                    var v = (raw - p.black[k]) * invRange[k]
                    if (hasShade) {
                        val ch = if (mono) 0 else when (color) { 0 -> 0; 2 -> 3; else -> if (dy == 0) 1 else 2 }
                        val g0 = rg[sc0[i] * 4 + ch]
                        v *= g0 + (rg[sc1[i] * 4 + ch] - g0) * sf[i]
                    }
                    v *= wb[color]
                    if (v > 1f) v = 1f
                    vals[k] = v
                    when (color) {
                        0 -> rSum += v
                        2 -> bSum += v
                        else -> { gSum += v; gN++ }
                    }
                }
                val px: Int
                if (mono) {
                    val g = lut[(((vals[0] + vals[1] + vals[2] + vals[3]) * 0.25f).coerceIn(0f, 1f) * LUT_MAX + 0.5f).toInt()]
                    px = -0x1000000 or (g shl 16) or (g shl 8) or g
                } else {
                    val gg = if (gN > 0) gSum / gN else 0f
                    var ro = m[0] * rSum + m[1] * gg + m[2] * bSum
                    var go = m[3] * rSum + m[4] * gg + m[5] * bSum
                    var bo = m[6] * rSum + m[7] * gg + m[8] * bSum
                    if (ro < 0f) ro = 0f else if (ro > 1f) ro = 1f
                    if (go < 0f) go = 0f else if (go > 1f) go = 1f
                    if (bo < 0f) bo = 0f else if (bo > 1f) bo = 1f
                    px = -0x1000000 or
                        (lut[(ro * LUT_MAX + 0.5f).toInt()] shl 16) or
                        (lut[(go * LUT_MAX + 0.5f).toInt()] shl 8) or
                        lut[(bo * LUT_MAX + 0.5f).toInt()]
                }
                out[j * ow + i] = px
            }
        }
        sink.rows(oy0, rows, out, ow)
    }
}
