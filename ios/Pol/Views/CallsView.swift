import SwiftUI

struct CallsView: View {
    @EnvironmentObject var store: Store
    @State private var missedOnly = false
    @State private var showKeypad = false

    private var calls: [CallEntry] {
        missedOnly ? store.calls.filter(\.missed) : store.calls
    }

    var body: some View {
        NavigationStack {
            List {
                Picker("فیلتر", selection: $missedOnly) {
                    Text("همه").tag(false)
                    Text("بی‌پاسخ").tag(true)
                }
                .pickerStyle(.segmented)
                .listRowBackground(Color.clear)
                .listRowSeparator(.hidden)

                ForEach(calls) { c in
                    CallRow(call: c, title: store.name(for: c.addr) ?? c.name ?? c.addr) {
                        store.call(c.addr, sim: c.sim)
                    }
                    .listRowBackground(Color.clear)
                    .listRowSeparatorTint(Color(white: 0.12))
                }
                if calls.isEmpty {
                    Text("تماسی نیست")
                        .foregroundStyle(Theme.muted)
                        .frame(maxWidth: .infinity)
                        .padding(.top, 40)
                        .listRowBackground(Color.clear)
                        .listRowSeparator(.hidden)
                }
            }
            .listStyle(.plain)
            .scrollContentBackground(.hidden)
            .background(Theme.bg)
            .refreshable { store.refresh() }
            .animation(.snappy, value: missedOnly)
            .navigationTitle("تماس‌ها")
            .safeAreaInset(edge: .bottom) {
                Button { showKeypad = true } label: {
                    Label("صفحه‌کلید", systemImage: "circle.grid.3x3.fill")
                        .font(.system(size: 16, weight: .bold))
                        .padding(.horizontal, 28)
                        .frame(height: 52)
                        .foregroundStyle(Theme.onAccent)
                        .background(Theme.accent, in: Capsule())
                }
                .padding(.bottom, 10)
            }
            .sheet(isPresented: $showKeypad) {
                KeypadView().presentationDetents([.large])
            }
        }
    }
}

struct CallRow: View {
    let call: CallEntry
    let title: String
    let onCall: () -> Void

    var body: some View {
        HStack(spacing: 12) {
            Image(systemName: call.type == "out" ? "arrow.up.right" : "arrow.down.left")
                .font(.system(size: 15, weight: .semibold))
                .foregroundStyle(call.missed ? Theme.danger : Theme.muted)
                .frame(width: 26)
            VStack(alignment: .leading, spacing: 2) {
                Text(title).font(.system(size: 16, weight: .bold))
                    .foregroundStyle(call.missed ? Theme.danger : .white)
                    .lineLimit(1)
                Text("\(kind) · سیم \(Fa.num(call.sim))").font(.system(size: 13)).foregroundStyle(Theme.muted)
            }
            Spacer()
            Text(Fa.num(Fa.when(call.date))).font(.system(size: 13)).foregroundStyle(Theme.muted)
            Button(action: onCall) {
                Image(systemName: "phone.fill")
                    .foregroundStyle(Theme.accent)
                    .frame(width: 44, height: 44)
                    .background(Theme.field, in: Circle())
            }
            .buttonStyle(.plain)
            .accessibilityLabel("تماس با \(title)")
        }
        .padding(.vertical, 4)
    }

    private var kind: String {
        switch call.type {
        case "out": return "خروجی"
        case "missed": return "بی‌پاسخ"
        default: return "ورودی"
        }
    }
}

struct KeypadView: View {
    @EnvironmentObject var store: Store
    @Environment(\.dismiss) private var dismiss
    @State private var number = ""
    @State private var sim = 1

    private let keys: [[String]] = [["1", "2", "3"], ["4", "5", "6"], ["7", "8", "9"], ["*", "0", "#"]]

    var body: some View {
        VStack(spacing: 18) {
            Capsule().fill(Color(white: 0.3)).frame(width: 40, height: 5).padding(.top, 8)
            Text(number.isEmpty ? " " : Fa.num(number))
                .font(.system(size: 36, weight: .medium))
                .lineLimit(1)
                .minimumScaleFactor(0.5)
                .environment(\.layoutDirection, .leftToRight)
                .frame(maxWidth: .infinity)
                .padding(.top, 20)
            if let name = store.name(for: number), !number.isEmpty {
                Text(name).foregroundStyle(Theme.accent)
            }
            SimPicker(slot: $sim).padding(.horizontal, 24)
            Spacer()
            VStack(spacing: 14) {
                ForEach(keys, id: \.self) { row in
                    HStack(spacing: 24) {
                        ForEach(row, id: \.self) { k in
                            Button {
                                number.append(k)
                                UIImpactFeedbackGenerator(style: .light).impactOccurred()
                            } label: {
                                Text(Fa.num(k))
                                    .font(.system(size: 30))
                                    .frame(width: 78, height: 78)
                                    .background(Theme.field, in: Circle())
                            }
                            .buttonStyle(.plain)
                        }
                    }
                }
            }
            .environment(\.layoutDirection, .leftToRight)
            HStack(spacing: 24) {
                Color.clear.frame(width: 78, height: 78)
                Button {
                    store.call(number, sim: sim)
                    dismiss()
                } label: {
                    Image(systemName: "phone.fill")
                        .font(.system(size: 28))
                        .foregroundStyle(Theme.onAccent)
                        .frame(width: 78, height: 78)
                        .background(Theme.accent, in: Circle())
                }
                .accessibilityLabel("تماس")
                .disabled(number.isEmpty)
                Button { if !number.isEmpty { number.removeLast() } } label: {
                    Image(systemName: "delete.left")
                        .font(.system(size: 24))
                        .foregroundStyle(Theme.text2)
                        .frame(width: 78, height: 78)
                }
                .accessibilityLabel("پاک کردن")
                .opacity(number.isEmpty ? 0 : 1)
            }
            .environment(\.layoutDirection, .leftToRight)
            .padding(.bottom, 24)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(Theme.bg)
        .onAppear { sim = store.defaultSim }
    }
}
