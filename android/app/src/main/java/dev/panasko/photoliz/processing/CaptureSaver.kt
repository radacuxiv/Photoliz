package dev.panasko.photoliz.processing

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.util.Log
import dev.panasko.photoliz.AppInfo
import java.io.File
import java.io.FileInputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.roundToInt
import kotlin.math.roundToLong

private const val TAG = "PhotolizSaver"

/** Writes finished files to shared storage: JPEG → Pictures/Photoliz, DNG → user folder or DCIM/Photoliz/DNG. */
class CaptureSaver(context: Context) {
    private val app = context.applicationContext
    private val resolver: ContentResolver = app.contentResolver

    class ExifData(
        val orientationDegrees: Int,
        val timeMillis: Long,
        val exposureNs: Long?,
        val iso: Int?,
        val aperture: Float?,
        val focalLength: Float?,
        val focal35: Int?,
        val flashFired: Boolean,
        val manualExposure: Boolean,
        val description: String,
    )

    fun fileStamp(timeMillis: Long, index: Int): String {
        val f = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US)
        return "PLZ_" + f.format(Date(timeMillis)) + (if (index > 0) "_%03d".format(Locale.US, index) else "")
    }

    fun exifOrientation(degrees: Int): Int = when (((degrees % 360) + 360) % 360) {
        90 -> ExifInterface.ORIENTATION_ROTATE_90
        180 -> ExifInterface.ORIENTATION_ROTATE_180
        270 -> ExifInterface.ORIENTATION_ROTATE_270
        else -> ExifInterface.ORIENTATION_NORMAL
    }

    fun writeExif(jpeg: File, d: ExifData) {
        try {
            val exif = ExifInterface(jpeg.absolutePath)
            val date = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).format(Date(d.timeMillis))
            val offsetMinutes = TimeZone.getDefault().getOffset(d.timeMillis) / 60000
            val sign = if (offsetMinutes < 0) "-" else "+"
            val offset = "%s%02d:%02d".format(Locale.US, sign, kotlin.math.abs(offsetMinutes) / 60, kotlin.math.abs(offsetMinutes) % 60)
            exif.setAttribute(ExifInterface.TAG_ORIENTATION, exifOrientation(d.orientationDegrees).toString())
            exif.setAttribute(ExifInterface.TAG_DATETIME, date)
            exif.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, date)
            exif.setAttribute(ExifInterface.TAG_DATETIME_DIGITIZED, date)
            exif.setAttribute(ExifInterface.TAG_OFFSET_TIME, offset)
            exif.setAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL, offset)
            exif.setAttribute(ExifInterface.TAG_SUBSEC_TIME_ORIGINAL, "%03d".format(Locale.US, d.timeMillis % 1000))
            exif.setAttribute(ExifInterface.TAG_MAKE, Build.MANUFACTURER)
            exif.setAttribute(ExifInterface.TAG_MODEL, Build.MODEL)
            exif.setAttribute(ExifInterface.TAG_SOFTWARE, AppInfo.NAME)
            exif.setAttribute(ExifInterface.TAG_IMAGE_DESCRIPTION, d.description)
            d.exposureNs?.takeIf { it > 0 }?.let { ns ->
                // Must be "num/den": the decimal compatibility path only keeps 1/10000 s precision.
                val seconds = ns / 1e9
                val value = if (seconds >= 1) "${(seconds * 1000).roundToLong()}/1000"
                else "1/${(1 / seconds).roundToLong().coerceAtLeast(1)}"
                exif.setAttribute(ExifInterface.TAG_EXPOSURE_TIME, value)
            }
            d.iso?.let { exif.setAttribute(ExifInterface.TAG_ISO_SPEED_RATINGS, it.toString()) }
            d.aperture?.takeIf { it > 0f }?.let { exif.setAttribute(ExifInterface.TAG_F_NUMBER, "%.2f".format(Locale.US, it)) }
            d.focalLength?.takeIf { it > 0f }?.let { exif.setAttribute(ExifInterface.TAG_FOCAL_LENGTH, "${(it * 1000).roundToInt()}/1000") }
            d.focal35?.takeIf { it > 0 }?.let { exif.setAttribute(ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM, it.toString()) }
            exif.setAttribute(ExifInterface.TAG_FLASH, if (d.flashFired) "1" else "0")
            exif.setAttribute(ExifInterface.TAG_EXPOSURE_MODE, if (d.manualExposure) "1" else "0")
            exif.setAttribute(ExifInterface.TAG_WHITE_BALANCE, "0")
            exif.saveAttributes()
        } catch (t: Throwable) {
            Log.w(TAG, "EXIF write failed", t)
        }
    }

    private fun copy(src: File, dst: Uri): Boolean = try {
        resolver.openOutputStream(dst, "w")?.use { out ->
            FileInputStream(src).use { it.copyTo(out, 1 shl 16) }
            true
        } ?: false
    } catch (t: Throwable) {
        Log.w(TAG, "copy failed", t)
        false
    }

    private fun insertMedia(collection: Uri, name: String, mime: String, relativePath: String, timeMillis: Long, src: File, image: Boolean): Uri? {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
            if (image) put(MediaStore.Images.Media.DATE_TAKEN, timeMillis)
        }
        val uri = try { resolver.insert(collection, values) } catch (t: Throwable) { Log.w(TAG, "insert failed", t); null } ?: return null
        if (!copy(src, uri)) {
            try { resolver.delete(uri, null, null) } catch (_: Throwable) {}
            return null
        }
        try {
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        } catch (t: Throwable) { Log.w(TAG, "publish failed", t) }
        return uri
    }

    fun saveJpeg(src: File, baseName: String, timeMillis: Long): Uri? = insertMedia(
        MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
        "$baseName.jpg", "image/jpeg",
        Environment.DIRECTORY_PICTURES + "/" + AppInfo.MEDIA_DIR, timeMillis, src, true,
    )

    class DngSaveResult(val uri: Uri?, val fellBack: Boolean)

    /**
     * Saves into the user's SAF folder when one is set and still writable; otherwise into the automatic
     * folder DCIM/Photoliz/DNG (created by MediaStore on first use).
     */
    fun saveDng(src: File, baseName: String, timeMillis: Long, treeUri: String?): DngSaveResult {
        if (treeUri != null) {
            try {
                val tree = Uri.parse(treeUri)
                val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
                val doc = DocumentsContract.createDocument(resolver, parent, "image/x-adobe-dng", "$baseName.dng")
                if (doc != null) {
                    if (copy(src, doc)) return DngSaveResult(doc, false)
                    try { DocumentsContract.deleteDocument(resolver, doc) } catch (_: Throwable) {}
                }
            } catch (t: Throwable) {
                Log.w(TAG, "SAF save failed, falling back", t)
            }
            return DngSaveResult(saveDngDefault(src, baseName, timeMillis), true)
        }
        return DngSaveResult(saveDngDefault(src, baseName, timeMillis), false)
    }

    private fun saveDngDefault(src: File, baseName: String, timeMillis: Long): Uri? = insertMedia(
        MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
        "$baseName.dng", "image/x-adobe-dng",
        Environment.DIRECTORY_DCIM + "/" + AppInfo.MEDIA_DIR + "/" + AppInfo.DNG_SUBDIR, timeMillis, src, true,
    )

    class VideoEntry(val uri: Uri, val pfd: ParcelFileDescriptor)

    fun createVideo(baseName: String): VideoEntry? {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "$baseName.mp4")
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/" + AppInfo.MEDIA_DIR)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        return try {
            val uri = resolver.insert(MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values) ?: return null
            val pfd = resolver.openFileDescriptor(uri, "rw")
            if (pfd == null) {
                resolver.delete(uri, null, null)
                null
            } else VideoEntry(uri, pfd)
        } catch (t: Throwable) {
            Log.w(TAG, "video entry failed", t)
            null
        }
    }

    fun finishVideo(entry: VideoEntry, keep: Boolean) {
        try { entry.pfd.close() } catch (_: Throwable) {}
        try {
            if (keep) resolver.update(entry.uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            else resolver.delete(entry.uri, null, null)
        } catch (t: Throwable) { Log.w(TAG, "finish video failed", t) }
    }

    /** Readable location of the automatic DNG folder. */
    fun defaultDngFolderLabel(): String = Environment.DIRECTORY_DCIM + "/" + AppInfo.MEDIA_DIR + "/" + AppInfo.DNG_SUBDIR

    fun treeLabel(treeUri: String?): String {
        if (treeUri == null) return defaultDngFolderLabel()
        return try {
            val id = DocumentsContract.getTreeDocumentId(Uri.parse(treeUri))
            // "primary:Pictures/Raw" → "Pictures/Raw"
            id.substringAfter(':', id).ifEmpty { "/" }
        } catch (t: Throwable) { "custom folder" }
    }

    fun hasTreePermission(treeUri: String?): Boolean {
        if (treeUri == null) return true
        val uri = Uri.parse(treeUri)
        return resolver.persistedUriPermissions.any { it.uri == uri && it.isWritePermission }
    }
}
