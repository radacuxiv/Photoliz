import UIKit

struct DialStop {
    let pos: CGFloat
    let label: String?
    let major: Bool
}

/// A ruler model. Discrete dials snap to stops; continuous dials (ISO) glide and snap magnetically.
struct DialModel {
    let key: String
    let stops: [DialStop]
    let continuous: Bool
    let autoPos: CGFloat?
    let unit: CGFloat
    let snapThreshold: CGFloat

    var minPos: CGFloat { autoPos ?? stops.first?.pos ?? 0 }
    var maxPos: CGFloat { stops.last?.pos ?? 0 }

    func settle(_ projected: CGFloat) -> CGFloat {
        let p = min(max(projected, minPos), maxPos)
        guard let first = stops.first else { return autoPos ?? 0 }
        if let a = autoPos, p < (a + first.pos) / 2 { return a }
        guard let nearest = stops.min(by: { abs($0.pos - p) < abs($1.pos - p) }) else { return p }
        return (!continuous || abs(nearest.pos - p) <= snapThreshold) ? nearest.pos : p
    }

    func isAuto(_ p: CGFloat) -> Bool {
        guard let a = autoPos, let first = stops.first else { return false }
        return p < (a + first.pos) / 2
    }

    func detent(_ p: CGFloat) -> Int {
        guard let first = stops.first else { return -1 }
        if let a = autoPos, p < (a + first.pos) / 2 { return -1 }
        var best = 0
        var bestD = CGFloat.greatestFiniteMagnitude
        for (i, s) in stops.enumerated() where abs(s.pos - p) < bestD { bestD = abs(s.pos - p); best = i }
        if continuous { return bestD <= snapThreshold * 0.5 ? best : -2 }
        return best
    }
}

final class DialStrip: AnimatedView {
    var palette: Palette { didSet { setNeedsDisplay() } }
    var vertical = false { didSet { setNeedsDisplay() } }
    private(set) var model: DialModel?
    /// (position, settled)
    var onScrub: ((CGFloat, Bool) -> Void)?

    private lazy var pos = spring(0, stiffness: 260, damping: 1.0)
    private lazy var labelRot = spring(0, stiffness: 260, damping: 0.78)
    private(set) var isDragging = false
    private var settling = false
    private var lastTouch: CGFloat = 0
    private var downTouch: CGFloat = 0
    private var moved = false
    private var lastDetent = Int.min
    private var samples: [(CGFloat, CFTimeInterval)] = []

    init(palette: Palette) {
        self.palette = palette
        super.init(frame: .zero)
    }

    required init?(coder: NSCoder) { fatalError("init(coder:) is not used") }

    func setModel(_ m: DialModel?, position: CGFloat) {
        model = m
        if let m = m { pos.snap(min(max(position, m.minPos), m.maxPos)) } else { pos.snap(0) }
        lastDetent = m?.detent(pos.value) ?? Int.min
        settling = false
        setNeedsDisplay()
    }

    func move(to position: CGFloat) {
        guard let m = model, !isDragging else { return }
        settling = false
        pos.target = min(max(position, m.minPos), m.maxPos)
        kick()
    }

    func setContentRotation(_ deg: CGFloat) { labelRot.target = Motion.nearestAngle(current: labelRot.value, target: deg); kick() }

    private func coord(_ t: UITouch) -> CGFloat { vertical ? t.location(in: self).y : t.location(in: self).x }

    override func touchesBegan(_ touches: Set<UITouch>, with event: UIEvent?) {
        guard model != nil, let t = touches.first else { return }
        isDragging = true
        settling = false
        moved = false
        lastTouch = coord(t)
        downTouch = lastTouch
        samples = [(lastTouch, t.timestamp)]
        pos.snap(pos.value)
    }

    override func touchesMoved(_ touches: Set<UITouch>, with event: UIEvent?) {
        guard let m = model, let t = touches.first else { return }
        let c = coord(t)
        let d = c - lastTouch
        lastTouch = c
        if abs(c - downTouch) > 4 { moved = true }
        samples.append((c, t.timestamp))
        if samples.count > 6 { samples.removeFirst() }
        var next = pos.value - d / m.unit
        if next < m.minPos { next = m.minPos - (m.minPos - next) * 0.35 }
        if next > m.maxPos { next = m.maxPos + (next - m.maxPos) * 0.35 }
        pos.snap(next)
        reportDetent()
        onScrub?(min(max(next, m.minPos), m.maxPos), false)
        setNeedsDisplay()
    }

    override func touchesEnded(_ touches: Set<UITouch>, with event: UIEvent?) { finish(touches.first, cancelled: false) }
    override func touchesCancelled(_ touches: Set<UITouch>, with event: UIEvent?) { finish(touches.first, cancelled: true) }

    private func finish(_ t: UITouch?, cancelled: Bool) {
        guard let m = model else { return }
        isDragging = false
        var target: CGFloat
        if !moved && !cancelled, let t = t {
            let center = vertical ? bounds.height / 2 : bounds.width / 2
            target = m.settle(pos.value + (coord(t) - center) / m.unit)
        } else {
            var v: CGFloat = 0
            if let a = samples.first, let b = samples.last, b.1 > a.1 { v = -(b.0 - a.0) / CGFloat(b.1 - a.1) / m.unit }
            target = m.settle(pos.value + v * 0.16)
        }
        pos.target = target
        settling = true
        kick()
    }

