import Foundation
import SwiftUI

/// All data mirrored from the Android phone, plus actions sent back to it.
@MainActor
final class Store: ObservableObject {
    @Published var deviceName: String = ""
    @Published var battery: Int?
    @Published var sims: [Sim] = []
    @Published var messages: [SmsMessage] = []
    @Published var contacts: [Contact] = []
    @Published var calls: [CallEntry] = []
    @Published var readUpTo: [String: Double] = [:]
    /// Subscription state reported by the Android app.
    @Published var licenseActive = true
    @Published var licensePlan = "trial"
    @Published var licenseDays = 0
    @AppStorage("pol.defaultSim") var defaultSim: Int = 1

    let bridge: BridgeManager
    let callManager: CallManager
    private var nameIndex: [String: String] = [:]

    init(bridge: BridgeManager) {
        self.bridge = bridge
        self.callManager = CallManager(bridge: bridge)
        load()
        bridge.onMessage = { [weak self] obj in
            Task { @MainActor in self?.handle(obj) }
        }
    }

    // MARK: Derived

    var threads: [ChatThread] {
        var groups: [String: [SmsMessage]] = [:]
        for m in messages { groups[m.threadKey, default: []].append(m) }
        return groups.compactMap { key, list -> ChatThread? in
            guard let last = list.max(by: { $0.ts < $1.ts }) else { return nil }
            let seen = readUpTo[key] ?? 0
            let unread = list.filter { $0.incoming && $0.ts > seen }.count
            return ChatThread(id: key, addr: last.addr, title: name(for: last.addr) ?? last.addr, last: last, unread: unread)
        }
        .sorted { $0.last.ts > $1.last.ts }
    }

    func messages(in key: String) -> [SmsMessage] {
        messages.filter { $0.threadKey == key }.sorted { $0.ts < $1.ts }
    }

    func name(for addr: String) -> String? {
        nameIndex[PhoneNumber.key(addr)]
    }

    func markRead(_ key: String) {
        readUpTo[key] = Date().timeIntervalSince1970 * 1000
        save()
    }

    var licenseLabel: String {
        switch licensePlan {
        case "y": return "سالانه · \(Fa.num(licenseDays)) روز مانده"
        case "s": return "شش‌ماهه · \(Fa.num(licenseDays)) روز مانده"
        case "m": return "ماهانه · \(Fa.num(licenseDays)) روز مانده"
        case "trial": return licenseActive ? "آزمایشی · \(Fa.num(licenseDays)) روز مانده" : "غیرفعال"
        default: return licenseActive ? "اشتراک · \(Fa.num(licenseDays)) روز مانده" : "غیرفعال"
        }
    }

    func simName(_ slot: Int) -> String {
        if let s = sims.first(where: { $0.slot == slot }), !s.name.isEmpty {
            return "سیم \(Fa.num(slot)) · \(s.name)"
        }
        return "سیم \(Fa.num(slot))"
    }

    // MARK: Actions

    func sendSms(to: String, body: String, sim: Int) {
        let ref = UUID().uuidString
        let now = Date().timeIntervalSince1970 * 1000
        messages.append(SmsMessage(id: ref, addr: to, name: name(for: to), body: body, ts: now, dir: "out", sim: sim, pending: true))
        bridge.send(["t": "send_sms", "ref": ref, "to": to, "body": body, "sim": sim])
        save()
    }

    func call(_ number: String, sim: Int? = nil) {
        callManager.startCall(to: number, name: name(for: number), sim: sim ?? defaultSim)
    }

    func refresh() { bridge.send(["t": "sync"]) }

    // MARK: Incoming

    private func handle(_ obj: [String: Any]) {
        if let lic = obj["license"] as? [String: Any] {
            licenseActive = lic["active"] as? Bool ?? true
            licensePlan = lic["plan"] as? String ?? "trial"
            licenseDays = lic["daysLeft"] as? Int ?? 0
        }
        switch obj["t"] as? String {
        case "welcome":
            deviceName = obj["device"] as? String ?? ""
            battery = obj["battery"] as? Int
            sims = decode([Sim].self, obj["sims"]) ?? []
        case "status":
            battery = obj["battery"] as? Int
            if let s = decode([Sim].self, obj["sims"]) { sims = s }
        case "contacts":
            contacts = decode([Contact].self, obj["items"]) ?? []
            rebuildNames()
        case "sms_sync":
            let synced = decode([SmsMessage].self, obj["items"]) ?? []
            let pending = messages.filter { $0.pending == true || $0.failed == true }
            messages = synced + pending
            if readUpTo.isEmpty {
                // First sync: treat history as read.
                for t in threads { readUpTo[t.id] = t.last.ts }
            }
        case "sms":
            if let m = decode(SmsMessage.self, obj["item"]) {
                messages.append(m)
                Notifier.sms(from: name(for: m.addr) ?? m.name ?? m.addr, body: m.body, thread: m.threadKey)
            }
        case "sent":
            if let ref = obj["ref"] as? String, let i = messages.firstIndex(where: { $0.id == ref }) {
                let ok = obj["ok"] as? Bool ?? false
                messages[i].pending = nil
                messages[i].failed = ok ? nil : true
            }
        case "license":
            break
        case "calls":
            calls = decode([CallEntry].self, obj["items"]) ?? []
        case "call":
            callManager.handle(event: obj, name: (obj["addr"] as? String).flatMap { name(for: $0) })
        default:
            return
        }
        save()
    }

    private func rebuildNames() {
        var index: [String: String] = [:]
        for c in contacts { for p in c.phones { index[PhoneNumber.key(p)] = c.name } }
        nameIndex = index
    }

    private func decode<T: Decodable>(_ type: T.Type, _ value: Any?) -> T? {
        guard let value, JSONSerialization.isValidJSONObject(value),
              let data = try? JSONSerialization.data(withJSONObject: value) else { return nil }
        return try? JSONDecoder().decode(type, from: data)
    }

    // MARK: Persistence (so data shows instantly before the phone reconnects)

    private struct Snapshot: Codable {
        var deviceName: String
        var sims: [Sim]
        var messages: [SmsMessage]
        var contacts: [Contact]
        var calls: [CallEntry]
        var readUpTo: [String: Double]
    }

    private var fileURL: URL {
        FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("pol.json")
    }

    private var saveTask: Task<Void, Never>?

    private func save() {
        saveTask?.cancel()
        let snap = Snapshot(deviceName: deviceName, sims: sims, messages: messages, contacts: contacts, calls: calls, readUpTo: readUpTo)
        let url = fileURL
        saveTask = Task.detached(priority: .utility) {
            try? await Task.sleep(nanoseconds: 400_000_000)
            if Task.isCancelled { return }
            try? FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
            if let data = try? JSONEncoder().encode(snap) {
                try? data.write(to: url, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
            }
        }
    }

    private func load() {
        guard let data = try? Data(contentsOf: fileURL),
              let snap = try? JSONDecoder().decode(Snapshot.self, from: data) else { return }
        deviceName = snap.deviceName
        sims = snap.sims
        messages = snap.messages
        contacts = snap.contacts
        calls = snap.calls
        readUpTo = snap.readUpTo
        rebuildNames()
    }

    func wipe() {
        messages = []; contacts = []; calls = []; sims = []; deviceName = ""; battery = nil; readUpTo = [:]
        nameIndex = [:]
        try? FileManager.default.removeItem(at: fileURL)
    }
}
