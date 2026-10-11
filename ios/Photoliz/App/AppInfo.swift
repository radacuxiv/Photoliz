import Foundation

/// Static product information shown in MENU → DESCRIPTION.
enum AppInfo {
    static let name = "Photoliz"
    static let developer = "radacuxiv"
    static let website = "panasko.dev"
    static let websiteURL = URL(string: "https://panasko.dev")!
    static let mail = "mail@panasko.dev"
    static let tagline = "Photoliz treats your phone as a weirdly designed plain digital camera, not a machine that decides how your pics look."

    struct Credit {
        let name: String
        let role: String
        let link: String?
    }

    static let credits: [Credit] = [
        Credit(name: "radacuxiv", role: "Developer", link: website),
        Credit(name: "Clemems Montiel", role: "Photographer, consulting", link: nil),
    ]

    static var version: String {
        let v = Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "1.0"
        let b = Bundle.main.object(forInfoDictionaryKey: "CFBundleVersion") as? String ?? "1"
        return "\(v) (\(b))"
    }
}
