import Foundation

enum Fa {
    private static let digits: [Character] = ["۰", "۱", "۲", "۳", "۴", "۵", "۶", "۷", "۸", "۹"]

    /// Converts ASCII digits to Persian digits.
    static func num(_ s: String) -> String {
        String(s.map { c in
            if let v = c.wholeNumberValue, c.isASCII { return digits[v] }
            return c
        })
    }

    static func num(_ n: Int) -> String { num(String(n)) }

    private static let time: DateFormatter = {
        let f = DateFormatter()
        f.locale = Locale(identifier: "fa_IR")
        f.dateFormat = "HH:mm"
        return f
    }()

    private static let weekday: DateFormatter = {
        let f = DateFormatter()
        f.locale = Locale(identifier: "fa_IR")
        f.dateFormat = "EEEE"
        return f
    }()

    private static let short: DateFormatter = {
        let f = DateFormatter()
        f.locale = Locale(identifier: "fa_IR")
        f.calendar = Calendar(identifier: .persian)
        f.dateFormat = "d MMMM"
        return f
    }()

    static func clock(_ d: Date) -> String { time.string(from: d) }

    /// "۱۰:۴۲", "دیروز", "دوشنبه" or "۴ آذر".
    static func when(_ d: Date) -> String {
        let cal = Calendar.current
        if cal.isDateInToday(d) { return time.string(from: d) }
        if cal.isDateInYesterday(d) { return "دیروز" }
        if let days = cal.dateComponents([.day], from: d, to: Date()).day, days < 7 { return weekday.string(from: d) }
        return short.string(from: d)
    }
}
