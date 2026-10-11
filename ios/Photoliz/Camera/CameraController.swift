import AVFoundation
import Photos
import UIKit

protocol CameraControllerDelegate: AnyObject {
    func cameraConfigured(sensor: SensorInfo, mode: CaptureMode, formatSize: CGSize, raw: Bool, stillSize: CMVideoDimensions?)
    func cameraFailed(_ message: String, recoverable: Bool)
    func cameraLive(_ state: LiveState)
    func cameraFocus(_ phase: FocusPhase)
    func cameraShutter()
    func cameraSaved(thumbnail: UIImage?, preview: UIImage?)
    func cameraNotice(_ message: String)
    func cameraBurst(active: Bool, count: Int)
    func cameraRecording(active: Bool, started: Date?)
    func cameraBusy(_ busy: Bool)
    func cameraInterrupted(_ interrupted: Bool)
}

/// AVFoundation engine. All session / device work happens on one serial queue; delegate calls arrive on main.
/// Only hardware controls are used; anything a device does not support is never set.
final class CameraController: NSObject {
    weak var delegate: CameraControllerDelegate?

    let session = AVCaptureSession()
    private let queue = DispatchQueue(label: "dev.panasko.photoliz.session", qos: .userInitiated)
    private let photoOutput = AVCapturePhotoOutput()
    private let movieOutput = AVCaptureMovieFileOutput()
    private let processor = PhotoProcessor()

    private(set) var sensors: [SensorInfo] = []
    private var sensor: SensorInfo?
    private var videoInput: AVCaptureDeviceInput?
    private var audioInput: AVCaptureDeviceInput?
    private var settings = CaptureSettings()
    private var configuredMode: CaptureMode?
    private var configuredResolution = -1
    private var observations: [NSKeyValueObservation] = []
    private var notificationTokens: [NSObjectProtocol] = []
    private let liveLock = NSLock()
    private var lastLive = Date.distantPast
    private var livePending = false

    // Capture state (session queue only)
    private var inFlight = 0
    private var stillBusy = false
    private var burstActive = false
    private var burstCount = 0
    private var burstOrientation = 0
    private var burstStart = Date()
    private var captureDelegates: [Int64: PhotoCaptureDelegate] = [:]
    private var focusPoint = CGPoint(x: 0.5, y: 0.5)
    private var afCompletion: ((Bool) -> Void)?
    private var afSawAdjusting = false
    private var afToken = 0
    private var recordingStarted: Date?
    private var wantsRunning = false

    var dngFolder: URL? {
        get { processor.dngFolder }
        set { processor.dngFolder = newValue }
    }

    override init() {
        super.init()
        processor.notice = { [weak self] msg in self?.main { $0.delegate?.cameraNotice(msg) } }
        processor.onProcessed = { [weak self] in
            guard let self = self else { return }
            self.queue.async { self.pumpBurst() }
        }
        observeSession()
    }

    deinit {
        notificationTokens.forEach { NotificationCenter.default.removeObserver($0) }
    }

    private func main(_ block: @escaping (CameraController) -> Void) {
        DispatchQueue.main.async { [weak self] in
            guard let self = self else { return }
            block(self)
        }
    }

    // MARK: - Discovery

    /// Discovers physical sensors and reads their capabilities by briefly configuring the session with each one.
    func scan(completion: @escaping ([SensorInfo]) -> Void) {
        queue.async {
            let list = SensorInfo.discover()
            for s in list { self.scanCapabilities(s) }
            self.sensors = list
            DispatchQueue.main.async { completion(list) }
        }
    }

