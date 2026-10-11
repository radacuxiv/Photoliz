import UIKit

/// Transient one-line message near the top of the viewfinder.
final class Toaster: AnimatedView {
    var palette: Palette { didSet { setNeedsDisplay() } }
    var anchorY: CGFloat = 0
    private var message = ""
    private lazy var amt = spring(0, stiffness: 520, damping: 0.8)
    private lazy var rot = spring(0, stiffness: 260, damping: 0.78)
    private var hideWork: DispatchWorkItem?

    init(palette: Palette) {
        self.palette = palette
        super.init(frame: .zero)
        isUserInteractionEnabled = false
    }

    required init?(coder: NSCoder) { fatalError("init(coder:) is not used") }

    func show(_ msg: String, seconds: Double = 1.8) {
        message = msg
        amt.target = 1
        kick()
        hideWork?.cancel()
        let work = DispatchWorkItem { [weak self] in self?.amt.target = 0; self?.kick() }
        hideWork = work
        DispatchQueue.main.asyncAfter(deadline: .now() + seconds, execute: work)
    }

    func setContentRotation(_ deg: CGFloat) { rot.target = Motion.nearestAngle(current: rot.value, target: deg); kick() }

    override func draw(_ rect: CGRect) {
        let a = amt.value
        guard a > 0.01, !message.isEmpty, let ctx = UIGraphicsGetCurrentContext() else { return }
        let font = Fonts.mono(11.5)
        let tw = min(textWidth(message, font: font) + 24, bounds.width - 24)
        let th: CGFloat = 30
        let c = CGPoint(x: bounds.midX, y: anchorY + th / 2 + 10 - (1 - a) * 14)
        ctx.saveGState()
        ctx.translateBy(x: c.x, y: c.y)
        ctx.rotate(by: Motion.radians(rot.value))
        palette.surface.alpha(0.94 * a).setFill()
        UIBezierPath(roundedRect: CGRect(x: -tw / 2, y: -th / 2, width: tw, height: th), cornerRadius: th / 2).fill()
        palette.accent.alpha(a).setFill()
        UIRectFill(CGRect(x: -tw / 2 + th / 2, y: th / 2 - 2, width: max(0, tw - th), height: 2))
        var shown = message
        while textWidth(shown, font: font) > tw - 16 && shown.count > 4 { shown = String(shown.dropLast(2)) }
        if shown != message { shown = String(shown.dropLast()) + "…" }
        drawText(shown, font: font, color: palette.text.alpha(a), center: .zero)
        ctx.restoreGState()
    }
}

/// A list row in the old-camera style; pressed rows light up in the accent color.
final class MenuRow: AnimatedView {
    enum Style { case toggle, text, action, header }

    var palette: Palette { didSet { setNeedsDisplay() } }
    var title = ""
    var value = ""
    var note = ""
    var on = false
    var style: Style = .text
    var icon: PixelIcon?
    var iconColor: UIColor?
    var enabled = true
    var onTap: (() -> Void)?

    private lazy var pressed = spring(0, stiffness: 700, damping: 0.9)
    private lazy var onAmt = spring(0, stiffness: 520, damping: 0.85)

    init(palette: Palette) {
        self.palette = palette
        super.init(frame: .zero)
    }

    required init?(coder: NSCoder) { fatalError("init(coder:) is not used") }

    func refresh() {
        onAmt.target = on ? 1 : 0
        kick()
    }

    private var base: CGFloat { style == .header ? 40 : 54 }

    private var noteAttributes: [NSAttributedString.Key: Any] {
        let ps = NSMutableParagraphStyle()
        ps.lineSpacing = 2
        return [.font: Fonts.mono(10.5, .regular), .paragraphStyle: ps]
    }

    func height(for width: CGFloat) -> CGFloat {
        guard !note.isEmpty else { return base }
        let r = (note as NSString).boundingRect(with: CGSize(width: width - 36, height: .greatestFiniteMagnitude),
                                                 options: [.usesLineFragmentOrigin], attributes: noteAttributes, context: nil)
        return base + ceil(r.height) + 8
    }

    override func touchesBegan(_ touches: Set<UITouch>, with event: UIEvent?) {
        guard onTap != nil, style != .header, enabled else { return }
        pressed.target = 1; kick()
    }

    override func touchesCancelled(_ touches: Set<UITouch>, with event: UIEvent?) { pressed.target = 0; kick() }

