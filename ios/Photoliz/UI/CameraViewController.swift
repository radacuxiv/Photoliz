import AVFoundation
import AVKit
import UIKit

/// Lays out the camera screen like the Android version: chips on top, viewfinder, exposure bar, deck at the
/// bottom; in a wide window the same layout is turned 90° (chips left, deck right).
final class CameraRootView: UIView {
    var viewfinder: ViewfinderView!
    var topBar: ChipBar!
    var paramBar: ParamBar!
    var dial: DialStrip!
    var deck: ShutterDeck!
    var toaster: Toaster!
    var sheets: [Sheet] = []
    private(set) var landscape = false
    private(set) var dialShown = false
    var onLandscapeChange: ((Bool) -> Void)?

    func showDial(_ show: Bool) {
        guard show != dialShown else { return }
        dialShown = show
        if show {
            dial.isHidden = false
            dial.alpha = 0
            dial.transform = landscape ? CGAffineTransform(translationX: 24, y: 0) : CGAffineTransform(translationX: 0, y: 24)
            UIView.animate(withDuration: 0.3, delay: 0, usingSpringWithDamping: 0.9, initialSpringVelocity: 0, options: [.allowUserInteraction]) {
                self.dial.alpha = 1
                self.dial.transform = .identity
            }
        } else {
            UIView.animate(withDuration: 0.16, delay: 0, options: [.curveEaseIn, .allowUserInteraction], animations: {
                self.dial.alpha = 0
                self.dial.transform = self.landscape ? CGAffineTransform(translationX: 24, y: 0) : CGAffineTransform(translationX: 0, y: 24)
            }, completion: { _ in if !self.dialShown { self.dial.isHidden = true } })
        }
    }

    override func layoutSubviews() {
        super.layoutSubviews()
        let w = bounds.width, h = bounds.height
        let safe = safeAreaInsets
        let wasLandscape = landscape
        landscape = w > h * 1.05
        if wasLandscape != landscape { onLandscapeChange?(landscape) }
        topBar.vertical = landscape
        paramBar.vertical = landscape
        dial.vertical = landscape
        deck.vertical = landscape
        let bar: CGFloat = 60, paramT: CGFloat = 64, dialT: CGFloat = 58
        let deckT = deck.preferredThickness
        let top = max(safe.top, 6)
        let vf: CGRect, param: CGRect, dialR: CGRect, deckR: CGRect, topR: CGRect
        if !landscape {
            topR = CGRect(x: safe.left, y: top, width: w - safe.left - safe.right, height: bar)
            deckR = CGRect(x: safe.left, y: h - safe.bottom - deckT, width: w - safe.left - safe.right, height: deckT)
            vf = CGRect(x: 0, y: topR.maxY, width: w, height: deckR.minY - topR.maxY)
            param = CGRect(x: safe.left, y: vf.maxY - paramT, width: w - safe.left - safe.right, height: paramT)
            dialR = CGRect(x: 0, y: param.minY - dialT, width: w, height: dialT)
            viewfinder.reserveBottom = paramT
            viewfinder.reserveRight = 0
        } else {
            topR = CGRect(x: safe.left, y: top, width: bar, height: h - top - safe.bottom)
            deckR = CGRect(x: w - safe.right - deckT, y: top, width: deckT, height: h - top - safe.bottom)
            vf = CGRect(x: topR.maxX, y: 0, width: deckR.minX - topR.maxX, height: h)
            param = CGRect(x: vf.maxX - 92, y: top, width: 92, height: h - top - safe.bottom)
            dialR = CGRect(x: param.minX - dialT, y: 0, width: dialT, height: h)
            viewfinder.reserveBottom = 0
            viewfinder.reserveRight = 92
        }
        viewfinder.frame = vf
        topBar.frame = topR
        paramBar.frame = param
        dial.bounds = CGRect(origin: .zero, size: dialR.size)
        dial.center = CGPoint(x: dialR.midX, y: dialR.midY)
        deck.frame = deckR
        toaster.frame = bounds
        viewfinder.layoutIfNeeded()
        toaster.anchorY = vf.minY + viewfinder.contentRect.minY + 36
        for s in sheets {
            s.frame = bounds
            s.safe = safe
            s.vertical = landscape
        }
    }
}

/// Full-screen look at the last photo (screen-size render of the saved JPEG).
final class QuickViewer: UIViewController {
    private let image: UIImage
    init(image: UIImage) {
        self.image = image
        super.init(nibName: nil, bundle: nil)
        modalPresentationStyle = .overFullScreen
        modalTransitionStyle = .crossDissolve
    }
    required init?(coder: NSCoder) { fatalError("init(coder:) is not used") }

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .black
        let iv = UIImageView(image: image)
        iv.contentMode = .scaleAspectFit
        iv.frame = view.bounds
        iv.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        view.addSubview(iv)
        view.addGestureRecognizer(UITapGestureRecognizer(target: self, action: #selector(close)))
    }

    @objc private func close() { dismiss(animated: true) }
    override var prefersStatusBarHidden: Bool { true }
}

final class CameraViewController: UIViewController, CameraControllerDelegate, UIDocumentPickerDelegate {
    private let prefs = Prefs.shared
    private var palette = Palette.named(Prefs.shared.palette)
    private let camera = CameraController()
    private let orientation = OrientationTracker()

    private var root: CameraRootView!
    private var viewfinder: ViewfinderView!
    private let topBar = ChipBar()
    private var menuChip: ChipButton!
    private var cameraChip: ChipButton!
    private var resChip: ChipButton!
    private var flashChip: ChipButton!
    private let paramBar = ParamBar()
    private var isoCell: ParamCell!
    private var shutterCell: ParamCell!
    private var dial: DialStrip!
    private var deck: ShutterDeck!
    private var toaster: Toaster!
    private var menuSheet: Sheet!
    private var reportSheet: Sheet!
    private var pickerSheet: Sheet!
    private var aboutSheet: Sheet!

