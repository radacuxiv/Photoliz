import UIKit

/// Small square camera button: pixel icon + LCD label; content rotates with the device.
final class ChipButton: AnimatedView {
    var palette: Palette { didSet { setNeedsDisplay() } }
    var icon: PixelIcon? { didSet { setNeedsDisplay() } }
    var onTap: (() -> Void)?

    private var labelText = ""
    private var oldLabel: String?
    private lazy var swap = spring(1, stiffness: 520, damping: 0.92)
    private lazy var press = spring(1, stiffness: 900, damping: 0.55)
    private lazy var activeAmt = spring(0, stiffness: 520, damping: 0.9)
    private lazy var rot = spring(0, stiffness: 260, damping: 0.78)

    var active = false { didSet { if active != oldValue { activeAmt.target = active ? 1 : 0; kick() } } }
    var dimmed = false { didSet { setNeedsDisplay() } }

    var label: String {
        get { labelText }
        set {
            guard newValue != labelText else { return }
            oldLabel = (labelText.isEmpty || window == nil) ? nil : labelText
            labelText = newValue
            if oldLabel != nil { swap.snap(0); swap.target = 1; kick() } else { setNeedsDisplay() }
        }
    }

    init(palette: Palette) {
        self.palette = palette
        super.init(frame: .zero)
        isAccessibilityElement = true
        accessibilityTraits = .button
    }

    required init?(coder: NSCoder) { fatalError("init(coder:) is not used") }

    func setContentRotation(_ deg: CGFloat) { rot.target = Motion.nearestAngle(current: rot.value, target: deg); kick() }

    override func touchesBegan(_ touches: Set<UITouch>, with event: UIEvent?) { press.target = 0.86; kick() }
    override func touchesCancelled(_ touches: Set<UITouch>, with event: UIEvent?) { press.target = 1; kick() }
    override func touchesEnded(_ touches: Set<UITouch>, with event: UIEvent?) {
        press.target = 1; kick()
        if let t = touches.first, bounds.insetBy(dx: -bounds.width * 0.25, dy: -bounds.height * 0.25).contains(t.location(in: self)) {
            Haptics.press()
            onTap?()
        }
    }

    override func draw(_ rect: CGRect) {
        guard let ctx = UIGraphicsGetCurrentContext() else { return }
        let w = bounds.width, h = bounds.height
        let c = CGPoint(x: w / 2, y: h / 2)
        let side = min(w, h)
        ctx.saveGState()
        ctx.translateBy(x: c.x, y: c.y)
        ctx.scaleBy(x: press.value, y: press.value)
        let a = activeAmt.value
        let box = CGRect(x: -side / 2 + 2, y: -side / 2 + 2, width: side - 4, height: side - 4)
        let path = UIBezierPath(roundedRect: box, cornerRadius: 10)
        palette.surface.mix(palette.accent, a).alpha(0.62 + 0.38 * a).setFill()
        path.fill()
        if a < 0.99 {
            palette.text.alpha(0.22 * (1 - a)).setStroke()
            path.lineWidth = 1.2
            path.stroke()
        }
        ctx.rotate(by: Motion.radians(rot.value))
        let fg = palette.text.mix(palette.onAccent, a)
        let alpha: CGFloat = dimmed ? 0.38 : 1
        let hasLabel = !labelText.isEmpty
        icon?.draw(in: ctx, center: CGPoint(x: 0, y: hasLabel ? -side * 0.12 : 0), size: side * 0.40, color: fg.alpha(alpha))
        if hasLabel {
            var font = Fonts.mono(9.5)
            let maxW = side - 8
            let longest = max(textWidth(labelText, font: font), oldLabel.map { textWidth($0, font: font) } ?? 0)
            if longest > maxW { font = font.withSize(font.pointSize * maxW / longest) }
            let y: CGFloat = icon != nil ? side * 0.30 : 0
            let t = swap.value
            if let ol = oldLabel, t < 1 {
                drawText(ol, font: font, color: fg.alpha(alpha * (1 - t)), center: CGPoint(x: 0, y: y - 8 * t))
            }
            drawText(labelText, font: font, color: fg.alpha(alpha * t), center: CGPoint(x: 0, y: y + 8 * (1 - t)))
            if t >= 1 { oldLabel = nil }
        }
        ctx.restoreGState()
    }
}

