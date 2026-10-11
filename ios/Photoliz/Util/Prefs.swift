import Foundation
import UIKit

/// Persistent preferences. Exposure is intentionally not persisted: every launch starts in auto, AF on.
final class Prefs {
    static let shared = Prefs()
    private let d = UserDefaults.standard

    private var buildKey: String { "\(AppInfo.version)|\(UIDevice.current.systemVersion)" }

    var reportAccepted: Bool {
        get { d.bool(forKey: "report_accepted") && d.string(forKey: "report_build") == buildKey }
        set { d.set(newValue, forKey: "report_accepted"); d.set(buildKey, forKey: "report_build") }
    }

    var palette: String {
        get { d.string(forKey: "palette") ?? "Canonical" }
        set { d.set(newValue, forKey: "palette") }
    }

    var zoomSlider: Bool {
        get { d.object(forKey: "zoom_slider") as? Bool ?? false }
        set { d.set(newValue, forKey: "zoom_slider") }
    }

    var focusSlider: Bool {
        get { d.object(forKey: "focus_slider") as? Bool ?? true }
        set { d.set(newValue, forKey: "focus_slider") }
    }

    var ois: Bool {
        get { d.object(forKey: "ois") as? Bool ?? false }
        set { d.set(newValue, forKey: "ois") }
    }

    var saveDng: Bool {
        get { d.object(forKey: "save_dng") as? Bool ?? false }
        set { d.set(newValue, forKey: "save_dng") }
    }

    /// Bookmark of a user-picked DNG folder (nil = automatic Documents/DNG).
    var dngFolderBookmark: Data? {
        get { d.data(forKey: "dng_bookmark") }
        set { d.set(newValue, forKey: "dng_bookmark") }
    }

    var extraAspects: Set<String> {
        get { Set(d.stringArray(forKey: "extra_aspects") ?? []) }
        set { d.set(Array(newValue).sorted(), forKey: "extra_aspects") }
    }

    var aspectId: String {
        get { d.string(forKey: "aspect") ?? "TRUE" }
        set { d.set(newValue, forKey: "aspect") }
    }

    var lastCamera: String? {
        get { d.string(forKey: "last_camera") }
        set { d.set(newValue, forKey: "last_camera") }
    }
}
