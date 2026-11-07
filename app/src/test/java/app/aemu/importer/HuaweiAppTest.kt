package app.aemu.importer

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import org.junit.Assert.*
import org.junit.Test

class HuaweiAppTest {
    private class MemSource(val data: ByteArray) : RandomSource {
        override val size get() = data.size.toLong()
        override fun read(pos: Long, dst: ByteBuffer) {
            var p = pos.toInt()
            while (dst.hasRemaining()) dst.put(if (p < data.size) data[p++] else 0)
        }
    }

    private fun le(v: Long, n: Int) = ByteArray(n) { (v shr (8 * it)).toByte() }

    /** one packet exactly as found in dload/UPDATE.APP */
    private fun packet(id: Int, payload: ByteArray, blockSize: Int = 4096, corrupt: Boolean = false): ByteArray {
        val blocks = (payload.size + blockSize - 1) / blockSize
        val o = ByteArrayOutputStream()
        o.write(byteArrayOf(0x55, 0xAA.toByte(), 0x5A, 0xA5.toByte()))
        o.write(le(98L + 2 * blocks, 4)); o.write(le(1, 4))
        o.write("HW7x27A".toByteArray()); o.write(0xff)
        o.write(byteArrayOf(0, 0, 0, id.toByte()))
        o.write(le(payload.size.toLong(), 4))
        for (s in listOf("2012.03.21", "18.43.40", "INPUT", "")) { o.write(s.toByteArray()); o.write(ByteArray(16 - s.length)) }
        o.write(le(0x1234, 2)); o.write(le(blockSize.toLong(), 2)); o.write(ByteArray(2))
        for (b in 0 until blocks) {
            val len = minOf(blockSize, payload.size - b * blockSize)
            val crc = HuaweiApp.crc16(payload, b * blockSize, len) xor (if (corrupt && b == 0) 1 else 0)
            o.write(le(crc.toLong(), 2))
        }
        o.write(payload)
        while (o.size() % 4 != 0) o.write(0)
        return o.toByteArray()
    }

    private fun file(vararg packets: ByteArray) = ByteArrayOutputStream().also { o ->
        o.write(ByteArray(0x5C)); packets.forEach { o.write(it) }
    }.toByteArray()

    @Test fun crcMatchesX25CheckValue() {
        val d = "123456789".toByteArray()
        assertEquals(0x906e, HuaweiApp.crc16(d, 0, d.size))
    }

    @Test fun probesMagicAtFixedOffset() {
        assertTrue(HuaweiApp.probe(file(packet(0, ByteArray(10)))))
        assertFalse(HuaweiApp.probe(ByteArray(0x200)))
    }

    @Test fun walksPacketChainAndPadding() {
        val a = ByteArray(128) { it.toByte() }
        val b = ByteArray(9000) { (it * 7).toByte() }     // 3 blocks, last one partial, not 4-byte aligned size below
        val c = ByteArray(5001) { 3 }                     // forces 3 bytes of padding
        val src = MemSource(file(packet(0xfd, a), packet(0x00, b), packet(0x40, c)))
        val ps = HuaweiApp.packets(src)
        assertEquals(listOf(0xfd, 0x00, 0x40), ps.map { it.id })
        assertEquals(listOf(128L, 9000L, 5001L), ps.map { it.size })
        assertEquals("HW7x27A", ps[0].hardware)
        assertEquals("2012.03.21", ps[0].date)
        assertEquals(3, ps[1].blocks)
        for ((p, expect) in ps.zip(listOf(a, b, c))) {
            val got = ByteBuffer.allocate(expect.size); src.read(p.dataOffset, got)
            assertArrayEquals(expect, got.array())
            assertEquals(0, HuaweiApp.verify(src, p))
        }
    }

    @Test fun verifyCountsCorruptBlocks() {
        val payload = ByteArray(10_000) { it.toByte() }
        val ok = MemSource(file(packet(0, payload)))
        assertEquals(0, HuaweiApp.verify(ok, HuaweiApp.packets(ok).single()))
        val bad = MemSource(file(packet(0, payload, corrupt = true)))
        assertEquals(1, HuaweiApp.verify(bad, HuaweiApp.packets(bad).single()))
        val flipped = file(packet(0, payload)).also { it[it.size - 10] = (it[it.size - 10] + 1).toByte() }
        assertEquals(1, HuaweiApp.verify(MemSource(flipped), HuaweiApp.packets(MemSource(flipped)).single()))
    }

    @Test fun rejectsTruncatedAndInconsistentFiles() {
        val full = file(packet(0, ByteArray(20_000)))
        assertThrows(IOException::class.java) { HuaweiApp.packets(MemSource(full.copyOf(full.size - 100))) }
        val wrongLen = full.copyOf().also { it[0x5C + 4] = 0x70 } // header length no longer 98 + 2 * blocks
        assertThrows(IOException::class.java) { HuaweiApp.packets(MemSource(wrongLen)) }
    }

    @Test fun stopsAtTrailingBytesWithoutMagic() {
        val src = MemSource(file(packet(0, ByteArray(64))) + ByteArray(512))
        assertEquals(1, HuaweiApp.packets(src).size)
    }
}
