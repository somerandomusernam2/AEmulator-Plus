package app.aemu.core

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The software keymaster libraries must be of one release. SymmetricKeyFactory::ImportKey of one release called a virtual
 * method of an object another release had built, landed in PureSoftKeymasterContext's destructor and crashed the HAL.
 */
class KeymasterLibsTest {
    @get:Rule val tmp = TemporaryFolder()

    // ---- plan ---------------------------------------------------------------------------------------------------------

    private fun m(name: String, reachable: String?, platform: String?) = KeymasterLibs.Member(name, reachable, platform)

    @Test fun nothingInVendor_everythingIsBroughtFromThePlatform() {
        val p = KeymasterLibs.plan(listOf(m("a.so", null, "p1"), m("b.so", null, "p2")))
        assertEquals(listOf("a.so", "b.so"), p.take)
        assertTrue(p.overwrite.isEmpty())
    }

    @Test fun allTheirsAlready_nothingToDo() {
        val p = KeymasterLibs.plan(listOf(m("a.so", "p1", "p1"), m("b.so", "p2", "p2")))
        assertTrue(p.take.isEmpty())
    }

    @Test fun firmwaresOwnCompleteSet_isLeftAlone() {
        val p = KeymasterLibs.plan(listOf(m("a.so", "v1", "p1"), m("b.so", "v2", "p2")))
        assertTrue(p.take.isEmpty())
        assertTrue(p.verdict.contains("firmware's own"))
    }

    @Test fun partOfTheFirmwaresSetPlusMissingOnes_isAMixtureAndBecomesThePlatformsSet() {
        // portable/messages are the firmware's (QSEE keymaster), the soft device was missing and is brought: two releases
        val p = KeymasterLibs.plan(listOf(m("libkeymaster_portable.so", "v1", "p1"), m("libkeymaster_messages.so", "v2", "p2"), m("libsoftkeymasterdevice.so", null, "p3")))
        assertEquals(listOf("libsoftkeymasterdevice.so", "libkeymaster_portable.so", "libkeymaster_messages.so"), p.take)
        assertEquals(listOf("libkeymaster_portable.so", "libkeymaster_messages.so"), p.overwrite)
        assertTrue(p.verdict.startsWith("MIXED"))
    }

    @Test fun mixtureLeftByAnEarlierStart_isFoundWithoutAnyHistory() {
        // the soft device was copied by the earlier build (equal to the platform's), portable is still the firmware's
        val p = KeymasterLibs.plan(listOf(m("libkeymaster_portable.so", "v1", "p1"), m("libsoftkeymasterdevice.so", "p3", "p3")))
        assertEquals(listOf("libkeymaster_portable.so"), p.take)
        assertEquals(listOf("libkeymaster_portable.so"), p.overwrite)
    }

    @Test fun memberOnlyTheFirmwareHas_staysEvenInAMixture() {
        val p = KeymasterLibs.plan(listOf(m("libkeymaster3device.so", "v9", null), m("libkeymaster_portable.so", "v1", "p1"), m("libsoftkeymasterdevice.so", null, "p3")))
        assertFalse("libkeymaster3device.so" in p.take)
        assertEquals(listOf("libkeymaster_portable.so"), p.overwrite)
    }

    // ---- a tree on disk ------------------------------------------------------------------------------------------------

