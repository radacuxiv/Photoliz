package dev.panasko.photoliz.camera

import android.graphics.ImageFormat
import android.graphics.Rect
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraCharacteristics.Key
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.StreamConfigurationMap
import android.os.Build
import android.util.Log
import android.util.Range
import android.util.Size
import android.util.SizeF
import kotlin.math.roundToInt
import kotlin.math.sqrt

private const val TAG = "PhotolizCatalog"

private const val CAP_ULTRA_HIGH_RESOLUTION_SENSOR = 16
private const val CAP_REMOSAIC_REPROCESSING = 17
private const val AE_PRIORITY_SENSITIVITY = 1
private const val AE_PRIORITY_EXPOSURE_TIME = 2

internal fun <T> CameraCharacteristics.safe(key: Key<T>): T? =
    try { get(key) } catch (t: Throwable) { null }

internal val Size.area: Long get() = width.toLong() * height.toLong()

/**
 * One physical image sensor. [openId] is the camera id that has to be opened; when the sensor is only
 * reachable through a logical multi-camera, [physicalId] names it and every stream is routed to it with
 * OutputConfiguration.setPhysicalCameraId — the logical (fused / auto-switching) output is never used.
 */
class SensorInfo(
    val openId: String,
    val physicalId: String?,
    val chars: CameraCharacteristics,
    /** Characteristics of the logical container, when accessed through one. */
    val containerChars: CameraCharacteristics?,
) {
    val key: String = if (physicalId == null) openId else "$openId/$physicalId"
    val sensorId: String = physicalId ?: openId

    val caps: IntArray = chars.safe(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: IntArray(0)
    fun hasCap(c: Int) = caps.contains(c)

    val facing: Int = chars.safe(CameraCharacteristics.LENS_FACING) ?: CameraMetadata.LENS_FACING_EXTERNAL
    val manualSensor: Boolean = hasCap(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR)
    val map: StreamConfigurationMap? = chars.safe(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)

    /** Keys accepted in requests sent to the opened device (the container when accessed through one). */
    val requestKeys: Set<CaptureRequest.Key<*>> = try {
        ((containerChars ?: chars).availableCaptureRequestKeys ?: emptyList()).toSet()
    } catch (t: Throwable) { emptySet() }

    /** Keys that may be overridden per physical sensor (only meaningful when [physicalId] != null). */
    val physicalRequestKeys: Set<CaptureRequest.Key<*>> = try {
        if (containerChars != null) (containerChars.availablePhysicalCameraRequestKeys ?: emptyList()).toSet() else emptySet()
    } catch (t: Throwable) { emptySet() }

    fun accepts(key: CaptureRequest.Key<*>): Boolean = key in requestKeys || key in physicalRequestKeys

    val rawSizes: Array<Size> =
        if (hasCap(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_RAW)) (try { map?.getOutputSizes(ImageFormat.RAW_SENSOR) } catch (t: Throwable) { null }) ?: emptyArray()
        else emptyArray()
    val rawSize: Size? = rawSizes.maxByOrNull { it.area }
    val raw: Boolean = rawSize != null
    val jpegSize: Size? = (try { map?.getOutputSizes(ImageFormat.JPEG) } catch (t: Throwable) { null })?.maxByOrNull { it.area }

    val isoRange: Range<Int>? =
        if (manualSensor && accepts(CaptureRequest.SENSOR_SENSITIVITY)) chars.safe(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE) else null
    val exposureRange: Range<Long>? =
        if (manualSensor && accepts(CaptureRequest.SENSOR_EXPOSURE_TIME)) chars.safe(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE) else null
    val maxFrameDuration: Long = chars.safe(CameraCharacteristics.SENSOR_INFO_MAX_FRAME_DURATION) ?: 0L

    val apertures: FloatArray = (chars.safe(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES) ?: FloatArray(0)).sortedArray()
    val apertureAdjustable: Boolean = manualSensor && apertures.size > 1 && accepts(CaptureRequest.LENS_APERTURE)

    val ndDensities: FloatArray = (chars.safe(CameraCharacteristics.LENS_INFO_AVAILABLE_FILTER_DENSITIES) ?: FloatArray(0)).sortedArray()
    val ndAdjustable: Boolean = manualSensor && ndDensities.size > 1 && accepts(CaptureRequest.LENS_FILTER_DENSITY)

    val focalLengths: FloatArray = (chars.safe(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS) ?: FloatArray(0)).sortedArray()
    val opticalZoom: Boolean = manualSensor && focalLengths.size > 1 && accepts(CaptureRequest.LENS_FOCAL_LENGTH)

    val ois: Boolean = (chars.safe(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION) ?: IntArray(0))
        .contains(CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON) && accepts(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE)

    val flash: Boolean = chars.safe(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true

    val testPatterns: IntArray = (chars.safe(CameraCharacteristics.SENSOR_AVAILABLE_TEST_PATTERN_MODES) ?: IntArray(0))
        .filter { it != CameraMetadata.SENSOR_TEST_PATTERN_MODE_OFF }.toIntArray()
    val testPatternAvailable: Boolean = testPatterns.isNotEmpty() && accepts(CaptureRequest.SENSOR_TEST_PATTERN_MODE)

    val minFocusDiopters: Float = chars.safe(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f
    val hyperfocalDiopters: Float = chars.safe(CameraCharacteristics.LENS_INFO_HYPERFOCAL_DISTANCE) ?: 0f
    val focusCalibrated: Boolean = (chars.safe(CameraCharacteristics.LENS_INFO_FOCUS_DISTANCE_CALIBRATION)
        ?: CameraMetadata.LENS_INFO_FOCUS_DISTANCE_CALIBRATION_UNCALIBRATED) != CameraMetadata.LENS_INFO_FOCUS_DISTANCE_CALIBRATION_UNCALIBRATED
    val afModes: IntArray = chars.safe(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES) ?: IntArray(0)
    val autofocus: Boolean = minFocusDiopters > 0f && afModes.contains(CameraMetadata.CONTROL_AF_MODE_AUTO)
    val manualFocus: Boolean = minFocusDiopters > 0f && manualSensor && accepts(CaptureRequest.LENS_FOCUS_DISTANCE) &&
        afModes.contains(CameraMetadata.CONTROL_AF_MODE_OFF)
    val maxAfRegions: Int = chars.safe(CameraCharacteristics.CONTROL_MAX_REGIONS_AF) ?: 0
    val maxAeRegions: Int = chars.safe(CameraCharacteristics.CONTROL_MAX_REGIONS_AE) ?: 0

    val activeArray: Rect = chars.safe(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
        ?: Rect(0, 0, rawSize?.width ?: jpegSize?.width ?: 4000, rawSize?.height ?: jpegSize?.height ?: 3000)
    val preCorrectionArray: Rect = chars.safe(CameraCharacteristics.SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE) ?: activeArray
    val pixelArray: Size? = chars.safe(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)
    val physicalSize: SizeF? = chars.safe(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
    val sensorOrientation: Int = chars.safe(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
    val cfa: Int = chars.safe(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT) ?: -1

    val blackLevelLock: Boolean = accepts(CaptureRequest.BLACK_LEVEL_LOCK)
    val shadingMapOn: Boolean = (chars.safe(CameraCharacteristics.STATISTICS_INFO_AVAILABLE_LENS_SHADING_MAP_MODES) ?: IntArray(0))
        .contains(CameraMetadata.STATISTICS_LENS_SHADING_MAP_MODE_ON)
    val zoomRatioRange: Range<Float>? = chars.safe(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)

    /** Hybrid AE (Android 16): keep ISO or exposure time fixed while hardware AE drives the other. */
    val aePriorityModes: IntArray =
        if (Build.VERSION.SDK_INT >= 36) {
            try {
                val modes = chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_PRIORITY_MODES) ?: IntArray(0)
                if (accepts(CaptureRequest.CONTROL_AE_PRIORITY_MODE)) modes else IntArray(0)
            } catch (t: Throwable) { IntArray(0) }
        } else IntArray(0)
    val isoPriority: Boolean get() = aePriorityModes.contains(AE_PRIORITY_SENSITIVITY)
    val shutterPriority: Boolean get() = aePriorityModes.contains(AE_PRIORITY_EXPOSURE_TIME)

    // Sensor pixel mode (Android 12+): binned "default" vs. full "maximum resolution" readout.
    val maxResMap: StreamConfigurationMap? =
        if (Build.VERSION.SDK_INT >= 31 && hasCap(CAP_ULTRA_HIGH_RESOLUTION_SENSOR))
            try { chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP_MAXIMUM_RESOLUTION) } catch (t: Throwable) { null }
        else null
    val rawSizeMax: Size? = maxResMap?.let { m ->
        (try { m.getOutputSizes(ImageFormat.RAW_SENSOR) } catch (t: Throwable) { null })?.maxByOrNull { it.area }
    }?.takeIf { raw && it.area > (rawSize?.area ?: 0L) }
    val pixelModeAvailable: Boolean = Build.VERSION.SDK_INT >= 31 && rawSizeMax != null && accepts(CaptureRequest.SENSOR_PIXEL_MODE)

    /** RAW in max-res mode is quad-Bayer unless a non-RAW max-res stream rides along in the same request. */
    val remosaic: Boolean = hasCap(CAP_REMOSAIC_REPROCESSING)
    val maxResCompanion: Pair<Int, Size>? = maxResMap?.let { m ->
        val jpeg = (try { m.getOutputSizes(ImageFormat.JPEG) } catch (t: Throwable) { null })?.minByOrNull { it.area }
        if (jpeg != null) ImageFormat.JPEG to jpeg
        else (try { m.getOutputSizes(ImageFormat.YUV_420_888) } catch (t: Throwable) { null })?.minByOrNull { it.area }?.let { ImageFormat.YUV_420_888 to it }
    }

    /** 35 mm equivalent focal length of the widest focal length. */
    val equivalentFocal: Float = run {
        val f = focalLengths.firstOrNull() ?: 0f
        val ps = physicalSize
        val pa = pixelArray
        if (f <= 0f || ps == null || ps.width <= 0f) return@run 0f
        // Physical size is for the full pixel array; scale to the active area.
        val fracW = if (pa != null && pa.width > 0) activeArray.width().toFloat() / pa.width else 1f
        val fracH = if (pa != null && pa.height > 0) activeArray.height().toFloat() / pa.height else 1f
        val w = ps.width * fracW
        val h = ps.height * fracH
        val diag = sqrt(w * w + h * h)
        if (diag <= 0f) 0f else f * 43.27f / diag
    }

    val shortName: String = when {
        facing == CameraMetadata.LENS_FACING_FRONT -> "F"
        facing == CameraMetadata.LENS_FACING_EXTERNAL -> "EXT"
        equivalentFocal <= 0f -> "CAM"
        equivalentFocal < 20f -> "UW"
        equivalentFocal < 35f -> "W"
        equivalentFocal < 60f -> "M"
        else -> "T"
    }

    val megapixels: Float = ((rawSize ?: jpegSize)?.area ?: 0L) / 1_000_000f

    val facingName: String get() = when (facing) {
        CameraMetadata.LENS_FACING_FRONT -> "FRONT"
        CameraMetadata.LENS_FACING_BACK -> "BACK"
        else -> "EXTERNAL"
    }

    val label: String get() = if (equivalentFocal > 0f) "$shortName ${equivalentFocal.roundToInt()}mm" else "$shortName $sensorId"

    fun mpText(size: Size?): String = if (size == null) "--" else {
        val mp = size.area / 1_000_000f
        if (mp >= 10f) "${mp.roundToInt()}MP" else "${(mp * 10).roundToInt() / 10f}MP"
    }
}

enum class ScanStatus { OK, UNSUPPORTED, UNTESTED }

/**
 * Something the user can pick in the camera chooser: a single physical sensor, or a combo of two physical
 * sensors of the same logical container streaming at the same time.
 */
class CameraOption(val sensors: List<SensorInfo>, val isCombo: Boolean) {
    val primary: SensorInfo get() = sensors[0]
    val key: String = if (isCombo) "combo:" + sensors.joinToString("+") { it.key } else sensors[0].key
    val openId: String get() = primary.openId

    /** Result of the hardware probe. Directly openable sensors are always OK. */
    var status: ScanStatus = if (!isCombo && sensors[0].physicalId == null) ScanStatus.OK else ScanStatus.UNTESTED

    /** For combos: whether all sensors can deliver RAW at the same time (else ISP JPEG for some). */
    var comboRaw: BooleanArray = BooleanArray(sensors.size) { sensors[it].raw }

    val label: String get() = if (isCombo) sensors.joinToString("+") { it.shortName } else primary.label

    val detail: String get() = if (isCombo) {
        sensors.joinToString("  ") { s -> s.label } + if (comboRaw.all { it }) "  RAW×${sensors.size}" else "  MIXED"
    } else {
        val p = primary
        "${p.facingName} · ${p.mpText(p.rawSize ?: p.jpegSize)} · " + if (p.raw) "RAW" else "NO RAW"
    }
}

/** Capabilities of the current option (intersection for combos). */
class Caps(val option: CameraOption) {
    private val s = option.sensors
    private val p = option.primary
    private val combo = option.isCombo

    val raw: Boolean = s.all { it.raw }
    val isoRange: Range<Int>? = intersectInt(s.map { it.isoRange })
    val exposureRange: Range<Long>? = intersectLong(s.map { it.exposureRange })
    val manual: Boolean = isoRange != null && exposureRange != null
    val apertures: FloatArray = if (!combo && p.apertureAdjustable && manual) p.apertures else FloatArray(0)
    val fixedAperture: Float = p.apertures.firstOrNull() ?: 0f
    val ndDensities: FloatArray = if (!combo && p.ndAdjustable && manual) p.ndDensities else FloatArray(0)
    val ois: Boolean = s.all { it.ois }
    val flash: Boolean = p.flash
    val testPatterns: IntArray = if (!combo && p.testPatternAvailable) p.testPatterns else IntArray(0)
    val autofocus: Boolean = s.all { it.autofocus }
    val manualFocus: Boolean = !combo && p.manualFocus
    val minFocusDiopters: Float = p.minFocusDiopters
    val hyperfocalDiopters: Float = p.hyperfocalDiopters
    val focusCalibrated: Boolean = p.focusCalibrated
    val pixelMode: Boolean = !combo && p.pixelModeAvailable
    val opticalFocalLengths: FloatArray = if (!combo && p.opticalZoom) p.focalLengths else FloatArray(0)
    val blackLevelLock: Boolean = s.all { it.blackLevelLock }
    val isoPriority: Boolean = !combo && p.isoPriority
    val shutterPriority: Boolean = !combo && p.shutterPriority
    /** ISP zoom for video, only for directly opened sensors (never asks a logical camera to switch lenses). */
    val videoZoomMax: Float = if (!combo && p.physicalId == null && p.accepts(CaptureRequest.CONTROL_ZOOM_RATIO))
        (p.zoomRatioRange?.upper ?: 1f).coerceAtMost(10f) else 1f

    private fun intersectInt(list: List<Range<Int>?>): Range<Int>? {
        if (list.any { it == null }) return null
        val lo = list.maxOf { it!!.lower }
        val hi = list.minOf { it!!.upper }
        return if (lo <= hi) Range(lo, hi) else null
    }

    private fun intersectLong(list: List<Range<Long>?>): Range<Long>? {
        if (list.any { it == null }) return null
        val lo = list.maxOf { it!!.lower }
        val hi = list.minOf { it!!.upper }
        return if (lo <= hi) Range(lo, hi) else null
    }
}

class Catalog(val options: List<CameraOption>, val sensors: List<SensorInfo>) {
    /** Options the user may pick (probe failures removed). */
    val usable: List<CameraOption> get() = options.filter { it.status != ScanStatus.UNSUPPORTED }

    companion object {
        fun build(manager: CameraManager): Catalog {
            val ids = try { manager.cameraIdList.toList() } catch (t: Throwable) { emptyList() }
            val direct = ArrayList<SensorInfo>()
            val logical = ArrayList<Pair<String, CameraCharacteristics>>()
            for (id in ids) {
                val ch = try { manager.getCameraCharacteristics(id) } catch (t: Throwable) { Log.w(TAG, "chars $id", t); null } ?: continue
                val caps = ch.safe(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: IntArray(0)
                if (!caps.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_BACKWARD_COMPATIBLE)) continue
                if (caps.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA)) {
                    logical.add(id to ch)
                } else {
                    direct.add(SensorInfo(id, null, ch, null))
                }
            }

            val viaLogical = ArrayList<SensorInfo>()
            val combos = ArrayList<CameraOption>()
            val seenPhysical = HashSet<String>(direct.map { it.sensorId })
            for ((lid, lch) in logical) {
                val physIds = try { lch.physicalCameraIds.toList() } catch (t: Throwable) { emptyList() }
                val members = ArrayList<SensorInfo>()
                for (pid in physIds) {
                    val pch = try { manager.getCameraCharacteristics(pid) } catch (t: Throwable) { null } ?: continue
                    val pcaps = pch.safe(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: IntArray(0)
                    if (!pcaps.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_BACKWARD_COMPATIBLE)) continue
                    // Nested logical cameras are fused devices themselves: never used.
                    if (pcaps.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA)) continue
                    val viaContainer = SensorInfo(lid, pid, pch, lch)
                    members.add(viaContainer)
                    // A sensor that is also listed on its own is opened directly; otherwise through the container.
                    if (pid !in seenPhysical) {
                        seenPhysical.add(pid)
                        viaLogical.add(viaContainer)
                    }
                }
                // Simultaneous pairs inside one container ("combo stream"); verified by the hardware probe.
                for (i in members.indices) for (j in i + 1 until members.size) {
                    val a = members[i]
                    val b = members[j]
                    if (a.facing != b.facing) continue
                    val pair = listOf(a, b).sortedBy { if (it.equivalentFocal > 0) it.equivalentFocal else 999f }
                    combos.add(CameraOption(pair, true))
                }
            }

            fun order(s: SensorInfo): Float {
                val facingRank = when (s.facing) {
                    CameraMetadata.LENS_FACING_BACK -> 0f
                    CameraMetadata.LENS_FACING_FRONT -> 1000f
                    else -> 2000f
                }
                return facingRank + (if (s.equivalentFocal > 0f) s.equivalentFocal else 500f)
            }

            val singles = (direct + viaLogical).sortedBy { order(it) }.map { CameraOption(listOf(it), false) }
            // The same physical pair can sit in several logical containers: offer it once.
            val uniqueCombos = combos.distinctBy { o -> o.sensors.map { it.sensorId }.sorted().joinToString("+") }
            return Catalog(singles + uniqueCombos, direct + viaLogical)
        }
    }
}
