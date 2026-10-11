import CoreImage
import ImageIO
import UIKit

/// Develops Bayer RAW (DNG) into a plain JPEG and saves both files. Serial queue, bounded by the controller.
///
/// The develop is the RAW decoder with everything "creative" switched off: no tone boost, no local tone mapping,
/// no sharpening, no noise reduction, no lens geometry correction — as-shot white balance and the sensor's own
/// color matrices, rendered to sRGB. Photo zoom and aspect crops are applied here (and to the DNG default crop).
final class PhotoProcessor {
    private let queue = DispatchQueue(label: "dev.panasko.photoliz.develop", qos: .userInitiated)
    private let lock = NSLock()
    private var _pending = 0
    private lazy var context: CIContext = {
        var opts: [CIContextOption: Any] = [.cacheIntermediates: false]
        if let linear = CGColorSpace(name: CGColorSpace.extendedLinearSRGB) { opts[.workingColorSpace] = linear }
        return CIContext(options: opts)
    }()

    var notice: ((String) -> Void)?
    var dngFolder: URL?

    var pending: Int {
        lock.lock(); defer { lock.unlock() }
        return _pending
    }

    private func adjust(_ d: Int) {
        lock.lock(); _pending += d; lock.unlock()
    }

    struct Output {
        let thumbnail: UIImage?
        let preview: UIImage?
    }

    /// Called on the develop queue each time a shot has been fully processed (lets a stalled burst continue).
    var onProcessed: (() -> Void)?

    func process(data: Data, isRaw: Bool, metadata: [String: Any], context ctx: ShotContext, completion: @escaping (Output) -> Void) {
        adjust(1)
        queue.async {
            autoreleasepool {
                let out = isRaw ? self.processRaw(data, metadata: metadata, ctx: ctx) : self.processProcessed(data, metadata: metadata, ctx: ctx)
                completion(out)
            }
            self.adjust(-1)
            self.onProcessed?()
        }
    }

    private func zoomOf(_ s: CaptureSettings) -> Double { s.mode == .photo ? max(1, Double(s.zoom)) : 1 }

    private func baseName(_ ctx: ShotContext) -> String {
        let f = DateFormatter()
        f.locale = Locale(identifier: "en_US_POSIX")
        f.dateFormat = "yyyyMMdd_HHmmss_SSS"
        var name = "PLZ_" + f.string(from: ctx.time)
        if ctx.index > 0 { name += String(format: "_%03d", ctx.index) }
        return name
    }

    // MARK: RAW