    override func touchesEnded(_ touches: Set<UITouch>, with event: UIEvent?) {
        pressed.target = 0; kick()
        guard onTap != nil, style != .header, enabled, let t = touches.first, bounds.contains(t.location(in: self)) else { return }
        Haptics.press()
        onTap?()
    }

    override func draw(_ rect: CGRect) {
        let w = bounds.width
        let pr = pressed.value
        let pad: CGFloat = 18
        if pr > 0.01 {
            palette.accent.alpha(pr).setFill()
            UIBezierPath(roundedRect: CGRect(x: 6, y: 2, width: w - 12, height: bounds.height - 4), cornerRadius: 8).fill()
        }
        let fg = palette.text.mix(palette.onAccent, pr)
        let alpha: CGFloat = enabled ? 1 : 0.35
        if style == .header {
            let color = palette.accentOnSurface
            drawText(title, font: Fonts.label(11.5), color: color, at: CGPoint(x: pad, y: base * 0.6), align: .left, kern: 2)
            palette.line.setFill()
            UIRectFill(CGRect(x: pad, y: base - 1, width: w - pad * 2, height: 1))
            return
        }
        var x = pad
        if let icon = icon, let ctx = UIGraphicsGetCurrentContext() {
            icon.draw(in: ctx, center: CGPoint(x: x + 9, y: base / 2), size: 18, color: (iconColor ?? fg).alpha(alpha))
            x += 28
        }
        let tf = Fonts.mono(13.5)
        drawText(title, font: tf, color: fg.alpha(alpha), at: CGPoint(x: x, y: base / 2), align: .left)
        let vf = Fonts.mono(12)
        switch style {
        case .toggle:
            let o = onAmt.value
            let box = CGRect(x: w - pad - 48, y: base / 2 - 12, width: 48, height: 24)
            palette.surface2.mix(palette.accent, o).alpha(alpha).setFill()
            UIBezierPath(roundedRect: box, cornerRadius: 4).fill()
            drawText(on ? "ON" : "OFF", font: vf, color: palette.textDim.mix(palette.onAccent, o).alpha(alpha), center: CGPoint(x: box.midX, y: box.midY))
        case .text, .action:
            var v = value
            let maxW = w - x - textWidth(title, font: tf) - pad * 2.5
            while v.count > 3 && textWidth(v, font: vf) > maxW { v = String(v.dropFirst(2)) }
            if v != value && v.count > 1 { v = "…" + v.dropFirst() }
            drawText(v + (style == .action ? " ›" : ""), font: vf, color: palette.textDim.mix(palette.onAccent, pr).alpha(alpha), at: CGPoint(x: w - pad, y: base / 2), align: .right)
        case .header:
            break
        }
        if !note.isEmpty {
            var attrs = noteAttributes
            attrs[.foregroundColor] = palette.textDim.mix(palette.onAccent, pr).alpha(0.75 * alpha)
            (note as NSString).draw(with: CGRect(x: pad, y: base - 6, width: w - pad * 2, height: bounds.height - base),
                                    options: [.usesLineFragmentOrigin], attributes: attrs, context: nil)
        }
        palette.line.setFill()
        UIRectFill(CGRect(x: pad, y: bounds.height - 1, width: w - pad * 2, height: 1))
    }
}

/// A wrapping row of small toggle chips (extra crops, palettes).
final class ChoiceRow: UIView {
    struct Choice {
        let id: String
        let label: String
        let swatches: [UIColor]?
    }

    var palette: Palette { didSet { setNeedsDisplay() } }
    var choices: [Choice] = [] { didSet { setNeedsLayout(); setNeedsDisplay() } }
    var selected: Set<String> = [] { didSet { setNeedsDisplay() } }
    var onToggle: ((String) -> Void)?
    private var rects: [CGRect] = []
    private var pressedIndex = -1

    init(palette: Palette) {
        self.palette = palette
        super.init(frame: .zero)
        backgroundColor = .clear
        contentMode = .redraw
    }

    required init?(coder: NSCoder) { fatalError("init(coder:) is not used") }

    private var chipHeight: CGFloat { choices.contains { $0.swatches != nil } ? 58 : 36 }