    private var sensors: [SensorInfo] = []
    private var sensor: SensorInfo?
    private var settings = CaptureSettings()
    private var live: LiveState?
    private var activeDial: String?
    private var recording = false
    private var bursting = false
    private var cameraError: String?
    private var permissionDenied = false
    private var reportPending = false
    private var active = false
    private var uiRotation: CGFloat = 0
    private var lastTap: CGPoint?
    private var lastPush = Date.distantPast
    private var pushScheduled = false
    private var lastPreview: UIImage?
    private var formatSize: CGSize = .zero
    private var stillSize: CMVideoDimensions?
    private var rawActive = false
    private var hwDownAt: Date?
    private var hwBurst = false
    private var hwTimer: Timer?

    private static let keyISO = "iso"
    private static let keyShutter = "shutter"
    private let photoZoomMax: CGFloat = 4

    override var prefersStatusBarHidden: Bool { true }
    override var prefersHomeIndicatorAutoHidden: Bool { true }
    override var preferredScreenEdgesDeferringSystemGestures: UIRectEdge { .bottom }
    override var supportedInterfaceOrientations: UIInterfaceOrientationMask { .portrait }

    // MARK: - Setup

    override func loadView() {
        root = CameraRootView()
        root.backgroundColor = palette.bg
        view = root
    }

    override func viewDidLoad() {
        super.viewDidLoad()
        camera.delegate = self
        if let bm = prefs.dngFolderBookmark { camera.dngFolder = MediaSaver.resolve(bookmark: bm) }
        let aspect = AspectRatio.byId(prefs.aspectId)
        settings.aspect = AspectRatio.cycle(extras: prefs.extraAspects).contains(aspect) ? aspect : .trueToSensor
        settings.ois = prefs.ois
        settings.saveDng = prefs.saveDng
        buildViews()
        orientation.onChange = { [weak self] _ in self?.applyRotation() }
        installHardwareButtons()
        requestCamera()
    }

    private func chip() -> ChipButton { ChipButton(palette: palette) }

    private func buildViews() {
        viewfinder = ViewfinderView(session: camera.session, palette: palette)
        viewfinder.onTap = { [weak self] device, point in self?.viewfinderTapped(device: device, point: point) }

        menuChip = chip(); menuChip.icon = PixelIcons.menu; menuChip.label = "MENU"; menuChip.onTap = { [weak self] in self?.openMenu() }
        cameraChip = chip(); cameraChip.icon = PixelIcons.camera; cameraChip.label = "CAM"; cameraChip.onTap = { [weak self] in self?.openPicker() }
        resChip = chip(); resChip.icon = PixelIcons.pixelBin; resChip.label = "--"; resChip.onTap = { [weak self] in self?.cycleResolution() }
        flashChip = chip(); flashChip.icon = PixelIcons.flashOff; flashChip.label = "OFF"; flashChip.onTap = { [weak self] in self?.cycleFlash() }
        for c in [menuChip!, cameraChip!, resChip!, flashChip!] { topBar.addSubview(c) }

        paramBar.scrimColor = palette.deep.alpha(0.7)
        isoCell = ParamCell(key: CameraViewController.keyISO, title: "ISO", palette: palette)
        shutterCell = ParamCell(key: CameraViewController.keyShutter, title: "SHUTTER", palette: palette)
        for c in [isoCell!, shutterCell!] {
            c.onTap = { [weak self] k in self?.toggleDial(k) }
            c.onDoubleTap = { [weak self] k in self?.resetParam(k) }
            paramBar.addSubview(c)
        }

        dial = DialStrip(palette: palette)
        dial.isHidden = true
        dial.onScrub = { [weak self] p, settled in self?.dialScrubbed(p, settled: settled) }

        deck = ShutterDeck(palette: palette)
        deck.modeChip.icon = PixelIcons.photo; deck.modeChip.label = "PHOTO"; deck.modeChip.onTap = { [weak self] in self?.toggleMode() }
        deck.afChip.icon = PixelIcons.af; deck.afChip.label = "AF"; deck.afChip.onTap = { [weak self] in self?.toggleAF() }
        deck.aspectChip.icon = PixelIcons.aspect; deck.aspectChip.label = settings.aspect.label; deck.aspectChip.onTap = { [weak self] in self?.cycleAspect() }
        deck.thumb.onTap = { [weak self] in self?.openLast() }
        deck.shutter.onShoot = { [weak self] in self?.shoot() }
        deck.shutter.onBurstStart = { [weak self] in self?.startBurst() }
        deck.shutter.onBurstEnd = { [weak self] in self?.stopBurst() }
        deck.arcs.onChange = { [weak self] arc, t, done in self?.arcChanged(arc, t, done: done) }
        deck.setArcs(focus: prefs.focusSlider, zoom: prefs.zoomSlider, animated: false)

        toaster = Toaster(palette: palette)
        menuSheet = Sheet(palette: palette, title: "MENU", icon: PixelIcons.menu)
        reportSheet = Sheet(palette: palette, title: "HARDWARE REPORT", icon: PixelIcons.camera)
        pickerSheet = Sheet(palette: palette, title: "SENSOR", icon: PixelIcons.camera)
        aboutSheet = Sheet(palette: palette, title: AppInfo.name.uppercased(), icon: PixelIcons.photo)

        root.viewfinder = viewfinder
        root.topBar = topBar
        root.paramBar = paramBar
        root.dial = dial
        root.deck = deck
        root.toaster = toaster
        root.sheets = [pickerSheet, menuSheet, aboutSheet, reportSheet]
        for v in [viewfinder!, paramBar, dial!, topBar, deck!, toaster!] as [UIView] { root.addSubview(v) }
        for s in root.sheets { root.addSubview(s) }
        root.onLandscapeChange = { [weak self] _ in self?.applyRotation() }
        bindCaps(animated: false)
    }

