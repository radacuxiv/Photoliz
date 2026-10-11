import QuartzCore
import UIKit

/// Damped spring integrated in small fixed steps (stable at 60 and 120 Hz).
final class Spring {
    private(set) var value: CGFloat
    var target: CGFloat
    var velocity: CGFloat = 0
    var stiffness: CGFloat
    var damping: CGFloat

    init(_ v: CGFloat, stiffness: CGFloat = 420, damping: CGFloat = 0.86) {
        value = v
        target = v
        self.stiffness = stiffness
        self.damping = damping
    }

    var isMoving: Bool { value != target || velocity != 0 }

    func snap(_ v: CGFloat) { value = v; target = v; velocity = 0 }

    @discardableResult
    func step(_ dtIn: CGFloat) -> Bool {
        guard isMoving else { return false }
        var dt = min(max(dtIn, 0), 0.064)
        let c = 2 * damping * sqrt(stiffness)
        while dt > 0 {
            let h = min(dt, 1.0 / 240.0)
            let a = -stiffness * (value - target) - c * velocity
            velocity += a * h
            value += velocity * h
            dt -= h
        }
        let scale = max(1, abs(target))
        if abs(value - target) < 0.001 * scale && abs(velocity) < 0.02 * scale {
            value = target
            velocity = 0
            return false
        }
        return true
    }
}

private final class DisplayLinkProxy: NSObject {
    weak var owner: AnimatedView?
    init(_ owner: AnimatedView) {
        self.owner = owner
        super.init()
    }
    @objc func tick(_ link: CADisplayLink) {
        guard let o = owner else { link.invalidate(); return }
        o.frameTick(link)
    }
}

/// Base for custom-drawn controls driven by springs.
class AnimatedView: UIView {
    private var springs: [Spring] = []
    private var link: CADisplayLink?
    private var last: CFTimeInterval = 0

    override init(frame: CGRect) {
        super.init(frame: frame)
        isOpaque = false
        backgroundColor = .clear
        contentMode = .redraw
    }

    required init?(coder: NSCoder) { fatalError("init(coder:) is not used") }

    deinit { link?.invalidate() }

    func spring(_ v: CGFloat, stiffness: CGFloat = 420, damping: CGFloat = 0.86) -> Spring {
        let s = Spring(v, stiffness: stiffness, damping: damping)
        springs.append(s)
        return s
    }

    /// Starts the frame clock (stops itself when everything settles).
    func kick() {
        setNeedsDisplay()
        guard link == nil else { return }
        let l = CADisplayLink(target: DisplayLinkProxy(self), selector: #selector(DisplayLinkProxy.tick(_:)))
        l.add(to: .main, forMode: .common)
        link = l
        last = CACurrentMediaTime()
    }

    /// Override for time-based effects (spinners, blinking).
    var keepAnimating: Bool { false }

    fileprivate func frameTick(_ l: CADisplayLink) {
        let now = l.timestamp
        let dt = CGFloat(now - last)
        last = now
        var moving = false
        for s in springs where s.step(dt) { moving = true }
        setNeedsDisplay()
        if !moving && !keepAnimating {
            l.invalidate()
            link = nil
        }
    }

    override func didMoveToWindow() {
        super.didMoveToWindow()
        if window == nil { link?.invalidate(); link = nil }
    }
}

enum Motion {
    static func nearestAngle(current: CGFloat, target: CGFloat) -> CGFloat {
        var t = target
        while t - current > 180 { t -= 360 }
        while t - current < -180 { t += 360 }
        return t
    }

    static func radians(_ deg: CGFloat) -> CGFloat { deg * .pi / 180 }
}

/// 12x12 pixel-art icons (identical art to Android).
struct PixelIcon {
    let bits: [[Bool]]

    init(_ rows: [String]) {
        bits = rows.map { row in row.map { $0 == "#" } }
    }

