package app.aemu.core

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import java.util.Base64
import java.util.zip.Adler32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * A 556-byte dex with SyntheticPasswordCrypto.createBlob() and a second method "other", both calling
 * KeyStore.setEntry(String, KeyStore$Entry, KeyStore$ProtectionParameter). Only createBlob() may change.
 */
class LockSettingsBlobPatchTest {
    private val dex: ByteArray get() = Base64.getDecoder().decode("ZGV4CjAzNQDjblpvYRlebkb6gAprErpPIYfv9A4/VK0sAgAAcAAAAHhWNBIAAAAAAAAAAAAAAAAKAAAAcAAAAAYAAACYAAAAAgAAALAAAAAAAAAAAAAAAAMAAADIAAAAAQAAAOAAAAAsAQAAAAEAAAABAAA7AQAATwEAAG8BAACdAQAAtwEAALoBAADAAQAAzAEAANMBAAAAAAAABAAAAAEAAAACAAAAAwAAAAUAAAAGAAAABQAAAOABAAAFAAAABQAAAAAAAAAAAAEABwAAAAAAAQAIAAAAAQAAAAkAAAAAAAAAAQAAAP////8AAAAA/////wAAAAAgAgAAAAAAADlMY29tL2FuZHJvaWQvc2VydmVyL2xvY2tzZXR0aW5ncy9TeW50aGV0aWNQYXNzd29yZENyeXB0bzsAEkxqYXZhL2xhbmcvU3RyaW5nOwAeTGphdmEvc2VjdXJpdHkvS2V5U3RvcmUkRW50cnk7ACxMamF2YS9zZWN1cml0eS9LZXlTdG9yZSRQcm90ZWN0aW9uUGFyYW1ldGVyOwAYTGphdmEvc2VjdXJpdHkvS2V5U3RvcmU7AAFWAARWTExMAApjcmVhdGVCbG9iAAVvdGhlcgAIc2V0RW50cnkAAAAAAwAAAAIAAwAEAAAABAAAAAQAAAAAAAAABAAAAG5AAgAQMg4ABAAAAAQAAAAAAAAABgAAAAAAAABuQAIAEDIOAAAAAAIAAewDAQGEBA==")
    private val createBlobInvoke = 0x1ec + 16        // first code unit of createBlob's code
    private val otherInvoke = 0x204 + 16 + 4         // "other" starts with two nops

    private fun u4(d: ByteArray, o: Int) = (d[o].toInt() and 255) or ((d[o + 1].toInt() and 255) shl 8) or
        ((d[o + 2].toInt() and 255) shl 16) or ((d[o + 3].toInt() and 255) shl 24)

    private fun headerValid(d: ByteArray) =
        Adler32().apply { update(d, 12, d.size - 12) }.value.toInt() == u4(d, 8) &&
            MessageDigest.getInstance("SHA-1").digest(d.copyOfRange(32, d.size)).contentEquals(d.copyOfRange(12, 32))

    @Test fun fixtureIsValid() = assertTrue(headerValid(dex))

    @Test fun skipsOnlyTheCallInCreateBlob() {
        val d = dex
        val r = LockSettingsBlobPatch.patchDex(d)
        assertEquals(LockSettingsBlobPatch.Outcome.PATCHED, r.outcome)
        assertEquals(1, r.calls)
        assertTrue((0 until 6).all { d[createBlobInvoke + it] == 0.toByte() })
        assertEquals(0x6e.toByte(), d[otherInvoke])                       // the other method keeps its call
        assertEquals(dex.size, d.size)
        assertTrue(headerValid(d))
    }

    @Test fun secondRunIsAlready() {
        val d = dex
        LockSettingsBlobPatch.patchDex(d)
        assertEquals(LockSettingsBlobPatch.Outcome.ALREADY, LockSettingsBlobPatch.patchDex(d).outcome)
    }

    @Test fun otherFilesAreAbsent() = assertEquals(LockSettingsBlobPatch.Outcome.ABSENT, LockSettingsBlobPatch.patchDex(ByteArray(300)).outcome)

    private fun jar(stored: Boolean): File {
        val f = File.createTempFile("services", ".jar")
        ZipOutputStream(f.outputStream()).use { z ->
            val d = dex
            val e = ZipEntry("classes.dex")
            if (stored) { e.method = ZipEntry.STORED; e.size = d.size.toLong(); e.compressedSize = d.size.toLong(); e.crc = java.util.zip.CRC32().apply { update(d) }.value }
            z.putNextEntry(e); z.write(d); z.closeEntry()
            z.putNextEntry(ZipEntry("META-INF/MANIFEST.MF")); z.write("Manifest-Version: 1.0\n".toByteArray()); z.closeEntry()
        }
        return f
    }

    private fun jarRoundTrip(stored: Boolean) {
        val f = jar(stored); val bak = File(f.path + ".orig"); val stock = f.readBytes()
        assertEquals(LockSettingsBlobPatch.Outcome.PATCHED, LockSettingsBlobPatch.patchJar(f, bak).outcome)
        assertArrayEquals(stock, bak.readBytes())
        ZipFile(f).use { z ->
            val e = z.getEntry("classes.dex")
            assertEquals(stored, e.method == ZipEntry.STORED)
            val d = z.getInputStream(e).readBytes()
            assertTrue(headerValid(d))
            assertEquals(0, d[createBlobInvoke].toInt())
            assertNotNull(z.getEntry("META-INF/MANIFEST.MF"))
        }
        assertEquals(LockSettingsBlobPatch.Outcome.ALREADY, LockSettingsBlobPatch.patchJar(f, bak).outcome)
        f.delete(); bak.delete()
    }

    @Test fun storedJar() = jarRoundTrip(true)
    @Test fun deflatedJar() = jarRoundTrip(false)

    @Test fun onlyKirkwoodPro() {
        assertTrue(LockSettingsBlobPatch.isTargetDevice("kirkwoodpro"))
        assertFalse(LockSettingsBlobPatch.isTargetDevice("kirkwood"))
        assertFalse(LockSettingsBlobPatch.isTargetDevice(null))
    }
}
