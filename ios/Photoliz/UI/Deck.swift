import UIKit

/// Tap = one frame, hold = burst (photo). In video mode a tap starts / stops recording.
final class ShutterButton: AnimatedView {
    var palette: Palette { didSet { setNeedsDisplay() } }
    var onShoot: (() -> Void)?
    var onBurstStart: (() -> Void)?
    var onBurstEnd: (() -> Void)?

    private lazy var press = spring(1, stiffness: 900, damping: 0.55)
    private lazy var videoAmt = spring(0, stiffness: 380, damping: 0.85)
    private lazy var recAmt = spring(0, stiffness: 420, damping: 0.8)
    private lazy var burstAmt = spring(0, stiffness: 420, damping: 0.8)

    var video = false { didSet { if video != oldValue { videoAmt.target = video ? 1 : 0; kick() } } }
    var recording = false { didSet { if recording != oldValue { recAmt.target = recording ? 1 : 0; kick() } } }
    var busy = false { didSet { if busy != oldValue { kick() } } }
    var burstCount = 0 { didSet { setNeedsDisplay() } }

    private var bursting = false
    private var downInside = false
    private var burstTimer: Timer?

    init(palette: Palette) {
        self.palette = palette
        super.init(frame: .zero)
        isAccessibilityElement = true
        accessibilityLabel = "Shutter"
        accessibilityTraits = .button
    }

    required init?(coder: NSCoder) { fatalError("init(coder:) is not used") }

    override var keepAnimating: Bool { busy || bursting }

    override func point(inside point: CGPoint, with event: UIEvent?) -> Bool {
        let r = min(bounds.width, bounds.height) / 2
        let dx = point.x - bounds.midX, dy = point.y - bounds.midY
        return dx * dx + dy * dy <= r * r * 1.6
    }

    override func touchesBegan(_ touches: Set<UITouch>, with event: UIEvent?) {
        downInside = true
        press.target = 0.88
        kick()
        burstTimer?.invalidate()
        burstTimer = Timer.scheduledTimer(withTimeInterval: 0.38, repeats: false) { [weak self] _ in
            guard let self = self, !self.video, self.downInside else { return }
            self.bursting = true
            self.burstAmt.target = 1
            self.kick()
            Haptics.confirm()
            self.onBurstStart?()
        }
    }

    override func touchesMoved(_ touches: Set<UITouch>, with event: UIEvent?) {
        guard let t = touches.first, !bursting else { return }
        if !self.point(inside: t.location(in: self), with: event) {
            downInside = false
            burstTimer?.invalidate()
            press.target = 1
            kick()
        }
    }

    override func touchesEnded(_ touches: Set<UITouch>, with event: UIEvent?) {
        burstTimer?.invalidate()
        press.target = 1
        kick()
        if bursting {
            bursting = false
            burstAmt.target = 0
            onBurstEnd?()
        } else if downInside {
            Haptics.press()
            onShoot?()
        }
        downInside = false
    }

    override func touchesCancelled(_ touches: Set<UITouch>, with event: UIEvent?) {
        burstTimer?.invalidate()
        press.target = 1
        kick()
        if bursting { bursting = false; burstAmt.target = 0; onBurstEnd?() }
        downInside = false
    }

    /// Hardware buttons (Camera Control / volume) path.
    func externalBurst(_ on: Bool) {
        if on && !video && !bursting { bursting = true; burstAmt.target = 1; kick(); onBurstStart?() }
        if !on && bursting { bursting = false; burstAmt.target = 0; kick(); onBurstEnd?() }
    }

