import UIKit

extension UIColor {
    convenience init(hex: UInt32, alpha: CGFloat = 1) {
        self.init(red: CGFloat((hex >> 16) & 0xFF) / 255, green: CGFloat((hex >> 8) & 0xFF) / 255,
                  blue: CGFloat(hex & 0xFF) / 255, alpha: alpha)
    }

    var rgba: (CGFloat, CGFloat, CGFloat, CGFloat) {
        var r: CGFloat = 0, g: CGFloat = 0, b: CGFloat = 0, a: CGFloat = 0
        getRed(&r, green: &g, blue: &b, alpha: &a)
        return (r, g, b, a)
    }

    func mix(_ other: UIColor, _ t: CGFloat) -> UIColor {
        let a = rgba, b = other.rgba
        let k = min(max(t, 0), 1)
        return UIColor(red: a.0 + (b.0 - a.0) * k, green: a.1 + (b.1 - a.1) * k, blue: a.2 + (b.2 - a.2) * k, alpha: a.3 + (b.3 - a.3) * k)
    }

    func alpha(_ a: CGFloat) -> UIColor { withAlphaComponent(min(max(a, 0), 1)) }

    var luminance: CGFloat {
        func lin(_ v: CGFloat) -> CGFloat { v <= 0.04045 ? v / 12.92 : pow((v + 0.055) / 1.055, 2.4) }
        let c = rgba
        return 0.2126 * lin(c.0) + 0.7152 * lin(c.1) + 0.0722 * lin(c.2)
    }

    func contrast(with other: UIColor) -> CGFloat {
        let a = luminance, b = other.luminance
        return (max(a, b) + 0.05) / (min(a, b) + 0.05)
    }
}

/// Same palettes and role mapping as Android.
struct Palette {
    let name: String
    let bg: UIColor
    let deep: UIColor
    let surface: UIColor
    let surface2: UIColor
    let body: UIColor
    let bodyLight: UIColor
    let bodyDark: UIColor
    let accent: UIColor
    let alert: UIColor
    let warn: UIColor
    let ink: UIColor
    let text: UIColor
    let textDim: UIColor
    let swatches: [UIColor]

    var line: UIColor { text.alpha(0.24) }
    var onAccent: UIColor { accent.contrast(with: ink) >= accent.contrast(with: text) ? ink : text }
    var onBody: UIColor { body.contrast(with: ink) >= body.contrast(with: text) ? ink : text }
    var mask: UIColor { deep.alpha(0.86) }
    /// Accent if it reads on panels, else text.
    var accentOnSurface: UIColor { accent.contrast(with: surface) > 2.2 ? accent : text }

    static let canonical = Palette(
        name: "Canonical", bg: UIColor(hex: 0x111214), deep: UIColor(hex: 0x050505), surface: UIColor(hex: 0x26282B), surface2: UIColor(hex: 0x333333),
        body: UIColor(hex: 0xB3B5B8), bodyLight: UIColor(hex: 0xD0D2D5), bodyDark: UIColor(hex: 0x8A8D91),
        accent: UIColor(hex: 0x1C61B2), alert: UIColor(hex: 0xD32F2F), warn: UIColor(hex: 0x333333), ink: UIColor(hex: 0x111214),
        text: UIColor(hex: 0xFFFFFF), textDim: UIColor(hex: 0xEAEBED),
        swatches: colors([0xB3B5B8, 0x8A8D91, 0xD0D2D5, 0x1C61B2, 0xD32F2F, 0x333333, 0x111214, 0x050505, 0x26282B, 0xFFFFFF, 0xEAEBED]))

    static let europe = Palette(
        name: "Europe", bg: UIColor(hex: 0x313638), deep: UIColor(hex: 0x313638).mix(.black, 0.55),
        surface: UIColor(hex: 0x313638).mix(UIColor(hex: 0xF9FCFB), 0.08), surface2: UIColor(hex: 0x313638).mix(UIColor(hex: 0xA4B0B4), 0.25),
        body: UIColor(hex: 0x5B93C7), bodyLight: UIColor(hex: 0x86B5DF), bodyDark: UIColor(hex: 0x3D73A9),
        accent: UIColor(hex: 0xE67300), alert: UIColor(hex: 0xC4520A), warn: UIColor(hex: 0xF39C12), ink: UIColor(hex: 0x313638),
        text: UIColor(hex: 0xF9FCFB), textDim: UIColor(hex: 0xD1D8DD),
        swatches: colors([0x5B93C7, 0x86B5DF, 0x3D73A9, 0xE67300, 0xC4520A, 0xF39C12, 0x313638, 0xA4B0B4, 0x8B3E1A, 0xF9FCFB, 0xD1D8DD]))

