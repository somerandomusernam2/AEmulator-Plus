package app.aemu.core

import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream
import java.util.UUID

/**
 * Wire protocol between the host Bluetooth bridge and the guest-side Bluetooth service.
 * One long-lived stream connection on /dev/aemu_bluetooth; frames in both directions:
 *
 *   u32le length   (= 5 + payload size, at most [MAX_FRAME])
 *   u8    op
 *   u32le id       (request id for guest requests and [RESP]; 0 for host events)
 *   payload
 *
 * Every guest request gets exactly one [RESP] carrying the same id: i32 status (0 = ok, negative errno),
 * then op-specific data. Slow requests (RFCOMM connect) answer late, so the guest must match replies by id.
 * Host events (op >= 0x80) arrive at any time. Full description: BLUETOOTH_BRIDGE.md.
 */
object BluetoothProtocol {
    const val VERSION = 1
    const val MAX_FRAME = 65_536
    const val HEADER_BODY = 5
    const val MAX_STRING = 255
    const val MAX_SPP_WRITE = 16_384
    const val MAX_GATT_VALUE = 512

    // guest -> host requests
    const val OP_HELLO = 0x01
    const val OP_ADAPTER_INFO = 0x02
    const val OP_BONDED = 0x03
    const val OP_SCAN_START = 0x04
    const val OP_SCAN_STOP = 0x05
    const val OP_BOND_CREATE = 0x06
    const val OP_SPP_OPEN = 0x10
    const val OP_SPP_WRITE = 0x11
    const val OP_SPP_CLOSE = 0x12
    const val OP_SPP_LISTEN = 0x13
    const val OP_SPP_UNLISTEN = 0x14
    const val OP_GATT_OPEN = 0x20
    const val OP_GATT_DISCOVER = 0x21
    const val OP_GATT_READ = 0x22
    const val OP_GATT_WRITE = 0x23
    const val OP_GATT_NOTIFY = 0x24
    const val OP_GATT_MTU = 0x25
    const val OP_GATT_CLOSE = 0x26

    /** host -> guest reply to a request */
    const val OP_RESP = 0x7F

    // host -> guest events
    const val EV_SCAN_RESULT = 0x80
    const val EV_SCAN_DONE = 0x81
    const val EV_ADAPTER_STATE = 0x82
    const val EV_BOND_STATE = 0x83
    const val EV_SPP_DATA = 0x90
    const val EV_SPP_CLOSED = 0x91
    const val EV_SPP_ACCEPT = 0x92
    const val EV_GATT_STATE = 0xA0
    const val EV_GATT_SERVICES = 0xA1
    const val EV_GATT_READ = 0xA2
    const val EV_GATT_WRITE = 0xA3
    const val EV_GATT_CHANGED = 0xA4
    const val EV_GATT_MTU = 0xA5

    // scan modes (bit set) and device kinds
    const val SCAN_CLASSIC = 1
    const val SCAN_LE = 2
    const val KIND_CLASSIC = 1
    const val KIND_LE = 2

    // GATT attribute kinds
    const val ATTR_SERVICE = 1
    const val ATTR_CHARACTERISTIC = 2
    const val ATTR_DESCRIPTOR = 3

    // GATT write types
    const val WRITE_NO_RESPONSE = 1
    const val WRITE_DEFAULT = 2

    // capability bits in the HELLO reply
    const val CAP_CLASSIC_SCAN = 1
    const val CAP_LE_SCAN = 2
    const val CAP_SPP = 4
    const val CAP_GATT = 8
    const val CAP_BOND = 16
    const val CAP_LISTEN = 32

    // status codes (negative errno)
    const val OK = 0
    const val EPERM = -1
    const val ENOENT = -2
    const val EIO = -5
    const val EAGAIN = -11
    const val EBUSY = -16
    const val ENODEV = -19
    const val EINVAL = -22
    const val EMFILE = -24
    const val EPROTO = -71
    const val ENOTCONN = -107
    const val ETIMEDOUT = -110

    private val ADDRESS = Regex("^([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}$")

    class Frame(val op: Int, val id: Int, val payload: ByteArray = ByteArray(0))

    /** Malformed input from the guest: bad length, truncated payload, oversized string. */
    class FormatException(message: String) : IllegalArgumentException(message)

    fun encode(frame: Frame): ByteArray {
        val length = HEADER_BODY + frame.payload.size
        require(length <= MAX_FRAME) { "frame too large: $length" }
        val out = ByteArray(4 + length)
        putU32(out, 0, length.toLong())
        out[4] = frame.op.toByte()
        putU32(out, 5, frame.id.toLong() and 0xffffffffL)
        frame.payload.copyInto(out, 9)
        return out
    }

