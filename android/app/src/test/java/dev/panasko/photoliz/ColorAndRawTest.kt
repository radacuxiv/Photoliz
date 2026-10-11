package dev.panasko.photoliz

import dev.panasko.photoliz.processing.ColorMath
import dev.panasko.photoliz.processing.DevelopParams
import dev.panasko.photoliz.processing.RawCore
import dev.panasko.photoliz.processing.ShadingGrid
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.ShortBuffer
import java.util.concurrent.Executors
import kotlin.math.abs

class ColorAndRawTest {

    /** Linear sRGB -> XYZ (D50, Bradford): the forward matrix of an imaginary "sRGB sensor". */
    private val srgbToXyzD50 = doubleArrayOf(
        0.4360747, 0.3850649, 0.1430804,
        0.2225045, 0.7168786, 0.0606169,
        0.0139322, 0.0971045, 0.7141733,
    )

    private fun assertMatrix(expected: DoubleArray, actual: DoubleArray, tol: Double) {
        for (i in 0 until 9) assertEquals("element $i", expected[i], actual[i], tol)
    }

    @Test
    fun srgbSensorDevelopsToIdentity() {
        val cal = ColorMath.Calibration(null, null, srgbToXyzD50, null, null, null, 21, 0)
        val dev = ColorMath.develop(cal, doubleArrayOf(1.0, 1.0, 1.0))
        assertMatrix(ColorMath.IDENTITY, dev.matrix, 2e-3)
        assertEquals(1.0, dev.wbMul[0], 1e-9)
    }

    @Test
    fun neutralBecomesWhite() {
        val cal = ColorMath.Calibration(null, null, srgbToXyzD50, null, null, null, 21, 0)
        val n = doubleArrayOf(0.5, 1.0, 0.8)
        val dev = ColorMath.develop(cal, n)
        assertEquals(2.0, dev.wbMul[0], 1e-9)
        assertEquals(1.0, dev.wbMul[1], 1e-9)
        assertEquals(1.25, dev.wbMul[2], 1e-9)
        val balanced = DoubleArray(3) { n[it] * dev.wbMul[it] }
        val out = ColorMath.mulVec(dev.matrix, balanced)
        for (v in out) assertEquals(1.0, v, 2e-3)
    }

    @Test
    fun colorMatrixOnlyPathKeepsNeutral() {
        val cm = ColorMath.inverse(srgbToXyzD50)!!
        val cal = ColorMath.Calibration(cm, null, null, null, null, null, 23, 0)
        val n = doubleArrayOf(0.6, 1.0, 0.7)
        val dev = ColorMath.develop(cal, n)
        val out = ColorMath.mulVec(dev.matrix, DoubleArray(3) { n[it] * dev.wbMul[it] })
        assertEquals(out[0], out[1], 2e-3)
        assertEquals(out[2], out[1], 2e-3)
    }

    @Test
    fun dualIlluminantInterpolationConverges() {
        val cm1 = ColorMath.inverse(srgbToXyzD50)!!
        val cm2 = ColorMath.scale(cm1, 1.1)
        val cal = ColorMath.Calibration(cm1, cm2, srgbToXyzD50, srgbToXyzD50, null, null, 17, 21)
        val dev = ColorMath.develop(cal, doubleArrayOf(0.9, 1.0, 0.4))
        assertTrue(dev.weight1 in 0.0..1.0)
        assertTrue(dev.matrix.all { it.isFinite() })
    }

    @Test
    fun cctOfD65IsAbout6500() {
        val cct = ColorMath.cctFromXy(0.3127, 0.3290)
        assertTrue("cct=$cct", abs(cct - 6504) < 60)
    }

    private fun bayer(w: Int, h: Int, value: (x: Int, y: Int) -> Int): ShortBuffer {
        val bb = ByteBuffer.allocateDirect(w * h * 2).order(ByteOrder.LITTLE_ENDIAN)
        val sb = bb.asShortBuffer()
        for (y in 0 until h) for (x in 0 until w) sb.put(y * w + x, value(x, y).toShort())
        return sb
    }

