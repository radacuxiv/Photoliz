import AVFoundation
import Photos
import UIKit

/// Photos library + Files (Documents) storage.
enum MediaSaver {

    /// Asks for add-only access (iOS 14+) or full access (iOS 13) when needed.
    static func ensurePhotosAccess(_ done: @escaping (Bool) -> Void) {
        if #available(iOS 14, *) {
            switch PHPhotoLibrary.authorizationStatus(for: .addOnly) {
            case .authorized, .limited: done(true)
            case .notDetermined: PHPhotoLibrary.requestAuthorization(for: .addOnly) { s in done(s == .authorized || s == .limited) }
            default: done(false)
            }
        } else {
            switch PHPhotoLibrary.authorizationStatus() {
            case .authorized: done(true)
            case .notDetermined: PHPhotoLibrary.requestAuthorization { s in done(s == .authorized) }
            default: done(false)
            }
        }
    }

    static var documents: URL {
        FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
    }

    /// Automatic DNG folder, created on first use (visible in Files → On My iPhone → Photoliz).
    static var defaultDNGFolder: URL { documents.appendingPathComponent("DNG", isDirectory: true) }

    private static func ensureFolder(_ url: URL) -> Bool {
        var isDir: ObjCBool = false
        if FileManager.default.fileExists(atPath: url.path, isDirectory: &isDir) { return isDir.boolValue }
        return (try? FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)) != nil
    }

    /// (ok, savedToFilesInstead)
    static func saveJPEG(_ data: Data, name: String, completion: @escaping (Bool, Bool) -> Void) {
        ensurePhotosAccess { granted in
            guard granted else {
                let folder = documents.appendingPathComponent("JPG", isDirectory: true)
                let ok = ensureFolder(folder) && ((try? data.write(to: folder.appendingPathComponent(name + ".jpg"), options: .atomic)) != nil)
                completion(ok, true)
                return
            }
            PHPhotoLibrary.shared().performChanges({
                let req = PHAssetCreationRequest.forAsset()
                let opts = PHAssetResourceCreationOptions()
                opts.originalFilename = name + ".jpg"
                req.addResource(with: .photo, data: data, options: opts)
            }, completionHandler: { ok, _ in completion(ok, false) })
        }
    }

    /// Writes a DNG into the chosen folder (security scoped bookmark) or the automatic folder.
    static func writeDNG(_ data: Data, name: String, folder: URL?) -> Bool {
        if let folder = folder {
            let scoped = folder.startAccessingSecurityScopedResource()
            defer { if scoped { folder.stopAccessingSecurityScopedResource() } }
            if (try? data.write(to: folder.appendingPathComponent(name + ".dng"), options: .atomic)) != nil { return true }
        }
        let fallback = defaultDNGFolder
        guard ensureFolder(fallback) else { return false }
        return (try? data.write(to: fallback.appendingPathComponent(name + ".dng"), options: .atomic)) != nil
    }

    static func saveVideo(_ url: URL, completion: @escaping (Bool) -> Void) {
        ensurePhotosAccess { granted in
            guard granted else {
                let folder = documents.appendingPathComponent("Video", isDirectory: true)
                if ensureFolder(folder) {
                    try? FileManager.default.moveItem(at: url, to: folder.appendingPathComponent(url.lastPathComponent))
                }
                completion(false)
                return
            }
            PHPhotoLibrary.shared().performChanges({
                let req = PHAssetCreationRequest.forAsset()
                let opts = PHAssetResourceCreationOptions()
                opts.shouldMoveFile = true
                req.addResource(with: .video, fileURL: url, options: opts)
            }, completionHandler: { ok, _ in
                if !ok { try? FileManager.default.removeItem(at: url) }
                completion(ok)
            })
        }
    }

    static func videoThumbnail(_ url: URL) -> UIImage? {
        let gen = AVAssetImageGenerator(asset: AVURLAsset(url: url))
        gen.appliesPreferredTrackTransform = true
        gen.maximumSize = CGSize(width: 360, height: 360)
        guard let cg = try? gen.copyCGImage(at: CMTime(value: 0, timescale: 1), actualTime: nil) else { return nil }
        return UIImage(cgImage: cg)
    }

    // MARK: Folder bookmark

    static func bookmark(for url: URL) -> Data? {
        let scoped = url.startAccessingSecurityScopedResource()
        defer { if scoped { url.stopAccessingSecurityScopedResource() } }
        return try? url.bookmarkData(options: .minimalBookmark, includingResourceValuesForKeys: nil, relativeTo: nil)
    }

    static func resolve(bookmark: Data) -> URL? {
        var stale = false
        guard let url = try? URL(resolvingBookmarkData: bookmark, options: [], relativeTo: nil, bookmarkDataIsStale: &stale) else { return nil }
        return url
    }
}
