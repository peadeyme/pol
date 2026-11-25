import SwiftUI

@main
struct PolApp: App {
    @StateObject private var bridge: BridgeManager
    @StateObject private var store: Store

    init() {
        let bridge = BridgeManager()
        _bridge = StateObject(wrappedValue: bridge)
        _store = StateObject(wrappedValue: Store(bridge: bridge))
    }

    var body: some Scene {
        WindowGroup {
            RootView()
                .environmentObject(bridge)
                .environmentObject(store)
                .environment(\.layoutDirection, .rightToLeft)
                .environment(\.locale, Locale(identifier: "fa_IR"))
                .preferredColorScheme(.dark)
                .tint(Theme.accent)
                .onAppear {
                    Notifier.requestPermission()
                    Notifier.scheduleRenewalReminders(expiry: Provisioning.expiry)
                }
        }
    }
}
