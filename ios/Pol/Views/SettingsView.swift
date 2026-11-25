import SwiftUI

struct SettingsView: View {
    @EnvironmentObject var store: Store
    @EnvironmentObject var bridge: BridgeManager
    @State private var confirmUnpair = false

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: 16) {
                    deviceCard
                    if let days = Provisioning.daysLeft { expiryCard(days) }
                    optionsCard
                    Button(role: .destructive) { confirmUnpair = true } label: {
                        Text("قطع اتصال از گوشی اندروید")
                            .frame(maxWidth: .infinity, minHeight: 48)
                            .foregroundStyle(Theme.danger)
                            .overlay(RoundedRectangle(cornerRadius: 14).stroke(Color(red: 0.35, green: 0.16, blue: 0.16)))
                    }
                    VStack(spacing: 2) {
                        Text("نسخه \(Fa.num(Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? ""))")
                            .font(.system(size: 12)).foregroundStyle(Theme.muted)
                        Credit()
                    }
                    .padding(.top, 4)
                }
                .padding(16)
            }
            .background(Theme.bg)
            .navigationTitle("تنظیمات")
            .confirmationDialog("اتصال قطع شود؟", isPresented: $confirmUnpair, titleVisibility: .visible) {
                Button("قطع و پاک کردن داده‌ها", role: .destructive) {
                    bridge.unpair()
                    store.wipe()
                }
            } message: {
                Text("برای وصل شدن دوباره باید کد جفت‌سازی را وارد کنی.")
            }
        }
    }

    private var deviceCard: some View {
        VStack(alignment: .leading, spacing: 14) {
            HStack(spacing: 12) {
                Image(systemName: "candybarphone")
                    .font(.system(size: 22))
                    .foregroundStyle(Theme.accent)
                    .frame(width: 48, height: 48)
                    .background(Color(red: 0.07, green: 0.15, blue: 0.12), in: RoundedRectangle(cornerRadius: 14))
                VStack(alignment: .leading) {
                    Text(store.deviceName.isEmpty ? "گوشی اندروید" : store.deviceName).font(.system(size: 17, weight: .bold))
                    StatusPill()
                }
                Spacer()
            }
            HStack(spacing: 8) {
                stat("باتری", store.battery.map { "\(Fa.num($0))٪" } ?? "—")
                ForEach(store.sims) { s in stat("سیم \(Fa.num(s.slot))", s.name.isEmpty ? "فعال" : s.name) }
            }
        }
        .padding(16)
        .background(Theme.card, in: RoundedRectangle(cornerRadius: 20))
    }

    private func stat(_ title: String, _ value: String) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(title).font(.system(size: 12)).foregroundStyle(Theme.muted)
            Text(value).font(.system(size: 15, weight: .bold)).lineLimit(1).minimumScaleFactor(0.7)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(10)
        .background(Theme.field, in: RoundedRectangle(cornerRadius: 12))
    }

    private func expiryCard(_ days: Int) -> some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack {
                Text("اعتبار نصب اپ").font(.system(size: 16, weight: .bold))
                Spacer()
                Text("\(Fa.num(days)) روز مانده").font(.system(size: 14))
            }
            .foregroundStyle(Color(red: 1, green: 0.886, blue: 0.682))
            ProgressView(value: Double(min(days, 7)), total: 7).tint(Theme.warn)
            Text("LocalDevVPN رو روشن کن، SideStore رو باز کن و روی شمارنده‌ی روزها بزن.")
                .font(.system(size: 13))
                .foregroundStyle(Color(red: 0.851, green: 0.761, blue: 0.604))
            OpenSideStoreButton()
        }
        .padding(16)
        .background(Theme.warnBg, in: RoundedRectangle(cornerRadius: 20))
    }

    private var optionsCard: some View {
        VStack(spacing: 0) {
            HStack {
                Text("سیم‌کارت پیش‌فرض")
                Spacer()
                Picker("سیم‌کارت پیش‌فرض", selection: $store.defaultSim) {
                    ForEach(store.sims.isEmpty ? [1] : store.sims.map(\.slot), id: \.self) { s in
                        Text(store.simName(s)).tag(s)
                    }
                }
                .labelsHidden()
            }
            .frame(minHeight: 52)
            Divider()
            HStack {
                Text("اشتراک")
                Spacer()
                Text(store.licenseLabel).font(.system(size: 13))
                    .foregroundStyle(store.licenseActive ? Theme.muted : Theme.danger)
            }
            .frame(minHeight: 52)
            Divider()
            HStack {
                Text("صدای تماس")
                Spacer()
                Text("فعلاً روی گوشی اندروید").font(.system(size: 13)).foregroundStyle(Theme.muted)
            }
            .frame(minHeight: 52)
            Divider()
            Button { store.refresh() } label: {
                HStack {
                    Text("همگام‌سازی دوباره")
                    Spacer()
                    Image(systemName: "arrow.clockwise")
                }
                .frame(minHeight: 52)
            }
            .disabled(bridge.state != .connected)
        }
        .padding(.horizontal, 16)
        .background(Theme.card, in: RoundedRectangle(cornerRadius: 20))
    }
}
