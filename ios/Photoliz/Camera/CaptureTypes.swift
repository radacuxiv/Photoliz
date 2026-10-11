import AVFoundation
import CoreGraphics
import Foundation

enum FlashMode { case off, flash, torch }
enum CaptureMode { case photo, video }
enum ExposureMode { case auto, manual }

/// Everything the user controls. Value type, copied to the session queue.
struct CaptureSettings {
    var mode: CaptureMode = .photo
    var isoAuto = true
    var iso: Float = 100
    var shutterAuto = true
    /// Exposure time in seconds.
    var exposure: Double = 1.0 / 100.0
    var afOn = true
    /// Manual lens position 0 (near) ... 1 (far).
    var lensPosition: Float = 1
    var flash: FlashMode = .off
    var ois = false
    /// Photo: digital crop applied to preview and files. Video: ISP zoom.
    var zoom: CGFloat = 1
    var aspect: AspectRatio = .trueToSensor
    var saveDng = false
    /// Index into the sensor's photo resolution options.
    var resolutionIndex = 0

    /// AVFoundation has no shutter- or ISO-priority mode: one manual value makes both manual.
    var exposureMode: ExposureMode { (isoAuto && shutterAuto) ? .auto : .manual }
}

struct LiveState {
    var iso: Float
    var exposure: Double
    var lensPosition: Float
    var aperture: Float
}

enum FocusPhase { case idle, scanning, locked, failed }

/// Pure exposure scales (same tables as Android).
enum ExposureScales {
    static let isoCommon: [Int] = [25, 32, 40, 50, 64, 80, 100, 125, 160, 200, 250, 320, 400, 500, 640, 800, 1000, 1250,
                                   1600, 2000, 2500, 3200, 4000, 5000, 6400, 8000, 10000, 12800, 16000, 20000, 25600]

    struct Shutter {
        let seconds: Double
        let label: String
    }

    private static func frac(_ d: Int) -> Shutter { Shutter(seconds: 1.0 / Double(d), label: "1/\(d)") }
    private static func secs(_ s: Double, _ l: String) -> Shutter { Shutter(seconds: s, label: l) }

    static let shutters: [Shutter] = [
        frac(32000), frac(25600), frac(20000), frac(16000), frac(12800), frac(10000), frac(8000), frac(6400), frac(5000),
        frac(4000), frac(3200), frac(2500), frac(2000), frac(1600), frac(1250), frac(1000), frac(800), frac(640),
        frac(500), frac(400), frac(320), frac(250), frac(200), frac(160), frac(125), frac(100), frac(80), frac(60),
        frac(50), frac(40), frac(30), frac(25), frac(20), frac(15), frac(13), frac(10), frac(8), frac(6), frac(5), frac(4),
        secs(0.3, "0.3\""), secs(0.4, "0.4\""), secs(0.5, "0.5\""), secs(0.6, "0.6\""), secs(0.8, "0.8\""),
        secs(1.0, "1\""), secs(1.3, "1.3\""), secs(1.6, "1.6\""), secs(2.0, "2\""), secs(2.5, "2.5\""),
        secs(3.2, "3.2\""), secs(4.0, "4\""), secs(5.0, "5\""), secs(6.0, "6\""), secs(8.0, "8\""), secs(10.0, "10\""),
        secs(13.0, "13\""), secs(15.0, "15\""), secs(20.0, "20\""), secs(25.0, "25\""), secs(30.0, "30\""),
    ]

    static func shuttersIn(min: Double, max: Double) -> [Shutter] {
        guard max > 0, max >= min else { return [] }
        let list = shutters.filter { $0.seconds >= min * 0.98 && $0.seconds <= max * 1.02 }
        return list.isEmpty ? [Shutter(seconds: max, label: format(shutter: max))] : list
    }

    static func format(shutter s: Double) -> String {
        guard s > 0, s.isFinite else { return "--" }
        let candidates = s >= 0.3 ? shutters.filter { $0.seconds >= 0.3 } : shutters.filter { $0.seconds < 0.3 }
        if let n = candidates.min(by: { abs(log($0.seconds / s)) < abs(log($1.seconds / s)) }), abs(log(n.seconds / s)) < 0.06 {
            return n.label
        }
        if s >= 0.3 { return s >= 10 ? "\(Int(s.rounded()))\"" : String(format: "%.1f\"", s) }
        return "1/\(Int((1 / s).rounded()))"
    }

    static func stopsBetween(_ a: Double, _ b: Double) -> Double { abs(log2(b / a)) }

