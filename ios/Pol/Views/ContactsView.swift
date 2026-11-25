import SwiftUI

struct ContactsView: View {
    @EnvironmentObject var store: Store
    @State private var query = ""
    @State private var favOnly = false
    @State private var exportResult: String?
    @State private var exporting = false
    @State private var chatWith: ChatThread?

    private var filtered: [Contact] {
        store.contacts.filter { c in
            (!favOnly || c.fav) &&
            (query.isEmpty || c.name.localizedCaseInsensitiveContains(query) || c.phones.contains { $0.contains(query) })
        }
    }

    private var sections: [(String, [Contact])] {
        let groups = Dictionary(grouping: filtered) { $0.name.first.map(String.init) ?? "#" }
        return groups.keys.sorted().map { ($0, groups[$0] ?? []) }
    }

    var body: some View {
        NavigationStack {
            List {
                Section {
                    VStack(spacing: 12) {
                        SearchField(placeholder: "جستجوی نام یا شماره", text: $query)
                        HStack(spacing: 10) {
                            Image(systemName: "arrow.triangle.2.circlepath").foregroundStyle(Theme.accent)
                            Text("\(Fa.num(store.contacts.count)) مخاطب از \(store.deviceName.isEmpty ? "اندروید" : store.deviceName)")
                                .font(.system(size: 13)).foregroundStyle(Theme.text2)
                            Spacer()
                        }
                        .padding(10)
                        .background(Theme.card, in: RoundedRectangle(cornerRadius: 14))
                        Picker("نمایش", selection: $favOnly) {
                            Text("همه").tag(false)
                            Text("علاقه‌مندی‌ها").tag(true)
                        }
                        .pickerStyle(.segmented)
                    }
                    .listRowBackground(Color.clear)
                    .listRowSeparator(.hidden)
                }
                ForEach(sections, id: \.0) { letter, people in
                    Section {
                        ForEach(people) { c in
                            ContactRow(contact: c,
                                       onMessage: { open(c) },
                                       onCall: { if let n = c.phones.first { store.call(n) } })
                                .listRowBackground(Color.clear)
                                .listRowSeparator(.hidden)
                        }
                    } header: {
                        Text(letter).font(.system(size: 13, weight: .bold)).foregroundStyle(Theme.accent)
                    }
                }
            }
            .listStyle(.plain)
            .scrollContentBackground(.hidden)
            .background(Theme.bg)
            .refreshable { store.refresh() }
            .animation(.snappy, value: favOnly)
            .navigationTitle("مخاطبین")
            .navigationDestination(item: $chatWith) { t in ChatView(threadKey: t.id, addr: t.addr, title: t.title) }
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Menu {
                        Button {
                            exporting = true
                            Task {
                                let r = await ContactsExporter.export(store.contacts)
                                exporting = false
                                switch r {
                                case .done(let n): exportResult = n == 0 ? "همه‌ی مخاطبین از قبل در آیفون بودند." : "\(Fa.num(n)) مخاطب به آیفون اضافه شد."
                                case .denied: exportResult = "اجازه‌ی دسترسی به مخاطبین داده نشد. از تنظیمات آیفون فعالش کن."
                                case .failed: exportResult = "ذخیره ناموفق بود."
                                }
                            }
                        } label: {
                            Label("ذخیره در مخاطبین آیفون", systemImage: "person.crop.circle.badge.plus")
                        }
                    } label: {
                        if exporting { ProgressView() } else { Image(systemName: "ellipsis.circle") }
                    }
                    .accessibilityLabel("گزینه‌ها")
                }
            }
            .alert("مخاطبین", isPresented: Binding(get: { exportResult != nil }, set: { if !$0 { exportResult = nil } })) {
                Button("باشه", role: .cancel) {}
            } message: {
                Text(exportResult ?? "")
            }
        }
    }

    private func open(_ c: Contact) {
        guard let n = c.phones.first else { return }
        let key = PhoneNumber.key(n)
        chatWith = store.threads.first { $0.id == key }
            ?? ChatThread(id: key, addr: n, title: c.name,
                          last: SmsMessage(id: "", addr: n, name: c.name, body: "", ts: 0, dir: "out", sim: store.defaultSim),
                          unread: 0)
    }
}

struct ContactRow: View {
    let contact: Contact
    let onMessage: () -> Void
    let onCall: () -> Void

    var body: some View {
        HStack(spacing: 12) {
            Avatar(name: contact.name, size: 44)
            VStack(alignment: .leading, spacing: 1) {
                Text(contact.name).font(.system(size: 16, weight: .bold)).lineLimit(1)
                Text(contact.phones.first ?? "")
                    .font(.system(size: 13)).foregroundStyle(Theme.muted)
                    .environment(\.layoutDirection, .leftToRight)
            }
            Spacer()
            RoundIcon(system: "message.fill", label: "پیام به \(contact.name)", action: onMessage)
            RoundIcon(system: "phone.fill", label: "تماس با \(contact.name)", action: onCall)
        }
        .padding(.vertical, 2)
    }
}

struct RoundIcon: View {
    let system: String
    let label: String
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Image(systemName: system)
                .font(.system(size: 15))
                .foregroundStyle(Theme.accent)
                .frame(width: 44, height: 44)
                .background(Theme.field, in: Circle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(label)
    }
}
