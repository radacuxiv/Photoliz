import AVFoundation
import UIKit

/// One physical camera module. Virtual (fused) devices — dual, triple, dual-wide, TrueDepth, LiDAR — are never used.
final class SensorInfo {
    let device: AVCaptureDevice

    // Filled by the capability scan (needs a configured session).
    var bayerRaw: OSType?
    var flashModes: [AVCaptureDevice.FlashMode] = []
    var lensStabilization = false
    /// Photo resolution options, smallest first (binned and full readout).
    var resolutions: [CMVideoDimensions] = []
    var isoRange: ClosedRange<Float> = 0...0
    var exposureRange: ClosedRange<Double> = 0...0
    var fov: Float = 0
    var videoMaxZoom: CGFloat = 1
    var scanned = false

    init(device: AVCaptureDevice) { self.device = device }

    var id: String { device.uniqueID }
    var raw: Bool { bayerRaw != nil }
    var flash: Bool { flashModes.contains(.on) }
    var torch: Bool { device.hasTorch && device.isTorchModeSupported(.on) }
    var autofocus: Bool { device.isFocusModeSupported(.autoFocus) }
    var manualFocus: Bool { device.isLockingFocusWithCustomLensPositionSupported }
    var manualExposure: Bool { device.isExposureModeSupported(.custom) && isoRange.upperBound > 0 }
    var aperture: Float { device.lensAperture }
    var front: Bool { device.position == .front }

    var equivalentFocal: Float {
        guard fov > 1 && fov < 179 else { return 0 }
        return 18.0 / tan(fov * .pi / 360.0)
    }

    var shortName: String {
        if front { return "F" }
        let f = equivalentFocal
        if f <= 0 { return deviceTypeName }
        if f < 20 { return "UW" }
        if f < 35 { return "W" }
        if f < 60 { return "M" }
        return "T"
    }

    private var deviceTypeName: String {
        switch device.deviceType {
        case .builtInUltraWideCamera: return "UW"
        case .builtInTelephotoCamera: return "T"
        default: return "W"
        }
    }

    var label: String {
        let f = equivalentFocal
        return f > 0 ? "\(shortName) \(Int(f.rounded()))mm" : shortName
    }

    var facingName: String { front ? "FRONT" : (device.position == .back ? "BACK" : "EXTERNAL") }

    static func megapixels(_ d: CMVideoDimensions) -> String {
        let mp = Double(d.width) * Double(d.height) / 1_000_000
        return mp >= 10 ? "\(Int(mp.rounded()))MP" : String(format: "%.1fMP", mp)
    }

    var detail: String {
        let res = resolutions.last.map { SensorInfo.megapixels($0) } ?? "--"
        return "\(facingName) · \(res) · " + (raw ? "RAW" : "NO RAW")
    }

    /// Discovers the physical cameras. Back modules first (wide to tele), then front.
    static func discover() -> [SensorInfo] {
        let types: [AVCaptureDevice.DeviceType] = [.builtInWideAngleCamera, .builtInUltraWideCamera, .builtInTelephotoCamera]
        let session = AVCaptureDevice.DiscoverySession(deviceTypes: types, mediaType: .video, position: .unspecified)
        var seen = Set<String>()
        var list: [SensorInfo] = []
        for d in session.devices where !seen.contains(d.uniqueID) {
            seen.insert(d.uniqueID)
            list.append(SensorInfo(device: d))
        }
        func rank(_ s: SensorInfo) -> Int {
            let pos = s.device.position == .back ? 0 : (s.device.position == .front ? 1 : 2)
            let type: Int
            switch s.device.deviceType {
            case .builtInUltraWideCamera: type = 0
            case .builtInWideAngleCamera: type = 1
            default: type = 2
            }
            return pos * 10 + type
        }
        return list.sorted { rank($0) < rank($1) }
    }
}