    private fun params(w: Int, h: Int, cfa: Int = 0, half: Boolean = false, crop: IntArray = intArrayOf(0, 0, w, h), shading: ShadingGrid? = null) = DevelopParams(
        width = w, height = h, rowStride = w, cfa = cfa,
        black = floatArrayOf(64f, 64f, 64f, 64f), white = 1023f,
        wbMul = floatArrayOf(1f, 1f, 1f), matrix = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f),
        shading = shading, shadeLeft = 0, shadeTop = 0, shadeWidth = w, shadeHeight = h,
        cropLeft = crop[0], cropTop = crop[1], cropWidth = crop[2], cropHeight = crop[3], half = half,
    )

    private fun develop(src: ShortBuffer, p: DevelopParams, threads: Boolean = false): Array<IntArray> {
        val outW = RawCore.outWidth(p)
        val outH = RawCore.outHeight(p)
        val out = Array(outH) { IntArray(outW) }
        val pool = if (threads) Executors.newFixedThreadPool(3) else null
        RawCore.develop(src, p, pool) { y0, count, argb, width ->
            synchronized(out) { for (r in 0 until count) System.arraycopy(argb, r * width, out[y0 + r], 0, width) }
        }
        pool?.shutdown()
        return out
    }

    private fun rgb(px: Int) = intArrayOf((px shr 16) and 0xFF, (px shr 8) and 0xFF, px and 0xFF)

    @Test
    fun uniformGrayStaysGray() {
        val mid = 64 + (1023 - 64) / 2
        val img = develop(bayer(64, 48) { _, _ -> mid }, params(64, 48), threads = true)
        val expected = RawCore.SRGB_LUT[((mid - 64f) / (1023f - 64f) * (RawCore.LUT_SIZE - 1) + 0.5f).toInt()]
        for (row in img) for (px in row) {
            val c = rgb(px)
            for (v in c) assertTrue("got $v expected $expected", abs(v - expected) <= 1)
        }
    }

    @Test
    fun redSitesProduceRed() {
        // RGGB: red at even/even.
        val img = develop(bayer(32, 32) { x, y -> if (x % 2 == 0 && y % 2 == 0) 1023 else 64 }, params(32, 32))
        val c = rgb(img[16][16])
        assertTrue("r=${c[0]} g=${c[1]} b=${c[2]}", c[0] > 200 && c[1] < 40 && c[2] < 40)
    }

    @Test
    fun cfaPatternIsHonoured() {
        // BGGR: blue at even/even.
        val img = develop(bayer(32, 32) { x, y -> if (x % 2 == 0 && y % 2 == 0) 1023 else 64 }, params(32, 32, cfa = 3))
        val c = rgb(img[16][16])
        assertTrue("r=${c[0]} g=${c[1]} b=${c[2]}", c[2] > 200 && c[0] < 40)
    }

    @Test
    fun cropAndHalfResolutionSizes() {
        val p = params(400, 300, crop = intArrayOf(50, 40, 300, 200))
        assertEquals(300, RawCore.outWidth(p))
        assertEquals(200, RawCore.outHeight(p))
        val h = params(400, 300, half = true, crop = intArrayOf(50, 40, 300, 200))
        assertEquals(150, RawCore.outWidth(h))
        assertEquals(100, RawCore.outHeight(h))
        val img = develop(bayer(400, 300) { _, _ -> 500 }, h, threads = true)
        assertEquals(100, img.size)
        assertEquals(150, img[0].size)
        val a = rgb(img[50][75])
        assertEquals(a[0], a[1])
        assertEquals(a[1], a[2])
    }

    @Test
    fun monochromeSensor() {
        val img = develop(bayer(16, 16) { _, _ -> 1023 }, params(16, 16, cfa = RawCore.CFA_MONO))
        assertEquals(255, rgb(img[8][8])[0])
    }

    @Test
    fun shadingGainBrightensCorners() {
        // 2x2 grid: gain 1 in the top-left, 2 elsewhere.
        val gains = FloatArray(2 * 2 * 4) { 2f }
        for (ch in 0 until 4) gains[ch] = 1f
        val mid = 64 + (1023 - 64) / 4
        val img = develop(bayer(64, 64) { _, _ -> mid }, params(64, 64, shading = ShadingGrid(2, 2, gains)))
        val tl = rgb(img[2][2])[1]
        val br = rgb(img[61][61])[1]
        assertTrue("tl=$tl br=$br", br > tl + 20)
    }

    @Test
    fun lutEndpoints() {
        assertEquals(0, RawCore.SRGB_LUT[0])
        assertEquals(255, RawCore.SRGB_LUT[RawCore.LUT_SIZE - 1])
        assertNotNull(RawCore.SRGB_LUT)
    }
}
