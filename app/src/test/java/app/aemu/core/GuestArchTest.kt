package app.aemu.core

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GuestArchTest {
    private fun elf(machine: Int, cls: Int = 1): ByteArray = ByteArray(64).also {
        it[0] = 0x7f; it[1] = 'E'.code.toByte(); it[2] = 'L'.code.toByte(); it[3] = 'F'.code.toByte()
        it[4] = cls.toByte(); it[5] = 1
        it[18] = (machine and 0xff).toByte(); it[19] = (machine shr 8).toByte()
    }

    private fun tree(machine: Int?, cls: Int = 1): File {
        val root = Files.createTempDirectory("guestarch").toFile()
        File(root, "system/lib").mkdirs()
        if (machine != null) File(root, "system/lib/libc.so").writeBytes(elf(machine, cls))
        return root
    }

    @Test fun x86ByElfEvenWhenAbiSaysArm() {
        assertEquals(GuestArch.X86, GuestArch.detect(tree(3), "armeabi-v7a"))
    }

    @Test fun armByElfEvenWhenAbiSaysX86() {
        assertEquals(GuestArch.ARM, GuestArch.detect(tree(40), "x86"))
    }

    @Test fun abiIsTheFallbackWhenThereIsNoElf() {
        assertEquals(GuestArch.X86, GuestArch.detect(tree(null), "x86"))
        assertEquals(GuestArch.ARM, GuestArch.detect(tree(null), "armeabi-v7a"))
        // (x86-64 is a firmware of its own now: translated by libqemu_x86_64.so on the native engine)
        assertEquals(GuestArch.X86_64, GuestArch.detect(tree(null), "x86_64"))
    }

    @Test fun x86_64ByElfClassAndMachine() {
        assertEquals(GuestArch.X86_64, GuestArch.detect(tree(62, 2), "armeabi-v7a"))
        // the ELF wins over the property here as well
        assertEquals(GuestArch.ARM, GuestArch.detect(tree(183, 2), "x86_64"))
    }

    /** a dual x86-64 firmware has a 32-bit x86 side too: its 64-bit side decides */
    @Test fun dualX86_64FirmwareIsNotTakenForX86() {
        val root = Files.createTempDirectory("dual").toFile()
        File(root, "system/lib").mkdirs(); File(root, "system/lib64").mkdirs(); File(root, "system/bin").mkdirs()
        File(root, "system/bin/app_process32").writeBytes(elf(3))
        File(root, "system/lib/libc.so").writeBytes(elf(3))
        File(root, "system/lib64/libc.so").writeBytes(elf(62, 2))
        assertEquals(GuestArch.X86_64, GuestArch.detect(root, "x86"))
        assertFalse(GuestArch.isUnsupported(root, "x86_64"))
    }

    @Test fun unsupportedMachines() {
        assertFalse(GuestArch.isUnsupported(tree(3), "x86"))
        assertFalse(GuestArch.isUnsupported(tree(40), "armeabi-v7a"))
        assertFalse(GuestArch.isUnsupported(tree(183, 2), "arm64-v8a"))
        assertFalse(GuestArch.isUnsupported(tree(62, 2), "x86_64"))
        assertTrue(GuestArch.isUnsupported(tree(8), "mips"))
        assertTrue(GuestArch.isUnsupported(tree(243, 2), "riscv64"))
        assertFalse(GuestArch.isUnsupported(tree(null), "x86_64"))
        assertTrue(GuestArch.isUnsupported(tree(null), "mips"))
    }

    @Test fun absoluteSymlinkIsNotFollowedToTheHost() {
        val root = tree(null)
        File(root, "system/bin").mkdirs()
        // the phone itself has /system/bin/sh, an arm64 file: it must not decide the guest's architecture
        Files.createSymbolicLink(File(root, "system/bin/sh").toPath(), File("/system/bin/sh").toPath())
        File(root, "system/lib/libc.so").writeBytes(elf(3))
        assertEquals(GuestArch.X86, GuestArch.detect(root, "armeabi-v7a"))
    }

    @Test fun noSmcOnlyOnArm() {
        assertTrue(GuestArch.ARM.supportsNoSmc)
        assertFalse(GuestArch.X86.supportsNoSmc)
    }

    @Test fun translators() {
        assertEquals("libqemu_kk.so", GuestArch.ARM.translator(Engine.KK))
        assertEquals("libqemu_kk_xperiaplay.so", GuestArch.ARM.translator(Engine.KK, true))
        assertEquals("libqemu_kk_i386.so", GuestArch.X86.translator(Engine.KK))
    }

    /** the translators the native engine's runner is given (AEMU64_QEMU / AEMU64_QEMU64), in analogy to libqemu_arm.so */
    @Test fun nativeEngineTranslators() {
        assertEquals("libqemu_arm.so", GuestArch.ARM.n64Lib32)
        assertEquals(null, GuestArch.ARM.n64Lib64)
        assertEquals(listOf("libqemu_i386.so"), GuestArch.X86.n64Translators)
        assertEquals(listOf("libqemu_i386.so", "libqemu_x86_64.so"), GuestArch.X86_64.n64Translators)
        assertFalse(GuestArch.ARM.foreign)
        assertTrue(GuestArch.X86.foreign)
        assertTrue(GuestArch.X86_64.foreign)
    }

    /** x86-32 from Oreo, x86-64 from Lollipop */
    @Test fun nativeEngineApiRanges() {
        assertFalse(GuestArch.X86.runsOnNative(25))
        assertTrue(GuestArch.X86.runsOnNative(26))
        assertFalse(GuestArch.X86_64.runsOnNative(20))
        assertTrue(GuestArch.X86_64.runsOnNative(21))
        assertTrue(GuestArch.X86_64.runsOnNative(37))
        assertTrue(GuestArch.ARM.runsOnNative(1))
    }

    private fun stored(api: Int, arch: GuestArch, abi: String) = GuestImage(
        id = "t", name = "t", release = "", api = api, brand = "", model = "", skin = "", engine = Engine.KK, abi = abi, arch = arch,
        bootclasspath = "", exports = emptyMap(), dirs = emptyList(), services = emptyList(), sdcardPath = "",
        settings = VmSettings(), createdAt = 0,
    )

    @Test fun engineByArchitecture() {
        // x86-32: the universal engine up to 7.x, the native one from 8.0
        for (api in listOf(15, 19, 21, 25)) assertEquals("x86 api $api", Engine.KK, stored(api, GuestArch.X86, "x86").effective().engine)
        for (api in listOf(26, 27, 28, 30, 34, 37)) assertEquals("x86 api $api", Engine.N64, stored(api, GuestArch.X86, "x86").effective().engine)
        // x86-64: the native engine from 5.0 on
        for (api in listOf(21, 22, 23, 25, 26, 29, 33, 37)) assertEquals("x86_64 api $api", Engine.N64, stored(api, GuestArch.X86_64, "x86_64").effective().engine)
        // unchanged: ARM
        assertEquals(Engine.KK, stored(19, GuestArch.ARM, "armeabi-v7a").effective().engine)
        assertEquals(Engine.N64, stored(28, GuestArch.ARM, "arm64-v8a").effective().engine)
        assertEquals(Engine.N64, stored(26, GuestArch.ARM, "armeabi-v7a").effective().engine)
    }

    @Test fun arm64FirmwareWithAnX86AbiPropertyStaysNative() {
        // (the arch is the ELF's word, not ro.product.cpu.abi's: detect() gives ARM for an arm64 tree whatever the property says)
        assertEquals(GuestArch.ARM, GuestArch.detect(tree(183, 2), "x86_64"))
    }

    @Test fun preloadOfAnotherArchitectureIsRecognized() {
        val d = Files.createTempDirectory("preload").toFile()
        val arm = File(d, "a.so").apply { writeBytes(elf(40)) }
        val x86 = File(d, "b.so").apply { writeBytes(elf(3)) }
        assertFalse(GuestArch.isOfArch(arm, GuestArch.X86))
        assertTrue(GuestArch.isOfArch(x86, GuestArch.X86))
        assertTrue(GuestArch.isOfArch(x86, GuestArch.X86_64))      // its 32-bit side
        assertFalse(GuestArch.isOfArch(arm, GuestArch.X86_64))
        assertFalse(GuestArch.isOfArch(File(d, "missing.so"), GuestArch.X86))
    }
}