    override func draw(_ rect: CGRect) {
        guard let ctx = UIGraphicsGetCurrentContext() else { return }
        let c = CGPoint(x: bounds.midX, y: bounds.midY)
        let r = min(bounds.width, bounds.height) / 2 - 2
        ctx.setStrokeColor(palette.text.cgColor)
        ctx.setLineWidth(3.5)
        ctx.strokeEllipse(in: CGRect(x: c.x - r + 1.75, y: c.y - r + 1.75, width: (r - 1.75) * 2, height: (r - 1.75) * 2))
        let v = videoAmt.value, rec = recAmt.value
        let inner = (r - 8) * press.value
        let dot = inner * (1 - 0.42 * v)
        let sq = inner * 0.52
        let half = dot + (sq - dot) * rec
        let corner = half * (1 - 0.7 * rec)
        palette.body.mix(palette.alert, v).setFill()
        UIBezierPath(roundedRect: CGRect(x: c.x - half, y: c.y - half, width: half * 2, height: half * 2), cornerRadius: corner).fill()
        if busy || burstAmt.value > 0.01 {
            let t = CGFloat(CACurrentMediaTime().truncatingRemainder(dividingBy: 0.9) / 0.9)
            let start = Motion.radians(t * 360 - 90)
            let sweep = Motion.radians(burstAmt.value > 0.01 ? 300 * burstAmt.value : 80)
            let arc = UIBezierPath(arcCenter: c, radius: r - 1.75, startAngle: start, endAngle: start + sweep, clockwise: true)
            arc.lineWidth = 3.5
            arc.lineCapStyle = .round
            palette.accent.setStroke()
            arc.stroke()
        }
        if burstCount > 0 && burstAmt.value > 0.01 {
            drawText("\(burstCount)", font: Fonts.mono(16), color: palette.onBody.alpha(burstAmt.value), center: c)
        }
    }
}

/// Focus and zoom sliders curved around the shutter. 0...1 along each arc; clockwise always increases.
final class ArcControls: AnimatedView {
    final class Arc {
        let key: String
        var start: CGFloat = 0
        var end: CGFloat = 0
        var value: CGFloat = 0
        var enabled = true
        var startLabel = ""
        var endLabel = ""
        var bubble: (CGFloat) -> String = { _ in "" }
        var detents: [CGFloat] = []
        init(_ key: String) { self.key = key }
    }

    var palette: Palette { didSet { setNeedsDisplay() } }
    let focus = Arc("focus")
    let zoom = Arc("zoom")
    var center0: CGPoint = .zero
    var radius: CGFloat = 0
    var onChange: ((Arc, CGFloat, Bool) -> Void)?

    private lazy var focusShown = spring(0, stiffness: 300, damping: 0.9)
    private lazy var zoomShown = spring(0, stiffness: 300, damping: 0.9)
    private lazy var bubbleAmt = spring(0, stiffness: 500, damping: 0.9)
    private lazy var labelRot = spring(0, stiffness: 260, damping: 0.78)
    private var active: Arc?
    private var bubbleArc: Arc?

    init(palette: Palette) {
        self.palette = palette
        super.init(frame: .zero)
    }

    required init?(coder: NSCoder) { fatalError("init(coder:) is not used") }

    func setShown(focus f: Bool, zoom z: Bool, animated: Bool) {
        if animated { focusShown.target = f ? 1 : 0; zoomShown.target = z ? 1 : 0; kick() }
        else { focusShown.snap(f ? 1 : 0); zoomShown.snap(z ? 1 : 0); setNeedsDisplay() }
    }

    func setContentRotation(_ deg: CGFloat) { labelRot.target = Motion.nearestAngle(current: labelRot.value, target: deg); kick() }

    func setValue(_ a: Arc, _ v: CGFloat) {
        if active === a { return }
        a.value = min(max(v, 0), 1)
        setNeedsDisplay()
    }

    private func shown(_ a: Arc) -> CGFloat { a === focus ? focusShown.value : zoomShown.value }

    private func point(_ deg: CGFloat, _ r: CGFloat) -> CGPoint {
        let rad = Motion.radians(deg)
        return CGPoint(x: center0.x + r * cos(rad), y: center0.y - r * sin(rad))
    }

    private func delta(_ from: CGFloat, _ to: CGFloat) -> CGFloat {
        var d = (to - from).truncatingRemainder(dividingBy: 360)
        if d > 180 { d -= 360 }
        if d <= -180 { d += 360 }
        return d
    }

    private func angle(of p: CGPoint) -> CGFloat {
        atan2(-(p.y - center0.y), p.x - center0.x) * 180 / .pi
    }