    /** Next frame, or null on a clean end of stream before the first byte. */
    fun read(input: InputStream): Frame? {
        val head = ByteArray(4)
        val first = input.read()
        if (first < 0) return null
        head[0] = first.toByte()
        readFully(input, head, 1, 3)
        val length = getU32(head, 0)
        if (length < HEADER_BODY || length > MAX_FRAME) throw FormatException("bad frame length $length")
        val body = ByteArray(length.toInt())
        readFully(input, body, 0, body.size)
        return Frame(body[0].toInt() and 255, getU32(body, 1).toInt(), body.copyOfRange(HEADER_BODY, body.size))
    }

    fun normalizeAddress(text: String): String? =
        if (ADDRESS.matches(text)) text.uppercase() else null

    fun uuidBytes(uuid: UUID): ByteArray = ByteArray(16).also {
        putU64(it, 0, uuid.mostSignificantBits)
        putU64(it, 8, uuid.leastSignificantBits)
    }

    fun capabilities(classic: Boolean, le: Boolean): Int =
        (if (classic) CAP_CLASSIC_SCAN or CAP_SPP or CAP_LISTEN else 0) or (if (le) CAP_LE_SCAN or CAP_GATT else 0) or
            (if (classic || le) CAP_BOND else 0)

    private fun readFully(input: InputStream, buf: ByteArray, offset: Int, count: Int) {
        var done = 0
        while (done < count) {
            val n = input.read(buf, offset + done, count - done)
            if (n < 0) throw EOFException("stream ended inside a frame")
            done += n
        }
    }

    internal fun putU32(buf: ByteArray, at: Int, value: Long) {
        for (i in 0..3) buf[at + i] = (value ushr (8 * i)).toByte()
    }
    internal fun getU32(buf: ByteArray, at: Int): Long =
        (0..3).fold(0L) { acc, i -> acc or ((buf[at + i].toLong() and 255) shl (8 * i)) }
    private fun putU64(buf: ByteArray, at: Int, value: Long) {
        for (i in 0..7) buf[at + i] = (value ushr (8 * (7 - i))).toByte()
    }
}

/** Bounds-checked little-endian reader; every underflow becomes a [BluetoothProtocol.FormatException]. */
class BtReader(private val data: ByteArray) {
    private var pos = 0
    val remaining get() = data.size - pos

    private fun need(n: Int) {
        if (n < 0 || n > remaining) throw BluetoothProtocol.FormatException("truncated payload")
    }
    fun u8(): Int { need(1); return data[pos++].toInt() and 255 }
    fun u16(): Int { need(2); val v = (data[pos].toInt() and 255) or ((data[pos + 1].toInt() and 255) shl 8); pos += 2; return v }
    fun u32(): Long { need(4); val v = BluetoothProtocol.getU32(data, pos); pos += 4; return v }
    fun i32(): Int = u32().toInt()
    fun bytes(n: Int): ByteArray { need(n); return data.copyOfRange(pos, pos + n).also { pos += n } }
    fun rest(): ByteArray = bytes(remaining)
    fun string(): String {
        val n = u16()
        if (n > BluetoothProtocol.MAX_STRING) throw BluetoothProtocol.FormatException("string too long")
        return String(bytes(n), Charsets.UTF_8)
    }
    fun uuid(): UUID {
        val raw = bytes(16)
        fun u64(at: Int) = (0..7).fold(0L) { acc, i -> (acc shl 8) or (raw[at + i].toLong() and 255) }
        return UUID(u64(0), u64(8))
    }
}

/** Little-endian payload builder; strings longer than the protocol limit are cut on a byte boundary. */
class BtWriter {
    private val out = ByteArrayOutputStream()
    fun u8(v: Int) = apply { out.write(v and 255) }
    fun u16(v: Int) = apply { out.write(v and 255); out.write((v ushr 8) and 255) }
    fun u32(v: Long) = apply { val b = ByteArray(4); BluetoothProtocol.putU32(b, 0, v); out.write(b) }
    fun i32(v: Int) = u32(v.toLong() and 0xffffffffL)
    fun bytes(b: ByteArray) = apply { out.write(b) }
    fun string(s: String) = apply {
        var raw = s.toByteArray(Charsets.UTF_8)
        if (raw.size > BluetoothProtocol.MAX_STRING) raw = raw.copyOf(BluetoothProtocol.MAX_STRING)
        u16(raw.size); out.write(raw)
    }
    fun uuid(u: UUID) = bytes(BluetoothProtocol.uuidBytes(u))
    fun build(): ByteArray = out.toByteArray()
}
