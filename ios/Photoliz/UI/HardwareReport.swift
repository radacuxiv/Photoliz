import AVFoundation
import UIKit

/// The first-run disclaimer: which hardware controls exist on this iPhone and which do not.
enum HardwareReport {
    private enum Mark { case yes, no, maybe }

    /// Hardware identifier such as "iPhone15,2".
    static var machine: String {
        var u = utsname()
        uname(&u)
        return withUnsafeBytes(of: &u.machine) { raw in
            String(decoding: raw.prefix { $0 != 0 }, as: UTF8.self)
        }
    }

    static func rows(sensors: [SensorInfo], palette p: Palette) -> [UIView] {
        var out: [UIView] = []
        func header(_ t: String) {
            let r = MenuRow(palette: p)
            r.style = .header
            r.title = t
            out.append(r)
        }
        func row(_ t: String, _ v: String, _ m: Mark, _ note: String = "") {
            let r = MenuRow(palette: p)
            r.style = .text
            r.title = t
            r.value = v
            r.note = note
            switch m {
            case .yes: r.icon = PixelIcons.check; r.iconColor = p.accentOnSurface
            case .no: r.icon = PixelIcons.cross; r.iconColor = p.alert
            case .maybe: r.icon = PixelIcons.partial; r.iconColor = p.textDim
            }
            out.append(r)
        }
        func yes(_ b: Bool) -> Mark { b ? .yes : .no }

        let intro = TextBlock(palette: p)
        intro.text = "Photoliz only uses controls that physically exist in your camera hardware and are exposed by AVFoundation. " +
            "Everything marked ✕ is not available on this device and is hidden from the interface. JPEGs are developed from the " +
            "Bayer RAW sensor data with as-shot white balance and the sensor's own color calibration: no tone curve, no sharpening, " +
            "no noise reduction, no AI. Photo zoom is an honest crop of the sensor frame."
        out.append(intro)

        header("DEVICE")
        row("MODEL", machine.isEmpty ? UIDevice.current.model : machine, .yes)
        row("iOS", UIDevice.current.systemVersion, .yes)
        row("SENSORS", "\(sensors.count)", sensors.isEmpty ? .no : .yes)

        for s in sensors {
            header("\(s.facingName) · \(s.label.uppercased())")
            if !s.scanned {
                row("ACCESS", "unavailable", .no, "The camera could not be configured with this module.")
                continue
            }
            row("RAW / DNG", s.raw ? (s.resolutions.last.map { "\($0.width)×\($0.height) max" } ?? "yes") : "processed only", yes(s.raw),
                s.raw ? "" : "No Bayer RAW: the least-processed photo AVFoundation offers is used.")
            row("ISO", s.manualExposure ? "\(Int(s.isoRange.lowerBound))–\(Int(s.isoRange.upperBound))" : "auto only", yes(s.manualExposure))
            row("SHUTTER", s.manualExposure ? "\(ExposureScales.format(shutter: s.exposureRange.lowerBound))–\(ExposureScales.format(shutter: s.exposureRange.upperBound))" : "auto only",
                yes(s.manualExposure))
            row("APERTURE", "fixed " + ExposureScales.format(aperture: s.aperture), .no, "iPhone lenses have a fixed aperture: hidden.")
            row("OPTICAL STAB.", s.lensStabilization ? "photo" : "no", yes(s.lensStabilization),
                s.lensStabilization ? "Exposed only for bracketed captures, so stabilized shots are taken as one-frame brackets. Not combinable with flash." : "")
            row("FLASH", s.flash ? "yes" : "no", yes(s.flash))
            row("TORCH", s.torch ? "yes" : "no", yes(s.torch))
            row("AUTOFOCUS", s.autofocus ? "yes" : "fixed focus", yes(s.autofocus))
            row("MANUAL FOCUS", s.manualFocus ? "lens position" : "no", yes(s.manualFocus))
            let res = s.resolutions.map { SensorInfo.megapixels($0) }.joined(separator: " / ")
            row("RESOLUTION", res.isEmpty ? "--" : res, s.resolutions.count > 1 ? .yes : .maybe,
                s.resolutions.count > 1 ? "Full-resolution RAW may be delivered binned with manual exposure or Bayer RAW (iOS decides)." : "")
            row("ND FILTER", "none", .no)
            row("TEST PATTERNS", "Android only", .no)
            row("BLACK LEVEL LOCK", "Android only", .no)
        }
        header("ALWAYS AVAILABLE")
        row("ASPECT CROPS", "TRUE · 16:9 · 4:3 +", .yes,
            "Extra crops (2.39:1, 1.85:1, 2:3, 1.19:1, 1:1) can be added in MENU. Crops apply to the preview, the JPEG and the DNG default crop.")
        row("FILES", "JPG always · DNG opt.", .yes, "JPEGs go to Photos. DNG saving is off by default — enable it in MENU. DNGs go to Files → Photoliz → DNG unless you pick a folder.")
        return out
    }
}
