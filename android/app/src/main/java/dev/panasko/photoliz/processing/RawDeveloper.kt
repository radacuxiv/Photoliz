package dev.panasko.photoliz.processing

import android.app.ActivityManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.params.ColorSpaceTransform
import android.media.Image
import android.os.Build
import android.util.Size
import dev.panasko.photoliz.camera.CropMath
import java.nio.ByteOrder
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicInteger

/** Turns a RAW_SENSOR [Image] into a plain sRGB bitmap using [RawCore]. */
object RawDeveloper {

    private val pool: ExecutorService by lazy {
        val n = Runtime.getRuntime().availableProcessors().coerceIn(2, 8)
        val counter = AtomicInteger()
        Executors.newFixedThreadPool(n, ThreadFactory { r ->
            Thread(r, "PhotolizDevelop-${counter.incrementAndGet()}").apply {
                isDaemon = true
                priority = Thread.NORM_PRIORITY - 1
            }
        })
    }

    class Result(val bitmap: Bitmap, val half: Boolean)

    private fun ColorSpaceTransform?.toDoubles(): DoubleArray? {
        if (this == null) return null
        return DoubleArray(9) { i -> getElement(i % 3, i / 3).toDouble() }
    }

    fun calibration(chars: CameraCharacteristics): ColorMath.Calibration {
        fun g(k: CameraCharacteristics.Key<ColorSpaceTransform>) = try { chars.get(k) } catch (t: Throwable) { null }
        val ill1 = try { chars.get(CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT1) } catch (t: Throwable) { null } ?: 0
        val ill2 = try { chars.get(CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT2)?.toInt() } catch (t: Throwable) { null } ?: 0
        return ColorMath.Calibration(
            colorMatrix1 = g(CameraCharacteristics.SENSOR_COLOR_TRANSFORM1).toDoubles(),
            colorMatrix2 = g(CameraCharacteristics.SENSOR_COLOR_TRANSFORM2).toDoubles(),
            forwardMatrix1 = g(CameraCharacteristics.SENSOR_FORWARD_MATRIX1).toDoubles(),
            forwardMatrix2 = g(CameraCharacteristics.SENSOR_FORWARD_MATRIX2).toDoubles(),
            calibration1 = g(CameraCharacteristics.SENSOR_CALIBRATION_TRANSFORM1).toDoubles(),
            calibration2 = g(CameraCharacteristics.SENSOR_CALIBRATION_TRANSFORM2).toDoubles(),
            illuminant1 = ill1,
            illuminant2 = ill2,
        )
    }

