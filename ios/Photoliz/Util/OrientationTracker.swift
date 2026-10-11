import CoreMotion
import UIKit

/// Physical device orientation from the accelerometer (works with the rotation lock on).
/// Degrees use the same convention as Android: 0 portrait, 90 left side up, 180 upside down, 270 right side up.
final class OrientationTracker {
    private let motion = CMMotionManager()
    private(set) var orientation: Int = 0
    var onChange: ((Int) -> Void)?

    func start() {
        guard motion.isAccelerometerAvailable, !motion.isAccelerometerActive else { return }
        motion.accelerometerUpdateInterval = 0.1
        motion.startAccelerometerUpdates(to: .main) { [weak self] data, _ in
            guard let self = self, let a = data?.acceleration else { return }
            self.update(x: a.x, y: a.y, z: a.z)
        }
    }

    func stop() {
        if motion.isAccelerometerActive { motion.stopAccelerometerUpdates() }
    }

    private func update(x: Double, y: Double, z: Double) {
        // Ignore when the phone lies flat: keep the last orientation.
        if abs(z) > 0.85 { return }
        // Angle of gravity in the screen plane; 0 = portrait (gravity towards the bottom edge).
        var deg = atan2(x, -y) * 180.0 / .pi   // left side up -> +90
        if deg < 0 { deg += 360 }
        let current = Double(orientation)
        var diff = deg - current
        while diff > 180 { diff -= 360 }
        while diff < -180 { diff += 360 }
        if abs(diff) < 60 { return }
        let next = (Int((deg + 45).rounded(.down)) / 90 * 90) % 360
        if next != orientation {
            orientation = next
            onChange?(next)
        }
    }
}

enum Haptics {
    private static let selection = UISelectionFeedbackGenerator()
    private static let light = UIImpactFeedbackGenerator(style: .light)
    private static let heavy = UIImpactFeedbackGenerator(style: .heavy)
    private static let notify = UINotificationFeedbackGenerator()

    static func tick() { selection.selectionChanged() }
    static func press() { light.impactOccurred() }
    static func confirm() { heavy.impactOccurred() }
    static func warn() { notify.notificationOccurred(.warning) }
}