/// Appear / disappear with a quick scale-fade; hidden views leave no gap in the bars.
enum Reveal {
    static func set(_ v: UIView, shown: Bool, animated: Bool) {
        let isShown = !v.isHidden && v.tag != 1
        if shown == isShown { return }
        v.tag = shown ? 0 : 1
        v.layer.removeAllAnimations()
        if !animated || v.window == nil {
            v.isHidden = !shown
            v.alpha = 1
            v.transform = .identity
            v.superview?.setNeedsLayout()
            return
        }
        if shown {
            v.isHidden = false
            v.alpha = 0
            v.transform = CGAffineTransform(scaleX: 0.55, y: 0.55)
            v.superview?.setNeedsLayout()
            UIView.animate(withDuration: 0.36, delay: 0, usingSpringWithDamping: 0.62, initialSpringVelocity: 0, options: [.allowUserInteraction]) {
                v.alpha = 1
                v.transform = .identity
            }
        } else {
            UIView.animate(withDuration: 0.16, delay: 0, options: [.curveEaseIn, .allowUserInteraction], animations: {
                v.alpha = 0
                v.transform = CGAffineTransform(scaleX: 0.55, y: 0.55)
            }, completion: { _ in
                if v.tag == 1 {
                    v.isHidden = true
                    v.alpha = 1
                    v.transform = .identity
                    v.superview?.setNeedsLayout()
                }
            })
        }
    }

    /// Visible for layout purposes (a view that is fading out still counts until it is hidden).
    static func occupiesSlot(_ v: UIView) -> Bool { !v.isHidden }
}

/// Moves a view to a new center with the emphasized curve when it was already on screen.
func glide(_ v: UIView, to center: CGPoint) {
    if v.window == nil || v.bounds.width == 0 || v.center == .zero {
        v.center = center
        return
    }
    if abs(v.center.x - center.x) < 0.5 && abs(v.center.y - center.y) < 0.5 { return }
    UIView.animate(withDuration: 0.38, delay: 0, usingSpringWithDamping: 0.86, initialSpringVelocity: 0, options: [.allowUserInteraction, .beginFromCurrentState]) {
        v.center = center
    }
}

/// Row (or column) of equally spaced chips.
final class ChipBar: UIView {
    var vertical = false { didSet { setNeedsLayout() } }
    var chipSize: CGFloat = 50

    override func layoutSubviews() {
        super.layoutSubviews()
        let shown = subviews.filter { Reveal.occupiesSlot($0) }
        guard !shown.isEmpty else { return }
        let span = vertical ? bounds.height : bounds.width
        let slot = span / CGFloat(shown.count)
        for (i, v) in shown.enumerated() {
            v.bounds = CGRect(x: 0, y: 0, width: chipSize, height: chipSize)
            let pos = slot * (CGFloat(i) + 0.5)
            glide(v, to: vertical ? CGPoint(x: bounds.midX, y: pos) : CGPoint(x: pos, y: bounds.midY))
        }
    }
}

/// One exposure readout. Tap selects its dial, double tap returns to AUTO.
final class ParamCell: AnimatedView {
    let key: String
    private let title: String
    var palette: Palette { didSet { setNeedsDisplay() } }
    var onTap: ((String) -> Void)?
    var onDoubleTap: ((String) -> Void)?
    var readOnly = false { didSet { setNeedsDisplay() } }
    var chosen = false { didSet { if chosen != oldValue { sel.target = chosen ? 1 : 0; kick() } } }

    private var value = ""
    private var prevText: String?
    private var auto = false
    private lazy var swap = spring(1, stiffness: 560, damping: 0.9)
    private lazy var sel = spring(0, stiffness: 520, damping: 0.85)
    private lazy var rot = spring(0, stiffness: 260, damping: 0.78)
    private lazy var press = spring(1, stiffness: 900, damping: 0.6)

