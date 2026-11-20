package app.ylite.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

/** Pinned to bytes seen in the official app's HCI capture (2026-11-15). */
class CcuProtocolTest {

    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it.toInt() and 0xFF) }

    @Test
    fun heartbeatAMatchesCapture() {
        val t = Calendar.getInstance().apply { clear(); set(2026, Calendar.NOVEMBER, 15, 14, 29, 42) }
        assertEquals("a601058a0807ea0b0f0e1d2a44001e", hex(CcuProtocol.heartbeatA(t, 0)))
    }

    @Test
    fun heartbeatBMatchesCapture() {
        assertEquals("a601058b02000001c6", hex(CcuProtocol.heartbeatB(1)))
    }

    @Test
    fun acksDecode() {
        assertEquals(0, CcuProtocol.ackPayload(CcuProtocol.parseHex("5a017f0101000123")!!))
        assertEquals(1, CcuProtocol.ackPayload(CcuProtocol.parseHex("5a 01 7f 01 01 01 01 22")!!))
        assertNull(CcuProtocol.ackPayload(CcuProtocol.parseHex("5a017f0101000124")!!)) // bad checksum
        assertNull(CcuProtocol.ackPayload(CcuProtocol.parseHex("a601058b02000001c6")!!))
    }

    @Test
    fun checksum() {
        assertTrue(CcuProtocol.checksumOk(CcuProtocol.parseHex("a601058b02000001c6")!!))
        assertFalse(CcuProtocol.checksumOk(CcuProtocol.parseHex("a601058b02000001c7")!!))
        assertFalse(CcuProtocol.checksumOk(ByteArray(0)))
    }

    @Test
    fun handshakeValidation() {
        // Synthetic frame with the right layout. Not a real serial or token.
        fun fake(index: Int): ByteArray {
            val payload = IntArray(53) { 0x30 }.also { it[52] = index }
            return CcuProtocol.frame(0xAA, 0x01, 0x7F, 0x00, 0x35, *payload, index)
        }
        assertEquals(60, fake(0).size)
        assertNull(CcuProtocol.validateHandshake(fake(0), 0))
        assertNull(CcuProtocol.validateHandshake(fake(1), 1))
        assertNotNull(CcuProtocol.validateHandshake(fake(0), 1))
        assertNotNull(CcuProtocol.validateHandshake(fake(0).copyOf(59), 0))
        val broken = fake(0).also { it[10] = (it[10] + 1).toByte() }
        assertNotNull(CcuProtocol.validateHandshake(broken, 0))
        assertNotNull(CcuProtocol.validateHandshake(CcuProtocol.parseHex("zz"), 0))
    }
}
