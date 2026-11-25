import SwiftUI

enum Theme {
    static let bg = Color(red: 0.043, green: 0.051, blue: 0.063)
    static let card = Color(red: 0.071, green: 0.086, blue: 0.106)
    static let field = Color(red: 0.102, green: 0.122, blue: 0.149)
    static let accent = Color(red: 0.204, green: 0.827, blue: 0.600)
    static let onAccent = Color(red: 0.024, green: 0.157, blue: 0.102)
    static let muted = Color(red: 0.541, green: 0.576, blue: 0.627)
    static let text2 = Color(red: 0.639, green: 0.675, blue: 0.722)
    static let warn = Color(red: 0.961, green: 0.710, blue: 0.278)
    static let warnBg = Color(red: 0.165, green: 0.129, blue: 0.071)
    static let danger = Color(red: 0.973, green: 0.443, blue: 0.443)
}

/// "from" small and lowercase, PEDICODE below it; opens pedicode.ir.
struct Credit: View {
    var body: some View {
        Link(destination: URL(string: "https://pedicode.ir")!) {
            VStack(spacing: 0) {
                Text("from")
                    .font(.system(size: 11))
                    .tracking(1)
                    .foregroundStyle(Theme.muted)
                Text("PEDICODE")
                    .font(.system(size: 15, weight: .heavy))
                    .tracking(3)
                    .foregroundStyle(Theme.accent)
            }
            .padding(.horizontal, 16)
            .frame(minHeight: 44)
        }
        .environment(\.layoutDirection, .leftToRight)
        .accessibilityLabel("from PEDICODE — pedicode.ir")
    }
}

struct Avatar: View {
    let name: String
    var size: CGFloat = 48

    private static let tints: [Color] = [
        Color(red: 0.17, green: 0.23, blue: 0.33), Color(red: 0.23, green: 0.17, blue: 0.33),
        Color(red: 0.17, green: 0.33, blue: 0.28), Color(red: 0.33, green: 0.25, blue: 0.17),
        Color(red: 0.33, green: 0.17, blue: 0.23),
    ]

    var body: some View {
        let initial = name.first.map(String.init) ?? "؟"
        let seed = name.unicodeScalars.reduce(0) { $0 &+ Int($1.value) }
        let tint = Self.tints[seed % Self.tints.count]
        Text(initial)
            .font(.system(size: size * 0.38, weight: .bold))
            .foregroundStyle(.white)
            .frame(width: size, height: size)
            .background(tint, in: Circle())
            .accessibilityHidden(true)
    }
}

struct StatusPill: View {
    @EnvironmentObject var bridge: BridgeManager
    @EnvironmentObject var store: Store

    var body: some View {
        HStack(spacing: 8) {
            Circle().fill(color).frame(width: 8, height: 8)
            Text(label)
            if bridge.state == .connected, let b = store.battery {
                Text("·").foregroundStyle(Theme.muted)
                Text("باتری \(Fa.num(b))٪")
            }
        }
        .font(.system(size: 13))
        .foregroundStyle(bridge.state == .connected ? Color(red: 0.72, green: 0.94, blue: 0.82) : Theme.text2)
        .padding(.horizontal, 12)
        .padding(.vertical, 6)
        .background(bridge.state == .connected ? Color(red: 0.07, green: 0.15, blue: 0.12) : Theme.field, in: Capsule())
        .animation(.easeInOut(duration: 0.25), value: bridge.state)
    }

    private var color: Color {
        switch bridge.state {
        case .connected: return Theme.accent
        case .connecting, .authorizing, .searching: return Theme.warn
        default: return Theme.muted
        }
    }

    private var label: String {
        switch bridge.state {
        case .connected: return "متصل به \(store.deviceName.isEmpty ? "اندروید" : store.deviceName)"
        case .connecting, .authorizing: return "در حال اتصال…"
        case .searching: return "در حال جستجو…"
        case .denied: return "کد اشتباه بود"
        case .off: return bridge.isPaired ? "قطع — بلوتوث را چک کن" : "جفت نشده"
        }
    }
}

