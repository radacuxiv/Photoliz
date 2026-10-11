package dev.panasko.photoliz

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.display.DisplayManager
import android.media.MediaActionSound
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.KeyEvent
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import dev.panasko.photoliz.camera.AspectRatio
import dev.panasko.photoliz.camera.CameraController
import dev.panasko.photoliz.camera.CameraOption
import dev.panasko.photoliz.camera.Caps
import dev.panasko.photoliz.camera.CaptureMode
import dev.panasko.photoliz.camera.CaptureSettings
import dev.panasko.photoliz.camera.Catalog
import dev.panasko.photoliz.camera.ExposureMode
import dev.panasko.photoliz.camera.ExposureScales
import dev.panasko.photoliz.camera.FlashMode
import dev.panasko.photoliz.camera.FocusPhase
import dev.panasko.photoliz.camera.FocusUi
import dev.panasko.photoliz.camera.HardwareProbe
import dev.panasko.photoliz.camera.LiveState
import dev.panasko.photoliz.camera.SavedShot
import dev.panasko.photoliz.camera.SessionInfo
import dev.panasko.photoliz.camera.area
import dev.panasko.photoliz.processing.CaptureSaver
import dev.panasko.photoliz.ui.ArcControls
import dev.panasko.photoliz.ui.CameraRootLayout
import dev.panasko.photoliz.ui.ChipBar
import dev.panasko.photoliz.ui.ChipButton
import dev.panasko.photoliz.ui.ChoiceRow
import dev.panasko.photoliz.ui.DialModel
import dev.panasko.photoliz.ui.DialStop
import dev.panasko.photoliz.ui.DialStrip
import dev.panasko.photoliz.ui.HardwareReport
import dev.panasko.photoliz.ui.MenuRow
import dev.panasko.photoliz.ui.Palette
import dev.panasko.photoliz.ui.Palettes
import dev.panasko.photoliz.ui.ParamBar
import dev.panasko.photoliz.ui.ParamCell
import dev.panasko.photoliz.ui.PixelIcons
import dev.panasko.photoliz.ui.Reveal
import dev.panasko.photoliz.ui.Sheet
import dev.panasko.photoliz.ui.ShutterButton
import dev.panasko.photoliz.ui.ShutterDeck
import dev.panasko.photoliz.ui.TextBlock
import dev.panasko.photoliz.ui.ThumbView
import dev.panasko.photoliz.ui.Toaster
import dev.panasko.photoliz.ui.Ui
import dev.panasko.photoliz.ui.ViewfinderView
import dev.panasko.photoliz.ui.WideButton
import dev.panasko.photoliz.util.OrientationTracker
import dev.panasko.photoliz.util.Prefs
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

class MainActivity : Activity(), CameraController.Listener {

    private lateinit var ui: Ui
    private lateinit var prefs: Prefs
    private lateinit var saver: CaptureSaver
    private var palette: Palette = Palettes.CANONICAL
    private val main = Handler(Looper.getMainLooper())

    // Views
    private lateinit var root: CameraRootLayout
    private lateinit var viewfinder: ViewfinderView
    private lateinit var topBar: ChipBar
    private lateinit var menuChip: ChipButton
    private lateinit var cameraChip: ChipButton
    private lateinit var pixelChip: ChipButton
    private lateinit var flashChip: ChipButton
    private lateinit var testChip: ChipButton
    private lateinit var paramBar: ParamBar
    private lateinit var isoCell: ParamCell
    private lateinit var shutterCell: ParamCell
    private lateinit var apertureCell: ParamCell
    private lateinit var ndCell: ParamCell
    private lateinit var dial: DialStrip
    private lateinit var deck: ShutterDeck
    private lateinit var shutter: ShutterButton
    private lateinit var modeChip: ChipButton
    private lateinit var afChip: ChipButton
    private lateinit var aspectChip: ChipButton
    private lateinit var thumb: ThumbView
    private lateinit var arcs: ArcControls
    private lateinit var toaster: Toaster
    private lateinit var menuSheet: Sheet
    private lateinit var reportSheet: Sheet
    private lateinit var pickerSheet: Sheet
    private lateinit var aboutSheet: Sheet

    // Engine
    private lateinit var controller: CameraController
    private lateinit var orientation: OrientationTracker
    private val sound = MediaActionSound()

    // State
    private var catalog: Catalog? = null
    private var option: CameraOption? = null
    private var caps: Caps? = null
    private var settings = CaptureSettings()
    private var live: LiveState? = null
    private var session: SessionInfo? = null
    private var activeDial: String? = null
    private var recording = false
    private var bursting = false
    private var lastPhotoUri: Uri? = null
    private var lastVideoUri: Uri? = null
    private var cameraError: String? = null
    private var permissionMissing = false
    private var resumed = false
    private var textureReady = false
    private var catalogReady = false
    private var reportPending = false
    private var displayRotationDeg = 0
    private var uiRotation = 0f
    private var lastTapX = -1f
    private var lastTapY = -1f
    private var lastPush = 0L
    private var pushScheduled = false
    private var keyShutterDown = false
    private var keyBurst = false
    private var backCallback: Any? = null

    private val cameraManager by lazy { getSystemService(CameraManager::class.java) }

    // ------------------------------------------------------------------ lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui = Ui(this)
        prefs = Prefs(this)
        saver = CaptureSaver(this)
        palette = Palettes.byName(prefs.palette)
        setupWindow()
        buildViews()
        controller = CameraController(this, this)
        controller.dngTreeUri = prefs.dngTreeUri?.takeIf { saver.hasTreePermission(it) }
        orientation = OrientationTracker(this) { o -> onDeviceOrientation(o) }
        try {
            sound.load(MediaActionSound.SHUTTER_CLICK)
            sound.load(MediaActionSound.START_VIDEO_RECORDING)
            sound.load(MediaActionSound.STOP_VIDEO_RECORDING)
            sound.load(MediaActionSound.FOCUS_COMPLETE)
        } catch (_: Throwable) {}
        getSystemService(DisplayManager::class.java)?.registerDisplayListener(displayListener, main)

