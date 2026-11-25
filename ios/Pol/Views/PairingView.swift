import SwiftUI

/// First-run screen: find the Android phone and enter its 6-digit code.
struct PairingView: View {
    @EnvironmentObject var bridge: BridgeManager
    @State private var selected: BridgeManager.Found?
    @State private var code = ""
    @FocusState private var codeFocused: Bool

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 18) {
                    VStack(alignment: .leading, spacing: 6) {
                        Text("وصل شدن به اندروید").font(.system(size: 28, weight: .heavy))
                        Text("روی گوشی اندروید اپ پل را باز کن و اتصال را روشن کن.")
                            .foregroundStyle(Theme.text2)
                    }

                    if bridge.state == .denied {
                        Label(bridge.deniedCode.map { "گوشی اندروید کد «\($0)» رو دریافت کرد و قبول نکرد. کد روی اندروید رو دوباره چک کن." } ?? "کد اشتباه بود. دوباره امتحان کن.",
                              systemImage: "exclamationmark.triangle.fill")
                            .foregroundStyle(Theme.danger)
                    }

                    if let phone = selected {
                        codeEntry(for: phone)
                    } else {
                        phoneList
                    }
                }
                .padding(20)
            }
            .background(Theme.bg)
            .safeAreaInset(edge: .bottom) { Credit().padding(.bottom, 8) }
            .onAppear { bridge.startSearching() }
            .onDisappear { bridge.stopSearching() }
            .animation(.snappy, value: bridge.found)
            .animation(.snappy, value: selected)
        }
    }

    private var phoneList: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack {
                Text("گوشی‌های پیدا شده").font(.system(size: 15, weight: .bold))
                Spacer()
                if bridge.state == .searching { ProgressView() }
            }
            if bridge.found.isEmpty {
                Text(bridge.state == .off ? "بلوتوث آیفون خاموش است یا اجازه داده نشده." : "در حال جستجو… گوشی اندروید را نزدیک نگه دار.")
                    .font(.system(size: 14))
                    .foregroundStyle(Theme.muted)
                    .padding(.vertical, 20)
            }
            ForEach(bridge.found) { phone in
                Button {
                    selected = phone
                    codeFocused = true
                } label: {
                    HStack(spacing: 12) {
                        Image(systemName: "candybarphone").foregroundStyle(Theme.accent)
                            .frame(width: 44, height: 44)
                            .background(Theme.field, in: Circle())
                        Text(phone.name).font(.system(size: 16, weight: .semibold))
                        Spacer()
                        Image(systemName: "chevron.left").foregroundStyle(Theme.muted)
                    }
                    .padding(12)
                    .background(Theme.card, in: RoundedRectangle(cornerRadius: 16))
                }
                .buttonStyle(.plain)
            }
        }
    }

    private func codeEntry(for phone: BridgeManager.Found) -> some View {
        VStack(alignment: .leading, spacing: 14) {
            Text("کد ۶ رقمی روی \(phone.name)").font(.system(size: 15, weight: .bold))
            TextField("۱۲۳ ۴۵۶", text: $code)
                .keyboardType(.numberPad)
                .textContentType(.oneTimeCode)
                .focused($codeFocused)
                .font(.system(size: 34, weight: .heavy))
                .multilineTextAlignment(.center)
                .environment(\.layoutDirection, .leftToRight)
                .frame(height: 64)
                .background(Theme.field, in: RoundedRectangle(cornerRadius: 16))
                .onChange(of: code) {
                    let latin = String(code.compactMap { $0.wholeNumberValue.map(String.init) }.joined().prefix(6))
                    if latin != code { code = latin }
                }
            Button {
                bridge.pair(with: phone.id, code: code)
            } label: {
                HStack {
                    if bridge.state == .connecting || bridge.state == .authorizing { ProgressView().tint(Theme.onAccent) }
                    Text("اتصال").font(.system(size: 16, weight: .bold))
                }
                .frame(maxWidth: .infinity, minHeight: 50)
                .foregroundStyle(Theme.onAccent)
                .background(Theme.accent, in: RoundedRectangle(cornerRadius: 14))
            }
            .disabled(code.count != 6)
            Button("انتخاب گوشی دیگر") {
                selected = nil
                code = ""
                bridge.startSearching()
            }
            .frame(maxWidth: .infinity)
        }
    }
}
