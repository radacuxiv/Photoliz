package dev.panasko.photoliz.camera

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.ImageReader
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.Size
import android.view.Surface
import org.json.JSONObject
import java.util.concurrent.Executor

private const val TAG = "PhotolizProbe"

/**
 * Verifies, before the first run, which physical sensors behind logical cameras (and which simultaneous
 * pairs) the hardware really accepts as stream configurations. Uses CameraDevice.isSessionConfigurationSupported,
 * so nothing is streamed. Results are cached per OS build.
 */
class HardwareProbe(context: Context, private val handler: Handler) {
    private val app = context.applicationContext
    private val manager = app.getSystemService(CameraManager::class.java)
    private val executor = Executor { handler.post(it) }
    private val main = Handler(Looper.getMainLooper())

    fun applyCache(catalog: Catalog, json: String?): Boolean {
        if (json == null) return false
        return try {
            val o = JSONObject(json)
            var all = true
            for (opt in catalog.options) {
                if (opt.status == ScanStatus.OK && !opt.isCombo && opt.primary.physicalId == null) continue
                val e = o.optJSONObject(opt.key)
                if (e == null) { all = false; continue }
                opt.status = ScanStatus.valueOf(e.optString("s", "UNTESTED"))
                val raw = e.optString("r", "")
                if (raw.length == opt.sensors.size) opt.comboRaw = BooleanArray(raw.length) { raw[it] == '1' }
            }
            all
        } catch (t: Throwable) { false }
    }

    fun toCache(catalog: Catalog): String {
        val o = JSONObject()
        for (opt in catalog.options) {
            if (!opt.isCombo && opt.primary.physicalId == null) continue
            o.put(opt.key, JSONObject().put("s", opt.status.name).put("r", opt.comboRaw.joinToString("") { if (it) "1" else "0" }))
        }
        return o.toString()
    }

    /** Probes every UNTESTED option. [done] runs on the main thread. */
    fun run(catalog: Catalog, done: () -> Unit) {
        val groups = catalog.options.filter { it.status == ScanStatus.UNTESTED }.groupBy { it.openId }.toList()
        if (groups.isEmpty() || app.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            main.post(done)
            return
        }
        handler.post { next(groups, 0, done) }
    }

    private fun next(groups: List<Pair<String, List<CameraOption>>>, i: Int, done: () -> Unit) {
        if (i >= groups.size) { main.post(done); return }
        val (id, options) = groups[i]
        var finished = false
        val timeout = Runnable {
            if (!finished) { finished = true; Log.w(TAG, "open timeout $id"); next(groups, i + 1, done) }
        }
        handler.postDelayed(timeout, 4000)
        openCamera(id, object : CameraDevice.StateCallback() {
            override fun onOpened(camera: CameraDevice) {
                if (finished) { camera.close(); return }
                for (opt in options) probe(camera, opt)
                camera.close()
                finished = true
                handler.removeCallbacks(timeout)
                next(groups, i + 1, done)
            }

            override fun onDisconnected(camera: CameraDevice) {
                camera.close()
                if (!finished) { finished = true; handler.removeCallbacks(timeout); next(groups, i + 1, done) }
            }

            override fun onError(camera: CameraDevice, error: Int) {
                camera.close()
                if (!finished) { finished = true; handler.removeCallbacks(timeout); next(groups, i + 1, done) }
            }
        }) {
            if (!finished) { finished = true; handler.removeCallbacks(timeout); next(groups, i + 1, done) }
        }
    }

    @SuppressLint("MissingPermission")
    private fun openCamera(id: String, cb: CameraDevice.StateCallback, onFail: () -> Unit) {
        try {
            manager.openCamera(id, executor, cb)
        } catch (t: Throwable) {
            Log.w(TAG, "open $id", t)
            onFail()
        }
    }

    private fun previewSize(sn: SensorInfo): Size =
        (try { sn.map?.getOutputSizes(SurfaceTexture::class.java) } catch (t: Throwable) { null })
            ?.filter { it.width <= 1920 && it.height <= 1440 }?.maxByOrNull { it.area } ?: Size(1280, 720)

    private fun probe(camera: CameraDevice, opt: CameraOption) {
        val withRaw = BooleanArray(opt.sensors.size) { opt.sensors[it].raw }
        val r1 = check(camera, opt, withRaw)
        when {
            r1 == true -> { opt.status = ScanStatus.OK; opt.comboRaw = withRaw }
            withRaw.any { it } -> {
                val none = BooleanArray(opt.sensors.size)
                val r2 = check(camera, opt, none)
                when (r2) {
                    true -> { opt.status = ScanStatus.OK; opt.comboRaw = none }
                    false -> opt.status = if (r1 == null) ScanStatus.UNTESTED else ScanStatus.UNSUPPORTED
                    null -> opt.status = ScanStatus.UNTESTED
                }
            }
            r1 == false -> opt.status = ScanStatus.UNSUPPORTED
            else -> opt.status = ScanStatus.UNTESTED
        }
        Log.i(TAG, "${opt.key} -> ${opt.status} raw=${opt.comboRaw.joinToString()}")
    }

    /** @return true/false from the HAL, or null when the HAL cannot answer the query. */
    private fun check(camera: CameraDevice, opt: CameraOption, raw: BooleanArray): Boolean? {
        val readers = ArrayList<ImageReader>()
        var tex: SurfaceTexture? = null
        var surface: Surface? = null
        try {
            val p = opt.primary
            val ps = previewSize(p)
            tex = SurfaceTexture(false).apply { setDefaultBufferSize(ps.width, ps.height) }
            surface = Surface(tex)
            val outputs = ArrayList<OutputConfiguration>()
            outputs.add(OutputConfiguration(surface).apply { p.physicalId?.let { setPhysicalCameraId(it) } })
            opt.sensors.forEachIndexed { i, sn ->
                val useRaw = raw[i] && sn.rawSize != null
                val size = (if (useRaw) sn.rawSize else sn.jpegSize) ?: return false
                val reader = ImageReader.newInstance(size.width, size.height, if (useRaw) ImageFormat.RAW_SENSOR else ImageFormat.JPEG, 2)
                readers.add(reader)
                outputs.add(OutputConfiguration(reader.surface).apply { sn.physicalId?.let { setPhysicalCameraId(it) } })
            }
            val cfg = SessionConfiguration(SessionConfiguration.SESSION_REGULAR, outputs, executor, object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {}
                override fun onConfigureFailed(session: CameraCaptureSession) {}
            })
            return camera.isSessionConfigurationSupported(cfg)
        } catch (e: UnsupportedOperationException) {
            return null
        } catch (e: IllegalArgumentException) {
            return false
        } catch (t: Throwable) {
            Log.w(TAG, "check", t)
            return null
        } finally {
            readers.forEach { try { it.close() } catch (_: Throwable) {} }
            try { surface?.release() } catch (_: Throwable) {}
            try { tex?.release() } catch (_: Throwable) {}
        }
    }
}