    private func developRaw(_ data: Data) -> CIImage? {
        if #available(iOS 15.0, *) {
            guard let f = CIRAWFilter(imageData: data, identifierHint: "com.adobe.raw-image") else { return nil }
            f.boostAmount = 0
            f.boostShadowAmount = 0
            if f.isLensCorrectionSupported { f.isLensCorrectionEnabled = false }
            if f.isLuminanceNoiseReductionSupported { f.luminanceNoiseReductionAmount = 0 }
            if f.isColorNoiseReductionSupported { f.colorNoiseReductionAmount = 0 }
            if f.isSharpnessSupported { f.sharpnessAmount = 0 }
            if f.isContrastSupported { f.contrastAmount = 0 }
            if f.isDetailSupported { f.detailAmount = 0 }
            if f.isMoireReductionSupported { f.moireReductionAmount = 0 }
            if f.isLocalToneMapSupported { f.localToneMapAmount = 0 }
            f.extendedDynamicRangeAmount = 0
            // Exposed as API only by the newest SDKs: set through guarded KVC so every Xcode version builds
            // and older systems simply skip them.
            PhotoProcessor.setIfSupported(f, key: "despeckleAmount", value: 0, support: "isDespeckleSupported")
            PhotoProcessor.setIfSupported(f, key: "highlightRecoveryEnabled", value: false, support: "isHighlightRecoverySupported")
            return f.outputImage
        } else {
            let options: [CIRAWFilterOption: Any] = [
                .boostAmount: 0.0,
                .boostShadowAmount: 0.0,
                .enableSharpening: false,
                .luminanceNoiseReductionAmount: 0.0,
                .colorNoiseReductionAmount: 0.0,
                .noiseReductionSharpnessAmount: 0.0,
                .noiseReductionContrastAmount: 0.0,
                .noiseReductionDetailAmount: 0.0,
                .enableVendorLensCorrection: false,
                .moireAmount: 0.0,
            ]
            return CIFilter(imageData: data, options: options)?.outputImage
        }
    }

    private static func setIfSupported(_ f: NSObject, key: String, value: Any, support: String) {
        let setter = NSSelectorFromString("set" + key.prefix(1).uppercased() + String(key.dropFirst()) + ":")
        guard f.responds(to: setter), f.responds(to: NSSelectorFromString(support)) else { return }
        try? PLZObjC.catchException {
            if (f.value(forKey: support) as? Bool) == true { f.setValue(value, forKey: key) }
        }
    }

    private func processRaw(_ data: Data, metadata: [String: Any], ctx: ShotContext) -> Output {
        let s = ctx.settings
        let ratio = s.aspect.ratio
        let zoom = zoomOf(s)
        let name = baseName(ctx)
        var result = Output(thumbnail: nil, preview: nil)

        if let image = developRaw(data) {
            if let rendered = renderJPEG(image, ratio: ratio, zoom: zoom, metadata: metadata) {
                let jpeg = rendered.0
                result = rendered.1
                MediaSaver.saveJPEG(jpeg, name: name) { [weak self] ok, fallback in
                    if !ok { self?.notice?("SAVE FAILED") } else if fallback { self?.notice?("NO PHOTOS ACCESS · JPEG IN FILES") }
                }
            } else {
                notice?("JPEG RENDER FAILED")
            }
        } else {
            notice?("RAW DEVELOP FAILED")
        }

        if s.saveDng {
            var dng = data
            DNGCropPatcher.patch(&dng, ratio: ratio, zoom: zoom)
            if !MediaSaver.writeDNG(dng, name: name, folder: dngFolder) { notice?("DNG WRITE FAILED") }
        }
        return result
    }

    // MARK: Processed (sensors without Bayer RAW)

    private func processProcessed(_ data: Data, metadata: [String: Any], ctx: ShotContext) -> Output {
        let s = ctx.settings
        let ratio = s.aspect.ratio
        let zoom = zoomOf(s)
        guard let image = CIImage(data: data, options: [.applyOrientationProperty: true]) else {
            notice?("PHOTO DECODE FAILED")
            return Output(thumbnail: nil, preview: nil)
        }
        guard let rendered = renderJPEG(image, ratio: ratio, zoom: zoom, metadata: metadata) else {
            notice?("JPEG RENDER FAILED")
            return Output(thumbnail: nil, preview: nil)
        }
        let (jpeg, out) = rendered
        MediaSaver.saveJPEG(jpeg, name: baseName(ctx)) { [weak self] ok, fallback in
            if !ok { self?.notice?("SAVE FAILED") } else if fallback { self?.notice?("NO PHOTOS ACCESS · JPEG IN FILES") }
        }
        return out
    }

    // MARK: Rendering

    private func renderJPEG(_ image: CIImage, ratio: Double?, zoom: Double, metadata: [String: Any]) -> (Data, Output)? {
        let extent = image.extent.integral
        guard extent.width >= 8, extent.height >= 8, extent.width.isFinite, extent.height.isFinite else { return nil }
        let w = Int(extent.width)
        let h = Int(extent.height)
        let c = CropMath.centered(w: w, h: h, ratio: ratio, zoom: zoom)
        // Core Image's origin is bottom-left.
        let rect = CGRect(x: extent.minX + CGFloat(c.left), y: extent.minY + CGFloat(h - c.top - c.height),
                          width: CGFloat(c.width), height: CGFloat(c.height))
        guard let srgb = CGColorSpace(name: CGColorSpace.sRGB),
              let cg = context.createCGImage(image, from: rect, format: .RGBA8, colorSpace: srgb) else { return nil }

        let out = NSMutableData()
        guard let dest = CGImageDestinationCreateWithData(out as CFMutableData, "public.jpeg" as CFString, 1, nil) else { return nil }
        var props: [String: Any] = [:]
        if var exif = metadata[kCGImagePropertyExifDictionary as String] as? [String: Any] {
            exif[kCGImagePropertyExifPixelXDimension as String] = cg.width
            exif[kCGImagePropertyExifPixelYDimension as String] = cg.height
            props[kCGImagePropertyExifDictionary as String] = exif
        }
        var tiff = metadata[kCGImagePropertyTIFFDictionary as String] as? [String: Any] ?? [:]
        tiff[kCGImagePropertyTIFFOrientation as String] = 1
        tiff[kCGImagePropertyTIFFSoftware as String] = AppInfo.name
        props[kCGImagePropertyTIFFDictionary as String] = tiff
        if let gps = metadata[kCGImagePropertyGPSDictionary as String] { props[kCGImagePropertyGPSDictionary as String] = gps }
        props[kCGImagePropertyOrientation as String] = 1
        props[kCGImageDestinationLossyCompressionQuality as String] = 0.92
        CGImageDestinationAddImage(dest, cg, props as CFDictionary)
        guard CGImageDestinationFinalize(dest) else { return nil }

        let output = Output(thumbnail: PhotoProcessor.thumbnail(cg, maxSide: 360), preview: PhotoProcessor.thumbnail(cg, maxSide: 1800))
        return (out as Data, output)
    }

    static func thumbnail(_ cg: CGImage, maxSide: CGFloat) -> UIImage? {
        let w = CGFloat(cg.width)
        let h = CGFloat(cg.height)
        let s = min(1, maxSide / max(w, h))
        let size = CGSize(width: max(1, (w * s).rounded()), height: max(1, (h * s).rounded()))
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1
        format.opaque = true
        let renderer = UIGraphicsImageRenderer(size: size, format: format)
        return renderer.image { _ in UIImage(cgImage: cg).draw(in: CGRect(origin: .zero, size: size)) }
    }
}
