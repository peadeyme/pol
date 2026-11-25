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

    @Environment(\.scenePhase) private var scenePhase

    var body: some Scene {
        WindowGroup {
            RootView()
                .environmentObject(bridge)
                .environmentObject(store)
                .environment(\.layoutDirection, .rightToLeft)
                .environment(\.locale, Locale(identifier: "fa_IR"))
                .preferredColorScheme(.dark)
                .tint(Theme.accent)
                .onChange(of: scenePhase) {
                    if scenePhase == .active { bridge.refreshLink() }
                }
                .onAppear {
                    Notifier.requestPermission()
                    Notifier.scheduleRenewalReminders(expiry: Provisioning.expiry)
                }
        }
    }
}