    private func hit(_ p: CGPoint) -> Arc? {
        let r = hypot(p.x - center0.x, p.y - center0.y)
        guard abs(r - radius) <= 30 else { return nil }
        let deg = angle(of: p)
        for a in [focus, zoom] where shown(a) >= 0.5 && a.enabled {
            let span = delta(a.start, a.end)
            if abs(span) < 1 { continue }
            let t = delta(a.start, deg) / span
            let margin = 9 / abs(span)
            if t >= -margin && t <= 1 + margin { return a }
        }
        return nil
    }

    override func point(inside point: CGPoint, with event: UIEvent?) -> Bool { hit(point) != nil }

    override func touchesBegan(_ touches: Set<UITouch>, with event: UIEvent?) {
        guard let t = touches.first, let a = hit(t.location(in: self)) else { return }
        active = a
        bubbleArc = a
        bubbleAmt.target = 1
        update(a, t.location(in: self), done: false)
    }

    override func touchesMoved(_ touches: Set<UITouch>, with event: UIEvent?) {
        guard let t = touches.first, let a = active else { return }
        update(a, t.location(in: self), done: false)
    }

    override func touchesEnded(_ touches: Set<UITouch>, with event: UIEvent?) {
        // Release first so the listener may move the knob (setValue ignores the arc being dragged).
        let a = active
        active = nil
        if let t = touches.first, let a = a { update(a, t.location(in: self), done: true) }
        bubbleAmt.target = 0
        kick()
    }

    override func touchesCancelled(_ touches: Set<UITouch>, with event: UIEvent?) { touchesEnded(touches, with: event) }

    private func update(_ a: Arc, _ p: CGPoint, done: Bool) {
        let span = delta(a.start, a.end)
        guard abs(span) > 0.001 else { return }
        let t = min(max(delta(a.start, angle(of: p)) / span, 0), 1)
        let old = a.value
        if a.detents.contains(where: { (old - $0) * (t - $0) < 0 || (t == $0 && old != $0) }) { Haptics.tick() }
        a.value = t
        onChange?(a, t, done)
        kick()
    }

    override func draw(_ rect: CGRect) {
        guard radius > 0, let ctx = UIGraphicsGetCurrentContext() else { return }
        drawArc(ctx, focus, focusShown.value)
        drawArc(ctx, zoom, zoomShown.value)
    }

    private func drawArc(_ ctx: CGContext, _ a: Arc, _ shownAmt: CGFloat) {
        guard shownAmt > 0.01 else { return }
        let span = delta(a.start, a.end)
        func arcPath(to t: CGFloat, width: CGFloat) -> UIBezierPath {
            // UIKit angles are clockwise from +x with y down: negate the math angle.
            let s = -Motion.radians(a.start)
            let e = -Motion.radians(a.start + span * t)
            let p = UIBezierPath(arcCenter: center0, radius: radius, startAngle: s, endAngle: e, clockwise: span < 0)
            p.lineWidth = width
            p.lineCapStyle = .round
            return p
        }
        palette.text.alpha((a.enabled ? 0.32 : 0.14) * shownAmt).setStroke()
        arcPath(to: shownAmt, width: 2).stroke()
        if a.enabled {
            palette.accent.alpha(0.95 * shownAmt).setStroke()
            arcPath(to: min(a.value, shownAmt), width: 3).stroke()
        }
        ctx.setLineCap(.round)
        ctx.setLineWidth(1.2)
        for i in 0...10 {
            let tt = CGFloat(i) / 10
            if tt > shownAmt + 0.001 { break }
            let major = i == 0 || i == 5 || i == 10
            let deg = a.start + span * tt
            let p0 = point(deg, radius + 7), p1 = point(deg, radius + (major ? 13 : 10))
            ctx.setStrokeColor(palette.text.alpha((major ? 0.6 : 0.3) * shownAmt).cgColor)
            ctx.move(to: p0); ctx.addLine(to: p1); ctx.strokePath()
        }
        let small = Fonts.mono(9)
        labelAt(a.startLabel, deg: a.start, r: radius + 22, font: small, color: palette.textDim.alpha(0.8 * shownAmt * shownAmt))
        if shownAmt > 0.98 { labelAt(a.endLabel, deg: a.end, r: radius + 22, font: small, color: palette.textDim.alpha(0.8)) }
        guard a.enabled else { return }
        let knobDeg = a.start + span * min(a.value, shownAmt)
        let k = point(knobDeg, radius)
        let kr: CGFloat = active === a ? 11 : 9
        let knob = UIBezierPath(ovalIn: CGRect(x: k.x - kr, y: k.y - kr, width: kr * 2, height: kr * 2))
        palette.body.alpha(shownAmt).setFill()
        knob.fill()
        palette.text.alpha(shownAmt).setStroke()
        knob.lineWidth = 2
        knob.stroke()
        if bubbleArc === a && bubbleAmt.value > 0.01 {
            let text = a.bubble(a.value)
            guard !text.isEmpty else { return }
            let b = point(knobDeg, radius + 36)
            let font = Fonts.mono(12)
            let tw = textWidth(text, font: font) + 12
            let th: CGFloat = 22
            ctx.saveGState()
            ctx.translateBy(x: b.x, y: b.y)
            ctx.rotate(by: Motion.radians(labelRot.value))
            ctx.scaleBy(x: bubbleAmt.value, y: bubbleAmt.value)
            palette.accent.setFill()
            UIBezierPath(roundedRect: CGRect(x: -tw / 2, y: -th / 2, width: tw, height: th), cornerRadius: th / 2).fill()
            drawText(text, font: font, color: palette.onAccent, center: .zero)
            ctx.restoreGState()
        }
    }

