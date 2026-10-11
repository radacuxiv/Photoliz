import AVFoundation
import UIKit

/// Live image sized to the exact sensor aspect (never cropped by the screen) plus an LCD-style overlay.
/// Photo zoom scales the preview layer: it is a crop of the full sensor frame, exactly like the saved files.
final class ViewfinderView: UIView {
    let container = UIView()
    let previewLayer: AVCaptureVideoPreviewLayer
    let overlay: ViewfinderOverlay

    /// Active format dimensions (sensor orientation, landscape).
    var formatSize = CGSize(width: 4032, height: 3024) { didSet { if formatSize != oldValue { setNeedsLayout() } } }
    var reserveBottom: CGFloat = 0 { didSet { if reserveBottom != oldValue { setNeedsLayout() } } }
    var reserveRight: CGFloat = 0 { didSet { if reserveRight != oldValue { setNeedsLayout() } } }
    var zoom: CGFloat = 1 { didSet { if zoom != oldValue { applyZoom() } } }

    /// (device point 0...1, point in this view)
    var onTap: ((CGPoint, CGPoint) -> Void)?
    private(set) var contentRect: CGRect = .zero

    init(session: AVCaptureSession, palette: Palette) {
        previewLayer = AVCaptureVideoPreviewLayer(session: session)
        overlay = ViewfinderOverlay(palette: palette)
        super.init(frame: .zero)
        backgroundColor = palette.deep
        previewLayer.videoGravity = .resizeAspect
        container.clipsToBounds = true
        container.backgroundColor = .black
        container.layer.addSublayer(previewLayer)
        addSubview(container)
        overlay.isUserInteractionEnabled = false
        addSubview(overlay)
        addGestureRecognizer(UITapGestureRecognizer(target: self, action: #selector(tapped(_:))))
    }

    required init?(coder: NSCoder) { fatalError("init(coder:) is not used") }

    func setPalette(_ p: Palette) {
        backgroundColor = p.deep
        overlay.palette = p
    }

    /// The UI is portrait: the image is shown upright, so width/height swap relative to the sensor format.
    var displayedAspect: CGFloat {
        guard formatSize.width > 0, formatSize.height > 0 else { return 3.0 / 4.0 }
        let long = max(formatSize.width, formatSize.height)
        let short = min(formatSize.width, formatSize.height)
        return short / long
    }

    func orientPreview() {
        guard let c = previewLayer.connection else { return }
        if #available(iOS 17.0, *) {
            if c.isVideoRotationAngleSupported(90) { c.videoRotationAngle = 90 }
        } else if c.isVideoOrientationSupported {
            c.videoOrientation = .portrait
        }
    }

    override func layoutSubviews() {
        super.layoutSubviews()
        let w = bounds.width, h = bounds.height
        guard w > 0, h > 0 else { return }
        let aspect = displayedAspect
        var cw = w
        var ch = cw / aspect
        if ch > h { ch = h; cw = ch * aspect }
        let freeV = h - ch, freeH = w - cw
        let top = freeV >= reserveBottom ? (freeV - reserveBottom) / 2 : freeV / 2
        let left = freeH >= reserveRight ? (freeH - reserveRight) / 2 : freeH / 2
        let newRect = CGRect(x: left, y: top, width: cw, height: ch).integral
        let changed = newRect != contentRect
        contentRect = newRect
        container.frame = contentRect
        CATransaction.begin()
        CATransaction.setDisableActions(true)
        previewLayer.setAffineTransform(.identity)
        previewLayer.frame = container.bounds
        previewLayer.setAffineTransform(CGAffineTransform(scaleX: zoom, y: zoom))
        CATransaction.commit()
        overlay.frame = bounds
        overlay.content = contentRect
        if changed { overlay.relayoutAspect() }
    }

    private func applyZoom() {
        CATransaction.begin()
        CATransaction.setDisableActions(true)
        previewLayer.setAffineTransform(CGAffineTransform(scaleX: zoom, y: zoom))
        CATransaction.commit()
    }

    @objc private func tapped(_ g: UITapGestureRecognizer) {
        let p = g.location(in: self)
        guard contentRect.contains(p) else { onTap?(CGPoint(x: -1, y: -1), p); return }
        let layerPoint = previewLayer.convert(p, from: layer)
        let device = previewLayer.captureDevicePointConverted(fromLayerPoint: layerPoint)
        onTap?(CGPoint(x: min(max(device.x, 0), 1), y: min(max(device.y, 0), 1)), p)
    }
}

/// Everything drawn over the live image.
final class ViewfinderOverlay: AnimatedView {
    var palette: Palette { didSet { setNeedsDisplay() } }
    var content: CGRect = .zero { didSet { setNeedsDisplay() } }