    private func applyPalette(_ p: Palette) {
        palette = p
        prefs.palette = p.name
        root.backgroundColor = p.bg
        viewfinder.setPalette(p)
        for c in [menuChip!, cameraChip!, resChip!, flashChip!, deck.modeChip, deck.afChip, deck.aspectChip] { c.palette = p }
        isoCell.palette = p
        shutterCell.palette = p
        paramBar.scrimColor = p.deep.alpha(0.7)
        dial.palette = p
        deck.shutter.palette = p
        deck.arcs.palette = p
        deck.thumb.palette = p
        toaster.palette = p
        for s in root.sheets { s.palette = p }
    }

    // MARK: - Lifecycle

    func appBecameActive() {
        active = true
        orientation.start()
        if sensor != nil && !reportPending { camera.start() }
        if permissionDenied && AVCaptureDevice.authorizationStatus(for: .video) == .authorized {
            permissionDenied = false
            viewfinder.overlay.centerText = ""
            startScan()
        }
    }

    func appWillResignActive() {
        active = false
        if recording { camera.stopRecording() }
        if bursting { stopBurst() }
        camera.stop()
        orientation.stop()
    }

    private func requestCamera() {
        switch AVCaptureDevice.authorizationStatus(for: .video) {
        case .authorized:
            startScan()
        case .notDetermined:
            AVCaptureDevice.requestAccess(for: .video) { [weak self] granted in
                DispatchQueue.main.async {
                    if granted { self?.startScan() } else { self?.denied() }
                }
            }
        default:
            denied()
        }
    }

    private func denied() {
        permissionDenied = true
        viewfinder.overlay.centerText = "CAMERA ACCESS NEEDED\nTAP TO OPEN SETTINGS"
    }

    private func startScan() {
        viewfinder.overlay.centerText = "SCANNING HARDWARE…"
        camera.scan { [weak self] list in
            guard let self = self else { return }
            self.sensors = list.filter { $0.scanned }
            self.viewfinder.overlay.centerText = ""
            if self.sensors.isEmpty {
                self.viewfinder.overlay.centerText = "NO USABLE CAMERA"
                return
            }
            if !self.prefs.reportAccepted {
                self.reportPending = true
                self.showReport(mandatory: true)
            } else {
                self.openInitial()
            }
        }
    }

    private func openInitial() {
        let pick = sensors.first { $0.id == prefs.lastCamera }
            ?? sensors.first { $0.device.position == .back && $0.device.deviceType == .builtInWideAngleCamera }
            ?? sensors.first!
        select(pick)
        camera.start()
        // Ask for Photos access now rather than in the middle of the first shot.
        MediaSaver.ensurePhotosAccess { _ in }
    }

    private func select(_ s: SensorInfo) {
        guard !recording else { return }
        sensor = s
        prefs.lastCamera = s.id
        settings.isoAuto = true
        settings.shutterAuto = true
        settings.afOn = true
        settings.zoom = 1
        settings.resolutionIndex = 0
        if !s.flash && settings.flash == .flash { settings.flash = .off }
        if !s.torch && settings.flash == .torch { settings.flash = .off }
        viewfinder.zoom = 1
        live = nil
        bindCaps(animated: true)
        camera.select(s, settings: settings)
    }

    // MARK: - Binding

    private var zoomPossible: Bool {
        guard let s = sensor else { return false }
        return settings.mode == .video ? s.videoMaxZoom > 1.01 : true
    }

    private func bindCaps(animated: Bool) {
        let s = sensor
        let video = settings.mode == .video
        Reveal.set(resChip, shown: (s?.resolutions.count ?? 0) > 1 && !video, animated: animated)
        Reveal.set(flashChip, shown: s != nil && (s!.flash || s!.torch), animated: animated)
        Reveal.set(deck.afChip, shown: s?.autofocus == true, animated: animated)
        Reveal.set(deck.aspectChip, shown: !video, animated: animated)
        isoCell.readOnly = s?.manualExposure != true
        shutterCell.readOnly = s?.manualExposure != true
        if activeDial != nil && (s == nil || !(s!.manualExposure)) { closeDial() }
        deck.setArcs(focus: prefs.focusSlider && s?.manualFocus == true, zoom: prefs.zoomSlider && zoomPossible, animated: animated)
        configureArcs()
        refreshChips()
        refreshReadouts()
        refreshInfo()
        topBar.setNeedsLayout()
        paramBar.setNeedsLayout()
    }

    private func configureArcs() {
        let f = deck.arcs.focus
        f.enabled = sensor?.manualFocus == true
        f.startLabel = "NEAR"
        f.endLabel = "∞"
        f.bubble = { t in "F \(Int((t * 100).rounded()))" }
        f.detents = [1]
        deck.arcs.setValue(f, CGFloat(settings.afOn ? (live?.lensPosition ?? settings.lensPosition) : settings.lensPosition))
        let z = deck.arcs.zoom
        let zMax = zoomMax
        z.startLabel = "1×"
        z.endLabel = "\(Int(zMax.rounded()))×"
        z.bubble = { [weak self] t in String(format: "%.1f×", Double(self?.zoomFor(t) ?? 1)) }
        let marks: [CGFloat] = [2, 3, 5]
        z.detents = marks.filter { $0 < zMax }.map { log($0) / log(zMax) }
        z.enabled = zoomPossible
        deck.arcs.setValue(z, zMax > 1.01 ? log(max(settings.zoom, 1)) / log(zMax) : 0)
        deck.arcs.setNeedsDisplay()
    }

