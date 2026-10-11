package dev.panasko.photoliz.camera

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.MeteringRectangle
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.Image
import android.media.ImageReader
import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.util.Range
import android.util.Size
import android.view.Surface
import dev.panasko.photoliz.processing.CaptureSaver
import dev.panasko.photoliz.processing.ShotJob
import dev.panasko.photoliz.processing.ShotProcessor
import dev.panasko.photoliz.processing.ShotTicket
import java.io.File
import java.util.concurrent.Executor
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private const val TAG = "PhotolizCamera"

/**
 * Camera2 engine. Everything below runs on one camera thread; the UI talks to it through the public methods
 * (which post) and hears back through [Listener] (always called on the main thread).
 *
 * Only hardware controls are used: if a key does not exist for the sensor, it is never set.
 */
class CameraController(context: Context, private val listener: Listener) {

    interface Listener {
        fun onSessionReady(info: SessionInfo)
        fun onCameraError(message: String, recoverable: Boolean)
        fun onLive(state: LiveState)
        fun onFocus(state: FocusUi)
        fun onShutter(burstIndex: Int)
        fun onSaved(shot: SavedShot)
        fun onSaveError(message: String)
        fun onNotice(message: String)
        fun onBurst(active: Boolean, count: Int)
        fun onRecording(active: Boolean, startedAtElapsed: Long)
        fun onVideoSaved(uri: android.net.Uri, thumbnail: android.graphics.Bitmap?)
        fun onBusy(busy: Boolean)
    }

    private val app = context.applicationContext
    private val manager = app.getSystemService(CameraManager::class.java)
    private val thread = HandlerThread("PhotolizCamera").apply { start() }
    val handler = Handler(thread.looper)
    private val main = Handler(Looper.getMainLooper())
    private val executor = Executor { handler.post(it) }
    private val saver = CaptureSaver(app)

    private val processor = ShotProcessor(app, object : ShotProcessor.Callback {
        override fun onSaved(shot: SavedShot) = post { listener.onSaved(shot) }
        override fun onSaveError(message: String) = post { listener.onSaveError(message) }
        override fun onNotice(message: String) = post { listener.onNotice(message) }
    })

    private fun post(block: () -> Unit) { main.post(block) }

    // ---------------------------------------------------------------- camera-thread state
    private var device: CameraDevice? = null
    private var deviceId: String? = null
    private var session: CameraCaptureSession? = null
    private var option: CameraOption? = null
    private var caps: Caps? = null
    private var settings = CaptureSettings()
    private var texture: SurfaceTexture? = null
    private var previewSurface: Surface? = null
    private var previewSize: Size? = null
    private val stills = LinkedHashMap<String, StillStream>()
    private var companion: ImageReader? = null
    private var maxResActive = false
    private var sessionGen = 0
    private var openGen = 0
    private var closedByUs = false

    @Volatile var deviceOrientation: Int = 0
    @Volatile var dngTreeUri: String? = null

    // AF / AE sequencing
    private var afRegionNorm: Pair<Float, Float>? = null
    private var afWaiter: Waiter? = null
    private var aeWaiter: Waiter? = null
    private var precaptureActive = false

    // Capture state
    private var shotCounter = 0L
    private var inFlight = 0
    private var stillBusy = false
    private var burstActive = false
    private var burstCount = 0
    private var burstSequence = 0
    private var burstStartMillis = 0L

    // Video
    private var recorderSurface: Surface? = null
    private var recorderSize: Size? = null
    private var recorder: MediaRecorder? = null
    private var videoEntry: CaptureSaver.VideoEntry? = null
    private var recording = false
    private var lastLiveMs = 0L

    private class StillStream(val sensor: SensorInfo, val reader: ImageReader, val maxRes: Boolean) {
        val pending = ArrayDeque<PendingFrame>()
    }

    private class PendingFrame(val ticket: ShotTicket) {
        var image: Image? = null
        var result: CaptureResult? = null
        var logical: CaptureResult? = null
        /** Sensor timestamp from onCaptureStarted: pairs the image with its request even if a frame fails. */
        var timestamp = -1L
        /** The request failed but its image may still arrive: absorb it and drop it. */
        var failed = false
    }

    private class Waiter(val deadline: Long, val onDone: (Boolean) -> Unit) {
        var armedFrame = Long.MAX_VALUE
        var sawActive = false
        var done = false
    }

    // ================================================================ public API (thread safe)

    fun setTexture(t: SurfaceTexture?) = handler.post {
        texture = t
        if (t == null) closeCameraInternal()
    }

    /** Opens [opt] (or reuses the open device) and builds a session for [s]. */
    fun open(opt: CameraOption, s: CaptureSettings) = handler.post {
        settings = s
        if (device != null && deviceId == opt.openId && option?.key == opt.key) {
            configureSession()
            return@post
        }
        val sameDevice = device != null && deviceId == opt.openId
        option = opt
        caps = Caps(opt)
        afRegionNorm = null
        if (sameDevice) {
            configureSession()
        } else {
            closeCameraInternal()
            openDevice(opt.openId)
        }
    }

    /** Settings change. Rebuilds the session only when the stream layout changes. */
    fun update(s: CaptureSettings) = handler.post {
        val old = settings
        settings = s
        if (device == null) return@post
        val needsSession = old.mode != s.mode || old.maxRes != s.maxRes
        if (needsSession) {
            if (recording) stopRecordingInternal()
            configureSession()
        } else {
            if (old.afOn != s.afOn && !s.afOn) afWaiter?.let { finishWaiter(it, false) }
            if (old.flash != s.flash && precaptureActive) precaptureActive = false
            setRepeating()
        }
    }

    fun tapFocus(nx: Float, ny: Float) = handler.post {
        val c = caps ?: return@post
        if (!c.autofocus || !settings.afOn || burstActive) return@post
        afRegionNorm = nx to ny
        runAutofocus(fromTap = true) { }
    }

    fun capture() = handler.post {
        if (session == null || settings.mode != CaptureMode.PHOTO) return@post
        if (stillBusy || burstActive) return@post
        if (inFlight >= maxInFlight()) {
            post { listener.onNotice("BUFFER FULL") }
            return@post
        }
        stillBusy = true
        post { listener.onBusy(true) }
        prepareThenShoot { submitStill(0) }
    }

    fun startBurst() = handler.post {
        if (session == null || settings.mode != CaptureMode.PHOTO || burstActive) return@post
        burstActive = true
        burstCount = 0
        burstSequence++
        burstStartMillis = System.currentTimeMillis()
        setRepeating() // OIS on + black level lock for the whole burst
        post { listener.onBurst(true, 0) }
        val seq = burstSequence
        prepareThenShoot { if (burstActive && seq == burstSequence) pumpBurst() }
    }

