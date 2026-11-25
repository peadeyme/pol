package ir.pedicode.pol.ble

import java.io.ByteArrayOutputStream
import java.util.UUID

/**
 * Wire format shared with the iOS app (see docs/PROTOCOL.md).
 *
 * Every message is one UTF-8 JSON object with a "t" (type) field.
 * Messages are split into packets that fit the BLE MTU. Each packet starts
 * with one flag byte: bit0 = first packet of a message, bit1 = last packet.
 */
object Protocol {
    val SERVICE: UUID = UUID.fromString("6F70C0DE-0000-4E1A-9B2C-000000000001")
    /** Android -> iPhone (notify) */
    val TX: UUID = UUID.fromString("6F70C0DE-0000-4E1A-9B2C-000000000002")
    /** iPhone -> Android (write) */
    val RX: UUID = UUID.fromString("6F70C0DE-0000-4E1A-9B2C-000000000003")
    val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    const val FLAG_START: Int = 0x01
    const val FLAG_END: Int = 0x02

    fun split(message: String, mtu: Int): List<ByteArray> {
        val data = message.toByteArray(Charsets.UTF_8)
        // ATT header takes 3 bytes, our flag byte takes 1.
        val chunk = (mtu - 4).coerceAtLeast(16)
        val packets = ArrayList<ByteArray>()
        var offset = 0
        do {
            val end = minOf(offset + chunk, data.size)
            var flags = 0
            if (offset == 0) flags = flags or FLAG_START
            if (end == data.size) flags = flags or FLAG_END
            val packet = ByteArray(1 + end - offset)
            packet[0] = flags.toByte()
            System.arraycopy(data, offset, packet, 1, end - offset)
            packets.add(packet)
            offset = end
        } while (offset < data.size)
        return packets
    }
}

/** Reassembles packets coming from one sender. */
class Reassembler {
    private val buffer = ByteArrayOutputStream()

    /** Returns a complete message when the last packet arrives, else null. */
    fun push(packet: ByteArray): String? {
        if (packet.isEmpty()) return null
        val flags = packet[0].toInt()
        if (flags and Protocol.FLAG_START != 0) buffer.reset()
        buffer.write(packet, 1, packet.size - 1)
        if (flags and Protocol.FLAG_END != 0) {
            val text = buffer.toString(Charsets.UTF_8.name())
            buffer.reset()
            return text
        }
        return null
    }
}