    private var zoomMax: CGFloat {
        if settings.mode == .video { return max(1.01, min(sensor?.videoMaxZoom ?? 1, 10)) }
        return photoZoomMax
    }

    private func zoomFor(_ t: CGFloat) -> CGFloat { pow(zoomMax, min(max(t, 0), 1)) }

    private func refreshChips() {
        cameraChip.label = sensor?.label ?? "CAM"
        let video = settings.mode == .video
        deck.modeChip.icon = video ? PixelIcons.video : PixelIcons.photo
        deck.modeChip.label = video ? "VIDEO" : "PHOTO"
        deck.modeChip.active = video
        deck.afChip.icon = settings.afOn ? PixelIcons.af : PixelIcons.mf
        deck.afChip.label = settings.afOn ? "AF" : "MF"
        deck.afChip.active = settings.afOn
        deck.aspectChip.label = settings.aspect.label
        deck.aspectChip.active = settings.aspect != .trueToSensor
        switch settings.flash {
        case .off: flashChip.icon = PixelIcons.flashOff; flashChip.label = "OFF"; flashChip.active = false
        case .flash: flashChip.icon = PixelIcons.flash; flashChip.label = "FLASH"; flashChip.active = true
        case .torch: flashChip.icon = PixelIcons.torch; flashChip.label = "TORCH"; flashChip.active = true
        }
        if let s = sensor, s.resolutions.count > 1 {
            let i = min(max(settings.resolutionIndex, 0), s.resolutions.count - 1)
            resChip.icon = i == s.resolutions.count - 1 ? PixelIcons.pixelFull : PixelIcons.pixelBin
            resChip.label = SensorInfo.megapixels(s.resolutions[i]).replacingOccurrences(of: "MP", with: "M")
            resChip.active = i > 0
        }
        deck.shutter.video = video
    }

    private func refreshReadouts() {
        let manualOK = sensor?.manualExposure == true
        let isoAuto = !manualOK || settings.isoAuto
        let shAuto = !manualOK || settings.shutterAuto
        let liveISO = live.map { "\(Int($0.iso.rounded()))" } ?? "--"
        isoCell.setValue(isoAuto ? liveISO : "\(Int(settings.iso.rounded()))", auto: isoAuto)
        shutterCell.setValue(ExposureScales.format(shutter: shAuto ? (live?.exposure ?? 0) : settings.exposure), auto: shAuto)
        viewfinder.overlay.modeBadge = settings.exposureMode == .auto ? "P" : "M"
    }

    private func refreshInfo() {
        guard let s = sensor else { viewfinder.overlay.infoLeft = ""; viewfinder.overlay.infoRight = ""; return }
        if settings.mode == .video {
            viewfinder.overlay.infoLeft = s.label
            let hgt = Int(min(formatSize.width, formatSize.height))
            viewfinder.overlay.infoRight = hgt >= 2160 ? "4K 30" : (hgt > 0 ? "\(hgt)p 30" : "VIDEO")
            return
        }
        var left = s.label
        if let d = stillSize, d.width > 0 { left += " · " + SensorInfo.megapixels(d) }
        viewfinder.overlay.infoLeft = left
        viewfinder.overlay.infoRight = rawActive ? (settings.saveDng ? "DNG+JPG" : "JPG·RAW") : "JPG·ISP"
    }

    private func applyRotation() {
        var r = -orientation.orientation
        if r <= -180 { r += 360 }
        uiRotation = CGFloat(r)
        for c in [menuChip!, cameraChip!, resChip!, flashChip!, deck.modeChip, deck.afChip, deck.aspectChip] { c.setContentRotation(uiRotation) }
        isoCell.setContentRotation(uiRotation)
        shutterCell.setContentRotation(uiRotation)
        dial.setContentRotation(uiRotation)
        deck.arcs.setContentRotation(uiRotation)
        deck.thumb.setContentRotation(uiRotation)
        toaster.setContentRotation(uiRotation)
        viewfinder.overlay.setContentRotation(uiRotation)
    }

    // MARK: - Settings push

    private func commit(_ s: CaptureSettings, immediate: Bool = true) {
        settings = s
        refreshChips()
        refreshReadouts()
        refreshInfo()
        push(immediate)
    }

    private func push(_ immediate: Bool) {
        if immediate || Date().timeIntervalSince(lastPush) > 0.045 {
            lastPush = Date()
            camera.update(settings)
        } else if !pushScheduled {
            pushScheduled = true
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.045) { [weak self] in
                guard let self = self else { return }
                self.pushScheduled = false
                self.lastPush = Date()
                self.camera.update(self.settings)
            }
        }
    }

    private func toast(_ m: String) { toaster.show(m) }

    private func announce(_ before: ExposureMode) {
        if settings.exposureMode != before { toast(settings.exposureMode == .auto ? "AUTO EXPOSURE" : "MANUAL EXPOSURE") }
    }

    // MARK: - Exposure (AVFoundation: both auto, or both manual)

    private func setISO(_ iso: Float, immediate: Bool) {
        let before = settings.exposureMode
        var s = settings
        s.isoAuto = false
        s.iso = iso
        if s.shutterAuto { s.shutterAuto = false; s.exposure = live?.exposure ?? s.exposure }
        commit(s, immediate: immediate)
        announce(before)
    }

