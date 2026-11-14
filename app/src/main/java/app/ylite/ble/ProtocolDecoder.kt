package app.ylite.ble

import java.util.UUID

/**
 * Turns raw characteristic bytes into readable values.
 *
 * It is deliberately empty: nothing is known about the CCU yet. Phase 0 (capture)
 * comes first, then this file is written against real bytes. Until then the Debug
 * tab shows the raw hex so you can decode by hand and compare.
 */
object ProtocolDecoder {

    fun decode(service: UUID, characteristic: UUID, data: ByteArray): Map<String, String> {
        if (data.isEmpty()) return emptyMap()
        return emptyMap()
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
