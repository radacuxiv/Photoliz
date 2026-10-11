package dev.panasko.photoliz.processing

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.DngCreator
import android.media.Image
import android.net.Uri
import android.os.Build
import android.util.Log
import dev.panasko.photoliz.AppInfo
import dev.panasko.photoliz.camera.CaptureMode
import dev.panasko.photoliz.camera.CaptureSettings
import dev.panasko.photoliz.camera.CropMath
import dev.panasko.photoliz.camera.SavedShot
import dev.panasko.photoliz.camera.SensorInfo
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.max
import kotlin.math.roundToInt

private const val TAG = "PhotolizShot"

/** One press of the shutter (or one frame of a burst). Shared by all sensors of a combo. */
class ShotTicket(
    val id: Long,
    val settings: CaptureSettings,
    /** Clockwise rotation (degrees) needed to view the sensor image upright. */
    val orientation: Int,
    val timeMillis: Long,
    val index: Int,
    val frames: Int,
    val treeUri: String?,
    val manualExposure: Boolean,
) {
    val remaining = AtomicInteger(frames)
}

class ShotJob(
    val image: Image,
    val result: CaptureResult,
    /** Logical result, used as DngCreator fallback when a physical result lacks metadata. */
    val fallbackResult: CaptureResult?,
    val sensor: SensorInfo,
    val ticket: ShotTicket,
    val comboSuffix: String?,
    /** Called exactly once on the processor thread as soon as the Image has been closed. */
    val onReleased: () -> Unit,
)

/** Sequential, single-threaded save pipeline (the develop itself is multi-threaded). */
class ShotProcessor(context: Context, private val callback: Callback) {
    interface Callback {
        fun onSaved(shot: SavedShot)
        fun onSaveError(message: String)
        fun onNotice(message: String)
    }