    private func setShutter(_ t: Double, immediate: Bool) {
        let before = settings.exposureMode
        var s = settings
        s.shutterAuto = false
        s.exposure = t
        if s.isoAuto { s.isoAuto = false; s.iso = live?.iso ?? s.iso }
        commit(s, immediate: immediate)
        announce(before)
    }

    private func setAuto() {
        let before = settings.exposureMode
        var s = settings
        s.isoAuto = true
        s.shutterAuto = true
        commit(s)
        announce(before)
    }

    private func resetParam(_ key: String) {
        guard sensor?.manualExposure == true else { return }
        setAuto()
        syncDial()
    }

    // MARK: - Dials

    private let isoMajors: Set<Int> = [25, 50, 100, 200, 400, 800, 1600, 3200, 6400, 12800, 25600]
    private let shutterMajors: Set<String> = ["1/32000", "1/16000", "1/8000", "1/4000", "1/2000", "1/1000", "1/500", "1/250", "1/125",
                                              "1/60", "1/30", "1/15", "1/8", "1/4", "0.5\"", "1\"", "2\"", "4\"", "8\"", "15\"", "30\""]

    private func shutterList() -> [ExposureScales.Shutter] {
        guard let s = sensor, s.manualExposure else { return [] }
        let hi = settings.mode == .video ? min(s.exposureRange.upperBound, 1.0 / 30.0) : s.exposureRange.upperBound
        return ExposureScales.shuttersIn(min: s.exposureRange.lowerBound, max: hi)
    }

    private func edgeLabels(_ stops: [DialStop], minGap: CGFloat) -> [DialStop] {
        stops.enumerated().map { pair -> DialStop in
            let i = pair.offset
            let s = pair.element
            guard i == 0 || i == stops.count - 1 else { return s }
            let gaps: [CGFloat] = stops.enumerated().filter { $0.offset != i && $0.element.major }.map { abs($0.element.pos - s.pos) }
            let near: CGFloat = gaps.min() ?? .greatestFiniteMagnitude
            return DialStop(pos: s.pos, label: s.label, major: near >= minGap)
        }
    }

    private func dialModel(_ key: String) -> DialModel? {
        guard let s = sensor, s.manualExposure else { return nil }
        if key == CameraViewController.keyISO {
            let lo = Int(s.isoRange.lowerBound.rounded(.up)), hi = Int(s.isoRange.upperBound.rounded(.down))
            let isos = ExposureScales.isoStops(min: lo, max: hi)
            guard !isos.isEmpty else { return nil }
            let stops = isos.map { DialStop(pos: CGFloat(ExposureScales.isoToStops(Double($0), min: lo) * 3), label: "\($0)", major: isoMajors.contains($0)) }
            return DialModel(key: key, stops: edgeLabels(stops, minGap: 2.5), continuous: true, autoPos: stops[0].pos - 2.4, unit: 16, snapThreshold: 0.5)
        }
        let list = shutterList()
        guard !list.isEmpty else { return nil }
        let stops = list.enumerated().map { DialStop(pos: CGFloat($0.offset), label: $0.element.label, major: shutterMajors.contains($0.element.label)) }
        return DialModel(key: key, stops: edgeLabels(stops, minGap: 2.5), continuous: false, autoPos: -2.4, unit: 22, snapThreshold: 0.5)
    }

    private func dialPosition(_ key: String) -> CGFloat {
        guard let s = sensor else { return 0 }
        if key == CameraViewController.keyISO {
            let lo = Int(s.isoRange.lowerBound.rounded(.up)), hi = Int(s.isoRange.upperBound.rounded(.down))
            let iso = Double(settings.isoAuto ? (live?.iso ?? Float(lo)) : settings.iso)
            return CGFloat(ExposureScales.isoToStops(min(max(iso, Double(lo)), Double(max(lo, hi))), min: lo) * 3)
        }
        let t = settings.shutterAuto ? (live?.exposure ?? 0.01) : settings.exposure
        return CGFloat(max(0, ExposureScales.nearestShutterIndex(shutterList(), t)))
    }

    private func toggleDial(_ key: String) {
        if activeDial == key { closeDial(); return }
        guard let m = dialModel(key) else { return }
        activeDial = key
        dial.setModel(m, position: dialPosition(key))
        isoCell.chosen = key == CameraViewController.keyISO
        shutterCell.chosen = key == CameraViewController.keyShutter
        root.showDial(true)
    }

    private func closeDial() {
        activeDial = nil
        isoCell.chosen = false
        shutterCell.chosen = false
        root.showDial(false)
    }

    private func rebuildDial() {
        guard let key = activeDial else { return }
        if let m = dialModel(key) { dial.setModel(m, position: dialPosition(key)) } else { closeDial() }
    }

    private func syncDial() {
        guard let key = activeDial else { return }
        dial.move(to: dialPosition(key))
    }

    private func dialScrubbed(_ pos: CGFloat, settled: Bool) {
        guard let key = activeDial, let m = dial.model, let s = sensor else { return }
        let isAuto = m.isAuto(pos) && (settled || m.settle(pos) == m.autoPos)
        if isAuto {
            if settings.exposureMode != .auto { setAuto() }
            return
        }
        if key == CameraViewController.keyISO {
            let lo = Int(s.isoRange.lowerBound.rounded(.up)), hi = Int(s.isoRange.upperBound.rounded(.down))
            let iso = ExposureScales.snapISO(ExposureScales.stopsToISO(Double(max(0, pos / 3)), min: lo), min: lo, max: max(lo, hi), threshold: 1.0 / 12.0)
            if settings.isoAuto || Int(settings.iso.rounded()) != iso { setISO(Float(iso), immediate: settled) }
        } else {
            let list = shutterList()
            guard !list.isEmpty else { return }
            let idx = min(max(Int(pos.rounded()), 0), list.count - 1)
            let t = list[idx].seconds
            if settings.shutterAuto || abs(t - settings.exposure) > 1e-9 { setShutter(t, immediate: settled) }
        }
    }