    private func labelAt(_ s: String, deg: CGFloat, r: CGFloat, font: UIFont, color: UIColor) {
        guard !s.isEmpty, let ctx = UIGraphicsGetCurrentContext() else { return }
        let p = point(deg, r)
        ctx.saveGState()
        ctx.translateBy(x: p.x, y: p.y)
        ctx.rotate(by: Motion.radians(labelRot.value))
        drawText(s, font: font, color: color, center: .zero)
        ctx.restoreGState()
    }
}

/// Last photo; pops in after every save.
final class ThumbView: AnimatedView {
    var palette: Palette { didSet { setNeedsDisplay() } }
    var onTap: (() -> Void)?
    private var image: UIImage?
    private lazy var pop = spring(1, stiffness: 520, damping: 0.62)
    private lazy var press = spring(1, stiffness: 900, damping: 0.6)
    private lazy var rot = spring(0, stiffness: 260, damping: 0.78)

    init(palette: Palette) {
        self.palette = palette
        super.init(frame: .zero)
    }

    required init?(coder: NSCoder) { fatalError("init(coder:) is not used") }

    func show(_ img: UIImage?) {
        image = img
        pop.snap(0.3)
        pop.target = 1
        kick()
    }

    func setContentRotation(_ deg: CGFloat) { rot.target = Motion.nearestAngle(current: rot.value, target: deg); kick() }

    override func touchesBegan(_ touches: Set<UITouch>, with event: UIEvent?) { press.target = 0.88; kick() }
    override func touchesCancelled(_ touches: Set<UITouch>, with event: UIEvent?) { press.target = 1; kick() }
    override func touchesEnded(_ touches: Set<UITouch>, with event: UIEvent?) {
        press.target = 1; kick()
        if image != nil { Haptics.press(); onTap?() }
    }

    override func draw(_ rect: CGRect) {
        guard let ctx = UIGraphicsGetCurrentContext() else { return }
        let side = min(bounds.width, bounds.height) - 6
        ctx.saveGState()
        ctx.translateBy(x: bounds.midX, y: bounds.midY)
        let s = pop.value * press.value
        ctx.scaleBy(x: s, y: s)
        ctx.rotate(by: Motion.radians(rot.value))
        let box = CGRect(x: -side / 2, y: -side / 2, width: side, height: side)
        let path = UIBezierPath(roundedRect: box, cornerRadius: 8)
        if let img = image {
            ctx.saveGState()
            path.addClip()
            let sc = max(side / img.size.width, side / img.size.height)
            let sz = CGSize(width: img.size.width * sc, height: img.size.height * sc)
            img.draw(in: CGRect(x: -sz.width / 2, y: -sz.height / 2, width: sz.width, height: sz.height))
            ctx.restoreGState()
        } else {
            palette.surface.alpha(0.7).setFill()
            path.fill()
        }
        palette.text.alpha(0.5).setStroke()
        path.lineWidth = 1.5
        path.stroke()
        ctx.restoreGState()
    }
}

