package app.ylite.ble

import java.util.Calendar
import java.util.UUID

/**
 * Yamaha CCU (YSCCU_*) framing, as observed from the official Y-Connect app over an HCI snoop.
 *
 * Y-Lite only ever sends frames that were seen from the official app:
 *  - the two 60-byte handshake frames (supplied by the user in Settings, never in source:
 *    they carry the CCU serial and a per-pairing token), and
 *  - heartbeat A/B, once per second.
 * Nothing else is built or sent. Do not add other opcodes: this is a vehicle module.
 *
 * Every frame ends in a checksum byte that makes the sum of all bytes ≡ 0 (mod 256).
 */
object CcuProtocol {
    val NUS_SERVICE: UUID = UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e")
    val NUS_RX: UUID = UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e") // phone -> CCU
    val NUS_TX: UUID = UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9e") // CCU -> phone

    /** The largest CCU frame is ~76 B (0x59 pages), well over the default 20-byte payload. */
    const val MTU = 247
    const val HANDSHAKE_LEN = 60

    /** First byte of a CCU -> phone frame. */
    const val T_ACK = 0x5A
    const val T_ID = 0x5B
    const val T_PAGE = 0x59
    const val T_STREAM_A = 0x56
    const val T_STREAM_B = 0x55

    /** Appends the checksum byte. */
    fun frame(vararg b: Int): ByteArray {
        val body = ByteArray(b.size) { b[it].toByte() }
        val sum = body.sumOf { it.toInt() and 0xFF }
        return body + ((0x100 - (sum and 0xFF)) and 0xFF).toByte()
    }

    fun checksumOk(b: ByteArray): Boolean =
        b.isNotEmpty() && (b.sumOf { it.toInt() and 0xFF } and 0xFF) == 0

    /** Heartbeat A (15 B): `a6 01 05 8a 08 | yyyy(BE) mm dd hh mi ss | 44 | seq | cs`, phone's local clock. */
    fun heartbeatA(now: Calendar, seq: Int): ByteArray {
        val y = now.get(Calendar.YEAR)
        return frame(
            0xA6, 0x01, 0x05, 0x8A, 0x08,
            (y shr 8) and 0xFF, y and 0xFF,
            now.get(Calendar.MONTH) + 1, now.get(Calendar.DAY_OF_MONTH),
            now.get(Calendar.HOUR_OF_DAY), now.get(Calendar.MINUTE), now.get(Calendar.SECOND),
            0x44, seq and 0xFF,
        )
    }

    /** Heartbeat B (9 B): `a6 01 05 8b 02 00 00 | seq | cs`. */
    fun heartbeatB(seq: Int): ByteArray = frame(0xA6, 0x01, 0x05, 0x8B, 0x02, 0x00, 0x00, seq and 0xFF)

    /** Ack `5a 01 7f 01 01 <p> 01 cs`: returns p (00 after handshake #1, 01 after #2), or null. */
    fun ackPayload(b: ByteArray): Int? {
        if (b.size != 8 || !checksumOk(b)) return null
        val u = IntArray(8) { b[it].toInt() and 0xFF }
        if (u[0] != 0x5A || u[1] != 0x01 || u[2] != 0x7F || u[3] != 0x01 || u[4] != 0x01 || u[6] != 0x01) {
            return null
        }
        return u[5]
    }

    /**
     * Checks a pasted handshake frame: `aa 01 7f 00 35 | 53-byte payload | seq | cs`, 60 B,
     * where the payload's last byte and the seq both equal [index] (0 for #1, 1 for #2).
     * Returns null when it looks right, otherwise what is wrong.
     */
    fun validateHandshake(b: ByteArray?, index: Int): String? {
        if (b == null) return "not hex"
        if (b.size != HANDSHAKE_LEN) return "expected $HANDSHAKE_LEN bytes, got ${b.size}"
        val u = IntArray(b.size) { b[it].toInt() and 0xFF }
        if (u[0] != 0xAA || u[1] != 0x01 || u[2] != 0x7F || u[3] != 0x00 || u[4] != 0x35) {
            return "header is not aa 01 7f 00 35"
        }
        if (!checksumOk(b)) return "checksum does not add up"
        if (u[57] != index || u[58] != index) return "trailing index/seq is not %02x".format(index)
        return null
    }

    /** Accepts `aa017f…`, `aa 01 7f …` or `aa:01:7f:…`. */
    fun parseHex(s: String?): ByteArray? {
        if (s == null) return null
        val clean = s.filter { !it.isWhitespace() && it != ':' }.lowercase()
        if (clean.isEmpty() || clean.length % 2 != 0 || clean.any { it !in "0123456789abcdef" }) return null
        return ByteArray(clean.length / 2) { clean.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }
}
