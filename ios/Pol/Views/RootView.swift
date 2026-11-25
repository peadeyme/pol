import SwiftUI

struct RootView: View {
    @EnvironmentObject var bridge: BridgeManager
    @State private var showSplash = true
    @State private var tab = 1

    var body: some View {
        ZStack {
            Theme.bg.ignoresSafeArea()
            if bridge.isPaired || bridge.state == .connected {
                TabView(selection: $tab) {
                    CallsView()
                        .tabItem { Label("تماس‌ها", systemImage: "phone.fill") }
                        .tag(0)
                    MessagesView()
                        .tabItem { Label("پیام‌ها", systemImage: "message.fill") }
                        .tag(1)
                    ContactsView()
                        .tabItem { Label("مخاطبین", systemImage: "person.crop.circle.fill") }
                        .tag(2)
                    SettingsView()
                        .tabItem { Label("تنظیمات", systemImage: "gearshape.fill") }
                        .tag(3)
                }
                .transition(.opacity)
            } else {
                PairingView()
                    .transition(.opacity)
            }
            if showSplash {
                SplashView().transition(.opacity).zIndex(1)
            }
        }
        .animation(.easeInOut(duration: 0.35), value: bridge.isPaired)
        .task {
            try? await Task.sleep(nanoseconds: 1_100_000_000)
            withAnimation(.easeOut(duration: 0.4)) { showSplash = false }
        }
    }
}

struct SplashView: View {
    var body: some View {
        ZStack {
            Theme.bg.ignoresSafeArea()
            VStack(spacing: 6) {
                Spacer()
                Text("پل").font(.system(size: 56, weight: .heavy))
                Text("اندروید ↔ آیفون").font(.system(size: 15)).foregroundStyle(Theme.muted)
                Spacer()
                Credit().padding(.bottom, 24)
            }
        }
    }
}
