import CoreBluetooth
import Foundation

/// Bluetooth link to the Android phone. The iPhone is the BLE central.
/// Uses state restoration so iOS can relaunch the app in the background
/// when the Android phone sends an SMS or call event.
final class BridgeManager: NSObject, ObservableObject {
    enum LinkState: Equatable {
        case off            // Bluetooth is off or not allowed
        case searching      // scanning for phones to pair
        case connecting
        case authorizing    // connected, waiting for the Android app to accept us
        case connected
        case denied         // wrong pairing code
    }

    struct Found: Identifiable, Hashable {
        let id: UUID
        let name: String
        let rssi: Int
    }

    @Published private(set) var state: LinkState = .off
    @Published private(set) var found: [Found] = []

    /// Called on the main queue for every complete JSON message.
    var onMessage: (([String: Any]) -> Void)?

    private var central: CBCentralManager!
    private var peripheral: CBPeripheral?
    private var rx: CBCharacteristic?
    private let reassembler = Reassembler()
    private var outbox: [Data] = []
    private var writing = false
    private var pendingCode: String?
    private var discovered: [UUID: CBPeripheral] = [:]

    private let savedKey = "pol.peripheral"
    private let installKey = "pol.installId"

    var pairedID: UUID? {
        get { UserDefaults.standard.string(forKey: savedKey).flatMap(UUID.init(uuidString:)) }
        set { UserDefaults.standard.set(newValue?.uuidString, forKey: savedKey) }
    }

    var isPaired: Bool { pairedID != nil }

    /// Stable id for this iPhone install; the Android app remembers it after pairing.
    private var installID: String {
        if let v = UserDefaults.standard.string(forKey: installKey) { return v }
        let v = UUID().uuidString
        UserDefaults.standard.set(v, forKey: installKey)
        return v
    }

    override init() {
        super.init()
        central = CBCentralManager(
            delegate: self, queue: .main,
            options: [CBCentralManagerOptionRestoreIdentifierKey: "pol.central"]
        )
    }

    // MARK: Pairing

    private var wantsSearch = false

    func startSearching() {
        wantsSearch = true
        guard central.state == .poweredOn else { return }
        found = []
        discovered = [:]
        state = .searching
        central.scanForPeripherals(withServices: [Wire.service], options: nil)
    }

    func stopSearching() {
        wantsSearch = false
        central.stopScan()
        if state == .searching { state = isPaired ? .connecting : .off }
    }

    func pair(with id: UUID, code: String) {
        guard let p = discovered[id] else { return }
        wantsSearch = false
        central.stopScan()
        pendingCode = code
        connect(p)
    }

    func unpair() {
        if let p = peripheral { central.cancelPeripheralConnection(p) }
        peripheral = nil
        pairedID = nil
        state = .off
    }

    // MARK: Sending

    func send(_ object: [String: Any]) {
        guard state == .connected || object["t"] as? String == "hello" else { return }
        guard let data = try? JSONSerialization.data(withJSONObject: object),
              let p = peripheral else { return }
        let max = p.maximumWriteValueLength(for: .withResponse)
        outbox.append(contentsOf: Wire.split(data, maxPacket: max))
        pump()
    }

    private func pump() {
        guard !writing, let p = peripheral, let rx, !outbox.isEmpty else { return }
        writing = true
        p.writeValue(outbox.removeFirst(), for: rx, type: .withResponse)
    }

    // MARK: Connection

    private func connect(_ p: CBPeripheral) {
        peripheral = p
        p.delegate = self
        state = .connecting
        // A pending connect never times out: iOS connects as soon as the phone is in range.
        central.connect(p, options: [CBConnectPeripheralOptionNotifyOnDisconnectionKey: false])
    }

    private func reconnectSaved() {
        guard let id = pairedID else { state = .off; return }
        if let p = central.retrievePeripherals(withIdentifiers: [id]).first {
            connect(p)
        }
    }

    private func handle(_ data: Data) {
        guard let obj = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any],
              let type = obj["t"] as? String else { return }
        switch type {
        case "welcome":
            if let p = peripheral { pairedID = p.identifier }
            pendingCode = nil
            state = .connected
        case "denied":
            state = .denied
            pendingCode = nil
            if let p = peripheral { central.cancelPeripheralConnection(p) }
            if !isPaired { peripheral = nil }
        default:
            break
        }
        onMessage?(obj)
    }

    private func sayHello() {
        var hello: [String: Any] = ["t": "hello", "id": installID, "name": "iPhone"]
        if let code = pendingCode { hello["code"] = code }
        state = .authorizing
        send(hello)
    }
}

extension BridgeManager: CBCentralManagerDelegate {
    func centralManagerDidUpdateState(_ central: CBCentralManager) {
        if central.state == .poweredOn {
            if peripheral == nil { reconnectSaved() } else if let p = peripheral, p.state != .connected { connect(p) }
            if wantsSearch && !isPaired { startSearching() }
        } else {
            state = .off
        }
    }

    func centralManager(_ central: CBCentralManager, willRestoreState dict: [String: Any]) {
        if let restored = (dict[CBCentralManagerRestoredStatePeripheralsKey] as? [CBPeripheral])?.first {
            peripheral = restored
            restored.delegate = self
        }
    }

    func centralManager(_ central: CBCentralManager, didDiscover p: CBPeripheral,
                        advertisementData: [String: Any], rssi RSSI: NSNumber) {
        discovered[p.identifier] = p
        let name = (advertisementData[CBAdvertisementDataLocalNameKey] as? String) ?? p.name ?? "گوشی اندروید"
        let item = Found(id: p.identifier, name: name, rssi: RSSI.intValue)
        if let i = found.firstIndex(where: { $0.id == item.id }) { found[i] = item } else { found.append(item) }
    }

    func centralManager(_ central: CBCentralManager, didConnect p: CBPeripheral) {
        outbox.removeAll()
        writing = false
        p.discoverServices([Wire.service])
    }

    func centralManager(_ central: CBCentralManager, didFailToConnect p: CBPeripheral, error: Error?) {
        if isPaired { connect(p) } else { state = .off }
    }

    func centralManager(_ central: CBCentralManager, didDisconnectPeripheral p: CBPeripheral, error: Error?) {
        rx = nil
        outbox.removeAll()
        writing = false
        if state == .denied { return }
        if isPaired, p.identifier == pairedID { connect(p) } else { state = .off }
    }
}

extension BridgeManager: CBPeripheralDelegate {
    func peripheral(_ p: CBPeripheral, didDiscoverServices error: Error?) {
        guard let service = p.services?.first(where: { $0.uuid == Wire.service }) else { return }
        p.discoverCharacteristics([Wire.tx, Wire.rx], for: service)
    }

    func peripheral(_ p: CBPeripheral, didDiscoverCharacteristicsFor service: CBService, error: Error?) {
        for c in service.characteristics ?? [] {
            if c.uuid == Wire.tx { p.setNotifyValue(true, for: c) }
            if c.uuid == Wire.rx { rx = c }
        }
    }

    func peripheral(_ p: CBPeripheral, didUpdateNotificationStateFor c: CBCharacteristic, error: Error?) {
        if c.uuid == Wire.tx, c.isNotifying { sayHello() }
    }

    func peripheral(_ p: CBPeripheral, didUpdateValueFor c: CBCharacteristic, error: Error?) {
        guard c.uuid == Wire.tx, let value = c.value, let message = reassembler.push(value) else { return }
        handle(message)
    }

    func peripheral(_ p: CBPeripheral, didWriteValueFor c: CBCharacteristic, error: Error?) {
        writing = false
        pump()
    }
}