    private func computeRects(width: CGFloat) -> [CGRect] {
        var out: [CGRect] = []
        let pad: CGFloat = 18, gap: CGFloat = 8
        var x = pad, y: CGFloat = 6
        for c in choices {
            let cw = max(64, textWidth(c.label, font: Fonts.mono(12)) + 22)
            if x + cw > width - pad && x > pad { x = pad; y += chipHeight + gap }
            out.append(CGRect(x: x, y: y, width: cw, height: chipHeight))
            x += cw + gap
        }
        return out
    }

    func height(for width: CGFloat) -> CGFloat { (computeRects(width: width).last?.maxY ?? 0) + 12 }

    override func layoutSubviews() {
        super.layoutSubviews()
        rects = computeRects(width: bounds.width)
        setNeedsDisplay()
    }

    override func touchesBegan(_ touches: Set<UITouch>, with event: UIEvent?) {
        guard let p = touches.first?.location(in: self) else { return }
        pressedIndex = rects.firstIndex { $0.contains(p) } ?? -1
        setNeedsDisplay()
    }

    override func touchesEnded(_ touches: Set<UITouch>, with event: UIEvent?) {
        if let p = touches.first?.location(in: self), let i = rects.firstIndex(where: { $0.contains(p) }), i == pressedIndex {
            Haptics.press()
            onToggle?(choices[i].id)
        }
        pressedIndex = -1
        setNeedsDisplay()
    }

    override func touchesCancelled(_ touches: Set<UITouch>, with event: UIEvent?) { pressedIndex = -1; setNeedsDisplay() }

    override func draw(_ rect: CGRect) {
        for (i, c) in choices.enumerated() where i < rects.count {
            let r = rects[i]
            let sel = selected.contains(c.id)
            (i == pressedIndex ? palette.accent.alpha(0.6) : (sel ? palette.accent : palette.surface2)).setFill()
            UIBezierPath(roundedRect: r, cornerRadius: 6).fill()
            let color = sel ? palette.onAccent : palette.text
            if let sw = c.swatches {
                let n = min(sw.count, 6)
                let s: CGFloat = 9
                var sx = r.midX - (CGFloat(n) * s + CGFloat(n - 1) * 2) / 2
                for k in 0..<n {
                    sw[k].setFill()
                    UIRectFill(CGRect(x: sx, y: r.minY + 9, width: s, height: s))
                    sx += s + 2
                }
                drawText(c.label, font: Fonts.mono(12), color: color, center: CGPoint(x: r.midX, y: r.maxY - 16))
            } else {
                drawText(c.label, font: Fonts.mono(12), color: color, center: CGPoint(x: r.midX, y: r.midY))
            }
        }
    }
}

/// Wrapped paragraph text.
final class TextBlock: UIView {
    var palette: Palette { didSet { setNeedsDisplay() } }
    var text = "" { didSet { setNeedsDisplay() } }
    var size: CGFloat = 12.5

    init(palette: Palette) {
        self.palette = palette
        super.init(frame: .zero)
        backgroundColor = .clear
        contentMode = .redraw
        isUserInteractionEnabled = false
    }

    required init?(coder: NSCoder) { fatalError("init(coder:) is not used") }

    private func attributes() -> [NSAttributedString.Key: Any] {
        let ps = NSMutableParagraphStyle()
        ps.lineSpacing = 3
        return [.font: Fonts.mono(size, .regular), .foregroundColor: palette.text, .paragraphStyle: ps]
    }

    func height(for width: CGFloat) -> CGFloat {
        let r = (text as NSString).boundingRect(with: CGSize(width: width - 36, height: .greatestFiniteMagnitude),
                                                 options: [.usesLineFragmentOrigin], attributes: attributes(), context: nil)
        return ceil(r.height) + 16
    }

    override func draw(_ rect: CGRect) {
        (text as NSString).draw(with: CGRect(x: 18, y: 8, width: bounds.width - 36, height: bounds.height - 16),
                                options: [.usesLineFragmentOrigin], attributes: attributes(), context: nil)
    }
}

/// Big confirmation button.
final class WideButton: AnimatedView {
    var palette: Palette { didSet { setNeedsDisplay() } }
    var label = ""
    var onTap: (() -> Void)?
    private lazy var press = spring(1, stiffness: 900, damping: 0.6)

    init(palette: Palette) {
        self.palette = palette
        super.init(frame: .zero)
    }

    required init?(coder: NSCoder) { fatalError("init(coder:) is not used") }