    private func reportDetent() {
        guard let m = model else { return }
        let d = m.detent(pos.value)
        if d != lastDetent && d != -2 { Haptics.tick() }
        lastDetent = d
    }

    override func draw(_ rect: CGRect) {
        guard let m = model, let ctx = UIGraphicsGetCurrentContext() else { return }
        if settling {
            reportDetent()
            if !pos.isMoving {
                settling = false
                let p = pos.value
                DispatchQueue.main.async { [weak self] in self?.onScrub?(p, true) }
            } else {
                let p = min(max(pos.value, m.minPos), m.maxPos)
                DispatchQueue.main.async { [weak self] in self?.onScrub?(p, false) }
            }
        }
        let w = bounds.width, h = bounds.height
        let along = vertical ? h : w
        let across = vertical ? w : h
        let center = along / 2

        // Scrim
        let colors = [palette.deep.alpha(0).cgColor, palette.deep.alpha(0.78).cgColor] as CFArray
        if let g = CGGradient(colorsSpace: CGColorSpaceCreateDeviceRGB(), colors: colors, locations: [0, 1]) {
            if vertical { ctx.drawLinearGradient(g, start: CGPoint(x: w, y: 0), end: CGPoint(x: 0, y: 0), options: []) }
            else { ctx.drawLinearGradient(g, start: .zero, end: CGPoint(x: 0, y: h), options: []) }
        }
        let p = pos.value
        let base = across * 0.62
        func at(_ sp: CGFloat) -> CGFloat { center + (sp - p) * m.unit }
        func fade(_ x: CGFloat) -> CGFloat {
            let v = min(max(1 - abs(x - center) / (along * 0.5), 0), 1)
            return v * v * 0.85 + 0.15
        }
        let font = Fonts.mono(10.5)
        if let a = m.autoPos {
            let x = at(a)
            if x > -m.unit * 3 && x < along + m.unit * 3 {
                let selected = m.settle(p) == a
                label("AUTO", along: x, acrossPos: base - 14, font: font, color: (selected ? palette.text : palette.textDim).alpha(fade(x)))
                tick(ctx, along: x, from: base - 6, to: base + 8, width: 3, color: palette.accent.alpha(fade(x)))
            }
        }
        for s in m.stops {
            let x = at(s.pos)
            if x < -m.unit * 2 || x > along + m.unit * 2 { continue }
            let a = fade(x)
            let len: CGFloat = s.major ? 12 : 6
            tick(ctx, along: x, from: base - len / 2 + 2, to: base + len / 2 + 2, width: s.major ? 2 : 1.2, color: palette.text.alpha(a))
            if let l = s.label, s.major { label(l, along: x, acrossPos: base - 14, font: font, color: palette.text.alpha(a)) }
        }
        // Center marker
        palette.accent.setFill()
        let mw: CGFloat = 6
        let tri = UIBezierPath()
        if vertical {
            tri.move(to: CGPoint(x: w, y: center - mw)); tri.addLine(to: CGPoint(x: w - mw * 1.4, y: center)); tri.addLine(to: CGPoint(x: w, y: center + mw))
            tri.close(); tri.fill()
            palette.accent.alpha(0.47).setFill()
            UIRectFill(CGRect(x: 0, y: center - 1, width: w - mw, height: 2))
        } else {
            tri.move(to: CGPoint(x: center - mw, y: h)); tri.addLine(to: CGPoint(x: center, y: h - mw * 1.4)); tri.addLine(to: CGPoint(x: center + mw, y: h))
            tri.close(); tri.fill()
            palette.accent.alpha(0.47).setFill()
            UIRectFill(CGRect(x: center - 1, y: 0, width: 2, height: h - mw))
        }
    }

    private func tick(_ ctx: CGContext, along: CGFloat, from a0: CGFloat, to a1: CGFloat, width: CGFloat, color: UIColor) {
        ctx.setStrokeColor(color.cgColor)
        ctx.setLineWidth(width)
        ctx.setLineCap(.round)
        if vertical {
            ctx.move(to: CGPoint(x: bounds.width - a0, y: along)); ctx.addLine(to: CGPoint(x: bounds.width - a1, y: along))
        } else {
            ctx.move(to: CGPoint(x: along, y: a0)); ctx.addLine(to: CGPoint(x: along, y: a1))
        }
        ctx.strokePath()
    }

    private func label(_ text: String, along: CGFloat, acrossPos: CGFloat, font: UIFont, color: UIColor) {
        guard let ctx = UIGraphicsGetCurrentContext() else { return }
        let pt = vertical ? CGPoint(x: bounds.width - acrossPos, y: along) : CGPoint(x: along, y: acrossPos)
        ctx.saveGState()
        ctx.translateBy(x: pt.x, y: pt.y)
        ctx.rotate(by: Motion.radians(labelRot.value))
        drawText(text, font: font, color: color, center: .zero)
        ctx.restoreGState()
    }
}
