import SwiftUI

struct MessagesView: View {
    @EnvironmentObject var store: Store
    @State private var query = ""
    @State private var composing = false

    private var threads: [ChatThread] {
        let all = store.threads
        guard !query.isEmpty else { return all }
        return all.filter { $0.title.localizedCaseInsensitiveContains(query) || $0.addr.contains(query) || $0.last.body.localizedCaseInsensitiveContains(query) }
    }

    var body: some View {
        NavigationStack {
            List {
                Section {
                    VStack(alignment: .leading, spacing: 14) {
                        StatusPill()
                        if !store.licenseActive { LicenseBanner() }
                        if let days = Provisioning.daysLeft, days <= 2 {
                            RenewalBanner(daysLeft: days)
                        }
                        SearchField(placeholder: "جستجو در پیام‌ها", text: $query)
                    }
                    .listRowBackground(Color.clear)
                    .listRowSeparator(.hidden)
                    .listRowInsets(EdgeInsets(top: 0, leading: 16, bottom: 8, trailing: 16))
                }
                ForEach(threads) { t in
                    NavigationLink(value: t) { ThreadRow(thread: t) }
                        .listRowBackground(Color.clear)
                        .listRowSeparatorTint(Color(white: 0.12))
                }
                if threads.isEmpty {
                    Text(query.isEmpty ? "هنوز پیامی نیامده" : "چیزی پیدا نشد")
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
            .navigationTitle("پیام‌ها")
            .navigationDestination(for: ChatThread.self) { t in ChatView(threadKey: t.id, addr: t.addr, title: t.title) }
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Button { composing = true } label: { Image(systemName: "square.and.pencil") }
                        .accessibilityLabel("پیام جدید")
                }
            }
            .sheet(isPresented: $composing) { NewMessageView() }
            .animation(.snappy, value: threads.map(\.id))
        }
    }
}

struct ThreadRow: View {
    let thread: ChatThread

    var body: some View {
        HStack(spacing: 12) {
            Avatar(name: thread.title)
            VStack(alignment: .leading, spacing: 3) {
                HStack {
                    Text(thread.title).font(.system(size: 16, weight: .bold)).lineLimit(1)
                    Spacer()
                    Text(Fa.num(Fa.when(thread.last.date))).font(.system(size: 12)).foregroundStyle(Theme.muted)
                }
                HStack(spacing: 6) {
                    Text(thread.last.body).font(.system(size: 14)).foregroundStyle(Theme.text2).lineLimit(1)
                    Spacer()
                    SimBadge(slot: thread.last.sim)
                    if thread.unread > 0 {
                        Text(Fa.num(thread.unread))
                            .font(.system(size: 12, weight: .bold))
                            .foregroundStyle(Theme.onAccent)
                            .frame(minWidth: 20, minHeight: 20)
                            .background(Theme.accent, in: Capsule())
                    }
                }
            }
        }
        .padding(.vertical, 6)
    }
}

struct NewMessageView: View {
    @EnvironmentObject var store: Store
    @Environment(\.dismiss) private var dismiss
    @State private var to = ""
    @State private var body_ = ""
    @State private var sim = 1

    var body: some View {
        NavigationStack {
            Form {
                Section("گیرنده") {
                    TextField("شماره یا نام", text: $to)
                        .keyboardType(.phonePad)
                        .environment(\.layoutDirection, .leftToRight)
                    let matches = store.contacts.filter { !to.isEmpty && $0.name.contains(to) }.prefix(5)
                    ForEach(Array(matches)) { c in
                        Button { to = c.phones.first ?? to } label: {
                            HStack { Text(c.name); Spacer(); Text(c.phones.first ?? "").foregroundStyle(Theme.muted) }
                        }
                    }
                }
                Section("متن") {
                    TextField("پیامک", text: $body_, axis: .vertical).lineLimit(3...8)
                }
                Section { SimPicker(slot: $sim) }
            }
            .scrollContentBackground(.hidden)
            .background(Theme.bg)
            .navigationTitle("پیام جدید")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("لغو") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("ارسال") {
                        store.sendSms(to: to, body: body_, sim: sim)
                        dismiss()
                    }
                    .disabled(to.isEmpty || body_.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                }
            }
            .onAppear { sim = store.defaultSim }
        }
    }
}