    static func isoStops(min: Int, max: Int) -> [Int] {
        guard max >= min, max > 0 else { return [] }
        var set = Set(isoCommon.filter { $0 >= min && $0 <= max })
        set.insert(min); set.insert(max)
        var list = set.sorted()
        if list.count > 2, stopsBetween(Double(list[0]), Double(list[1])) < 1.0 / 12, isoCommon.contains(list[1]) { list.removeFirst() }
        let n = list.count
        if n > 2, stopsBetween(Double(list[n - 2]), Double(list[n - 1])) < 1.0 / 12, isoCommon.contains(list[n - 2]) { list.removeLast() }
        return list
    }

    /// Magnetic snapping for the continuous ISO dial.
    static func snapISO(_ iso: Double, min: Int, max: Int, threshold: Double) -> Int {
        let c = Swift.min(Swift.max(iso, Double(min)), Double(max))
        var best = -1
        var bestD = Double.greatestFiniteMagnitude
        for s in isoStops(min: min, max: max) {
            let d = stopsBetween(c, Double(s))
            if d < bestD { bestD = d; best = s }
        }
        if best > 0 && bestD <= threshold { return best }
        return Swift.min(Swift.max(Int(c.rounded()), min), max)
    }

    static func isoToStops(_ iso: Double, min: Int) -> Double { log2(iso / Double(min)) }
    static func stopsToISO(_ stops: Double, min: Int) -> Double { Double(min) * pow(2, stops) }

    static func nearestShutterIndex(_ list: [Shutter], _ s: Double) -> Int {
        guard !list.isEmpty else { return -1 }
        var best = 0
        var bestD = Double.greatestFiniteMagnitude
        for (i, v) in list.enumerated() {
            let d = abs(log(v.seconds / Swift.max(s, 1e-9)))
            if d < bestD { bestD = d; best = i }
        }
        return best
    }

    static func format(aperture f: Float) -> String {
        guard f > 0 else { return "f/--" }
        let v = (f * 10).rounded() / 10
        return v == v.rounded() ? "f/\(Int(v))" : String(format: "f/%.1f", v)
    }
}

/// Aspect crops; ratios are long side : short side and follow how the phone is held.
enum AspectRatio: String, CaseIterable {
    case trueToSensor = "TRUE"
    case r16x9 = "16:9"
    case r4x3 = "4:3"
    case r239 = "2.39:1"
    case r185 = "1.85:1"
    case r2x3 = "2:3"
    case r119 = "1.19:1"
    case r1x1 = "1:1"

    var label: String { rawValue }

    var ratio: Double? {
        switch self {
        case .trueToSensor: return nil
        case .r16x9: return 16.0 / 9.0
        case .r4x3: return 4.0 / 3.0
        case .r239: return 2.39
        case .r185: return 1.85
        case .r2x3: return 3.0 / 2.0
        case .r119: return 1.19
        case .r1x1: return 1.0
        }
    }

    var isExtra: Bool { ![.trueToSensor, .r16x9, .r4x3].contains(self) }

    static func cycle(extras: Set<String>) -> [AspectRatio] { allCases.filter { !$0.isExtra || extras.contains($0.rawValue) } }
    static func byId(_ id: String?) -> AspectRatio { AspectRatio(rawValue: id ?? "") ?? .trueToSensor }
}

enum CropMath {
    struct Rect { var left: Int; var top: Int; var width: Int; var height: Int }

    /// Centered crop of a w×h frame for a long:short ratio and a zoom factor (same rules as Android).
    static func centered(w: Int, h: Int, ratio: Double?, zoom: Double, align: Int = 2) -> Rect {
        var cw = Double(w)
        var ch = Double(h)
        if let r = ratio, r > 0 {
            let landscape = w >= h
            let frame = landscape ? Double(w) / Double(h) : Double(h) / Double(w)
            if r >= frame {
                if landscape { ch = Double(w) / r } else { cw = Double(h) / r }
            } else {
                if landscape { cw = Double(h) * r } else { ch = Double(w) * r }
            }
        }
        let z = max(zoom, 1)
        cw /= z
        ch /= z
        var iw = Int((cw / Double(align)).rounded()) * align
        var ih = Int((ch / Double(align)).rounded()) * align
        iw = min(max(iw, align), w)
        ih = min(max(ih, align), h)
        var left = ((w - iw) / 2 / align) * align
        var top = ((h - ih) / 2 / align) * align
        if left + iw > w { left = w - iw }
        if top + ih > h { top = h - ih }
        return Rect(left: max(0, left), top: max(0, top), width: iw, height: ih)
    }
}

extension CMTime {
    init(secondsValue s: Double) { self = CMTimeMakeWithSeconds(s, preferredTimescale: 1_000_000_000) }
}