    private func scanCapabilities(_ s: SensorInfo) {
        session.beginConfiguration()
        for i in session.inputs { session.removeInput(i) }
        for o in session.outputs { session.removeOutput(o) }
        var ok = false
        if let input = try? AVCaptureDeviceInput(device: s.device), session.canAddInput(input) {
            session.addInput(input)
            if session.canSetSessionPreset(.photo) { session.sessionPreset = .photo }
            if session.canAddOutput(photoOutput) {
                session.addOutput(photoOutput)
                ok = true
            }
        }
        session.commitConfiguration()
        guard ok else { return }
        let raws = photoOutput.availableRawPhotoPixelFormatTypes
        if #available(iOS 14.3, *) {
            s.bayerRaw = raws.first(where: { AVCapturePhotoOutput.isBayerRAWPixelFormat($0) })
        } else {
            s.bayerRaw = raws.first
        }
        s.flashModes = photoOutput.supportedFlashModes
        s.lensStabilization = photoOutput.isLensStabilizationDuringBracketedCaptureSupported && photoOutput.maxBracketedCapturePhotoCount >= 1
        let f = s.device.activeFormat
        s.isoRange = f.minISO...max(f.minISO, f.maxISO)
        let minE = CMTimeGetSeconds(f.minExposureDuration)
        let maxE = CMTimeGetSeconds(f.maxExposureDuration)
        if minE.isFinite && maxE.isFinite && maxE >= minE { s.exposureRange = minE...maxE }
        s.fov = f.videoFieldOfView
        s.videoMaxZoom = f.videoMaxZoomFactor
        if #available(iOS 16.0, *) {
            // Keep the binned and the full readout; skip fused intermediate sizes (e.g. 24MP).
            let dims = f.supportedMaxPhotoDimensions.sorted { Int($0.width) * Int($0.height) < Int($1.width) * Int($1.height) }
            if let a = dims.first, let b = dims.last {
                s.resolutions = (a.width == b.width && a.height == b.height) ? [a] : [a, b]
            }
        } else {
            s.resolutions = [f.highResolutionStillImageDimensions]
        }
        s.scanned = true
        for i in session.inputs { session.removeInput(i) }
    }

    // MARK: - Session

    func select(_ s: SensorInfo, settings newSettings: CaptureSettings) {
        queue.async {
            self.sensor = s
            self.settings = newSettings
            self.configuredMode = nil
            self.configure()
        }
    }

    func update(_ newSettings: CaptureSettings) {
        queue.async {
            let old = self.settings
            self.settings = newSettings
            guard self.sensor != nil else { return }
            if old.mode != newSettings.mode || old.resolutionIndex != newSettings.resolutionIndex {
                if self.movieOutput.isRecording { self.movieOutput.stopRecording() }
                self.configure()
            } else {
                self.applyDevice(old: old)
            }
        }
    }

    func start() {
        queue.async {
            self.wantsRunning = true
            if self.sensor != nil && !self.session.isRunning { self.session.startRunning() }
        }
    }

    func stop() {
        queue.async {
            self.wantsRunning = false
            self.burstActive = false
            if self.movieOutput.isRecording { self.movieOutput.stopRecording() }
            if self.session.isRunning { self.session.stopRunning() }
        }
    }

    private func fail(_ message: String, recoverable: Bool = true) {
        main { $0.delegate?.cameraFailed(message, recoverable: recoverable) }
    }

    private func configure() {
        guard let s = sensor else { return }
        let mode = settings.mode
        observations.removeAll()
        session.beginConfiguration()
        for i in session.inputs { session.removeInput(i) }
        for o in session.outputs { session.removeOutput(o) }
        videoInput = nil
        audioInput = nil
        guard let input = try? AVCaptureDeviceInput(device: s.device), session.canAddInput(input) else {
            session.commitConfiguration()
            fail("CAMERA UNAVAILABLE")
            return
        }
        session.addInput(input)
        videoInput = input
        if mode == .photo {
            if session.canSetSessionPreset(.photo) { session.sessionPreset = .photo }
            if session.canAddOutput(photoOutput) { session.addOutput(photoOutput) }
        } else {
            let presets: [AVCaptureSession.Preset] = [.hd4K3840x2160, .hd1920x1080, .high]
            if let p = presets.first(where: { s.device.supportsSessionPreset($0) && session.canSetSessionPreset($0) }) {
                session.sessionPreset = p
            }
            if session.canAddOutput(movieOutput) { session.addOutput(movieOutput) }
            if AVCaptureDevice.authorizationStatus(for: .audio) == .authorized,
               let mic = AVCaptureDevice.default(for: .audio),
               let ai = try? AVCaptureDeviceInput(device: mic), session.canAddInput(ai) {
                session.addInput(ai)
                audioInput = ai
            }
        }
        session.commitConfiguration()
        configuredMode = mode
        configureOutputs(s)
        configureDevice(s)
        observe(s.device)
        if wantsRunning && !session.isRunning { session.startRunning() }

        let dims = CMVideoFormatDescriptionGetDimensions(s.device.activeFormat.formatDescription)
        let raw = mode == .photo && s.bayerRaw != nil && photoOutput.availableRawPhotoPixelFormatTypes.contains(s.bayerRaw!)
        var still: CMVideoDimensions? = nil
        if mode == .photo {
            if #available(iOS 16.0, *) { still = photoOutput.maxPhotoDimensions } else { still = s.device.activeFormat.highResolutionStillImageDimensions }
        }
        main { $0.delegate?.cameraConfigured(sensor: s, mode: mode, formatSize: CGSize(width: CGFloat(dims.width), height: CGFloat(dims.height)), raw: raw, stillSize: still) }
    }

    private func configureOutputs(_ s: SensorInfo) {
        if settings.mode == .photo {
            if #available(iOS 16.0, *) {
                if !s.resolutions.isEmpty {
                    let idx = min(max(settings.resolutionIndex, 0), s.resolutions.count - 1)
                    let target = s.resolutions[idx]
                    let supported = s.device.activeFormat.supportedMaxPhotoDimensions
                    if supported.contains(where: { $0.width == target.width && $0.height == target.height }) {
                        try? PLZObjC.catchException { self.photoOutput.maxPhotoDimensions = target }
                    }
                }
            } else {
                try? PLZObjC.catchException { self.photoOutput.isHighResolutionCaptureEnabled = true }
            }
            if let c = photoOutput.connection(with: .video) { unmirror(c) }
        } else if let c = movieOutput.connection(with: .video) {
            if c.isVideoStabilizationSupported { c.preferredVideoStabilizationMode = .off }
            unmirror(c)
        }
    }

    /// Files are true to the sensor: never mirrored (the front preview still mirrors like a mirror).
    private func unmirror(_ c: AVCaptureConnection) {
        guard c.isVideoMirroringSupported else { return }
        try? PLZObjC.catchException {
            c.automaticallyAdjustsVideoMirroring = false
            c.isVideoMirrored = false
        }
    }

    private func withDevice(_ d: AVCaptureDevice, _ body: () -> Void) {
        do { try d.lockForConfiguration() } catch { return }
        defer { d.unlockForConfiguration() }
        do {
            try PLZObjC.catchException(body)
        } catch {
            main { $0.delegate?.cameraNotice("SETTING REJECTED") }
        }
    }

    private func configureDevice(_ s: SensorInfo) {
        let d = s.device
        withDevice(d) {
            d.isSubjectAreaChangeMonitoringEnabled = false
            let f = d.activeFormat
            if f.isVideoHDRSupported {
                d.automaticallyAdjustsVideoHDREnabled = false
                d.isVideoHDREnabled = false
            }
            if d.isLowLightBoostSupported { d.automaticallyEnablesLowLightBoostWhenAvailable = false }
            if f.isGlobalToneMappingSupported { d.isGlobalToneMappingEnabled = false }
            if settings.mode == .video, f.videoSupportedFrameRateRanges.contains(where: { $0.minFrameRate <= 30 && $0.maxFrameRate >= 30 }) {
                d.activeVideoMinFrameDuration = CMTime(value: 1, timescale: 30)
                d.activeVideoMaxFrameDuration = CMTime(value: 1, timescale: 30)
            }
            // Autofocus never acts without user input: park the lens.
            if !settings.afOn && s.manualFocus {
                d.setFocusModeLocked(lensPosition: min(max(settings.lensPosition, 0), 1), completionHandler: nil)
            } else if d.isFocusModeSupported(.locked) {
                d.focusMode = .locked
            }
            applyExposure(d, s)
            applyZoom(d)
            applyTorch(d, s)
        }
    }

    private func applyDevice(old: CaptureSettings) {
        guard let s = sensor else { return }
        let d = s.device
        withDevice(d) {
            if old.isoAuto != settings.isoAuto || old.shutterAuto != settings.shutterAuto || old.iso != settings.iso || old.exposure != settings.exposure {
                applyExposure(d, s)
            }
            if !settings.afOn && s.manualFocus && (old.afOn || old.lensPosition != settings.lensPosition) {
                // AF switched off mid-scan: let a waiting shot go ahead without it.
                if let pending = afCompletion {
                    afCompletion = nil
                    afToken += 1
                    pending(false)
                }
                d.setFocusModeLocked(lensPosition: min(max(settings.lensPosition, 0), 1), completionHandler: nil)
            }
            if old.zoom != settings.zoom { applyZoom(d) }
            if old.flash != settings.flash { applyTorch(d, s) }
        }
    }

    private func applyExposure(_ d: AVCaptureDevice, _ s: SensorInfo) {
        if settings.exposureMode == .auto || !s.manualExposure {
            if d.isExposureModeSupported(.continuousAutoExposure) { d.exposureMode = .continuousAutoExposure }
            return
        }
        let f = d.activeFormat
        let minD = CMTimeGetSeconds(f.minExposureDuration)
        var maxD = CMTimeGetSeconds(f.maxExposureDuration)
        if settings.mode == .video { maxD = min(maxD, 1.0 / 30.0) }
        let duration = min(max(settings.exposure, minD), max(minD, maxD))
        let iso = min(max(settings.iso, f.minISO), f.maxISO)
        d.setExposureModeCustom(duration: CMTime(secondsValue: duration), iso: iso, completionHandler: nil)
    }

    private func applyZoom(_ d: AVCaptureDevice) {
        // Photo zoom is a crop of the full RAW frame (Bayer RAW cannot be zoomed by the ISP); video uses the ISP.
        let z: CGFloat = settings.mode == .video ? min(max(settings.zoom, 1), d.activeFormat.videoMaxZoomFactor) : 1
        if abs(d.videoZoomFactor - z) > 0.001 { d.videoZoomFactor = z }
    }

    private func applyTorch(_ d: AVCaptureDevice, _ s: SensorInfo) {
        guard s.torch else { return }
        let want: AVCaptureDevice.TorchMode = settings.flash == .torch ? .on : .off
        if want == .on && !d.isTorchAvailable { return }
        if d.torchMode != want && d.isTorchModeSupported(want) { d.torchMode = want }
    }

    // MARK: - Observation

    private func observe(_ d: AVCaptureDevice) {
        observations = [
            d.observe(\.iso, options: [.new]) { [weak self] dev, _ in self?.liveChanged(dev) },
            d.observe(\.exposureDuration, options: [.new]) { [weak self] dev, _ in self?.liveChanged(dev) },
            d.observe(\.lensPosition, options: [.new]) { [weak self] dev, _ in self?.liveChanged(dev) },
            d.observe(\.isAdjustingFocus, options: [.new]) { [weak self] dev, _ in
                let adjusting = dev.isAdjustingFocus
                self?.queue.async { self?.focusAdjustingChanged(adjusting) }
            },
        ]
        liveChanged(d)
    }

    /// KVO fires on arbitrary threads, often every frame: coalesce to ~12 updates per second, always delivering the
    /// latest values (a trailing update follows the last change).
    private func liveChanged(_ d: AVCaptureDevice) {
        liveLock.lock()
        if livePending {
            liveLock.unlock()
            return
        }
        livePending = true
        let wait = max(0, 0.08 - Date().timeIntervalSince(lastLive))
        liveLock.unlock()
        DispatchQueue.main.asyncAfter(deadline: .now() + wait) { [weak self] in
            guard let self = self else { return }
            self.liveLock.lock()
            self.livePending = false
            self.lastLive = Date()
            self.liveLock.unlock()
            let state = LiveState(iso: d.iso, exposure: CMTimeGetSeconds(d.exposureDuration), lensPosition: d.lensPosition, aperture: d.lensAperture)
            self.delegate?.cameraLive(state)
        }
    }

    private func observeSession() {
        let nc = NotificationCenter.default
        let interrupted = Notification.Name("AVCaptureSessionWasInterruptedNotification")
        let ended = Notification.Name("AVCaptureSessionInterruptionEndedNotification")
        let runtime = Notification.Name("AVCaptureSessionRuntimeErrorNotification")
        notificationTokens.append(nc.addObserver(forName: interrupted, object: session, queue: nil) { [weak self] _ in
            self?.main { $0.delegate?.cameraInterrupted(true) }
        })
        notificationTokens.append(nc.addObserver(forName: ended, object: session, queue: nil) { [weak self] _ in
            self?.main { $0.delegate?.cameraInterrupted(false) }
        })
        notificationTokens.append(nc.addObserver(forName: runtime, object: session, queue: nil) { [weak self] note in
            guard let self = self else { return }
            let err = note.userInfo?["AVCaptureSessionErrorKey"] as? NSError
            if err?.code == AVError.mediaServicesWereReset.rawValue {
                self.queue.async { if self.wantsRunning { self.session.startRunning() } }
            } else {
                self.fail("CAMERA ERROR")
            }
        })
    }

    // MARK: - Focus

    /// Tap to focus at a point in device coordinates (0...1, landscape sensor space).
    func focus(at point: CGPoint) {
        queue.async {
            // While a shot is focusing or a burst runs, the lens belongs to the capture.
            guard !self.stillBusy && !self.burstActive else { return }
            self.focusPoint = point
            self.runAutofocus { _ in }
        }
    }

    private func runAutofocus(_ completion: @escaping (Bool) -> Void) {
        guard let s = sensor, s.autofocus, settings.afOn else { completion(true); return }
        let d = s.device
        // A newer request replaces a running one: settle the old one first so its owner (a shot) never hangs.
        if let previous = afCompletion {
            afCompletion = nil
            previous(false)
        }
        afToken += 1
        let token = afToken
        afSawAdjusting = false
        afCompletion = completion
        main { $0.delegate?.cameraFocus(.scanning) }
        withDevice(d) {
            if d.isFocusPointOfInterestSupported { d.focusPointOfInterest = focusPoint }
            if settings.exposureMode == .auto && d.isExposurePointOfInterestSupported {
                d.exposurePointOfInterest = focusPoint
                if d.isExposureModeSupported(.continuousAutoExposure) { d.exposureMode = .continuousAutoExposure }
            }
            d.focusMode = .autoFocus
        }
        // If the lens never reports adjusting (already in focus), finish quickly.
        queue.asyncAfter(deadline: .now() + 0.35) {
            if token == self.afToken && !self.afSawAdjusting && !d.isAdjustingFocus { self.finishAutofocus(true) }
        }
        queue.asyncAfter(deadline: .now() + 2.5) {
            if token == self.afToken { self.finishAutofocus(false) }
        }
    }

    private func focusAdjustingChanged(_ adjusting: Bool) {
        guard afCompletion != nil else { return }
        if adjusting { afSawAdjusting = true } else if afSawAdjusting { finishAutofocus(true) }
    }

    private func finishAutofocus(_ ok: Bool) {
        guard let c = afCompletion else { return }
        afCompletion = nil
        afToken += 1
        main { $0.delegate?.cameraFocus(ok ? .locked : .failed) }
        c(ok)
    }

    // MARK: - Photo capture

    func capture(orientation: Int) {
        queue.async {
            guard self.settings.mode == .photo, self.session.isRunning, self.configuredMode == .photo else { return }
            guard !self.stillBusy && !self.burstActive else { return }
            if self.inFlight >= 2 || self.processor.pending >= 3 {
                self.main { $0.delegate?.cameraNotice("BUFFER FULL") }
                return
            }
            self.stillBusy = true
            self.main { $0.delegate?.cameraBusy(true) }
            self.runAutofocus { _ in
                self.shoot(index: 0, orientation: orientation, time: Date())
                self.stillBusy = false
                self.main { $0.delegate?.cameraBusy(false) }
            }
        }
    }

    func startBurst(orientation: Int) {
        queue.async {
            guard self.settings.mode == .photo, self.configuredMode == .photo, self.session.isRunning,
                  !self.burstActive, !self.stillBusy else { return }
            self.burstActive = true
            self.burstCount = 0
            self.burstOrientation = orientation
            self.burstStart = Date()
            self.main { $0.delegate?.cameraBurst(active: true, count: 0) }
            self.runAutofocus { _ in self.pumpBurst() }
        }
    }

    func stopBurst() {
        queue.async {
            guard self.burstActive else { return }
            self.burstActive = false
            let n = self.burstCount
            self.main { $0.delegate?.cameraBurst(active: false, count: n) }
        }
    }

    private func pumpBurst() {
        while burstActive && inFlight < 2 && processor.pending < 3 {
            burstCount += 1
            let n = burstCount
            if !shoot(index: n, orientation: burstOrientation, time: burstStart) { burstActive = false; break }
            main { $0.delegate?.cameraBurst(active: true, count: n) }
        }
    }

    private func videoOrientation(_ o: Int) -> AVCaptureVideoOrientation {
        switch o {
        case 90: return .landscapeLeft
        case 180: return .portraitUpsideDown
        case 270: return .landscapeRight
        default: return .portrait
        }
    }

    /// Orientation of captured files. Same equivalence Apple documents between the two APIs.
    private func orient(_ c: AVCaptureConnection, _ o: Int) {
        if #available(iOS 17.0, *) {
            let angle = CGFloat((o + 90) % 360)
            if c.isVideoRotationAngleSupported(angle) { c.videoRotationAngle = angle }
        } else if c.isVideoOrientationSupported {
            c.videoOrientation = videoOrientation(o)
        }
    }

    private func makePhotoSettings(_ s: SensorInfo) -> AVCapturePhotoSettings {
        let ps: AVCapturePhotoSettings
        let wantOIS = (settings.ois || burstActive) && s.lensStabilization && settings.flash != .flash
        if let raw = s.bayerRaw, photoOutput.availableRawPhotoPixelFormatTypes.contains(raw) {
            if wantOIS {
                // Lens stabilization is only exposed for bracketed captures: a one-frame bracket.
                let exposure: AVCaptureBracketedStillImageSettings
                if settings.exposureMode == .manual && s.manualExposure {
                    exposure = AVCaptureManualExposureBracketedStillImageSettings.manualExposureSettings(
                        exposureDuration: AVCaptureDevice.currentExposureDuration, iso: AVCaptureDevice.currentISO)
                } else {
                    exposure = AVCaptureAutoExposureBracketedStillImageSettings.autoExposureSettings(
                        exposureTargetBias: AVCaptureDevice.currentExposureTargetBias)
                }
                let b = AVCapturePhotoBracketSettings(rawPixelFormatType: raw, processedFormat: nil, bracketedSettings: [exposure])
                b.isLensStabilizationEnabled = true
                ps = b
            } else {
                ps = AVCapturePhotoSettings(rawPixelFormatType: raw)
            }
        } else {
            // No Bayer RAW on this sensor: the least processed photo the pipeline offers.
            if photoOutput.availablePhotoCodecTypes.contains(.jpeg) {
                ps = AVCapturePhotoSettings(format: [AVVideoCodecKey: AVVideoCodecType.jpeg])
            } else {
                ps = AVCapturePhotoSettings()
            }
        }
        // Bayer RAW and one-frame brackets get no multi-frame processing anyway; .speed is valid for every output.
        ps.photoQualityPrioritization = .speed
        if !(ps is AVCapturePhotoBracketSettings) {
            ps.flashMode = (settings.flash == .flash && photoOutput.supportedFlashModes.contains(.on)) ? .on : .off
        }
        if #available(iOS 16.0, *) {
            ps.maxPhotoDimensions = photoOutput.maxPhotoDimensions
        } else {
            ps.isHighResolutionPhotoEnabled = photoOutput.isHighResolutionCaptureEnabled
        }
        return ps
    }

    @discardableResult
    private func shoot(index: Int, orientation: Int, time: Date) -> Bool {
        guard let s = sensor, session.isRunning else { return false }
        if let c = photoOutput.connection(with: .video) { orient(c, orientation) }
        let ps = makePhotoSettings(s)
        let ctx = ShotContext(settings: settings, time: time, index: index, sensorLabel: s.label, manual: settings.exposureMode == .manual)
        let id = ps.uniqueID
        let del = PhotoCaptureDelegate(context: ctx, processor: processor)
        del.onShutter = { [weak self] in self?.main { $0.delegate?.cameraShutter() } }
        del.onSaved = { [weak self] out in self?.main { $0.delegate?.cameraSaved(thumbnail: out.thumbnail, preview: out.preview) } }
        del.onError = { [weak self] msg in self?.main { $0.delegate?.cameraNotice(msg) } }
        del.onFinished = { [weak self] in
            self?.queue.async {
                guard let self = self else { return }
                self.captureDelegates[id] = nil
                self.inFlight = max(0, self.inFlight - 1)
                self.pumpBurst()
            }
        }
        captureDelegates[id] = del
        inFlight += 1
        do {
            try PLZObjC.catchException { self.photoOutput.capturePhoto(with: ps, delegate: del) }
            return true
        } catch {
            captureDelegates[id] = nil
            inFlight = max(0, inFlight - 1)
            main { $0.delegate?.cameraNotice("CAPTURE FAILED: \(error.localizedDescription.prefix(48).uppercased())") }
            return false
        }
    }

    // MARK: - Video

    func startRecording(orientation: Int) {
        queue.async {
            guard self.settings.mode == .video, self.configuredMode == .video, !self.movieOutput.isRecording,
                  let c = self.movieOutput.connection(with: .video) else { return }
            self.orient(c, orientation)
            let f = DateFormatter()
            f.locale = Locale(identifier: "en_US_POSIX")
            f.dateFormat = "yyyyMMdd_HHmmss"
            let url = FileManager.default.temporaryDirectory.appendingPathComponent("PLZ_\(f.string(from: Date())).mov")
            try? FileManager.default.removeItem(at: url)
            if self.audioInput == nil { self.main { $0.delegate?.cameraNotice("NO MIC ACCESS · SILENT VIDEO") } }
            self.movieOutput.startRecording(to: url, recordingDelegate: self)
        }
    }

    func stopRecording() {
        queue.async { if self.movieOutput.isRecording { self.movieOutput.stopRecording() } }
    }
}