    private val app = context.applicationContext
    private val saver = CaptureSaver(app)
    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "PhotolizSave").apply { isDaemon = true } }
    private val queued = AtomicInteger(0)

    val pending: Int get() = queued.get()

    fun submit(job: ShotJob) {
        queued.incrementAndGet()
        executor.execute {
            try {
                if (job.image.format == ImageFormat.RAW_SENSOR) processRaw(job) else processJpeg(job)
            } catch (t: Throwable) {
                Log.e(TAG, "processing failed", t)
                callback.onSaveError("SAVE FAILED: " + (t.message ?: t.javaClass.simpleName).take(60))
            } finally {
                release(job)
                queued.decrementAndGet()
            }
        }
    }

    private val released = java.util.Collections.newSetFromMap(java.util.WeakHashMap<ShotJob, Boolean>())

    private fun release(job: ShotJob) {
        synchronized(released) {
            if (!released.add(job)) return
        }
        try { job.image.close() } catch (_: Throwable) {}
        try { job.onReleased() } catch (t: Throwable) { Log.w(TAG, "onReleased", t) }
    }

    private fun baseName(job: ShotJob): String =
        saver.fileStamp(job.ticket.timeMillis, job.ticket.index) + (job.comboSuffix?.let { "_$it" } ?: "")

    private fun scaleDown(src: Bitmap, maxSide: Int): Bitmap {
        val s = maxSide.toFloat() / max(src.width, src.height)
        if (s >= 1f) return src.copy(Bitmap.Config.ARGB_8888, false)
        return Bitmap.createScaledBitmap(src, max(1, (src.width * s).roundToInt()), max(1, (src.height * s).roundToInt()), true)
    }

    private fun rotate(src: Bitmap, degrees: Int): Bitmap {
        if (degrees % 360 == 0) return src
        val m = Matrix().apply { postRotate(degrees.toFloat()) }
        return Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
    }

    private fun exifFor(job: ShotJob, result: CaptureResult): CaptureSaver.ExifData {
        val s = job.sensor
        val focal = result.get(CaptureResult.LENS_FOCAL_LENGTH)
        val base = s.focalLengths.firstOrNull() ?: 0f
        val eq = if (focal != null && base > 0f && s.equivalentFocal > 0f) (s.equivalentFocal * focal / base).roundToInt() else s.equivalentFocal.roundToInt()
        return CaptureSaver.ExifData(
            orientationDegrees = job.ticket.orientation,
            timeMillis = job.ticket.timeMillis,
            exposureNs = result.get(CaptureResult.SENSOR_EXPOSURE_TIME),
            iso = result.get(CaptureResult.SENSOR_SENSITIVITY),
            aperture = result.get(CaptureResult.LENS_APERTURE) ?: s.apertures.firstOrNull(),
            focalLength = focal ?: s.focalLengths.firstOrNull(),
            focal35 = eq,
            flashFired = result.get(CaptureResult.FLASH_STATE) == CaptureResult.FLASH_STATE_FIRED,
            manualExposure = job.ticket.manualExposure,
            description = "${AppInfo.NAME} · ${s.label} · ${s.facingName}",
        )
    }

    private fun zoomOf(s: CaptureSettings): Double = if (s.mode == CaptureMode.PHOTO) s.zoom.toDouble().coerceAtLeast(1.0) else 1.0

    private fun processRaw(job: ShotJob) {
        val t = job.ticket
        val s = t.settings
        val ratio = s.aspect.ratio
        val zoom = zoomOf(s)
        val base = baseName(job)
        val cache = app.cacheDir

        var developed: RawDeveloper.Result? = null
        try {
            developed = RawDeveloper.develop(app, job.image, job.sensor.chars, job.result, ratio, zoom)
        } catch (e: Throwable) {
            Log.e(TAG, "develop failed", e)
            callback.onNotice("JPEG DEVELOP FAILED")
        }
        val sensorThumb = developed?.let { scaleDown(it.bitmap, 360) }

        var dngFile: File? = null
        if (s.saveDng) {
            val f = File(cache, "$base.dng")
            val ok = writeDng(job, f, job.result, sensorThumb) ||
                (job.fallbackResult != null && job.fallbackResult !== job.result && writeDng(job, f, job.fallbackResult, sensorThumb))
            if (ok) {
                DngCropPatcher.patchFile(f, ratio, zoom)
                dngFile = f
            } else {
                f.delete()
                callback.onNotice("DNG FAILED")
            }
        }
        // Free the camera buffer as early as possible: the burst waits for it.
        release(job)

        var jpegUri: Uri? = null
        var outW = 0
        var outH = 0
        developed?.let { d ->
            val jpg = File(cache, "$base.jpg")
            try {
                FileOutputStream(jpg).use { d.bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it) }
                outW = d.bitmap.width
                outH = d.bitmap.height
                d.bitmap.recycle()
                saver.writeExif(jpg, exifFor(job, job.result))
                jpegUri = saver.saveJpeg(jpg, base, t.timeMillis)
                if (jpegUri == null) callback.onSaveError("GALLERY WRITE FAILED")
            } finally {
                jpg.delete()
            }
        }

        var dngUri: Uri? = null
        dngFile?.let { f ->
            try {
                val r = saver.saveDng(f, base, t.timeMillis, t.treeUri)
                dngUri = r.uri
                if (r.fellBack) callback.onNotice("DNG FOLDER LOST · SAVED TO " + saver.defaultDngFolderLabel())
                if (r.uri == null) callback.onSaveError("DNG WRITE FAILED")
            } finally {
                f.delete()
            }
        }

        val upright = sensorThumb?.let { rotate(it, t.orientation) }
        callback.onSaved(SavedShot(jpegUri, dngUri, upright, job.sensor.label, outW, outH))
    }

    private fun writeDng(job: ShotJob, file: File, result: CaptureResult, thumb: Bitmap?): Boolean {
        return try {
            FileOutputStream(file).use { out ->
                DngCreator(job.sensor.chars, result).use { dng ->
                    dng.setOrientation(saver.exifOrientation(job.ticket.orientation))
                    dng.setDescription("${AppInfo.NAME} ${job.sensor.label}")
                    thumb?.let { tb ->
                        val small = scaleDown(tb, 256)
                        dng.setThumbnail(small)
                    }
                    dng.writeImage(out, job.image)
                }
            }
            true
        } catch (t: Throwable) {
            Log.w(TAG, "DngCreator failed", t)
            false
        }
    }

    private fun processJpeg(job: ShotJob) {
        val t = job.ticket
        val s = t.settings
        val ratio = s.aspect.ratio
        val zoom = zoomOf(s)
        val base = baseName(job)
        val buffer = job.image.planes[0].buffer
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
        release(job)

        val jpg = File(app.cacheDir, "$base.jpg")
        var outW = 0
        var outH = 0
        var thumb: Bitmap? = null
        try {
            val needsCrop = ratio != null || zoom > 1.001
            if (needsCrop) {
                @Suppress("DEPRECATION")
                val decoder = if (Build.VERSION.SDK_INT >= 31) BitmapRegionDecoder.newInstance(bytes, 0, bytes.size)
                else BitmapRegionDecoder.newInstance(bytes, 0, bytes.size, false)
                    ?: throw IllegalStateException("decoder")
                val c = CropMath.centered(decoder.width, decoder.height, ratio, zoom, align = 2)
                val bmp = decoder.decodeRegion(Rect(c.left, c.top, c.left + c.width, c.top + c.height), null)
                decoder.recycle()
                FileOutputStream(jpg).use { bmp.compress(Bitmap.CompressFormat.JPEG, 95, it) }
                outW = bmp.width
                outH = bmp.height
                thumb = scaleDown(bmp, 360)
                bmp.recycle()
            } else {
                jpg.writeBytes(bytes)
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                outW = bounds.outWidth
                outH = bounds.outHeight
                val opts = BitmapFactory.Options().apply {
                    inSampleSize = max(1, max(outW, outH) / 360)
                }
                thumb = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
            }
            saver.writeExif(jpg, exifFor(job, job.result))
            val uri = saver.saveJpeg(jpg, base, t.timeMillis)
            if (uri == null) callback.onSaveError("GALLERY WRITE FAILED")
            callback.onSaved(SavedShot(uri, null, thumb?.let { rotate(it, t.orientation) }, job.sensor.label, outW, outH))
        } finally {
            jpg.delete()
        }
    }
}
