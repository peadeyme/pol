import CoreBluetooth
import Foundation

/// Wire format shared with the Android app (see docs/PROTOCOL.md).
/// One UTF-8 JSON object per message, split into packets with a 1-byte flag header:
/// bit0 = first packet, bit1 = last packet.
enum Wire {
    static let service = CBUUID(string: "6F70C0DE-0000-4E1A-9B2C-000000000001")
    /// Android -> iPhone (notify)
    static let tx = CBUUID(string: "6F70C0DE-0000-4E1A-9B2C-000000000002")
    /// iPhone -> Android (write)
    static let rx = CBUUID(string: "6F70C0DE-0000-4E1A-9B2C-000000000003")

    static let start: UInt8 = 0x01
    static let end: UInt8 = 0x02

    static func split(_ data: Data, maxPacket: Int) -> [Data] {
        let chunk = max(16, maxPacket - 1)
        var packets: [Data] = []
        var offset = 0
        repeat {
            let upper = min(offset + chunk, data.count)
            var flags: UInt8 = 0
            if offset == 0 { flags |= start }
            if upper == data.count { flags |= end }
            var packet = Data([flags])
            packet.append(data.subdata(in: offset..<upper))
            packets.append(packet)
            offset = upper
        } while offset < data.count
        return packets
    }
}

final class Reassembler {
    private var buffer = Data()

    /// Returns the full message once its last packet arrives.
    func push(_ packet: Data) -> Data? {
        guard let flags = packet.first else { return nil }
        if flags & Wire.start != 0 { buffer.removeAll(keepingCapacity: true) }
        buffer.append(packet.dropFirst())
        if flags & Wire.end != 0 {
            let message = buffer
            buffer.removeAll(keepingCapacity: true)
            return message
        }
        return nil
    }
}
