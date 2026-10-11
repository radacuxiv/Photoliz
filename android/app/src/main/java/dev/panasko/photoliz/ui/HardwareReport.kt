package dev.panasko.photoliz.ui

import android.content.Context
import android.hardware.camera2.CameraMetadata
import android.os.Build
import dev.panasko.photoliz.camera.Catalog
import dev.panasko.photoliz.camera.ExposureScales
import dev.panasko.photoliz.camera.ScanStatus
import dev.panasko.photoliz.camera.SensorInfo
import kotlin.math.roundToInt

/** The first-run disclaimer: which hardware controls exist on this phone and which do not. */
object HardwareReport {

    private enum class Mark { YES, NO, MAYBE }

    fun fill(sheet: Sheet, context: Context, ui: Ui, p: Palette, catalog: Catalog) {
        val list = sheet.list
        list.removeAllViews()

        fun header(t: String) = list.addView(MenuRow(context, ui, p).apply { style = MenuRow.Style.HEADER; title = t })
        fun row(t: String, v: String, m: Mark, note: String = "") = list.addView(MenuRow(context, ui, p).apply {
            style = MenuRow.Style.TEXT
            title = t
            value = v
            this.note = note
            icon = when (m) { Mark.YES -> PixelIcons.CHECK; Mark.NO -> PixelIcons.CROSS; Mark.MAYBE -> PixelIcons.PARTIAL }
            iconColor = when (m) { Mark.YES -> if (Palette.contrast(p.accent, p.surface) > 2.2) p.accent else p.text; Mark.NO -> p.alert; Mark.MAYBE -> p.textDim }
        })

        list.addView(TextBlock(context, ui, p).apply {
            text = "Photoliz only uses controls that physically exist in your camera hardware and are exposed by " +
                "the camera API. Everything marked ✕ below is not available on this device and is hidden from " +
                "the interface. JPEGs are developed from the RAW sensor data with as-shot white balance and the " +
                "sensor's own color calibration: no tone curve, no sharpening, no noise reduction, no AI. " +
                "Photo zoom is an honest crop of the sensor frame."
        })

        header("DEVICE")
        row("MODEL", "${Build.MANUFACTURER} ${Build.MODEL}".trim(), Mark.YES)
        row("ANDROID", "${Build.VERSION.RELEASE} · API ${Build.VERSION.SDK_INT}", Mark.YES)
        row("SENSORS", "${catalog.options.count { !it.isCombo }}", if (catalog.options.isEmpty()) Mark.NO else Mark.YES)

        for (opt in catalog.options.filter { !it.isCombo }) {
            val s = opt.primary
            header("${s.facingName} · ${s.label.uppercase()} · ID ${s.sensorId}")
            when (opt.status) {
                ScanStatus.OK -> row("ACCESS", if (s.physicalId == null) "direct" else "physical via ${s.openId}", Mark.YES)
                ScanStatus.UNTESTED -> row("ACCESS", "physical via ${s.openId}", Mark.MAYBE, "Could not be verified in advance; Photoliz will try it when selected.")
                ScanStatus.UNSUPPORTED -> { row("ACCESS", "not streamable", Mark.NO, "The camera HAL rejects streams from this physical sensor. Hidden."); continue }
            }
            sensorRows(s, ::row)
        }

        val combos = catalog.options.filter { it.isCombo }
        header("COMBO STREAMS")
        if (combos.isEmpty()) {
            row("SIMULTANEOUS SENSORS", "none exposed", Mark.NO, "This device does not expose physical sensors that can stream together.")
        } else {
            for (c in combos) {
                val label = c.sensors.joinToString(" + ") { it.label }
                when (c.status) {
                    ScanStatus.OK -> row(c.label, if (c.comboRaw.all { it }) "RAW × ${c.sensors.size}" else "ISP JPEG", Mark.YES, label)
                    ScanStatus.UNTESTED -> row(c.label, "untested", Mark.MAYBE, label)
                    ScanStatus.UNSUPPORTED -> row(c.label, "unsupported", Mark.NO, label)
                }
            }
        }

        header("ALWAYS AVAILABLE")
        row("ASPECT CROPS", "TRUE · 16:9 · 4:3 +", Mark.YES, "Extra crops (2.39:1, 1.85:1, 2:3, 1.19:1, 1:1) can be added in MENU. Crops apply to the preview, the JPEG and the DNG default crop.")
        row("FILES", "JPG always · DNG opt.", Mark.YES, "JPEGs go to Pictures/Photoliz. DNG saving is off by default — enable it in MENU.")
    }

