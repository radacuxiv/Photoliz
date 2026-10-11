package dev.panasko.photoliz.camera

import android.graphics.Bitmap
import android.net.Uri
import android.util.Size

enum class FlashMode { OFF, FLASH, TORCH }

enum class CaptureMode { PHOTO, VIDEO }

enum class ExposureMode { AUTO, MANUAL, ISO_PRIORITY, SHUTTER_PRIORITY }

/** Immutable snapshot of everything the user controls. Sent from the UI to the camera thread. */
data class CaptureSettings(
    val mode: CaptureMode = CaptureMode.PHOTO,
    val isoAuto: Boolean = true,
    val iso: Int = 100,
    val shutterAuto: Boolean = true,
    val exposureNs: Long = 10_000_000L,
    /** f-number; 0 = widest available. */
    val aperture: Float = 0f,
    val ndAuto: Boolean = true,
    val nd: Float = 0f,
    val afOn: Boolean = true,
    /** Manual focus in diopters (0 = infinity). Used when AF is off. */
    val focusDiopters: Float = 0f,
    val flash: FlashMode = FlashMode.OFF,
    /** 0 = off, else a SENSOR_TEST_PATTERN_MODE value. */
    val testPattern: Int = 0,
    val maxRes: Boolean = false,
    val ois: Boolean = false,
    /** Photo: digital crop factor applied to preview + files. Video: ISP zoom ratio. */
    val zoom: Float = 1f,
    /** Optical zoom lenses only (LENS_FOCAL_LENGTH); 0 = not used. */
    val focalLength: Float = 0f,
    val aspect: AspectRatio = AspectRatio.TRUE,
    val saveDng: Boolean = false,
) {
    fun exposureMode(caps: Caps): ExposureMode = when {
        isoAuto && shutterAuto -> ExposureMode.AUTO
        !isoAuto && !shutterAuto -> ExposureMode.MANUAL
        !isoAuto && shutterAuto -> if (caps.isoPriority) ExposureMode.ISO_PRIORITY else ExposureMode.MANUAL
        else -> if (caps.shutterPriority) ExposureMode.SHUTTER_PRIORITY else ExposureMode.MANUAL
    }
}

/** What the sensor is actually doing, read back from capture results (~15 Hz). */
data class LiveState(
    val iso: Int,
    val exposureNs: Long,
    val aperture: Float,
    val nd: Float,
    val focusDiopters: Float,
    val focalLength: Float,
)

enum class FocusPhase { IDLE, SCANNING, LOCKED, FAILED }

/** AF feedback. [x],[y] are normalized coordinates in the upright preview (0..1). */
data class FocusUi(val phase: FocusPhase, val x: Float, val y: Float, val fromTap: Boolean)

data class SessionInfo(
    val option: CameraOption,
    val mode: CaptureMode,
    val previewSize: Size,
    val sensorOrientation: Int,
    val front: Boolean,
    /** Size of the still frames (RAW or ISP JPEG) of the primary sensor. */
    val stillSize: Size?,
    val raw: Boolean,
    val maxResActive: Boolean,
    val videoSize: Size?,
)

data class SavedShot(
    val jpegUri: Uri?,
    val dngUri: Uri?,
    val thumbnail: Bitmap?,
    val sensorLabel: String,
    val width: Int,
    val height: Int,
)