extension CameraController: AVCaptureFileOutputRecordingDelegate {
    func fileOutput(_ output: AVCaptureFileOutput, didStartRecordingTo fileURL: URL, from connections: [AVCaptureConnection]) {
        let start = Date()
        main { $0.delegate?.cameraRecording(active: true, started: start) }
    }

    func fileOutput(_ output: AVCaptureFileOutput, didFinishRecordingTo outputFileURL: URL, from connections: [AVCaptureConnection], error: Error?) {
        var ok = error == nil
        if let e = error as NSError?, let done = e.userInfo[AVErrorRecordingSuccessfullyFinishedKey] as? Bool { ok = done }
        main { $0.delegate?.cameraRecording(active: false, started: nil) }
        guard ok else {
            try? FileManager.default.removeItem(at: outputFileURL)
            main { $0.delegate?.cameraNotice("VIDEO FAILED") }
            return
        }
        let thumb = MediaSaver.videoThumbnail(outputFileURL)
        MediaSaver.saveVideo(outputFileURL) { [weak self] saved in
            self?.main {
                $0.delegate?.cameraNotice(saved ? "VIDEO SAVED" : "VIDEO KEPT IN FILES")
                if saved { $0.delegate?.cameraSaved(thumbnail: thumb, preview: nil) }
            }
        }
    }
}