    fun stopBurst() = handler.post {
        if (!burstActive) return@post
        burstActive = false
        precaptureActive = false
        setRepeating()
        post { listener.onBurst(false, burstCount) }
    }

    fun startRecording() = handler.post { startRecordingInternal() }
    fun stopRecording() = handler.post { stopRecordingInternal() }

    fun close() = handler.post { closeCameraInternal() }

    fun release() {
        handler.post {
            closeCameraInternal()
            try { recorderSurface?.release() } catch (_: Throwable) {}
            recorderSurface = null
            thread.quitSafely()
        }
    }

    // ================================================================ device

    @SuppressLint("MissingPermission")
    private fun openDevice(id: String) {
        if (app.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            post { listener.onCameraError("CAMERA PERMISSION DENIED", false) }
            return
        }
        val gen = ++openGen
        closedByUs = false
        try {
            manager.openCamera(id, executor, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    if (gen != openGen) { camera.close(); return }
                    device = camera
                    deviceId = id
                    configureSession()
                }

                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                    if (device === camera) {
                        resetDeviceState()
                        if (!closedByUs) post { listener.onCameraError("CAMERA IN USE BY ANOTHER APP", true) }
                    }
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close()
                    if (device === camera || device == null) {
                        resetDeviceState()
                        val msg = when (error) {
                            ERROR_CAMERA_IN_USE, ERROR_MAX_CAMERAS_IN_USE -> "CAMERA BUSY"
                            ERROR_CAMERA_DISABLED -> "CAMERA DISABLED BY POLICY"
                            ERROR_CAMERA_DEVICE -> "CAMERA DEVICE ERROR"
                            else -> "CAMERA SERVICE ERROR"
                        }
                        post { listener.onCameraError(msg, error != ERROR_CAMERA_DISABLED) }
                    }
                }
            })
        } catch (e: CameraAccessException) {
            post { listener.onCameraError(if (e.reason == CameraAccessException.CAMERA_DISABLED) "CAMERA DISABLED" else "CAMERA UNAVAILABLE", true) }
        } catch (t: Throwable) {
            Log.e(TAG, "openCamera", t)
            post { listener.onCameraError("CAMERA UNAVAILABLE", true) }
        }
    }

    private fun resetDeviceState() {
        if (recording) abortRecording()
        session = null
        device = null
        deviceId = null
        releaseStreams()
        afWaiter = null
        aeWaiter = null
        precaptureActive = false
        if (burstActive) {
            burstActive = false
            post { listener.onBurst(false, burstCount) }
        }
        if (stillBusy) {
            stillBusy = false
            post { listener.onBusy(false) }
        }
    }

    private fun closeCameraInternal() {
        openGen++
        sessionGen++
        closedByUs = true
        if (recording) stopRecordingInternal()
        try { session?.close() } catch (_: Throwable) {}
        try { device?.close() } catch (_: Throwable) {}
        resetDeviceState()
        try { previewSurface?.release() } catch (_: Throwable) {}
        previewSurface = null
    }

    private fun releaseStreams() {
        for (s in stills.values) {
            for (p in s.pending) try { p.image?.close() } catch (_: Throwable) {}
            s.pending.clear()
            try { s.reader.setOnImageAvailableListener(null, null) } catch (_: Throwable) {}
            // Readers whose images are still being processed are closed when the processor releases them.
            if (inFlight == 0) try { s.reader.close() } catch (_: Throwable) {} else orphanReaders.add(s.reader)
        }
        stills.clear()
        companion?.let { c ->
            try { c.setOnImageAvailableListener(null, null) } catch (_: Throwable) {}
            try { c.close() } catch (_: Throwable) {}
        }
        companion = null
    }

    /** Readers detached from a dead session but with images still in the save pipeline. */
    private val orphanReaders = ArrayList<ImageReader>()

    private fun closeOrphansIfIdle() {
        if (inFlight == 0 && orphanReaders.isNotEmpty()) {
            for (r in orphanReaders) try { r.close() } catch (_: Throwable) {}
            orphanReaders.clear()
        }
    }

    // ================================================================ session

    private class PlanStream(val sensor: SensorInfo, val format: Int, val size: Size, val maxRes: Boolean, val companion: Boolean)
    private class Plan(val streams: List<PlanStream>, val maxRes: Boolean, val raw: Boolean)

    private fun stillPlans(opt: CameraOption, s: CaptureSettings): List<Plan> {
        val plans = ArrayList<Plan>()
        if (opt.isCombo) {
            val withRaw = opt.sensors.mapIndexed { i, sn ->
                val useRaw = sn.raw && opt.comboRaw.getOrElse(i) { sn.raw }
                PlanStream(sn, if (useRaw) ImageFormat.RAW_SENSOR else ImageFormat.JPEG, (if (useRaw) sn.rawSize else sn.jpegSize) ?: Size(640, 480), false, false)
            }
            plans.add(Plan(withRaw, false, withRaw.all { it.format == ImageFormat.RAW_SENSOR }))
            if (withRaw.any { it.format == ImageFormat.RAW_SENSOR }) {
                plans.add(Plan(opt.sensors.map { PlanStream(it, ImageFormat.JPEG, it.jpegSize ?: Size(640, 480), false, false) }, false, false))
            }
            return plans
        }
        val sn = opt.primary
        if (s.maxRes && sn.pixelModeAvailable && Build.VERSION.SDK_INT >= 31) {
            val list = ArrayList<PlanStream>()
            list.add(PlanStream(sn, ImageFormat.RAW_SENSOR, sn.rawSizeMax!!, true, false))
            if (sn.remosaic) {
                val c = sn.maxResCompanion
                if (c != null) list.add(PlanStream(sn, c.first, c.second, true, true))
            }
            // Without a companion stream a remosaic-capable sensor would deliver quad-Bayer RAW: skip that plan.
            if (!sn.remosaic || list.size == 2) plans.add(Plan(list, true, true))
        }
        if (sn.raw) plans.add(Plan(listOf(PlanStream(sn, ImageFormat.RAW_SENSOR, sn.rawSize!!, false, false)), false, true))
        sn.jpegSize?.let { plans.add(Plan(listOf(PlanStream(sn, ImageFormat.JPEG, it, false, false)), false, false)) }
        return plans
    }

    private fun aspectOf(sn: SensorInfo): Float {
        val a = sn.activeArray
        return if (a.height() > 0) a.width().toFloat() / a.height() else 4f / 3f
    }

    private fun choosePreviewSize(sn: SensorInfo, aspect: Float): Size {
        val sizes = try { sn.map?.getOutputSizes(SurfaceTexture::class.java) } catch (t: Throwable) { null }
        if (sizes.isNullOrEmpty()) return Size(1280, 960)
        val maxArea = 1920L * 1440L
        val candidates = sizes.filter { it.area <= maxArea && it.width >= it.height }
        val pool = candidates.ifEmpty { sizes.toList() }
        return pool.minWithOrNull(compareBy<Size>({ abs(it.width.toFloat() / it.height - aspect) > 0.02f }, { -it.area })) ?: sizes[0]
    }

    private fun chooseVideoSize(sn: SensorInfo): Size? {
        val map = sn.map ?: return null
        val sizes = try { map.getOutputSizes(MediaRecorder::class.java)?.toList() } catch (t: Throwable) { null } ?: return null
        val codecs = MediaCodecList(MediaCodecList.REGULAR_CODECS)
        fun ok(sz: Size): Boolean {
            if (sizes.none { it == sz }) return false
            val minFrame = try { map.getOutputMinFrameDuration(MediaRecorder::class.java, sz) } catch (t: Throwable) { 0L }
            if (minFrame > 34_000_000L) return false
            val fmt = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, sz.width, sz.height)
            fmt.setInteger(MediaFormat.KEY_FRAME_RATE, 30)
            return codecs.findEncoderForFormat(fmt) != null
        }
        for (sz in listOf(Size(3840, 2160), Size(1920, 1080), Size(1280, 720))) if (ok(sz)) return sz
        return sizes.filter { it.width <= 1920 && it.height <= 1080 }.maxByOrNull { it.area }
    }

    private fun configureSession() {
        val dev = device ?: return
        val opt = option ?: return
        val tex = texture ?: return
        val gen = ++sessionGen
        if (recording) stopRecordingInternal()
        try { session?.close() } catch (_: Throwable) {}
        session = null
        releaseStreams()
        afWaiter = null
        aeWaiter = null
        precaptureActive = false

        val primary = opt.primary
        val video = settings.mode == CaptureMode.VIDEO
        val videoSize = if (video) chooseVideoSize(primary) else null
        if (video && videoSize == null) {
            post { listener.onCameraError("VIDEO NOT SUPPORTED ON THIS SENSOR", true) }
            return
        }
        val aspect = if (videoSize != null) videoSize.width.toFloat() / videoSize.height else aspectOf(primary)
        val pSize = choosePreviewSize(primary, aspect)
        tex.setDefaultBufferSize(pSize.width, pSize.height)
        try { previewSurface?.release() } catch (_: Throwable) {}
        previewSurface = Surface(tex)
        previewSize = pSize

        if (video) {
            val vs = videoSize!!
            if (!prepareRecorderSurface(vs)) {
                post { listener.onCameraError("VIDEO ENCODER UNAVAILABLE", true) }
                return
            }
            tryVideoSession(dev, opt, gen, vs)
        } else {
            tryPlans(dev, opt, stillPlans(opt, settings), 0, gen)
        }
    }

    private fun previewOutput(sn: SensorInfo): OutputConfiguration {
        val oc = OutputConfiguration(previewSurface!!)
        sn.physicalId?.let { oc.setPhysicalCameraId(it) }
        return oc
    }

    private fun isSupported(dev: CameraDevice, outputs: List<OutputConfiguration>): Boolean? = try {
        dev.isSessionConfigurationSupported(SessionConfiguration(SessionConfiguration.SESSION_REGULAR, outputs, executor, NoopSessionCallback))
    } catch (e: UnsupportedOperationException) {
        null
    } catch (e: IllegalArgumentException) {
        false
    } catch (t: Throwable) {
        null
    }

    private fun tryPlans(dev: CameraDevice, opt: CameraOption, plans: List<Plan>, index: Int, gen: Int) {
        if (gen != sessionGen) return
        if (index >= plans.size) {
            post { listener.onCameraError("NO WORKING STREAM CONFIGURATION", true) }
            return
        }
        val plan = plans[index]
        releaseStreams()
        val outputs = ArrayList<OutputConfiguration>()
        outputs.add(previewOutput(opt.primary))
        val newStills = LinkedHashMap<String, StillStream>()
        var newCompanion: ImageReader? = null
        for (ps in plan.streams) {
            val bytes = ps.size.area * (if (ps.format == ImageFormat.RAW_SENSOR) 2 else 1)
            val maxImages = when {
                ps.companion -> 2
                ps.format == ImageFormat.RAW_SENSOR -> (240L * 1024 * 1024 / max(1L, bytes)).toInt().coerceIn(2, 5)
                else -> 4
            }
            val reader = try { ImageReader.newInstance(ps.size.width, ps.size.height, ps.format, maxImages) } catch (t: Throwable) { null }
            if (reader == null) { tryPlans(dev, opt, plans, index + 1, gen); return }
            val oc = OutputConfiguration(reader.surface)
            ps.sensor.physicalId?.let { oc.setPhysicalCameraId(it) }
            if (ps.maxRes && Build.VERSION.SDK_INT >= 31) {
                try { oc.addSensorPixelModeUsed(CameraMetadata.SENSOR_PIXEL_MODE_MAXIMUM_RESOLUTION) } catch (_: Throwable) {}
            }
            outputs.add(oc)
            if (ps.companion) {
                newCompanion = reader
                reader.setOnImageAvailableListener({ r -> drain(r) }, handler)
            } else {
                val stream = StillStream(ps.sensor, reader, ps.maxRes)
                newStills[ps.sensor.key] = stream
                reader.setOnImageAvailableListener({ r -> onStillImage(stream, r) }, handler)
            }
        }
        stills.putAll(newStills)
        companion = newCompanion

        val needsProbe = plan.maxRes || opt.isCombo || opt.primary.physicalId != null
        if (needsProbe && isSupported(dev, outputs) == false) {
            Log.i(TAG, "plan $index not supported, trying next")
            tryPlans(dev, opt, plans, index + 1, gen)
            return
        }
        createSession(dev, outputs, gen, onFail = { tryPlans(dev, opt, plans, index + 1, gen) }) {
            maxResActive = plan.maxRes
            if (settings.maxRes && !plan.maxRes && opt.primary.pixelModeAvailable) {
                post { listener.onNotice("MAX RES NOT AVAILABLE · USING DEFAULT") }
            }
            if (opt.isCombo && !plan.raw) post { listener.onNotice("COMBO: ISP JPEG ONLY") }
            val first = plan.streams.firstOrNull { !it.companion }
            sessionReady(SessionInfo(opt, CaptureMode.PHOTO, previewSize!!, opt.primary.sensorOrientation,
                opt.primary.facing == CameraMetadata.LENS_FACING_FRONT, first?.size, first?.format == ImageFormat.RAW_SENSOR, plan.maxRes, null))
        }
    }

    private fun tryVideoSession(dev: CameraDevice, opt: CameraOption, gen: Int, vs: Size) {
        val outputs = ArrayList<OutputConfiguration>()
        outputs.add(previewOutput(opt.primary))
        val rec = OutputConfiguration(recorderSurface!!)
        opt.primary.physicalId?.let { rec.setPhysicalCameraId(it) }
        outputs.add(rec)
        maxResActive = false
        createSession(dev, outputs, gen, onFail = { post { listener.onCameraError("VIDEO SESSION FAILED", true) } }) {
            sessionReady(SessionInfo(opt, CaptureMode.VIDEO, previewSize!!, opt.primary.sensorOrientation,
                opt.primary.facing == CameraMetadata.LENS_FACING_FRONT, null, false, false, vs))
        }
    }

    private fun createSession(dev: CameraDevice, outputs: List<OutputConfiguration>, gen: Int, onFail: () -> Unit, onReady: () -> Unit) {
        val cb = object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(s: CameraCaptureSession) {
                if (gen != sessionGen || device !== dev) { try { s.close() } catch (_: Throwable) {}; return }
                session = s
                onReady()
            }

            override fun onConfigureFailed(s: CameraCaptureSession) {
                if (gen != sessionGen) return
                Log.w(TAG, "configure failed")
                onFail()
            }
        }
        try {
            dev.createCaptureSession(SessionConfiguration(SessionConfiguration.SESSION_REGULAR, outputs, executor, cb))
        } catch (t: Throwable) {
            Log.w(TAG, "createCaptureSession", t)
            onFail()
        }
    }

    private fun sessionReady(info: SessionInfo) {
        setRepeating()
        post { listener.onSessionReady(info) }
    }

    private fun drain(r: ImageReader) {
        try { r.acquireLatestImage()?.close() } catch (_: Throwable) {}
    }

    // ================================================================ requests

    private fun builder(template: Int): CaptureRequest.Builder? {
        val dev = device ?: return null
        val opt = option ?: return null
        val phys = opt.sensors.mapNotNull { it.physicalId }.toSet()
        return try {
            if (phys.isNotEmpty()) dev.createCaptureRequest(template, phys) else dev.createCaptureRequest(template)
        } catch (t: Throwable) {
            Log.w(TAG, "createCaptureRequest", t)
            null
        }
    }

    /** Sets a key on the request and, where allowed, as per-physical-sensor override. */
    private fun <T> CaptureRequest.Builder.put(key: CaptureRequest.Key<T>, value: T) {
        val opt = option ?: return
        try { set(key, value) } catch (_: Throwable) {}
        for (sn in opt.sensors) {
            val pid = sn.physicalId ?: continue
            if (key in sn.physicalRequestKeys) {
                try { setPhysicalCameraKey(key, value, pid) } catch (_: Throwable) {}
            }
        }
    }

    private fun exposureMode(): ExposureMode {
        val c = caps ?: return ExposureMode.AUTO
        if (!c.manual) return ExposureMode.AUTO
        return settings.exposureMode(c)
    }

    private fun clampIso(c: Caps, iso: Int): Int = c.isoRange?.let { iso.coerceIn(it.lower, it.upper) } ?: iso
    private fun clampExposure(c: Caps, ns: Long): Long = c.exposureRange?.let { ns.coerceIn(it.lower, it.upper) } ?: ns

    private fun applyCommon(b: CaptureRequest.Builder, still: Boolean, afTrigger: Int? = null, aeTrigger: Int? = null) {
        val c = caps ?: return
        val opt = option ?: return
        val p = opt.primary
        val s = settings
        val video = s.mode == CaptureMode.VIDEO
        val burst = burstActive
        b.put(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
        if (p.accepts(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE)) {
            // Electronic stabilization is software: always off.
            b.put(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_OFF)
        }

        // ---- exposure
        val mode = exposureMode()
        val flashAuto = s.flash == FlashMode.FLASH && mode != ExposureMode.MANUAL
        val aeOnMode = if (flashAuto && (still || precaptureActive)) CameraMetadata.CONTROL_AE_MODE_ON_ALWAYS_FLASH
        else CameraMetadata.CONTROL_AE_MODE_ON
        when (mode) {
            ExposureMode.AUTO -> {
                b.put(CaptureRequest.CONTROL_AE_MODE, aeOnMode)
                if (Build.VERSION.SDK_INT >= 36 && (c.isoPriority || c.shutterPriority)) {
                    b.put(CaptureRequest.CONTROL_AE_PRIORITY_MODE, CameraMetadata.CONTROL_AE_PRIORITY_MODE_OFF)
                }
            }
            ExposureMode.MANUAL -> {
                val iso = clampIso(c, s.iso)
                var t = clampExposure(c, s.exposureNs)
                var shownIso = iso
                var frame: Long
                if (video) {
                    t = min(t, 33_333_333L)
                    frame = 33_333_333L
                } else if (!still && t > PREVIEW_MAX_EXPOSURE) {
                    // Long exposures: keep the viewfinder alive with the same brightness (exposure x gain).
                    val gain = t.toDouble() / PREVIEW_MAX_EXPOSURE
                    t = PREVIEW_MAX_EXPOSURE
                    shownIso = (iso * gain).roundToInt().let { v -> c.isoRange?.let { v.coerceIn(it.lower, it.upper) } ?: v }
                    frame = PREVIEW_MAX_EXPOSURE
                } else {
                    frame = if (still) t else max(t, 33_333_333L)
                }
                if (p.maxFrameDuration > 0) frame = min(frame, p.maxFrameDuration)
                frame = max(frame, t)
                b.put(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)
                b.put(CaptureRequest.SENSOR_SENSITIVITY, shownIso)
                b.put(CaptureRequest.SENSOR_EXPOSURE_TIME, t)
                b.put(CaptureRequest.SENSOR_FRAME_DURATION, frame)
                if (c.apertures.isNotEmpty()) {
                    val f = if (s.aperture > 0f) c.apertures.minByOrNull { abs(it - s.aperture) }!! else c.apertures.first()
                    b.put(CaptureRequest.LENS_APERTURE, f)
                }
            }
            ExposureMode.ISO_PRIORITY -> {
                b.put(CaptureRequest.CONTROL_AE_MODE, aeOnMode)
                if (Build.VERSION.SDK_INT >= 36) {
                    b.put(CaptureRequest.CONTROL_AE_PRIORITY_MODE, CameraMetadata.CONTROL_AE_PRIORITY_MODE_SENSOR_SENSITIVITY_PRIORITY)
                }
                b.put(CaptureRequest.SENSOR_SENSITIVITY, clampIso(c, s.iso))
            }
            ExposureMode.SHUTTER_PRIORITY -> {
                b.put(CaptureRequest.CONTROL_AE_MODE, aeOnMode)
                if (Build.VERSION.SDK_INT >= 36) {
                    b.put(CaptureRequest.CONTROL_AE_PRIORITY_MODE, CameraMetadata.CONTROL_AE_PRIORITY_MODE_SENSOR_EXPOSURE_TIME_PRIORITY)
                }
                var t = clampExposure(c, s.exposureNs)
                if (video) t = min(t, 33_333_333L)
                b.put(CaptureRequest.SENSOR_EXPOSURE_TIME, t)
            }
        }
        if (mode != ExposureMode.MANUAL) {
            if (video) chooseFpsRange(p, true)?.let { b.put(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it) }
            else chooseFpsRange(p, false)?.let { b.put(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it) }
            if (c.apertures.isNotEmpty()) b.put(CaptureRequest.LENS_APERTURE, c.apertures.first())
            aeTrigger?.let { b.put(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER, it) }
            val region = afRegionNorm
            if (region != null && p.maxAeRegions > 0 && opt.primary.physicalId == null && !(still && maxResActive)) {
                meteringRect(region.first, region.second)?.let { b.put(CaptureRequest.CONTROL_AE_REGIONS, arrayOf(it)) }
            }
        }
        if (c.ndDensities.isNotEmpty() && !s.ndAuto) {
            val nd = c.ndDensities.minByOrNull { abs(it - s.nd) }!!
            b.put(CaptureRequest.LENS_FILTER_DENSITY, nd)
        }
        if (c.opticalFocalLengths.isNotEmpty() && s.focalLength > 0f) {
            b.put(CaptureRequest.LENS_FOCAL_LENGTH, c.opticalFocalLengths.minByOrNull { abs(it - s.focalLength) }!!)
        }

        // ---- flash
        val flashMode = when {
            s.flash == FlashMode.TORCH -> CameraMetadata.FLASH_MODE_TORCH
            s.flash == FlashMode.FLASH && still && mode == ExposureMode.MANUAL -> CameraMetadata.FLASH_MODE_SINGLE
            else -> CameraMetadata.FLASH_MODE_OFF
        }
        if (c.flash) b.put(CaptureRequest.FLASH_MODE, flashMode)

        // ---- focus
        if (c.autofocus && s.afOn) {
            b.put(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_AUTO)
            val region = afRegionNorm
            if (region != null && p.maxAfRegions > 0 && opt.primary.physicalId == null && !(still && maxResActive)) {
                meteringRect(region.first, region.second)?.let { b.put(CaptureRequest.CONTROL_AF_REGIONS, arrayOf(it)) }
            }
            b.put(CaptureRequest.CONTROL_AF_TRIGGER, afTrigger ?: CameraMetadata.CONTROL_AF_TRIGGER_IDLE)
        } else if (c.manualFocus) {
            b.put(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF)
            b.put(CaptureRequest.LENS_FOCUS_DISTANCE, s.focusDiopters.coerceIn(0f, c.minFocusDiopters))
        } else if (p.afModes.contains(CameraMetadata.CONTROL_AF_MODE_OFF)) {
            b.put(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF)
        }

        // ---- stabilization / black level
        if (c.ois) {
            b.put(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE,
                if (s.ois || burst) CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON else CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_OFF)
        }
        if (c.blackLevelLock) b.put(CaptureRequest.BLACK_LEVEL_LOCK, burst)

        // ---- test pattern
        if (c.testPatterns.isNotEmpty()) {
            b.put(CaptureRequest.SENSOR_TEST_PATTERN_MODE, if (s.testPattern in c.testPatterns) s.testPattern else CameraMetadata.SENSOR_TEST_PATTERN_MODE_OFF)
        }

        // ---- zoom: video only (photo zoom is a crop of the full RAW frame)
        if (video && c.videoZoomMax > 1f && p.accepts(CaptureRequest.CONTROL_ZOOM_RATIO)) {
            b.put(CaptureRequest.CONTROL_ZOOM_RATIO, s.zoom.coerceIn(1f, c.videoZoomMax))
        } else if (p.accepts(CaptureRequest.CONTROL_ZOOM_RATIO) && opt.primary.physicalId == null && !opt.isCombo) {
            b.put(CaptureRequest.CONTROL_ZOOM_RATIO, 1f)
        }

        if (still) {
            if (opt.sensors.any { it.shadingMapOn }) {
                b.put(CaptureRequest.STATISTICS_LENS_SHADING_MAP_MODE, CameraMetadata.STATISTICS_LENS_SHADING_MAP_MODE_ON)
            }
            if (maxResActive && Build.VERSION.SDK_INT >= 31) {
                b.put(CaptureRequest.SENSOR_PIXEL_MODE, CameraMetadata.SENSOR_PIXEL_MODE_MAXIMUM_RESOLUTION)
            }
            // Processed (ISP) fallback stills: switch off what can be switched off.
            if (stills.values.any { it.reader.imageFormat != ImageFormat.RAW_SENSOR }) {
                if (p.accepts(CaptureRequest.NOISE_REDUCTION_MODE) && (p.chars.safe(android.hardware.camera2.CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES)
                        ?: IntArray(0)).contains(CameraMetadata.NOISE_REDUCTION_MODE_OFF)) {
                    b.put(CaptureRequest.NOISE_REDUCTION_MODE, CameraMetadata.NOISE_REDUCTION_MODE_OFF)
                }
                if (p.accepts(CaptureRequest.EDGE_MODE) && (p.chars.safe(android.hardware.camera2.CameraCharacteristics.EDGE_AVAILABLE_EDGE_MODES)
                        ?: IntArray(0)).contains(CameraMetadata.EDGE_MODE_OFF)) {
                    b.put(CaptureRequest.EDGE_MODE, CameraMetadata.EDGE_MODE_OFF)
                }
                b.put(CaptureRequest.JPEG_QUALITY, 95.toByte())
                b.put(CaptureRequest.JPEG_ORIENTATION, 0)
            }
        }
    }

    private fun chooseFpsRange(sn: SensorInfo, video: Boolean): Range<Int>? {
        val ranges = sn.chars.safe(android.hardware.camera2.CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES) ?: return null
        return if (video) {
            ranges.firstOrNull { it.lower == 30 && it.upper == 30 } ?: ranges.filter { it.upper == 30 }.maxByOrNull { it.lower }
        } else {
            ranges.filter { it.upper == 30 }.minByOrNull { it.lower } ?: ranges.maxByOrNull { it.upper }
        }
    }

    /** Normalized upright-preview coordinates -> metering rectangle in active-array coordinates. */
    private fun meteringRect(nx: Float, ny: Float): MeteringRectangle? {
        val opt = option ?: return null
        val p = opt.primary
        val front = p.facing == CameraMetadata.LENS_FACING_FRONT
        var u = nx.coerceIn(0f, 1f)
        val v = ny.coerceIn(0f, 1f)
        if (front) u = 1f - u
        // Undo the clockwise rotation that makes the sensor image upright.
        val (sx, sy) = when (((p.sensorOrientation % 360) + 360) % 360) {
            90 -> v to 1f - u
            180 -> 1f - u to 1f - v
            270 -> 1f - v to u
            else -> u to v
        }
        val a = p.activeArray
        val cx = (a.left + sx * a.width()).roundToInt()
        val cy = (a.top + sy * a.height()).roundToInt()
        val half = (min(a.width(), a.height()) * 0.06f).roundToInt().coerceAtLeast(16)
        val r = Rect(
            (cx - half).coerceIn(a.left, a.right - 1), (cy - half).coerceIn(a.top, a.bottom - 1),
            (cx + half).coerceIn(a.left + 1, a.right), (cy + half).coerceIn(a.top + 1, a.bottom),
        )
        if (r.width() <= 0 || r.height() <= 0) return null
        return MeteringRectangle(r, MeteringRectangle.METERING_WEIGHT_MAX - 1)
    }

    private fun setRepeating() {
        val s = session ?: return
        val template = if (settings.mode == CaptureMode.VIDEO) CameraDevice.TEMPLATE_RECORD else CameraDevice.TEMPLATE_PREVIEW
        val b = builder(template) ?: return
        val ps = previewSurface ?: return
        b.addTarget(ps)
        if (recording) recorderSurface?.let { b.addTarget(it) }
        applyCommon(b, still = false)
        try {
            s.setRepeatingRequest(b.build(), repeatingCallback, handler)
        } catch (t: Throwable) {
            Log.w(TAG, "setRepeatingRequest", t)
        }
    }

    private val repeatingCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(s: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
            onPreviewResult(result)
        }
    }

    @Suppress("DEPRECATION")
    private fun physicalResult(r: TotalCaptureResult, pid: String): CaptureResult? = try {
        if (Build.VERSION.SDK_INT >= 31) r.physicalCameraTotalResults[pid] else r.physicalCameraResults[pid]
    } catch (t: Throwable) { null }

    private fun onPreviewResult(result: TotalCaptureResult) {
        val opt = option ?: return
        val pid = opt.primary.physicalId
        val r: CaptureResult = (pid?.let { physicalResult(result, it) }) ?: result

        afWaiter?.let { w ->
            val st = result.get(CaptureResult.CONTROL_AF_STATE) ?: r.get(CaptureResult.CONTROL_AF_STATE)
            if (result.frameNumber >= w.armedFrame && st != null) {
                when (st) {
                    CaptureResult.CONTROL_AF_STATE_ACTIVE_SCAN -> w.sawActive = true
                    CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED -> finishWaiter(w, true)
                    CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED -> finishWaiter(w, false)
                    CaptureResult.CONTROL_AF_STATE_INACTIVE -> if (w.sawActive) finishWaiter(w, false)
                }
            }
            if (!w.done && SystemClock.elapsedRealtime() > w.deadline) finishWaiter(w, false)
        }
        aeWaiter?.let { w ->
            val st = result.get(CaptureResult.CONTROL_AE_STATE)
            if (result.frameNumber >= w.armedFrame && st != null) {
                if (st == CaptureResult.CONTROL_AE_STATE_PRECAPTURE) w.sawActive = true
                else if (w.sawActive || result.frameNumber > w.armedFrame + 2) {
                    if (st == CaptureResult.CONTROL_AE_STATE_CONVERGED || st == CaptureResult.CONTROL_AE_STATE_FLASH_REQUIRED ||
                        st == CaptureResult.CONTROL_AE_STATE_LOCKED) finishWaiter(w, true)
                }
            }
            if (!w.done && SystemClock.elapsedRealtime() > w.deadline) finishWaiter(w, false)
        }

        val now = SystemClock.elapsedRealtime()
        if (now - lastLiveMs >= 90) {
            lastLiveMs = now
            val live = LiveState(
                iso = r.get(CaptureResult.SENSOR_SENSITIVITY) ?: 0,
                exposureNs = r.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: 0L,
                aperture = r.get(CaptureResult.LENS_APERTURE) ?: (caps?.fixedAperture ?: 0f),
                nd = r.get(CaptureResult.LENS_FILTER_DENSITY) ?: 0f,
                focusDiopters = r.get(CaptureResult.LENS_FOCUS_DISTANCE) ?: -1f,
                focalLength = r.get(CaptureResult.LENS_FOCAL_LENGTH) ?: 0f,
            )
            post { listener.onLive(live) }
        }
    }

    private fun finishWaiter(w: Waiter, ok: Boolean) {
        if (w.done) return
        w.done = true
        if (afWaiter === w) afWaiter = null
        if (aeWaiter === w) aeWaiter = null
        w.onDone(ok)
    }

    // ================================================================ AF / precapture

    private fun runAutofocus(fromTap: Boolean, then: (Boolean) -> Unit) {
        val s = session
        val c = caps
        if (s == null || c == null || !c.autofocus || !settings.afOn) { then(true); return }
        afWaiter?.let { finishWaiter(it, false) }
        val region = afRegionNorm ?: (0.5f to 0.5f)
        post { listener.onFocus(FocusUi(FocusPhase.SCANNING, region.first, region.second, fromTap)) }
        val w = Waiter(SystemClock.elapsedRealtime() + 2500) { ok ->
            post { listener.onFocus(FocusUi(if (ok) FocusPhase.LOCKED else FocusPhase.FAILED, region.first, region.second, fromTap)) }
            then(ok)
        }
        afWaiter = w
        // Cancel any previous lock first so the new trigger always scans.
        val b = builder(CameraDevice.TEMPLATE_PREVIEW) ?: run { finishWaiter(w, false); return }
        b.addTarget(previewSurface ?: run { finishWaiter(w, false); return })
        applyCommon(b, still = false, afTrigger = CameraMetadata.CONTROL_AF_TRIGGER_START)
        try {
            s.capture(b.build(), object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
                    w.armedFrame = result.frameNumber
                    onPreviewResult(result)
                }

                override fun onCaptureFailed(session: CameraCaptureSession, request: CaptureRequest, failure: CaptureFailure) {
                    w.armedFrame = failure.frameNumber
                }
            }, handler)
        } catch (t: Throwable) {
            Log.w(TAG, "AF trigger", t)
            finishWaiter(w, false)
        }
        handler.postDelayed({ if (!w.done) finishWaiter(w, false) }, 2600)
    }

    private fun runPrecapture(then: () -> Unit) {
        val s = session
        val mode = exposureMode()
        if (s == null || settings.flash != FlashMode.FLASH || mode == ExposureMode.MANUAL || caps?.flash != true) { then(); return }
        precaptureActive = true
        setRepeating()
        val w = Waiter(SystemClock.elapsedRealtime() + 1800) { then() }
        aeWaiter = w
        val b = builder(CameraDevice.TEMPLATE_PREVIEW) ?: run { finishWaiter(w, false); return }
        b.addTarget(previewSurface ?: run { finishWaiter(w, false); return })
        applyCommon(b, still = false, aeTrigger = CameraMetadata.CONTROL_AE_PRECAPTURE_TRIGGER_START)
        try {
            s.capture(b.build(), object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
                    w.armedFrame = result.frameNumber
                }
            }, handler)
        } catch (t: Throwable) {
            finishWaiter(w, false)
        }
        handler.postDelayed({ if (!w.done) finishWaiter(w, false) }, 1900)
    }

    /** AF (when on) → flash precapture (when needed) → shoot. */
    private fun prepareThenShoot(shoot: () -> Unit) {
        runAutofocus(fromTap = false) {
            if (session == null) { abortShot(); return@runAutofocus }
            runPrecapture {
                if (session == null) { abortShot(); return@runPrecapture }
                shoot()
            }
        }
    }

    private fun abortShot() {
        if (stillBusy) {
            stillBusy = false
            post { listener.onBusy(false) }
        }
    }

    // ================================================================ stills

    private fun maxInFlight(): Int {
        val m = stills.values.minOfOrNull { it.reader.maxImages } ?: 2
        return (m - 1).coerceAtLeast(1)
    }

    private fun currentOrientation(sn: SensorInfo): Int {
        val dev = ((deviceOrientation + 45) / 90 * 90) % 360
        val front = sn.facing == CameraMetadata.LENS_FACING_FRONT
        val d = if (front) -dev else dev
        return (sn.sensorOrientation + d + 360) % 360
    }

    private fun submitStill(burstIndex: Int) {
        val s = session
        val opt = option
        if (s == null || opt == null || stills.isEmpty()) { abortShot(); return }
        val b = builder(CameraDevice.TEMPLATE_STILL_CAPTURE) ?: run { abortShot(); return }
        for (st in stills.values) b.addTarget(st.reader.surface)
        companion?.let { b.addTarget(it.surface) }
        if (!maxResActive) previewSurface?.let { b.addTarget(it) }
        applyCommon(b, still = true)
        val mode = exposureMode()
        val ticket = ShotTicket(
            id = ++shotCounter,
            settings = settings,
            orientation = currentOrientation(opt.primary),
            timeMillis = if (burstActive) burstStartMillis else System.currentTimeMillis(),
            index = burstIndex,
            frames = stills.size,
            treeUri = dngTreeUri,
            manualExposure = mode == ExposureMode.MANUAL,
        )
        b.setTag(ticket)
        for (st in stills.values) st.pending.addLast(PendingFrame(ticket))
        inFlight++
        try {
            s.capture(b.build(), stillCallback, handler)
        } catch (t: Throwable) {
            Log.w(TAG, "capture", t)
            dropTicket(ticket)
            abortShot()
            post { listener.onNotice("CAPTURE FAILED") }
        }
    }

    private fun pumpBurst() {
        if (!burstActive || session == null) return
        while (burstActive && inFlight < maxInFlight()) {
            submitStill(++burstCount)
            post { listener.onBurst(true, burstCount) }
            if (stills.isEmpty()) break
        }
    }

    private val stillCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureStarted(session: CameraCaptureSession, request: CaptureRequest, timestamp: Long, frameNumber: Long) {
            val t = request.tag as? ShotTicket ?: return
            for (st in stills.values) st.pending.firstOrNull { it.ticket === t }?.timestamp = timestamp
            post { listener.onShutter(t.index) }
        }

        override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
            val t = request.tag as? ShotTicket ?: return
            for (st in stills.values) {
                val frame = st.pending.firstOrNull { it.ticket === t && it.result == null } ?: continue
                val pid = st.sensor.physicalId
                frame.result = pid?.let { physicalResult(result, it) } ?: result
                frame.logical = result
                tryDeliver(st)
            }
            if (!burstActive && stillBusy) {
                stillBusy = false
                precaptureActive = false
                if (settings.flash == FlashMode.FLASH) setRepeating()
                post { listener.onBusy(false) }
            }
        }

        override fun onCaptureFailed(session: CameraCaptureSession, request: CaptureRequest, failure: CaptureFailure) {
            val t = request.tag as? ShotTicket ?: return
            Log.w(TAG, "capture failed reason=${failure.reason}")
            if (failure.wasImageCaptured()) {
                // Buffers may still be delivered for this request: keep its slots so they cannot be paired
                // with the next shot, and close them when they come.
                for (st in stills.values) {
                    val f = st.pending.firstOrNull { it.ticket === t } ?: continue
                    if (f.image != null) {
                        f.image?.close()
                        st.pending.remove(f)
                    } else {
                        f.failed = true
                    }
                }
                if (t.remaining.get() > 0) {
                    t.remaining.set(0)
                    inFlight = (inFlight - 1).coerceAtLeast(0)
                    closeOrphansIfIdle()
                }
            } else {
                dropTicket(t)
            }
            if (!burstActive) {
                abortShot()
                precaptureActive = false
            }
            post { listener.onNotice("FRAME LOST") }
            pumpBurst()
        }

        override fun onCaptureBufferLost(session: CameraCaptureSession, request: CaptureRequest, target: Surface, frameNumber: Long) {
            val t = request.tag as? ShotTicket ?: return
            val st = stills.values.firstOrNull { it.reader.surface == target } ?: return
            val frame = st.pending.firstOrNull { it.ticket === t } ?: return
            st.pending.remove(frame)
            frame.image?.close()
            if (!frame.failed) frameDone(t)
        }
    }

    private fun dropTicket(t: ShotTicket) {
        var removedAny = false
        for (st in stills.values) {
            val it = st.pending.iterator()
            while (it.hasNext()) {
                val f = it.next()
                if (f.ticket === t) {
                    f.image?.close()
                    it.remove()
                    removedAny = true
                }
            }
        }
        if (removedAny || t.remaining.get() > 0) {
            t.remaining.set(0)
            inFlight = (inFlight - 1).coerceAtLeast(0)
            closeOrphansIfIdle()
        }
    }

    private fun frameDone(t: ShotTicket) {
        // Exactly one transition to zero per ticket (a failed ticket was already settled at zero).
        if (t.remaining.get() > 0 && t.remaining.decrementAndGet() == 0) {
            inFlight = (inFlight - 1).coerceAtLeast(0)
            closeOrphansIfIdle()
        }
        pumpBurst()
    }

    private fun onStillImage(st: StillStream, r: ImageReader) {
        val img = try { r.acquireNextImage() } catch (t: Throwable) { null } ?: return
        // Pair by sensor timestamp; fall back to arrival order when the start callback has not been seen.
        val frame = st.pending.firstOrNull { it.image == null && it.timestamp == img.timestamp }
            ?: st.pending.firstOrNull { it.image == null && !it.failed && it.timestamp < 0 }
            ?: st.pending.firstOrNull { it.image == null && !it.failed }
        if (frame == null) {
            img.close()
            return
        }
        if (frame.failed) {
            st.pending.remove(frame)
            img.close()
            return
        }
        frame.image = img
        tryDeliver(st)
    }

    /** Hands every frame that has both its image and its result to the processor (in request order). */
    private fun tryDeliver(st: StillStream) {
        val it = st.pending.iterator()
        while (it.hasNext()) {
            val f = it.next()
            val img = f.image ?: continue
            if (f.failed) {
                img.close()
                it.remove()
                continue
            }
            val res = f.result ?: continue
            it.remove()
            val t = f.ticket
            val opt = option
            val suffix = if (opt != null && opt.isCombo) st.sensor.shortName else null
            processor.submit(ShotJob(img, res, f.logical, st.sensor, t, suffix) {
                handler.post { frameDone(t) }
            })
        }
    }

    // ================================================================ video

    private fun audioAllowed() = app.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    @Suppress("DEPRECATION")
    private fun newRecorder(): MediaRecorder = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(app) else MediaRecorder()

    private fun configureRecorder(r: MediaRecorder, size: Size, fd: java.io.FileDescriptor?, file: File?, orientation: Int, audio: Boolean) {
        if (audio) r.setAudioSource(MediaRecorder.AudioSource.CAMCORDER)
        r.setVideoSource(MediaRecorder.VideoSource.SURFACE)
        r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        if (fd != null) r.setOutputFile(fd) else r.setOutputFile(file!!.absolutePath)
        val bitrate = when {
            size.area >= 3840L * 2160 -> 50_000_000
            size.area >= 1920L * 1080 -> 20_000_000
            else -> max(4_000_000, (size.area * 30 / 8).toInt())
        }
        r.setVideoEncodingBitRate(bitrate)
        r.setVideoFrameRate(30)
        r.setVideoSize(size.width, size.height)
        r.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
        if (audio) {
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioSamplingRate(48_000)
            r.setAudioEncodingBitRate(192_000)
            r.setAudioChannels(1)
        }
        r.setOrientationHint(orientation)
        r.setInputSurface(recorderSurface!!)
    }

    /** Creates (once per size) the persistent encoder surface and primes it with a throwaway recorder. */
    private fun prepareRecorderSurface(size: Size): Boolean {
        if (recorderSurface != null && recorderSize == size) return true
        try { recorderSurface?.release() } catch (_: Throwable) {}
        recorderSurface = null
        val surface = try { MediaCodec.createPersistentInputSurface() } catch (t: Throwable) { return false }
        recorderSurface = surface
        recorderSize = size
        val tmp = File(app.cacheDir, "plz_primer.mp4")
        val r = newRecorder()
        return try {
            configureRecorder(r, size, null, tmp, 0, audio = false)
            r.prepare()
            true
        } catch (t: Throwable) {
            Log.w(TAG, "primer", t)
            try { surface.release() } catch (_: Throwable) {}
            recorderSurface = null
            false
        } finally {
            try { r.release() } catch (_: Throwable) {}
            tmp.delete()
        }
    }

    private fun startRecordingInternal() {
        val s = session
        val size = recorderSize
        val opt = option
        if (recording || s == null || size == null || opt == null || settings.mode != CaptureMode.VIDEO || recorderSurface == null) return
        val name = saver.fileStamp(System.currentTimeMillis(), 0)
        val entry = saver.createVideo(name) ?: run { post { listener.onNotice("CANNOT CREATE VIDEO FILE") }; return }
        val r = newRecorder()
        val audio = audioAllowed()
        try {
            configureRecorder(r, size, entry.pfd.fileDescriptor, null, currentOrientation(opt.primary), audio)
            r.prepare()
        } catch (t: Throwable) {
            Log.w(TAG, "recorder prepare", t)
            try { r.release() } catch (_: Throwable) {}
            saver.finishVideo(entry, keep = false)
            post { listener.onNotice("RECORDER FAILED") }
            return
        }
        recorder = r
        videoEntry = entry
        recording = true
        setRepeating()
        try {
            r.start()
        } catch (t: Throwable) {
            Log.w(TAG, "recorder start", t)
            abortRecording()
            post { listener.onNotice("RECORDER FAILED") }
            return
        }
        val started = SystemClock.elapsedRealtime()
        post { listener.onRecording(true, started) }
        if (!audio) post { listener.onNotice("NO MIC PERMISSION · SILENT VIDEO") }
    }

    private fun stopRecordingInternal() {
        if (!recording) return
        recording = false
        setRepeating()
        val r = recorder
        val e = videoEntry
        recorder = null
        videoEntry = null
        var ok = false
        if (r != null) {
            ok = try { r.stop(); true } catch (t: Throwable) { Log.w(TAG, "recorder stop", t); false }
            try { r.reset() } catch (_: Throwable) {}
            try { r.release() } catch (_: Throwable) {}
        }
        if (e != null) saver.finishVideo(e, ok)
        post {
            listener.onRecording(false, 0L)
            listener.onNotice(if (ok) "VIDEO SAVED" else "VIDEO TOO SHORT")
        }
        if (ok && e != null) {
            val uri = e.uri
            Thread {
                val thumb = try { app.contentResolver.loadThumbnail(uri, Size(320, 320), null) } catch (t: Throwable) { null }
                post { listener.onVideoSaved(uri, thumb) }
            }.start()
        }
    }

    private fun abortRecording() {
        recording = false
        val r = recorder
        val e = videoEntry
        recorder = null
        videoEntry = null
        if (r != null) {
            try { r.reset() } catch (_: Throwable) {}
            try { r.release() } catch (_: Throwable) {}
        }
        if (e != null) saver.finishVideo(e, keep = false)
        post { listener.onRecording(false, 0L) }
    }

    private object NoopSessionCallback : CameraCaptureSession.StateCallback() {
        override fun onConfigured(session: CameraCaptureSession) {}
        override fun onConfigureFailed(session: CameraCaptureSession) {}
    }

    companion object {
        private const val PREVIEW_MAX_EXPOSURE = 66_000_000L
    }
}