    private lazy var cl = spring(0, stiffness: 380, damping: 0.9)
    private lazy var ct = spring(0, stiffness: 380, damping: 0.9)
    private lazy var cr = spring(1, stiffness: 380, damping: 0.9)
    private lazy var cb = spring(1, stiffness: 380, damping: 0.9)
    private lazy var rot = spring(0, stiffness: 260, damping: 0.8)
    private lazy var curtain = spring(0, stiffness: 1600, damping: 1)
    private lazy var afAmt = spring(0, stiffness: 420, damping: 0.75)
    private lazy var afScale = spring(1, stiffness: 300, damping: 0.7)

    var infoLeft = "" { didSet { if infoLeft != oldValue { setNeedsDisplay() } } }
    var infoRight = "" { didSet { if infoRight != oldValue { setNeedsDisplay() } } }
    var modeBadge = "" { didSet { if modeBadge != oldValue { setNeedsDisplay() } } }
    var centerText = "" { didSet { if centerText != oldValue { setNeedsDisplay() } } }
    var recordingSince: Date? { didSet { kick() } }

    private var ratio: Double?
    private var afPoint = CGPoint.zero
    private var afPhase: FocusPhase = .idle
    private var afHideAt = Date.distantFuture

    init(palette: Palette) {
        self.palette = palette
        super.init(frame: .zero)
    }

    required init?(coder: NSCoder) { fatalError("init(coder:) is not used") }

    override var keepAnimating: Bool { recordingSince != nil || (afPhase != .idle && Date() < afHideAt.addingTimeInterval(0.4)) }

    func relayoutAspect() { setAspect(ratio, animated: false) }

    func setAspect(_ r: Double?, animated: Bool) {
        ratio = r
        guard content.width > 0, content.height > 0 else { return }
        let w = Int(content.width * 4), h = Int(content.height * 4)
        let c = CropMath.centered(w: w, h: h, ratio: r, zoom: 1, align: 1)
        let l = CGFloat(c.left) / CGFloat(w), t = CGFloat(c.top) / CGFloat(h)
        let rr = CGFloat(c.left + c.width) / CGFloat(w), b = CGFloat(c.top + c.height) / CGFloat(h)
        if animated { cl.target = l; ct.target = t; cr.target = rr; cb.target = b; kick() }
        else { cl.snap(l); ct.snap(t); cr.snap(rr); cb.snap(b); setNeedsDisplay() }
    }

    func setContentRotation(_ deg: CGFloat) { rot.target = Motion.nearestAngle(current: rot.value, target: deg); kick() }

    func blink() {
        curtain.snap(0)
        curtain.target = 1
        kick()
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.07) { [weak self] in
            self?.curtain.target = 0
            self?.kick()
        }
    }

    func focus(_ phase: FocusPhase, at p: CGPoint) {
        afPhase = phase
        afPoint = p
        switch phase {
        case .scanning: afAmt.target = 1; afScale.snap(1.5); afScale.target = 1; afHideAt = .distantFuture
        case .locked, .failed: afAmt.target = 1; afScale.target = 0.92; afHideAt = Date().addingTimeInterval(1.4)
        case .idle: afAmt.target = 0
        }
        kick()
    }

    private func corner(_ ctx: CGContext, _ x: CGFloat, _ y: CGFloat, _ dx: CGFloat, _ dy: CGFloat) {
        ctx.move(to: CGPoint(x: x, y: y + dy))
        ctx.addLine(to: CGPoint(x: x, y: y))
        ctx.addLine(to: CGPoint(x: x + dx, y: y))
        ctx.strokePath()
    }