        val initialAspect = AspectRatio.byId(prefs.aspectId).takeIf { it in AspectRatio.cycle(prefs.extraAspects) } ?: AspectRatio.TRUE
        settings = CaptureSettings(aspect = initialAspect, ois = prefs.ois, saveDng = prefs.saveDng)
        updateDisplayRotation()

        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) startCatalog()
        else requestPermissions(arrayOf(Manifest.permission.CAMERA), REQ_CAMERA)
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        hideSystemBars()
        orientation.start()
        updateDisplayRotation()
        if (permissionMissing && checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            permissionMissing = false
            viewfinder.overlay.center = ""
            startCatalog()
        }
        maybeOpen()
    }

    override fun onPause() {
        resumed = false
        if (recording) controller.stopRecording()
        if (bursting) { controller.stopBurst(); bursting = false }
        keyShutterDown = false
        keyBurst = false
        controller.close()
        orientation.stop()
        super.onPause()
    }

    override fun onDestroy() {
        try { getSystemService(DisplayManager::class.java)?.unregisterDisplayListener(displayListener) } catch (_: Throwable) {}
        controller.release()
        try { sound.release() } catch (_: Throwable) {}
        super.onDestroy()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    private fun setupWindow() {
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.setDecorFitsSystemWindows(false)
        window.attributes = window.attributes.apply {
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }
        window.decorView.setBackgroundColor(palette.bg)
    }

    private fun hideSystemBars() {
        window.insetsController?.let {
            it.hide(WindowInsets.Type.systemBars())
            it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {}
        override fun onDisplayRemoved(displayId: Int) {}
        override fun onDisplayChanged(displayId: Int) {
            if (display?.displayId == displayId) updateDisplayRotation()
        }
    }

    private fun updateDisplayRotation() {
        val r = when (display?.rotation ?: Surface.ROTATION_0) {
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }
        if (r != displayRotationDeg || !this::viewfinder.isInitialized) {
            displayRotationDeg = r
            session?.let { viewfinder.configure(it.previewSize, it.sensorOrientation, r) }
        }
        if (this::orientation.isInitialized) applyUiRotation()
    }

    // ------------------------------------------------------------------ views

    private fun chip(): ChipButton = ChipButton(this, ui, palette)

    private fun buildViews() {
        root = CameraRootLayout(this, ui)
        root.setBackgroundColor(palette.bg)

        viewfinder = ViewfinderView(this, ui, palette)
        viewfinder.texture.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: SurfaceTexture, width: Int, height: Int) {
                textureReady = true
                controller.setTexture(st)
                maybeOpen()
            }
            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, width: Int, height: Int) {}
            override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                textureReady = false
                controller.setTexture(null)
                return true
            }
            override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
        }
        viewfinder.onTap = { nx, ny, vx, vy -> onViewfinderTap(nx, ny, vx, vy) }

        topBar = ChipBar(this, ui)
        menuChip = chip().apply { icon = PixelIcons.MENU; label = "MENU"; onTap = { openMenu() } }
        cameraChip = chip().apply { icon = PixelIcons.CAMERA; label = "CAM"; onTap = { openPicker() } }
        pixelChip = chip().apply { icon = PixelIcons.PIXEL_BIN; label = "--"; onTap = { togglePixelMode() } }
        flashChip = chip().apply { icon = PixelIcons.FLASH_OFF; label = "OFF"; onTap = { cycleFlash() } }
        testChip = chip().apply { icon = PixelIcons.TEST; label = "OFF"; onTap = { cycleTestPattern() } }
        for (c in listOf(menuChip, cameraChip, pixelChip, flashChip, testChip)) topBar.addView(c)

        paramBar = ParamBar(this)
        paramBar.scrimColor = Palette.withAlpha(palette.deep, 0.7f)
        isoCell = ParamCell(this, ui, palette, KEY_ISO, "ISO")
        shutterCell = ParamCell(this, ui, palette, KEY_SHUTTER, "SHUTTER")
        apertureCell = ParamCell(this, ui, palette, KEY_APERTURE, "APERTURE")
        ndCell = ParamCell(this, ui, palette, KEY_ND, "ND")
        for (c in listOf(isoCell, shutterCell, apertureCell, ndCell)) {
            c.onTap = { k -> toggleDial(k) }
            c.onDoubleTap = { k -> resetParam(k) }
            paramBar.addView(c)
        }

        dial = DialStrip(this, ui, palette)
        dial.visibility = View.INVISIBLE
        dial.onScrub = { pos, settled -> onDialScrub(pos, settled) }

        deck = ShutterDeck(this, ui)
        arcs = ArcControls(this, ui, palette)
        shutter = ShutterButton(this, ui, palette)
        modeChip = chip().apply { icon = PixelIcons.PHOTO; label = "PHOTO"; onTap = { toggleMode() } }
        afChip = chip().apply { icon = PixelIcons.AF; label = "AF"; onTap = { toggleAf() } }
        aspectChip = chip().apply { icon = PixelIcons.ASPECT; label = settings.aspect.label; onTap = { cycleAspect() } }
        thumb = ThumbView(this, ui, palette).apply { onTap = { openLast() } }
        deck.arcs = arcs; deck.shutter = shutter; deck.modeChip = modeChip; deck.afChip = afChip; deck.aspectChip = aspectChip; deck.thumb = thumb
        for (v in listOf<View>(arcs, modeChip, afChip, aspectChip, thumb, shutter)) deck.addView(v)
        deck.setArcs(prefs.focusSlider, prefs.zoomSlider, animate = false)
        shutter.onShoot = { shoot() }
        shutter.onBurstStart = { startBurst() }
        shutter.onBurstEnd = { stopBurst() }
        arcs.onChange = { arc, t, final -> onArc(arc, t, final) }

        toaster = Toaster(this, ui, palette)
        menuSheet = Sheet(this, ui, palette, "MENU", PixelIcons.MENU)
        reportSheet = Sheet(this, ui, palette, "HARDWARE REPORT", PixelIcons.CAMERA)
        pickerSheet = Sheet(this, ui, palette, "SENSOR", PixelIcons.CAMERA)
        aboutSheet = Sheet(this, ui, palette, AppInfo.NAME.uppercase(), PixelIcons.PHOTO)
        for (s in listOf(menuSheet, reportSheet, pickerSheet, aboutSheet)) s.onDismiss = { main.post { updateBack() } }

        root.viewfinder = viewfinder
        root.topBar = topBar
        root.paramBar = paramBar
        root.dial = dial
        root.deck = deck
        root.toaster = toaster
        root.sheets.addAll(listOf(pickerSheet, menuSheet, aboutSheet, reportSheet))
        root.addView(viewfinder)
        root.addView(paramBar)
        root.addView(dial)
        root.addView(topBar)
        root.addView(deck)
        root.addView(toaster)
        for (s in root.sheets) root.addView(s)
        root.onOrientationLayout = { applyUiRotation() }

        root.setOnApplyWindowInsetsListener { _, insets ->
            val cut = insets.getInsets(WindowInsets.Type.displayCutout())
            val bars = insets.getInsetsIgnoringVisibility(WindowInsets.Type.systemBars())
            root.insetLeft = max(cut.left, bars.left)
            root.insetTop = max(cut.top, ui.dp(6))
            root.insetRight = max(cut.right, bars.right)
            root.insetBottom = max(cut.bottom, bars.bottom)
            root.requestLayout()
            insets
        }
        setContentView(root)
        bindCaps(animate = false)
    }

    private fun applyPalette(p: Palette) {
        palette = p
        prefs.palette = p.name
        window.decorView.setBackgroundColor(p.bg)
        root.setBackgroundColor(p.bg)
        viewfinder.setPalette(p)
        for (c in listOf(menuChip, cameraChip, pixelChip, flashChip, testChip, modeChip, afChip, aspectChip)) { c.palette = p; c.invalidate() }
        for (c in listOf(isoCell, shutterCell, apertureCell, ndCell)) { c.palette = p; c.invalidate() }
        paramBar.scrimColor = Palette.withAlpha(p.deep, 0.7f)
        dial.palette = p; dial.invalidate()
        shutter.palette = p; shutter.invalidate()
        arcs.palette = p; arcs.invalidate()
        thumb.palette = p; thumb.invalidate()
        toaster.palette = p; toaster.invalidate()
        for (s in root.sheets) s.setPaletteAll(p)
    }

    // ------------------------------------------------------------------ catalog / first run

    private fun startCatalog() {
        viewfinder.overlay.center = "READING SENSORS…"
        controller.handler.post {
            val cat = try { Catalog.build(cameraManager) } catch (t: Throwable) { Catalog(emptyList(), emptyList()) }
            val probe = HardwareProbe(this, controller.handler)
            val cached = probe.applyCache(cat, prefs.scanCache)
            if (cached) {
                main.post { onCatalogReady(cat) }
            } else {
                main.post { viewfinder.overlay.center = "SCANNING HARDWARE…" }
                probe.run(cat) {
                    prefs.scanCache = probe.toCache(cat)
                    onCatalogReady(cat)
                }
            }
        }
    }

    private fun onCatalogReady(cat: Catalog) {
        catalog = cat
        catalogReady = true
        viewfinder.overlay.center = ""
        if (cat.usable.isEmpty()) {
            viewfinder.overlay.center = "NO USABLE CAMERA FOUND"
            return
        }
        if (!prefs.reportAccepted) {
            reportPending = true
            showReport(mandatory = true)
        } else {
            openInitialCamera()
        }
    }

    private fun openInitialCamera() {
        val cat = catalog ?: return
        val usable = cat.usable.filter { !it.isCombo }
        val pick = cat.usable.firstOrNull { it.key == prefs.lastCamera }
            ?: usable.firstOrNull { it.primary.facing == CameraMetadata.LENS_FACING_BACK && it.primary.raw }
            ?: usable.firstOrNull { it.primary.facing == CameraMetadata.LENS_FACING_BACK }
            ?: usable.firstOrNull() ?: cat.usable.first()
        selectOption(pick)
    }

    private fun maybeOpen() {
        val opt = option ?: return
        if (resumed && catalogReady && !reportPending && textureReady && !permissionMissing) controller.open(opt, settings)
    }

    private fun selectOption(opt: CameraOption) {
        if (recording) return
        option = opt
        val c = Caps(opt)
        caps = c
        prefs.lastCamera = opt.key
        // Every sensor starts in auto with the widest aperture.
        settings = settings.copy(
            isoAuto = true, shutterAuto = true, aperture = 0f, ndAuto = true, focusDiopters = 0f,
            zoom = 1f, focalLength = c.opticalFocalLengths.firstOrNull() ?: 0f, testPattern = 0, maxRes = false,
            flash = if (c.flash) settings.flash else FlashMode.OFF,
        )
        viewfinder.zoom = 1f
        live = null
        bindCaps(animate = true)
        maybeOpen()
    }

    // ------------------------------------------------------------------ binding

    private val zoomPossible: Boolean get() {
        val c = caps ?: return false
        return if (settings.mode == CaptureMode.VIDEO) c.videoZoomMax > 1f || c.opticalFocalLengths.isNotEmpty() else true
    }

    private fun bindCaps(animate: Boolean) {
        val c = caps
        val video = settings.mode == CaptureMode.VIDEO
        Reveal.set(pixelChip, c != null && c.pixelMode && !video, animate)
        Reveal.set(flashChip, c != null && c.flash, animate)
        Reveal.set(testChip, c != null && c.testPatterns.isNotEmpty(), animate)
        Reveal.set(apertureCell, c != null && c.apertures.isNotEmpty(), animate)
        Reveal.set(ndCell, c != null && c.ndDensities.isNotEmpty(), animate)
        Reveal.set(afChip, c != null && c.autofocus, animate)
        Reveal.set(aspectChip, !video, animate)
        isoCell.readOnly = c?.isoRange == null
        shutterCell.readOnly = c?.exposureRange == null
        if (activeDial != null && (c == null || dialModel(activeDial!!) == null)) closeDial()

        val focusOn = prefs.focusSlider && c?.manualFocus == true
        val zoomOn = prefs.zoomSlider && zoomPossible
        deck.setArcs(focusOn, zoomOn, animate)
        configureArcs()
        refreshChips()
        refreshReadouts()
        refreshOverlayInfo()
    }

    private fun configureArcs() {
        val c = caps ?: return
        val f = arcs.focus
        val minD = c.minFocusDiopters
        f.enabled = c.manualFocus
        f.startLabel = if (c.focusCalibrated && minD > 0f) formatDistance(1f / minD) else "NEAR"
        f.endLabel = "∞"
        f.bubble = { t -> if (c.focusCalibrated) formatDistance(diopterFor(t)) else "F ${(t * 100).roundToInt()}" }
        val hyper = if (c.hyperfocalDiopters > 0f && minD > 0f) 1f - c.hyperfocalDiopters / minD else -1f
        f.detents = if (hyper in 0f..1f) floatArrayOf(hyper, 1f) else floatArrayOf(1f)
        arcs.setValue(f, if (settings.afOn) focusTFromLive() else 1f - (settings.focusDiopters / max(minD, 1e-3f)))

        val z = arcs.zoom
        val optical = c.opticalFocalLengths
        if (optical.isNotEmpty()) {
            z.startLabel = "${optical.first().roundToInt()}mm"
            z.endLabel = "${optical.last().roundToInt()}mm"
            z.bubble = { t -> "${focalForT(t).roundToInt()}mm" }
            z.detents = FloatArray(optical.size) { if (optical.size > 1) it / (optical.size - 1f) else 0f }
        } else {
            val zMax = zoomMax()
            z.startLabel = "1×"
            z.endLabel = "${zMax.roundToInt()}×"
            z.bubble = { t -> String.format(java.util.Locale.US, "%.1f×", zoomForT(t)) }
            z.detents = listOf(2f, 3f, 5f).filter { it < zMax }.map { (ln(it) / ln(zMax)).toFloat() }.toFloatArray()
        }
        z.enabled = zoomPossible
        arcs.setValue(z, zoomT())
        arcs.invalidate()
    }

    private fun diopterFor(t: Float): Float = (caps?.minFocusDiopters ?: 0f) * (1f - t)

    private fun formatDistance(diopters: Float): String {
        if (diopters <= 0.02f) return "∞"
        val m = 1f / diopters
        return if (m >= 10f) "${m.roundToInt()}m" else if (m >= 1f) String.format(java.util.Locale.US, "%.1fm", m) else "${(m * 100).roundToInt()}cm"
    }

    private fun focusTFromLive(): Float {
        val c = caps ?: return 1f
        val d = live?.focusDiopters ?: return arcs.focus.value
        if (d < 0f || c.minFocusDiopters <= 0f) return arcs.focus.value
        return (1f - d / c.minFocusDiopters).coerceIn(0f, 1f)
    }

    private fun zoomMax(): Float = if (settings.mode == CaptureMode.VIDEO) (caps?.videoZoomMax ?: 1f).coerceAtLeast(1.01f) else PHOTO_ZOOM_MAX

    private fun zoomForT(t: Float): Float = zoomMax().pow(t.coerceIn(0f, 1f))

    private fun focalForT(t: Float): Float {
        val list = caps?.opticalFocalLengths ?: return 0f
        if (list.isEmpty()) return 0f
        return list[(t * (list.size - 1)).roundToInt().coerceIn(0, list.size - 1)]
    }

    private fun zoomT(): Float {
        val c = caps ?: return 0f
        if (c.opticalFocalLengths.size > 1) {
            val i = c.opticalFocalLengths.indexOfFirst { abs(it - settings.focalLength) < 0.01f }.coerceAtLeast(0)
            return i / (c.opticalFocalLengths.size - 1f)
        }
        val zm = zoomMax()
        return if (zm <= 1.01f) 0f else (ln(settings.zoom.coerceAtLeast(1f)) / ln(zm)).coerceIn(0f, 1f)
    }

    private fun refreshChips() {
        val c = caps
        val opt = option
        cameraChip.label = opt?.label ?: "CAM"
        modeChip.icon = if (settings.mode == CaptureMode.VIDEO) PixelIcons.VIDEO else PixelIcons.PHOTO
        modeChip.label = if (settings.mode == CaptureMode.VIDEO) "VIDEO" else "PHOTO"
        modeChip.active = settings.mode == CaptureMode.VIDEO
        afChip.icon = if (settings.afOn) PixelIcons.AF else PixelIcons.MF
        afChip.label = if (settings.afOn) "AF" else "MF"
        afChip.active = settings.afOn
        aspectChip.label = settings.aspect.label
        aspectChip.active = settings.aspect != AspectRatio.TRUE
        when (settings.flash) {
            FlashMode.OFF -> { flashChip.icon = PixelIcons.FLASH_OFF; flashChip.label = "OFF"; flashChip.active = false }
            FlashMode.FLASH -> { flashChip.icon = PixelIcons.FLASH; flashChip.label = "FLASH"; flashChip.active = true }
            FlashMode.TORCH -> { flashChip.icon = PixelIcons.TORCH; flashChip.label = "TORCH"; flashChip.active = true }
        }
        testChip.label = testPatternName(settings.testPattern)
        testChip.active = settings.testPattern != 0
        if (c != null && c.pixelMode) {
            val p = opt!!.primary
            pixelChip.icon = if (settings.maxRes) PixelIcons.PIXEL_FULL else PixelIcons.PIXEL_BIN
            pixelChip.label = p.mpText(if (settings.maxRes) p.rawSizeMax else p.rawSize).removeSuffix("P")
            pixelChip.active = settings.maxRes
        }
        shutter.video = settings.mode == CaptureMode.VIDEO
    }

    private fun testPatternName(mode: Int): String = when (mode) {
        CameraMetadata.SENSOR_TEST_PATTERN_MODE_OFF -> "OFF"
        CameraMetadata.SENSOR_TEST_PATTERN_MODE_SOLID_COLOR -> "SOLID"
        CameraMetadata.SENSOR_TEST_PATTERN_MODE_COLOR_BARS -> "BARS"
        CameraMetadata.SENSOR_TEST_PATTERN_MODE_COLOR_BARS_FADE_TO_GRAY -> "FADE"
        CameraMetadata.SENSOR_TEST_PATTERN_MODE_PN9 -> "PN9"
        CameraMetadata.SENSOR_TEST_PATTERN_MODE_CUSTOM1 -> "CUST"
        else -> "T$mode"
    }

    private fun refreshReadouts() {
        val c = caps
        val l = live
        val mode = c?.let { settings.exposureMode(it) } ?: ExposureMode.AUTO
        val isoAuto = c?.isoRange == null || settings.isoAuto
        val shAuto = c?.exposureRange == null || settings.shutterAuto
        isoCell.setValue(if (isoAuto) (l?.iso?.takeIf { it > 0 }?.toString() ?: "--") else settings.iso.toString(), isoAuto)
        val shownNs = if (shAuto) l?.exposureNs ?: 0L else settings.exposureNs
        shutterCell.setValue(ExposureScales.formatShutter(shownNs), shAuto)
        if (c != null && c.apertures.isNotEmpty()) {
            val manual = mode == ExposureMode.MANUAL
            val f = if (manual) (if (settings.aperture > 0f) settings.aperture else c.apertures.first()) else (l?.aperture ?: c.apertures.first())
            apertureCell.setValue(ExposureScales.formatAperture(f), !manual)
        }
        if (c != null && c.ndDensities.isNotEmpty()) {
            ndCell.setValue(ExposureScales.formatNd(if (settings.ndAuto) (l?.nd ?: 0f) else settings.nd).removePrefix("ND"), settings.ndAuto)
        }
        viewfinder.overlay.modeBadge = when (mode) {
            ExposureMode.AUTO -> "P"
            ExposureMode.MANUAL -> "M"
            ExposureMode.ISO_PRIORITY -> "ISO"
            ExposureMode.SHUTTER_PRIORITY -> "S"
        }
    }

    private fun refreshOverlayInfo() {
        val opt = option
        val si = session
        if (opt == null) { viewfinder.overlay.infoLeft = ""; viewfinder.overlay.infoRight = ""; return }
        if (settings.mode == CaptureMode.VIDEO) {
            val vs = si?.videoSize
            viewfinder.overlay.infoLeft = opt.label
            viewfinder.overlay.infoRight = if (vs != null) "${if (vs.height >= 2160) "4K" else "${vs.height}p"} 30" else "VIDEO"
            return
        }
        val raw = si?.raw ?: (caps?.raw == true)
        val size = si?.stillSize
        val mp = if (size != null) {
            val v = size.area / 1_000_000f
            if (v >= 10f) "${v.roundToInt()}MP" else String.format(java.util.Locale.US, "%.1fMP", v)
        } else ""
        viewfinder.overlay.infoLeft = listOf(opt.label, mp).filter { it.isNotEmpty() }.joinToString(" · ")
        viewfinder.overlay.infoRight = (if (raw) (if (settings.saveDng) "DNG+JPG" else "JPG·RAW") else "JPG·ISP") + if (si?.maxResActive == true) " · MAX" else ""
    }

    // ------------------------------------------------------------------ orientation

    private fun onDeviceOrientation(o: Int) {
        controller.deviceOrientation = o
        applyUiRotation()
    }

    private fun applyUiRotation() {
        val o = if (this::orientation.isInitialized) orientation.orientation else 0
        var r = (-(o + displayRotationDeg)) % 360
        if (r <= -180) r += 360
        if (r > 180) r -= 360
        uiRotation = r.toFloat()
        for (c in listOf(menuChip, cameraChip, pixelChip, flashChip, testChip, modeChip, afChip, aspectChip)) c.setContentRotation(uiRotation)
        for (c in listOf(isoCell, shutterCell, apertureCell, ndCell)) c.setContentRotation(uiRotation)
        dial.setContentRotation(uiRotation)
        arcs.setContentRotation(uiRotation)
        thumb.setContentRotation(uiRotation)
        toaster.setContentRotation(uiRotation)
        viewfinder.overlay.setContentRotation(uiRotation)
    }

    // ------------------------------------------------------------------ settings push

    private fun commit(s: CaptureSettings, immediate: Boolean = true) {
        settings = s
        refreshChips()
        refreshReadouts()
        refreshOverlayInfo()
        push(immediate)
    }

    private fun push(immediate: Boolean) {
        val now = SystemClock.uptimeMillis()
        if (immediate || now - lastPush > 45) {
            lastPush = now
            controller.update(settings)
        } else if (!pushScheduled) {
            pushScheduled = true
            main.postDelayed({
                pushScheduled = false
                lastPush = SystemClock.uptimeMillis()
                controller.update(settings)
            }, 45)
        }
    }

    private fun toast(msg: String) = toaster.show(msg)

    private fun announceMode(before: ExposureMode) {
        val c = caps ?: return
        val after = settings.exposureMode(c)
        if (after != before) toast(when (after) {
            ExposureMode.AUTO -> "AUTO EXPOSURE"
            ExposureMode.MANUAL -> "MANUAL EXPOSURE"
            ExposureMode.ISO_PRIORITY -> "ISO PRIORITY · HARDWARE AE"
            ExposureMode.SHUTTER_PRIORITY -> "SHUTTER PRIORITY · HARDWARE AE"
        })
    }

    // ------------------------------------------------------------------ exposure semantics

    /** ISO set by the user. Without hardware ISO priority the shutter is frozen at its live value. */
    private fun setIso(iso: Int, immediate: Boolean) {
        val c = caps ?: return
        val before = settings.exposureMode(c)
        var s = settings.copy(isoAuto = false, iso = iso)
        if (s.shutterAuto && !c.isoPriority) s = s.copy(shutterAuto = false, exposureNs = live?.exposureNs?.takeIf { it > 0 } ?: s.exposureNs)
        commit(s, immediate)
        announceMode(before)
    }

    private fun setIsoAuto() {
        val c = caps ?: return
        val before = settings.exposureMode(c)
        var s = settings.copy(isoAuto = true)
        if (!s.shutterAuto && !c.shutterPriority) s = s.copy(shutterAuto = true)
        commit(s)
        announceMode(before)
    }

    private fun setShutter(ns: Long, immediate: Boolean) {
        val c = caps ?: return
        val before = settings.exposureMode(c)
        var s = settings.copy(shutterAuto = false, exposureNs = ns)
        if (s.isoAuto && !c.shutterPriority) s = s.copy(isoAuto = false, iso = live?.iso?.takeIf { it > 0 } ?: s.iso)
        commit(s, immediate)
        announceMode(before)
    }

    private fun setShutterAuto() {
        val c = caps ?: return
        val before = settings.exposureMode(c)
        var s = settings.copy(shutterAuto = true)
        if (!s.isoAuto && !c.isoPriority) s = s.copy(isoAuto = true)
        commit(s)
        announceMode(before)
    }

    /** The aperture only exists in manual exposure (the AE algorithm owns it otherwise). */
    private fun setAperture(f: Float, immediate: Boolean) {
        val c = caps ?: return
        val before = settings.exposureMode(c)
        var s = settings.copy(aperture = f)
        if (s.exposureMode(c) != ExposureMode.MANUAL) {
            s = s.copy(isoAuto = false, shutterAuto = false,
                iso = live?.iso?.takeIf { it > 0 } ?: s.iso, exposureNs = live?.exposureNs?.takeIf { it > 0 } ?: s.exposureNs)
        }
        commit(s, immediate)
        announceMode(before)
    }

    private fun resetParam(key: String) {
        val c = caps ?: return
        when (key) {
            KEY_ISO -> if (c.isoRange != null) setIsoAuto()
            KEY_SHUTTER -> if (c.exposureRange != null) setShutterAuto()
            KEY_APERTURE -> if (c.apertures.isNotEmpty()) setAperture(c.apertures.first(), true)
            KEY_ND -> commit(settings.copy(ndAuto = true))
        }
        if (activeDial == key) syncDialToSettings()
    }

    // ------------------------------------------------------------------ dials

    private fun isoMajor(iso: Int) = iso in intArrayOf(25, 50, 100, 200, 400, 800, 1600, 3200, 6400, 12800, 25600, 51200, 102400)

    private val shutterMajors = setOf("1/32000", "1/16000", "1/8000", "1/4000", "1/2000", "1/1000", "1/500", "1/250", "1/125", "1/60",
        "1/30", "1/15", "1/8", "1/4", "0.5\"", "1\"", "2\"", "4\"", "8\"", "15\"", "30\"", "60\"")

    private fun shutterList(): List<ExposureScales.Shutter> {
        val r = caps?.exposureRange ?: return emptyList()
        val hi = if (settings.mode == CaptureMode.VIDEO) min(r.upper, 33_333_333L) else r.upper
        return ExposureScales.shuttersIn(r.lower, hi)
    }

    /** Labels the extremes only when they do not collide with a neighbouring major label. */
    private fun withEdgeLabels(stops: List<DialStop>, minGap: Float): List<DialStop> = stops.mapIndexed { i, s ->
        if (i != 0 && i != stops.lastIndex) return@mapIndexed s
        val near = stops.filterIndexed { j, o -> j != i && o.major }.minOfOrNull { abs(it.pos - s.pos) } ?: Float.MAX_VALUE
        DialStop(s.pos, s.label, near >= minGap)
    }

    private fun dialModel(key: String): DialModel? {
        val c = caps ?: return null
        return when (key) {
            KEY_ISO -> {
                val r = c.isoRange ?: return null
                val isos = ExposureScales.isoStopsIn(r.lower, r.upper)
                if (isos.isEmpty()) return null
                val stops = isos.map { iso -> DialStop((ExposureScales.isoToStops(iso.toDouble(), r.lower) * 3).toFloat(), iso.toString(), isoMajor(iso)) }
                DialModel(KEY_ISO, withEdgeLabels(stops, 2.5f), continuous = true, autoPos = stops.first().pos - 2.4f, unitDp = 16f, snapThreshold = 0.5f)
            }
            KEY_SHUTTER -> {
                val list = shutterList()
                if (list.isEmpty()) return null
                val stops = list.mapIndexed { i, s -> DialStop(i.toFloat(), s.label, s.label in shutterMajors) }
                DialModel(KEY_SHUTTER, withEdgeLabels(stops, 2.5f), continuous = false, autoPos = -2.4f, unitDp = 22f, snapThreshold = 0.5f)
            }
            KEY_APERTURE -> {
                if (c.apertures.isEmpty()) return null
                DialModel(KEY_APERTURE, c.apertures.mapIndexed { i, f -> DialStop(i.toFloat(), ExposureScales.formatAperture(f), true) },
                    continuous = false, autoPos = null, unitDp = 64f, snapThreshold = 0.5f)
            }
            KEY_ND -> {
                if (c.ndDensities.isEmpty()) return null
                DialModel(KEY_ND, c.ndDensities.mapIndexed { i, d -> DialStop(i.toFloat(), ExposureScales.formatNd(d), true) },
                    continuous = false, autoPos = -1.4f, unitDp = 64f, snapThreshold = 0.5f)
            }
            else -> null
        }
    }

    /** Dial position that represents the current (manual or live auto) value. */
    private fun dialPosition(key: String, m: DialModel): Float {
        val c = caps ?: return 0f
        val l = live
        return when (key) {
            KEY_ISO -> {
                val r = c.isoRange ?: return 0f
                val iso = if (settings.isoAuto) (l?.iso?.takeIf { it > 0 } ?: r.lower) else settings.iso
                (ExposureScales.isoToStops(iso.toDouble().coerceIn(r.lower.toDouble(), r.upper.toDouble()), r.lower) * 3).toFloat()
            }
            KEY_SHUTTER -> {
                val ns = if (settings.shutterAuto) (l?.exposureNs?.takeIf { it > 0 } ?: 10_000_000L) else settings.exposureNs
                ExposureScales.nearestShutterIndex(shutterList(), ns).toFloat()
            }
            KEY_APERTURE -> {
                val f = if (settings.aperture > 0f) settings.aperture else c.apertures.first()
                c.apertures.indices.minByOrNull { abs(c.apertures[it] - f) }?.toFloat() ?: 0f
            }
            KEY_ND -> if (settings.ndAuto) (m.autoPos ?: 0f) else (c.ndDensities.indices.minByOrNull { abs(c.ndDensities[it] - settings.nd) }?.toFloat() ?: 0f)
            else -> 0f
        }
    }

    private fun toggleDial(key: String) {
        if (activeDial == key) { closeDial(); return }
        val m = dialModel(key) ?: return
        activeDial = key
        dial.setModel(m, dialPosition(key, m))
        for (cell in listOf(isoCell, shutterCell, apertureCell, ndCell)) cell.chosen = cell.key == key
        root.showDial(true)
        updateBack()
    }

    private fun closeDial() {
        activeDial = null
        for (cell in listOf(isoCell, shutterCell, apertureCell, ndCell)) cell.chosen = false
        root.showDial(false)
        updateBack()
    }

    /** Rebuilds the open dial after its range changed (e.g. video limits the shutter to 1/30). */
    private fun rebuildDial() {
        val key = activeDial ?: return
        val m = dialModel(key)
        if (m == null) closeDial() else dial.setModel(m, dialPosition(key, m))
    }

    private fun syncDialToSettings() {
        val key = activeDial ?: return
        val m = dial.model ?: return
        dial.moveTo(dialPosition(key, m))
    }

    private fun onDialScrub(pos: Float, settled: Boolean) {
        val key = activeDial ?: return
        val m = dial.model ?: return
        val c = caps ?: return
        val isAuto = m.autoPos != null && m.settle(pos) == m.autoPos && (settled || pos < (m.autoPos + (m.stops.firstOrNull()?.pos ?: 0f)) / 2f)
        when (key) {
            KEY_ISO -> {
                val r = c.isoRange ?: return
                if (isAuto) { if (!settings.isoAuto) setIsoAuto(); return }
                val iso = ExposureScales.snapIso(ExposureScales.stopsToIso((pos / 3f).toDouble().coerceAtLeast(0.0), r.lower), r.lower, r.upper, 1.0 / 12.0)
                if (settings.isoAuto || iso != settings.iso) setIso(iso, settled)
            }
            KEY_SHUTTER -> {
                val list = shutterList()
                if (isAuto) { if (!settings.shutterAuto) setShutterAuto(); return }
                val idx = pos.roundToInt().coerceIn(0, list.lastIndex)
                val ns = list[idx].nanos
                if (settings.shutterAuto || ns != settings.exposureNs) setShutter(ns, settled)
            }
            KEY_APERTURE -> {
                val idx = pos.roundToInt().coerceIn(0, c.apertures.lastIndex)
                val f = c.apertures[idx]
                if (f != settings.aperture || settings.exposureMode(c) != ExposureMode.MANUAL) setAperture(f, settled)
            }
            KEY_ND -> {
                if (isAuto) { if (!settings.ndAuto) commit(settings.copy(ndAuto = true)); return }
                val idx = pos.roundToInt().coerceIn(0, c.ndDensities.lastIndex)
                val d = c.ndDensities[idx]
                if (settings.ndAuto || d != settings.nd) commit(settings.copy(ndAuto = false, nd = d), settled)
            }
        }
    }

    // ------------------------------------------------------------------ chips

    private fun togglePixelMode() {
        val c = caps ?: return
        if (!c.pixelMode || recording) return
        commit(settings.copy(maxRes = !settings.maxRes))
        toast(if (settings.maxRes) "FULL RESOLUTION READOUT" else "BINNED READOUT")
    }

    private fun cycleFlash() {
        val c = caps ?: return
        if (!c.flash) return
        val next = when (settings.flash) {
            FlashMode.OFF -> if (settings.mode == CaptureMode.VIDEO) FlashMode.TORCH else FlashMode.FLASH
            FlashMode.FLASH -> FlashMode.TORCH
            FlashMode.TORCH -> FlashMode.OFF
        }
        commit(settings.copy(flash = next))
    }

    private fun cycleTestPattern() {
        val c = caps ?: return
        if (c.testPatterns.isEmpty()) return
        val list = intArrayOf(0) + c.testPatterns
        val i = list.indexOf(settings.testPattern)
        commit(settings.copy(testPattern = list[(i + 1) % list.size]))
    }

    private fun toggleAf() {
        val c = caps ?: return
        if (!c.autofocus) return
        val on = !settings.afOn
        // Switching to manual keeps the lens where AF left it.
        val d = live?.focusDiopters?.takeIf { it >= 0f } ?: settings.focusDiopters
        commit(settings.copy(afOn = on, focusDiopters = if (on) settings.focusDiopters else d))
        if (on) viewfinder.overlay.focus(FocusPhase.IDLE, 0f, 0f)
        configureArcs()
    }

    private fun cycleAspect() {
        val list = AspectRatio.cycle(prefs.extraAspects)
        val i = list.indexOf(settings.aspect)
        val next = list[(i + 1) % list.size]
        prefs.aspectId = next.id
        viewfinder.overlay.setAspect(next.ratio, animate = true)
        commit(settings.copy(aspect = next))
    }

    private fun toggleMode() {
        if (recording || bursting) return
        val video = settings.mode != CaptureMode.VIDEO
        if (video && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_AUDIO)
        }
        val opt = option
        var s = settings.copy(mode = if (video) CaptureMode.VIDEO else CaptureMode.PHOTO, maxRes = false, zoom = 1f,
            flash = if (video && settings.flash == FlashMode.FLASH) FlashMode.OFF else settings.flash)
        viewfinder.zoom = 1f
        viewfinder.overlay.setAspect(if (video) null else s.aspect.ratio, animate = true)
        if (video && opt != null && opt.isCombo) {
            // Video records one sensor: fall back to the combo's first sensor.
            val single = catalog?.usable?.firstOrNull { !it.isCombo && it.primary.sensorId == opt.primary.sensorId }
            if (single != null) {
                settings = s
                selectOption(single)
                s = settings
                toast("VIDEO USES ONE SENSOR")
            }
        }
        if (s.mode == CaptureMode.VIDEO && !s.shutterAuto && s.exposureNs > 33_333_333L) s = s.copy(exposureNs = 33_333_333L)
        commit(s)
        rebuildDial()
        bindCaps(animate = true)
    }

    // ------------------------------------------------------------------ capture

    private fun shoot() {
        if (cameraError != null) { retryCamera(); return }
        if (settings.mode == CaptureMode.VIDEO) {
            if (recording) controller.stopRecording() else controller.startRecording()
        } else {
            controller.capture()
        }
    }

    private fun startBurst() {
        if (settings.mode != CaptureMode.PHOTO || cameraError != null) return
        bursting = true
        controller.startBurst()
    }

    private fun stopBurst() {
        if (!bursting) return
        bursting = false
        controller.stopBurst()
    }

    private fun openLast() {
        val video = lastVideoUri
        val photo = lastPhotoUri
        val (uri, type) = when {
            photo != null -> photo to "image/jpeg"
            video != null -> video to "video/mp4"
            else -> return
        }
        try {
            startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, type).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        } catch (e: ActivityNotFoundException) {
            toast("NO VIEWER APP")
        } catch (t: Throwable) {
            toast("CANNOT OPEN")
        }
    }

    private fun onViewfinderTap(nx: Float, ny: Float, vx: Float, vy: Float) {
        if (permissionMissing) { requestCameraAgain(); return }
        if (cameraError != null) { retryCamera(); return }
        if (activeDial != null) { closeDial(); return }
        val c = caps ?: return
        if (!c.autofocus) return
        if (!settings.afOn) { toast("MANUAL FOCUS · TAP AF TO ENABLE"); return }
        lastTapX = vx
        lastTapY = vy
        controller.tapFocus(nx, ny)
    }

    private fun retryCamera() {
        cameraError = null
        viewfinder.overlay.center = ""
        val opt = option ?: return
        controller.close()
        controller.open(opt, settings)
    }

    private fun onArc(arc: ArcControls.Arc, t: Float, final: Boolean) {
        val c = caps ?: return
        if (arc === arcs.focus) {
            if (!c.manualFocus) return
            var s = settings.copy(focusDiopters = diopterFor(t))
            if (s.afOn) {
                s = s.copy(afOn = false)
                toast("MANUAL FOCUS")
                viewfinder.overlay.focus(FocusPhase.IDLE, 0f, 0f)
            }
            commit(s, final)
        } else {
            if (c.opticalFocalLengths.size > 1) {
                val f = focalForT(t)
                if (f != settings.focalLength || final) commit(settings.copy(focalLength = f), final)
                // Snap the knob onto the chosen focal length once released.
                if (final) arcs.setValue(arcs.zoom, zoomT())
            } else {
                val z = zoomForT(t)
                if (settings.mode == CaptureMode.PHOTO) viewfinder.zoom = z
                commit(settings.copy(zoom = z), final)
            }
        }
    }

    // ------------------------------------------------------------------ sheets

    private fun anySheet(): Sheet? = root.sheets.lastOrNull { it.isShowing }

    private fun updateBack() {
        if (Build.VERSION.SDK_INT < 33) return
        val sheet = anySheet()
        val needed = (sheet != null && sheet.dismissible) || activeDial != null
        val dispatcher = onBackInvokedDispatcher
        if (needed && backCallback == null) {
            val cb = OnBackInvokedCallback { handleBack() }
            dispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, cb)
            backCallback = cb
        } else if (!needed && backCallback != null) {
            dispatcher.unregisterOnBackInvokedCallback(backCallback as OnBackInvokedCallback)
            backCallback = null
        }
    }

    private fun handleBack(): Boolean {
        val sheet = anySheet()
        if (sheet != null) {
            if (sheet.dismissible) sheet.dismiss()
            updateBack()
            return sheet.dismissible
        }
        if (activeDial != null) { closeDial(); return true }
        return false
    }

    @Deprecated("Pre-Android 13 back handling")
    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (!handleBack()) super.onBackPressed()
    }

    private fun showSheet(s: Sheet) {
        for (o in root.sheets) if (o !== s && o.isShowing && o.dismissible) o.dismiss()
        s.show()
        updateBack()
    }

    private fun row(title: String, value: String = "", style: MenuRow.Style = MenuRow.Style.TEXT, note: String = "", on: Boolean = false, onTap: (() -> Unit)? = null): MenuRow =
        MenuRow(this, ui, palette).apply {
            this.title = title; this.value = value; this.style = style; this.note = note; this.on = on; this.onTap = onTap
            refresh()
        }

    private fun header(title: String) = row(title, style = MenuRow.Style.HEADER)

    private fun openMenu() {
        val list = menuSheet.list
        list.removeAllViews()
        val c = caps
        list.addView(header("SLIDERS"))
        list.addView(toggleRow("ZOOM SLIDER", prefs.zoomSlider, "Photo zoom crops the sensor frame (preview, JPEG and DNG crop).") { on ->
            prefs.zoomSlider = on
            if (!on) {
                viewfinder.zoom = 1f
                commit(settings.copy(zoom = 1f, focalLength = caps?.opticalFocalLengths?.firstOrNull() ?: 0f))
            }
            bindCaps(animate = true)
        })
        list.addView(toggleRow("FOCUS SLIDER", prefs.focusSlider, if (c?.manualFocus == false) "This sensor has no adjustable focus." else "") { on ->
            prefs.focusSlider = on
            bindCaps(animate = true)
        })
        list.addView(header("CAPTURE"))
        list.addView(toggleRow("OPTICAL STAB.", prefs.ois,
            if (c?.ois == true) "Lens/sensor-shift stabilization. Bursts always use it." else "This sensor has no optical stabilization.",
            enabled = c?.ois == true) { on ->
            prefs.ois = on
            commit(settings.copy(ois = on))
        })
        list.addView(toggleRow("SAVE DNG", prefs.saveDng, "The JPEG developed from RAW is always saved to the gallery.") { on ->
            prefs.saveDng = on
            commit(settings.copy(saveDng = on))
            refreshOverlayInfo()
        })
        list.addView(row("DNG FOLDER", saver.treeLabel(prefs.dngTreeUri?.takeIf { saver.hasTreePermission(it) }), MenuRow.Style.ACTION,
            note = if (prefs.dngTreeUri == null) "Automatic folder, created on the first DNG." else "") { pickDngFolder() })
        if (prefs.dngTreeUri != null) {
            list.addView(row("USE AUTOMATIC FOLDER", saver.defaultDngFolderLabel(), MenuRow.Style.ACTION) {
                prefs.dngTreeUri = null
                controller.dngTreeUri = null
                openMenu()
            })
        }
        list.addView(header("EXTRA CROPS"))
        list.addView(ChoiceRow(this, ui, palette).apply {
            choices = AspectRatio.entries.filter { it.extra }.map { ChoiceRow.Choice(it.id, it.label) }
            selected = prefs.extraAspects
            onToggle = { id ->
                val set = prefs.extraAspects.toMutableSet()
                if (!set.add(id)) set.remove(id)
                prefs.extraAspects = set
                selected = set
                invalidate()
                if (settings.aspect.extra && settings.aspect.id !in set) {
                    prefs.aspectId = AspectRatio.TRUE.id
                    viewfinder.overlay.setAspect(null, animate = true)
                    commit(settings.copy(aspect = AspectRatio.TRUE))
                }
            }
        })
        list.addView(header("PALETTE"))
        list.addView(ChoiceRow(this, ui, palette).apply {
            choices = Palettes.ALL.map { ChoiceRow.Choice(it.name, it.name.uppercase(), it.swatches) }
            selected = setOf(palette.name)
            onToggle = { name ->
                applyPalette(Palettes.byName(name))
                selected = setOf(name)
                invalidate()
            }
        })
        list.addView(header("INFO"))
        list.addView(row("HARDWARE REPORT", "", MenuRow.Style.ACTION) { showReport(mandatory = false) })
        list.addView(row("DESCRIPTION", "", MenuRow.Style.ACTION) { showAbout() })
        list.addView(row("VERSION", appVersion()))
        showSheet(menuSheet)
    }

    private fun toggleRow(title: String, value: Boolean, note: String, enabled: Boolean = true, onChange: (Boolean) -> Unit): MenuRow {
        val r = row(title, style = MenuRow.Style.TOGGLE, note = note, on = value)
        r.isEnabled = enabled
        r.onTap = {
            r.on = !r.on
            r.refresh()
            onChange(r.on)
        }
        return r
    }

    private fun appVersion(): String = try {
        packageManager.getPackageInfo(packageName, 0).versionName ?: "1.0"
    } catch (t: Throwable) { "1.0" }

    private fun openPicker() {
        if (recording) return
        val cat = catalog ?: return
        val list = pickerSheet.list
        list.removeAllViews()
        val video = settings.mode == CaptureMode.VIDEO
        val opts = cat.usable.filter { !(video && it.isCombo) }
        var lastGroup = ""
        for (o in opts) {
            val group = if (o.isCombo) "COMBO STREAMS" else o.primary.facingName
            if (group != lastGroup) { list.addView(header(group)); lastGroup = group }
            val r = row(o.label, o.detail, MenuRow.Style.TEXT) {
                pickerSheet.dismiss()
                if (option?.key != o.key) selectOption(o)
            }
            if (option?.key == o.key) { r.icon = PixelIcons.CHECK; r.iconColor = palette.accent.takeIf { Palette.contrast(it, palette.surface) > 2.2 } ?: palette.text }
            list.addView(r)
        }
        showSheet(pickerSheet)
    }

    private fun showReport(mandatory: Boolean) {
        val cat = catalog ?: return
        HardwareReport.fill(reportSheet, this, ui, palette, cat)
        reportSheet.dismissible = !mandatory
        reportSheet.footer = WideButton(this, ui, palette).apply {
            label = "I UNDERSTAND"
            onTap = {
                prefs.reportAccepted = true
                reportSheet.dismissible = true
                reportSheet.dismiss()
                if (reportPending) {
                    reportPending = false
                    openInitialCamera()
                }
            }
        }
        showSheet(reportSheet)
    }

    private fun showAbout() {
        val list = aboutSheet.list
        list.removeAllViews()
        list.addView(TextBlock(this, ui, palette).apply { text = AppInfo.TAGLINE; size = 14f })
        list.addView(header("CREDITS"))
        for (c in AppInfo.CREDITS) {
            list.addView(row(c.name, c.role, MenuRow.Style.TEXT, note = c.link ?: "") {
                c.link?.let { openUrl("https://$it") }
            })
        }
        list.addView(header("CONTACT"))
        list.addView(row("WEBSITE", AppInfo.WEBSITE, MenuRow.Style.ACTION) { openUrl(AppInfo.WEBSITE_URL) })
        list.addView(row("MAIL", AppInfo.MAIL, MenuRow.Style.ACTION) { openUrl("mailto:${AppInfo.MAIL}") })
        list.addView(header("ABOUT"))
        list.addView(row("VERSION", appVersion()))
        list.addView(row("DEVELOPER", AppInfo.DEVELOPER))
        showSheet(aboutSheet)
    }

    private fun openUrl(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (t: Throwable) {
            toast(url.removePrefix("mailto:").removePrefix("https://"))
        }
    }

    private fun pickDngFolder() {
        try {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION), REQ_TREE)
        } catch (t: Throwable) {
            toast("NO FOLDER PICKER")
        }
    }

    @Deprecated("Platform Activity result API")
    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_TREE || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            prefs.dngTreeUri = uri.toString()
            controller.dngTreeUri = uri.toString()
            toast("DNG → " + saver.treeLabel(uri.toString()))
            if (menuSheet.isShowing) openMenu()
        } catch (t: Throwable) {
            toast("FOLDER NOT WRITABLE")
        }
    }

    // ------------------------------------------------------------------ permissions

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_CAMERA) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                permissionMissing = false
                startCatalog()
            } else {
                permissionMissing = true
                viewfinder.overlay.center = "CAMERA PERMISSION NEEDED\nTAP TO GRANT"
            }
        }
    }

    private fun requestCameraAgain() {
        if (shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), REQ_CAMERA)
        } else {
            try {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
            } catch (t: Throwable) {
                requestPermissions(arrayOf(Manifest.permission.CAMERA), REQ_CAMERA)
            }
        }
    }

    // ------------------------------------------------------------------ hardware keys

    private fun isShutterKey(code: Int) = code == KeyEvent.KEYCODE_VOLUME_DOWN || code == KeyEvent.KEYCODE_VOLUME_UP ||
        code == KeyEvent.KEYCODE_CAMERA || code == KeyEvent.KEYCODE_FOCUS

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (!isShutterKey(keyCode) || anySheet() != null || option == null) return super.onKeyDown(keyCode, event)
        if (keyCode == KeyEvent.KEYCODE_FOCUS) return true
        if (event.repeatCount == 0) {
            keyShutterDown = true
            keyBurst = false
        } else if (event.repeatCount >= 2 && settings.mode == CaptureMode.PHOTO && !keyBurst && keyShutterDown) {
            keyBurst = true
            startBurst()
            shutter.keyDown(true)
        }
        return true
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (!isShutterKey(keyCode) || anySheet() != null || option == null) return super.onKeyUp(keyCode, event)
        if (keyCode == KeyEvent.KEYCODE_FOCUS) return true
        if (!keyShutterDown) return true
        keyShutterDown = false
        if (keyBurst) {
            keyBurst = false
            shutter.keyUp()
            stopBurst()
        } else {
            shoot()
        }
        return true
    }

    // ------------------------------------------------------------------ CameraController.Listener

    override fun onSessionReady(info: SessionInfo) {
        session = info
        cameraError = null
        viewfinder.overlay.center = ""
        viewfinder.configure(info.previewSize, info.sensorOrientation, displayRotationDeg)
        viewfinder.overlay.setAspect(if (info.mode == CaptureMode.VIDEO) null else settings.aspect.ratio, animate = false)
        if (settings.maxRes && !info.maxResActive && info.mode == CaptureMode.PHOTO) {
            settings = settings.copy(maxRes = false)
            refreshChips()
        }
        refreshOverlayInfo()
    }

    override fun onCameraError(message: String, recoverable: Boolean) {
        cameraError = message
        session = null
        viewfinder.overlay.center = if (recoverable) "$message\nTAP TO RETRY" else message
        shutter.busy = false
        shutter.recording = false
        recording = false
        viewfinder.overlay.recordingSince = 0L
        if (bursting) { bursting = false; shutter.burstCount = 0 }
    }

    override fun onLive(state: LiveState) {
        live = state
        refreshReadouts()
        val key = activeDial
        if (key != null && !dial.isDragging) {
            val trackAuto = (key == KEY_ISO && settings.isoAuto) || (key == KEY_SHUTTER && settings.shutterAuto)
            if (trackAuto) dial.model?.let { m -> dial.moveTo(dialPosition(key, m)) }
        }
        if (settings.afOn && caps?.manualFocus == true && state.focusDiopters >= 0f) arcs.setValue(arcs.focus, focusTFromLive())
    }

    override fun onFocus(state: FocusUi) {
        val r = viewfinder.contentRect
        val (x, y) = if (state.fromTap && lastTapX >= 0f) lastTapX to lastTapY else r.centerX() to r.centerY()
        viewfinder.overlay.focus(state.phase, x, y)
        shutter.busy = state.phase == FocusPhase.SCANNING
        if (state.phase == FocusPhase.LOCKED) try { sound.play(MediaActionSound.FOCUS_COMPLETE) } catch (_: Throwable) {}
    }

    override fun onShutter(burstIndex: Int) {
        try { sound.play(MediaActionSound.SHUTTER_CLICK) } catch (_: Throwable) {}
        viewfinder.overlay.blink()
    }

    override fun onSaved(shot: SavedShot) {
        shot.jpegUri?.let { lastPhotoUri = it; lastVideoUri = null }
        shot.thumbnail?.let { thumb.show(it) }
    }

    override fun onVideoSaved(uri: Uri, thumbnail: Bitmap?) {
        lastVideoUri = uri
        lastPhotoUri = null
        thumbnail?.let { thumb.show(it) }
    }

    override fun onSaveError(message: String) = toast(message)

    override fun onNotice(message: String) = toast(message)

    override fun onBurst(active: Boolean, count: Int) {
        shutter.burstCount = count
        if (!active) {
            bursting = false
            if (count > 0) toast("BURST · $count FRAMES")
        }
    }

    override fun onRecording(active: Boolean, startedAtElapsed: Long) {
        val was = recording
        recording = active
        shutter.recording = active
        viewfinder.overlay.recordingSince = if (active) startedAtElapsed else 0L
        modeChip.dimmed = active
        cameraChip.dimmed = active
        if (active != was) try {
            sound.play(if (active) MediaActionSound.START_VIDEO_RECORDING else MediaActionSound.STOP_VIDEO_RECORDING)
        } catch (_: Throwable) {}
    }

    override fun onBusy(busy: Boolean) {
        shutter.busy = busy
    }

    companion object {
        private const val REQ_CAMERA = 1
        private const val REQ_AUDIO = 2
        private const val REQ_TREE = 3
        private const val KEY_ISO = "iso"
        private const val KEY_SHUTTER = "shutter"
        private const val KEY_APERTURE = "aperture"
        private const val KEY_ND = "nd"
        private const val PHOTO_ZOOM_MAX = 4f
    }
}