    func draw(in ctx: CGContext, center: CGPoint, size: CGFloat, color: UIColor) {
        let n = CGFloat(bits.count)
        guard n > 0 else { return }
        let scale = UIScreen.mainScale
        // Snap pixel size to device pixels for crisp edges.
        let px = max(1 / scale, (size / n * scale).rounded(.down) / scale)
        let total = px * n
        let left = ((center.x - total / 2) * scale).rounded(.down) / scale
        let top = ((center.y - total / 2) * scale).rounded(.down) / scale
        ctx.setFillColor(color.cgColor)
        for (y, row) in bits.enumerated() {
            var x = 0
            while x < row.count {
                if !row[x] { x += 1; continue }
                var run = x
                while run < row.count && row[run] { run += 1 }
                ctx.fill(CGRect(x: left + CGFloat(x) * px, y: top + CGFloat(y) * px, width: CGFloat(run - x) * px, height: px))
                x = run
            }
        }
    }
}

extension UIScreen {
    /// Display scale without touching the deprecated `UIScreen.main` on new systems.
    static var mainScale: CGFloat {
        if let scene = UIApplication.shared.connectedScenes.first(where: { $0 is UIWindowScene }) as? UIWindowScene {
            return scene.screen.scale
        }
        return 3
    }
}

enum PixelIcons {
    static let menu = PixelIcon([
        "............", ".##########.", ".##########.", "............", "............", ".##########.",
        ".##########.", "............", "............", ".##########.", ".##########.", "............",
    ])
    static let camera = PixelIcon([
        "............", "...####.....", ".##########.", ".#........#.", ".#..####..#.", ".#.#....#.#.",
        ".#.#....#.#.", ".#..####..#.", ".#........#.", ".##########.", "............", "............",
    ])
    static let flash = PixelIcon([
        "......####..", ".....####...", "....####....", "...####.....", "..########..", "......###...",
        ".....###....", "....###.....", "...###......", "..##........", "..#.........", "............",
    ])
    static let flashOff = PixelIcon([
        "#.....####..", ".#...####...", "..#.####....", "...####.....", "..###.###...", "......###...",
        ".....###....", "....###.#...", "...###...#..", "..##......#.", "..#........#", "............",
    ])
    static let torch = PixelIcon([
        ".#...#...#..", "..#..#..#...", "............", "...#####....", "...#####....", "....###.....",
        "....###.....", "....#.#.....", "....###.....", "....###.....", "....###.....", "............",
    ])
    static let pixelBin = PixelIcon([
        "............", ".####..####.", ".####..####.", ".####..####.", ".####..####.", "............",
        "............", ".####..####.", ".####..####.", ".####..####.", ".####..####.", "............",
    ])
    static let pixelFull = PixelIcon([
        "............", ".##.##.##.#.", ".##.##.##.#.", "............", ".##.##.##.#.", ".##.##.##.#.",
        "............", ".##.##.##.#.", ".##.##.##.#.", "............", ".##.##.##.#.", "............",
    ])
    static let af = PixelIcon([
        "............", ".###....###.", ".#........#.", ".#........#.", ".....##.....", "....####....",
        "....####....", ".....##.....", ".#........#.", ".#........#.", ".###....###.", "............",
    ])
    static let mf = PixelIcon([
        "............", ".###....###.", ".#........#.", ".#........#.", "............", "..########..",
        "..#.#.#.#...", "............", ".#........#.", ".#........#.", ".###....###.", "............",
    ])
    static let aspect = PixelIcon([
        "............", ".##......##.", ".#........#.", "............", "...######...", "...#....#...",
        "...#....#...", "...######...", "............", ".#........#.", ".##......##.", "............",
    ])
    static let photo = PixelIcon([
        "............", "....###.....", ".##########.", ".##########.", ".###....###.", ".##..##..##.",
        ".##..##..##.", ".###....###.", ".##########.", ".##########.", "............", "............",
    ])
    static let video = PixelIcon([
        "............", "............", ".#######....", ".#######..#.", ".#######.##.", ".#########..",
        ".#########..", ".#######.##.", ".#######..#.", ".#######....", "............", "............",
    ])
    static let check = PixelIcon([
        "............", "............", "..........#.", ".........##.", "........##..", ".#.....##...",
        ".##...##....", "..##.##.....", "...###......", "....#.......", "............", "............",
    ])
    static let cross = PixelIcon([
        "............", ".##......##.", "..##....##..", "...##..##...", "....####....", ".....##.....",
        "....####....", "...##..##...", "..##....##..", ".##......##.", "............", "............",
    ])
    static let partial = PixelIcon([
        "............", "............", "............", "............", "............", ".##########.",
        ".##########.", "............", "............", "............", "............", "............",
    ])
}
