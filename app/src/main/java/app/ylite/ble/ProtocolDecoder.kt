package app.ylite.ble

import app.ylite.data.Telemetry
import java.util.UUID

/**
 * Turns raw characteristic bytes into scooter values.
 *
 * It returns an empty [Telemetry] today, and that is deliberate: nothing is known
 * about the CCU's frame layout yet. The plan (Phase 0) is to capture frames first,
 * then fill in [decode] against real bytes — every other part of the app already
 * reacts to whatever this returns, so no UI code has to change.
 *
 * The CCU is a Nordic UART device:
 *   6e400002  write       phone -> CCU
 *   6e400003  notify      CCU -> phone   <- frames arrive here
 *
 * See lessons/02-decoding-the-frames.md.
 */
object ProtocolDecoder {

    fun decode(service: UUID, characteristic: UUID, data: ByteArray): Telemetry {
        if (data.isEmpty()) return Telemetry()

        // Until the layout is known we keep the shape of the frame visible, so the
        // Debug tab still tells you whether anything is arriving at all.
        return Telemetry(
            extra = mapOf(
                "len" to data.size.toString(),
                "hex" to data.hexCompact(),
            ),
        )
    }
}

/** "0a 1f 00" — spaced, for the frame list. */
fun ByteArray.hex(): String =
    joinToString(" ") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }

/** "0a1f00" — compact, for storage and CSV. */
fun ByteArray.hexCompact(): String =
    joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }

/**
 * Little-endian 16-bit read, the usual layout for BLE sensors.
 * The old draft decoded everything big-endian; check real bytes before trusting either.
 */
fun u16le(data: ByteArray, offset: Int = 0): Int? {
    if (offset < 0 || offset + 1 >= data.size) return null
    return (data[offset].toInt() and 0xff) or ((data[offset + 1].toInt() and 0xff) shl 8)
}

/** Big-endian 16-bit read, kept so the two can be compared on real frames. */
fun u16be(data: ByteArray, offset: Int = 0): Int? {
    if (offset < 0 || offset + 1 >= data.size) return null
    return ((data[offset].toInt() and 0xff) shl 8) or (data[offset + 1].toInt() and 0xff)
}

/** Short 16-bit UUID form (0000180f-…) when the Bluetooth base UUID is in use. */
fun shortUuid(uuid: UUID): String {
    val text = uuid.toString().uppercase()
    val base = "-0000-1000-8000-00805F9B34FB"
    return if (text.startsWith("0000") && text.endsWith(base)) text.substring(4, 8) else text
}