/// Shown when the SideStore signature is about to expire.
struct RenewalBanner: View {
    let daysLeft: Int

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack(alignment: .top, spacing: 10) {
                Image(systemName: "clock")
                    .foregroundStyle(Theme.warn)
                    .font(.system(size: 20))
                VStack(alignment: .leading, spacing: 2) {
                    Text(daysLeft == 0 ? "امروز باید تمدید شود" : "\(Fa.num(daysLeft)) روز تا تمدید اپ")
                        .font(.system(size: 15, weight: .bold))
                        .foregroundStyle(Color(red: 1, green: 0.886, blue: 0.682))
                    Text("برای اینکه اپ بسته نشه، توی SideStore تمدیدش کن.")
                        .font(.system(size: 13))
                        .foregroundStyle(Color(red: 0.851, green: 0.761, blue: 0.604))
                }
            }
            OpenSideStoreButton()
        }
        .padding(14)
        .background(Theme.warnBg, in: RoundedRectangle(cornerRadius: 16))
    }
}

struct OpenSideStoreButton: View {
    @Environment(\.openURL) private var openURL

    var body: some View {
        Button {
            if let url = URL(string: "sidestore://") { openURL(url) }
        } label: {
            Text("تمدید در SideStore")
                .font(.system(size: 15, weight: .bold))
                .frame(maxWidth: .infinity, minHeight: 44)
                .foregroundStyle(Theme.warnBg)
                .background(Theme.warn, in: RoundedRectangle(cornerRadius: 12))
        }
        .buttonStyle(.plain)
    }
}

struct SearchField: View {
    let placeholder: String
    @Binding var text: String

    var body: some View {
        HStack(spacing: 8) {
            Image(systemName: "magnifyingglass").foregroundStyle(Theme.muted)
            TextField(placeholder, text: $text)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
            if !text.isEmpty {
                Button { text = "" } label: {
                    Image(systemName: "xmark.circle.fill").foregroundStyle(Theme.muted)
                }
                .accessibilityLabel("پاک کردن")
            }
        }
        .padding(.horizontal, 12)
        .frame(height: 44)
        .background(Theme.field, in: RoundedRectangle(cornerRadius: 12))
    }
}

struct SimBadge: View {
    let slot: Int
    var body: some View {
        Text("سیم \(Fa.num(slot))")
            .font(.system(size: 11))
            .foregroundStyle(Theme.muted)
            .padding(.horizontal, 5)
            .overlay(RoundedRectangle(cornerRadius: 6).stroke(Color(white: 0.25), lineWidth: 1))
    }
}

struct SimPicker: View {
    @EnvironmentObject var store: Store
    @Binding var slot: Int

    var body: some View {
        let slots = store.sims.isEmpty ? [1] : store.sims.map(\.slot).sorted()
        if slots.count > 1 {
            HStack(spacing: 8) {
                ForEach(slots, id: \.self) { s in
                    Button { withAnimation(.snappy) { slot = s } } label: {
                        Text(store.simName(s))
                            .font(.system(size: 13))
                            .padding(.horizontal, 14)
                            .frame(height: 32)
                            .foregroundStyle(slot == s ? Color(red: 0.72, green: 0.94, blue: 0.82) : Theme.text2)
                            .background(slot == s ? Color(red: 0.07, green: 0.15, blue: 0.12) : .clear, in: Capsule())
                            .overlay(Capsule().stroke(slot == s ? Theme.accent : Color(white: 0.25), lineWidth: 1))
                    }
                    .buttonStyle(.plain)
                    .accessibilityAddTraits(slot == s ? .isSelected : [])
                }
                Spacer()
            }
        }
    }
}

/// Shown when the subscription on the Android phone has ended.
struct LicenseBanner: View {
    var body: some View {
        HStack(alignment: .top, spacing: 10) {
            Image(systemName: "lock.fill").foregroundStyle(Theme.danger)
            VStack(alignment: .leading, spacing: 2) {
                Text("اشتراک تمام شده").font(.system(size: 15, weight: .bold))
                Text("پیامک و تماس تا تمدید اشتراک منتقل نمی‌شوند. اشتراک رو از ربات تلگرام بخر و کد لایسنس رو در اپ اندروید وارد کن.")
                    .font(.system(size: 13)).foregroundStyle(Theme.text2)
                Link("خرید از @polantenbot", destination: URL(string: "https://t.me/polantenbot")!)
                    .font(.system(size: 14, weight: .bold))
                    .padding(.top, 4)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(14)
        .background(Color(red: 0.16, green: 0.07, blue: 0.08), in: RoundedRectangle(cornerRadius: 16))
    }
}
