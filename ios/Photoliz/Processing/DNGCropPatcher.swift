import Foundation

/// Rewrites DefaultCropOrigin / DefaultCropSize of the main image in a DNG (in place, raw data untouched),
/// so raw converters show the aspect / zoom crop by default. Port of the Android patcher.
enum DNGCropPatcher {
    private static let tagNewSubfileType = 254
    private static let tagSubIFDs = 330
    private static let tagCropOrigin = 50719
    private static let tagCropSize = 50720

    @discardableResult
    static func patch(_ data: inout Data, ratio: Double?, zoom: Double) -> Bool {
        if ratio == nil && zoom <= 1.0001 { return false }
        guard data.count >= 16 else { return false }
        return data.withUnsafeMutableBytes { (raw: UnsafeMutableRawBufferPointer) -> Bool in
            guard let base = raw.baseAddress else { return false }
            let p = base.assumingMemoryBound(to: UInt8.self)
            let len = raw.count
            let little: Bool
            if p[0] == 0x49 && p[1] == 0x49 { little = true } else if p[0] == 0x4D && p[1] == 0x4D { little = false } else { return false }

            func u16(_ o: Int) -> Int {
                guard o >= 0 && o + 2 <= len else { return 0 }
                return little ? Int(p[o]) | Int(p[o + 1]) << 8 : Int(p[o]) << 8 | Int(p[o + 1])
            }
            func u32(_ o: Int) -> Int {
                guard o >= 0 && o + 4 <= len else { return 0 }
                let b0 = Int(p[o]), b1 = Int(p[o + 1]), b2 = Int(p[o + 2]), b3 = Int(p[o + 3])
                return little ? b0 | b1 << 8 | b2 << 16 | b3 << 24 : b0 << 24 | b1 << 16 | b2 << 8 | b3
            }
            func w16(_ o: Int, _ v: Int) {
                guard o >= 0 && o + 2 <= len else { return }
                let x = min(max(v, 0), 0xFFFF)
                if little { p[o] = UInt8(x & 0xFF); p[o + 1] = UInt8((x >> 8) & 0xFF) } else { p[o] = UInt8((x >> 8) & 0xFF); p[o + 1] = UInt8(x & 0xFF) }
            }
            func w32(_ o: Int, _ v: Int) {
                guard o >= 0 && o + 4 <= len else { return }
                for i in 0..<4 {
                    let shift = little ? 8 * i : 8 * (3 - i)
                    p[o + i] = UInt8((v >> shift) & 0xFF)
                }
            }
            func typeSize(_ t: Int) -> Int {
                switch t {
                case 1, 2, 6, 7: return 1
                case 3, 8: return 2
                case 4, 9, 11, 13: return 4
                case 5, 10, 12: return 8
                default: return 0
                }
            }
            struct Entry { let type: Int; let pos: Int }
            func read(_ e: Entry, _ i: Int) -> Double {
                switch e.type {
                case 3: return Double(u16(e.pos + 2 * i))
                case 4, 13: return Double(u32(e.pos + 4 * i))
                case 5:
                    let num = u32(e.pos + 8 * i), den = u32(e.pos + 8 * i + 4)
                    return den == 0 ? 0 : Double(num) / Double(den)
                default: return .nan
                }
            }
            func write(_ e: Entry, _ i: Int, _ v: Int) {
                switch e.type {
                case 3: w16(e.pos + 2 * i, v)
                case 4, 13: w32(e.pos + 4 * i, v)
                case 5: w32(e.pos + 8 * i, v); w32(e.pos + 8 * i + 4, 1)
                default: break
                }
            }

            var queue = [u32(4)]
            var visited = Set<Int>()
            var patched = false
            var guardCount = 0
            while !queue.isEmpty && guardCount < 64 {
                guardCount += 1
                let ifd = queue.removeFirst()
                if ifd <= 0 || ifd + 2 > len || visited.contains(ifd) { continue }
                visited.insert(ifd)
                let n = u16(ifd)
                if n <= 0 || n > 4096 || ifd + 2 + n * 12 + 4 > len { continue }
                var origin: Entry?
                var size: Entry?
                var subfile = 0
                for k in 0..<n {
                    let e = ifd + 2 + k * 12
                    let tag = u16(e)
                    let type = u16(e + 2)
                    let count = u32(e + 4)
                    let bytes = typeSize(type) * count
                    if bytes <= 0 { continue }
                    let pos = bytes <= 4 ? e + 8 : u32(e + 8)
                    if pos + bytes > len { continue }
                    switch tag {
                    case tagCropOrigin: if count >= 2 { origin = Entry(type: type, pos: pos) }
                    case tagCropSize: if count >= 2 { size = Entry(type: type, pos: pos) }
                    case tagNewSubfileType: subfile = type == 3 ? u16(pos) : u32(pos)
                    case tagSubIFDs:
                        for i in 0..<min(count, 16) { queue.append(type == 3 ? u16(pos + 2 * i) : u32(pos + 4 * i)) }
                    default: break
                    }
                }
                let next = u32(ifd + 2 + n * 12)
                if next != 0 { queue.append(next) }
                guard let o = origin, let s = size, subfile & 1 == 0 else { continue }
                let ox = read(o, 0), oy = read(o, 1), sw = read(s, 0), sh = read(s, 1)
                guard sw.isFinite, sh.isFinite, sw >= 8, sh >= 8 else { continue }
                let c = CropMath.centered(w: Int(sw.rounded()), h: Int(sh.rounded()), ratio: ratio, zoom: zoom)
                write(o, 0, Int((ox + Double(c.left)).rounded()))
                write(o, 1, Int((oy + Double(c.top)).rounded()))
                write(s, 0, c.width)
                write(s, 1, c.height)
                patched = true
            }
            return patched
        }
    }
}
