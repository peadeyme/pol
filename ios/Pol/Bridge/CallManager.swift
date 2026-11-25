import AVFoundation
import CallKit
import Foundation

/// Shows Android calls with the native iOS call screen and sends
/// answer / end / dial back to the Android phone.
///
/// Phase 1: call audio stays on the Android phone (answering here answers there).
final class CallManager: NSObject, CXProviderDelegate {
    private let provider: CXProvider
    private let controller = CXCallController()
    private weak var bridge: BridgeManager?

    /// Android call id -> CallKit UUID
    private var calls: [String: UUID] = [:]
    private var outgoing: UUID?

    init(bridge: BridgeManager) {
        self.bridge = bridge
        let config = CXProviderConfiguration()
        config.supportsVideo = false
        config.maximumCallsPerCallGroup = 1
        config.maximumCallGroups = 1
        config.supportedHandleTypes = [.phoneNumber]
        config.includesCallsInRecents = true
        provider = CXProvider(configuration: config)
        super.init()
        provider.setDelegate(self, queue: .main)
    }

    /// Handles a {"t":"call"} event from Android.
    func handle(event: [String: Any], name: String?) {
        guard let id = event["id"] as? String, let state = event["state"] as? String else { return }
        switch state {
        case "ringing":
            guard calls[id] == nil, let addr = event["addr"] as? String else { return }
            let uuid = UUID()
            calls[id] = uuid
            let update = CXCallUpdate()
            update.remoteHandle = CXHandle(type: .phoneNumber, value: addr)
            update.localizedCallerName = name ?? (event["name"] as? String) ?? addr
            update.hasVideo = false
            update.supportsHolding = false
            update.supportsDTMF = false
            update.supportsGrouping = false
            update.supportsUngrouping = false
            provider.reportNewIncomingCall(with: uuid, update: update) { [weak self] error in
                if error != nil { self?.calls[id] = nil }
            }
        case "active":
            if let out = outgoing, calls[id] == nil {
                calls[id] = out
                outgoing = nil
                provider.reportOutgoingCall(with: out, connectedAt: Date())
            }
        case "ended":
            if let uuid = calls.removeValue(forKey: id) ?? outgoing {
                outgoing = nil
                provider.reportCall(with: uuid, endedAt: Date(), reason: .remoteEnded)
            }
        default:
            break
        }
    }

    func startCall(to number: String, name: String?, sim: Int) {
        let uuid = UUID()
        let handle = CXHandle(type: .phoneNumber, value: number)
        let action = CXStartCallAction(call: uuid, handle: handle)
        action.contactIdentifier = name
        pendingSim = sim
        controller.request(CXTransaction(action: action)) { _ in }
    }

    private var pendingSim = 1

    // MARK: CXProviderDelegate

    func providerDidReset(_ provider: CXProvider) {
        calls.removeAll()
        outgoing = nil
    }

    func provider(_ provider: CXProvider, perform action: CXStartCallAction) {
        outgoing = action.callUUID
        bridge?.send(["t": "dial", "to": action.handle.value, "sim": pendingSim])
        provider.reportOutgoingCall(with: action.callUUID, startedConnectingAt: Date())
        action.fulfill()
    }

    func provider(_ provider: CXProvider, perform action: CXAnswerCallAction) {
        bridge?.send(["t": "call_answer"])
        action.fulfill()
    }

    func provider(_ provider: CXProvider, perform action: CXEndCallAction) {
        bridge?.send(["t": "call_end"])
        calls = calls.filter { $0.value != action.callUUID }
        if outgoing == action.callUUID { outgoing = nil }
        action.fulfill()
    }

    func provider(_ provider: CXProvider, didActivate audioSession: AVAudioSession) {}
    func provider(_ provider: CXProvider, didDeactivate audioSession: AVAudioSession) {}
}