    override func draw(_ rect: CGRect) {
        guard content.width > 0, let ctx = UIGraphicsGetCurrentContext() else { return }
        let w = content.width, h = content.height
        let ml = content.minX + cl.value * w, mt = content.minY + ct.value * h
        let mr = content.minX + cr.value * w, mb = content.minY + cb.value * h
        palette.mask.setFill()
        if mt > content.minY + 0.5 { UIRectFill(CGRect(x: content.minX, y: content.minY, width: w, height: mt - content.minY)) }
        if mb < content.maxY - 0.5 { UIRectFill(CGRect(x: content.minX, y: mb, width: w, height: content.maxY - mb)) }
        if ml > content.minX + 0.5 { UIRectFill(CGRect(x: content.minX, y: mt, width: ml - content.minX, height: mb - mt)) }
        if mr < content.maxX - 0.5 { UIRectFill(CGRect(x: mr, y: mt, width: content.maxX - mr, height: mb - mt)) }
        if ml > content.minX + 0.5 || mt > content.minY + 0.5 || mr < content.maxX - 0.5 || mb < content.maxY - 0.5 {
            ctx.setStrokeColor(palette.text.alpha(0.45).cgColor)
            ctx.setLineWidth(1)
            ctx.stroke(CGRect(x: ml, y: mt, width: mr - ml, height: mb - mt))
        }
        // Corner frame marks
        ctx.setStrokeColor(palette.text.alpha(0.55).cgColor)
        ctx.setLineWidth(1.5)
        let k: CGFloat = 14, inset: CGFloat = 10
        corner(ctx, ml + inset, mt + inset, k, k)
        corner(ctx, mr - inset, mt + inset, -k, k)
        corner(ctx, ml + inset, mb - inset, k, -k)
        corner(ctx, mr - inset, mb - inset, -k, -k)

        // AF reticle
        if afPhase != .idle && Date() > afHideAt && afAmt.target != 0 { afAmt.target = 0; kick() }
        let aa = afAmt.value
        if aa > 0.01 {
            let color: UIColor = afPhase == .locked ? palette.accent : (afPhase == .failed ? palette.alert : palette.text)
            ctx.setStrokeColor(color.alpha(aa).cgColor)
            ctx.setLineWidth(2)
            let s = 30 * afScale.value, b = s * 0.35
            corner(ctx, afPoint.x - s, afPoint.y - s, b, b)
            corner(ctx, afPoint.x + s, afPoint.y - s, -b, b)
            corner(ctx, afPoint.x - s, afPoint.y + s, b, -b)
            corner(ctx, afPoint.x + s, afPoint.y + s, -b, -b)
        }

        // Rotated info layer
        ctx.saveGState()
        ctx.translateBy(x: (ml + mr) / 2, y: (mt + mb) / 2)
        ctx.rotate(by: Motion.radians(rot.value))
        let sideways = Int((rot.value / 90).rounded()) % 2 != 0
        let iw = sideways ? mb - mt : mr - ml
        let ih = sideways ? mr - ml : mb - mt
        let pad: CGFloat = 14
        let font = Fonts.mono(10.5)
        ctx.setShadow(offset: .zero, blur: 2, color: palette.deep.alpha(0.9).cgColor)
        if !infoLeft.isEmpty { drawText(infoLeft, font: font, color: palette.text, at: CGPoint(x: -iw / 2 + pad, y: -ih / 2 + pad + 6), align: .left) }
        var rightX = iw / 2 - pad
        if !modeBadge.isEmpty {
            let bf = Fonts.mono(11)
            let bw = textWidth(modeBadge, font: bf) + 10
            let box = CGRect(x: rightX - bw, y: -ih / 2 + pad - 3, width: bw, height: bf.lineHeight + 4)
            ctx.saveGState()
            ctx.setShadow(offset: .zero, blur: 0, color: nil)
            palette.accent.setFill()
            UIBezierPath(roundedRect: box, cornerRadius: 3).fill()
            drawText(modeBadge, font: bf, color: palette.onAccent, center: CGPoint(x: box.midX, y: box.midY))
            ctx.restoreGState()
            rightX -= bw + 8
        }
        if !infoRight.isEmpty { drawText(infoRight, font: font, color: palette.text, at: CGPoint(x: rightX, y: -ih / 2 + pad + 6), align: .right) }
        if let since = recordingSince {
            let secs = max(0, Int(Date().timeIntervalSince(since)))
            let label = String(format: "REC %02d:%02d", secs / 60, secs % 60)
            let rf = Fonts.mono(13)
            let y = ih / 2 - pad - 4
            if Int(Date().timeIntervalSince1970 * 2) % 2 == 0 {
                palette.alert.setFill()
                let tw = textWidth(label, font: rf)
                UIBezierPath(ovalIn: CGRect(x: -tw / 2 - 15, y: y - 5, width: 10, height: 10)).fill()
            }
            drawText(label, font: rf, color: palette.text, center: CGPoint(x: 0, y: y))
        }
        if !centerText.isEmpty {
            let cf = Fonts.mono(13)
            let lines = centerText.components(separatedBy: "\n")
            var y = -CGFloat(lines.count - 1) * cf.lineHeight * 0.75
            for line in lines { drawText(line, font: cf, color: palette.text, center: CGPoint(x: 0, y: y)); y += cf.lineHeight * 1.5 }
        }
        ctx.restoreGState()

        // Shutter curtain
        let c = curtain.value
        if c > 0.01 {
            palette.deep.setFill()
            let half = h / 2 * c
            UIRectFill(CGRect(x: content.minX, y: content.minY, width: w, height: half))
            UIRectFill(CGRect(x: content.minX, y: content.maxY - half, width: w, height: half))
        }
    }
}