    // MARK: - Chips

    private func cycleResolution() {
        guard let s = sensor, s.resolutions.count > 1, !recording else { return }
        var st = settings
        st.resolutionIndex = (settings.resolutionIndex + 1) % s.resolutions.count
        commit(st)
        toast(st.resolutionIndex == s.resolutions.count - 1 ? "FULL RESOLUTION" : "BINNED RESOLUTION")
    }

    private func cycleFlash() {
        guard let s = sensor else { return }
        var options: [FlashMode] = [.off]
        if s.flash && settings.mode == .photo { options.append(.flash) }
        if s.torch { options.append(.torch) }
        guard options.count > 1 else { return }
        let i = options.firstIndex(of: settings.flash) ?? 0
        var st = settings
        st.flash = options[(i + 1) % options.count]
        commit(st)
    }

    private func toggleAF() {
        guard let s = sensor, s.autofocus else { return }
        var st = settings
        st.afOn.toggle()
        if !st.afOn { st.lensPosition = live?.lensPosition ?? st.lensPosition }
        commit(st)
        if st.afOn { viewfinder.overlay.focus(.idle, at: .zero) }
        configureArcs()
    }

    private func cycleAspect() {
        let list = AspectRatio.cycle(extras: prefs.extraAspects)
        let i = list.firstIndex(of: settings.aspect) ?? 0
        let next = list[(i + 1) % list.count]
        prefs.aspectId = next.rawValue
        viewfinder.overlay.setAspect(next.ratio, animated: true)
        var st = settings
        st.aspect = next
        commit(st)
    }

    private func toggleMode() {
        guard !recording && !bursting else { return }
        var st = settings
        st.mode = settings.mode == .video ? .photo : .video
        st.zoom = 1
        if st.mode == .video && st.flash == .flash { st.flash = .off }
        if st.mode == .video && !st.shutterAuto { st.exposure = min(st.exposure, 1.0 / 30.0) }
        if st.mode == .video && AVCaptureDevice.authorizationStatus(for: .audio) == .notDetermined {
            AVCaptureDevice.requestAccess(for: .audio) { [weak self] _ in
                DispatchQueue.main.async { if let self = self, self.settings.mode == .video { self.camera.update(self.settings) } }
            }
        }
        viewfinder.zoom = 1
        viewfinder.overlay.setAspect(st.mode == .video ? nil : st.aspect.ratio, animated: true)
        commit(st)
        rebuildDial()
        bindCaps(animated: true)
    }

    // MARK: - Capture

    private func shoot() {
        if cameraError != nil { retry(); return }
        if settings.mode == .video {
            if recording { camera.stopRecording() } else { camera.startRecording(orientation: orientation.orientation) }
        } else {
            camera.capture(orientation: orientation.orientation)
        }
    }

    private func startBurst() {
        guard settings.mode == .photo, cameraError == nil else { return }
        bursting = true
        camera.startBurst(orientation: orientation.orientation)
    }

    private func stopBurst() {
        guard bursting else { return }
        bursting = false
        camera.stopBurst()
    }

    private func retry() {
        cameraError = nil
        viewfinder.overlay.centerText = ""
        if let s = sensor { camera.select(s, settings: settings); camera.start() }
    }

    private func openLast() {
        guard let img = lastPreview else { return }
        present(QuickViewer(image: img), animated: true)
    }

    private func viewfinderTapped(device: CGPoint, point: CGPoint) {
        if permissionDenied {
            if let url = URL(string: UIApplication.openSettingsURLString) { UIApplication.shared.open(url) }
            return
        }
        if cameraError != nil { retry(); return }
        if activeDial != nil { closeDial(); return }
        guard device.x >= 0, let s = sensor, s.autofocus else { return }
        guard settings.afOn else { toast("MANUAL FOCUS · TAP AF TO ENABLE"); return }
        lastTap = point
        camera.focus(at: device)
    }

    private func arcChanged(_ arc: ArcControls.Arc, _ t: CGFloat, done: Bool) {
        guard let s = sensor else { return }
        if arc === deck.arcs.focus {
            guard s.manualFocus else { return }
            var st = settings
            st.lensPosition = Float(t)
            if st.afOn {
                st.afOn = false
                toast("MANUAL FOCUS")
                viewfinder.overlay.focus(.idle, at: .zero)
            }
            commit(st, immediate: done)
        } else {
            let z = zoomFor(t)
            if settings.mode == .photo { viewfinder.zoom = z }
            var st = settings
            st.zoom = z
            commit(st, immediate: done)
        }
    }

    // MARK: - Hardware buttons (volume / Camera Control, iOS 17.2+)