    private fun sensorRows(s: SensorInfo, row: (String, String, Mark, String) -> Unit) {
        fun yes(b: Boolean) = if (b) Mark.YES else Mark.NO
        row("RAW / DNG", if (s.raw) s.rawSize!!.let { "${it.width}×${it.height}" } else "ISP JPEG only", yes(s.raw),
            if (s.raw) "" else "No RAW stream: the processed JPEG is used with noise reduction and sharpening switched off where possible.")
        val iso = s.isoRange
        row("ISO", if (iso != null) "${iso.lower}–${iso.upper}" else "auto only", yes(iso != null), "")
        val ex = s.exposureRange
        row("SHUTTER", if (ex != null) "${ExposureScales.formatShutter(ex.lower)}–${ExposureScales.formatShutter(ex.upper)}" else "auto only", yes(ex != null), "")
        row("APERTURE", if (s.apertureAdjustable) s.apertures.joinToString(" ") { ExposureScales.formatAperture(it) }
            else "fixed " + ExposureScales.formatAperture(s.apertures.firstOrNull() ?: 0f), yes(s.apertureAdjustable), "")
        row("ND FILTER", if (s.ndAdjustable) s.ndDensities.joinToString(" ") { ExposureScales.formatNd(it) } else "none", yes(s.ndAdjustable), "")
        row("OPTICAL STAB.", if (s.ois) "yes" else "no", yes(s.ois), "")
        row("FLASH / TORCH", if (s.flash) "yes" else "no", yes(s.flash), "")
        row("AUTOFOCUS", when { s.autofocus -> if (s.focusCalibrated) "calibrated" else "uncalibrated"; s.minFocusDiopters <= 0f -> "fixed focus"; else -> "no" }, yes(s.autofocus), "")
        row("MANUAL FOCUS", if (s.manualFocus) "yes" else "no", yes(s.manualFocus), "")
        row("OPTICAL ZOOM", if (s.opticalZoom) s.focalLengths.joinToString(" ") { "${(it * 10).roundToInt() / 10f}mm" } else "crop only", yes(s.opticalZoom), "")
        row("TEST PATTERNS", if (s.testPatternAvailable) "${s.testPatterns.size}" else "none", yes(s.testPatternAvailable), "")
        row("PIXEL MODE", if (s.pixelModeAvailable) s.rawSizeMax!!.let { "${it.width}×${it.height}" } else if (Build.VERSION.SDK_INT < 31) "Android 12+" else "binned only",
            yes(s.pixelModeAvailable), if (s.pixelModeAvailable && s.remosaic) "Full-resolution RAW is requested together with a companion stream so it arrives as a regular Bayer mosaic." else "")
        row("AE PRIORITY", if (s.isoPriority || s.shutterPriority) listOfNotNull(if (s.isoPriority) "ISO" else null, if (s.shutterPriority) "shutter" else null).joinToString(" + ")
            else if (Build.VERSION.SDK_INT < 36) "Android 16+" else "no", yes(s.isoPriority || s.shutterPriority),
            "Without hardware priority modes, setting ISO or shutter makes both manual.")
        row("BLACK LEVEL LOCK", if (s.blackLevelLock) "yes (burst)" else "no", yes(s.blackLevelLock), "")
        if (s.facing == CameraMetadata.LENS_FACING_EXTERNAL) row("EXTERNAL", "USB camera", Mark.MAYBE, "")
    }
}