/// Bottom (portrait) / right (landscape) deck. Laid out in a portrait frame and rotated for landscape.
final class ShutterDeck: UIView {
    let arcs: ArcControls
    let shutter: ShutterButton
    let modeChip: ChipButton
    let afChip: ChipButton
    let aspectChip: ChipButton
    let thumb: ThumbView

    var vertical = false { didSet { if vertical != oldValue { setNeedsLayout() } } }
    private(set) var focusArc = true
    private(set) var zoomArc = false

    let shutterSize: CGFloat = 78
    let chip: CGFloat = 50

    init(palette: Palette) {
        arcs = ArcControls(palette: palette)
        shutter = ShutterButton(palette: palette)
        modeChip = ChipButton(palette: palette)
        afChip = ChipButton(palette: palette)
        aspectChip = ChipButton(palette: palette)
        thumb = ThumbView(palette: palette)
        super.init(frame: .zero)
        for v in [arcs, modeChip, afChip, aspectChip, thumb, shutter] as [UIView] { addSubview(v) }
    }

    required init?(coder: NSCoder) { fatalError("init(coder:) is not used") }

    func setArcs(focus f: Bool, zoom z: Bool, animated: Bool) {
        guard f != focusArc || z != zoomArc else { return }
        focusArc = f
        zoomArc = z
        arcs.setShown(focus: f, zoom: z, animated: animated)
        setNeedsLayout()
        if animated { UIView.animate(withDuration: 0.38, delay: 0, usingSpringWithDamping: 0.86, initialSpringVelocity: 0, options: [.allowUserInteraction], animations: { self.layoutIfNeeded() }) }
    }

    var preferredThickness: CGFloat { (focusArc || zoomArc) ? 200 : 148 }

    override func layoutSubviews() {
        super.layoutSubviews()
        let w = bounds.width, h = bounds.height
        let vw = vertical ? h : w
        let vh = vertical ? w : h
        let anyArc = focusArc || zoomArc
        let half = shutterSize / 2
        let radius = half + 34
        let vcx = vw / 2
        let vcy = anyArc ? vh - half - 16 : vh / 2
        let chipY = anyArc ? vcy + 22 : vcy
        let edge = 14 + chip / 2
        let inner = anyArc ? radius + 6 : max(vw / 5, half + chip / 2 + 14)

        func map(_ px: CGFloat, _ py: CGFloat) -> CGPoint { vertical ? CGPoint(x: py, y: vw - px) : CGPoint(x: px, y: py) }
        func place(_ v: UIView, _ px: CGFloat, _ py: CGFloat, _ size: CGFloat) {
            v.bounds = CGRect(x: 0, y: 0, width: size, height: size)
            glide(v, to: map(px, py))
        }
        arcs.frame = bounds
        place(shutter, vcx, vcy, shutterSize)
        place(afChip, vcx - inner, chipY, chip)
        place(aspectChip, vcx + inner, chipY, chip)
        place(modeChip, max(min(edge, vcx - inner - chip - 4), chip / 2), chipY, chip)
        place(thumb, min(max(vw - edge, vcx + inner + chip + 4), vw - chip / 2), chipY, chip)

        arcs.center0 = map(vcx, vcy)
        arcs.radius = radius
        let rot: CGFloat = vertical ? 90 : 0
        if focusArc && zoomArc {
            arcs.focus.start = 172 + rot; arcs.focus.end = 100 + rot
            arcs.zoom.start = 80 + rot; arcs.zoom.end = 8 + rot
        } else {
            arcs.focus.start = 165 + rot; arcs.focus.end = 15 + rot
            arcs.zoom.start = 165 + rot; arcs.zoom.end = 15 + rot
        }
        arcs.setNeedsDisplay()
    }
}