    override func touchesBegan(_ touches: Set<UITouch>, with event: UIEvent?) { press.target = 0.95; kick() }
    override func touchesCancelled(_ touches: Set<UITouch>, with event: UIEvent?) { press.target = 1; kick() }
    override func touchesEnded(_ touches: Set<UITouch>, with event: UIEvent?) {
        press.target = 1; kick()
        if let t = touches.first, bounds.contains(t.location(in: self)) { Haptics.confirm(); onTap?() }
    }

    override func draw(_ rect: CGRect) {
        guard let ctx = UIGraphicsGetCurrentContext() else { return }
        ctx.saveGState()
        ctx.translateBy(x: bounds.midX, y: bounds.midY)
        ctx.scaleBy(x: press.value, y: press.value)
        let r = CGRect(x: -bounds.width / 2 + 18, y: -bounds.height / 2 + 12, width: bounds.width - 36, height: bounds.height - 24)
        palette.accent.setFill()
        UIBezierPath(roundedRect: r, cornerRadius: 10).fill()
        drawText(label, font: Fonts.mono(14), color: palette.onAccent, center: .zero, kern: 1.4)
        ctx.restoreGState()
    }
}

/// Modal sheet: scrim + panel sliding up (portrait) or in from the right (wide windows).
final class Sheet: UIView {
    var palette: Palette { didSet { applyPalette() } }
    var dismissible = true { didSet { header.showClose = dismissible } }
    var onDismiss: (() -> Void)?
    var vertical = false { didSet { setNeedsLayout() } }
    var safe: UIEdgeInsets = .zero { didSet { setNeedsLayout() } }
    private(set) var isShowing = false

    private let scrim = UIView()
    private let panel = UIView()
    private let header: SheetHeader
    private let scroll = UIScrollView()
    private var rows: [UIView] = []
    var footer: UIView? {
        didSet {
            oldValue?.removeFromSuperview()
            if let f = footer { panel.addSubview(f) }
            setNeedsLayout()
        }
    }

    var title: String {
        get { header.title }
        set { header.title = newValue; header.setNeedsDisplay() }
    }