    /** Region of the raw frame covered by the lens shading map (the active array). */
    private fun shadingRegion(chars: CameraCharacteristics, w: Int, h: Int): Rect {
        val candidates = ArrayList<Pair<Size?, Rect?>>()
        candidates.add(chars.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE) to
            chars.get(CameraCharacteristics.SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE))
        if (Build.VERSION.SDK_INT >= 31) {
            try {
                candidates.add(chars.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE_MAXIMUM_RESOLUTION) to
                    chars.get(CameraCharacteristics.SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE_MAXIMUM_RESOLUTION))
            } catch (t: Throwable) { /* not an ultra high resolution sensor */ }
        }
        for ((pixel, active) in candidates) {
            if (active == null) continue
            if (active.width() == w && active.height() == h) return Rect(0, 0, w, h)
            if (pixel != null && pixel.width == w && pixel.height == h &&
                active.left >= 0 && active.top >= 0 && active.right <= w && active.bottom <= h) return Rect(active)
        }
        return Rect(0, 0, w, h)
    }

    fun buildParams(
        image: Image,
        chars: CameraCharacteristics,
        result: CaptureResult,
        ratio: Double?,
        zoom: Double,
        forceHalf: Boolean,
    ): DevelopParams {
        val w = image.width
        val h = image.height
        val plane = image.planes[0]
        val cfa = chars.get(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT) ?: 0

        val black = FloatArray(4)
        val dyn = try { result.get(CaptureResult.SENSOR_DYNAMIC_BLACK_LEVEL) } catch (t: Throwable) { null }
        if (dyn != null && dyn.size >= 4) {
            for (i in 0 until 4) black[i] = dyn[i]
        } else {
            val pattern = chars.get(CameraCharacteristics.SENSOR_BLACK_LEVEL_PATTERN)
            for (i in 0 until 4) black[i] = pattern?.getOffsetForIndex(i and 1, i shr 1)?.toFloat() ?: 0f
        }
        val white = (try { result.get(CaptureResult.SENSOR_DYNAMIC_WHITE_LEVEL) } catch (t: Throwable) { null }
            ?: chars.get(CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL) ?: 1023).toFloat()

        val neutral = result.get(CaptureResult.SENSOR_NEUTRAL_COLOR_POINT)?.let { arr ->
            if (arr.size >= 3) DoubleArray(3) { arr[it].toDouble() } else null
        }
        val dev = ColorMath.develop(calibration(chars), neutral)

        val lsm = try { result.get(CaptureResult.STATISTICS_LENS_SHADING_CORRECTION_MAP) } catch (t: Throwable) { null }
        val grid = lsm?.let { map ->
            val rows = map.rowCount
            val cols = map.columnCount
            if (rows < 1 || cols < 1) null else {
                val gains = FloatArray(rows * cols * 4)
                map.copyGainFactors(gains, 0)
                ShadingGrid(rows, cols, gains)
            }
        }
        val region = shadingRegion(chars, w, h)

        val crop = CropMath.centered(w, h, ratio, zoom, align = 2)
        val pixels = crop.width.toLong() * crop.height
        val half = forceHalf || pixels > 64_000_000L

        return DevelopParams(
            width = w,
            height = h,
            rowStride = plane.rowStride / 2,
            cfa = cfa,
            black = black,
            white = white,
            wbMul = FloatArray(3) { dev.wbMul[it].toFloat() },
            matrix = FloatArray(9) { dev.matrix[it].toFloat() },
            shading = grid,
            shadeLeft = region.left,
            shadeTop = region.top,
            shadeWidth = region.width(),
            shadeHeight = region.height(),
            cropLeft = crop.left,
            cropTop = crop.top,
            cropWidth = crop.width,
            cropHeight = crop.height,
            half = half,
        )
    }

    /** Bytes we allow one developed frame to occupy. */
    fun memoryBudget(context: Context): Long {
        val am = context.getSystemService(ActivityManager::class.java)
        val info = ActivityManager.MemoryInfo()
        am?.getMemoryInfo(info)
        val avail = if (info.availMem > 0) info.availMem else 512L shl 20
        return (avail / 3).coerceIn(96L shl 20, 400L shl 20)
    }

    fun develop(context: Context, image: Image, chars: CameraCharacteristics, result: CaptureResult, ratio: Double?, zoom: Double): Result {
        var params = buildParams(image, chars, result, ratio, zoom, forceHalf = false)
        if (!params.half && params.cropWidth.toLong() * params.cropHeight * 4 > memoryBudget(context)) {
            params = buildParams(image, chars, result, ratio, zoom, forceHalf = true)
        }
        val bitmap = try {
            Bitmap.createBitmap(RawCore.outWidth(params), RawCore.outHeight(params), Bitmap.Config.ARGB_8888)
        } catch (oom: OutOfMemoryError) {
            if (params.half) throw oom
            params = buildParams(image, chars, result, ratio, zoom, forceHalf = true)
            Bitmap.createBitmap(RawCore.outWidth(params), RawCore.outHeight(params), Bitmap.Config.ARGB_8888)
        }
        val shorts = image.planes[0].buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val lock = Any()
        RawCore.develop(shorts, params, pool) { y0, count, argb, width ->
            synchronized(lock) { bitmap.setPixels(argb, 0, width, 0, y0, width, count) }
        }
        return Result(bitmap, params.half)
    }
}
