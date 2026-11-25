import Foundation

/// Reads when this install stops working (the signing profile SideStore uses lasts 7 days).
enum Provisioning {
    static var expiry: Date? {
        guard let url = Bundle.main.url(forResource: "embedded", withExtension: "mobileprovision"),
              let raw = try? Data(contentsOf: url),
              let text = String(data: raw, encoding: .isoLatin1),
              let start = text.range(of: "<?xml"),
              let end = text.range(of: "</plist>") else { return nil }
        let xml = String(text[start.lowerBound..<end.upperBound])
        guard let data = xml.data(using: .isoLatin1),
              let plist = try? PropertyListSerialization.propertyList(from: data, format: nil) as? [String: Any]
        else { return nil }
        return plist["ExpirationDate"] as? Date
    }

    /// Whole days left, or nil when unknown (e.g. running from Xcode).
    static var daysLeft: Int? {
        guard let expiry else { return nil }
        return max(0, Int(expiry.timeIntervalSinceNow / 86_400))
    }
}