    init(palette: Palette, title: String, icon: PixelIcon?) {
        self.palette = palette
        header = SheetHeader(palette: palette, title: title, icon: icon)
        super.init(frame: .zero)
        isHidden = true
        scrim.alpha = 0
        addSubview(scrim)
        addSubview(panel)
        panel.layer.cornerRadius = 18
        panel.clipsToBounds = true
        panel.addSubview(header)
        panel.addSubview(scroll)
        scroll.alwaysBounceVertical = true
        scroll.showsVerticalScrollIndicator = false
        scroll.delaysContentTouches = false
        header.onClose = { [weak self] in self?.dismiss() }
        scrim.addGestureRecognizer(UITapGestureRecognizer(target: self, action: #selector(scrimTapped)))
        applyPalette()
    }

    required init?(coder: NSCoder) { fatalError("init(coder:) is not used") }

    private func applyPalette() {
        scrim.backgroundColor = palette.deep.alpha(0.62)
        panel.backgroundColor = palette.surface
        header.palette = palette
        for r in rows {
            (r as? MenuRow)?.palette = palette
            (r as? ChoiceRow)?.palette = palette
            (r as? TextBlock)?.palette = palette
        }
        (footer as? WideButton)?.palette = palette
    }

    func setRows(_ list: [UIView]) {
        rows.forEach { $0.removeFromSuperview() }
        rows = list
        list.forEach { scroll.addSubview($0) }
        setNeedsLayout()
    }

    @objc private func scrimTapped() { if dismissible { dismiss() } }

    private func panelFrame() -> CGRect {
        let w = bounds.width, h = bounds.height
        if vertical {
            let pw = max(min(w * 0.62, 440), min(w, 300))
            return CGRect(x: w - pw - safe.right, y: safe.top, width: pw, height: h - safe.top)
        }
        let pw = min(w, 560)
        let content = 58 + rowsHeight(width: pw) + (footer != nil ? 76 : 0) + safe.bottom
        let maxH = (h - safe.top) * 0.86
        let ph = max(min(maxH, content), min(maxH, 240))
        return CGRect(x: (w - pw) / 2, y: h - ph, width: pw, height: ph)
    }

    private func rowHeight(_ v: UIView, _ width: CGFloat) -> CGFloat {
        if let r = v as? MenuRow { return r.height(for: width) }
        if let r = v as? ChoiceRow { return r.height(for: width) }
        if let r = v as? TextBlock { return r.height(for: width) }
        return 54
    }

    private func rowsHeight(width: CGFloat) -> CGFloat { rows.reduce(0) { $0 + rowHeight($1, width) } }

    override func layoutSubviews() {
        super.layoutSubviews()
        scrim.frame = bounds
        let f = panelFrame()
        panel.bounds = CGRect(origin: .zero, size: CGSize(width: f.width, height: f.height + 18))
        panel.center = CGPoint(x: f.midX, y: f.minY + panel.bounds.height / 2)
        if !isShowing {
            panel.transform = vertical ? CGAffineTransform(translationX: f.width + safe.right, y: 0) : CGAffineTransform(translationX: 0, y: f.height + 18)
        }
        header.frame = CGRect(x: 0, y: 0, width: f.width, height: 58)
        let footH: CGFloat = footer != nil ? 76 : 0
        let listH = max(0, f.height - 58 - footH - safe.bottom)
        scroll.frame = CGRect(x: 0, y: 58, width: f.width, height: listH)
        footer?.frame = CGRect(x: 0, y: 58 + listH, width: f.width, height: footH)
        var y: CGFloat = 0
        for r in rows {
            let rh = rowHeight(r, f.width)
            r.frame = CGRect(x: 0, y: y, width: f.width, height: rh)
            r.setNeedsDisplay()
            y += rh
        }
        scroll.contentSize = CGSize(width: f.width, height: y + 8)
    }

    func show() {
        guard !isShowing else { return }
        isHidden = false
        layoutIfNeeded()
        isShowing = true
        scroll.setContentOffset(.zero, animated: false)
        UIView.animate(withDuration: 0.42, delay: 0, usingSpringWithDamping: 0.88, initialSpringVelocity: 0, options: [.allowUserInteraction]) {
            self.scrim.alpha = 1
            self.panel.transform = .identity
        }
    }

    func dismiss() {
        guard isShowing else { return }
        isShowing = false
        let f = panelFrame()
        UIView.animate(withDuration: 0.24, delay: 0, options: [.curveEaseIn], animations: {
            self.scrim.alpha = 0
            self.panel.transform = self.vertical ? CGAffineTransform(translationX: f.width + self.safe.right, y: 0) : CGAffineTransform(translationX: 0, y: f.height + 18)
        }, completion: { _ in
            if !self.isShowing { self.isHidden = true }
        })
        onDismiss?()
    }
}

final class SheetHeader: UIView {
    var palette: Palette { didSet { setNeedsDisplay() } }
    var title: String
    var showClose = true { didSet { setNeedsDisplay() } }
    var onClose: (() -> Void)?
    private let icon: PixelIcon?

    init(palette: Palette, title: String, icon: PixelIcon?) {
        self.palette = palette
        self.title = title
        self.icon = icon
        super.init(frame: .zero)
        backgroundColor = .clear
        contentMode = .redraw
    }

    required init?(coder: NSCoder) { fatalError("init(coder:) is not used") }

    override func touchesEnded(_ touches: Set<UITouch>, with event: UIEvent?) {
        if showClose, let p = touches.first?.location(in: self), p.x > bounds.width - 64 { Haptics.press(); onClose?() }
    }

    override func draw(_ rect: CGRect) {
        guard let ctx = UIGraphicsGetCurrentContext() else { return }
        let cy = bounds.height / 2 + 3
        palette.line.setFill()
        UIBezierPath(roundedRect: CGRect(x: bounds.midX - 18, y: 7, width: 36, height: 3), cornerRadius: 1.5).fill()
        var x: CGFloat = 18
        if let icon = icon {
            icon.draw(in: ctx, center: CGPoint(x: x + 10, y: cy), size: 20, color: palette.accentOnSurface)
            x += 30
        }
        drawText(title, font: Fonts.mono(16), color: palette.text, at: CGPoint(x: x, y: cy), align: .left, kern: 1.2)
        if showClose { PixelIcons.cross.draw(in: ctx, center: CGPoint(x: bounds.width - 34, y: cy), size: 18, color: palette.textDim) }
        palette.line.setFill()
        UIRectFill(CGRect(x: 18, y: bounds.height - 1, width: bounds.width - 36, height: 1))
    }
}
