package dev.panasko.photoliz.util

import android.content.Context
import android.content.SharedPreferences
import android.os.Build

/**
 * Persistent user preferences. Exposure values are intentionally NOT persisted:
 * every launch starts with auto ISO / auto shutter / widest aperture / auto ND and AF on.
 */
class Prefs(context: Context) {
    private val sp: SharedPreferences =
        context.applicationContext.getSharedPreferences("photoliz", Context.MODE_PRIVATE)

    var reportAccepted: Boolean
        get() = sp.getBoolean(KEY_REPORT_ACCEPTED, false) && sp.getString(KEY_FINGERPRINT, null) == Build.FINGERPRINT
        set(v) {
            sp.edit().putBoolean(KEY_REPORT_ACCEPTED, v).putString(KEY_FINGERPRINT, Build.FINGERPRINT).apply()
        }

    var palette: String
        get() = sp.getString("palette", "Canonical") ?: "Canonical"
        set(v) = sp.edit().putString("palette", v).apply()

    var zoomSlider: Boolean
        get() = sp.getBoolean("zoom_slider", false)
        set(v) = sp.edit().putBoolean("zoom_slider", v).apply()

    var focusSlider: Boolean
        get() = sp.getBoolean("focus_slider", true)
        set(v) = sp.edit().putBoolean("focus_slider", v).apply()

    var ois: Boolean
        get() = sp.getBoolean("ois", false)
        set(v) = sp.edit().putBoolean("ois", v).apply()

    var saveDng: Boolean
        get() = sp.getBoolean("save_dng", false)
        set(v) = sp.edit().putBoolean("save_dng", v).apply()

    /** SAF tree URI chosen by the user, or null for the automatic MediaStore folder. */
    var dngTreeUri: String?
        get() = sp.getString("dng_tree", null)
        set(v) = sp.edit().putString("dng_tree", v).apply()

    var extraAspects: Set<String>
        get() = sp.getStringSet("extra_aspects", emptySet())?.toSet() ?: emptySet()
        set(v) = sp.edit().putStringSet("extra_aspects", v).apply()

    var aspectId: String
        get() = sp.getString("aspect", "TRUE") ?: "TRUE"
        set(v) = sp.edit().putString("aspect", v).apply()

    var lastCamera: String?
        get() = sp.getString("last_camera", null)
        set(v) = sp.edit().putString("last_camera", v).apply()

    /** Cached result of the hardware scan (session-support probes), keyed by build fingerprint. */
    var scanCache: String?
        get() = if (sp.getString("scan_fp", null) == Build.FINGERPRINT) sp.getString("scan_cache", null) else null
        set(v) = sp.edit().putString("scan_cache", v).putString("scan_fp", Build.FINGERPRINT).apply()

    companion object {
        private const val KEY_REPORT_ACCEPTED = "report_accepted"
        private const val KEY_FINGERPRINT = "report_fp"
    }
}
