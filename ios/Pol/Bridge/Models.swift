import Foundation

struct Sim: Codable, Hashable, Identifiable {
    var id: Int
    var slot: Int
    var name: String
}

struct SmsMessage: Codable, Hashable, Identifiable {
    var id: String
    var addr: String
    var name: String?
    var body: String
    var ts: Double
    var dir: String
    var sim: Int
    /// Local-only: false while waiting for the Android phone to confirm.
    var pending: Bool? = nil
    /// Local-only: the Android phone reported the SMS was not sent.
    var failed: Bool? = nil

    var incoming: Bool { dir == "in" }
    var date: Date { Date(timeIntervalSince1970: ts / 1000) }
    var threadKey: String { PhoneNumber.key(addr) }
}

struct Contact: Codable, Hashable, Identifiable {
    var id: String
    var name: String
    var fav: Bool
    var phones: [String]
}

struct CallEntry: Codable, Hashable, Identifiable {
    var addr: String
    var name: String?
    var ts: Double
    var type: String // in | out | missed
    var dur: Double
    var sim: Int

    var id: String { "\(addr)-\(ts)" }
    var date: Date { Date(timeIntervalSince1970: ts / 1000) }
    var missed: Bool { type == "missed" }
}

struct ChatThread: Identifiable, Hashable {
    var id: String
    var addr: String
    var title: String
    var last: SmsMessage
    var unread: Int
}

enum PhoneNumber {
    /// Comparable key: digits only, last 10 digits (ignores +98 / 0 prefixes).
    static func key(_ raw: String) -> String {
        let digits = raw.filter(\.isNumber)
        if digits.isEmpty { return raw.lowercased() }
        return String(digits.suffix(10))
    }
}