    private func installHardwareButtons() {
        if #available(iOS 17.2, *) {
            let interaction = AVCaptureEventInteraction(handler: { [weak self] event in
                guard let self = self else { return }
                switch event.phase {
                case .began: self.hardwareDown()
                case .ended: self.hardwareUp(cancelled: false)
                case .cancelled: self.hardwareUp(cancelled: true)
                @unknown default: break
                }
            })
            view.addInteraction(interaction)
        }
    }

    private func hardwareDown() {
        guard root.sheets.allSatisfy({ !$0.isShowing }), sensor != nil else { return }
        hwDownAt = Date()
        hwBurst = false
        hwTimer?.invalidate()
        if settings.mode == .photo {
            hwTimer = Timer.scheduledTimer(withTimeInterval: 0.45, repeats: false) { [weak self] _ in
                guard let self = self, self.hwDownAt != nil else { return }
                self.hwBurst = true
                self.deck.shutter.externalBurst(true)
            }
        }
    }

    private func hardwareUp(cancelled: Bool) {
        hwTimer?.invalidate()
        guard hwDownAt != nil else { return }
        hwDownAt = nil
        if hwBurst {
            hwBurst = false
            deck.shutter.externalBurst(false)
        } else if !cancelled {
            shoot()
        }
    }

    // MARK: - Sheets

    private func showSheet(_ s: Sheet) {
        for o in root.sheets where o !== s && o.isShowing && o.dismissible { o.dismiss() }
        s.show()
    }

    private func row(_ title: String, _ value: String = "", style: MenuRow.Style = .text, note: String = "", on: Bool = false, tap: (() -> Void)? = nil) -> MenuRow {
        let r = MenuRow(palette: palette)
        r.title = title
        r.value = value
        r.style = style
        r.note = note
        r.on = on
        r.onTap = tap
        r.refresh()
        return r
    }

    private func header(_ title: String) -> MenuRow { row(title, style: .header) }

    private func toggleRow(_ title: String, _ value: Bool, note: String, enabled: Bool = true, change: @escaping (Bool) -> Void) -> MenuRow {
        let r = row(title, style: .toggle, note: note, on: value)
        r.enabled = enabled
        r.onTap = { [weak r] in
            guard let r = r else { return }
            r.on.toggle()
            r.refresh()
            change(r.on)
        }
        return r
    }

    private func openMenu() {
        let s = sensor
        var rows: [UIView] = []
        rows.append(header("SLIDERS"))
        rows.append(toggleRow("ZOOM SLIDER", prefs.zoomSlider, note: "Photo zoom crops the sensor frame (preview, JPEG and DNG crop).") { [weak self] on in
            guard let self = self else { return }
            self.prefs.zoomSlider = on
            if !on {
                self.viewfinder.zoom = 1
                var st = self.settings
                st.zoom = 1
                self.commit(st)
            }
            self.bindCaps(animated: true)
        })
        rows.append(toggleRow("FOCUS SLIDER", prefs.focusSlider, note: s?.manualFocus == false ? "This sensor has no adjustable focus." : "") { [weak self] on in
            self?.prefs.focusSlider = on
            self?.bindCaps(animated: true)
        })
        rows.append(header("CAPTURE"))
        rows.append(toggleRow("OPTICAL STAB.", prefs.ois,
                              note: s?.lensStabilization == true ? "Lens stabilization for photos (one-frame bracket). Bursts always use it." : "Not exposed for this sensor.",
                              enabled: s?.lensStabilization == true) { [weak self] on in
            guard let self = self else { return }
            self.prefs.ois = on
            var st = self.settings
            st.ois = on
            self.commit(st)
        })
        rows.append(toggleRow("SAVE DNG", prefs.saveDng, note: "The JPEG developed from RAW is always saved to Photos.") { [weak self] on in
            guard let self = self else { return }
            self.prefs.saveDng = on
            var st = self.settings
            st.saveDng = on
            self.commit(st)
        })
        let folderLabel = camera.dngFolder?.lastPathComponent ?? "Files › Photoliz › DNG"
        rows.append(row("DNG FOLDER", folderLabel, style: .action, note: camera.dngFolder == nil ? "Automatic folder, created on the first DNG." : "") { [weak self] in
            self?.pickFolder()
        })
        if camera.dngFolder != nil {
            rows.append(row("USE AUTOMATIC FOLDER", "Files › Photoliz › DNG", style: .action) { [weak self] in
                self?.prefs.dngFolderBookmark = nil
                self?.camera.dngFolder = nil
                self?.openMenu()
            })
        }
        rows.append(header("EXTRA CROPS"))
        let crops = ChoiceRow(palette: palette)
        crops.choices = AspectRatio.allCases.filter { $0.isExtra }.map { ChoiceRow.Choice(id: $0.rawValue, label: $0.label, swatches: nil) }
        crops.selected = prefs.extraAspects
        crops.onToggle = { [weak self, weak crops] id in
            guard let self = self else { return }
            var set = self.prefs.extraAspects
            if set.contains(id) { set.remove(id) } else { set.insert(id) }
            self.prefs.extraAspects = set
            crops?.selected = set
            if self.settings.aspect.isExtra && !set.contains(self.settings.aspect.rawValue) {
                self.prefs.aspectId = AspectRatio.trueToSensor.rawValue
                self.viewfinder.overlay.setAspect(nil, animated: true)
                var st = self.settings
                st.aspect = .trueToSensor
                self.commit(st)
            }
        }
        rows.append(crops)
        rows.append(header("PALETTE"))
        let pal = ChoiceRow(palette: palette)
        pal.choices = Palette.all.map { ChoiceRow.Choice(id: $0.name, label: $0.name.uppercased(), swatches: $0.swatches) }
        pal.selected = [palette.name]
        pal.onToggle = { [weak self, weak pal] name in
            self?.applyPalette(Palette.named(name))
            pal?.selected = [name]
        }
        rows.append(pal)
        rows.append(header("INFO"))
        rows.append(row("HARDWARE REPORT", style: .action) { [weak self] in self?.showReport(mandatory: false) })
        rows.append(row("DESCRIPTION", style: .action) { [weak self] in self?.showAbout() })
        rows.append(row("VERSION", AppInfo.version))
        menuSheet.setRows(rows)
        showSheet(menuSheet)
    }

    private func openPicker() {
        guard !recording, !sensors.isEmpty else { return }
        var rows: [UIView] = []
        var lastGroup = ""
        for s in sensors {
            if s.facingName != lastGroup { rows.append(header(s.facingName)); lastGroup = s.facingName }
            let r = row(s.label, s.detail) { [weak self] in
                self?.pickerSheet.dismiss()
                if self?.sensor?.id != s.id { self?.select(s) }
            }
            if sensor?.id == s.id { r.icon = PixelIcons.check; r.iconColor = palette.accentOnSurface }
            rows.append(r)
        }
        pickerSheet.setRows(rows)
        showSheet(pickerSheet)
    }

    private func showReport(mandatory: Bool) {
        reportSheet.setRows(HardwareReport.rows(sensors: sensors, palette: palette))
        reportSheet.dismissible = !mandatory
        let b = WideButton(palette: palette)
        b.label = "I UNDERSTAND"
        b.onTap = { [weak self] in
            guard let self = self else { return }
            self.prefs.reportAccepted = true
            self.reportSheet.dismissible = true
            self.reportSheet.dismiss()
            if self.reportPending {
                self.reportPending = false
                self.openInitial()
            }
        }
        reportSheet.footer = b
        showSheet(reportSheet)
    }

    private func showAbout() {
        var rows: [UIView] = []
        let t = TextBlock(palette: palette)
        t.text = AppInfo.tagline
        t.size = 14
        rows.append(t)
        rows.append(header("CREDITS"))
        for c in AppInfo.credits {
            rows.append(row(c.name, c.role, note: c.link ?? "") {
                if let l = c.link, let url = URL(string: "https://" + l) { UIApplication.shared.open(url) }
            })
        }
        rows.append(header("CONTACT"))
        rows.append(row("WEBSITE", AppInfo.website, style: .action) { UIApplication.shared.open(AppInfo.websiteURL) })
        rows.append(row("MAIL", AppInfo.mail, style: .action) {
            if let url = URL(string: "mailto:" + AppInfo.mail) { UIApplication.shared.open(url) }
        })
        rows.append(header("ABOUT"))
        rows.append(row("VERSION", AppInfo.version))
        rows.append(row("DEVELOPER", AppInfo.developer))
        aboutSheet.setRows(rows)
        showSheet(aboutSheet)
    }

    private func pickFolder() {
        // The string-UTI initializer works on every supported iOS version (13+) without linking
        // UniformTypeIdentifiers, which does not exist on iOS 13.
        let picker = UIDocumentPickerViewController(documentTypes: ["public.folder"], in: .open)
        picker.delegate = self
        picker.allowsMultipleSelection = false
        present(picker, animated: true)
    }

    func documentPicker(_ controller: UIDocumentPickerViewController, didPickDocumentsAt urls: [URL]) {
        guard let url = urls.first else { return }
        if let bm = MediaSaver.bookmark(for: url) {
            prefs.dngFolderBookmark = bm
            camera.dngFolder = MediaSaver.resolve(bookmark: bm) ?? url
            toast("DNG → " + url.lastPathComponent.uppercased())
        } else {
            toast("FOLDER NOT WRITABLE")
        }
        if menuSheet.isShowing { openMenu() }
    }

    // MARK: - CameraControllerDelegate

    func cameraConfigured(sensor s: SensorInfo, mode: CaptureMode, formatSize size: CGSize, raw: Bool, stillSize still: CMVideoDimensions?) {
        cameraError = nil
        viewfinder.overlay.centerText = ""
        formatSize = size
        stillSize = still
        rawActive = raw
        if size.width > 0 { viewfinder.formatSize = size }
        viewfinder.orientPreview()
        viewfinder.overlay.setAspect(mode == .video ? nil : settings.aspect.ratio, animated: false)
        refreshInfo()
    }

    func cameraFailed(_ message: String, recoverable: Bool) {
        cameraError = message
        viewfinder.overlay.centerText = recoverable ? message + "\nTAP TO RETRY" : message
        deck.shutter.busy = false
        if bursting { bursting = false }
    }

    func cameraInterrupted(_ interrupted: Bool) {
        viewfinder.overlay.centerText = interrupted ? "CAMERA IN USE" : ""
    }

    func cameraLive(_ state: LiveState) {
        live = state
        refreshReadouts()
        if let key = activeDial, !dial.isDragging, settings.exposureMode == .auto { dial.move(to: dialPosition(key)) }
        if settings.afOn && sensor?.manualFocus == true { deck.arcs.setValue(deck.arcs.focus, CGFloat(state.lensPosition)) }
    }

    func cameraFocus(_ phase: FocusPhase) {
        let r = viewfinder.contentRect
        let p = lastTap ?? CGPoint(x: r.midX, y: r.midY)
        viewfinder.overlay.focus(phase, at: p)
        deck.shutter.busy = phase == .scanning
        if phase != .scanning { lastTap = nil }
    }

    func cameraShutter() {
        viewfinder.overlay.blink()
    }

    func cameraSaved(thumbnail: UIImage?, preview: UIImage?) {
        if let t = thumbnail { deck.thumb.show(t) }
        lastPreview = preview ?? thumbnail
    }

    func cameraNotice(_ message: String) { toast(message) }

    func cameraBurst(active: Bool, count: Int) {
        deck.shutter.burstCount = count
        if !active {
            bursting = false
            if count > 0 { toast("BURST · \(count) FRAMES") }
        }
    }

    func cameraRecording(active: Bool, started: Date?) {
        recording = active
        deck.shutter.recording = active
        viewfinder.overlay.recordingSince = active ? started : nil
        deck.modeChip.dimmed = active
        cameraChip.dimmed = active
    }

    func cameraBusy(_ busy: Bool) { deck.shutter.busy = busy }
}