    init(key: String, title: String, palette: Palette) {
        self.key = key
        self.title = title
        self.palette = palette
        super.init(frame: .zero)
        let single = UITapGestureRecognizer(target: self, action: #selector(tapped))
        let double = UITapGestureRecognizer(target: self, action: #selector(doubleTapped))
        double.numberOfTapsRequired = 2
        single.require(toFail: double)
        addGestureRecognizer(single)
        addGestureRecognizer(double)
    }

    required init?(coder: NSCoder) { fatalError("init(coder:) is not used") }

    @objc private func tapped() { guard !readOnly else { return }; Haptics.press(); onTap?(key) }
    @objc private func doubleTapped() { guard !readOnly else { return }; Haptics.confirm(); onDoubleTap?(key) }

    override func touchesBegan(_ touches: Set<UITouch>, with event: UIEvent?) { super.touchesBegan(touches, with: event); if !readOnly { press.target = 0.93; kick() } }
    override func touchesEnded(_ touches: Set<UITouch>, with event: UIEvent?) { super.touchesEnded(touches, with: event); press.target = 1; kick() }
    override func touchesCancelled(_ touches: Set<UITouch>, with event: UIEvent?) { super.touchesCancelled(touches, with: event); press.target = 1; kick() }

    func setValue(_ text: String, auto isAuto: Bool) {
        guard text != value || isAuto != auto else { return }
        if text != value && !value.isEmpty && window != nil {
            prevText = value
            swap.snap(0); swap.target = 1
        }
        value = text
        auto = isAuto
        kick()
    }

    func setContentRotation(_ deg: CGFloat) { rot.target = Motion.nearestAngle(current: rot.value, target: deg); kick() }

    override func draw(_ rect: CGRect) {
        guard let ctx = UIGraphicsGetCurrentContext() else { return }
        let w = bounds.width, h = bounds.height
        ctx.saveGState()
        ctx.translateBy(x: w / 2, y: h / 2)
        ctx.scaleBy(x: press.value, y: press.value)
        let s = sel.value
        if s > 0.01 {
            palette.accent.alpha(0.92 * s).setFill()
            UIBezierPath(roundedRect: CGRect(x: -w / 2 + 4, y: -h / 2 + 4, width: w - 8, height: h - 8), cornerRadius: 8).fill()
        }
        ctx.rotate(by: Motion.radians(rot.value))
        let sideways = Int((rot.value / 90).rounded()) % 2 != 0
        let boxH = sideways ? w : h
        let maxW = (sideways ? h : w) - 10
        let fg = palette.text.mix(palette.onAccent, s)
        drawText(title, font: Fonts.label(10), color: fg.alpha(readOnly ? 0.45 : 0.7), center: CGPoint(x: 0, y: -boxH * 0.19), kern: 1.2)
        var font = Fonts.mono(15)
        let shown = auto ? "A " + value : value
        let mw = textWidth(shown, font: font)
        if mw > maxW { font = font.withSize(font.pointSize * maxW / mw) }
        let y = boxH * 0.14
        let t = swap.value
        let dim: CGFloat = readOnly ? 0.6 : 1
        if let ov = prevText, t < 1 {
            drawText(auto ? "A " + ov : ov, font: font, color: fg.alpha((1 - t) * dim), center: CGPoint(x: 0, y: y - 10 * t))
        }
        let cy = y + 10 * (1 - t)
        if auto {
            let full = textWidth(shown, font: font)
            let aw = textWidth("A ", font: font)
            let startX = -full / 2
            let bw = textWidth("A", font: font)
            fg.alpha(0.9 * t).setStroke()
            let box = UIBezierPath(rect: CGRect(x: startX - 2, y: cy - font.lineHeight * 0.42, width: bw + 4, height: font.lineHeight * 0.84))
            box.lineWidth = 1.2
            box.stroke()
            drawText("A", font: font, color: fg.alpha(t * dim), at: CGPoint(x: startX, y: cy), align: .left)
            drawText(value, font: font, color: fg.alpha(t * dim), at: CGPoint(x: startX + aw, y: cy), align: .left)
        } else {
            drawText(value, font: font, color: fg.alpha(t * dim), center: CGPoint(x: 0, y: cy))
        }
        if t >= 1 { prevText = nil }
        ctx.restoreGState()
    }
}

/// Evenly spaced exposure readouts over a soft scrim.
final class ParamBar: UIView {
    var vertical = false { didSet { setNeedsLayout(); updateScrim() } }
    var scrimColor: UIColor = .clear { didSet { updateScrim() } }
    private let gradient = CAGradientLayer()

    override init(frame: CGRect) {
        super.init(frame: frame)
        layer.insertSublayer(gradient, at: 0)
    }

    required init?(coder: NSCoder) { fatalError("init(coder:) is not used") }

    private func updateScrim() {
        gradient.colors = [scrimColor.alpha(0).cgColor, scrimColor.cgColor]
        gradient.startPoint = vertical ? CGPoint(x: 0, y: 0.5) : CGPoint(x: 0.5, y: 0)
        gradient.endPoint = vertical ? CGPoint(x: 1, y: 0.5) : CGPoint(x: 0.5, y: 1)
    }

    override func layoutSubviews() {
        super.layoutSubviews()
        CATransaction.begin()
        CATransaction.setDisableActions(true)
        gradient.frame = bounds
        CATransaction.commit()
        let shown = subviews.filter { Reveal.occupiesSlot($0) }
        guard !shown.isEmpty else { return }
        let n = CGFloat(shown.count)
        let cw = vertical ? bounds.width : min(bounds.width / n, bounds.height * 2.6)
        let ch = vertical ? min(bounds.height / n, bounds.width * 0.9) : bounds.height
        let slot = (vertical ? bounds.height : bounds.width) / n
        for (i, v) in shown.enumerated() {
            v.bounds = CGRect(x: 0, y: 0, width: cw, height: ch)
            let p = slot * (CGFloat(i) + 0.5)
            glide(v, to: vertical ? CGPoint(x: bounds.midX, y: p) : CGPoint(x: p, y: bounds.midY))
        }
    }
}