/// Snapshot of one shot (settings at shutter time).
struct ShotContext {
    let settings: CaptureSettings
    let time: Date
    let index: Int
    let sensorLabel: String
    let manual: Bool
}

/// One AVCapturePhotoCaptureDelegate per capture request.
final class PhotoCaptureDelegate: NSObject, AVCapturePhotoCaptureDelegate {
    let context: ShotContext
    private let processor: PhotoProcessor
    var onShutter: (() -> Void)?
    var onSaved: ((PhotoProcessor.Output) -> Void)?
    var onError: ((String) -> Void)?
    var onFinished: (() -> Void)?

    init(context: ShotContext, processor: PhotoProcessor) {
        self.context = context
        self.processor = processor
    }

    func photoOutput(_ output: AVCapturePhotoOutput, willCapturePhotoFor resolvedSettings: AVCaptureResolvedPhotoSettings) {
        onShutter?()
    }

    func photoOutput(_ output: AVCapturePhotoOutput, didFinishProcessingPhoto photo: AVCapturePhoto, error: Error?) {
        if let error = error {
            onError?("PHOTO FAILED: " + error.localizedDescription.prefix(40).uppercased())
            return
        }
        guard let data = photo.fileDataRepresentation() else {
            onError?("NO PHOTO DATA")
            return
        }
        let onSaved = self.onSaved
        processor.process(data: data, isRaw: photo.isRawPhoto, metadata: photo.metadata, context: context) { out in
            onSaved?(out)
        }
    }

    func photoOutput(_ output: AVCapturePhotoOutput, didFinishCaptureFor resolvedSettings: AVCaptureResolvedPhotoSettings, error: Error?) {
        if let error = error { onError?("CAPTURE: " + error.localizedDescription.prefix(40).uppercased()) }
        onFinished?()
    }
}
