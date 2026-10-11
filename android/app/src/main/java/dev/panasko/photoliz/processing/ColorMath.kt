package dev.panasko.photoliz.processing

import kotlin.math.abs
import kotlin.math.max

/**
 * DNG-style color math (pure Kotlin, unit tested). Matrices are 3x3, row-major DoubleArrays.
 *
 * The develop is deliberately plain: as-shot white balance, the sensor's own calibration matrices
 * (DNG ForwardMatrix / ColorMatrix, interpolated by color temperature exactly like the DNG spec),
 * the standard sRGB transfer curve. No tone curve, no sharpening, no noise reduction.
 */
object ColorMath {

    val IDENTITY = doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0)

    /** XYZ (D50) -> linear sRGB (D65), Bradford adapted. */
    val XYZ_D50_TO_SRGB = doubleArrayOf(
        3.1338561, -1.6168667, -0.4906146,
        -0.9787684, 1.9161415, 0.0334540,
        0.0719453, -0.2289914, 1.4052427,
    )

    val D50_XYZ = doubleArrayOf(0.96422, 1.0, 0.82521)

    private val BRADFORD = doubleArrayOf(
        0.8951, 0.2664, -0.1614,
        -0.7502, 1.7135, 0.0367,
        0.0389, -0.0685, 1.0296,
    )

    fun mul(a: DoubleArray, b: DoubleArray): DoubleArray {
        val r = DoubleArray(9)
        for (i in 0 until 3) for (j in 0 until 3) {
            r[i * 3 + j] = a[i * 3] * b[j] + a[i * 3 + 1] * b[3 + j] + a[i * 3 + 2] * b[6 + j]
        }
        return r
    }

    fun mulVec(m: DoubleArray, v: DoubleArray): DoubleArray = doubleArrayOf(
        m[0] * v[0] + m[1] * v[1] + m[2] * v[2],
        m[3] * v[0] + m[4] * v[1] + m[5] * v[2],
        m[6] * v[0] + m[7] * v[1] + m[8] * v[2],
    )

    fun diag(a: Double, b: Double, c: Double) = doubleArrayOf(a, 0.0, 0.0, 0.0, b, 0.0, 0.0, 0.0, c)

    fun scale(m: DoubleArray, s: Double) = DoubleArray(9) { m[it] * s }

    fun lerp(a: DoubleArray, b: DoubleArray, w: Double) = DoubleArray(9) { a[it] * w + b[it] * (1 - w) }

    fun inverse(m: DoubleArray): DoubleArray? {
        val a = m[0]; val b = m[1]; val c = m[2]
        val d = m[3]; val e = m[4]; val f = m[5]
        val g = m[6]; val h = m[7]; val i = m[8]
        val A = e * i - f * h
        val B = -(d * i - f * g)
        val C = d * h - e * g
        val det = a * A + b * B + c * C
        if (abs(det) < 1e-12) return null
        val inv = doubleArrayOf(
            A, -(b * i - c * h), b * f - c * e,
            B, a * i - c * g, -(a * f - c * d),
            C, -(a * h - b * g), a * e - b * d,
        )
        return DoubleArray(9) { inv[it] / det }
    }

    /** McCamy's approximation of correlated color temperature from CIE xy. */
    fun cctFromXy(x: Double, y: Double): Double {
        val n = (x - 0.3320) / (0.1858 - y)
        val cct = 449.0 * n * n * n + 3525.0 * n * n + 6823.3 * n + 5520.33
        return cct.coerceIn(1500.0, 25000.0)
    }

    /** EXIF / DNG LightSource code -> color temperature (values used by the Adobe DNG SDK). */
    fun illuminantCct(code: Int): Double = when (code) {
        17, 3 -> 2850.0                // Standard A, Tungsten
        24 -> 3200.0                   // ISO studio tungsten
        23 -> 5000.0                   // D50
        20, 1, 9, 4, 18 -> 5500.0      // D55, Daylight, Fine weather, Flash, Standard B
        21, 19, 10 -> 6500.0           // D65, Standard C, Cloudy
        22, 11 -> 7500.0               // D75, Shade
        12 -> 6400.0                   // Daylight fluorescent
        13 -> 5050.0                   // Day white fluorescent
        14, 2 -> 4150.0                // Cool white fluorescent, Fluorescent
        15 -> 3525.0                   // White fluorescent
        16 -> 2925.0                   // Warm white fluorescent
        else -> 0.0
    }

    fun bradford(srcWhite: DoubleArray, dstWhite: DoubleArray): DoubleArray {
        val s = mulVec(BRADFORD, srcWhite)
        val d = mulVec(BRADFORD, dstWhite)
        val inv = inverse(BRADFORD) ?: return IDENTITY
        return mul(inv, mul(diag(d[0] / s[0], d[1] / s[1], d[2] / s[2]), BRADFORD))
    }

    class Calibration(
        val colorMatrix1: DoubleArray?,
        val colorMatrix2: DoubleArray?,
        val forwardMatrix1: DoubleArray?,
        val forwardMatrix2: DoubleArray?,
        val calibration1: DoubleArray?,
        val calibration2: DoubleArray?,
        val illuminant1: Int,
        val illuminant2: Int,
    )

    class Develop(
        /** Per-channel multipliers applied to the raw (camera RGB) data; min multiplier is 1. */
        val wbMul: DoubleArray,
        /** White-balanced camera RGB -> linear sRGB. */
        val matrix: DoubleArray,
        val weight1: Double,
    )

    /**
     * @param neutralIn as-shot neutral (camera RGB of a gray object), e.g. SENSOR_NEUTRAL_COLOR_POINT.
     */
    fun develop(cal: Calibration, neutralIn: DoubleArray?): Develop {
        var n = neutralIn?.takeIf { it.size == 3 && it.all { v -> v > 1e-6 && v.isFinite() } } ?: doubleArrayOf(1.0, 1.0, 1.0)
        val nMax = max(n[0], max(n[1], n[2]))
        n = doubleArrayOf(n[0] / nMax, n[1] / nMax, n[2] / nMax)

        val cc1 = cal.calibration1 ?: IDENTITY
        val cc2 = cal.calibration2 ?: IDENTITY
        val cct1 = illuminantCct(cal.illuminant1)
        val cct2 = illuminantCct(cal.illuminant2)
        val twoSets = cal.colorMatrix1 != null && cal.colorMatrix2 != null && cct1 > 0 && cct2 > 0 && abs(cct1 - cct2) > 1.0

        fun weightFor(cct: Double): Double {
            if (!twoSets) return 1.0
            val t = 1.0 / cct
            val t1 = 1.0 / cct1
            val t2 = 1.0 / cct2
            return ((t - t2) / (t1 - t2)).coerceIn(0.0, 1.0)
        }

        var w = 1.0
        if (twoSets) {
            val xyzToCam1 = mul(cc1, cal.colorMatrix1!!)
            val xyzToCam2 = mul(cc2, cal.colorMatrix2!!)
            var x = 0.3457
            var y = 0.3585
            for (iter in 0 until 30) {
                w = weightFor(cctFromXy(x, y))
                val inv = inverse(lerp(xyzToCam1, xyzToCam2, w)) ?: break
                val xyz = mulVec(inv, n)
                val sum = xyz[0] + xyz[1] + xyz[2]
                if (sum <= 1e-9) break
                val nx = xyz[0] / sum
                val ny = xyz[1] / sum
                val done = abs(nx - x) < 1e-7 && abs(ny - y) < 1e-7
                x = nx; y = ny
                if (done) break
            }
            w = weightFor(cctFromXy(x, y))
        }

        val cc = if (twoSets) lerp(cc1, cc2, w) else cc1
        val ccInv = inverse(cc) ?: IDENTITY

        // White balance multipliers in camera space, normalized so the smallest one is 1.
        val inv = doubleArrayOf(1 / n[0], 1 / n[1], 1 / n[2])
        val minInv = minOf(inv[0], inv[1], inv[2])
        val wbMul = doubleArrayOf(inv[0] / minInv, inv[1] / minInv, inv[2] / minInv)

        val fm1 = cal.forwardMatrix1
        val camToXyzD50: DoubleArray = if (fm1 != null) {
            val fm = if (twoSets && cal.forwardMatrix2 != null) lerp(fm1, cal.forwardMatrix2, w) else fm1
            val ref = mulVec(ccInv, n)
            val d = diag(1 / ref[0].coerceAtLeast(1e-9), 1 / ref[1].coerceAtLeast(1e-9), 1 / ref[2].coerceAtLeast(1e-9))
            mul(fm, mul(d, ccInv))
        } else {
            val cm1 = cal.colorMatrix1
            val xyzToCam = when {
                cm1 == null -> null
                twoSets -> lerp(mul(cc1, cm1), mul(cc2, cal.colorMatrix2!!), w)
                else -> mul(cc1, cm1)
            }
            val camToXyz = xyzToCam?.let { inverse(it) }
            if (camToXyz == null) {
                // No usable calibration: white-balanced camera RGB is shown as-is.
                return Develop(wbMul, IDENTITY.copyOf(), w)
            }
            val white = mulVec(camToXyz, n)
            val wY = white[1].coerceAtLeast(1e-9)
            val whiteN = doubleArrayOf(white[0] / wY, 1.0, white[2] / wY)
            mul(bradford(whiteN, D50_XYZ), scale(camToXyz, 1 / wY))
        }

        val m = mul(XYZ_D50_TO_SRGB, mul(camToXyzD50, diag(1 / wbMul[0], 1 / wbMul[1], 1 / wbMul[2])))
        return Develop(wbMul, scaleToWhite(m, wbMul), w)
    }

    /** Makes sure a clipped white (1,1,1 after WB) lands exactly on sRGB white luminance. */
    private fun scaleToWhite(m: DoubleArray, wbMul: DoubleArray): DoubleArray {
        val out = mulVec(m, doubleArrayOf(1.0, 1.0, 1.0))
        val y = 0.2126 * out[0] + 0.7152 * out[1] + 0.0722 * out[2]
        return if (y > 1e-6 && abs(y - 1.0) > 1e-4) scale(m, 1.0 / y) else m
    }
}