    static let gaea = Palette(
        name: "Gaea", bg: UIColor(hex: 0x14061A), deep: UIColor(hex: 0x14061A).mix(.black, 0.5), surface: UIColor(hex: 0x2C0D32), surface2: UIColor(hex: 0x471D50),
        body: UIColor(hex: 0xC0366D), bodyLight: UIColor(hex: 0xDE6F95), bodyDark: UIColor(hex: 0x892A4E),
        accent: UIColor(hex: 0xE8E0D2), alert: UIColor(hex: 0xDE6F95), warn: UIColor(hex: 0xEEDCCB), ink: UIColor(hex: 0x2C0D32),
        text: UIColor(hex: 0xF6F4EE), textDim: UIColor(hex: 0xDBD3C9),
        swatches: colors([0xC0366D, 0xDE6F95, 0x892A4E, 0xE8E0D2, 0xA6927D, 0xEEDCCB, 0x2C0D32, 0x471D50, 0x14061A, 0xF6F4EE, 0xDBD3C9]))

    static let island = Palette(
        name: "Island", bg: UIColor(hex: 0x131818), deep: UIColor(hex: 0x0A0D0D), surface: UIColor(hex: 0x222B2A), surface2: UIColor(hex: 0x2C3E3B),
        body: UIColor(hex: 0x3D524E), bodyLight: UIColor(hex: 0x68766B), bodyDark: UIColor(hex: 0x1E2B29),
        accent: UIColor(hex: 0x87948B), alert: UIColor(hex: 0xC6D0CA), warn: UIColor(hex: 0x68766B), ink: UIColor(hex: 0x0A0D0D),
        text: UIColor(hex: 0xC6D0CA), textDim: UIColor(hex: 0xA8B4AC),
        swatches: colors([0x2C3E3B, 0x3D524E, 0x1E2B29, 0x4C5B52, 0x68766B, 0x87948B, 0x131818, 0x0A0D0D, 0x222B2A, 0xA8B4AC, 0xC6D0CA]))

    static let all: [Palette] = [.canonical, .europe, .gaea, .island]

    private static func colors(_ hex: [UInt32]) -> [UIColor] { hex.map { UIColor(hex: $0) } }

    static func named(_ n: String?) -> Palette { all.first { $0.name == n } ?? .canonical }
}

enum Fonts {
    static func mono(_ size: CGFloat, _ weight: UIFont.Weight = .bold) -> UIFont {
        UIFont.monospacedSystemFont(ofSize: scaled(size), weight: weight)
    }

    static func label(_ size: CGFloat) -> UIFont {
        UIFont(name: "AvenirNextCondensed-DemiBold", size: scaled(size)) ?? UIFont.systemFont(ofSize: scaled(size), weight: .semibold)
    }

    /// Honors Dynamic Type up to 1.3x so the camera layout cannot break.
    static func scaled(_ size: CGFloat) -> CGFloat {
        min(UIFontMetrics.default.scaledValue(for: size), size * 1.3)
    }
}

/// Draws text centered at a point (CoreGraphics context must be current).
func drawText(_ s: String, font: UIFont, color: UIColor, center: CGPoint, kern: CGFloat = 0) {
    let attrs: [NSAttributedString.Key: Any] = [.font: font, .foregroundColor: color, .kern: kern]
    let size = (s as NSString).size(withAttributes: attrs)
    (s as NSString).draw(at: CGPoint(x: center.x - size.width / 2, y: center.y - size.height / 2), withAttributes: attrs)
}

func drawText(_ s: String, font: UIFont, color: UIColor, at p: CGPoint, align: NSTextAlignment, kern: CGFloat = 0) {
    let attrs: [NSAttributedString.Key: Any] = [.font: font, .foregroundColor: color, .kern: kern]
    let size = (s as NSString).size(withAttributes: attrs)
    let x: CGFloat
    switch align {
    case .right: x = p.x - size.width
    case .center: x = p.x - size.width / 2
    default: x = p.x
    }
    (s as NSString).draw(at: CGPoint(x: x, y: p.y - size.height / 2), withAttributes: attrs)
}

func textWidth(_ s: String, font: UIFont, kern: CGFloat = 0) -> CGFloat {
    (s as NSString).size(withAttributes: [.font: font, .kern: kern]).width
}