    /** A minimal ELF32 ARM shared object: DT_NEEDED [needed], dynamic symbols [defined] / [undefined]; [salt] makes the bytes differ. */
    private fun elf(needed: List<String>, defined: List<String> = emptyList(), undefined: List<String> = emptyList(), salt: String = ""): ByteArray {
        val strs = ByteArrayOutputStream().apply { write(0) }
        fun str(s: String): Int { val o = strs.size(); strs.write(s.toByteArray(Charsets.US_ASCII)); strs.write(0); return o }
        val neededOff = needed.map { str(it) }
        val defOff = defined.map { str(it) }
        val undOff = undefined.map { str(it) }
        val saltBytes = salt.toByteArray(Charsets.US_ASCII)
        val ehdr = 52; val phdrs = 2 * 32
        val dynstrOff = ehdr + phdrs
        val dynOff = dynstrOff + strs.size() + saltBytes.size
        val dynSize = (neededOff.size + 2) * 8
        val symOff = dynOff + dynSize
        val symSize = (1 + defined.size + undefined.size) * 16
        val shOff = symOff + symSize
        val total = shOff + 3 * 40
        val b = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN)
        b.put(byteArrayOf(0x7f, 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte(), 1, 1, 1))
        b.putShort(16, 3); b.putShort(18, 40); b.putInt(20, 1)
        b.putInt(28, ehdr); b.putInt(32, shOff)
        b.putShort(42, 32); b.putShort(44, 2); b.putShort(46, 40); b.putShort(48, 3); b.putShort(50, 2)
        // PT_LOAD over the whole file (va == file offset), PT_DYNAMIC
        b.position(ehdr)
        b.putInt(1); b.putInt(0); b.putInt(0); b.putInt(0); b.putInt(total); b.putInt(total); b.putInt(5); b.putInt(4)
        b.putInt(2); b.putInt(dynOff); b.putInt(dynOff); b.putInt(dynOff); b.putInt(dynSize); b.putInt(dynSize); b.putInt(6); b.putInt(4)
        b.position(dynstrOff); b.put(strs.toByteArray()); b.put(saltBytes)
        b.position(dynOff)
        for (o in neededOff) { b.putInt(1); b.putInt(o) }
        b.putInt(5); b.putInt(dynstrOff); b.putInt(0); b.putInt(0)
        b.position(symOff + 16)
        for (o in defOff) { b.putInt(o); b.putInt(0); b.putInt(0); b.put(0x12); b.put(0); b.putShort(1) }
        for (o in undOff) { b.putInt(o); b.putInt(0); b.putInt(0); b.put(0x12); b.put(0); b.putShort(0) }
        b.position(shOff + 40)                                                   // section 1: .dynsym, linked to section 2
        b.putInt(0); b.putInt(11); b.putInt(0); b.putInt(0); b.putInt(symOff); b.putInt(symSize); b.putInt(2); b.putInt(0); b.putInt(4); b.putInt(16)
        b.putInt(0); b.putInt(3); b.putInt(0); b.putInt(0); b.putInt(dynstrOff); b.putInt(strs.size()); b.putInt(0); b.putInt(0); b.putInt(1); b.putInt(0)
        return b.array()
    }

    private val impl = "vendor/lib/hw/android.hardware.keymaster@3.0-impl.so"

    private fun put(root: File, rel: String, bytes: ByteArray) { File(root, rel).apply { parentFile!!.mkdirs(); writeBytes(bytes) } }

    /** A tree whose platform (system/lib) has the keymaster family of one release, tagged [rel]; the HAL impl names the whole family. */
    private fun tree(): File {
        val root = tmp.newFolder()
        put(root, impl, elf(listOf("libkeymaster3device.so", "libsoftkeymasterdevice.so", "libkeymaster_portable.so"), defined = listOf("HIDL_FETCH_IKeymasterDevice")))
        put(root, "vendor/lib/libkeymaster3device.so", elf(listOf("libkeymaster_portable.so", "libkeymaster_messages.so"), undefined = listOf("_ZN9keymaster14AndroidKeymasterE")))
        for (n in listOf("liblog.so", "libc.so")) put(root, "system/lib/$n", elf(emptyList()))
        return root
    }

    private fun platformSet(root: File, rel: String) {
        put(root, "system/lib/libkeymaster_messages.so", elf(emptyList(), defined = listOf("_ZN9keymaster7MessageE"), salt = rel))
        put(root, "system/lib/libkeymaster_portable.so", elf(listOf("libkeymaster_messages.so"), defined = listOf("_ZN9keymaster14AndroidKeymasterE"), salt = rel))
        put(root, "system/lib/libsoftkeymasterdevice.so", elf(listOf("libkeymaster_portable.so", "libkeymaster_messages.so"), defined = listOf("_ZN9keymaster23PureSoftKeymasterContextE"), undefined = listOf("_ZN9keymaster14AndroidKeymasterE"), salt = rel))
    }

    private fun bytes(root: File, rel: String) = File(root, rel).readBytes()

    @Test fun freshTree_theWholeFamilyComesFromThePlatform() {
        val root = tree(); platformSet(root, "A14")
        val r = KeymasterLibs.complete(root) {}
        assertTrue(r.ok)
        assertTrue(r.replaced.isEmpty())
        for (n in listOf("libkeymaster_messages.so", "libkeymaster_portable.so", "libsoftkeymasterdevice.so"))
            assertTrue(n, bytes(root, "vendor/lib/$n").contentEquals(bytes(root, "system/lib/$n")))
    }

    @Test fun firmwareHasPartOfTheFamily_itsCopiesAreReplacedSoThatNothingIsMixed() {
        val root = tree(); platformSet(root, "A14")
        val own = elf(listOf("libkeymaster_messages.so"), defined = listOf("_ZN9keymaster14AndroidKeymasterE"), salt = "QSEE-A11")
        put(root, "vendor/lib/libkeymaster_portable.so", own)
        val log = ArrayList<String>()
        val r = KeymasterLibs.complete(root) { log += it }
        assertEquals(listOf("libkeymaster_portable.so"), r.replaced)
        for (n in listOf("libkeymaster_messages.so", "libkeymaster_portable.so", "libsoftkeymasterdevice.so"))
            assertTrue(n, bytes(root, "vendor/lib/$n").contentEquals(bytes(root, "system/lib/$n")))
        assertTrue("the firmware's file is kept", bytes(root, "system/.aemu-parked/vendor#lib#libkeymaster_portable.so").contentEquals(own))
        assertTrue(log.any { it.contains("MIXED") })
        assertTrue(r.ok)
    }

    @Test fun mixtureAnEarlierBuildLeftBehind_isRepairedOnTheNextStart() {
        val root = tree(); platformSet(root, "A14")
        // what the old per-library copy produced: the soft device and messages from the platform, portable the firmware's
        put(root, "vendor/lib/libkeymaster_portable.so", elf(listOf("libkeymaster_messages.so"), defined = listOf("_ZN9keymaster14AndroidKeymasterE"), salt = "QSEE-A11"))
        File(root, "system/lib/libsoftkeymasterdevice.so").copyTo(File(root, "vendor/lib/libsoftkeymasterdevice.so"))
        File(root, "system/lib/libkeymaster_messages.so").copyTo(File(root, "vendor/lib/libkeymaster_messages.so"))
        val r = KeymasterLibs.complete(root) {}
        assertEquals(listOf("libkeymaster_portable.so"), r.replaced)
        assertTrue(bytes(root, "vendor/lib/libkeymaster_portable.so").contentEquals(bytes(root, "system/lib/libkeymaster_portable.so")))
    }

    @Test fun secondStart_changesNothing() {
        val root = tree(); platformSet(root, "A14")
        put(root, "vendor/lib/libkeymaster_portable.so", elf(listOf("libkeymaster_messages.so"), defined = listOf("_ZN9keymaster14AndroidKeymasterE"), salt = "QSEE-A11"))
        KeymasterLibs.complete(root) {}
        val before = listOf("libkeymaster_messages.so", "libkeymaster_portable.so", "libsoftkeymasterdevice.so").map { bytes(root, "vendor/lib/$it") }
        val log = ArrayList<String>()
        val r = KeymasterLibs.complete(root) { log += it }
        assertTrue(r.replaced.isEmpty() && r.copied.isEmpty())
        assertFalse(log.any { it.contains("MIXED") })
        assertEquals(before.map { it.toList() }, listOf("libkeymaster_messages.so", "libkeymaster_portable.so", "libsoftkeymasterdevice.so").map { bytes(root, "vendor/lib/$it").toList() })
    }

    @Test fun aCompleteFirmwareSet_isNotTouched() {
        val root = tree(); platformSet(root, "A14")
        val own = mapOf(
            "libkeymaster_messages.so" to elf(emptyList(), defined = listOf("_ZN9keymaster7MessageE"), salt = "FW"),
            "libkeymaster_portable.so" to elf(listOf("libkeymaster_messages.so"), defined = listOf("_ZN9keymaster14AndroidKeymasterE"), salt = "FW"),
            "libsoftkeymasterdevice.so" to elf(listOf("libkeymaster_portable.so", "libkeymaster_messages.so"), defined = listOf("_ZN9keymaster23PureSoftKeymasterContextE"), undefined = listOf("_ZN9keymaster14AndroidKeymasterE"), salt = "FW"),
        )
        for ((n, b) in own) put(root, "vendor/lib/$n", b)
        val r = KeymasterLibs.complete(root) {}
        assertTrue(r.replaced.isEmpty() && r.copied.isEmpty())
        for ((n, b) in own) assertTrue(n, bytes(root, "vendor/lib/$n").contentEquals(b))
    }
}
